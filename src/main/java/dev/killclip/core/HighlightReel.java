package dev.killclip.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The highlight reel: rates every kill clip, picks the best of a session, cuts each down to the moment around its
 * kills on the beat of the music, and joins them into one montage with transitions, kill popups, a slow-motion
 * finale and an intro and closing title, in a single FFmpeg run.
 *
 * <p>All of it is automatic: the reel opens with the best clip (the hook), the others follow in the order they
 * happened, and the second best closes it. Each clip runs a whole number of bars so every cut lands on a bar line,
 * the last two beats before a kill speed up slightly, and the kill itself lands on the beat.
 */
public final class HighlightReel {
   /** Clip names the recorder writes: 2026-11-10_21-04-33_kill.mp4 or ..._3-kill-streak.mp4, plus a title after a rename (not the _vertical copies). */
   private static final Pattern NAME = Pattern.compile("(\\d{4}-\\d\\d-\\d\\d_\\d\\d-\\d\\d-\\d\\d)_(kill|(\\d+)-kill-streak)(?:_(?!vertical\\.)[^.]*)?\\.[A-Za-z0-9]+");
   private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
   private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
   private static final Pattern SIZE = Pattern.compile("Video: .*?[ ,](\\d{2,5})x(\\d{2,5})[ ,\\[]");
   private static final Pattern PROGRESS = Pattern.compile("out_time_(?:us|ms)=(\\d+)");
   /** Clips less than this apart belong to the same play session. */
   private static final long SESSION_GAP_MS = 2 * 3600_000L;
   /** Clips saved this close together show the same fight; the reel keeps only the better one. */
   public static final long SAME_FIGHT_MS = 12_000L;
   /** An automatic reel stops adding clips at about a minute: long enough to show off, short enough to watch. */
   public static final double AUTO_TARGET_SECONDS = 60.0;
   /** An automatic reel keeps clips at least this good compared with the best one (but always at least three). */
   private static final double AUTO_MIN_SHARE = 0.35;
   /** The last two beats before a kill play this much faster, building into the hit. */
   static final double RAMP_SPEED = 1.25;
   /** The finale's kill plays at this speed. */
   static final double SLOW_MO = 0.4;
   /** The finale's slow motion starts this long before the kill, so the last hit is seen. */
   static final double SLOW_MO_LEAD = 0.3;
   /** Pieces slower than this are a held frame. */
   private static final double HOLD = 0.1;
   private static final String[] SMOOTH = {"fade", "slideleft", "circleopen", "smoothup", "slideright", "radial", "fadeblack"};

   private HighlightReel() {
   }

   /**
    * A saved kill clip: when it was made, how many kills are in it and, for clips from v1.14 on, what each kill was.
    * {@code away} lists where the game showed no fight (loading screens, a world switch) for clips from v1.16 on;
    * it is null for older clips, whose pictures are checked instead.
    */
   public record Clip(Path file, long timeMs, int kills, List<ClipMeta.Kill> moments, List<LoadingScreens.Span> away) {
      public Clip(Path file, long timeMs, int kills) {
         this(file, timeMs, kills, List.of(), null);
      }

      public Clip(Path file, long timeMs, int kills, List<ClipMeta.Kill> moments) {
         this(file, timeMs, kills, moments, null);
      }

      public Clip {
         moments = List.copyOf(moments);
         away = away == null ? null : List.copyOf(away);
         kills = Math.max(kills, moments.size());
      }

      /** The first and last kill, in the video's seconds, or the estimate from the clip settings for older clips. */
      public double[] killSpan(double videoSeconds, double preRoll, double postRoll) {
         if (!this.moments.isEmpty()) {
            return new double[]{this.moments.getFirst().at(), this.moments.getLast().at()};
         }

         double last = Math.max(0.0, Math.min(videoSeconds, videoSeconds - postRoll));
         return new double[]{this.kills > 1 ? Math.min(preRoll, last) : last, last};
      }
   }

   /** What FFmpeg says about a video. */
   public record Info(double seconds, int width, int height, boolean audio) {
   }

   /** Where a clip sits in the reel: the opener, the closer, or in between. */
   public enum Role {
      HOOK, NORMAL, FINALE
   }

   /** A stretch of a video played at one speed: {@code start} and {@code length} in the video's seconds. */
   public record Piece(double start, double length, double speed) {
      /** How long it lasts in the reel. */
      public double out() {
         return this.length / this.speed;
      }
   }

   /** Text over the video, {@code at} seconds into the segment (or into the reel, for titles). */
   public record Popup(double at, String main, String sub, ReelText.Style style) {
   }

   /**
    * The part of one clip in the reel, as pieces in order. {@code replay} marks an instant replay from the fight's
    * replay, which comes in with a white flash.
    */
   public record Segment(Path file, List<Piece> pieces, boolean audio, List<Popup> popups, boolean replay) {
      public Segment {
         pieces = List.copyOf(pieces);
         popups = List.copyOf(popups);
      }

      /** A plain cut of [start, start + length] at normal speed. */
      public Segment(Path file, double start, double length, boolean audio) {
         this(file, List.of(new Piece(start, length, 1.0)), audio, List.of(), false);
      }

      public double start() {
         return this.pieces.getFirst().start();
      }

      /** How long it lasts in the reel. */
      public double length() {
         double total = 0.0;
         for (Piece piece : this.pieces) {
            total += piece.out();
         }

         return total;
      }

      /** Where {@code source} seconds of the video show up in the segment, or -1 if they are cut out. */
      public double outAt(double source) {
         double out = 0.0;
         for (Piece piece : this.pieces) {
            if (source >= piece.start() - 1e-6 && source <= piece.start() + piece.length() + 1e-6) {
               return out + (source - piece.start()) / piece.speed();
            }

            out += piece.out();
         }

         return -1.0;
      }
   }

   /** A picture laid over the reel from {@code start} for {@code duration} seconds, its top at {@code top} of the height. */
   public record Overlay(Path png, double start, double duration, double top, boolean pop) {
   }

   public enum Range {
      LATEST("Latest session"), DAY("Last 24 hours"), WEEK("Last 7 days"), ALL("All clips");

      public final String label;

      Range(String label) {
         this.label = label;
      }
   }

   public enum Transition {
      MIX("Auto", 0.4), FADE("Fade", 0.4), FLASH("Flash", 0.3), SLIDE("Slide", 0.35), CUT("Hard cut", 0.0);

      public final String label;
      public final double seconds;

      Transition(String label, double seconds) {
         this.label = label;
         this.seconds = seconds;
      }

      /** The xfade into {@code next}: the automatic mix flashes into replays, zooms into streaks, and varies the rest. */
      String xfade(int index, Segment next) {
         return switch (this) {
            case FADE -> "fade";
            case FLASH -> "fadewhite";
            case SLIDE -> index % 2 == 0 ? "slideleft" : "slideright";
            default -> next != null && next.replay() ? "fadewhite" : next != null && streak(next) ? "zoomin" : SMOOTH[index % SMOOTH.length];
         };
      }
   }

   private static boolean streak(Segment segment) {
      return segment.popups().stream().anyMatch(p -> p.style() == ReelText.Style.STREAK);
   }

   /** The kill clip a file name stands for, or null for anything else (edits, imports, test clips). */
   public static Clip clip(Path file, long modifiedMs) {
      Matcher m = NAME.matcher(file.getFileName().toString());
      if (!m.matches() || !VideoTools.isVideo(file)) {
         return null;
      }

      long time = modifiedMs;
      try {
         time = LocalDateTime.parse(m.group(1), FILE_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
      } catch (RuntimeException ignored) {
         // Keep the file's own time.
      }

      int kills = 1;
      if (m.group(3) != null) {
         try {
            kills = Math.max(1, Integer.parseInt(m.group(3)));
         } catch (NumberFormatException ignored) {
            kills = 2;
         }
      }

      return new Clip(file, time, kills);
   }

   /** Every kill clip in the clips folder with what is known about its kills, oldest first. */
   public static List<Clip> scan(Path clipsDir) throws IOException {
      List<Clip> clips = new ArrayList<>();
      if (!Files.isDirectory(clipsDir)) {
         return clips;
      }

      try (var files = Files.list(clipsDir)) {
         for (Path file : files.filter(Files::isRegularFile).toList()) {
            Clip clip = clip(file, Files.getLastModifiedTime(file).toMillis());
            if (clip != null) {
               clips.add(new Clip(file, clip.timeMs(), clip.kills(), ClipMeta.read(file), ClipMeta.readAway(file)));
            }
         }
      }

      clips.sort(Comparator.comparingLong(Clip::timeMs));
      return clips;
   }

   /** The clips in {@code range}, oldest first. */
   public static List<Clip> inRange(List<Clip> all, Range range, long nowMs) {
      List<Clip> sorted = new ArrayList<>(all);
      sorted.sort(Comparator.comparingLong(Clip::timeMs));
      if (range == Range.ALL || sorted.isEmpty()) {
         return sorted;
      }

      if (range == Range.LATEST) {
         // Walk back from the newest clip until there is a long break.
         int first = sorted.size() - 1;
         while (first > 0 && sorted.get(first).timeMs() - sorted.get(first - 1).timeMs() < SESSION_GAP_MS) {
            first--;
         }

         return new ArrayList<>(sorted.subList(first, sorted.size()));
      }

      long since = nowMs - (range == Range.DAY ? 24L : 7 * 24L) * 3600_000L;
      return sorted.stream().filter(c -> c.timeMs() >= since).toList();
   }

   /**
    * How good a clip is. More kills count for much more (a triple beats three singles), then quick kills in a row,
    * crystal and anchor kills, long-range hits, and winning on low health. Clips from before kill details were saved
    * are rated on their kill count alone.
    */
   public static double score(Clip clip) {
      int k = clip.kills();
      double score = 10.0 * k + 6.0 * k * (k - 1);
      List<ClipMeta.Kill> moments = clip.moments();
      float lowest = Float.MAX_VALUE;
      for (int i = 0; i < moments.size(); i++) {
         ClipMeta.Kill kill = moments.get(i);
         if (i > 0) {
            double gap = kill.at() - moments.get(i - 1).at();
            score += gap <= 1.5 ? 14.0 : gap <= 3.0 ? 8.0 : gap <= 5.0 ? 3.0 : 0.0;
         }

         score += switch (kill.cause()) {
            case EXPLOSION -> 6.0;
            case RANGED -> 4.0;
            default -> 0.0;
         };
         score += kill.distance() >= 30.0F ? 14.0 : kill.distance() >= 15.0F ? 7.0 : 0.0;
         if (kill.health() > 0.0F) {
            lowest = Math.min(lowest, kill.health());
         }
      }

      score += lowest <= 4.0F ? 12.0 : lowest <= 8.0F ? 5.0 : 0.0;
      return score;
   }

   /** Two clips saved within a few seconds of each other show the same fight. */
   public static boolean sameFight(Clip a, Clip b) {
      return Math.abs(a.timeMs() - b.timeMs()) <= SAME_FIGHT_MS;
   }

   /** Roughly how long a clip lasts in the reel, before its video is read. */
   public static double estimate(Clip clip) {
      return (clip.kills() > 1 ? 5 : 3) * ReelMusic.BAR_SECONDS;
   }

   /** Roughly how long a clip lasts in a reel cut to {@code grid}. */
   public static double estimate(Clip clip, BeatDetector.Grid grid) {
      return pace(grid).clipSeconds(clip.kills());
   }

   /**
    * The clips for the reel, in the order they play. {@code max} above 0 takes that many of the best; 0 picks
    * automatically: every clip good enough next to the best one (at least three), up to about a minute of reel.
    * Of clips from the same fight only the best is used.
    */
   public static List<Clip> pick(List<Clip> candidates, int max) {
      return pick(candidates, max, AUTO_TARGET_SECONDS, BeatDetector.builtIn());
   }

   /**
    * Like {@link #pick(List, int)}, but an automatic pick fills {@code targetSeconds} of reel cut to {@code grid}.
    * A target longer than the usual minute (the length of your own song) keeps every different fight until the song
    * is full, best first, instead of only the ones close to the best.
    */
   public static List<Clip> pick(List<Clip> candidates, int max, double targetSeconds, BeatDetector.Grid grid) {
      List<Clip> ranked = new ArrayList<>(candidates);
      ranked.sort(Comparator.comparingDouble(HighlightReel::score).reversed().thenComparing(Comparator.comparingLong(Clip::timeMs).reversed()));
      List<Clip> chosen = new ArrayList<>();
      for (Clip clip : ranked) {
         if (chosen.stream().noneMatch(c -> sameFight(c, clip))) {
            chosen.add(clip);
         }
      }

      if (max > 0) {
         chosen = new ArrayList<>(chosen.subList(0, Math.min(max, chosen.size())));
      } else if (!chosen.isEmpty()) {
         double best = score(chosen.getFirst());
         double share = targetSeconds > AUTO_TARGET_SECONDS + 1.0 ? 0.0 : AUTO_MIN_SHARE;
         List<Clip> kept = new ArrayList<>();
         double total = 0.0;
         for (Clip clip : chosen) {
            boolean goodEnough = kept.size() < 3 || score(clip) >= best * share;
            double length = estimate(clip, grid);
            if (!goodEnough || total + length > targetSeconds && kept.size() >= 3) {
               break;
            }

            kept.add(clip);
            total += length;
         }

         chosen = kept;
      }

      return order(chosen);
   }

   /**
    * Best clip first (the hook), second best last (the finale), the rest in the order they happened, but with the
    * streaks spread evenly between the single kills so the big moments don't bunch up in one part of the song.
    */
   public static List<Clip> order(List<Clip> clips) {
      if (clips.size() < 2) {
         return new ArrayList<>(clips);
      }

      List<Clip> ranked = new ArrayList<>(clips);
      ranked.sort(Comparator.comparingDouble(HighlightReel::score).reversed().thenComparing(Comparator.comparingLong(Clip::timeMs).reversed()));
      Clip hook = ranked.get(0);
      Clip finale = ranked.get(1);
      List<Clip> middle = new ArrayList<>(clips);
      middle.remove(hook);
      middle.remove(finale);
      middle.sort(Comparator.comparingLong(Clip::timeMs));
      List<Clip> ordered = new ArrayList<>();
      ordered.add(hook);
      ordered.addAll(spread(middle));
      ordered.add(finale);
      return ordered;
   }

   /**
    * Places the streaks evenly among the single kills, each group keeping the order it happened in: with 2 streaks
    * and 6 singles the streaks land about a third and two thirds of the way through.
    */
   static List<Clip> spread(List<Clip> clips) {
      List<Clip> streaks = clips.stream().filter(c -> c.kills() > 1).toList();
      List<Clip> singles = clips.stream().filter(c -> c.kills() <= 1).toList();
      if (streaks.isEmpty() || singles.isEmpty()) {
         return new ArrayList<>(clips);
      }

      List<Clip> out = new ArrayList<>();
      int s = 0;
      int total = clips.size();
      for (int i = 0; i < streaks.size(); i++) {
         // Where this streak's slot falls: evenly spaced, centred in its share of the reel.
         int slot = (int)Math.round((i + 0.5) * total / streaks.size() - 0.5);
         while (out.size() < slot && s < singles.size()) {
            out.add(singles.get(s++));
         }

         out.add(streaks.get(i));
      }

      while (s < singles.size()) {
         out.add(singles.get(s++));
      }

      return out;
   }

   /** The role of the clip at {@code index} of {@code count}: a single clip gets the finale's slow motion. */
   public static Role role(int index, int count) {
      return index == count - 1 ? Role.FINALE : index == 0 ? Role.HOOK : Role.NORMAL;
   }

   /** Songs at least this fast get quick cuts. */
   public static final double FAST_BPM = 140.0;
   /** Songs slower than this get long, unhurried cuts. */
   public static final double SLOW_BPM = 100.0;

   /**
    * How fast the reel cuts, from the tempo of the music. {@code unit} is the length everything is counted in (a bar
    * or half a bar), {@code lead} how many units run up to a kill, {@code maxLead} the most a streak may take, and
    * {@code after} how many units follow the kill. Fast songs cut every half bar with a short run-up; slow songs
    * let each clip play for whole bars.
    */
   public record Pace(String name, double unit, int lead, int maxLead, int after) {
      /** Roughly how long a clip lasts in the reel. */
      public double clipSeconds(int kills) {
         return this.unit * (kills > 1 ? Math.min(this.maxLead, this.lead + 2) + this.after : this.lead + this.after);
      }
   }

   public static Pace pace(BeatDetector.Grid grid) {
      double bpm = grid.bpm();
      double bar = grid.bar();
      if (bpm >= FAST_BPM) {
         double unit = bar / 2.0;
         return new Pace("fast", unit, 3, Math.max(3, (int)Math.ceil(10.0 / unit)), 1);
      }

      if (bpm < SLOW_BPM) {
         double unit = bar <= 3.4 ? bar : bar / 2.0;
         return new Pace("slow", unit, 2, Math.max(2, (int)Math.ceil(14.0 / unit)), 1);
      }

      return new Pace("medium", bar, 2, 7, 1);
   }

   /** The length the reel is cut in: half a bar for fast songs, a bar otherwise (half a bar for very slow ones). */
   public static double unit(BeatDetector.Grid grid) {
      return pace(grid).unit();
   }

   /** The transition time, a whole number of half beats so it moves with the music. */
   public static double transitionSeconds(Transition transition, BeatDetector.Grid grid) {
      if (transition.seconds <= 0.0) {
         return 0.0;
      }

      double half = grid.beat() / 2.0;
      return Math.max(1, Math.round(transition.seconds / half)) * half;
   }

   /** Popup text for the {@code n}th kill in a clip. */
   public static String killName(int n) {
      return switch (n) {
         case 1 -> "KILL";
         case 2 -> "DOUBLE KILL";
         case 3 -> "TRIPLE KILL";
         case 4 -> "QUAD KILL";
         case 5 -> "PENTA KILL";
         default -> n + " KILL STREAK";
      };
   }

   /** The small line under a kill popup: a clutch, a long shot or an explosion, or nothing. */
   static String killNote(ClipMeta.Kill kill) {
      if (kill == null) {
         return "";
      }

      if (kill.health() > 0.0F && kill.health() <= 4.0F) {
         double hearts = Math.max(0.5, Math.round(kill.health()) / 2.0);
         return "CLUTCH - " + (hearts == Math.floor(hearts) ? String.valueOf((int)hearts) : String.valueOf(hearts)) + (hearts == 1.0 ? " HEART LEFT" : " HEARTS LEFT");
      }

      if (kill.distance() >= 15.0F) {
         return (kill.cause() == ClipMeta.Cause.RANGED ? "LONG SHOT - " : "") + Math.round(kill.distance()) + " BLOCKS";
      }

      return kill.cause() == ClipMeta.Cause.EXPLOSION ? "EXPLOSIVE" : "";
   }

   /**
    * Which parts of a clip go in the reel and at what speed. A clip runs from {@code preRoll} seconds before its first
    * kill to {@code postRoll} after its last, so without saved kill times the last kill is near the end.
    *
    * <p>The reel's clock runs in whole units ({@link #pace}): the lead-up, then two beats sped up into the kill, which
    * lands on a unit line, then the units after it ({@code transition} extra for the crossfade). The finale also drops
    * into slow motion on the kill. A streak gets all its kills, up to the pace's longest lead-up.
    *
    * <p>Nothing is taken from inside or across {@code avoid} (loading screens, a world switch): the lead-up starts
    * after the last one before the kills, and the rest stops before the next one after them. If that leaves too
    * little after the kill, the end is slowed down, then its last clean frame is held.
    */
   public static Segment segment(Clip clip, Info info, double preRoll, double postRoll, BeatDetector.Grid grid, Role role, double transition,
      List<LoadingScreens.Span> avoid) {
      double length = info.seconds();
      List<Double> times = new ArrayList<>();
      boolean exact = !clip.moments().isEmpty();
      if (exact) {
         for (ClipMeta.Kill kill : clip.moments()) {
            times.add(Math.max(0.0, Math.min(length, kill.at())));
         }
      } else {
         double[] span = clip.killSpan(length, preRoll, postRoll);
         if (clip.kills() > 1) {
            times.add(span[0]);
         }

         times.add(span[1]);
      }

      Pace pace = pace(grid);
      double unit = pace.unit();
      double beat = grid.beat();
      double kill = times.getLast();
      double[] clear = LoadingScreens.clear(avoid == null ? List.of() : avoid, times.getFirst(), kill, length);
      double floor = clear[0];
      double ceil = Math.min(length, clear[1]);
      boolean finale = role == Role.FINALE;
      double lead = finale ? Math.min(SLOW_MO_LEAD, kill - floor) : 0.0;
      double cutAt = kill - lead;
      int preUnits = pace.lead();
      if (times.size() > 1) {
         preUnits = (int)Math.max(pace.lead(), Math.min(pace.maxLead(), Math.ceil((kill - times.getFirst() + 2.5) / unit)));
      }

      double rampSource = Math.max(0.0, Math.min(2.0 * beat * RAMP_SPEED, cutAt - floor));
      double rampOut = rampSource / RAMP_SPEED;
      double before = cutAt - rampSource;
      while (preUnits > 1 && preUnits * unit - rampOut > before - floor + 1e-9) {
         preUnits--;
      }

      double normalOut = Math.max(0.0, Math.min(preUnits * unit - rampOut, before - floor));
      List<Piece> pieces = new ArrayList<>();
      if (normalOut > 0.01) {
         pieces.add(new Piece(before - normalOut, normalOut, 1.0));
      }

      if (rampSource > 0.01) {
         pieces.add(new Piece(before, rampSource, RAMP_SPEED));
      }

      double at = cutAt;
      if (finale) {
         double slowSource = Math.min(unit * SLOW_MO, Math.max(0.05, ceil - at));
         pieces.add(new Piece(at, slowSource, SLOW_MO));
         at += slowSource;
      }

      // The units after the kill (and the crossfade). A clip that ends too soon, or is cut short by a loading screen,
      // is slowed down to fill them, so the next cut still lands on the beat.
      double wanted = pace.after() * unit + transition;
      double available = Math.max(0.05, ceil - at);
      double source = Math.min(available, wanted);
      double speed = source < wanted ? Math.max(0.5, source / wanted) : 1.0;
      pieces.add(new Piece(at, source, speed));
      double missing = wanted - source / speed;
      if (missing > 0.01) {
         // Even slowed down the clip runs out: hold its last frame for the rest of the unit.
         double frame = Math.min(0.04, source);
         pieces.add(new Piece(at + source - frame, frame, frame / missing));
      }

      if (pieces.getFirst().start() < floor) {
         Piece first = pieces.removeFirst();
         double cut = floor - first.start();
         pieces.addFirst(new Piece(floor, Math.max(0.01, first.length() - cut), first.speed()));
      }

      Segment plain = new Segment(clip.file(), pieces, info.audio(), List.of(), false);
      List<Popup> popups = new ArrayList<>();
      for (int i = 0; i < times.size(); i++) {
         if (!exact && i < times.size() - 1) {
            continue;
         }

         double out = plain.outAt(times.get(i));
         if (out < 0.0) {
            continue;
         }

         int n = exact ? i + 1 : clip.kills();
         ClipMeta.Kill moment = exact ? clip.moments().get(i) : null;
         popups.add(new Popup(out, killName(n), killNote(moment), n > 1 ? ReelText.Style.STREAK : ReelText.Style.KILL));
      }

      return new Segment(clip.file(), pieces, info.audio(), popups, false);
   }

   /** {@link #segment(Clip, Info, double, double, BeatDetector.Grid, Role, double, List)} avoiding the clip's saved loading screens. */
   public static Segment segment(Clip clip, Info info, double preRoll, double postRoll, BeatDetector.Grid grid, Role role, double transition) {
      return segment(clip, info, preRoll, postRoll, grid, role, transition, clip.away());
   }

   /**
    * Which seconds of a clip to check for loading screens by picture: the most the reel could take around the kills.
    * Empty for clips whose loading screens were tracked while recording.
    */
   public static double[] scanWindow(Clip clip, Info info, double preRoll, double postRoll, BeatDetector.Grid grid) {
      if (clip.away() != null) {
         return new double[]{0.0, 0.0};
      }

      double[] kills = clip.killSpan(info.seconds(), preRoll, postRoll);
      Pace pace = pace(grid);
      double from = Math.max(0.0, Math.min(kills[0], kills[1] - pace.maxLead() * pace.unit()) - 1.0);
      return new double[]{from, Math.max(0.0, info.seconds() - from)};
   }

   /** Keeps the planned segment fields but with the built-in tempo; for callers and tests from before beat detection. */
   public static Segment segment(Clip clip, Info info, double preRoll, double postRoll, double transition) {
      return segment(clip, info, preRoll, postRoll, BeatDetector.builtIn(), Role.NORMAL, transition);
   }

   /**
    * An instant replay of a kill from another camera, filmed from the fight's replay already in slow motion: two
    * units long, stretched a little if the video is short. The kill sits about two thirds of the way in; loading
    * frames (the replay's world still appearing, or the end of the fight's log) before or after it are left out.
    */
   public static Segment replaySegment(Path video, Info info, BeatDetector.Grid grid, double transition, List<LoadingScreens.Span> avoid) {
      double wanted = 2.0 * unit(grid) + transition;
      double kill = info.seconds() * 0.65;
      double[] clear = LoadingScreens.clear(avoid == null ? List.of() : avoid, kill, kill, info.seconds());
      double start = Math.max(clear[0], Math.min(kill - wanted * 0.6, clear[1] - wanted));
      start = Math.max(0.0, start);
      double source = Math.max(0.05, Math.min(clear[1] - start, wanted));
      double speed = source < wanted ? Math.max(0.5, source / wanted) : 1.0;
      List<Piece> pieces = new ArrayList<>(List.of(new Piece(start, source, speed)));
      double missing = wanted - source / speed;
      if (missing > 0.01) {
         double frame = Math.min(0.04, source);
         pieces.add(new Piece(start + source - frame, frame, frame / missing));
      }

      return new Segment(video, pieces, info.audio(), List.of(new Popup(0.05, "REPLAY", "", ReelText.Style.REPLAY)), true);
   }

   public static Segment replaySegment(Path video, Info info, BeatDetector.Grid grid, double transition) {
      return replaySegment(video, info, grid, transition, List.of());
   }

   /** How long the finished reel is. */
   public static double length(List<Segment> segments, double transition) {
      double total = 0.0;
      for (Segment segment : segments) {
         total += segment.length();
      }

      return total - Math.max(0, segments.size() - 1) * overlap(segments, transition);
   }

   public static double length(List<Segment> segments, Transition transition) {
      return length(segments, transition.seconds);
   }

   /** The transition time, shortened if a clip is too short to carry one at each end. */
   static double overlap(List<Segment> segments, double transition) {
      double shortest = segments.stream().mapToDouble(Segment::length).min().orElse(0.0);
      return Math.max(0.0, Math.min(transition, shortest / 3.0));
   }

   /** Where each segment starts in the reel. */
   public static double[] offsets(List<Segment> segments, double transition) {
      double t = segments.size() > 1 ? overlap(segments, transition) : 0.0;
      double[] offsets = new double[segments.size()];
      double at = 0.0;
      for (int i = 0; i < segments.size(); i++) {
         offsets[i] = at;
         at += segments.get(i).length() - t;
      }

      return offsets;
   }

   /**
    * Draws every popup and the titles as pictures in {@code dir} and says when each shows. Kill popups stay up
    * until the next one or about a second; the intro title shows over the opening clip before its first kill, and
    * the closing card over the last seconds.
    */
   public static List<Overlay> overlays(List<Segment> segments, double transition, Popup intro, Popup outro, int width, int height, Path dir) throws IOException {
      Files.createDirectories(dir);
      double[] offsets = offsets(segments, transition);
      double total = length(segments, transition);
      List<Popup> timed = new ArrayList<>();
      for (int i = 0; i < segments.size(); i++) {
         for (Popup popup : segments.get(i).popups()) {
            timed.add(new Popup(offsets[i] + popup.at(), popup.main(), popup.sub(), popup.style()));
         }
      }

      timed.sort(Comparator.comparingDouble(Popup::at));
      List<Overlay> overlays = new ArrayList<>();
      int n = 0;
      double firstPopup = timed.isEmpty() ? total : timed.getFirst().at();
      if (intro != null && firstPopup > 1.0) {
         Path png = dir.resolve("title-" + n++ + ".png");
         ReelText.write(intro.main(), intro.sub(), intro.style(), width, height, png);
         overlays.add(new Overlay(png, 0.15, Math.min(2.2, firstPopup - 0.35), 0.36, false));
      }

      double lastEnd = 0.0;
      for (int i = 0; i < timed.size(); i++) {
         Popup popup = timed.get(i);
         double end = Math.min(popup.at() + (popup.style() == ReelText.Style.REPLAY ? 1.4 : 1.25), i + 1 < timed.size() ? timed.get(i + 1).at() - 0.02 : total);
         if (end - popup.at() < 0.15) {
            continue;
         }

         Path png = dir.resolve("popup-" + n++ + ".png");
         ReelText.write(popup.main(), popup.sub(), popup.style(), width, height, png);
         overlays.add(new Overlay(png, popup.at(), end - popup.at(), popup.style() == ReelText.Style.REPLAY ? 0.08 : 0.64, true));
         lastEnd = end;
      }

      if (outro != null) {
         double start = Math.max(lastEnd + 0.1, total - 2.4);
         if (total - start >= 1.0) {
            Path png = dir.resolve("title-" + n + ".png");
            ReelText.write(outro.main(), outro.sub(), outro.style(), width, height, png);
            overlays.add(new Overlay(png, start, total - start - 0.1, 0.36, false));
         }
      }

      return overlays;
   }

   public static Info probe(String ffmpeg, Path video) throws IOException, InterruptedException {
      Process process = new ProcessBuilder(ffmpeg, "-hide_banner", "-nostdin", "-i", video.toString()).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes());
      process.waitFor(20L, TimeUnit.SECONDS);
      Matcher d = DURATION.matcher(output);
      Matcher s = SIZE.matcher(output);
      if (!d.find() || !s.find()) {
         return null;
      }

      double seconds = Integer.parseInt(d.group(1)) * 3600.0 + Integer.parseInt(d.group(2)) * 60.0 + Double.parseDouble(d.group(3));
      return new Info(seconds, Integer.parseInt(s.group(1)), Integer.parseInt(s.group(2)), output.contains("Audio:"));
   }

   /** Everything about how the reel is put together, apart from the clips. */
   public record Style(Transition transition, double transitionSeconds, Path music, double musicStart, double musicVolume, double gameVolume,
      int width, int height, int fps, boolean vertical, List<Overlay> overlays) {
   }

   /** The FFmpeg command for a reel of plain cuts at the built-in tempo; kept for simple callers. */
   public static List<String> command(String ffmpeg, List<Segment> segments, Transition transition, Path music, double musicVolume, double gameVolume,
      int width, int height, int fps, Path output) {
      return command(ffmpeg, segments, new Style(transition, transition.seconds, music, 0.0, musicVolume, gameVolume, width, height, fps, false, List.of()), output);
   }

   /** The FFmpeg command that builds the reel. The music may be null; it loops and fades out with the picture. */
   public static List<String> command(String ffmpeg, List<Segment> segments, Style style, Path output) {
      int n = segments.size();
      double t = n > 1 ? overlap(segments, style.transitionSeconds()) : 0.0;
      boolean cut = t < 0.05;
      double total = length(segments, style.transitionSeconds());
      int width = style.width();
      int height = style.height();
      List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y"));
      StringBuilder graph = new StringBuilder();
      for (int i = 0; i < n; i++) {
         Segment segment = segments.get(i);
         List<Piece> pieces = segment.pieces();
         double from = segment.start();
         double to = from;
         for (Piece piece : pieces) {
            to = Math.max(to, piece.start() + piece.length());
         }

         cmd.addAll(List.of("-ss", seconds(from), "-t", seconds(to - from + 0.5), "-i", segment.file().toString()));
         String len = seconds(segment.length());
         int k = pieces.size();
         // Each piece is cut from the same input and retimed, then the pieces are joined back together.
         graph.append('[').append(i).append(":v]");
         if (k > 1) {
            graph.append("split=").append(k);
            for (int p = 0; p < k; p++) {
               graph.append("[s").append(i).append('_').append(p).append(']');
            }

            graph.append(';');
         }

         for (int p = 0; p < k; p++) {
            Piece piece = pieces.get(p);
            if (k > 1) {
               graph.append("[s").append(i).append('_').append(p).append(']');
            }

            graph.append("trim=start=").append(seconds(piece.start() - from)).append(":duration=").append(seconds(piece.length()))
               .append(",setpts=(PTS-STARTPTS)/").append(speed(piece.speed())).append(",fps=").append(style.fps()).append("[p").append(i).append('_').append(p).append("];");
            if (k == 1) {
               break;
            }
         }

         if (segment.audio()) {
            graph.append('[').append(i).append(":a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo");
            if (k > 1) {
               graph.append(",asplit=").append(k);
               for (int p = 0; p < k; p++) {
                  graph.append("[r").append(i).append('_').append(p).append(']');
               }

               graph.append(';');
            }

            for (int p = 0; p < k; p++) {
               Piece piece = pieces.get(p);
               if (k > 1) {
                  graph.append("[r").append(i).append('_').append(p).append(']');
               } else {
                  graph.append(',');
               }

               // A held frame is silent; anything else keeps its sound, slowed or sped up with the picture.
               graph.append("atrim=start=").append(seconds(piece.start() - from)).append(":duration=").append(seconds(piece.length()))
                  .append(",asetpts=PTS-STARTPTS").append(piece.speed() < HOLD ? ",volume=0" : atempo(piece.speed())).append(",apad=whole_dur=").append(seconds(piece.out()))
                  .append(",atrim=duration=").append(seconds(piece.out())).append("[q").append(i).append('_').append(p).append("];");
            }
         } else {
            for (int p = 0; p < k; p++) {
               graph.append("anullsrc=r=48000:cl=stereo,aformat=sample_fmts=fltp,atrim=duration=").append(seconds(pieces.get(p).out()))
                  .append("[q").append(i).append('_').append(p).append("];");
            }
         }

         for (int p = 0; p < k; p++) {
            graph.append("[p").append(i).append('_').append(p).append("][q").append(i).append('_').append(p).append(']');
         }

         graph.append("concat=n=").append(k).append(":v=1:a=1[c").append(i).append("][d").append(i).append("];");
         graph.append("[c").append(i).append(']');
         if (style.vertical()) {
            // The middle of the picture, 9:16, for phones.
            graph.append("crop=trunc(ih*9/16/2)*2:ih,");
         }

         graph.append("scale=").append(width).append(':').append(height).append(":force_original_aspect_ratio=decrease,pad=").append(width).append(':').append(height)
            .append(":(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p,trim=duration=").append(len).append(",setpts=PTS-STARTPTS[v").append(i).append("];");
         graph.append("[d").append(i).append("]apad,atrim=duration=").append(len).append(",asetpts=PTS-STARTPTS[a").append(i).append("];");
      }

      int next = n;
      if (style.music() != null) {
         if (style.musicStart() > 0.01) {
            cmd.addAll(List.of("-ss", seconds(style.musicStart())));
         }

         cmd.addAll(List.of("-stream_loop", "-1", "-i", style.music().toString()));
         next++;
      }

      String video = "v0";
      String audio = "a0";
      if (n > 1 && cut) {
         for (int i = 0; i < n; i++) {
            graph.append("[v").append(i).append("][a").append(i).append(']');
         }

         graph.append("concat=n=").append(n).append(":v=1:a=1[vj][aj];");
         video = "vj";
         audio = "aj";
      } else if (n > 1) {
         double offset = 0.0;
         for (int i = 1; i < n; i++) {
            offset += segments.get(i - 1).length() - t;
            graph.append('[').append(video).append("][v").append(i).append("]xfade=transition=").append(style.transition().xfade(i - 1, segments.get(i)))
               .append(":duration=").append(seconds(t)).append(":offset=").append(seconds(offset)).append("[vx").append(i).append("];");
            graph.append('[').append(audio).append("][a").append(i).append("]acrossfade=d=").append(seconds(t)).append(":c1=tri:c2=tri[ax").append(i).append("];");
            video = "vx" + i;
            audio = "ax" + i;
         }
      }

      // Popups and titles: each picture fades in (kill popups also pop up from a little lower) and fades out.
      int o = 0;
      for (Overlay overlay : style.overlays()) {
         double d = Math.max(0.2, overlay.duration());
         cmd.addAll(List.of("-loop", "1", "-framerate", String.valueOf(style.fps()), "-t", seconds(d), "-i", overlay.png().toString()));
         String in = "o" + o;
         graph.append('[').append(next).append(":v]format=rgba,fade=t=in:st=0:d=0.1:alpha=1,fade=t=out:st=").append(seconds(Math.max(0.0, d - 0.25)))
            .append(":d=0.25:alpha=1,setpts=PTS-STARTPTS+").append(seconds(overlay.start())).append("/TB[").append(in).append("];");
         String y = "H*" + seconds(overlay.top());
         if (overlay.pop()) {
            y = y + "+h*0.35*max(0\\,1-(t-" + seconds(overlay.start()) + ")/0.12)";
         }

         graph.append('[').append(video).append("][").append(in).append("]overlay=x=(W-w)/2:y=").append(y).append(":eof_action=pass[ov").append(o).append("];");
         video = "ov" + o;
         next++;
         o++;
      }

      double fadeOut = Math.min(1.0, total / 4.0);
      graph.append('[').append(video).append("]fade=t=in:d=0.3,fade=t=out:st=").append(seconds(total - fadeOut)).append(":d=").append(seconds(fadeOut)).append(",format=yuv420p[v];");
      double musicFade = Math.min(2.5, total / 3.0);
      if (style.music() != null) {
         graph.append('[').append(audio).append("]volume=").append(seconds(style.gameVolume())).append("[game];");
         graph.append('[').append(n).append(":a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,volume=").append(seconds(style.musicVolume()))
            .append(",atrim=duration=").append(seconds(total)).append("[music];");
         graph.append("[game][music]amix=inputs=2:duration=longest:normalize=0,alimiter=limit=0.95,");
      } else {
         graph.append('[').append(audio).append(']');
      }

      graph.append("afade=t=in:d=0.3,afade=t=out:st=").append(seconds(total - musicFade)).append(":d=").append(seconds(musicFade)).append("[a]");
      cmd.addAll(List.of("-filter_complex", graph.toString(), "-map", "[v]", "-map", "[a]", "-t", seconds(total),
         "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-ar", "48000"));
      String type = VideoTools.extension(output);
      if (type.equals("mp4") || type.equals("mov")) {
         cmd.addAll(List.of("-movflags", "+faststart"));
      }

      cmd.addAll(List.of("-progress", "pipe:1", "-nostats", output.toString()));
      return cmd;
   }

   /** atempo only takes 0.5 to 100, so slower speeds are chained. */
   private static String atempo(double speed) {
      if (Math.abs(speed - 1.0) < 1e-6) {
         return "";
      }

      StringBuilder out = new StringBuilder();
      double left = speed;
      while (left < 0.5) {
         out.append(",atempo=0.5");
         left /= 0.5;
      }

      return out.append(",atempo=").append(speed(left)).toString();
   }

   private static String speed(double value) {
      return String.format(Locale.ROOT, "%.5f", value);
   }

   /** Builds the reel; {@code progress} gets 0..1 as it goes. Throws with FFmpeg's own words if it fails. */
   public static void render(List<String> command, double totalSeconds, Path output, DoubleConsumer progress) throws IOException, InterruptedException {
      Files.createDirectories(output.toAbsolutePath().getParent());
      Files.deleteIfExists(output);
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      ProcessPriority.lower(process);
      String lastError = "";
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
         String line;
         while ((line = reader.readLine()) != null) {
            Matcher m = PROGRESS.matcher(line);
            if (m.matches()) {
               progress.accept(Math.min(1.0, Long.parseLong(m.group(1)) / 1_000_000.0 / Math.max(0.1, totalSeconds)));
            } else if (!line.isBlank() && line.indexOf('=') < 0) {
               lastError = line.strip();
            }
         }
      } catch (IOException | RuntimeException e) {
         process.destroyForcibly();
         throw e;
      }

      if (!process.waitFor(30L, TimeUnit.SECONDS)) {
         process.destroyForcibly();
         throw new IOException("FFmpeg did not finish");
      }

      if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0L) {
         Files.deleteIfExists(output);
         throw new IOException(lastError.isEmpty() ? "FFmpeg could not make the reel" : lastError);
      }
   }

   private static String seconds(double value) {
      return String.format(Locale.ROOT, "%.3f", Math.max(0.0, value));
   }
}
