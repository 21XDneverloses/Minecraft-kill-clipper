package dev.killclip.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.killclip.core.HitTracker;
import dev.killclip.core.KillDeduplicator;
import dev.killclip.core.KillRules;
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
   private List<Pattern> vanillaPatterns = List.of();
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
   private static final long SELF_TEST_MS = Long.getLong("killclip.selfTestClipSeconds", 0L) * 1000L;
   private final long launchMs = nowMs();
   private boolean selfTestDone;

   public static KillClipperClient instance() {
      return INSTANCE;
   }

   public void onInitializeClient() {
      INSTANCE = this;
      Path clipDir = FabricLoader.getInstance().getGameDir().resolve("killclips");
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
         tell("Loaded. " + this.recordingSummary() + ". Use /killclip status, /killclip test or /killclip menu.");
      });
      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
         this.finishCapture();
         this.reset();
      });
      ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
         this.finishCapture();
         this.recorder.stop();
      });
      AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
         if (world.isClient() && entity instanceof PlayerEntity victim && victim != player) {
            this.hits.onHit(victim.getGameProfile().name(), nowMs());
         }

         return ActionResult.PASS;
      });
      ClientTickEvents.END_CLIENT_TICK.register(client -> {
         if (this.captureUntil != 0L && nowMs() >= this.captureUntil) {
            this.finishCapture();
         }

         this.checkHitPlayers(client);
         this.runSelfTest();
         this.streaks.flush(nowMs()).ifPresent(this::saveStreakClip);
         String identity = client.world == null ? "" : client.world.getRegistryKey().toString();
         if (!identity.equals(this.worldIdentity)) {
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
   }

   private void onServerMessage(Text message) {
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
               kill = KillRules.fromMessage(message.getString(), this.host().equals("mcpvp.com") ? this.decoratedPatterns : this.vanillaPatterns, local, online);
            }

            if (kill.isEmpty()) {
               kill = KillRules.fromMessage(message.getString(), this.serverPatterns.getOrDefault(this.host(), List.of()), local, online);
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
         LOGGER.debug("Confirmed kill of {} from {}; grouping into streak at monotonic ms {}", victim, source, receipt);
         this.streaks.onKill(receipt).ifPresent(this::saveStreakClip);
      }
   }

   /** Credits a kill when a player you hit recently is now dead, on any server. */
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
         this.vanillaPatterns = VanillaDeathMessages.compile(templates, false);
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
      String label = isStreak ? streak.kills() + "-kill-streak" : "kill";
      String title = isStreak ? streak.kills() + "-kill streak" : "Kill";
      this.saveClip(streak.firstKillMs() - this.config.preRollMs, streak.lastKillMs() + this.config.postRollMs, label, title, false);
   }

   private void saveClip(long fromMs, long toMs, String label, String title, boolean manual) {
      if (manual || this.config.feedback) {
         notify("● Saving " + title.toLowerCase() + " clip...", Formatting.AQUA);
      }

      this.recorder.save(fromMs, toMs, label, this.config.clipsDir(), file -> {
         this.clipsSaved++;
         if (manual || this.config.feedback) {
            notify("✓ " + title + " clip saved", Formatting.GREEN);
         }

         LOGGER.info("Saved clip {}", file);
      }, error -> {
         notify("✗ Clip failed: " + error, Formatting.RED);
         LOGGER.warn("Clip failed: {}", error);
      });
   }

   private void applyConfig(ClipConfig next) {
      Map<String, List<Pattern>> compiled = new HashMap<>();
      next.serverPatterns.forEach((server, patterns) -> compiled.put(KillRules.normalizeHost(server), patterns.stream().map(Pattern::compile).toList()));
      this.config = next;
      this.serverPatterns = compiled;
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

   /** Dev check: -Dkillclip.selfTestClipSeconds=N saves a test clip N seconds after launch, without a world. */
   private void runSelfTest() {
      if (SELF_TEST_MS > 0L && !this.selfTestDone && nowMs() - this.launchMs >= SELF_TEST_MS) {
         this.selfTestDone = true;
         long now = nowMs();
         this.recorder.save(now - 10000L, now, "selftest", this.config.clipsDir(),
            file -> LOGGER.info("KILLCLIP_SELFTEST_CLIP saved {}", file), error -> LOGGER.error("KILLCLIP_SELFTEST_CLIP failed: {}", error));
      }
   }

   /** Saves the last 10 seconds right now. */
   public void testClip() {
      long now = nowMs();
      this.saveClip(now - 10000L, now, "test", "Test", true);
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
      return "Recording " + res + " " + this.config.fps + " fps, last " + this.config.bufferSeconds + " s buffered";
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
      tell("Streak: " + this.config.streakMinKills + "+ kills within " + this.config.streakGapMs / 1000.0 + " s | clips folder: " + this.config.clipsDir().toAbsolutePath());
      return 1;
   }

   private boolean allowed() {
      return !this.config.restrictToListedServers || KillRules.hostAllowed(this.host(), this.config.servers);
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
