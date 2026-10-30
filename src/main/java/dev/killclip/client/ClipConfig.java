package dev.killclip.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.killclip.core.ServerDefaults;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

@Environment(EnvType.CLIENT)
public final class ClipConfig {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   public static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("killclipper.json");
   public static final Set<String> FORMATS = Set.of("mp4", "mov", "mkv");
   public static final Set<String> ENCODERS = Set.of("libx264", "h264_nvenc", "hevc_nvenc", "h264_amf", "h264_qsv");
   /** Bumped when a default changes so older config files pick it up. */
   public int configVersion = 0;
   public boolean enabled = true;
   public boolean feedback = true;
   public int duplicateWindowMs = 3000;
   public boolean trackHits = true;
   /** Count kill messages in unknown wording when they say you killed an online player. */
   public boolean guessKills = true;
   /** Also save each clip's fight as a replay, so the editor can show it from any camera. */
   public boolean saveReplays = true;
   public int hitWindowMs = 10000;
   public int streakMinKills = 2;
   public int streakGapMs = 10000;
   public boolean recording = true;
   public int fps = 60;
   public int resolution = 1080;
   public int quality = 23;
   public String encoder = "libx264";
   /** Video file type for saved clips. */
   public String fileFormat = "mp4";
   /** Discord webhook URL for "Share to Discord" (kept only in this config file). */
   public String discordWebhook = "";
   /** Seconds of video kept ready to clip; 0 picks it from the clip settings. */
   public int bufferSeconds = 0;
   public String audioSource = "game";
   public String audioDevice = "";
   public int preRollMs = 5000;
   public int postRollMs = 2000;
   public String clipsFolder = "";
   public String ffmpegPath = "";
   public boolean restrictToListedServers = false;
   public List<String> servers = ServerDefaults.hosts();
   public Map<String, List<String>> serverPatterns = ServerDefaults.patterns();

   public static ClipConfig load() throws IOException {
      if (!Files.exists(PATH)) {
         ClipConfig config = new ClipConfig();
         config.configVersion = 3;
         config.save();
         return config;
      } else {
         try (BufferedReader reader = Files.newBufferedReader(PATH)) {
            ClipConfig config = (ClipConfig)GSON.fromJson(reader, ClipConfig.class);
            if (config == null) {
               throw new IllegalArgumentException("Config must be a JSON object");
            }

            config.validate();
            if (config.configVersion < 2) {
               // 1.1.4: longer hit window so lava and fall deaths after a hit still count.
               config.hitWindowMs = Math.max(config.hitWindowMs, 10000);
               config.configVersion = 2;
               config.save();
            }

            if (config.configVersion < 3) {
               // 1.5.0: the buffer sizes itself to the clip settings.
               config.bufferSeconds = 0;
               config.configVersion = 3;
               config.save();
            }

            return config;
         } catch (RuntimeException e) {
            throw new IOException("Invalid config: " + e.getMessage(), e);
         }
      }
   }

   public Path clipsDir() {
      return this.clipsFolder.isBlank() ? FabricLoader.getInstance().getGameDir().resolve("killclips") : Path.of(this.clipsFolder);
   }

   public void validate() {
      require(this.streakMinKills >= 2 && this.streakMinKills <= 20, "streakMinKills must be 2..20");
      require(this.streakGapMs >= 1000 && this.streakGapMs <= 60000, "streakGapMs must be 1000..60000");
      require(this.duplicateWindowMs >= 100 && this.duplicateWindowMs <= 3000, "duplicateWindowMs must be 100..3000");
      require(this.hitWindowMs >= 1000 && this.hitWindowMs <= 30000, "hitWindowMs must be 1000..30000");
      require(this.fps >= 15 && this.fps <= 120, "fps must be 15..120");
      require(this.resolution == 0 || this.resolution >= 240 && this.resolution <= 2160, "resolution must be 0 (native) or 240..2160");
      require(this.quality >= 0 && this.quality <= 51, "quality must be 0..51 (lower is better)");
      require(this.encoder != null && ENCODERS.contains(this.encoder), "encoder must be one of " + ENCODERS);
      require(this.fileFormat != null && FORMATS.contains(this.fileFormat), "fileFormat must be one of " + FORMATS);
      require(this.bufferSeconds == 0 || this.bufferSeconds >= 10 && this.bufferSeconds <= 300, "bufferSeconds must be 0 (auto) or 10..300");
      require(Set.of("off", "game", "system").contains(this.audioSource), "audioSource must be off, game or system");
      require(this.preRollMs >= 0 && this.preRollMs <= 120000, "preRollMs must be 0..120000");
      require(this.postRollMs >= 0 && this.postRollMs <= this.streakGapMs, "postRollMs must be 0..streakGapMs");
      require(this.clipsFolder != null, "clipsFolder must be a path or empty for the default");
      require(this.ffmpegPath != null, "ffmpegPath must be a path, or empty for the built-in FFmpeg");
      require(this.servers != null && this.servers.stream().noneMatch(s -> s == null || s.isBlank()), "servers must contain hostnames");
      require(this.serverPatterns != null && this.serverPatterns.size() <= 100, "Invalid serverPatterns");
      this.serverPatterns.forEach((host, rules) -> {
         require(host != null && rules != null && rules.size() <= 20, "Invalid pattern list");
         for (String rule : rules) {
            require(rule != null && rule.length() <= 512 && rule.contains("(?<killer>") && rule.contains("(?<victim>"),
               "Each pattern needs named killer and victim groups; max 512 characters");
            Map<String, Integer> groups = Pattern.compile(rule).namedGroups();
            require(groups.containsKey("killer") && groups.containsKey("victim"), "Pattern must define actual killer and victim capture groups");
         }
      });
   }

   private static void require(boolean ok, String message) {
      if (!ok) {
         throw new IllegalArgumentException(message);
      }
   }

   public void save() throws IOException {
      this.validate();
      Files.createDirectories(PATH.getParent());
      Path temp = Files.createTempFile(PATH.getParent(), "killclip-", ".tmp");

      try {
         Files.writeString(temp, GSON.toJson(this) + System.lineSeparator());

         try {
            Files.move(temp, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, PATH, StandardCopyOption.REPLACE_EXISTING);
         }
      } finally {
         Files.deleteIfExists(temp);
      }
   }

   /** Longest streak the automatic buffer leaves room for, after the first kill. */
   static final int AUTO_STREAK_SECONDS = 60;

   /**
    * The buffer actually used. Auto keeps enough for the longest clip the settings can make: the time before
    * the kill, a streak of up to a minute, and the time after, so a 2-minute clip is always possible.
    */
   public int effectiveBufferSeconds() {
      if (this.bufferSeconds > 0) {
         return this.bufferSeconds;
      }

      int seconds = (this.preRollMs + this.postRollMs + 999) / 1000 + AUTO_STREAK_SECONDS + 5;
      return Math.max(30, Math.min(300, seconds));
   }

   public ClipConfig copy() {
      return (ClipConfig)GSON.fromJson(GSON.toJson(this), ClipConfig.class);
   }
}
