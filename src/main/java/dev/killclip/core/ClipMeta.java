package dev.killclip.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What happened in a kill clip, saved beside it in the hidden ".meta" folder: when each kill lands in the video,
 * how it was done, your health at that moment and how far away the player was. The highlight reel uses it to rate
 * clips and to put the kill popups and beat cuts on the exact kill.
 */
public final class ClipMeta {
   private static final String HEADER = "killclip-meta 1";

   /** How a kill was made, as far as the client can tell. */
   public enum Cause {
      MELEE, RANGED, EXPLOSION, OTHER;

      static Cause parse(String text) {
         try {
            return valueOf(text.toUpperCase(Locale.ROOT));
         } catch (IllegalArgumentException e) {
            return OTHER;
         }
      }
   }

   /**
    * One kill: {@code at} seconds into the clip's video, your health (with absorption) then, and the distance to
    * the player when you last hit them (0 if unknown).
    */
   public record Kill(double at, Cause cause, float health, float distance) {
   }

   private ClipMeta() {
   }

   /** The cause a kill message suggests: crystals, anchors, beds and TNT are explosions; shots and arrows are ranged. */
   public static Cause causeFromText(String message) {
      String text = message.toLowerCase(Locale.ROOT);
      if (text.contains("crystal") || text.contains("anchor") || text.contains("blew up") || text.contains("blown up") || text.contains("explo")
         || text.contains("[intentional game design]") || text.contains("tnt")) {
         return Cause.EXPLOSION;
      }

      if (text.contains(" shot") || text.contains("arrow") || text.contains(" bow") || text.contains("crossbow") || text.contains("trident")
         || text.contains("sniped")) {
         return Cause.RANGED;
      }

      return Cause.OTHER;
   }

   /** The meta file for a clip: clips/.meta/&lt;clip name without type&gt;.kcmeta. */
   public static Path fileFor(Path clip) {
      String name = clip.getFileName().toString();
      int dot = name.lastIndexOf('.');
      return clip.resolveSibling(".meta").resolve((dot > 0 ? name.substring(0, dot) : name) + ".kcmeta");
   }

   public static void write(Path clip, List<Kill> kills) throws IOException {
      write(clip, kills, null);
   }

   /**
    * Saves the kills and the stretches of the video where the game showed no fight ({@code away}: loading and
    * connecting screens, a world switch), in the video's seconds; null if they weren't tracked. Older readers skip
    * the new lines.
    */
   public static void write(Path clip, List<Kill> kills, List<LoadingScreens.Span> away) throws IOException {
      Path file = fileFor(clip);
      Path dir = file.getParent();
      if (!Files.isDirectory(dir)) {
         Files.createDirectories(dir);
         try {
            Files.setAttribute(dir, "dos:hidden", true);
         } catch (IOException | UnsupportedOperationException | IllegalArgumentException ignored) {
            // Not Windows: the dot in the name already hides it.
         }
      }

      StringBuilder text = new StringBuilder(HEADER).append('\n');
      for (Kill kill : kills) {
         text.append(String.format(Locale.ROOT, "kill %.3f %s %.1f %.1f%n", kill.at(), kill.cause().name().toLowerCase(Locale.ROOT), kill.health(), kill.distance()));
      }

      if (away != null) {
         // Marks a clip whose loading screens were tracked, even if it had none.
         text.append("screens tracked\n");
         for (LoadingScreens.Span span : away) {
            text.append(String.format(Locale.ROOT, "away %.3f %.3f%n", span.from(), span.to()));
         }
      }

      Files.writeString(file, text);
   }

   /** The stretches of a clip where the game showed no fight; null for clips from before this was tracked. */
   public static List<LoadingScreens.Span> readAway(Path clip) {
      List<LoadingScreens.Span> spans = new ArrayList<>();
      boolean tracked = false;
      for (String[] parts : lines(clip)) {
         tracked |= parts.length >= 2 && parts[0].equals("screens") && parts[1].equals("tracked");
         if (parts.length >= 3 && parts[0].equals("away")) {
            try {
               spans.add(new LoadingScreens.Span(Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
            } catch (NumberFormatException ignored) {
               // Skip a damaged line.
            }
         }
      }

      return tracked ? LoadingScreens.merge(spans) : null;
   }

   /** The lines of a clip's meta file split into words, without the header; empty if there is none. */
   private static List<String[]> lines(Path clip) {
      Path file = fileFor(clip);
      List<String[]> out = new ArrayList<>();
      if (!Files.isRegularFile(file)) {
         return out;
      }

      try {
         List<String> lines = Files.readAllLines(file);
         if (lines.isEmpty() || !lines.get(0).strip().equals(HEADER)) {
            return out;
         }

         for (String line : lines.subList(1, lines.size())) {
            out.add(line.strip().split("\\s+"));
         }
      } catch (IOException ignored) {
         return new ArrayList<>();
      }

      return out;
   }

   /** The kills saved for a clip, oldest first; empty for clips from before this was saved, or an unreadable file. */
   public static List<Kill> read(Path clip) {
      Path file = fileFor(clip);
      List<Kill> kills = new ArrayList<>();
      if (!Files.isRegularFile(file)) {
         return kills;
      }

      try {
         List<String> lines = Files.readAllLines(file);
         if (lines.isEmpty() || !lines.get(0).strip().equals(HEADER)) {
            return kills;
         }

         for (String line : lines.subList(1, lines.size())) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length >= 5 && parts[0].equals("kill")) {
               try {
                  kills.add(new Kill(Double.parseDouble(parts[1]), Cause.parse(parts[2]), Float.parseFloat(parts[3]), Float.parseFloat(parts[4])));
               } catch (NumberFormatException ignored) {
                  // Skip a damaged line.
               }
            }
         }
      } catch (IOException ignored) {
         return new ArrayList<>();
      }

      kills.sort((a, b) -> Double.compare(a.at(), b.at()));
      return kills;
   }
}
