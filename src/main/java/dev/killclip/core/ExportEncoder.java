package dev.killclip.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encodes an editor export frame by frame: every frame handed in becomes exactly one video frame, so the result
 * is smooth however slowly the game rendered it (unlike the live recorder, which stamps frames as they arrive).
 */
public final class ExportEncoder {
   /** Frames waiting to be written before the game holds back the next one. */
   private static final int MAX_PENDING = 3;
   private final Process process;
   private final WritableByteChannel input;
   private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "KillClip export writer");
      thread.setDaemon(true);
      return thread;
   });
   private final AtomicInteger pending = new AtomicInteger();
   private final Path log;
   private final int frameBytes;
   private volatile String failure;
   private long frames;

   private ExportEncoder(Process process, Path log, int frameBytes) {
      this.process = process;
      this.log = log;
      this.frameBytes = frameBytes;
      OutputStream stdin = process.getOutputStream();
      this.input = Channels.newChannel(stdin);
   }

   /**
    * Starts FFmpeg for a {@code width} x {@code height} picture, either NV12 already flipped on the GPU or raw
    * RGBA rows bottom-up, written to {@code output} at {@code fps}.
    */
   public static ExportEncoder start(String ffmpeg, int width, int height, boolean nv12, int fps, int outputHeight, String encoder, int quality, Path output)
      throws IOException {
      Files.createDirectories(output.toAbsolutePath().getParent());
      int outHeight = outputHeight <= 0 || outputHeight >= height ? height : outputHeight;
      int outWidth = (int)Math.round((double)width * outHeight / height);
      List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
         "-f", "rawvideo", "-pix_fmt", nv12 ? "nv12" : "rgba", "-s", width + "x" + height, "-framerate", String.valueOf(fps), "-i", "-"));
      String scale = "scale=" + (outWidth & ~1) + ":" + (outHeight & ~1) + ":flags=bicubic";
      if (nv12) {
         if (outHeight != height || (width & 1) != 0) {
            cmd.addAll(List.of("-vf", scale));
         }
      } else {
         cmd.addAll(List.of("-vf", "vflip," + scale + ",format=yuv420p"));
      }

      cmd.addAll(ReplayBuffer.codecArgs(encoder, quality));
      cmd.addAll(List.of("-pix_fmt", "yuv420p", "-r", String.valueOf(fps)));
      String type = VideoTools.extension(output);
      if (type.equals("mp4") || type.equals("mov")) {
         cmd.addAll(List.of("-movflags", "+faststart"));
         if (encoder.startsWith("hevc")) {
            cmd.addAll(List.of("-tag:v", "hvc1"));
         }
      }

      cmd.add(output.toString());
      Path log = output.resolveSibling(output.getFileName() + ".log");
      Process process = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
      int bytes = nv12 ? width * height * 3 / 2 : width * height * 4;
      return new ExportEncoder(process, log, bytes);
   }

   public int frameBytes() {
      return this.frameBytes;
   }

   public long frames() {
      return this.frames;
   }

   public String failure() {
      if (this.failure == null && !this.process.isAlive()) {
         this.failure = "FFmpeg stopped: " + this.lastLogLine();
      }

      return this.failure;
   }

   /** True when another frame can be queued right now. */
   public boolean ready() {
      return this.pending.get() < MAX_PENDING && this.failure() == null;
   }

   /**
    * Queues one frame (its bytes are read on the writer thread, so they must stay valid until {@code done}
    * runs). {@code done} runs on the writer thread once the frame was written or dropped.
    */
   public void write(ByteBuffer frame, Runnable done) {
      this.pending.incrementAndGet();
      try {
         this.writer.execute(() -> {
            try {
               if (this.failure == null) {
                  ByteBuffer data = frame.duplicate();
                  data.limit(Math.min(data.capacity(), data.position() + this.frameBytes));
                  while (data.hasRemaining()) {
                     this.input.write(data);
                  }
               }
            } catch (IOException e) {
               this.failure = "could not send a frame to FFmpeg: " + e.getMessage() + " (" + this.lastLogLine() + ")";
            } finally {
               this.pending.decrementAndGet();
               done.run();
            }
         });
         this.frames++;
      } catch (java.util.concurrent.RejectedExecutionException e) {
         // A frame read back after the export ended: nothing more goes into the file.
         this.pending.decrementAndGet();
         done.run();
      }
   }

   /** Writes the last frames and waits for FFmpeg to finish the file. */
   public void finish() throws IOException, InterruptedException {
      this.writer.shutdown();
      this.writer.awaitTermination(2L, TimeUnit.MINUTES);
      this.input.close();
      if (!this.process.waitFor(5L, TimeUnit.MINUTES)) {
         this.process.destroyForcibly();
         throw new IOException("FFmpeg took too long to finish the export");
      }

      if (this.failure != null) {
         throw new IOException(this.failure);
      }

      if (this.process.exitValue() != 0) {
         throw new IOException("FFmpeg could not write the export: " + this.lastLogLine());
      }

      Files.deleteIfExists(this.log);
   }

   /** Stops without saving (export cancelled). */
   public void cancel() {
      this.writer.shutdownNow();
      this.process.destroyForcibly();
      try {
         Files.deleteIfExists(this.log);
      } catch (IOException ignored) {
      }
   }

   private String lastLogLine() {
      try {
         List<String> lines = Files.readAllLines(this.log);
         return lines.isEmpty() ? "no details" : lines.get(lines.size() - 1).strip().toLowerCase(Locale.ROOT);
      } catch (IOException e) {
         return "no details";
      }
   }
}
