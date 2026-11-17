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
   public record Settings(String ffmpegPath, int fps, int outputHeight, int quality, String encoder, int bufferSeconds, List<String> audioInput, boolean gameAudio, int maxMbps) {
      public Settings(String ffmpegPath, int fps, int outputHeight, int quality, String encoder, int bufferSeconds, List<String> audioInput, boolean gameAudio) {
         this(ffmpegPath, fps, outputHeight, quality, encoder, bufferSeconds, audioInput, gameAudio, 0);
      }

      boolean hasAudio() {
         return this.gameAudio || !this.audioInput.isEmpty();
      }
   }

   /** Game audio format: 48 kHz, 16-bit signed little-endian, stereo. */
   public static final int AUDIO_RATE = 48000;
   public static final int AUDIO_FRAME_BYTES = 4;
   private static final int AUDIO_BACKLOG_FRAMES = AUDIO_RATE / 4;

   /**
    * One frame to send: either a pooled array, or memory the caller owns (a mapped GPU buffer) with {@code done}
    * run once it has been sent or dropped.
    */
   private record Frame(byte[] pixels, java.nio.ByteBuffer direct, Runnable done, int copies) {
      Frame(byte[] pixels, int copies) {
         this(pixels, null, null, copies);
      }

      void finish() {
         if (this.done != null) {
            this.done.run();
         }
      }
   }

   private static final int QUEUE_FRAMES = 4;
   private static final long STALL_MS = 2000L;
   private final Settings settings;
   private final int width;
   private final int height;
   private final int frameBytes;
   /** Frames arrive already converted to YUV on the GPU (top-down NV12) instead of bottom-up RGBA. */
   private final boolean nv12;
   private final Path segmentDir;
   private final long startMs;
   private final Process ffmpeg;
   private final ServerSocket videoServer;
   private final java.nio.channels.ServerSocketChannel videoServerChannel;
   private volatile java.nio.channels.SocketChannel videoSocket;
   private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(QUEUE_FRAMES);
   private final BlockingQueue<byte[]> free = new ArrayBlockingQueue<>(QUEUE_FRAMES + 2);
   private final AtomicInteger carry = new AtomicInteger();
   private final AtomicInteger arrays = new AtomicInteger();
   /** First segment of each clip being saved, with a count, so pruning never deletes what a pending save needs. */
   private final java.util.concurrent.ConcurrentSkipListMap<Long, Integer> pinned = new java.util.concurrent.ConcurrentSkipListMap<>();
   private final ExecutorService saver = Executors.newSingleThreadExecutor(r -> daemon(r, "killclip-save"));
   private final Thread writer;
   private final ServerSocket audioServer;
   private final Thread audioThread;
   private final BlockingQueue<byte[]> audioChunks = new ArrayBlockingQueue<>(256);
   private final AtomicLong audioQueuedFrames = new AtomicLong();
   private long framesClaimed;
   private long lastClaimMs;
   private volatile long framesWritten;
   private volatile long framesRepeated;
   /** Frames repeated because the game rendered slower than the clip frame rate. */
   private volatile long framesSlow;
   /** Frames left out because the encoder or the GPU copy was still busy. */
   private final AtomicLong framesDropped = new AtomicLong();
   private volatile String failure;
   private volatile boolean closed;

   private ReplayBuffer(Settings settings, int width, int height, boolean nv12, Path segmentDir, long nowMs) throws IOException {
      this.settings = settings;
      this.width = width;
      this.height = height;
      this.nv12 = nv12;
      this.frameBytes = nv12 ? width * height * 3 / 2 : width * height * 4;
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

      // Frames go to FFmpeg over a loopback socket: much faster than a process pipe on Windows.
      // A channel writes straight from the mapped GPU memory: no copy into a Java array for every frame.
      this.videoServerChannel = java.nio.channels.ServerSocketChannel.open();
      this.videoServerChannel.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
      this.videoServer = this.videoServerChannel.socket();
      ProcessBuilder builder = new ProcessBuilder(recordCommand(settings, width, height, nv12, segmentDir, this.videoServer.getLocalPort(), audioPort));
      builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      builder.redirectError(segmentDir.resolve("ffmpeg.log").toFile());
      this.ffmpeg = builder.start();
      ProcessPriority.lower(this.ffmpeg);
      this.ffmpeg.getOutputStream().close();
      this.writer = daemon(this::writeLoop, "killclip-encoder");
      this.writer.start();
   }

   public static ReplayBuffer start(Settings settings, int width, int height, Path segmentDir, long nowMs) throws IOException {
      return start(settings, width, height, false, segmentDir, nowMs);
   }

   /** With {@code nv12}, frames are top-down NV12 (width a multiple of 4, height even) and need no conversion. */
   public static ReplayBuffer start(Settings settings, int width, int height, boolean nv12, Path segmentDir, long nowMs) throws IOException {
      return new ReplayBuffer(settings, width, height, nv12, segmentDir, nowMs);
   }

   public static List<String> recordCommand(Settings s, int width, int height, boolean nv12, Path segmentDir, int videoPort, int audioPort) {
      int outHeight = s.outputHeight() <= 0 || s.outputHeight() >= height ? height : s.outputHeight();
      int outWidth = (int)Math.round((double)width * outHeight / height);
      List<String> cmd = new ArrayList<>(List.of(
         s.ffmpegPath(), "-hide_banner", "-loglevel", "error", "-nostdin",
         // Each captured frame is sent once and stamped with its arrival time; FFmpeg repeats frames to keep a
         // constant frame rate, so a slow moment no longer means writing the same 8 MB frame several times.
         "-use_wallclock_as_timestamps", "1",
         "-f", "rawvideo", "-pix_fmt", nv12 ? "nv12" : "rgba", "-s", width + "x" + height, "-framerate", String.valueOf(s.fps()),
         "-i", "tcp://127.0.0.1:" + videoPort
      ));
      if (s.gameAudio()) {
         cmd.addAll(List.of("-f", "s16le", "-ar", String.valueOf(AUDIO_RATE), "-ac", "2", "-i", "tcp://127.0.0.1:" + audioPort));
      } else {
         cmd.addAll(s.audioInput());
      }

      String scale = "scale=" + (outWidth & ~1) + ":" + (outHeight & ~1) + ":flags=fast_bilinear";
      if (nv12) {
         // The GPU already flipped and converted the frame, like Medal does; only resize when a smaller size is set.
         if (outHeight != height) {
            cmd.addAll(List.of("-vf", scale));
         }
      } else {
         // vflip only adjusts line pointers; the RGBA to YUV conversion is the costly step, so run it on several threads.
         int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
         cmd.addAll(List.of("-filter_threads", String.valueOf(threads), "-vf", "vflip," + scale + ",format=yuv420p"));
      }
      cmd.addAll(codecArgs(s.encoder(), s.quality()));
      int capKbps = bitrateCapKbps(s.encoder(), outWidth, outHeight, s.fps(), s.maxMbps());
      if (capKbps > 0 && !s.encoder().equals("h264_amf") && !s.encoder().equals("h264_qsv")) {
         // Quality-based encoding with a ceiling: calm moments stay small, fast fights never balloon the file.
         cmd.addAll(List.of("-maxrate", capKbps + "k", "-bufsize", capKbps * 2 + "k"));
      }
      if (s.hasAudio()) {
         cmd.addAll(List.of("-c:a", "aac", "-b:a", "160k", "-ar", "48000"));
      }

      cmd.addAll(List.of(
         "-fps_mode", "cfr", "-r", String.valueOf(s.fps()),
         "-pix_fmt", "yuv420p", "-g", String.valueOf(s.fps()), "-force_key_frames", "expr:gte(t,n_forced*1)",
         // Segments keep one continuous timeline so a clip is the segments joined byte for byte (see saveClip).
         "-f", "segment", "-segment_time", "1", "-segment_format", "mpegts",
         segmentDir.resolve("seg%06d.ts").toString()
      ));
      return cmd;
   }

   /**
    * The bitrate ceiling in kbit/s. {@code maxMbps} 0 picks one like Medal's 50 Mbit/s for 1080p at 120 fps with
    * H.265, scaled to the picture size and frame rate (H.264 needs about a third more for the same picture).
    */
   public static int bitrateCapKbps(String encoder, int width, int height, int fps, int maxMbps) {
      if (maxMbps > 0) {
         return maxMbps * 1000;
      }

      double pixels = (double)width * height / (1920.0 * 1080.0);
      double rate = Math.pow(Math.max(15, fps) / 120.0, 0.75);
      double kbps = 50000.0 * pixels * rate * (encoder.startsWith("hevc") ? 1.0 : 1.35);
      return (int)Math.max(6000.0, Math.min(90000.0, Math.round(kbps / 500.0) * 500.0));
   }

   public static List<String> codecArgs(String encoder, int quality) {
      String q = String.valueOf(quality);
      return switch (encoder) {
         // p3 with high-quality tuning keeps the GPU's video engine well ahead of 120 fps without touching
         // the 3D cores the game draws with; lookahead stays off so frames never pile up waiting.
         case "h264_nvenc" -> List.of("-c:v", "h264_nvenc", "-preset", "p3", "-tune", "hq", "-rc", "vbr", "-cq", q, "-b:v", "0", "-rc-lookahead", "0");
         // H.265 makes much smaller files at the same quality.
         case "hevc_nvenc" -> List.of("-c:v", "hevc_nvenc", "-preset", "p3", "-tune", "hq", "-rc", "vbr", "-cq", q, "-b:v", "0", "-rc-lookahead", "0");
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

   public boolean nv12() {
      return this.nv12;
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
         if (due > 1 && this.framesClaimed > 0L) {
            this.framesSlow += due - 1;
         }

         this.framesClaimed += due;
         this.lastClaimMs = nowMs;
      }

      return due;
   }

   /** A free pixel buffer for the next frame, or null when the encoder is behind. */
   /** A frame array to fill, or null when all are queued. Arrays are made on first use: the game's zero-copy path never needs them. */
   public byte[] borrow() {
      byte[] pixels = this.free.poll();
      if (pixels == null && this.arrays.get() < QUEUE_FRAMES + 2) {
         this.arrays.incrementAndGet();
         pixels = new byte[this.frameBytes];
      }

      return pixels;
   }

   /** Share of written frames that repeat the previous one because capture fell behind (choppiness). */
   public double repeatedPercent() {
      long written = this.framesWritten;
      return written == 0L ? 0.0 : 100.0 * this.framesRepeated / written;
   }

   /** Share of frames repeated because the game ran below the clip frame rate (game fps too low). */
   public double slowPercent() {
      long written = this.framesWritten;
      return written == 0L ? 0.0 : 100.0 * Math.min(this.framesSlow, written) / written;
   }

   /** Share of frames left out because the encoder was busy (recording itself is too heavy for this PC). */
   public double droppedPercent() {
      long written = this.framesWritten;
      return written == 0L ? 0.0 : 100.0 * Math.min(this.framesDropped.get(), written) / written;
   }

   public long framesWritten() {
      return this.framesWritten;
   }

   public int frameBytes() {
      return this.frameBytes;
   }

   /** Hands a filled frame to the encoder; it is written {@code copies} times. */
   public void submit(byte[] pixels, int copies) {
      int total = copies + this.carry.getAndSet(0);
      if (this.closed || !this.queue.offer(new Frame(pixels, total))) {
         this.carry.addAndGet(total);
         this.framesDropped.addAndGet(copies);
         this.free.offer(pixels);
      }
   }

   /** Frames that were claimed but could not be captured; they repeat the next submitted frame. */
   /**
    * Queues a frame straight from memory the caller owns, so the game thread never copies it; the encoder thread
    * reads it and then runs {@code done}. Returns false (and runs {@code done}) when the frame was dropped.
    */
   public boolean submitDirect(java.nio.ByteBuffer data, int copies, Runnable done) {
      int total = copies + this.carry.getAndSet(0);
      if (this.closed || data.remaining() < this.frameBytes || !this.queue.offer(new Frame(null, data, done, total))) {
         this.carry.addAndGet(total);
         this.framesDropped.addAndGet(copies);
         done.run();
         return false;
      }

      return true;
   }

   public void skip(int copies, byte[] unused) {
      this.carry.addAndGet(copies);
      this.framesDropped.addAndGet(copies);
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

      try (java.nio.channels.SocketChannel socket = this.acceptVideo()) {
         if (socket == null) {
            return;
         }

         socket.socket().setSendBufferSize(Math.max(socket.socket().getSendBufferSize(), this.frameBytes * 2));
         socket.socket().setTcpNoDelay(true);
         this.videoSocket = socket;
         while (!this.closed) {
            Frame frame = this.queue.poll(250L, TimeUnit.MILLISECONDS);
            if (frame != null) {
               try {
                  java.nio.ByteBuffer data = frame.direct() != null
                     ? frame.direct().duplicate().limit(frame.direct().position() + this.frameBytes)
                     : java.nio.ByteBuffer.wrap(frame.pixels(), 0, this.frameBytes);
                  while (data.hasRemaining()) {
                     socket.write(data);
                  }
               } finally {
                  frame.finish();
               }

               // The timeline still advances by every claimed frame, so segments stay in step with real time.
               this.framesWritten += frame.copies();
               this.framesRepeated += frame.copies() - 1;
               if (frame.pixels() != null) {
                  this.free.offer(frame.pixels());
               }
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

   /** Waits up to 15 s for FFmpeg to connect; null when the buffer closed first or FFmpeg never came. */
   private java.nio.channels.SocketChannel acceptVideo() throws IOException, InterruptedException {
      this.videoServerChannel.configureBlocking(false);
      long deadline = System.nanoTime() + 15_000_000_000L;
      while (!this.closed && System.nanoTime() < deadline) {
         java.nio.channels.SocketChannel socket = this.videoServerChannel.accept();
         if (socket != null) {
            socket.configureBlocking(true);
            return socket;
         }

         Thread.sleep(5L);
      }

      if (!this.closed) {
         this.failure = "FFmpeg never connected for video: " + this.lastLogLine();
      }

      return null;
   }

   private long currentSegment() {
      return this.framesWritten / this.settings.fps();
   }

   private void prune(long current) {
      java.util.Map.Entry<Long, Integer> pin = this.pinned.firstEntry();
      long keepFrom = Math.min(current - this.settings.bufferSeconds() - 2L, pin == null ? Long.MAX_VALUE : pin.getKey());
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
   /** How far after the start of a clip's video {@code fromMs} is: clips start on a whole one-second segment. */
   public long videoLeadMs(long fromMs) {
      long oldest = Math.max(0L, this.currentSegment() - this.settings.bufferSeconds());
      long first = Math.max(oldest, Math.max(0L, (fromMs - this.startMs) / 1000L));
      return Math.max(0L, fromMs - (this.startMs + first * 1000L));
   }

   public CompletableFuture<Path> saveClip(long fromMs, long toMs, Path output) {
      long oldest = Math.max(0L, this.currentSegment() - this.settings.bufferSeconds());
      long first = Math.max(oldest, Math.max(0L, (fromMs - this.startMs) / 1000L));
      long last = Math.max(first, (toMs - this.startMs) / 1000L);
      this.pinned.merge(first, 1, Integer::sum);
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

            List<String> parts = new ArrayList<>();
            for (long k = first; k <= last; k++) {
               Path segment = this.segmentDir.resolve(String.format("seg%06d.ts", k));
               if (Files.isRegularFile(segment)) {
                  parts.add(segment.toAbsolutePath().toString());
               }
            }

            if (parts.isEmpty()) {
               throw new IOException("no recorded video covers that moment");
            }

            Files.createDirectories(output.toAbsolutePath().getParent());
            // MPEG-TS segments with one continuous timeline join byte for byte (the concat protocol). The concat
            // demuxer guesses each segment's length instead, which left a 2-3 frame hitch at many one-second joins.
            List<String> concatCommand = new ArrayList<>(List.of(
               this.settings.ffmpegPath(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
               "-i", "concat:" + String.join("|", parts), "-c", "copy"
            ));
            String name = output.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            boolean quickTime = name.endsWith(".mp4") || name.endsWith(".mov");
            if (quickTime) {
               concatCommand.addAll(List.of("-movflags", "+faststart"));
            }

            if (quickTime && this.settings.encoder().startsWith("hevc")) {
               // hvc1 is the H.265 tag Windows and Apple players expect in mp4.
               concatCommand.addAll(List.of("-tag:v", "hvc1"));
            }

            concatCommand.add(output.toString());
            Process concat = new ProcessBuilder(concatCommand)
               .redirectOutput(ProcessBuilder.Redirect.DISCARD)
               .redirectErrorStream(true)
               .start();
            CompletableFuture<String> log = CompletableFuture.supplyAsync(() -> {
               try {
                  return new String(concat.getInputStream().readAllBytes()).strip();
               } catch (IOException e) {
                  return "";
               }
            });
            if (!concat.waitFor(60L, TimeUnit.SECONDS)) {
               concat.destroyForcibly();
               throw new IOException("FFmpeg took over a minute to write the clip");
            }

            if (concat.exitValue() != 0 || !Files.isRegularFile(output)) {
               throw new IOException("FFmpeg could not write the clip: " + log.join());
            }

            return output;
         } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e.getMessage(), e);
         } finally {
            this.pinned.computeIfPresent(first, (k, n) -> n > 1 ? n - 1 : null);
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
      } catch (InterruptedException ignored) {
      }

      // Hand back any GPU buffers still waiting to be sent.
      for (Frame frame = this.queue.poll(); frame != null; frame = this.queue.poll()) {
         frame.finish();
      }

      try {
         java.nio.channels.SocketChannel socket = this.videoSocket;
         if (socket != null) {
            socket.close();
         }

         this.videoServerChannel.close();
      } catch (IOException ignored) {
      }

      try {
         if (!this.ffmpeg.waitFor(3L, TimeUnit.SECONDS)) {
            this.ffmpeg.destroyForcibly();
         }
      } catch (InterruptedException e) {
         this.ffmpeg.destroyForcibly();
      }

      for (Frame frame = this.queue.poll(); frame != null; frame = this.queue.poll()) {
         frame.finish();
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
