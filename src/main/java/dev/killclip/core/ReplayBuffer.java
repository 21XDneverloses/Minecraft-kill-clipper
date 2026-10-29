package dev.killclip.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Rolling replay buffer. Raw RGBA frames are piped into FFmpeg, which writes
 * one-second MPEG-TS segments; old segments are deleted as the buffer rolls.
 * Frames are written at a constant rate (repeating frames when the game renders
 * slower than the target fps), so segment k always covers wall-clock time
 * [startMs + k * 1000, startMs + (k + 1) * 1000).
 */
public final class ReplayBuffer implements AutoCloseable {
   /**
    * audioInput holds FFmpeg input arguments for a system audio device (empty = none).
    * gameAudio records PCM pushed through {@link #pushAudio} instead.
    */
   public record Settings(String ffmpegPath, int fps, int outputHeight, int quality, String encoder, int bufferSeconds, List<String> audioInput, boolean gameAudio) {
      boolean hasAudio() {
         return this.gameAudio || !this.audioInput.isEmpty();
      }
   }

   /** Game audio format: 48 kHz, 16-bit signed little-endian, stereo. */
   public static final int AUDIO_RATE = 48000;
   public static final int AUDIO_FRAME_BYTES = 4;
   private static final int AUDIO_BACKLOG_FRAMES = AUDIO_RATE / 4;

   private record Frame(byte[] pixels, int copies) {
   }

   private static final int QUEUE_FRAMES = 4;
   private static final long STALL_MS = 2000L;
   private final Settings settings;
   private final int width;
   private final int height;
   private final int frameBytes;
   private final Path segmentDir;
   private final long startMs;
   private final Process ffmpeg;
   private final OutputStream stdin;
   private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(QUEUE_FRAMES);
   private final BlockingQueue<byte[]> free = new ArrayBlockingQueue<>(QUEUE_FRAMES + 2);
   private final AtomicInteger carry = new AtomicInteger();
   private final AtomicLong pinnedSegment = new AtomicLong(Long.MAX_VALUE);
   private final ExecutorService saver = Executors.newSingleThreadExecutor(r -> daemon(r, "killclip-save"));
   private final Thread writer;
   private final ServerSocket audioServer;
   private final Thread audioThread;
   private final BlockingQueue<byte[]> audioChunks = new ArrayBlockingQueue<>(256);
   private final AtomicLong audioQueuedFrames = new AtomicLong();
   private long framesClaimed;
   private long lastClaimMs;
   private volatile long framesWritten;
   private volatile String failure;
   private volatile boolean closed;

   private ReplayBuffer(Settings settings, int width, int height, Path segmentDir, long nowMs) throws IOException {
      this.settings = settings;
      this.width = width;
      this.height = height;
      this.frameBytes = width * height * 4;
      this.segmentDir = segmentDir;
      this.startMs = nowMs;
      this.lastClaimMs = nowMs;
      Files.createDirectories(segmentDir);
      try (Stream<Path> old = Files.list(segmentDir)) {
         for (Path file : old.toList()) {
            Files.deleteIfExists(file);
         }
      }

      this.audioServer = settings.gameAudio() ? new ServerSocket(0, 1, InetAddress.getLoopbackAddress()) : null;
      int audioPort = this.audioServer == null ? 0 : this.audioServer.getLocalPort();
      this.audioThread = this.audioServer == null ? null : daemon(this::audioLoop, "killclip-audio");
      if (this.audioThread != null) {
         this.audioThread.start();
      }

      ProcessBuilder builder = new ProcessBuilder(recordCommand(settings, width, height, segmentDir, audioPort));
      builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      builder.redirectError(segmentDir.resolve("ffmpeg.log").toFile());
      this.ffmpeg = builder.start();
      this.stdin = this.ffmpeg.getOutputStream();
      for (int i = 0; i < QUEUE_FRAMES + 2; i++) {
         this.free.add(new byte[this.frameBytes]);
      }

      this.writer = daemon(this::writeLoop, "killclip-encoder");
      this.writer.start();
   }

   public static ReplayBuffer start(Settings settings, int width, int height, Path segmentDir, long nowMs) throws IOException {
      return new ReplayBuffer(settings, width, height, segmentDir, nowMs);
   }

   public static List<String> recordCommand(Settings s, int width, int height, Path segmentDir, int audioPort) {
      int outHeight = s.outputHeight() <= 0 || s.outputHeight() >= height ? height : s.outputHeight();
      int outWidth = (int)Math.round((double)width * outHeight / height);
      List<String> cmd = new ArrayList<>(List.of(
         s.ffmpegPath(), "-hide_banner", "-loglevel", "error", "-nostdin",
         "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-framerate", String.valueOf(s.fps()), "-i", "-"
      ));
      if (s.gameAudio()) {
         cmd.addAll(List.of("-f", "s16le", "-ar", String.valueOf(AUDIO_RATE), "-ac", "2", "-i", "tcp://127.0.0.1:" + audioPort));
      } else {
         cmd.addAll(s.audioInput());
      }

      cmd.addAll(List.of("-vf", "vflip,scale=" + (outWidth & ~1) + ":" + (outHeight & ~1)));
      cmd.addAll(codecArgs(s.encoder(), s.quality()));
      if (s.hasAudio()) {
         cmd.addAll(List.of("-c:a", "aac", "-b:a", "160k", "-ar", "48000"));
      }

      cmd.addAll(List.of(
         "-pix_fmt", "yuv420p", "-g", String.valueOf(s.fps()), "-force_key_frames", "expr:gte(t,n_forced*1)",
         "-f", "segment", "-segment_time", "1", "-segment_format", "mpegts", "-reset_timestamps", "1",
         segmentDir.resolve("seg%06d.ts").toString()
      ));
      return cmd;
   }

   static List<String> codecArgs(String encoder, int quality) {
      String q = String.valueOf(quality);
      return switch (encoder) {
         case "h264_nvenc" -> List.of("-c:v", "h264_nvenc", "-preset", "p4", "-rc", "vbr", "-cq", q, "-b:v", "0");
         case "h264_amf" -> List.of("-c:v", "h264_amf", "-quality", "balanced", "-rc", "cqp", "-qp_i", q, "-qp_p", q);
         case "h264_qsv" -> List.of("-c:v", "h264_qsv", "-global_quality", q);
         default -> List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", q);
      };
   }

   public int width() {
      return this.width;
   }

   public int height() {
      return this.height;
   }

   public Settings settings() {
      return this.settings;
   }

   public Path segmentDir() {
      return this.segmentDir;
   }

   /** Error text once FFmpeg or the writer has failed, otherwise null. */
   public String failure() {
      if (this.failure == null && !this.closed && !this.ffmpeg.isAlive()) {
         this.failure = "FFmpeg exited (code " + this.ffmpeg.exitValue() + "): " + this.lastLogLine();
      }

      return this.failure;
   }

   /** True when no frame was claimed for a while (window minimised, game frozen); the buffer should be restarted. */
   public boolean stalled(long nowMs) {
      return nowMs - this.lastClaimMs > STALL_MS;
   }

   /** Called once per rendered frame. Returns how many video frames this render should fill (0 = skip capture). */
   public int claimFrames(long nowMs) {
      long expected = (nowMs - this.startMs) * this.settings.fps() / 1000L + 1L;
      int due = (int)Math.max(0L, expected - this.framesClaimed);
      if (due > 0) {
         this.framesClaimed += due;
         this.lastClaimMs = nowMs;
      }

      return due;
   }

   /** A free pixel buffer for the next frame, or null when the encoder is behind. */
   public byte[] borrow() {
      return this.free.poll();
   }

   public int frameBytes() {
      return this.frameBytes;
   }

   /** Hands a filled frame to the encoder; it is written {@code copies} times. */
   public void submit(byte[] pixels, int copies) {
      int total = copies + this.carry.getAndSet(0);
      if (this.closed || !this.queue.offer(new Frame(pixels, total))) {
         this.carry.addAndGet(total);
         this.free.offer(pixels);
      }
   }

   /** Frames that were claimed but could not be captured; they repeat the next submitted frame. */
   public void skip(int copies, byte[] unused) {
      this.carry.addAndGet(copies);
      if (unused != null) {
         this.free.offer(unused);
      }
   }

   /** Queues game audio (AUDIO_RATE, s16le stereo). Dropped when the encoder is far behind. */
   public void pushAudio(byte[] pcm, int length) {
      if (this.audioThread == null || this.closed || length < AUDIO_FRAME_BYTES) {
         return;
      }

      byte[] copy = java.util.Arrays.copyOf(pcm, length - length % AUDIO_FRAME_BYTES);
      if (this.audioChunks.offer(copy)) {
         this.audioQueuedFrames.addAndGet(copy.length / AUDIO_FRAME_BYTES);
      }
   }

   /**
    * Feeds FFmpeg exactly as many audio frames as wall-clock time has passed
    * since the buffer started, taking pushed game audio first and filling
    * the rest with silence, so audio always lines up with the video frames.
    */
   private void audioLoop() {
      try (ServerSocket server = this.audioServer) {
         server.setSoTimeout(15000);
         try (Socket socket = server.accept(); OutputStream out = socket.getOutputStream()) {
            long written = 0L;
            byte[] current = null;
            int offset = 0;
            byte[] block = new byte[AUDIO_RATE / 10 * AUDIO_FRAME_BYTES];
            while (!this.closed) {
               long due = (System.nanoTime() / 1000000L - this.startMs) * AUDIO_RATE / 1000L - written;
               while (this.audioQueuedFrames.get() > due + AUDIO_BACKLOG_FRAMES) {
                  byte[] dropped = this.audioChunks.poll();
                  if (dropped == null) {
                     break;
                  }

                  this.audioQueuedFrames.addAndGet(-(dropped.length / AUDIO_FRAME_BYTES));
               }

               while (due > 0L) {
                  int frames = (int)Math.min(due, block.length / AUDIO_FRAME_BYTES);
                  int filled = 0;
                  while (filled < frames * AUDIO_FRAME_BYTES) {
                     if (current == null || offset >= current.length) {
                        current = this.audioChunks.poll();
                        offset = 0;
                        if (current == null) {
                           break;
                        }

                        this.audioQueuedFrames.addAndGet(-(current.length / AUDIO_FRAME_BYTES));
                     }

                     int n = Math.min(current.length - offset, frames * AUDIO_FRAME_BYTES - filled);
                     System.arraycopy(current, offset, block, filled, n);
                     offset += n;
                     filled += n;
                  }

                  java.util.Arrays.fill(block, filled, frames * AUDIO_FRAME_BYTES, (byte)0);
                  out.write(block, 0, frames * AUDIO_FRAME_BYTES);
                  written += frames;
                  due -= frames;
               }

               Thread.sleep(10L);
            }
         }
      } catch (InterruptedException ignored) {
      } catch (IOException e) {
         if (!this.closed) {
            this.failure = "Game audio feed stopped: " + e.getMessage();
         }
      }
   }

   private void writeLoop() {
      long lastPrune = 0L;

      try {
         while (!this.closed) {
            Frame frame = this.queue.poll(250L, TimeUnit.MILLISECONDS);
            if (frame != null) {
               for (int i = 0; i < frame.copies(); i++) {
                  this.stdin.write(frame.pixels(), 0, this.frameBytes);
               }

               this.framesWritten += frame.copies();
               this.free.offer(frame.pixels());
            }

            long segment = this.currentSegment();
            if (segment != lastPrune) {
               lastPrune = segment;
               this.prune(segment);
            }
         }
      } catch (InterruptedException ignored) {
      } catch (IOException e) {
         if (!this.closed) {
            this.failure = "FFmpeg stopped accepting frames: " + this.lastLogLine();
         }
      }
   }

   private long currentSegment() {
      return this.framesWritten / this.settings.fps();
   }

   private void prune(long current) {
      long keepFrom = Math.min(current - this.settings.bufferSeconds() - 2L, this.pinnedSegment.get());
      try (Stream<Path> files = Files.list(this.segmentDir)) {
         for (Path file : files.toList()) {
            long index = segmentIndex(file);
            if (index >= 0L && index < keepFrom) {
               Files.deleteIfExists(file);
            }
         }
      } catch (IOException ignored) {
      }
   }

   private static long segmentIndex(Path file) {
      String name = file.getFileName().toString();
      if (name.startsWith("seg") && name.endsWith(".ts")) {
         try {
            return Long.parseLong(name.substring(3, name.length() - 3));
         } catch (NumberFormatException ignored) {
         }
      }

      return -1L;
   }

   /**
    * Saves wall-clock window [fromMs, toMs] as an mp4. Waits for the last
    * segment in the window to be finished, then stitches segments without
    * re-encoding. The clip may start up to one second early.
    */
   public CompletableFuture<Path> saveClip(long fromMs, long toMs, Path output) {
      long oldest = Math.max(0L, this.currentSegment() - this.settings.bufferSeconds());
      long first = Math.max(oldest, Math.max(0L, (fromMs - this.startMs) / 1000L));
      long last = Math.max(first, (toMs - this.startMs) / 1000L);
      this.pinnedSegment.accumulateAndGet(first, Math::min);
      return CompletableFuture.supplyAsync(() -> {
         try {
            long deadline = System.nanoTime() / 1000000L + Math.max(0L, toMs - System.nanoTime() / 1000000L) + 5000L;
            // FFmpeg only opens segment k + 1 after it has finished writing segment k.
            while (!Files.exists(this.segmentDir.resolve(String.format("seg%06d.ts", last + 1L)))) {
               if (this.closed || this.failure() != null || System.nanoTime() / 1000000L > deadline) {
                  throw new IOException(this.failure() != null ? this.failure() : "recording stopped before the clip finished");
               }

               Thread.sleep(100L);
            }

            List<String> lines = new ArrayList<>();
            for (long k = first; k <= last; k++) {
               Path segment = this.segmentDir.resolve(String.format("seg%06d.ts", k));
               if (Files.isRegularFile(segment)) {
                  lines.add("file '" + segment.toAbsolutePath().toString().replace("'", "'\\''") + "'");
               }
            }

            if (lines.isEmpty()) {
               throw new IOException("no recorded video covers that moment");
            }

            Files.createDirectories(output.toAbsolutePath().getParent());
            Path list = this.segmentDir.resolve("concat-" + System.nanoTime() + ".txt");
            Files.write(list, lines);
            try {
               Process concat = new ProcessBuilder(
                     this.settings.ffmpegPath(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                     "-f", "concat", "-safe", "0", "-i", list.toString(), "-c", "copy", "-movflags", "+faststart", output.toString()
                  )
                  .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                  .redirectErrorStream(true)
                  .start();
               String log = new String(concat.getInputStream().readAllBytes()).strip();
               if (!concat.waitFor(60L, TimeUnit.SECONDS) || concat.exitValue() != 0 || !Files.isRegularFile(output)) {
                  throw new IOException("FFmpeg could not write the clip: " + log);
               }
            } finally {
               Files.deleteIfExists(list);
            }

            return output;
         } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e.getMessage(), e);
         } finally {
            this.pinnedSegment.set(Long.MAX_VALUE);
         }
      }, this.saver);
   }

   private String lastLogLine() {
      try {
         List<String> lines = Files.readAllLines(this.segmentDir.resolve("ffmpeg.log"));
         return lines.isEmpty() ? "no details" : lines.get(lines.size() - 1);
      } catch (IOException e) {
         return "no details";
      }
   }

   @Override
   public void close() {
      if (this.closed) {
         return;
      }

      this.closed = true;
      this.writer.interrupt();
      if (this.audioThread != null) {
         this.audioThread.interrupt();
         try {
            this.audioServer.close();
         } catch (IOException ignored) {
         }
      }

      try {
         this.writer.join(1000L);
         this.stdin.close();
      } catch (IOException | InterruptedException ignored) {
      }

      try {
         if (!this.ffmpeg.waitFor(3L, TimeUnit.SECONDS)) {
            this.ffmpeg.destroyForcibly();
         }
      } catch (InterruptedException e) {
         this.ffmpeg.destroyForcibly();
      }

      // Let a clip that is already being stitched finish before the segments go away.
      this.saver.shutdown();
      try {
         this.saver.awaitTermination(60L, TimeUnit.SECONDS);
      } catch (InterruptedException ignored) {
      }
   }

   private static Thread daemon(Runnable task, String name) {
      Thread thread = new Thread(task, name);
      thread.setDaemon(true);
      return thread;
   }
}
