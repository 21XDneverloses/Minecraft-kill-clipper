import dev.killclip.core.BeatDetector;
import dev.killclip.core.ClipMeta;
import dev.killclip.core.HighlightReel;
import dev.killclip.core.HighlightReel.Clip;
import dev.killclip.core.HighlightReel.Piece;
import dev.killclip.core.HighlightReel.Role;
import dev.killclip.core.HighlightReel.Segment;
import dev.killclip.core.LoadingScreens;
import dev.killclip.core.LoadingScreens.Span;
import dev.killclip.core.ReelMusic;
import dev.killclip.core.ReelText;
import dev.killclip.core.VideoTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class HighlightReelTest {
   public static void main(String[] args) throws Exception {
      Path dir = Path.of(args[0]);
      Files.createDirectories(dir);
      names();
      picking();
      Path music = ReelMusic.file(dir);
      check(Math.abs(VideoTools.duration("ffmpeg", music) - 30.0) < 0.05, "music is a 30 s loop");
      check(ReelMusic.file(dir).equals(music), "music is reused");
      beats(dir, music);
      segments();
      pace();
      loading(dir);
      filling();
      meta(dir);
      reels(dir, music);
      System.out.println("HighlightReelTest passed");
   }

   private static void names() {
      check(HighlightReel.clip(Path.of("2026-11-10_21-04-33_kill.mp4"), 0L).kills() == 1, "kill name");
      check(HighlightReel.clip(Path.of("2026-11-10_21-04-33_4-kill-streak.mkv"), 0L).kills() == 4, "streak name");
      check(HighlightReel.clip(Path.of("2026-11-10_21-04-33_kill_vertical.mp4"), 0L) == null, "vertical copy is not a clip");
      check(HighlightReel.clip(Path.of("2026-11-10_21-04-33_2-kill-streak_clutch.mp4"), 0L).kills() == 2, "renamed streak");
      check(HighlightReel.clip(Path.of("2026-11-10_21-04-33_test.mp4"), 0L) == null, "test clip is not a kill");
      check(HighlightReel.clip(Path.of("2026-11-10_21-04-33_kill.kcreplay"), 0L) == null, "replay is not a video");
   }

   private static ClipMeta.Kill kill(double at, ClipMeta.Cause cause, float health, float distance) {
      return new ClipMeta.Kill(at, cause, health, distance);
   }

   private static void picking() {
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

      // Scores: a triple beats three singles; quick kills, crystals, long shots and clutches add up.
      Clip single = new Clip(Path.of("s.mp4"), 0, 1);
      Clip triple = new Clip(Path.of("t.mp4"), 0, 3);
      check(HighlightReel.score(triple) > 3 * HighlightReel.score(single), "triple beats three singles");
      Clip plainDouble = new Clip(Path.of("d1.mp4"), 0, 2, List.of(kill(5, ClipMeta.Cause.MELEE, 20, 3), kill(12, ClipMeta.Cause.MELEE, 20, 3)));
      Clip fastDouble = new Clip(Path.of("d2.mp4"), 0, 2, List.of(kill(5, ClipMeta.Cause.MELEE, 20, 3), kill(6, ClipMeta.Cause.MELEE, 20, 3)));
      Clip crystal = new Clip(Path.of("c.mp4"), 0, 1, List.of(kill(5, ClipMeta.Cause.EXPLOSION, 20, 4)));
      Clip snipe = new Clip(Path.of("b.mp4"), 0, 1, List.of(kill(5, ClipMeta.Cause.RANGED, 20, 41)));
      Clip clutch = new Clip(Path.of("h.mp4"), 0, 1, List.of(kill(5, ClipMeta.Cause.MELEE, 3, 3)));
      check(HighlightReel.score(fastDouble) > HighlightReel.score(plainDouble), "rapid kills score more");
      check(HighlightReel.score(crystal) > HighlightReel.score(single), "crystal kill scores more");
      check(HighlightReel.score(snipe) > HighlightReel.score(crystal), "long shot scores more");
      check(HighlightReel.score(clutch) > HighlightReel.score(crystal), "low-health win scores more");
      check(HighlightReel.score(triple) > HighlightReel.score(snipe), "kill count still comes first");

      // Fixed count: the best three of the latest session, hook first and finale last. The single at 11 h is the same
      // fight as the double 5 s later, so the other single fills the middle.
      List<Clip> best = HighlightReel.pick(latest, 3);
      check(best.size() == 3 && best.get(0).kills() == 3 && best.get(2).kills() == 2 && best.get(1).kills() == 1 && best.get(1).timeMs() == 10 * hour,
         "best three as hook, middle, finale: " + best);

      // The same fight saved twice is used once (the better clip).
      List<Clip> twice = List.of(new Clip(Path.of("a.mp4"), 1000, 1), new Clip(Path.of("b.mp4"), 6000, 2), new Clip(Path.of("c.mp4"), 120_000, 1));
      List<Clip> deduped = HighlightReel.pick(twice, 0);
      check(deduped.size() == 2 && deduped.stream().noneMatch(c -> c.file().toString().equals("a.mp4")), "same fight once: " + deduped);

      // Automatic: weak clips are left out when there is much better material, but at least three stay.
      List<Clip> mixed = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
         mixed.add(new Clip(Path.of("k" + i + ".mp4"), i * 60_000L, i < 4 ? 4 : 1));
      }

      List<Clip> auto = HighlightReel.pick(mixed, 0);
      check(auto.size() == 4 && auto.stream().allMatch(c -> c.kills() == 4), "auto keeps the four streaks only: " + auto.size());
      List<Clip> singles = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
         singles.add(new Clip(Path.of("x" + i + ".mp4"), i * 60_000L, 1));
      }

      List<Clip> autoSingles = HighlightReel.pick(singles, 0);
      double estimate = autoSingles.stream().mapToDouble(HighlightReel::estimate).sum();
      check(autoSingles.size() >= 3 && estimate <= HighlightReel.AUTO_TARGET_SECONDS, "auto stops at about a minute: " + autoSingles.size());
      check(HighlightReel.role(0, 3) == Role.HOOK && HighlightReel.role(1, 3) == Role.NORMAL && HighlightReel.role(2, 3) == Role.FINALE
         && HighlightReel.role(0, 1) == Role.FINALE, "roles");
   }

   private static void beats(Path dir, Path builtIn) throws Exception {
      BeatDetector.Grid grid = BeatDetector.detect("ffmpeg", builtIn);
      check(grid != null && Math.abs(grid.bpm() - 128.0) < 1.0, "built-in beat is 128 BPM: " + (grid == null ? null : grid.bpm()));
      double offBeat = grid.downbeat() % grid.beat();
      check(Math.min(offBeat, grid.beat() - offBeat) < 0.03, "beats land on the grid: " + grid.downbeat());

      // A different song: 100 BPM kicks with hats, starting 0.37 s in after a quiet intro.
      int rate = 44100;
      double beat = 0.6;
      double first = 0.37 + 4 * beat * 2;
      float[] song = new float[rate * 40];
      java.util.Random random = new java.util.Random(5);
      for (double t = first; t < 40; t += beat) {
         int at = (int)(t * rate);
         boolean bar = Math.round((t - first) / beat) % 4 == 0;
         for (int i = 0; i < rate / 5 && at + i < song.length; i++) {
            double s = i / (double)rate;
            song[at + i] += (float)(Math.sin(2 * Math.PI * (50 + 120 * Math.exp(-s / 0.03)) * s) * Math.exp(-s / 0.08) * (bar ? 0.9 : 0.6));
         }

         int hat = (int)((t + beat / 2) * rate);
         for (int i = 0; i < rate / 30 && hat + i < song.length; i++) {
            song[hat + i] += (float)((random.nextFloat() * 2 - 1) * 0.15 * Math.exp(-i / (rate * 0.01)));
         }
      }

      for (int i = 0; i < (int)(first * rate); i++) {
         song[i] += (float)(Math.sin(2 * Math.PI * 220 * i / rate) * 0.02);
      }

      BeatDetector.Grid detected = BeatDetector.detect(song, rate);
      check(detected != null && Math.abs(detected.bpm() - 100.0) < 1.0, "100 BPM song: " + (detected == null ? null : detected.bpm()));
      double phase = ((detected.downbeat() - 0.37) % beat + beat) % beat;
      check(Math.min(phase, beat - phase) < 0.03, "100 BPM beats found: " + detected.downbeat());
      check(detected.start() >= first - 0.05, "starts after the quiet intro: " + detected.start());
      double bars = (detected.start() - first) / (4 * beat);
      check(Math.abs(bars - Math.round(bars)) < 0.05, "starts on a bar line: " + detected.start());

      // A fast song (drum and bass style, 172 BPM: kick and snare taking turns on every beat) is found at its real
      // tempo, not half of it; a slow 84 BPM song with quiet off-beat hats stays slow.
      BeatDetector.Grid fast = BeatDetector.detect(drums(rate, 172.0, true), rate);
      check(fast != null && Math.abs(fast.bpm() - 172.0) < 2.0, "172 BPM song: " + (fast == null ? null : fast.bpm()));
      check(HighlightReel.pace(fast).name().equals("fast"), "fast song gets fast cuts");
      BeatDetector.Grid chill = BeatDetector.detect(drums(rate, 84.0, false), rate);
      check(chill != null && Math.abs(chill.bpm() - 84.0) < 1.5, "84 BPM song: " + (chill == null ? null : chill.bpm()));
      check(HighlightReel.pace(chill).name().equals("slow"), "slow song gets slow cuts");
   }

   /**
    * 40 s of drums at {@code bpm}. {@code busy}: a kick or a snare on every beat, as loud as each other (fast music
    * that fits half its tempo too); otherwise a kick on the beat and a quiet hat between.
    */
   private static float[] drums(int rate, double bpm, boolean busy) {
      double beat = 60.0 / bpm;
      float[] song = new float[rate * 40];
      java.util.Random random = new java.util.Random(9);
      int n = 0;
      for (double t = 0.5; t < 39.5; t += beat, n++) {
         int at = (int)(t * rate);
         if (!busy || n % 2 == 0) {
            for (int i = 0; i < rate / 5 && at + i < song.length; i++) {
               double s = i / (double)rate;
               song[at + i] += (float)(Math.sin(2 * Math.PI * (50 + 120 * Math.exp(-s / 0.03)) * s) * Math.exp(-s / 0.08) * (n % 4 == 0 ? 0.9 : 0.7));
            }
         } else {
            for (int i = 0; i < rate / 8 && at + i < song.length; i++) {
               song[at + i] += (float)((random.nextFloat() * 2 - 1) * 0.7 * Math.exp(-i / (rate * 0.04)));
            }
         }

         int hat = (int)((t + beat / 2) * rate);
         for (int i = 0; i < rate / 30 && hat + i < song.length; i++) {
            song[hat + i] += (float)((random.nextFloat() * 2 - 1) * (busy ? 0.25 : 0.1) * Math.exp(-i / (rate * 0.01)));
         }
      }

      return song;
   }

   /** Fast music cuts quicker and slow music lets clips run: the same kill takes less reel time at 170 BPM than at 80. */
   private static void pace() {
      HighlightReel.Info info = new HighlightReel.Info(20.0, 1920, 1080, true);
      Clip clip = new Clip(Path.of("a.mp4"), 0, 1, List.of(kill(15.0, ClipMeta.Cause.MELEE, 20, 3)));
      double[] lengths = new double[3];
      double[] bpms = {170.0, 128.0, 80.0};
      for (int i = 0; i < 3; i++) {
         BeatDetector.Grid grid = new BeatDetector.Grid(60.0 / bpms[i], 0, 0);
         Segment segment = HighlightReel.segment(clip, info, 10.0, 5.0, grid, Role.NORMAL, 0.0);
         lengths[i] = segment.length();
         double units = segment.length() / HighlightReel.unit(grid);
         check(Math.abs(units - Math.round(units)) < 1e-6, bpms[i] + " BPM clip is whole units: " + units);
         check(Math.abs(segment.popups().get(0).at() / HighlightReel.unit(grid) - Math.round(segment.popups().get(0).at() / HighlightReel.unit(grid))) < 1e-6,
            bpms[i] + " BPM kill on a cut line");
      }

      check(lengths[0] < lengths[1] - 1.0 && lengths[1] < lengths[2] - 1.0, "cut length follows the tempo: " + java.util.Arrays.toString(lengths));
      check(lengths[0] <= 3.5, "fast cuts are short: " + lengths[0]);
   }

   /** Loading screens and world switches stay out of the reel. */
   private static void loading(Path dir) throws Exception {
      BeatDetector.Grid grid = BeatDetector.builtIn();
      double t = HighlightReel.transitionSeconds(HighlightReel.Transition.FADE, grid);
      double unit = HighlightReel.unit(grid);
      HighlightReel.Info info = new HighlightReel.Info(12.0, 1920, 1080, true);
      // Won at 8 s, then sent to the lobby 0.8 s later: the loading screen runs from 8.8 s to the end.
      Clip won = new Clip(Path.of("w.mp4"), 0, 1, List.of(kill(8.0, ClipMeta.Cause.MELEE, 20, 3)), List.of(new Span(8.55, 12.0)));
      for (Role role : Role.values()) {
         Segment segment = HighlightReel.segment(won, info, 10.0, 4.0, grid, role, t);
         check(segment.pieces().stream().allMatch(p -> p.start() + p.length() <= 8.55 + 1e-6), role + " stops before the lobby: " + segment.pieces());
         double units = (segment.length() - t) / unit;
         check(Math.abs(units - Math.round(units)) < 1e-6, role + " still whole units: " + units);
         check(segment.popups().size() == 1 && segment.popups().get(0).main().equals("KILL"), role + " kill still shown");
      }

      // Joined the arena 2 s before the kill: nothing from before the join.
      Clip joined = new Clip(Path.of("j.mp4"), 0, 1, List.of(kill(7.0, ClipMeta.Cause.MELEE, 20, 3)), List.of(new Span(0.0, 5.0)));
      Segment segment = HighlightReel.segment(joined, info, 10.0, 4.0, grid, Role.NORMAL, t);
      check(segment.pieces().stream().allMatch(p -> p.start() >= 5.0 - 1e-6), "starts after the join: " + segment.pieces());
      check(segment.popups().size() == 1, "kill still shown after a late join");

      // A span over the kill itself is ignored; one far away changes nothing.
      Clip plain = new Clip(Path.of("p.mp4"), 0, 1, List.of(kill(7.0, ClipMeta.Cause.MELEE, 20, 3)), List.of());
      Clip far = new Clip(Path.of("p.mp4"), 0, 1, List.of(kill(7.0, ClipMeta.Cause.MELEE, 20, 3)), List.of(new Span(0.0, 0.5), new Span(6.9, 7.1)));
      check(HighlightReel.segment(far, info, 10.0, 4.0, grid, Role.NORMAL, t).pieces().equals(HighlightReel.segment(plain, info, 10.0, 4.0, grid, Role.NORMAL, t).pieces()),
         "spans far away or over the kill change nothing");

      // Old clips are checked by picture around the kills; tracked clips aren't.
      double[] window = HighlightReel.scanWindow(new Clip(Path.of("o.mp4"), 0, 1), info, 10.0, 4.0, grid);
      check(window[0] < 8.0 - 2 * unit && window[0] + window[1] >= 12.0 - 1e-6, "scan window covers the lead-up and the end: " + java.util.Arrays.toString(window));
      check(HighlightReel.scanWindow(plain, info, 10.0, 4.0, grid)[1] == 0.0, "tracked clips need no scan");

      // Spans from frames: single blips are ignored, runs are widened and merged.
      List<Span> spans = LoadingScreens.spans(new boolean[]{false, true, false, false, true, true, true, false, false, false, false, false, false, true, true}, 10.0, 2.0);
      check(spans.size() == 2 && Math.abs(spans.get(0).from() - 2.15) < 1e-6 && Math.abs(spans.get(0).to() - 2.95) < 1e-6
         && Math.abs(spans.get(1).from() - 3.05) < 1e-6, "spans: " + spans);
      check(LoadingScreens.spans(new boolean[]{true, true, false, true, true}, 10.0, 0.0).size() == 1, "close spans merge");
      double[] clear = LoadingScreens.clear(List.of(new Span(0, 2), new Span(3, 4), new Span(9, 10), new Span(11, 12)), 5.0, 6.0, 12.0);
      check(clear[0] == 4.0 && clear[1] == 9.0, "clear stretch: " + java.util.Arrays.toString(clear));

      // The recorder's tracker: a loading screen and a quick world switch, in the video's seconds.
      LoadingScreens.Tracker tracker = new LoadingScreens.Tracker();
      tracker.update(10_000L, false);
      tracker.update(14_000L, true);
      tracker.update(15_000L, false);
      tracker.cut(18_000L);
      List<Span> away = tracker.between(10_000L, 20_000L, 500L);
      check(away.size() == 2 && Math.abs(away.get(0).from() - 4.25) < 1e-6 && Math.abs(away.get(0).to() - 6.25) < 1e-6
         && Math.abs(away.get(1).from() - 8.25) < 1e-6 && Math.abs(away.get(1).to() - 9.25) < 1e-6, "tracked spans: " + away);
      tracker.update(19_000L, true);
      List<Span> open = tracker.between(10_000L, 20_000L, 0L);
      check(Math.abs(open.getLast().to() - 10.0) < 1e-6, "a loading screen still showing runs to the clip's end: " + open);

      // The picture check: game-like detail passes, a blurred menu with a line of text and black frames don't.
      byte[] busy = new byte[160 * 90];
      byte[] flat = new byte[160 * 90];
      java.util.Random random = new java.util.Random(3);
      for (int i = 0; i < busy.length; i++) {
         int x = i % 160;
         int y = i / 160;
         busy[i] = (byte)(((x / 4 + y / 4) % 2 == 0 ? 60 : 140) + random.nextInt(30));
         flat[i] = (byte)(70 + x / 4 + ((y == 45 && x > 50 && x < 110 && x % 3 == 0) ? 120 : 0));
      }

      check(!LoadingScreens.loading(busy, 160, 90), "game picture is not loading: " + LoadingScreens.detail(busy, 160, 90));
      check(LoadingScreens.loading(flat, 160, 90), "blurred menu is loading: " + LoadingScreens.detail(flat, 160, 90));
      check(LoadingScreens.loading(new byte[160 * 90], 160, 90), "black is loading");

      // On a real video: 4 s of busy test picture, 1.5 s of a plain dark screen with a line of text, 2 s of picture.
      Path video = dir.resolve("2026-11-10_20-00-00_kill.mp4");
      check(new ProcessBuilder("ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:d=4", "-f", "lavfi", "-i",
         "color=c=0x303848:size=640x360:rate=30:d=1.5", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:d=2", "-filter_complex",
         "[1:v]drawbox=x=250:y=175:w=140:h=6:color=white:t=fill[l];[0:v][l][2:v]concat=n=3:v=1:a=0", "-c:v", "libx264", "-preset", "ultrafast",
         "-pix_fmt", "yuv420p", video.toString()).inheritIO().start().waitFor() == 0, "make loading video");
      List<Span> found = LoadingScreens.scan("ffmpeg", video, 1.0, 6.5);
      check(found.size() == 1 && Math.abs(found.get(0).from() - 3.75) < 0.2 && Math.abs(found.get(0).to() - 5.75) < 0.2, "loading screen found in the video: " + found);
      Files.delete(video);
   }

   /** With your own song, an automatic pick fills the song with every different fight, and streaks are spread out. */
   private static void filling() {
      List<Clip> clips = new ArrayList<>();
      for (int i = 0; i < 30; i++) {
         clips.add(new Clip(Path.of("f" + i + ".mp4"), i * 60_000L, i % 6 == 0 ? 2 : 1));
      }

      BeatDetector.Grid grid = BeatDetector.builtIn();
      int usual = HighlightReel.pick(clips, 0).size();
      List<Clip> song = HighlightReel.pick(clips, 0, 150.0, grid);
      double total = song.stream().mapToDouble(c -> HighlightReel.estimate(c, grid)).sum();
      check(song.size() > usual && total <= 150.0 + 1e-6 && total > 150.0 - 10.0, "fills a 150 s song: " + song.size() + " clips, " + total + " s vs " + usual);
      check(song.stream().map(Clip::file).distinct().count() == song.size(), "no clip twice");
      check(HighlightReel.pick(clips, 0, 600.0, grid).size() == 30, "a long song takes every clip");

      // Streaks spread through the middle instead of bunching up.
      List<Clip> middle = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
         middle.add(new Clip(Path.of("m" + i + ".mp4"), i * 60_000L, i >= 6 ? 3 : 1));
      }

      List<Clip> order = HighlightReel.order(middle);
      List<Integer> streakAt = new ArrayList<>();
      for (int i = 1; i < order.size() - 1; i++) {
         if (order.get(i).kills() > 1) {
            streakAt.add(i);
         }
      }

      check(order.get(0).kills() == 3 && order.getLast().kills() == 3, "best streaks open and close");
      middle.add(new Clip(Path.of("m8.mp4"), 8 * 60_000L, 2));
      middle.add(new Clip(Path.of("m9.mp4"), 9 * 60_000L, 2));
      order = HighlightReel.order(middle);
      streakAt.clear();
      for (int i = 1; i < order.size() - 1; i++) {
         if (order.get(i).kills() > 1) {
            streakAt.add(i);
         }
      }

      check(streakAt.size() == 2 && streakAt.get(0) >= 2 && streakAt.get(1) - streakAt.get(0) >= 3, "streaks spread out: " + streakAt + " " + order);
   }

   private static void segments() {
      BeatDetector.Grid grid = BeatDetector.builtIn();
      double unit = HighlightReel.unit(grid);
      double t = HighlightReel.transitionSeconds(HighlightReel.Transition.FADE, grid);
      check(Math.abs(t / (grid.beat() / 2) - Math.round(t / (grid.beat() / 2))) < 1e-9 && t > 0, "transition is whole half beats: " + t);

      // A 7 s single-kill clip (5 s before, 2 s after): three units, the kill on the second bar line.
      HighlightReel.Info seven = new HighlightReel.Info(7.0, 1920, 1080, true);
      Segment single = HighlightReel.segment(new Clip(Path.of("a.mp4"), 0, 1), seven, 5.0, 2.0, grid, Role.NORMAL, t);
      check(Math.abs(single.length() - (3 * unit + t)) < 1e-6, "single is three units: " + single);
      check(single.popups().size() == 1 && Math.abs(single.popups().get(0).at() - 2 * unit) < 1e-6 && single.popups().get(0).main().equals("KILL"),
         "kill lands on the bar line: " + single.popups());
      Piece ramp = single.pieces().stream().filter(p -> p.speed() > 1.0).findFirst().orElseThrow();
      check(Math.abs(ramp.start() + ramp.length() - 5.0) < 1e-6 && Math.abs(ramp.out() - 2 * grid.beat()) < 1e-6, "two beats sped up into the kill: " + ramp);

      // The finale drops into slow motion on the kill and holds a unit longer.
      Segment finale = HighlightReel.segment(new Clip(Path.of("a.mp4"), 0, 1), new HighlightReel.Info(9.0, 1920, 1080, true), 5.0, 4.0, grid, Role.FINALE, t);
      check(Math.abs(finale.length() - (4 * unit + t)) < 1e-6, "finale is four units: " + finale.length());
      check(finale.pieces().stream().anyMatch(p -> Math.abs(p.speed() - 0.4) < 1e-9 && p.start() < 5.0 && p.start() + p.length() > 5.0), "slow motion over the kill");

      // A clip that ends right after the kill is slowed down so the next cut is still on the beat.
      Segment shortEnd = HighlightReel.segment(new Clip(Path.of("a.mp4"), 0, 1), new HighlightReel.Info(5.6, 1920, 1080, true), 5.0, 0.6, grid, Role.NORMAL, t);
      check(Math.abs(shortEnd.length() - (3 * unit + t)) < 1e-6, "short ending still three units: " + shortEnd.length());

      // A streak with saved kill times: every kill shows, counted up, and the clip is whole units long.
      List<ClipMeta.Kill> kills = List.of(kill(8.0, ClipMeta.Cause.MELEE, 14, 3), kill(9.0, ClipMeta.Cause.EXPLOSION, 3, 5), kill(12.0, ClipMeta.Cause.RANGED, 12, 33));
      Segment streak = HighlightReel.segment(new Clip(Path.of("b.mp4"), 0, 3, kills), new HighlightReel.Info(15.0, 1920, 1080, true), 5.0, 3.0, grid, Role.HOOK, t);
      double units = (streak.length() - t) / unit;
      check(Math.abs(units - Math.round(units)) < 1e-6 && streak.start() <= 8.0 - 2.0, "streak covers its kills in whole units: " + streak);
      check(streak.popups().size() == 3 && streak.popups().get(1).main().equals("DOUBLE KILL") && streak.popups().get(2).main().equals("TRIPLE KILL"),
         "streak popups: " + streak.popups());
      check(streak.popups().get(1).sub().startsWith("CLUTCH") && streak.popups().get(2).sub().contains("LONG SHOT"), "notes: " + streak.popups());
      check(Math.abs(streak.popups().get(2).at() - (streak.length() - unit - t)) < 1e-6, "last kill on the bar line");

      // Old streak clips without kill times: one popup with the total at the last kill.
      Segment old = HighlightReel.segment(new Clip(Path.of("b.mp4"), 0, 4), new HighlightReel.Info(30.0, 1920, 1080, true), 5.0, 2.0, grid, Role.NORMAL, t);
      check(old.popups().size() == 1 && old.popups().get(0).main().equals("QUAD KILL"), "old streak popup");
      check(old.length() - t <= 8 * unit + 1e-6, "at most eight units");

      // A slow song is cut in whole bars (half bars only when a bar is very long); a fast one in half bars.
      BeatDetector.Grid slow = new BeatDetector.Grid(60.0 / 80.0, 0, 0);
      check(Math.abs(HighlightReel.unit(slow) - 3.0) < 1e-9, "whole bars for slow songs");
      check(Math.abs(HighlightReel.unit(new BeatDetector.Grid(1.0, 0, 0)) - 2.0) < 1e-9, "half bars for very slow songs");
      check(Math.abs(HighlightReel.unit(new BeatDetector.Grid(60.0 / 160.0, 0, 0)) - 0.75) < 1e-9, "half bars for fast songs");

      // Cuts land on bars: segment offsets are whole units.
      List<Segment> three = List.of(single, streak, finale);
      double[] offsets = HighlightReel.offsets(three, t);
      for (double offset : offsets) {
         double u = offset / unit;
         check(Math.abs(u - Math.round(u)) < 1e-6, "cut on a bar line: " + offset);
      }
   }

   private static ClipMeta.Kill kill(double at, ClipMeta.Cause cause, int health, int distance) {
      return new ClipMeta.Kill(at, cause, health, distance);
   }

   private static void meta(Path dir) throws Exception {
      Path clip = dir.resolve("2026-11-10_21-04-33_2-kill-streak.mp4");
      ClipMeta.write(clip, List.of(kill(3.25, ClipMeta.Cause.EXPLOSION, 7.5F, 4.0F), kill(1.0, ClipMeta.Cause.RANGED, 20.0F, 31.5F)));
      List<ClipMeta.Kill> read = ClipMeta.read(clip);
      check(read.size() == 2 && read.get(0).at() == 1.0 && read.get(0).cause() == ClipMeta.Cause.RANGED && read.get(1).health() == 7.5F, "meta round trip: " + read);
      check(ClipMeta.read(dir.resolve("none.mp4")).isEmpty(), "no meta");
      check(ClipMeta.readAway(clip) == null, "old meta has no loading screen tracking");
      ClipMeta.write(clip, read, List.of(new Span(9.0, 12.5), new Span(0.0, 1.25)));
      List<Span> away = ClipMeta.readAway(clip);
      check(away.size() == 2 && away.get(0).from() == 0.0 && away.get(1).to() == 12.5 && ClipMeta.read(clip).size() == 2, "away round trip: " + away);
      ClipMeta.write(clip, read, List.of());
      check(ClipMeta.readAway(clip) != null && ClipMeta.readAway(clip).isEmpty(), "tracked with no loading screens");
      check(ClipMeta.causeFromText("Steve was blown up by Alex using End Crystal") == ClipMeta.Cause.EXPLOSION, "crystal message");
      check(ClipMeta.causeFromText("Steve was shot by Alex") == ClipMeta.Cause.RANGED, "bow message");
      check(ClipMeta.causeFromText("Steve was slain by Alex") == ClipMeta.Cause.OTHER, "melee message");
      Files.delete(ClipMeta.fileFor(clip));
   }

   private static void reels(Path dir, Path music) throws Exception {
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

      ClipMeta.write(dir.resolve(sources[1][0]), List.of(kill(5.0, ClipMeta.Cause.MELEE, 15, 3), kill(6.0, ClipMeta.Cause.EXPLOSION, 3, 4),
         kill(10.5, ClipMeta.Cause.RANGED, 12, 30)));
      Files.writeString(dir.resolve("notes.txt"), "not a clip");
      List<Clip> found = HighlightReel.scan(dir);
      check(found.size() == 3, "scan found " + found.size());
      check(found.get(1).moments().size() == 3, "scan reads kill details");
      List<Clip> ordered = HighlightReel.pick(found, 0);
      check(ordered.get(0).kills() == 3, "streak opens the reel");
      BeatDetector.Grid grid = BeatDetector.builtIn();
      Path replayVideo = dir.resolve("replay-angle.mp4");
      check(new ProcessBuilder("ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=60", "-f", "lavfi", "-i", "sine=f=330",
         "-t", "4.2", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", replayVideo.toString()).inheritIO().start().waitFor() == 0, "make replay");
      for (HighlightReel.Transition transition : HighlightReel.Transition.values()) {
         for (int variant = 0; variant < 2; variant++) {
            boolean full = variant == 0;
            double t = HighlightReel.transitionSeconds(transition, grid);
            List<Segment> segments = new ArrayList<>();
            for (int i = 0; i < ordered.size(); i++) {
               Clip clip = ordered.get(i);
               HighlightReel.Info info = HighlightReel.probe("ffmpeg", clip.file());
               check(info != null && info.width() >= 320, "probe " + clip.file());
               segments.add(HighlightReel.segment(clip, info, 5.0, 2.0, grid, HighlightReel.role(i, ordered.size()), t));
               if (i == 0 && full) {
                  segments.add(HighlightReel.replaySegment(replayVideo, HighlightReel.probe("ffmpeg", replayVideo), grid, t));
               }
            }

            check(segments.stream().anyMatch(s -> !s.audio()) && segments.get(0).audio(), "audio flags");
            boolean vertical = full && transition == HighlightReel.Transition.MIX;
            int width = vertical ? 360 : 640;
            int height = vertical ? 640 : 360;
            List<HighlightReel.Overlay> overlays = full ? HighlightReel.overlays(segments, t, new HighlightReel.Popup(0, "HIGHLIGHTS", "NOV 10 - 5 KILLS", ReelText.Style.TITLE),
               new HighlightReel.Popup(0, "GG", "5 KILLS", ReelText.Style.TITLE), width, height, dir.resolve("text-" + transition)) : List.of();
            if (full) {
               check(overlays.size() >= 5, "popups and titles: " + overlays.size());
               check(overlays.stream().allMatch(o -> Files.isRegularFile(o.png())), "popup pictures");
            }

            Path out = dir.resolve("reel-" + transition + (full ? "-full" : "-plain") + ".mp4");
            double total = HighlightReel.length(segments, t);
            double[] seen = {0.0};
            HighlightReel.Style style = new HighlightReel.Style(transition, t, full ? music : null, 0.0, 0.8, 0.6, width, height, 60, vertical, overlays);
            HighlightReel.render(HighlightReel.command("ffmpeg", segments, style, out), total, out, p -> seen[0] = Math.max(seen[0], p));
            HighlightReel.Info made = HighlightReel.probe("ffmpeg", out);
            check(made != null && made.audio() && made.width() == width && made.height() == height, "reel output " + made);
            check(Math.abs(made.seconds() - total) < 0.15, transition + " reel length " + made.seconds() + " vs " + total);
            check(seen[0] > 0.5, "progress " + seen[0]);
         }
      }

      try {
         HighlightReel.render(List.of("ffmpeg", "-loglevel", "error", "-i", dir.resolve("missing.mp4").toString(), dir.resolve("bad.mp4").toString()), 1.0, dir.resolve("bad.mp4"), p -> {
         });
         check(false, "missing input must fail");
      } catch (java.io.IOException expected) {
         check(!expected.getMessage().isEmpty(), "error text");
      }
   }

   private static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError(what);
      }
   }
}
