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
         MinecraftClient.getInstance().execute(() -> tell("Recorder: " + message));
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
            tell("Replay: E opens the editor (H there shows all keys). Esc → Disconnect to leave.");
         } else {
            tell("Loaded. " + this.recordingSummary() + ". Use /killclip status, /killclip test or /killclip menu.");
         }
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
      AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
         if (world.isClient() && entity instanceof PlayerEntity victim && victim != player) {
            this.hits.onHit(victim.getGameProfile().name(), nowMs());
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
         boolean dead = client.player != null && client.player.isDead();
         if (dead && !this.wasDead && !ReplayPlayer.playing() && client.world != null) {
            this.stats.death(this.host());
         }

         this.wasDead = dead;
         if (dead) {
            this.lastSelfDeathMs = nowMs();
         }

         this.checkHitPlayers(client);
         this.runSelfTest();
         this.streaks.flush(nowMs()).ifPresent(this::saveStreakClip);
         String identity = client.world == null ? "" : client.world.getRegistryKey().toString();
         if (!identity.equals(this.worldIdentity)) {
            this.onWorldLeave();
            this.duplicates.clear();
            this.streaks.clear();
            this.hits.clear();
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
   public void onFrameRendered(MinecraftClient client) {
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
      if (this.captureUntil != 0L && this.captured.size() < 100) {
         String text = message.getString();
         this.captured.add(Instant.now() + " | " + translationKeys(message) + " | " + text.substring(0, Math.min(text.length(), 2048)).replace('\n', ' ').replace('\r', ' '));
      }

      if (this.config.enabled && client.player != null && client.getNetworkHandler() != null && this.allowed()) {
         String local = client.player.getGameProfile().name();
         String plain = message.getString();
         if (plain.contains(local)) {
            Set<String> online = this.recentPlayers.keySet();
            Optional<KillRules.Kill> kill = this.findTranslation(message, local, online);
            this.refreshVanillaPatterns();
            if (kill.isEmpty()) {
               kill = KillRules.fromMessage(message.getString(), this.decoratedPatterns, local, online);
            }

            if (kill.isEmpty()) {
               kill = KillRules.fromMessage(message.getString(), this.serverRules(this.host()), local, online);
            }

            if (kill.isEmpty()) {
               // Rules from every known server, then common kill-message shapes, on any server.
               kill = KillRules.fromMessage(message.getString(), this.allServerRules, local, online);
            }

            if (kill.isEmpty()) {
               kill = KillRules.searchMessage(message.getString(), UNIVERSAL_RULES, local, online);
            }

            if (kill.isEmpty() && this.config.guessKills) {
               kill = KillRules.guess(message.getString(), local, online);
            }

            kill.ifPresent(event -> this.onKillConfirmed(event.victim(), "chat"));
         }
      }
   }

   /** Every kill source (chat message or a death of someone you hit) ends up here; duplicates are dropped. */
   private void onKillConfirmed(String victim, String source) {
      long receipt = nowMs();
      this.hits.forget(victim);
      if (this.duplicates.accept(victim, receipt, this.config.duplicateWindowMs)) {
         this.stats.kill(this.host());
         LOGGER.debug("Confirmed kill of {} from {}; grouping into streak at monotonic ms {}", victim, source, receipt);
         this.streaks.onKill(receipt).ifPresent(this::saveStreakClip);
      }
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
      if (this.config.trackHits && now - this.lastSelfDeathMs > this.config.hitWindowMs) {
         for (String victim : this.hits.recent(now, MATCH_END_HIT_MS)) {
            this.onKillConfirmed(victim, "match end");
         }
      }

      this.streaks.finish().ifPresent(this::saveStreakClip);
   }

   /** Counts players near an explosion you set off (crystal, anchor, bed) as hit by you. */
   private void markPlayersNear(World world, Vec3d center, PlayerEntity self) {
      for (PlayerEntity other : world.getPlayers()) {
         if (other != self && other.squaredDistanceTo(center) <= EXPLOSION_RANGE * EXPLOSION_RANGE) {
            this.hits.onHit(other.getGameProfile().name(), nowMs());
         }
      }
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
            this.onKillConfirmed(name, "hit tracking");
         }
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
      long to = streak.lastKillMs() + this.config.postRollMs;
      // Clips are at most 2 minutes; a longer streak keeps its end.
      long from = Math.max(streak.firstKillMs() - this.config.preRollMs, to - MAX_CLIP_MS);
      this.saveClip(from, to, streak.lastKillMs(), label, title, false);
   }

   private void saveClip(long fromMs, long toMs, long killMs, String label, String title, boolean manual) {
      if (manual || this.config.feedback) {
         notify("● Saving " + title.toLowerCase() + " clip...", Formatting.AQUA);
      }

      // Claim the fight's replay log now: by the time the video is saved, a win may have moved you to the lobby.
      dev.killclip.core.ReplayLog.Hold replay = this.config.saveReplays ? ReplayCapture.hold(killMs) : null;
      this.recorder.save(fromMs, toMs, label, this.config.clipsDir(), this.config.fileFormat, file -> {
         this.clipsSaved++;
         if (manual || this.config.feedback) {
            notify("✓ " + title + " clip saved", Formatting.GREEN);
         }

         LOGGER.info("Saved clip {}", file);
         this.saveReplay(replay, fromMs, toMs, file);
      }, error -> {
         if (replay != null) {
            replay.release();
         }

         notify("✗ Clip failed: " + error, Formatting.RED);
         LOGGER.warn("Clip failed: {}", error);
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

   private void saveReplay(dev.killclip.core.ReplayLog.Hold replay, long fromMs, long toMs, String videoFile) {
      if (replay == null) {
         if (this.config.saveReplays) {
            LOGGER.warn("Fight replay not saved: no server connection was recorded");
         }

         return;
      }

      int dot = videoFile.lastIndexOf('.');
      String name = dot > 0 ? videoFile.substring(0, dot) : videoFile;
      ReplayCapture.save(replay, fromMs, toMs, this.config.clipsDir().resolve(name + ".kcreplay")).whenComplete((path, error) -> {
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

   /** Saves an exported replay edit: the recorder captured it while it played. */
   void exportEdit(long fromMs, long toMs, Path replay, String format, boolean vertical, java.util.function.Consumer<String> status) {
      long length = toMs - fromMs;
      if (length > this.config.effectiveBufferSeconds() * 1000L) {
         String message = "Export failed: it is " + length / 1000L + " s but the buffer only keeps " + this.config.effectiveBufferSeconds() + " s. Raise Buffer in the settings.";
         notify("✗ " + message, Formatting.RED);
         status.accept(message);
         return;
      }

      if (!this.recorder.running()) {
         String message = "Export failed: recording is off or FFmpeg isn't running (see /killclip status).";
         notify("✗ " + message, Formatting.RED);
         status.accept(message);
         return;
      }

      String name = replay.getFileName().toString().replace(".kcreplay", "");
      status.accept("Saving the export...");
      // No "saving" message on screen here: it would end up in the last frames of the export.
      this.recorder.save(fromMs, toMs, "edit_" + name.replaceAll("^\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_", ""), this.config.clipsDir(), format,
         file -> {
            notify("✓ Edited clip saved: " + file, Formatting.GREEN);
            ReplayPlayer.exportedFile = this.config.clipsDir().resolve(file);
            status.accept("Saved " + file + " in your clips folder.");
            if (vertical) {
               this.makeVertical(this.config.clipsDir().resolve(file));
            }
         },
         error -> {
            notify("✗ Export failed: " + error, Formatting.RED);
            status.accept("Export failed: " + error);
         });
   }

   /** Saves a 9:16 copy of a clip next to it (name_vertical.mp4). */
   void makeVertical(Path clip) {
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
            MinecraftClient.getInstance().execute(() -> notify("✓ Vertical clip saved: " + output.getFileName(), Formatting.GREEN));
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
      Path dir = this.config.clipsDir();

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
         client.player.sendMessage(Text.literal(message).formatted(color), true);
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
         client.getNetworkHandler().getPlayerList().forEach(info -> {
            if (this.recentPlayers.size() < 4096 || this.recentPlayers.containsKey(info.getProfile().name())) {
               this.recentPlayers.put(info.getProfile().name(), now);
            }
         });
      }
   }

   private void reset() {
      this.onWorldLeave();
      this.duplicates.clear();
      this.streaks.clear();
      this.hits.clear();
      this.recentPlayers.clear();
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

   private static void tell(String message) {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client.player != null) {
         client.player.sendMessage(Text.literal("[KillClip] " + message), false);
      } else {
         LOGGER.info("{}", message);
      }
   }
}
