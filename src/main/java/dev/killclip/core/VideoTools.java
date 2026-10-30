package dev.killclip.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FFmpeg helpers for the clip editor: length, preview frames, and trimming or converting any video. */
public final class VideoTools {
   /** Video types the editor lists and can import. */
   public static final Set<String> VIDEO_TYPES = Set.of("mp4", "mov", "mkv", "avi", "webm", "m4v", "flv", "wmv", "ts");
   private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

   private VideoTools() {
   }

   public static boolean isVideo(Path file) {
      return VIDEO_TYPES.contains(extension(file));
   }

   public static String extension(Path file) {
      String name = file.getFileName().toString();
      int dot = name.lastIndexOf('.');
      return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
   }

   /** Length in seconds, or -1 if FFmpeg can't read it. */
   public static double duration(String ffmpeg, Path video) throws IOException, InterruptedException {
      Process process = new ProcessBuilder(ffmpeg, "-hide_banner", "-nostdin", "-i", video.toString())
         .redirectErrorStream(true)
         .start();
      String output = new String(process.getInputStream().readAllBytes());
      process.waitFor(20L, TimeUnit.SECONDS);
      Matcher m = DURATION.matcher(output);
      if (!m.find()) {
         return -1.0;
      }

      return Integer.parseInt(m.group(1)) * 3600.0 + Integer.parseInt(m.group(2)) * 60.0 + Double.parseDouble(m.group(3));
   }

   /** Saves one frame at {@code seconds} as a PNG {@code width} pixels wide. */
   public static void frame(String ffmpeg, Path video, double seconds, int width, Path png) throws IOException, InterruptedException {
      run(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-ss", seconds(seconds), "-i", video.toString(),
         "-frames:v", "1", "-vf", "scale=" + width + ":-2", png.toString()), 30L);
      if (!Files.isRegularFile(png)) {
         throw new IOException("no frame at " + seconds(seconds) + " s");
      }
   }

   /**
    * Cuts [start, end] seconds out of {@code input} into {@code output}, whose extension picks the file type.
    * Copies the streams when possible (fast, no quality loss), otherwise re-encodes to H.264 and AAC.
    */
   public static void trim(String ffmpeg, Path input, double start, double end, Path output) throws IOException, InterruptedException {
      Files.deleteIfExists(output);
      List<String> base = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
         "-ss", seconds(start), "-t", seconds(Math.max(0.1, end - start)), "-i", input.toString()));
      String type = extension(output);
      boolean quickTime = type.equals("mp4") || type.equals("mov");
      List<String> copy = new ArrayList<>(base);
      copy.addAll(List.of("-c", "copy"));
      if (quickTime) {
         copy.addAll(List.of("-movflags", "+faststart"));
      }

      copy.add(output.toString());
      if (run(copy, 120L) && Files.isRegularFile(output) && Files.size(output) > 0L) {
         return;
      }

      List<String> encode = new ArrayList<>(base);
      encode.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k"));
      if (quickTime) {
         encode.addAll(List.of("-movflags", "+faststart"));
      }

      encode.add(output.toString());
      if (!run(encode, 600L) || !Files.isRegularFile(output)) {
         throw new IOException("FFmpeg could not convert " + input.getFileName());
      }
   }

   /** Makes a vertical 9:16 copy for Shorts, TikTok and Reels: the middle of the picture, full height. */
   public static void vertical(String ffmpeg, Path input, Path output) throws IOException, InterruptedException {
      Files.deleteIfExists(output);
      List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", input.toString(),
         "-vf", "crop=trunc(ih*9/16/2)*2:ih,scale=-2:'min(1920,ih)'", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
         "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k"));
      String type = extension(output);
      if (type.equals("mp4") || type.equals("mov")) {
         cmd.addAll(List.of("-movflags", "+faststart"));
      }

      cmd.add(output.toString());
      if (!run(cmd, 600L) || !Files.isRegularFile(output)) {
         throw new IOException("FFmpeg could not make the vertical video");
      }
   }

   /**
    * Re-encodes a video so it fits in {@code maxBytes} (Discord's upload limit), as H.264 MP4. Returns
    * {@code input} itself when it already fits.
    */
   public static Path fitSize(String ffmpeg, Path input, long maxBytes, Path output) throws IOException, InterruptedException {
      if (Files.size(input) <= maxBytes && extension(input).equals("mp4")) {
         return input;
      }

      double seconds = Math.max(1.0, duration(ffmpeg, input));
      long audioBits = 96_000L;
      // Leave 8% for the container; never go below a watchable rate.
      long videoBits = Math.max(300_000L, (long)(maxBytes * 8 * 0.92 / seconds) - audioBits);
      String height = videoBits < 1_500_000L ? "720" : "1080";
      // A 2-minute clip gets about 0.6 Mbps; spread over 120 frames a second that is a blur, so lower the frame rate.
      String fps = videoBits < 1_000_000L ? "30" : videoBits < 3_000_000L ? "60" : null;
      for (long bits = videoBits; bits >= 200_000L; bits = bits * 3 / 4) {
         Files.deleteIfExists(output);
         List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", input.toString(),
            "-vf", "scale=-2:'min(" + height + ",ih)'"));
         if (fps != null) {
            cmd.addAll(List.of("-r", fps));
         }

         cmd.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-b:v", String.valueOf(bits),
            "-maxrate", String.valueOf(bits * 3 / 2), "-bufsize", String.valueOf(bits * 2), "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", output.toString()));
         if (run(cmd, 600L) && Files.isRegularFile(output) && Files.size(output) <= maxBytes) {
            return output;
         }
      }

      throw new IOException("could not make the clip small enough to upload");
   }

   private static boolean run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      // Trims, 9:16 copies and Discord shrinks run on the CPU, often while you play: keep them behind the game.
      ProcessPriority.lower(process);
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
         process.destroyForcibly();
         return false;
      }

      return process.exitValue() == 0;
   }

   private static String seconds(double value) {
      return String.format(Locale.ROOT, "%.3f", Math.max(0.0, value));
   }
}
