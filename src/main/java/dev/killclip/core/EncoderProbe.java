package dev.killclip.core;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Finds which video encoders really work on this PC by encoding a short blank clip with each one. A hardware
 * encoder can be built into FFmpeg and still fail (no such GPU, old driver), so only a real test tells.
 */
public final class EncoderProbe {
   /** Best first: NVIDIA H.265 makes the smallest files, then the other GPU encoders, then the CPU. */
   public static final List<String> PREFERENCE = List.of("hevc_nvenc", "h264_nvenc", "h264_amf", "h264_qsv", "libx264");
   private static final int WIDTH = 1280;
   private static final int HEIGHT = 720;
   private static final int FRAMES = 20;

   private EncoderProbe() {
   }

   /** Tests every encoder in {@link #PREFERENCE}; the map keeps that order. Takes a few seconds: call off the render thread. */
   public static Map<String, Boolean> testAll(String ffmpeg) {
      Map<String, Boolean> result = new LinkedHashMap<>();
      for (String encoder : PREFERENCE) {
         result.put(encoder, works(ffmpeg, encoder));
      }

      return result;
   }

   /** The first working encoder in {@link #PREFERENCE}, or libx264 when none of the GPU ones work. */
   public static String best(Map<String, Boolean> results) {
      for (String encoder : PREFERENCE) {
         if (Boolean.TRUE.equals(results.get(encoder))) {
            return encoder;
         }
      }

      return "libx264";
   }

   public static boolean works(String ffmpeg, String encoder) {
      List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
         "-f", "rawvideo", "-pix_fmt", "nv12", "-s", WIDTH + "x" + HEIGHT, "-framerate", "60", "-i", "pipe:0"));
      cmd.addAll(ReplayBuffer.codecArgs(encoder, 23));
      cmd.addAll(List.of("-pix_fmt", "yuv420p", "-f", "null", "-"));
      cmd.set(cmd.indexOf("-nostdin"), "-y");
      Process process = null;
      try {
         process = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
         byte[] frame = new byte[WIDTH * HEIGHT * 3 / 2];
         java.util.Arrays.fill(frame, WIDTH * HEIGHT, frame.length, (byte)128);
         try (OutputStream in = process.getOutputStream()) {
            for (int i = 0; i < FRAMES; i++) {
               in.write(frame);
            }
         } catch (IOException ignored) {
            // FFmpeg quits early when the encoder can't open; the exit code says so.
         }

         if (!process.waitFor(15L, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
         }

         return process.exitValue() == 0;
      } catch (IOException e) {
         return false;
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         process.destroyForcibly();
         return false;
      }
   }

   /** Settings picked for this PC. */
   public record Tuning(String encoder, int fps, int resolution) {
   }

   /**
    * Picks clip settings for the machine: a GPU encoder records at the screen's refresh rate up to 120 fps and
    * 1080p with almost no fps cost; the CPU encoder needs lighter settings so the game keeps its frame rate.
    */
   public static Tuning tune(String encoder, int refreshRate, int cores, int screenHeight) {
      boolean gpu = !encoder.equals("libx264");
      int fps;
      int resolution;
      if (gpu) {
         fps = refreshRate >= 100 && cores >= 4 ? 120 : 60;
         resolution = 1080;
      } else {
         fps = cores >= 8 ? 60 : 30;
         resolution = cores >= 8 ? 1080 : 720;
      }

      if (screenHeight > 0 && screenHeight < resolution) {
         resolution = 0;
      }

      return new Tuning(encoder, fps, resolution);
   }
}
