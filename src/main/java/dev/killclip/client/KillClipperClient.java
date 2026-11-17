package dev.killclip.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.killclip.core.HitTracker;
import dev.killclip.core.KillDeduplicator;
import dev.killclip.core.KillRules;
import dev.killclip.core.ServerDefaults;
import dev.killclip.core.StreakTracker;
import dev.killclip.core.VanillaDeathMessages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;
import net.minecraft.util.Language;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public final class KillClipperClient implements ClientModInitializer {
   private static KillClipperClient INSTANCE;
   public static final Logger LOGGER = LoggerFactory.getLogger("killclipper");
   private Language patternLanguage;
   private List<Pattern> decoratedPatterns = List.of();
   private final KillDeduplicator duplicates = new KillDeduplicator();
   private final HitTracker hits = new HitTracker();
   /** Deaths of players you hit, held a moment in case the chat gives the kill to someone else. */
   private final dev.killclip.core.PendingKills pendingDeaths = new dev.killclip.core.PendingKills(400L, 3000L);
   /** Recent chat lines and detection events, written to a file by /killclip report. */
   private final dev.killclip.core.DetectionLog detection = new dev.killclip.core.DetectionLog(200, 200);
   /** Nicknames shown in the player list, mapped to the real account names. */
   private final Map<String, String> aliases = new HashMap<>();
   /** Your own nickname in the player list, if the server shows one. */
   private String ownAlias = "";
   /** When you last typed a command that leaves the match (/lobby, /hub, /leave...). */
   private long leaveCommandMs = Long.MIN_VALUE / 2;
   private final Map<String, Long> lastHitLogged = new HashMap<>();
   private String lastClipFile = "";
   private long lastClipWallMs;
   /** Error notices already shown, so the same one is not repeated every few seconds. */
   private final Map<String, Long> warned = new HashMap<>();
   private static final java.util.regex.Pattern LEAVE_COMMAND = java.util.regex.Pattern.compile(
      "(?i)^(?:lobby|hub|l|leave|spawn|quit|forfeit|ff|surrender|server|kill|suicide|logout)\\b.*");
   private net.minecraft.client.option.KeyBinding reportMissedKey;
   private net.minecraft.client.option.KeyBinding reportWrongKey;
   /** How you last hit each player and from how far, for the highlight reel's kill details. */
   private final Map<String, LastHit> lastHits = new HashMap<>();
   /** Kills waiting for their clip, with their details. */
   private final List<Moment> moments = new ArrayList<>();

   private record LastHit(dev.killclip.core.ClipMeta.Cause cause, float distance) {
   }

   private record Moment(long timeMs, dev.killclip.core.ClipMeta.Cause cause, float health, float distance) {
   }
   private StreakTracker streaks = new StreakTracker(10000L);
   private ClipRecorder recorder;
   private ClipConfig config;
   private Map<String, List<Pattern>> serverPatterns = Map.of();
   private long captureUntil;
   private final List<String> captured = new ArrayList<>();
   private String captureHost = "";
   private int clipsSaved;
   private String worldIdentity = "";
   private final Map<String, Long> recentPlayers = new HashMap<>();
   private long nextPlayerRefresh;
   private boolean started;
   private static final double PROJECTILE_RANGE = 128.0;
   private static final long MAX_CLIP_MS = 120000L;
   private static final List<Pattern> UNIVERSAL_RULES = ServerDefaults.UNIVERSAL.stream().map(rule -> Pattern.compile(rule, Pattern.CASE_INSENSITIVE)).toList();
   private List<Pattern> allServerRules = List.of();
   private long lastSelfDeathMs = Long.MIN_VALUE / 2;
   /** When you last died (death screen or a death/lost chat line); clips never reach back past it. */
   private long ownDeathMs = Long.MIN_VALUE / 2;
   /** A clip saved because you died ends just before that death. */
   private long clipEndLimitMs = Long.MAX_VALUE;
   /** A player hit this recently counts as killed if the match ends and you are sent away while alive. */
   private static final long MATCH_END_HIT_MS = 5000L;
   private static final double EXPLOSION_RANGE = 12.0;
   private static final boolean DEV_SPIN = Boolean.getBoolean("killclip.devSpin");
   private static final long SELF_TEST_MS = Long.getLong("killclip.selfTestClipSeconds", 0L) * 1000L;
   private final long launchMs = nowMs();
   private boolean selfTestDone;
   private boolean devConnected;
   private boolean wasDead;
   final KillStats stats = KillStats.load();

   public static KillClipperClient instance() {
      return INSTANCE;
   }

   public void onInitializeClient() {
      INSTANCE = this;
      Path clipDir = FabricLoader.getInstance().getGameDir().resolve("killclips");
      ReplayCapture.init(clipDir.resolve(".replay"));
      net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
         if (screen instanceof net.minecraft.client.gui.screen.TitleScreen) {
            net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen).add(
               net.minecraft.client.gui.widget.ButtonWidget.builder(Text.literal("Clip Editor"), b -> client.setScreen(new ClipLibraryScreen(screen)))
                  .dimensions(4, 4, 80, 20).build());
         }
      });
      this.recorder = new ClipRecorder(clipDir.resolve(".buffer"), clipDir.resolve(".ffmpeg"), message -> {
         LOGGER.warn("Recorder: {}", message);
         MinecraftClient.getInstance().execute(() -> warn("Recorder: " + message));
      });

      try {
         this.applyConfig(ClipConfig.load());
      } catch (Exception e) {
         this.config = new ClipConfig();
         this.config.enabled = false;
         LOGGER.error("Kill clipping disabled: fix {} and use /killclip reload", ClipConfig.PATH, e);
      }

      ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
         if (!overlay) {
            this.onServerMessage(message);
         }
      });
      ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
         this.reset();
         this.refreshVanillaPatterns();
         if (ReplayPlayer.playing() && !ReplayPlayer.active().exporting()) {
            // A short hint above the hotbar instead of a chat line.
            client.inGameHud.setOverlayMessage(Text.literal("Replay: E opens the editor, Esc → Disconnect leaves"), false);
         }

         LOGGER.info("Joined {}: {}", this.host(), this.recordingSummary());
      });
      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
         this.finishCapture();
         this.reset();
         ReplayCapture.stop();
         ReplayPlayer.onDisconnected(handler.getConnection());
      });
      ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
         this.finishCapture();
         this.recorder.stop();
      });
      net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.COMMAND.register(command -> {
         if (LEAVE_COMMAND.matcher(command.strip()).matches()) {
            // Leaving a fight on purpose is not a win: hits just before it must not count as kills.
            this.leaveCommandMs = nowMs();
            this.detection.event(System.currentTimeMillis(), "you typed /" + command.split(" ")[0] + " (leaving: recent hits will not count as a win)");
         }
      });
      net.minecraft.client.option.KeyBinding.Category keys = net.minecraft.client.option.KeyBinding.Category.create(net.minecraft.util.Identifier.of("killclipper", "main"));
      this.reportMissedKey = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
         new net.minecraft.client.option.KeyBinding("key.killclipper.report_missed", net.minecraft.client.util.InputUtil.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN, keys));
      this.reportWrongKey = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
         new net.minecraft.client.option.KeyBinding("key.killclipper.report_wrong", net.minecraft.client.util.InputUtil.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN, keys));
      AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
         if (world.isClient() && entity instanceof PlayerEntity victim && victim != player) {
            this.hits.onHit(victim.getGameProfile().name(), nowMs());
            this.noteHit(victim.getGameProfile().name(), dev.killclip.core.ClipMeta.Cause.MELEE, player.distanceTo(victim));
         } else if (world.isClient() && entity instanceof EndCrystalEntity) {
            // Crystal PvP: everyone near the crystal you pop is in the blast.
            this.markPlayersNear(world, entity.getEntityPos(), player);
         }

         return ActionResult.PASS;
      });
      UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
         if (world.isClient()) {
            BlockState state = world.getBlockState(hitResult.getBlockPos());
            boolean anchor = state.isOf(Blocks.RESPAWN_ANCHOR);
            boolean bed = state.getBlock() instanceof BedBlock && world.getRegistryKey() != World.OVERWORLD;
            if (anchor || bed) {
               this.markPlayersNear(world, Vec3d.ofCenter(hitResult.getBlockPos()), player);
            }
         }

         return ActionResult.PASS;
      });
      ClientTickEvents.END_CLIENT_TICK.register(client -> {
         if (this.captureUntil != 0L && nowMs() >= this.captureUntil) {
            this.finishCapture();
         }

         this.trackProjectileHits(client);
         ReplayCapture.recordSelf(client);
         if (ReplayPlayer.active() != null) {
            ReplayPlayer.active().tick();
         }

         this.runDevReplay(client);
         DevEditorCheck.tick(client);
         DevReelCheck.tick(client);
         ReelAngles.tick(client);
         boolean dead = client.player != null && client.player.isDead();
         if (dead && !this.wasDead && !ReplayPlayer.playing() && client.world != null) {
            this.stats.death(this.host());
            this.onOwnDeath("death screen");
         }

         this.wasDead = dead;
         if (dead) {
            this.lastSelfDeathMs = nowMs();
         }

         this.checkHitPlayers(client);
         for (Map.Entry<String, Long> death : this.pendingDeaths.due(nowMs())) {
            this.onKillConfirmed(death.getKey(), "hit tracking", "", death.getValue());
         }

         while (this.reportMissedKey.wasPressed()) {
            this.report("missed");
         }

         while (this.reportWrongKey.wasPressed()) {
            this.report("wrong");
         }

         this.runSelfTest();
         this.streaks.flush(nowMs()).ifPresent(this::saveStreakClip);
         String identity = client.world == null ? "" : client.world.getRegistryKey().toString();
         if (!identity.equals(this.worldIdentity)) {
            this.onWorldLeave();
            this.duplicates.clear();
            this.streaks.clear();
            this.hits.clear();
            this.lastHits.clear();
            this.pendingDeaths.clear();
            this.worldIdentity = identity;
         }

         if (this.config.enabled && nowMs() >= this.nextPlayerRefresh) {
            this.nextPlayerRefresh = nowMs() + 500L;
            this.refreshPlayers(client);
         }
      });
      ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
         LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> root = ClientCommandManager.literal("killclip");
         root.executes(context -> this.status());
         root.then(ClientCommandManager.literal("menu").executes(context -> {
            MinecraftClient.getInstance().send(() -> MinecraftClient.getInstance().setScreen(new KillClipSettingsScreen(null)));
            return 1;
         }));
         root.then(ClientCommandManager.literal("status").executes(context -> this.status()));
         root.then(ClientCommandManager.literal("stats").executes(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.send(() -> client.setScreen(new StatsScreen(null)));
            return 1;
         }));
         root.then(ClientCommandManager.literal("on").executes(context -> this.setEnabled(true)));
         root.then(ClientCommandManager.literal("off").executes(context -> this.setEnabled(false)));
         root.then(ClientCommandManager.literal("reload").executes(context -> this.reload()));
         root.then(ClientCommandManager.literal("test").executes(context -> {
            this.testClip();
            return 1;
         }));
         root.then(ClientCommandManager.literal("folder").executes(context -> {
            this.openClipsFolder();
            return 1;
         }));
         root.then(ClientCommandManager.literal("report")
            .executes(context -> this.report("missed"))
            .then(ClientCommandManager.literal("missed").executes(context -> this.report("missed")))
            .then(ClientCommandManager.literal("wrong").executes(context -> this.report("wrong"))));
         root.then(ClientCommandManager.literal("learn").executes(context -> {
            if (this.captureUntil != 0L) {
               this.finishCapture();
            } else {
               this.captured.clear();
               this.captureHost = this.host();
               this.captureUntil = nowMs() + 60000L;
               tell("Recording up to 100 server messages locally for 60 seconds. Get a kill now; /killclip learn stops early. This can include private server messages.");
            }

            return 1;
         }));
         dispatcher.register(root);
      });
      this.started = true;
      LOGGER.info("Kill Clipper loaded for Minecraft 1.21.11; config: {}", ClipConfig.PATH);
   }

   /** Called by the render mixin after every frame. */
   /** Set once this frame was captured, so the end-of-frame fallback doesn't capture it twice. */
   private boolean capturedThisFrame;

   public void onFrameRenderedIfMissed(MinecraftClient client) {
      if (!this.capturedThisFrame) {
         this.onFrameRendered(client);
      }

      this.capturedThisFrame = false;
   }

   public void onFrameRendered(MinecraftClient client) {
      this.capturedThisFrame = true;
      this.recorder.onFrameRendered(client, this.config, nowMs());
      if (!ReplayPlayer.playing()) {
         if (DEV_SPIN && client.player != null) {
            // Dev check: wiggle the aim like PvP tracking so per-frame aim recording can be checked.
            client.player.setYaw((float)(40.0 * Math.sin(System.nanoTime() / 1.0E9 * 2.0 * Math.PI * 2.0)));
         }

         ReplayCapture.recordLook(client);
      }
   }

   private void onServerMessage(Text message) {
      if (ReplayPlayer.playing()) {
         return;
      }

      MinecraftClient client = MinecraftClient.getInstance();
      String text = message.getString();
      if (text.startsWith("[KillClip]")) {
         // The mod's own replies to commands.
         return;
      }

      if (this.captureUntil != 0L && this.captured.size() < 100) {
         this.captured.add(Instant.now() + " | " + translationKeys(message) + " | " + text.substring(0, Math.min(text.length(), 2048)).replace('\n', ' ').replace('\r', ' '));
      }

      String decision;
      try {
         decision = this.detect(message, client);
      } catch (RuntimeException e) {
         // A bad rule must never break chat.
         LOGGER.warn("Kill detection failed on a chat line", e);
         decision = "error: " + e;
      }

      this.detection.line(System.currentTimeMillis(), translationKeys(message), text, decision);
   }

   /** Reads one server chat line for kills and your own deaths, and says what it decided (for reports). */
   private String detect(Text message, MinecraftClient client) {
      if (!this.config.enabled) {
         return "clipping off";
      }

      if (client.player == null || client.getNetworkHandler() == null) {
         return "not in a world";
      }

      if (!this.allowed()) {
         return "server not in the list";
      }

      String local = client.player.getGameProfile().name();
      Set<String> online = this.recentPlayers.keySet();
      String plain = KillRules.aliasAsName(message.getString(), this.ownAlias, local);
      String otherCredit = this.creditOthers(message, plain, local, online);
      if (!KillRules.mentionsYou(plain, local)) {
         return otherCredit.isEmpty() ? "-" : otherCredit;
      }

      String named = KillRules.youAsName(plain, local, online);
      String rule = "vanilla death message";
      Optional<KillRules.Kill> kill = this.findTranslation(message, local, online);
      this.refreshVanillaPatterns();
      if (kill.isEmpty()) {
         kill = KillRules.fromMessage(named, this.decoratedPatterns, local, online);
      }

      if (kill.isEmpty()) {
         rule = "this server's rules";
         kill = KillRules.fromMessage(named, this.serverRules(this.host()), local, online);
      }

      if (kill.isEmpty()) {
         // Rules from every known server, then common kill-message shapes, on any server.
         rule = "known server rules";
         kill = KillRules.fromMessage(named, this.allServerRules, local, online);
      }

      if (kill.isEmpty()) {
         rule = "common kill wording";
         kill = KillRules.searchMessage(named, UNIVERSAL_RULES, local, online);
      }

      if (kill.isEmpty() && this.config.guessKills) {
         rule = "kill-word guess";
         kill = KillRules.guess(plain, local, online);
      }

      if (kill.isPresent()) {
         String victim = this.canonical(kill.get().victim());
         this.onKillConfirmed(victim, "chat, " + rule, plain, nowMs());
         return "KILL of " + victim + " (" + rule + ")";
      }

      if (this.isOwnDeathLine(message, local, online)) {
         this.onOwnDeath("chat");
         return "your death";
      }

      return otherCredit.isEmpty() ? "names you, not a kill" : otherCredit;
   }

   /**
    * When the chat gives the kill on a player you hit to someone else ("Bob was slain by Carl"), that death
    * is not yours: drop it from hit tracking. Only checked while you have recent hits, to keep chat cheap.
    */
   private String creditOthers(Text message, String plain, String local, Set<String> online) {
      if (!this.config.trackHits || this.pendingDeaths.isEmpty() && this.hits.recent(nowMs(), this.config.hitWindowMs).isEmpty()) {
         return "";
      }

      Optional<KillRules.Kill> credit = this.findAnyTranslation(message, online);
      if (credit.isEmpty()) {
         List<Pattern> exact = new ArrayList<>(this.decoratedPatterns);
         exact.addAll(this.allServerRules);
         credit = KillRules.anyKill(KillRules.youAsName(plain, local, online), exact, UNIVERSAL_RULES, online);
      }

      if (credit.isEmpty() || credit.get().killer().equalsIgnoreCase(local) || this.canonical(credit.get().killer()).equals(local)) {
         return "";
      }

      String victim = this.canonical(credit.get().victim());
      boolean dropped = this.pendingDeaths.creditedToOther(victim, nowMs());
      this.hits.forget(victim);
      this.lastHits.remove(victim);
      this.detection.event(System.currentTimeMillis(), victim + " was killed by " + credit.get().killer() + (dropped ? ": your hit does not count" : ""));
      return "kill by another player (" + credit.get().killer() + " on " + victim + ")";
   }

   private Optional<KillRules.Kill> findAnyTranslation(Text text, Set<String> online) {
      if (text.getContent() instanceof TranslatableTextContent translation) {
         List<String> args = Arrays.stream(translation.getArgs()).map(arg -> arg instanceof Text component ? component.getString() : String.valueOf(arg)).toList();
         Optional<KillRules.Kill> result = KillRules.anyKillFromTranslation(translation.getKey(), args, online);
         if (result.isPresent()) {
            return result;
         }
      }

      for (Text sibling : text.getSiblings()) {
         Optional<KillRules.Kill> result = this.findAnyTranslation(sibling, online);
         if (result.isPresent()) {
            return result;
         }
      }

      return Optional.empty();
   }

   /** The account name behind a nickname from the player list (or the name itself). */
   private String canonical(String name) {
      return this.aliases.getOrDefault(name, name);
   }

   /** Called by the death-status mixin when another player dies in front of you. */
   public void onPlayerDeathStatus(String name) {
      if (!this.config.enabled || !this.config.trackHits || !this.allowed()) {
         return;
      }

      if (this.hits.recent(nowMs(), this.config.hitWindowMs).contains(name)) {
         this.hits.forget(name);
         if (this.pendingDeaths.add(name, nowMs())) {
            this.detection.event(System.currentTimeMillis(), name + " died (death status) after your hit");
         }
      }
   }

   /** Every kill source (chat message or a death of someone you hit) ends up here; duplicates are dropped. */
   private void onKillConfirmed(String victim, String source) {
      this.onKillConfirmed(victim, source, "", nowMs());
   }

   private void onKillConfirmed(String victim, String source, String message, long receipt) {
      this.hits.forget(victim);
      this.pendingDeaths.remove(victim);
      LastHit hit = this.lastHits.remove(victim);
      if (this.duplicates.accept(victim, receipt, this.config.duplicateWindowMs)) {
         this.detection.event(System.currentTimeMillis(), "KILL counted: " + victim + " (" + source + ")");
         this.stats.kill(this.host());
         this.noteMoment(receipt, victim, hit, message);
         LOGGER.debug("Confirmed kill of {} from {}; grouping into streak at monotonic ms {}", victim, source, receipt);
         this.streaks.onKill(receipt).ifPresent(this::saveStreakClip);
      } else {
         this.detection.event(System.currentTimeMillis(), "same kill again, ignored: " + victim + " (" + source + ")");
      }
   }

   /** Remembers how a kill was made (cause, your health, the distance) for the highlight reel. */
   private void noteMoment(long timeMs, String victim, LastHit hit, String message) {
      MinecraftClient client = MinecraftClient.getInstance();
      dev.killclip.core.ClipMeta.Cause cause = message.isEmpty() ? dev.killclip.core.ClipMeta.Cause.OTHER : dev.killclip.core.ClipMeta.causeFromText(message);
      if (cause == dev.killclip.core.ClipMeta.Cause.OTHER && hit != null) {
         cause = hit.cause();
      }

      float distance = hit != null ? hit.distance() : 0.0F;
      float health = 0.0F;
      if (client.player != null && !client.player.isDead()) {
         health = client.player.getHealth() + client.player.getAbsorptionAmount();
         if (client.world != null && distance <= 0.0F) {
            for (PlayerEntity player : client.world.getPlayers()) {
               if (player != client.player && player.getGameProfile().name().equals(victim)) {
                  distance = client.player.distanceTo(player);
               }
            }
         }
      }

      if (this.moments.size() > 100) {
         this.moments.removeFirst();
      }

      this.moments.add(new Moment(timeMs, cause, health, distance));
   }

   /**
    * Called when the server moves you to another world or server, such as back to the lobby after a match.
    * Saves pending kills right away instead of dropping them. If you were alive and had just hit someone,
    * the match ended in your favour, so that player counts as killed even if their death was never shown.
    */
   private void onWorldLeave() {
      if (!this.config.enabled || ReplayPlayer.playing()) {
         return;
      }

      long now = nowMs();
      // Deaths still waiting for a possible "killed by someone else" line happened before you left: count them.
      for (Map.Entry<String, Long> death : this.pendingDeaths.drain()) {
         this.onKillConfirmed(death.getKey(), "hit tracking", "", death.getValue());
      }

      boolean leftOnPurpose = now - this.leaveCommandMs < 10000L;
      if (this.config.trackHits && now - this.lastSelfDeathMs > this.config.hitWindowMs && !leftOnPurpose) {
         for (String victim : this.hits.recent(now, MATCH_END_HIT_MS)) {
            this.onKillConfirmed(victim, "match end (sent away while alive right after hitting them)");
         }
      }

      if (!this.worldIdentity.isEmpty()) {
         this.detection.event(System.currentTimeMillis(), "left world " + this.worldIdentity + (leftOnPurpose ? " (after a leave command)" : ""));
      }

      this.streaks.finish().ifPresent(this::saveStreakClip);
   }

   /** Counts players near an explosion you set off (crystal, anchor, bed) as hit by you. */
   private void markPlayersNear(World world, Vec3d center, PlayerEntity self) {
      for (PlayerEntity other : world.getPlayers()) {
         if (other != self && other.squaredDistanceTo(center) <= EXPLOSION_RANGE * EXPLOSION_RANGE) {
            this.hits.onHit(other.getGameProfile().name(), nowMs());
            this.noteHit(other.getGameProfile().name(), dev.killclip.core.ClipMeta.Cause.EXPLOSION, self.distanceTo(other));
         }
      }
   }

   private void noteHit(String victim, dev.killclip.core.ClipMeta.Cause cause, float distance) {
      if (this.lastHits.size() > 200) {
         this.lastHits.clear();
      }

      long now = nowMs();
      Long logged = this.lastHitLogged.get(victim);
      if (logged == null || now - logged > 2000L) {
         if (this.lastHitLogged.size() > 200) {
            this.lastHitLogged.clear();
         }

         this.lastHitLogged.put(victim, now);
         this.detection.event(System.currentTimeMillis(), String.format("hit %s (%s, %.1f blocks)", victim, cause.name().toLowerCase(), distance));
      }

      this.lastHits.put(victim, new LastHit(cause, distance));
   }

   /** Counts a player as hit when one of your arrows, tridents or other projectiles reaches them. */
   private void trackProjectileHits(MinecraftClient client) {
      if (!this.config.trackHits || client.world == null || client.player == null) {
         return;
      }

      // Only projectiles near you can be yours in flight; scanning every loaded entity each tick is wasted work.
      for (Entity entity : client.world.getOtherEntities(client.player, client.player.getBoundingBox().expand(PROJECTILE_RANGE), e -> e instanceof ProjectileEntity)) {
         if (entity instanceof ProjectileEntity projectile && projectile.getOwner() == client.player) {
            for (PlayerEntity other : client.world.getPlayers()) {
               if (other != client.player && other.getBoundingBox().expand(1.0).intersects(projectile.getBoundingBox())) {
                  this.hits.onHit(other.getGameProfile().name(), nowMs());
                  this.noteHit(other.getGameProfile().name(), dev.killclip.core.ClipMeta.Cause.RANGED, client.player.distanceTo(other));
               }
            }
         }
      }
   }

   /**
    * Credits a kill when a player you hit recently is now dead, on any server. The cause of death does not
    * matter: lava, falling, fire, the void or anything else after your hit counts.
    */
   private void checkHitPlayers(MinecraftClient client) {
      if (!this.config.enabled || !this.config.trackHits || client.world == null || !this.allowed()) {
         return;
      }

      List<String> recent = this.hits.recent(nowMs(), this.config.hitWindowMs);
      if (recent.isEmpty()) {
         return;
      }

      for (PlayerEntity player : client.world.getPlayers()) {
         String name = player.getGameProfile().name();
         if (player != client.player && recent.contains(name) && (player.isDead() || player.getHealth() <= 0.0F)) {
            this.hits.forget(name);
            if (this.pendingDeaths.add(name, nowMs())) {
               this.detection.event(System.currentTimeMillis(), name + " died (health 0) after your hit");
            }
         }
      }
   }

   private boolean isOwnDeathLine(Text message, String local, Set<String> online) {
      if (this.hasOwnDeathTranslation(message, local)) {
         return true;
      }

      String plain = message.getString();
      return KillRules.isOwnDeath(plain, this.decoratedPatterns, local, online)
         || KillRules.isOwnDeath(plain, this.allServerRules, local, online);
   }

   private boolean hasOwnDeathTranslation(Text text, String local) {
      if (text.getContent() instanceof TranslatableTextContent translation) {
         List<String> args = Arrays.stream(translation.getArgs()).map(arg -> arg instanceof Text component ? component.getString() : String.valueOf(arg)).toList();
         if (KillRules.isOwnDeathTranslation(translation.getKey(), args, local)) {
            return true;
         }
      }

      return text.getSiblings().stream().anyMatch(sibling -> this.hasOwnDeathTranslation(sibling, local));
   }

   /**
    * You died: kills before this get their clip now, ending just before the death, and later clips start after
    * it, so a round you lost never shows up in a kill clip.
    */
   private void onOwnDeath(String source) {
      long now = nowMs();
      if (now - this.ownDeathMs < 1000L) {
         return;
      }

      LOGGER.debug("Own death from {}", source);
      this.detection.event(System.currentTimeMillis(), "you died (" + source + ")");
      this.ownDeathMs = now;
      this.clipEndLimitMs = now - 200L;
      try {
         this.streaks.finish().ifPresent(this::saveStreakClip);
      } finally {
         this.clipEndLimitMs = Long.MAX_VALUE;
      }
   }

   private Optional<KillRules.Kill> findTranslation(Text text, String local, Set<String> online) {
      if (text.getContent() instanceof TranslatableTextContent translation) {
         List<String> args = Arrays.stream(translation.getArgs()).map(arg -> arg instanceof Text component ? component.getString() : String.valueOf(arg)).toList();
         Optional<KillRules.Kill> result = KillRules.fromTranslation(translation.getKey(), args, local, online);
         if (result.isPresent()) {
            return result;
         }
      }

      for (Text sibling : text.getSiblings()) {
         Optional<KillRules.Kill> result = this.findTranslation(sibling, local, online);
         if (result.isPresent()) {
            return result;
         }
      }

      if (text.getContent() instanceof TranslatableTextContent translation && !translation.getKey().startsWith("death.")) {
         for (Object arg : translation.getArgs()) {
            if (arg instanceof Text component) {
               Optional<KillRules.Kill> result = this.findTranslation(component, local, online);
               if (result.isPresent()) {
                  return result;
               }
            }
         }
      }

      return Optional.empty();
   }

   private void refreshVanillaPatterns() {
      Language language = Language.getInstance();
      if (language != this.patternLanguage) {
         List<String> templates = new ArrayList<>(VanillaDeathMessages.englishTemplates().values());
         VanillaDeathMessages.englishTemplates().keySet().forEach(key -> {
            String localized = language.get(key);
            if (localized.contains("%1$s") && localized.contains("%2$s")) {
               templates.add(localized);
            }
         });
         this.decoratedPatterns = VanillaDeathMessages.compile(templates, true);
         this.patternLanguage = language;
      }
   }

   private static String translationKeys(Text text) {
      String result = text.getContent() instanceof TranslatableTextContent t ? t.getKey() : "literal";

      for (Text sibling : text.getSiblings()) {
         result = result + "," + translationKeys(sibling);
      }

      return result;
   }

   private void saveStreakClip(StreakTracker.Streak streak) {
      boolean isStreak = streak.kills() >= this.config.streakMinKills;
      if (isStreak) {
         this.stats.streak(this.host(), streak.kills());
      }

      String label = isStreak ? streak.kills() + "-kill-streak" : "kill";
      String title = isStreak ? streak.kills() + "-kill streak" : "Kill";
      // A clip saved because you died stops just before your death.
      long to = Math.max(streak.lastKillMs() + 300L, Math.min(streak.lastKillMs() + this.config.postRollMs, this.clipEndLimitMs));
      // Clips are at most 2 minutes; a longer streak keeps its end.
      long from = Math.max(streak.firstKillMs() - this.config.preRollMs, to - MAX_CLIP_MS);
      // Start after your last death (and respawn), so the round you lost isn't in the clip; keep 2 s before the kill.
      long afterDeath = Math.max(this.ownDeathMs, this.lastSelfDeathMs) + 500L;
      from = Math.max(from, Math.min(afterDeath, streak.firstKillMs() - 2000L));
      List<Moment> kills = new ArrayList<>();
      for (Moment moment : this.moments) {
         if (moment.timeMs() >= streak.firstKillMs() - 50L && moment.timeMs() <= streak.lastKillMs() + 50L) {
            kills.add(moment);
         }
      }

      this.moments.removeIf(moment -> moment.timeMs() <= streak.lastKillMs() + 50L);
      this.saveClip(from, to, streak.lastKillMs(), label, title, false, kills);
   }

   private void saveClip(long fromMs, long toMs, long killMs, String label, String title, boolean manual) {
      this.saveClip(fromMs, toMs, killMs, label, title, manual, List.of());
   }

   private void saveClip(long fromMs, long toMs, long killMs, String label, String title, boolean manual, List<Moment> kills) {
      if (manual || this.config.feedback) {
         notify("● Saving " + title.toLowerCase() + " clip...", Formatting.AQUA);
      }

      // Claim the fight's replay log now: by the time the video is saved, a win may have moved you to the lobby.
      dev.killclip.core.ReplayLog.Hold replay = this.config.saveReplays ? ReplayCapture.hold(killMs) : null;
      long videoLead = this.recorder.videoLeadMs(fromMs);
      this.recorder.save(fromMs, toMs, label, this.config.clipsDir(), this.config.fileFormat, file -> {
         this.clipsSaved++;
         this.lastClipFile = file;
         this.lastClipWallMs = System.currentTimeMillis();
         if (manual || this.config.feedback) {
            notify("✓ " + title + " clip saved", Formatting.GREEN);
         }

         LOGGER.info("Saved clip {}", file);
         this.saveMeta(file, fromMs, videoLead, kills);
         this.saveReplay(replay, fromMs, toMs, videoLead, file);
      }, error -> {
         if (replay != null) {
            replay.release();
         }

         warn("Clip failed: " + error);
      });
   }

   private void applyConfig(ClipConfig next) {
      Map<String, List<Pattern>> compiled = new HashMap<>();
      next.serverPatterns.forEach((server, patterns) -> compiled.put(KillRules.normalizeHost(server), patterns.stream().map(Pattern::compile).toList()));
      this.config = next;
      ReplayCapture.setEnabled(next.saveReplays);
      this.serverPatterns = compiled;
      Set<String> allRules = new java.util.LinkedHashSet<>();
      next.serverPatterns.values().forEach(allRules::addAll);
      ServerDefaults.patterns().values().forEach(allRules::addAll);
      this.allServerRules = allRules.stream().map(Pattern::compile).toList();
      boolean gameAudio = "game".equals(next.audioSource);
      if (gameAudio != GameAudioTap.wanted()) {
         GameAudioTap.setWanted(gameAudio);
         // Game audio needs the sound engine reopened on a loopback device (or back on the speakers).
         MinecraftClient client = MinecraftClient.getInstance();
         if (this.started && client != null && client.getSoundManager() != null) {
            client.execute(() -> client.getSoundManager().reloadSounds());
         }
      }

      this.duplicates.clear();
      this.streaks = new StreakTracker(next.streakGapMs);
   }

   private int setEnabled(boolean enabled) {
      boolean previous = this.config.enabled;
      this.config.enabled = enabled;

      try {
         this.config.save();
         this.duplicates.clear();
         tell("Automatic clipping " + (enabled ? "enabled" : "disabled") + ".");
      } catch (Exception e) {
         this.config.enabled = previous;
         tell("Could not save setting: " + e.getMessage());
      }

      return 1;
   }

   public ClipConfig settings() {
      return this.config.copy();
   }

   public String saveSettings(ClipConfig next) {
      try {
         next.save();
         this.applyConfig(next.copy());
         return "";
      } catch (Exception e) {
         return "Could not save settings: " + e.getMessage();
      }
   }

   private long devReplayStartMs;
   private int devReplayStep;

   /** Dev check: -Dkillclip.devReplay=file plays a replay and screenshots each camera mode. */
   private void runDevReplay(MinecraftClient client) {
      String file = System.getProperty("killclip.devReplay", "");
      if (file.isEmpty()) {
         return;
      }

      if (this.devReplayStep == 0 && client.currentScreen instanceof net.minecraft.client.gui.screen.TitleScreen) {
         this.devReplayStep = 1;
         try {
            ReplayPlayer.start(client, Path.of(file));
            LOGGER.info("KILLCLIP_DEVREPLAY started");
         } catch (Exception e) {
            LOGGER.error("KILLCLIP_DEVREPLAY failed to start", e);
         }
      } else if (this.devReplayStep == 1 && client.world != null && client.currentScreen == null) {
         this.devReplayStep = 2;
         this.devReplayStartMs = nowMs();
      } else if (this.devReplayStep >= 2 && this.devReplayStep <= 7 && ReplayPlayer.active() != null) {
         long since = nowMs() - this.devReplayStartMs;
         if (since >= (this.devReplayStep - 1) * 900L) {
            ReplayPlayer.CameraMode mode = ReplayPlayer.CameraMode.values()[(this.devReplayStep - 2) / 2];
            if (this.devReplayStep % 2 == 0) {
               ReplayPlayer.active().setMode(mode);
            } else {
               net.minecraft.entity.Entity cam = client.getCameraEntity();
               if (client.world != null) {
                  StringBuilder seen = new StringBuilder();
                  for (net.minecraft.entity.Entity e : client.world.getEntities()) {
                     seen.append(e.getClass().getSimpleName()).append('#').append(e.getId()).append('@').append(e.getBlockPos().toShortString()).append(' ');
                  }

                  LOGGER.info("KILLCLIP_DEVREPLAY {} entities: {} | {}", mode, client.worldRenderer.getEntitiesDebugString(), seen);
               }

               LOGGER.info("KILLCLIP_DEVREPLAY {} camera={} at {} player at {} hud hidden {} perspective {}", mode, cam == null ? null : cam.getClass().getSimpleName(),
                  cam == null ? null : cam.getEntityPos(), client.player == null ? null : client.player.getEntityPos(), client.options.hudHidden, client.options.getPerspective());
               net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(client.runDirectory, "replay-" + mode.name().toLowerCase() + ".png", client.getFramebuffer(), 1,
                  text -> LOGGER.info("KILLCLIP_DEVREPLAY {}: {}", mode, text.getString()));
            }

            this.devReplayStep++;
         }
      }
   }

   /** Dev check: -Dkillclip.selfTestClipSeconds=N saves a test clip N seconds after launch, without a world. */
   private void runSelfTest() {
      String devServer = System.getProperty("killclip.devConnect", "");
      MinecraftClient mc = MinecraftClient.getInstance();
      if (!devServer.isEmpty() && !this.devConnected && mc.currentScreen instanceof net.minecraft.client.gui.screen.TitleScreen) {
         // Dev check: join a local test server so the replay capture can be tested over a real connection.
         this.devConnected = true;
         net.minecraft.client.network.ServerInfo info = new net.minecraft.client.network.ServerInfo("dev", devServer, net.minecraft.client.network.ServerInfo.ServerType.OTHER);
         net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(mc.currentScreen, mc, net.minecraft.client.network.ServerAddress.parse(devServer), info, false, null);
      }

      if (SELF_TEST_MS > 0L && !this.selfTestDone && nowMs() - this.launchMs >= SELF_TEST_MS) {
         this.selfTestDone = true;
         long now = nowMs();
         this.recorder.save(now - 10000L, now, "selftest", this.config.clipsDir(), this.config.fileFormat,
            file -> LOGGER.info("KILLCLIP_SELFTEST_CLIP saved {}", file), error -> LOGGER.error("KILLCLIP_SELFTEST_CLIP failed: {}", error));
         ReplayCapture.save(now - 10000L, now, this.config.clipsDir().resolve("selftest.kcreplay")).whenComplete((path, error) -> {
            if (error != null) {
               LOGGER.info("KILLCLIP_SELFTEST_REPLAY none: {}", error.getMessage());
               return;
            }

            MinecraftClient.getInstance().execute(() -> {
               try {
                  LOGGER.info("KILLCLIP_SELFTEST_REPLAY {} bytes: {}", java.nio.file.Files.size(path),
                     ReplayCheck.verify(path, MinecraftClient.getInstance().world.getRegistryManager()));
               } catch (Exception e) {
                  LOGGER.error("KILLCLIP_SELFTEST_REPLAY failed", e);
               }
            });
         });
      }
   }

   /** Saves when each kill lands in the clip's video and how it was made, for the highlight reel. */
   private void saveMeta(String videoFile, long fromMs, long videoLead, List<Moment> kills) {
      if (kills.isEmpty()) {
         return;
      }

      List<dev.killclip.core.ClipMeta.Kill> details = new ArrayList<>();
      for (Moment moment : kills) {
         details.add(new dev.killclip.core.ClipMeta.Kill((moment.timeMs() - fromMs + videoLead) / 1000.0, moment.cause(), moment.health(), moment.distance()));
      }

      try {
         dev.killclip.core.ClipMeta.write(this.config.clipsDir().resolve(videoFile), details);
      } catch (Exception e) {
         LOGGER.warn("Could not save the kill details of {}: {}", videoFile, e.getMessage());
      }
   }

   private void saveReplay(dev.killclip.core.ReplayLog.Hold replay, long fromMs, long toMs, long videoLead, String videoFile) {
      if (replay == null) {
         if (this.config.saveReplays) {
            LOGGER.warn("Fight replay not saved: no server connection was recorded");
         }

         return;
      }

      int dot = videoFile.lastIndexOf('.');
      String name = dot > 0 ? videoFile.substring(0, dot) : videoFile;
      ReplayCapture.save(replay, fromMs, toMs, videoLead, this.config.clipsDir().resolve(name + ".kcreplay")).whenComplete((path, error) -> {
         if (error == null) {
            LOGGER.info("Saved fight replay {}", path.getFileName());
         } else {
            LOGGER.warn("Fight replay not saved: {}", error.getMessage());
         }
      });
   }

   /** The FFmpeg the recorder uses, or null while it is still being unpacked. */
   public String ffmpeg() {
      return this.recorder.ffmpegPath(this.config);
   }

   String encoderInUse() {
      return this.recorder.encoderFor(this.config);
   }

   /** An editor export was saved: tell the player and open it (or its 9:16 copy once that is made). */
   void onExportSaved(Path file, boolean vertical) {
      notify("✓ Edited clip saved: Edited/" + file.getFileName(), Formatting.GREEN);
      if (vertical) {
         this.makeVertical(file, this.config.openAfterExport);
      } else if (this.config.openAfterExport) {
         openVideo(file);
      }
   }

   /** Saves a 9:16 copy of a clip next to it (name_vertical.mp4). */
   void makeVertical(Path clip) {
      this.makeVertical(clip, false);
   }

   /** Opens a saved video in the system's video player. */
   static void openVideo(Path file) {
      try {
         Util.getOperatingSystem().open(file);
      } catch (Exception e) {
         LOGGER.warn("Could not open {}: {}", file, e.getMessage());
      }
   }

   void makeVertical(Path clip, boolean openWhenDone) {
      String ffmpeg = this.ffmpeg();
      if (ffmpeg == null) {
         return;
      }

      String name = clip.getFileName().toString();
      int dot = name.lastIndexOf('.');
      Path output = clip.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + "_vertical.mp4");
      notify("● Making the vertical (9:16) version...", Formatting.AQUA);
      java.util.concurrent.CompletableFuture.runAsync(() -> {
         try {
            dev.killclip.core.VideoTools.vertical(ffmpeg, clip, output);
            MinecraftClient.getInstance().execute(() -> {
               notify("✓ Vertical clip saved: " + output.getFileName(), Formatting.GREEN);
               if (openWhenDone) {
                  openVideo(output);
               }
            });
         } catch (Exception e) {
            MinecraftClient.getInstance().execute(() -> notify("✗ Vertical clip failed: " + e.getMessage(), Formatting.RED));
         }
      });
   }

   /** Saves the last 10 seconds right now. */
   public void testClip() {
      long now = nowMs();
      this.saveClip(now - 10000L, now, now, "test", "Test", true);
   }

   public void openClipsFolder() {
      this.openFolder(this.config.clipsDir());
   }

   public void openEditedFolder() {
      this.openFolder(this.config.editedDir());
   }

   private void openFolder(Path dir) {

      try {
         Files.createDirectories(dir);
         Util.getOperatingSystem().open(dir);
      } catch (Exception e) {
         tell("Clips are saved in " + dir.toAbsolutePath());
      }
   }

   private String recordingSummary() {
      if (!this.config.recording) {
         return "Recording is OFF";
      }

      String res = this.config.resolution == 0 ? "native" : this.config.resolution + "p";
      return "Recording " + res + " " + this.config.fps + " fps, last " + this.config.effectiveBufferSeconds() + " s buffered" + (this.config.bufferSeconds == 0 ? " (auto)" : "");
   }

   private static void notify(String message, Formatting color) {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client.player != null) {
         // Action bar text in the mod's colours: the leading mark (dot, tick, cross) coloured, the words soft white.
         int mark = color == Formatting.GREEN ? Ui.SUCCESS : color == Formatting.RED ? Ui.ERROR : Ui.ACCENT;
         int space = message.indexOf(' ');
         Text text = space == 1
            ? Text.literal(message.substring(0, 1)).withColor(mark & 0xFFFFFF).append(Text.literal(message.substring(1)).withColor(0xE6E8EB))
            : Text.literal(message).withColor(mark & 0xFFFFFF);
         client.player.sendMessage(text, true);
      }
   }

   private int reload() {
      try {
         this.applyConfig(ClipConfig.load());
         tell("Config reloaded.");
      } catch (Exception e) {
         tell("Reload failed; previous settings kept: " + e.getMessage());
      }

      return 1;
   }

   private int status() {
      tell("Automatic: " + (this.config.enabled ? "ON" : "OFF") + " | " + this.recordingSummary() + (this.recorder.running() ? "" : " (not running yet)")
         + " | server: " + this.host() + " | clips saved: " + this.clipsSaved);
      double repeated = this.recorder.repeatedPercent();
      if (repeated >= 0.0) {
         tell(String.format("Smoothness: %.1f%% of frames repeated, game at %d fps%s", repeated, MinecraftClient.getInstance().getCurrentFps(),
            repeated > 10.0 ? " (choppy: try a lower FPS or resolution)" : ""));
      }
      tell("Streak: " + this.config.streakMinKills + "+ kills within " + this.config.streakGapMs / 1000.0 + " s | clips folder: " + this.config.clipsDir().toAbsolutePath());
      return 1;
   }

   /** Chat rules for a host, including rules for its parent domains (nae.mcpvp.club uses mcpvp.club's). */
   private List<Pattern> serverRules(String host) {
      List<Pattern> rules = new ArrayList<>();
      this.serverPatterns.forEach((key, patterns) -> {
         boolean sameNetwork = host.equals(key) || host.endsWith("." + key)
            || (host.contains("mcpvp") && key.contains("mcpvp"));
         if (sameNetwork) {
            rules.addAll(patterns);
         }
      });
      return rules;
   }

   private boolean allowed() {
      // Never clip kills while watching a replay.
      return !ReplayPlayer.playing() && (!this.config.restrictToListedServers || KillRules.hostAllowed(this.host(), this.config.servers));
   }

   private String host() {
      ServerInfo server = MinecraftClient.getInstance().getCurrentServerEntry();
      return server == null ? "singleplayer" : KillRules.normalizeHost(server.address);
   }

   private void refreshPlayers(MinecraftClient client) {
      long now = nowMs();
      this.recentPlayers.entrySet().removeIf(entry -> now - entry.getValue() > 15000L);
      if (client.world != null) {
         client.world.getPlayers().forEach(player -> this.recentPlayers.put(player.getGameProfile().name(), now));
      }

      if (client.getNetworkHandler() != null) {
         String self = client.player == null ? "" : client.player.getGameProfile().name();
         client.getNetworkHandler().getPlayerList().forEach(info -> {
            String name = info.getProfile().name();
            if (this.recentPlayers.size() < 4096 || this.recentPlayers.containsKey(name)) {
               this.recentPlayers.put(name, now);
               // Servers with nicknames print the nickname in kill messages: learn it from the player list.
               String nick = info.getDisplayName() == null ? "" : nickname(info.getDisplayName().getString());
               if (nick.length() >= 3 && !nick.equals(name) && this.aliases.size() < 4096) {
                  if (name.equals(self)) {
                     this.ownAlias = nick;
                  } else if (!this.recentPlayers.containsKey(nick) || this.aliases.containsKey(nick)) {
                     this.aliases.put(nick, name);
                     this.recentPlayers.put(nick, now);
                  }
               }
            }
         });
      }
   }

   private void reset() {
      this.onWorldLeave();
      this.duplicates.clear();
      this.streaks.clear();
      this.hits.clear();
      this.lastHits.clear();
      this.pendingDeaths.clear();
      this.recentPlayers.clear();
      this.aliases.clear();
      this.ownAlias = "";
      this.nextPlayerRefresh = 0L;
      this.worldIdentity = "";
   }

   private static long nowMs() {
      return System.nanoTime() / 1000000L;
   }

   private void finishCapture() {
      if (this.captureUntil != 0L) {
         this.captureUntil = 0L;
         List<String> lines = new ArrayList<>();
         lines.add("Server: " + this.captureHost + " | Server messages only. Review before sharing.");
         lines.addAll(this.captured);
         this.captured.clear();
         Path path = FabricLoader.getInstance().getConfigDir().resolve("killclip-capture-" + System.currentTimeMillis() + ".txt");
         CompletableFuture.runAsync(() -> {
            try {
               Files.write(path, lines, StandardOpenOption.CREATE_NEW);
               MinecraftClient.getInstance().execute(() -> tell("Saved " + (lines.size() - 1) + " messages to " + path.getFileName()));
            } catch (Exception e) {
               LOGGER.error("Could not save kill notification capture", e);
            }
         });
      }
   }

   /** The last name-like word of a player-list display name ("[VIP] Bob ⚔ 12" gives "Bob"). */
   private static String nickname(String display) {
      java.util.regex.Matcher words = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])[A-Za-z0-9_]{3,16}(?![A-Za-z0-9_])").matcher(KillRules.clean(display));
      String last = "";
      while (words.find()) {
         if (!words.group().chars().allMatch(Character::isDigit)) {
            last = words.group();
         }
      }

      return last;
   }

   /**
    * A problem the player should know about, shown quietly above the hotbar (never in chat) and at most once
    * every 5 minutes for the same message. Always written to the log.
    */
   private void warn(String message) {
      LOGGER.warn("{}", message);
      long now = nowMs();
      Long shown = this.warned.get(message);
      if (shown != null && now - shown < 300000L) {
         return;
      }

      if (this.warned.size() > 50) {
         this.warned.clear();
      }

      this.warned.put(message, now);
      notify("✗ " + message, Formatting.RED);
   }

   /**
    * Saves what detection saw (recent chat with the decision for each line, hits, deaths, kills) to a file, so
    * a missed or wrong kill can be turned into a rule fix. A missed kill also gets its clip saved now.
    */
   private int report(String kind) {
      MinecraftClient client = MinecraftClient.getInstance();
      long wall = System.currentTimeMillis();
      long now = nowMs();
      if ("missed".equals(kind) && this.config.recording && client.player != null) {
         this.saveClip(now - Math.max(this.config.preRollMs, 15000L), now, now, "kill", "Missed kill", true);
      }

      Map<String, String> details = new java.util.LinkedHashMap<>();
      details.put("Mod version", FabricLoader.getInstance().getModContainer("killclipper").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
      details.put("Server", this.host());
      details.put("Your name", client.player == null ? "(not in a world)" : client.player.getGameProfile().name());
      details.put("Your nickname in the player list", this.ownAlias.isEmpty() ? "(none)" : this.ownAlias);
      details.put("Players known online", String.valueOf(this.recentPlayers.size()));
      details.put("Nicknames known", String.valueOf(this.aliases.size()));
      details.put("Clipping on", String.valueOf(this.config.enabled));
      details.put("Allowed on this server", String.valueOf(this.allowed()));
      details.put("Guess kills", String.valueOf(this.config.guessKills));
      details.put("Hit tracking", this.config.trackHits + " (window " + this.config.hitWindowMs + " ms)");
      details.put("Recent hits", this.hits.recent(now, this.config.hitWindowMs).toString());
      details.put("Deaths waiting", this.pendingDeaths.snapshot().keySet().toString());
      details.put("Rules for this server", String.valueOf(this.serverRules(this.host()).size()));
      details.put("Last clip saved", this.lastClipFile.isEmpty() ? "(none this session)" : this.lastClipFile + " (" + (wall - this.lastClipWallMs) / 1000L + " s ago)");
      details.put("Last own death", this.ownDeathMs < 0 ? "(none)" : (now - this.ownDeathMs) / 1000L + " s ago");
      List<String> lines = this.detection.report(kind, wall, details);
      Path dir = FabricLoader.getInstance().getGameDir().resolve("killclip-reports");
      Path file = dir.resolve("report-" + kind + "-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
      CompletableFuture.runAsync(() -> {
         try {
            Files.createDirectories(dir);
            Files.write(file, lines);
            client.execute(() -> {
               if (client.player != null) {
                  client.player.sendMessage(Text.literal("[KillClip] Report saved" + ("missed".equals(kind) ? " and the last 15 s clipped" : "") + ": ")
                     .append(Text.literal(file.getFileName().toString()).formatted(Formatting.UNDERLINE)
                        .styled(style -> style.withClickEvent(new net.minecraft.text.ClickEvent.OpenFile(dir))))
                     .append(Text.literal(". Send it so detection can be fixed (it holds recent chat, so read it first).")), false);
               }
            });
         } catch (Exception e) {
            LOGGER.error("Could not save the detection report", e);
            client.execute(() -> this.warn("Report not saved: " + e.getMessage()));
         }
      });
      return 1;
   }

   private static void tell(String message) {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client.player != null) {
         client.player.sendMessage(Text.literal("[KillClip] " + message), false);
      } else {
         LOGGER.info("{}", message);
      }
   }
}
