package dev.killclip.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Finds the parts of a clip that aren't the fight: loading and connecting screens, the black frames of a world
 * switch, and anything after you were sent to another world. The highlight reel keeps its cuts out of them.
 *
 * <p>Clips saved from this version on say when the game showed no world (see {@link ClipMeta}). Older clips, and
 * the instant replays, are checked by picture: a loading screen is the blurred menu background with a line of text,
 * so it has far less fine detail than any game picture (blocks, the hotbar, armour), or it is nearly black.
 */
public final class LoadingScreens {
   /** Frames per second looked at, and the size they are shrunk to. */
   static final int FPS = 10;
   static final int WIDTH = 160;
   static final int HEIGHT = 90;
   /**
    * Below this much fine detail a frame is a loading screen. Fight footage measured 4.5 and up (most of it 6 to 10);
    * the blurred menu background with a line of text measured about 2.5.
    */
   static final double DETAIL = 3.2;
   /** A frame darker than this (0..255) is black. */
   static final double DARK = 10.0;
   /** Kept clear of each side of a loading screen: the frames around it are the fade in and out. */
   public static final double MARGIN = 0.25;

   private LoadingScreens() {
   }

   /** A stretch of a video, in seconds, that must not be shown. */
   public record Span(double from, double to) {
      public boolean contains(double t) {
         return t >= this.from && t <= this.to;
      }
   }

   /** How much fine detail a grey frame has: the mean brightness change between neighbouring pixels. */
   public static double detail(byte[] gray, int width, int height) {
      long sum = 0L;
      int n = 0;
      for (int y = 0; y + 1 < height; y++) {
         int row = y * width;
         for (int x = 0; x + 1 < width; x++) {
            int v = gray[row + x] & 0xFF;
            sum += Math.abs(v - (gray[row + x + 1] & 0xFF)) + Math.abs(v - (gray[row + width + x] & 0xFF));
            n += 2;
         }
      }

      return n == 0 ? 0.0 : sum / (double)n;
   }

   public static double brightness(byte[] gray) {
      long sum = 0L;
      for (byte b : gray) {
         sum += b & 0xFF;
      }

      return gray.length == 0 ? 0.0 : sum / (double)gray.length;
   }

   /** Whether a grey frame looks like a loading screen or a black frame. */
   public static boolean loading(byte[] gray, int width, int height) {
      return brightness(gray) < DARK || detail(gray, width, height) < DETAIL;
   }

   /**
    * The loading screens in {@code length} seconds of a video from {@code from}, in the video's seconds. Short
    * blips (one frame) are ignored; an empty list if FFmpeg can't read the video.
    */
   public static List<Span> scan(String ffmpeg, Path video, double from, double length) throws IOException, InterruptedException {
      if (length <= 0.05) {
         return List.of();
      }

      Process process = new ProcessBuilder(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-ss", seconds(Math.max(0.0, from)), "-t", seconds(length),
         "-i", video.toString(), "-an", "-vf", "fps=" + FPS + ",scale=" + WIDTH + ":" + HEIGHT + ",format=gray", "-f", "rawvideo", "-"))
         .redirectError(ProcessBuilder.Redirect.DISCARD).start();
      ProcessPriority.lower(process);
      List<Boolean> frames = new ArrayList<>();
      byte[] frame = new byte[WIDTH * HEIGHT];
      try (InputStream in = new BufferedInputStream(process.getInputStream(), 1 << 16)) {
         while (in.readNBytes(frame, 0, frame.length) == frame.length) {
            frames.add(loading(frame, WIDTH, HEIGHT));
         }
      }

      if (!process.waitFor(30L, TimeUnit.SECONDS)) {
         process.destroyForcibly();
      }

      boolean[] flags = new boolean[frames.size()];
      for (int i = 0; i < flags.length; i++) {
         flags[i] = frames.get(i);
      }

      return spans(flags, FPS, Math.max(0.0, from));
   }

   /** Turns per-frame flags into spans: runs of two or more flagged frames, widened by {@link #MARGIN}. */
   public static List<Span> spans(boolean[] flags, double fps, double offset) {
      List<Span> out = new ArrayList<>();
      int i = 0;
      while (i < flags.length) {
         if (!flags[i]) {
            i++;
            continue;
         }

         int j = i;
         while (j < flags.length && flags[j]) {
            j++;
         }

         if (j - i >= 2) {
            out.add(new Span(Math.max(0.0, offset + i / fps - MARGIN), offset + j / fps + MARGIN));
         }

         i = j;
      }

      return merge(out);
   }

   /** Sorts spans and joins any that touch. */
   public static List<Span> merge(List<Span> spans) {
      List<Span> sorted = new ArrayList<>(spans);
      sorted.sort(Comparator.comparingDouble(Span::from));
      List<Span> out = new ArrayList<>();
      for (Span span : sorted) {
         if (!out.isEmpty() && span.from() <= out.getLast().to() + 0.05) {
            Span last = out.removeLast();
            out.add(new Span(last.from(), Math.max(last.to(), span.to())));
         } else {
            out.add(span);
         }
      }

      return out;
   }

   /**
    * The part of a clip around its kills that is clear of every span: from the end of the last span before
    * {@code firstKill} to the start of the first span after {@code lastKill}. A span over a kill itself is ignored
    * (the kill is the one thing the reel must show).
    */
   public static double[] clear(List<Span> spans, double firstKill, double lastKill, double length) {
      double floor = 0.0;
      double ceil = length;
      for (Span span : spans) {
         if (span.to() <= firstKill) {
            floor = Math.max(floor, span.to());
         } else if (span.from() >= lastKill) {
            ceil = Math.min(ceil, span.from());
         }
      }

      return new double[]{Math.min(floor, firstKill), Math.max(ceil, lastKill)};
   }

   /**
    * Remembers, while you play, when the game showed no fight: no world, a loading or connecting screen, or the
    * moment you were moved to another world. Times are the mod's millisecond clock; thread-safe.
    */
   public static final class Tracker {
      /** How long spans are kept: longer than any clip. */
      private static final long KEEP_MS = 10L * 60_000L;
      /** Extra time kept clear after a loading screen, while the new world's chunks appear. */
      static final long SETTLE_MS = 750L;
      private final java.util.ArrayDeque<long[]> spans = new java.util.ArrayDeque<>();
      private long awaySince = -1L;

      /** Called every tick with whether the game is showing a loading screen (or no world) right now. */
      public synchronized void update(long nowMs, boolean away) {
         if (away && this.awaySince < 0L) {
            this.awaySince = nowMs;
         } else if (!away && this.awaySince >= 0L) {
            this.spans.add(new long[]{this.awaySince, nowMs});
            this.awaySince = -1L;
         }

         while (!this.spans.isEmpty() && this.spans.peekFirst()[1] < nowMs - KEEP_MS) {
            this.spans.removeFirst();
         }
      }

      /** The world was swapped for another one with no loading screen showing (a quick server switch). */
      public synchronized void cut(long nowMs) {
         this.spans.add(new long[]{nowMs, nowMs});
      }

      /**
       * The spans that touch the video from {@code fromMs} to {@code toMs}, in the video's seconds ({@code videoLeadMs}
       * is how far the video's start is ahead of {@code fromMs}), widened a little on each side.
       */
      public synchronized List<Span> between(long fromMs, long toMs, long videoLeadMs) {
         List<long[]> all = new ArrayList<>(this.spans);
         if (this.awaySince >= 0L) {
            all.add(new long[]{this.awaySince, Long.MAX_VALUE / 4});
         }

         double end = (toMs - fromMs + videoLeadMs) / 1000.0;
         List<Span> out = new ArrayList<>();
         for (long[] span : all) {
            long a = span[0] - (long)(MARGIN * 1000.0);
            long b = span[1] + SETTLE_MS;
            if (b < fromMs - videoLeadMs || a > toMs) {
               continue;
            }

            double from = Math.max(0.0, (a - fromMs + videoLeadMs) / 1000.0);
            double to = Math.min(end, (b - fromMs + videoLeadMs) / 1000.0);
            if (to >= from) {
               out.add(new Span(from, to));
            }
         }

         return merge(out);
      }
   }

   private static String seconds(double value) {
      return String.format(Locale.ROOT, "%.3f", value);
   }
}
