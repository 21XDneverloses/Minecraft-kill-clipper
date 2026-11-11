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
 * The highlight reel: picks the best kill clips of a session, cuts each down to the moment around the kill,
 * and joins them into one montage with transitions and music, in a single FFmpeg run.
 */
public final class HighlightReel {
   /** Clip names the recorder writes: 2026-11-10_21-04-33_kill.mp4 or ..._3-kill-streak.mp4. */
   private static final Pattern NAME = Pattern.compile("(\\d{4}-\\d\\d-\\d\\d_\\d\\d-\\d\\d-\\d\\d)_(kill|(\\d+)-kill-streak)\\.[A-Za-z0-9]+");
   private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
   private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
   private static final Pattern SIZE = Pattern.compile("Video: .*?[ ,](\\d{2,5})x(\\d{2,5})[ ,\\[]");
   private static final Pattern PROGRESS = Pattern.compile("out_time_(?:us|ms)=(\\d+)");
   /** Clips less than this apart belong to the same play session. */
   private static final long SESSION_GAP_MS = 2 * 3600_000L;
   /** Seconds kept after the kill, so it lands before the cut. */
   private static final double AFTER_KILL = 1.5;
   private static final String[] MIXED = {"fade", "fadewhite", "slideleft", "circleopen", "smoothup", "fadeblack", "slideright", "radial"};

   private HighlightReel() {
   }

   /** A saved kill clip: when it was made and how many kills are in it. */
   public record Clip(Path file, long timeMs, int kills) {
   }

   /** What FFmpeg says about a video. */
   public record Info(double seconds, int width, int height, boolean audio) {
   }

   /** The stretch of one clip that goes in the reel. */
   public record Segment(Path file, double start, double length, boolean audio) {
   }

   public enum Range {
      LATEST("Latest session"), DAY("Last 24 hours"), WEEK("Last 7 days"), ALL("All clips");

      public final String label;

      Range(String label) {
         this.label = label;
      }
   }

   public enum Transition {
      MIX("Mixed", 0.4), FADE("Fade", 0.4), FLASH("Flash", 0.3), SLIDE("Slide", 0.35), CUT("Hard cut", 0.0);

      public final String label;
      public final double seconds;

      Transition(String label, double seconds) {
         this.label = label;
         this.seconds = seconds;
      }

      String xfade(int index) {
         return switch (this) {
            case FADE -> "fade";
            case FLASH -> "fadewhite";
            case SLIDE -> index % 2 == 0 ? "slideleft" : "slideright";
            default -> MIXED[index % MIXED.length];
         };
      }
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

   /** Every kill clip in the clips folder, oldest first. */
   public static List<Clip> scan(Path clipsDir) throws IOException {
      List<Clip> clips = new ArrayList<>();
      if (!Files.isDirectory(clipsDir)) {
         return clips;
      }

      try (var files = Files.list(clipsDir)) {
         for (Path file : files.filter(Files::isRegularFile).toList()) {
            Clip clip = clip(file, Files.getLastModifiedTime(file).toMillis());
            if (clip != null) {
               clips.add(clip);
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

   /** The best {@code max} clips (streaks first, biggest first, then the newest kills), in the order they happened. */
   public static List<Clip> pick(List<Clip> candidates, int max) {
      List<Clip> best = new ArrayList<>(candidates);
      best.sort(Comparator.comparingInt(Clip::kills).reversed().thenComparing(Comparator.comparingLong(Clip::timeMs).reversed()));
      best = new ArrayList<>(best.subList(0, Math.min(max, best.size())));
      best.sort(Comparator.comparingLong(Clip::timeMs));
      return best;
   }

   /**
    * Which part of a clip to show. A clip runs from {@code preRoll} seconds before its first kill to {@code postRoll}
    * after its last, so the kill sits near the end: a single kill gets the three bars of music leading up to it, a
    * streak gets all its kills (up to eight bars). Lengths are whole bars so every cut lands on the beat.
    */
   public static Segment segment(Clip clip, Info info, double preRoll, double postRoll, double transition) {
      double length = info.seconds();
      double end = Math.min(length, Math.max(0.0, length - postRoll) + AFTER_KILL);
      double bar = ReelMusic.BAR_SECONDS;
      int bars = 3;
      if (clip.kills() > 1) {
         double firstKill = Math.min(preRoll, length);
         double wanted = end - Math.max(0.0, firstKill - 3.0);
         bars = (int)Math.max(3, Math.min(8, Math.ceil((wanted - transition) / bar)));
      }

      double start = Math.max(0.0, end - (bars * bar + transition));
      return new Segment(clip.file(), start, end - start, info.audio());
   }

   /** How long the finished reel is. */
   public static double length(List<Segment> segments, Transition transition) {
      double total = 0.0;
      for (Segment segment : segments) {
         total += segment.length();
      }

      return total - Math.max(0, segments.size() - 1) * overlap(segments, transition);
   }

   /** The transition time, shortened if a clip is too short to carry one at each end. */
   static double overlap(List<Segment> segments, Transition transition) {
      double shortest = segments.stream().mapToDouble(Segment::length).min().orElse(0.0);
      return Math.max(0.0, Math.min(transition.seconds, shortest / 3.0));
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

   /** The FFmpeg command that builds the reel. {@code music} may be null; it loops and fades out with the picture. */
   public static List<String> command(String ffmpeg, List<Segment> segments, Transition transition, Path music, double musicVolume, double gameVolume,
      int width, int height, int fps, Path output) {
      int n = segments.size();
      double t = n > 1 ? overlap(segments, transition) : 0.0;
      boolean cut = t < 0.05;
      double total = length(segments, transition);
      List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y"));
      StringBuilder graph = new StringBuilder();
      for (int i = 0; i < n; i++) {
         Segment segment = segments.get(i);
         cmd.addAll(List.of("-ss", seconds(segment.start()), "-t", seconds(segment.length() + 0.5), "-i", segment.file().toString()));
         String len = seconds(segment.length());
         graph.append('[').append(i).append(":v]trim=duration=").append(len).append(",setpts=PTS-STARTPTS,fps=").append(fps)
            .append(",scale=").append(width).append(':').append(height).append(":force_original_aspect_ratio=decrease,pad=").append(width).append(':').append(height)
            .append(":(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p[v").append(i).append("];");
         if (segment.audio()) {
            graph.append('[').append(i).append(":a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,asetpts=PTS-STARTPTS,apad,atrim=duration=").append(len);
         } else {
            graph.append("anullsrc=r=48000:cl=stereo,aformat=sample_fmts=fltp,atrim=duration=").append(len);
         }

         graph.append("[a").append(i).append("];");
      }

      if (music != null) {
         cmd.addAll(List.of("-stream_loop", "-1", "-i", music.toString()));
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
            graph.append('[').append(video).append("][v").append(i).append("]xfade=transition=").append(transition.xfade(i - 1))
               .append(":duration=").append(seconds(t)).append(":offset=").append(seconds(offset)).append("[vx").append(i).append("];");
            graph.append('[').append(audio).append("][a").append(i).append("]acrossfade=d=").append(seconds(t)).append(":c1=tri:c2=tri[ax").append(i).append("];");
            video = "vx" + i;
            audio = "ax" + i;
         }
      }

      double fadeOut = Math.min(1.0, total / 4.0);
      graph.append('[').append(video).append("]fade=t=in:d=0.3,fade=t=out:st=").append(seconds(total - fadeOut)).append(":d=").append(seconds(fadeOut)).append("[v];");
      double musicFade = Math.min(2.5, total / 3.0);
      if (music != null) {
         graph.append('[').append(audio).append("]volume=").append(seconds(gameVolume)).append("[game];");
         graph.append('[').append(n).append(":a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,volume=").append(seconds(musicVolume))
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
