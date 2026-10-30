package dev.killclip.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

/** Your kill stats, overall and per server, saved in config/killclipper-stats.json on this PC only. */
@Environment(EnvType.CLIENT)
public final class KillStats {
   private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("killclipper-stats.json");
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

   /** Counts for one server (or for everything). */
   public static final class Line {
      public int kills;
      public int deaths;
      /** Multi-kills that saved as a streak clip. */
      public int streaks;
      /** Most kills in one multi-kill. */
      public int bestStreak;
      /** Most kills in one life, without dying. */
      public int bestLife;
      transient int life;

      public double kd() {
         return this.deaths == 0 ? this.kills : (double)this.kills / this.deaths;
      }

      void kill() {
         this.kills++;
         this.life++;
         this.bestLife = Math.max(this.bestLife, this.life);
      }

      void death() {
         this.deaths++;
         this.life = 0;
      }

      void streak(int kills) {
         this.streaks++;
         this.bestStreak = Math.max(this.bestStreak, kills);
      }
   }

   private static final class Saved {
      Line total = new Line();
      Map<String, Line> servers = new LinkedHashMap<>();
   }

   private Saved data = new Saved();

   static KillStats load() {
      KillStats stats = new KillStats();
      if (Files.isRegularFile(PATH)) {
         try {
            Saved saved = GSON.fromJson(Files.readString(PATH), Saved.class);
            if (saved != null) {
               if (saved.total == null) {
                  saved.total = new Line();
               }

               if (saved.servers == null) {
                  saved.servers = new LinkedHashMap<>();
               }

               stats.data = saved;
            }
         } catch (IOException | RuntimeException e) {
            KillClipperClient.LOGGER.warn("Could not read stats {}", PATH, e);
         }
      }

      return stats;
   }

   private void save() {
      try {
         Files.createDirectories(PATH.getParent());
         Files.writeString(PATH, GSON.toJson(this.data));
      } catch (IOException e) {
         KillClipperClient.LOGGER.warn("Could not save stats {}", PATH, e);
      }
   }

   private Line server(String host) {
      return this.data.servers.computeIfAbsent(host, h -> new Line());
   }

   void kill(String host) {
      this.data.total.kill();
      this.server(host).kill();
      this.save();
   }

   void death(String host) {
      this.data.total.death();
      this.server(host).death();
      this.save();
   }

   void streak(String host, int kills) {
      this.data.total.streak(kills);
      this.server(host).streak(kills);
      this.save();
   }

   public Line total() {
      return this.data.total;
   }

   /** Servers with the most kills first. */
   public List<Map.Entry<String, Line>> servers() {
      List<Map.Entry<String, Line>> list = new ArrayList<>(this.data.servers.entrySet());
      list.sort(Comparator.comparingInt((Map.Entry<String, Line> e) -> e.getValue().kills).reversed());
      return list;
   }

   void reset() {
      this.data = new Saved();
      this.save();
   }
}
