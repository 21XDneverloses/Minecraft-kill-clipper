import dev.killclip.core.ClipLibrary;
import dev.killclip.core.ClipLibrary.Filter;
import dev.killclip.core.ClipLibrary.Item;
import dev.killclip.core.VideoTools;
import dev.killclip.core.VideoTools.AudioPart;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Library rules (titles, tags, search, storage limits, rename, delete) and the silent gap a freeze-frame puts in the sound. */
public class ClipLibraryTest {
   public static void main(String[] args) throws Exception {
      Path dir = Path.of(args[0]);
      Files.createDirectories(dir);
      names();
      search();
      limits();
      files(dir);
      silences();
      if (args.length > 1) {
         audio(dir, args[1]);
      }

      System.out.println("ClipLibraryTest passed");
   }

   static void names() {
      Path kill = Path.of("2026-11-10_21-04-33_kill.mp4");
      Path streak = Path.of("2026-11-10_21-04-33_3-kill-streak.mp4");
      check(ClipLibrary.kills(kill) == 1 && ClipLibrary.kills(streak) == 3, "kills from the name");
      check(ClipLibrary.title(streak).startsWith("Triple kill  Nov 10 21:04"), "streak title: " + ClipLibrary.title(streak));
      Path renamed = Path.of(ClipLibrary.renamedFile(streak, "clutch vs Steve: 1v3"));
      check(renamed.toString().equals("2026-11-10_21-04-33_3-kill-streak_clutch_vs_Steve_1v3.mp4"), "rename keeps date and kind: " + renamed);
      check(ClipLibrary.kills(renamed) == 3 && ClipLibrary.title(renamed).equals("clutch vs Steve 1v3"), "renamed clip keeps its kills and shows its title");
      check(ClipLibrary.renamedFile(Path.of("imported.mov"), "my clip").equals("my_clip.mov"), "other videos take the title");
      check(ClipLibrary.shortServer("play.PvPHQ.com:25565").equals("pvphq.com"), "server tag");
      check(ClipLibrary.shortServer("127.0.0.1:25565").equals("127.0.0.1"), "ip stays");
   }

   static Item item(String name, long modified, long bytes, boolean fav, boolean edited, String server) {
      return new Item(Path.of(name), modified, bytes, true, edited, fav, server);
   }

   static void search() {
      Item a = item("2026-11-10_21-04-33_3-kill-streak.mp4", 0, 1, true, false, "mcpvp.club:25565");
      Item b = item("2026-11-10_21-05-33_kill.mp4", 0, 1, false, false, "pvphq.com");
      check(ClipLibrary.matches(a, Filter.FAVOURITES, "") && !ClipLibrary.matches(b, Filter.FAVOURITES, ""), "favourites filter");
      check(ClipLibrary.matches(a, Filter.STREAKS, "") && !ClipLibrary.matches(b, Filter.STREAKS, ""), "streaks filter");
      check(ClipLibrary.matches(a, Filter.ALL, "mcpvp 3k") && !ClipLibrary.matches(b, Filter.ALL, "mcpvp"), "search by server and streak tag");
      check(ClipLibrary.matches(a, Filter.ALL, "triple"), "search by title");
      check(a.tags().equals(List.of("3K", "mcpvp.club", "REPLAY")), "tags " + a.tags());
   }

   static void limits() {
      long day = 86_400_000L;
      long now = 100 * day;
      List<Item> items = new ArrayList<>();
      items.add(item("old-fav.mp4", now - 50 * day, 400, true, false, ""));
      items.add(item("old.mp4", now - 40 * day, 300, false, false, ""));
      items.add(item("mid.mp4", now - 10 * day, 300, false, false, ""));
      items.add(item("edit.mp4", now - 60 * day, 5000, false, true, ""));
      items.add(item("new.mp4", now - 60_000L, 300, false, false, ""));
      List<String> byAge = ClipLibrary.overLimits(items, 0, 30, now).stream().map(i -> i.file().toString()).toList();
      check(byAge.equals(List.of("old.mp4")), "age limit skips favourites and edits: " + byAge);
      List<String> bySize = ClipLibrary.overLimits(items, 700, 0, now).stream().map(i -> i.file().toString()).toList();
      check(bySize.equals(List.of("old.mp4", "mid.mp4")), "size limit deletes oldest first, edits don't count: " + bySize);
      List<String> tiny = ClipLibrary.overLimits(items, 1, 0, now).stream().map(i -> i.file().toString()).toList();
      check(!tiny.contains("new.mp4") && !tiny.contains("old-fav.mp4"), "newest clip and favourites are kept: " + tiny);
   }

   static void files(Path dir) throws Exception {
      Path clips = dir.resolve("clips");
      Files.createDirectories(clips.resolve(".edits"));
      Files.createDirectories(clips.resolve(".meta").resolve("thumbs"));
      Path video = clips.resolve("2026-11-10_21-04-33_kill.mp4");
      Files.writeString(video, "v");
      Files.writeString(clips.resolve("2026-11-10_21-04-33_kill.kcreplay"), "r");
      Files.writeString(clips.resolve(".edits/2026-11-10_21-04-33_kill.kcedit"), "e");
      Files.writeString(clips.resolve(".meta/2026-11-10_21-04-33_kill.kcmeta"), "m");
      Files.writeString(ClipLibrary.thumbnail(video), "t");
      ClipLibrary library = ClipLibrary.load(clips);
      library.setFavourite(video, true);
      library.setMaxGb(5);
      Path renamed = library.rename(video, "first blood");
      check(Files.isRegularFile(renamed) && !Files.exists(video), "video renamed");
      check(Files.isRegularFile(clips.resolve("2026-11-10_21-04-33_kill_first_blood.kcreplay")), "replay renamed");
      check(Files.isRegularFile(clips.resolve(".edits/2026-11-10_21-04-33_kill_first_blood.kcedit")), "edit renamed");
      check(Files.isRegularFile(ClipLibrary.thumbnail(renamed)), "thumbnail renamed");
      ClipLibrary reloaded = ClipLibrary.load(clips);
      check(reloaded.favourite(renamed) && reloaded.maxGb() == 5.0, "favourite and limit saved");
      check(ClipLibrary.bytes(renamed) == 2L, "size counts video and replay");
      check(reloaded.delete(renamed), "delete");
      for (Path file : ClipLibrary.companions(renamed)) {
         check(!Files.exists(file), "deleted " + file.getFileName());
      }
   }

   static void silences() {
      List<AudioPart> parts = List.of(new AudioPart(1.0, 4.0, 1.0), new AudioPart(4.0, 6.0, 0.5));
      List<AudioPart> out = VideoTools.withSilences(parts, List.of(2.0, 6.0), List.of(1.5, 1.0));
      check(out.size() == 5, "split around freezes: " + out);
      check(out.get(0).equals(new AudioPart(1.0, 2.0, 1.0)) && out.get(1).speed() == 0.0 && out.get(1).endSec() == 1.5
         && out.get(2).equals(new AudioPart(2.0, 4.0, 1.0)) && out.get(4).speed() == 0.0, "gap in the right place: " + out);
   }

   /** The real FFmpeg graph with a silent gap: the sound gets longer by the freeze. */
   static void audio(Path dir, String ffmpeg) throws Exception {
      Path source = dir.resolve("source.mp4");
      Path video = dir.resolve("silent.mp4");
      Path out = dir.resolve("out.mp4");
      run(ffmpeg, "-y", "-f", "lavfi", "-i", "testsrc=size=160x90:rate=30", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "6",
         "-c:v", "libx264", "-c:a", "aac", "-ac", "1", source.toString());
      run(ffmpeg, "-y", "-f", "lavfi", "-i", "testsrc=size=160x90:rate=30", "-t", "6", "-c:v", "libx264", video.toString());
      List<AudioPart> parts = VideoTools.withSilences(List.of(new AudioPart(1.0, 4.0, 1.0)), List.of(2.0), List.of(2.0));
      check(VideoTools.withAudio(ffmpeg, video, source, parts, false, out), "sound added");
      double length = VideoTools.duration(ffmpeg, out);
      check(length > 4.5, "output has the video length: " + length);
   }

   static void run(String... cmd) throws Exception {
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      check(p.waitFor() == 0, "ran " + String.join(" ", cmd));
   }

   static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError(what);
      }
   }
}
