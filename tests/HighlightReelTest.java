import dev.killclip.core.HighlightReel;
import dev.killclip.core.HighlightReel.Clip;
import dev.killclip.core.HighlightReel.Segment;
import dev.killclip.core.ReelMusic;
import dev.killclip.core.VideoTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class HighlightReelTest {
   public static void main(String[] args) throws Exception {
      Path dir = Path.of(args[0]);
      Files.createDirectories(dir);
      check(HighlightReel.clip(Path.of("2026-11-10_21-04-33_kill.mp4"), 0L).kills() == 1, "kill name");
      check(HighlightReel.clip(Path.of("2026-11-10_21-04-33_4-kill-streak.mkv"), 0L).kills() == 4, "streak name");
      check(HighlightReel.clip(Path.of("2026-11-10_21-04-33_kill_vertical.mp4"), 0L) == null, "vertical copy is not a clip");
      check(HighlightReel.clip(Path.of("2026-11-10_21-04-33_test.mp4"), 0L) == null, "test clip is not a kill");
      check(HighlightReel.clip(Path.of("2026-11-10_21-04-33_kill.kcreplay"), 0L) == null, "replay is not a video");

      long hour = 3600_000L;
      List<Clip> all = new ArrayList<>();
      long[] times = {0, 10 * hour, 10 * hour + 60_000, 11 * hour, 11 * hour + 5000};
      int[] kills = {5, 1, 3, 1, 2};
      for (int i = 0; i < times.length; i++) {
         all.add(new Clip(Path.of("c" + i + ".mp4"), times[i], kills[i]));
      }

      List<Clip> latest = HighlightReel.inRange(all, HighlightReel.Range.LATEST, 12 * hour);
      check(latest.size() == 4 && latest.get(0).timeMs() == 10 * hour, "latest session stops at the long break: " + latest.size());
      check(HighlightReel.inRange(all, HighlightReel.Range.ALL, 12 * hour).size() == 5, "all");
      List<Clip> best = HighlightReel.pick(latest, 3);
      check(best.size() == 3 && best.get(0).kills() == 3 && best.get(1).kills() == 1 && best.get(1).timeMs() == 11 * hour && best.get(2).kills() == 2,
         "best three in time order: " + best);

      // A 7 s single-kill clip (5 s before, 2 s after): keep the lead-up and 1.5 s after the kill.
      Segment single = HighlightReel.segment(new Clip(Path.of("a.mp4"), 0, 1), new HighlightReel.Info(7.0, 1920, 1080, true), 5.0, 2.0, 0.4);
      check(Math.abs(single.start() + single.length() - 6.5) < 1e-6 && Math.abs(single.length() - (3 * ReelMusic.BAR_SECONDS + 0.4)) < 1e-6, "single " + single);
      // A 30 s streak: all of it from 3 s before the first kill, in whole bars, at most eight.
      Segment streak = HighlightReel.segment(new Clip(Path.of("b.mp4"), 0, 3), new HighlightReel.Info(30.0, 1920, 1080, true), 5.0, 2.0, 0.4);
      double bars = (streak.length() - 0.4) / ReelMusic.BAR_SECONDS;
      check(Math.abs(bars - Math.round(bars)) < 1e-6 && bars <= 8 && streak.start() >= 0 && Math.abs(streak.start() + streak.length() - 29.5) < 1e-6, "streak " + streak);

      Path music = ReelMusic.file(dir);
      check(Math.abs(VideoTools.duration("ffmpeg", music) - 30.0) < 0.05, "music is a 30 s loop");
      check(ReelMusic.file(dir).equals(music), "music is reused");

      // Three clips of different sizes, one without sound, like a real folder of clips.
      String[][] sources = {{"2026-11-10_21-00-00_kill.mp4", "640x360", "60", "sine=f=440"}, {"2026-11-10_21-01-00_3-kill-streak.mkv", "320x240", "30", "sine=f=660"},
         {"2026-11-10_21-02-00_kill.mp4", "640x360", "60", null}};
      for (String[] s : sources) {
         List<String> make = new ArrayList<>(List.of("ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=" + s[1] + ":rate=" + s[2]));
         if (s[3] != null) {
            make.addAll(List.of("-f", "lavfi", "-i", s[3]));
         }

         make.addAll(List.of("-t", s[0].contains("streak") ? "14" : "7", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p"));
         if (s[3] != null) {
            make.addAll(List.of("-c:a", "aac"));
         }

         make.add(dir.resolve(s[0]).toString());
         check(new ProcessBuilder(make).inheritIO().start().waitFor() == 0, "make " + s[0]);
      }

      Files.writeString(dir.resolve("notes.txt"), "not a clip");
      List<Clip> found = HighlightReel.scan(dir);
      check(found.size() == 3, "scan found " + found.size());
      for (HighlightReel.Transition transition : HighlightReel.Transition.values()) {
         for (boolean withMusic : new boolean[]{true, false}) {
            List<Segment> segments = new ArrayList<>();
            for (Clip clip : found) {
               HighlightReel.Info info = HighlightReel.probe("ffmpeg", clip.file());
               check(info != null && info.width() >= 320, "probe " + clip.file());
               segments.add(HighlightReel.segment(clip, info, 5.0, 2.0, transition.seconds));
            }

            check(!segments.get(2).audio() && segments.get(0).audio(), "audio flags");
            Path out = dir.resolve("reel-" + transition + (withMusic ? "-music" : "") + ".mp4");
            double total = HighlightReel.length(segments, transition);
            double[] seen = {0.0};
            HighlightReel.render(HighlightReel.command("ffmpeg", segments, transition, withMusic ? music : null, 0.8, 0.6, 640, 360, 60, out), total, out, p -> seen[0] = Math.max(seen[0], p));
            HighlightReel.Info made = HighlightReel.probe("ffmpeg", out);
            check(made != null && made.audio() && made.width() == 640 && made.height() == 360, "reel output " + made);
            check(Math.abs(made.seconds() - total) < 0.15, transition + " reel length " + made.seconds() + " vs " + total);
            check(seen[0] > 0.5, "progress " + seen[0]);
         }
      }

      // Cuts land on bars: with the built-in beat, each clip starts a whole number of bars in.
      List<Segment> two = List.of(new Segment(Path.of("a"), 0, 3 * ReelMusic.BAR_SECONDS + 0.4, true), new Segment(Path.of("b"), 0, 3 * ReelMusic.BAR_SECONDS + 0.4, true));
      check(Math.abs(HighlightReel.length(two, HighlightReel.Transition.FADE) - (6 * ReelMusic.BAR_SECONDS + 0.4)) < 1e-6, "bar maths");

      try {
         HighlightReel.render(List.of("ffmpeg", "-loglevel", "error", "-i", dir.resolve("missing.mp4").toString(), dir.resolve("bad.mp4").toString()), 1.0, dir.resolve("bad.mp4"), p -> {
         });
         check(false, "missing input must fail");
      } catch (java.io.IOException expected) {
         check(expected.getMessage().contains("missing.mp4") || !expected.getMessage().isEmpty(), "error text");
      }

      System.out.println("HighlightReelTest passed");
   }

   private static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError(what);
      }
   }
}
