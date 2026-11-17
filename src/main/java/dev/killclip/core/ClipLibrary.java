package dev.killclip.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The clip library's own data, kept in clips/.meta/library.properties: favourites, the server each clip was played
 * on, and the storage limits. Also the rules for search, filters, tags, renaming, deleting and the storage clean-up,
 * all without Minecraft so they can be tested on their own.
 */
public final class ClipLibrary {
   /** Clip names the recorder writes: 2026-11-10_21-04-33_kill.mp4 or ..._3-kill-streak.mp4, with an optional title after. */
   private static final Pattern NAME = Pattern.compile("(\\d{4}-\\d\\d-\\d\\d_\\d\\d-\\d\\d-\\d\\d_(?:kill|(\\d+)-kill-streak))(?:_(?!vertical$)(.*))?");
   private static final String FILE = "library.properties";

   /** What the list shows. */
   public enum Filter {
      ALL("All"), FAVOURITES("Favourites"), STREAKS("Streaks"), REPLAYS("Replays"), EDITED("Edited");

      public final String label;

      Filter(String label) {
         this.label = label;
      }

      public Filter next() {
         return values()[(this.ordinal() + 1) % values().length];
      }
   }

   /** One clip in the list: its video (or a replay with no video), and what is known about it. */
   public record Item(Path file, long modified, long bytes, boolean replay, boolean edited, boolean favourite, String server) {
      public String baseName() {
         return ClipLibrary.baseName(this.file);
      }

      /** Kills in the clip: 1 for a kill clip, N for an N-kill streak, 0 for other videos. */
      public int kills() {
         return ClipLibrary.kills(this.file);
      }

      /** The name shown in the list: the title you gave it, or the kind of clip and when it was made. */
      public String title() {
         return ClipLibrary.title(this.file);
      }

      public List<String> tags() {
         return ClipLibrary.tags(this);
      }
   }

   private final Path dir;
   private final Properties props = new Properties();

   private ClipLibrary(Path dir) {
      this.dir = dir;
   }

   /** The library for a clips folder (reads its saved data, if any). */
   public static ClipLibrary load(Path clipsDir) {
      ClipLibrary library = new ClipLibrary(clipsDir);
      Path file = library.file();
      if (Files.isRegularFile(file)) {
         try (Reader reader = Files.newBufferedReader(file)) {
            library.props.load(reader);
         } catch (IOException | IllegalArgumentException ignored) {
            // A damaged file only loses favourites; the clips themselves are untouched.
         }
      }

      return library;
   }

   private Path file() {
      return this.dir.resolve(".meta").resolve(FILE);
   }

   public void save() {
      Path file = this.file();
      try {
         Files.createDirectories(file.getParent());
         Path temp = file.resolveSibling(FILE + ".tmp");
         try (Writer writer = Files.newBufferedWriter(temp)) {
            this.props.store(writer, "Kill Clipper library: favourites and storage limits");
         }

         try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (IOException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (IOException ignored) {
         // Saving again on the next change will try once more.
      }
   }

   public boolean favourite(Path clip) {
      return this.props.containsKey("fav." + clip.getFileName());
   }

   public void setFavourite(Path clip, boolean favourite) {
      if (favourite) {
         this.props.setProperty("fav." + clip.getFileName(), "1");
      } else {
         this.props.remove("fav." + clip.getFileName());
      }

      this.save();
   }

   /** The server a clip was played on, "" if unknown, or null if it was never looked up. */
   public String server(Path clip) {
      return this.props.getProperty("server." + baseName(clip));
   }

   public void setServer(Path clip, String server) {
      this.props.setProperty("server." + baseName(clip), server == null ? "" : server);
   }

   /** Storage cap in GB; 0 means no cap. */
   public double maxGb() {
      try {
         return Math.max(0.0, Double.parseDouble(this.props.getProperty("storage.maxGb", "0")));
      } catch (NumberFormatException e) {
         return 0.0;
      }
   }

   public void setMaxGb(double gb) {
      this.props.setProperty("storage.maxGb", gb <= 0.0 ? "0" : String.valueOf(gb));
      this.save();
   }

   /** Clips older than this many days are deleted; 0 keeps them forever. */
   public int maxDays() {
      try {
         return Math.max(0, Integer.parseInt(this.props.getProperty("storage.maxDays", "0")));
      } catch (NumberFormatException e) {
         return 0;
      }
   }

   public void setMaxDays(int days) {
      this.props.setProperty("storage.maxDays", String.valueOf(Math.max(0, days)));
      this.save();
   }

   public static String baseName(Path file) {
      String name = file.getFileName().toString();
      int dot = name.lastIndexOf('.');
      return dot > 0 ? name.substring(0, dot) : name;
   }

   private static String extension(Path file) {
      String name = file.getFileName().toString();
      int dot = name.lastIndexOf('.');
      return dot > 0 ? name.substring(dot) : "";
   }

   public static int kills(Path file) {
      Matcher m = NAME.matcher(baseName(file));
      if (!m.matches()) {
         return 0;
      }

      return m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
   }

   /** The title given with Rename: a kill clip's words after its date and kind, another video's whole name; "" for none. */
   public static String customTitle(Path file) {
      Matcher m = NAME.matcher(baseName(file));
      if (!m.matches()) {
         return baseName(file);
      }

      return m.group(3) == null ? "" : m.group(3).replace('_', ' ');
   }

   /** "Triple kill · Nov 10 21:04", or the title given with Rename, or the file name for other videos. */
   public static String title(Path file) {
      String base = baseName(file);
      Matcher m = NAME.matcher(base);
      if (!m.matches()) {
         return base;
      }

      if (m.group(3) != null && !m.group(3).isBlank()) {
         return m.group(3).replace('_', ' ');
      }

      String stamp = base.substring(0, 19);
      String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
      int month = Integer.parseInt(stamp.substring(5, 7));
      String when = months[Math.max(0, Math.min(11, month - 1))] + " " + Integer.parseInt(stamp.substring(8, 10)) + " " + stamp.substring(11, 13) + ":" + stamp.substring(14, 16);
      return streakName(kills(file)) + "  " + when;
   }

   public static String streakName(int kills) {
      return switch (kills) {
         case 1 -> "Kill";
         case 2 -> "Double kill";
         case 3 -> "Triple kill";
         case 4 -> "Quadra kill";
         case 5 -> "Penta kill";
         default -> kills + "-kill streak";
      };
   }

   /** Tags shown on a clip and searched by: favourite, streak size, server, replay, edited. */
   static List<String> tags(Item item) {
      List<String> tags = new ArrayList<>();
      int kills = item.kills();
      if (kills >= 2) {
         tags.add(kills + "K");
      }

      if (item.server() != null && !item.server().isEmpty()) {
         tags.add(shortServer(item.server()));
      }

      if (item.replay()) {
         tags.add("REPLAY");
      }

      if (item.edited()) {
         tags.add("EDIT");
      }

      return tags;
   }

   /** "mcpvp.club:25565" becomes "mcpvp.club"; "play.pvphq.com" becomes "pvphq.com". */
   public static String shortServer(String server) {
      String host = server.toLowerCase(Locale.ROOT).strip();
      int colon = host.lastIndexOf(':');
      if (colon > 0 && host.indexOf(':') == colon) {
         host = host.substring(0, colon);
      }

      String[] parts = host.split("\\.");
      if (parts.length > 2 && !host.matches("[0-9.]+")) {
         host = parts[parts.length - 2] + "." + parts[parts.length - 1];
      }

      return host;
   }

   /** Whether a clip passes the filter and has every word of the search in its title, file name or tags. */
   public static boolean matches(Item item, Filter filter, String search) {
      boolean shown = switch (filter) {
         case ALL -> true;
         case FAVOURITES -> item.favourite();
         case STREAKS -> item.kills() >= 2;
         case REPLAYS -> item.replay();
         case EDITED -> item.edited();
      };
      if (!shown) {
         return false;
      }

      String haystack = (item.title() + " " + item.file().getFileName() + " " + String.join(" ", item.tags()) + " "
         + (item.server() == null ? "" : item.server()) + " " + streakName(Math.max(1, item.kills())) + (item.favourite() ? " favourite fav star" : ""))
         .toLowerCase(Locale.ROOT);
      for (String word : search.toLowerCase(Locale.ROOT).strip().split("\\s+")) {
         if (!word.isEmpty() && !haystack.contains(word)) {
            return false;
         }
      }

      return true;
   }

   /** Clips younger than this are never deleted, so a clip you just made is always kept. */
   public static final long KEEP_NEWEST_MS = 10 * 60_000L;

   /**
    * The clips the storage limits would delete: clips older than {@code maxDays}, then the oldest until the clips
    * fit in {@code maxBytes}. Favourites, edited videos and clips from the last ten minutes are never picked;
    * favourites count toward the size, edited videos don't.
    */
   public static List<Item> overLimits(List<Item> items, long maxBytes, int maxDays, long nowMs) {
      List<Item> candidates = new ArrayList<>(items.stream().filter(i -> !i.favourite() && !i.edited() && nowMs - i.modified() > KEEP_NEWEST_MS).toList());
      candidates.sort(Comparator.comparingLong(Item::modified));
      Set<Item> picked = new LinkedHashSet<>();
      if (maxDays > 0) {
         long cutoff = nowMs - maxDays * 86_400_000L;
         candidates.stream().filter(i -> i.modified() < cutoff).forEach(picked::add);
      }

      if (maxBytes > 0L) {
         long total = items.stream().filter(i -> !i.edited()).mapToLong(Item::bytes).sum() - picked.stream().mapToLong(Item::bytes).sum();
         for (Item item : candidates) {
            if (total <= maxBytes) {
               break;
            }

            if (picked.add(item)) {
               total -= item.bytes();
            }
         }
      }

      return new ArrayList<>(picked);
   }

   /** Every file that belongs to a clip: the video, its replay, edit, details and thumbnail. */
   public static List<Path> companions(Path clip) {
      String base = baseName(clip);
      List<Path> files = new ArrayList<>();
      files.add(clip);
      if (!clip.getFileName().toString().endsWith(".kcreplay")) {
         files.add(clip.resolveSibling(base + ".kcreplay"));
      }

      files.add(clip.resolveSibling(".edits").resolve(base + ".kcedit"));
      files.add(clip.resolveSibling(".meta").resolve(base + ".kcmeta"));
      files.add(thumbnail(clip));
      return files;
   }

   /** Where a clip's thumbnail is kept. */
   public static Path thumbnail(Path clip) {
      return clip.resolveSibling(".meta").resolve("thumbs").resolve(baseName(clip) + ".png");
   }

   /** The bytes a clip takes on disk: the video plus its replay. */
   public static long bytes(Path clip) {
      long total = 0L;
      for (Path file : companions(clip).subList(0, clip.getFileName().toString().endsWith(".kcreplay") ? 1 : 2)) {
         try {
            if (Files.isRegularFile(file)) {
               total += Files.size(file);
            }
         } catch (IOException ignored) {
         }
      }

      return total;
   }

   /** Deletes a clip with its replay, edit, details and thumbnail; returns false if the video itself could not be deleted. */
   public boolean delete(Path clip) {
      boolean ok = true;
      for (Path file : companions(clip)) {
         try {
            Files.deleteIfExists(file);
         } catch (IOException e) {
            if (file.equals(clip)) {
               ok = false;
            }
         }
      }

      this.props.remove("fav." + clip.getFileName());
      this.props.remove("server." + baseName(clip));
      this.save();
      return ok;
   }

   /** A title made safe for a file name: letters, digits, spaces become underscores, at most 60 characters. */
   public static String slug(String title) {
      String slug = title.strip().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").replaceAll("\\s+", "_");
      slug = slug.replaceAll("^\\.+", "");
      return slug.length() > 60 ? slug.substring(0, 60) : slug;
   }

   /**
    * The new file name for a clip given a title. Kill clips keep their date and kind at the front, so the highlight
    * reel and stats still know them; other videos simply take the title.
    */
   public static String renamedFile(Path clip, String title) {
      String slug = slug(title);
      if (slug.equalsIgnoreCase("vertical")) {
         // "_vertical" marks the 9:16 copies, which the highlight reel skips.
         slug = slug + "_clip";
      }

      Matcher m = NAME.matcher(baseName(clip));
      String base;
      if (m.matches()) {
         base = slug.isEmpty() ? m.group(1) : m.group(1) + "_" + slug;
      } else {
         base = slug.isEmpty() ? baseName(clip) : slug;
      }

      return base + extension(clip);
   }

   /** Renames a clip and everything that belongs to it; returns the new video path. */
   public Path rename(Path clip, String title) throws IOException {
      String name = renamedFile(clip, title);
      Path target = clip.resolveSibling(name);
      if (target.equals(clip)) {
         return clip;
      }

      if (Files.exists(target)) {
         throw new IOException("a clip called " + name + " already exists");
      }

      List<Path> from = companions(clip);
      List<Path> to = companions(target);
      Files.move(clip, target);
      for (int i = 1; i < from.size(); i++) {
         if (Files.isRegularFile(from.get(i)) && !Files.exists(to.get(i))) {
            try {
               Files.move(from.get(i), to.get(i));
            } catch (IOException ignored) {
               // A thumbnail or details file left behind is made again or simply not used.
            }
         }
      }

      if (this.props.remove("fav." + clip.getFileName()) != null) {
         this.props.setProperty("fav." + target.getFileName(), "1");
      }

      Object server = this.props.remove("server." + baseName(clip));
      if (server != null) {
         this.props.setProperty("server." + baseName(target), server.toString());
      }

      this.save();
      return target;
   }
}
