package dev.killclip.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * A fight replay: every packet the server sent plus the player's own movement, with timestamps.
 *
 * <p>The whole connection is logged to one compressed session file, because a replay needs the world from the
 * moment you joined (chunks, players, scoreboard). A clip's replay is a small header followed by the session
 * file up to the end of the clip; playback fast-forwards to the clip's start.
 */
public final class ReplayLog implements AutoCloseable {
   public static final int MAGIC = 0x4B435250; // "KCRP"
   public static final int VERSION = 1;
   /** Data is a {@code NetworkPhase} ordinal: packets after it use that phase's codec. */
   public static final byte PHASE = 0;
   /** Data is one encoded clientbound packet (id and body). */
   public static final byte PACKET = 1;
   /** Data is the player's own state for one tick; the server never sends it to you. */
   public static final byte SELF = 2;
   private static final long MAX_PENDING_BYTES = 256L << 20;
   private static final Record STOP = new Record(0, (byte)-1, new byte[0]);

   private record Record(int timeMs, byte kind, byte[] data) {
   }

   private final Path file;
   private final long startMs;
   private final BlockingQueue<Record> queue = new LinkedBlockingQueue<>();
   private final AtomicLong pendingBytes = new AtomicLong();
   private final Thread writer;
   private final ExecutorService saver = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "killclip-replay-save");
      thread.setDaemon(true);
      return thread;
   });
   /** Bytes of the session file that form a complete, readable stream, and the last record time they hold. */
   private volatile long flushedBytes;
   private volatile long flushedTimeMs = -1L;
   private volatile String failure;
   private volatile boolean closed;
   /** Clip saves still using this log, and whether it should close once they are done. */
   private int holds;
   private boolean closeWanted;

   public ReplayLog(Path file, long startMs) throws IOException {
      this.file = file;
      this.startMs = startMs;
      Files.createDirectories(file.getParent());
      Files.deleteIfExists(file);
      FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
      this.writer = new Thread(() -> this.writeLoop(channel), "killclip-replay");
      this.writer.setDaemon(true);
      this.writer.setPriority(Thread.MIN_PRIORITY);
      this.writer.start();
   }

   public long startMs() {
      return this.startMs;
   }

   public String failure() {
      return this.failure;
   }

   /** Queues a record; safe from any thread. Drops records if the disk can't keep up. */
   public void append(long nowMs, byte kind, byte[] data) {
      if (this.closed || this.failure != null) {
         return;
      }

      if (this.pendingBytes.addAndGet(data.length) > MAX_PENDING_BYTES) {
         this.failure = "replay writer fell behind";
         return;
      }

      this.queue.add(new Record((int)Math.max(0L, nowMs - this.startMs), kind, data));
   }

   private void writeLoop(FileChannel channel) {
      Deflater deflater = new Deflater(Deflater.BEST_SPEED);
      try (channel;
           DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(channel), 1 << 16), deflater, 1 << 16, true))) {
         long lastFlush = System.nanoTime();
         int lastTime = -1;
         while (true) {
            Record record = this.queue.poll(200L, TimeUnit.MILLISECONDS);
            if (record == STOP) {
               break;
            }

            if (record != null) {
               this.pendingBytes.addAndGet(-record.data.length);
               out.writeInt(record.timeMs);
               out.writeByte(record.kind);
               out.writeInt(record.data.length);
               out.write(record.data);
               lastTime = Math.max(lastTime, record.timeMs);
            }

            // Sync-flush so everything written so far is a readable deflate stream a clip can copy.
            if (record == null || System.nanoTime() - lastFlush > 250_000_000L) {
               out.flush();
               this.flushedBytes = channel.position();
               this.flushedTimeMs = record == null ? this.nowRelative() : lastTime;
               lastFlush = System.nanoTime();
            }
         }
      } catch (IOException | InterruptedException e) {
         this.failure = "replay writer stopped: " + e.getMessage();
      } finally {
         deflater.end();
      }
   }

   private long nowRelative() {
      return System.nanoTime() / 1000000L - this.startMs;
   }

   /** Keeps the log open for one clip save, or returns null when it is already closed. */
   public synchronized Hold hold() {
      if (this.closed || this.closeWanted && this.holds == 0) {
         return null;
      }

      this.holds++;
      return new Hold();
   }

   /** Closes the log now, or as soon as the last {@link Hold} is released. */
   public void closeWhenUnused() {
      synchronized (this) {
         this.closeWanted = true;
         if (this.holds > 0) {
            return;
         }
      }

      this.close();
   }

   private void release() {
      boolean closeNow;
      synchronized (this) {
         this.holds--;
         closeNow = this.holds == 0 && this.closeWanted;
      }

      if (closeNow) {
         this.close();
      }
   }

   /** One clip's claim on the log; saving through it releases it when the save ends. */
   public final class Hold {
      private boolean released;

      public ReplayLog log() {
         return ReplayLog.this;
      }

      public CompletableFuture<Path> saveClip(long fromMs, long toMs, Map<String, String> info, Path output) {
         return ReplayLog.this.saveClip(fromMs, toMs, info, output).whenComplete((path, error) -> this.release());
      }

      public synchronized void release() {
         if (!this.released) {
            this.released = true;
            ReplayLog.this.release();
         }
      }
   }

   /**
    * Writes a replay covering [fromMs, toMs] (same clock as {@link #append}) to {@code output} once the session
    * log has reached {@code toMs}.
    */
   public CompletableFuture<Path> saveClip(long fromMs, long toMs, Map<String, String> info, Path output) {
      return CompletableFuture.supplyAsync(() -> {
         try {
            // Wait until the log has reached the clip's end, which may still be in the future.
            long deadline = System.nanoTime() + Math.max(0L, toMs - System.nanoTime() / 1000000L) * 1_000_000L + 10_000_000_000L;
            while (this.flushedTimeMs < toMs - this.startMs && this.failure == null && !this.closed && System.nanoTime() < deadline) {
               Thread.sleep(50L);
            }

            if (this.failure != null) {
               throw new IOException(this.failure);
            }

            long length = this.flushedBytes;
            Map<String, String> header = new LinkedHashMap<>(info);
            header.put("clipStartMs", String.valueOf(Math.max(0L, fromMs - this.startMs)));
            header.put("clipEndMs", String.valueOf(Math.max(0L, toMs - this.startMs)));
            Path partial = output.resolveSibling(output.getFileName() + ".part");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(partial)));
                 InputStream in = Files.newInputStream(this.file)) {
               out.writeInt(MAGIC);
               out.writeInt(VERSION);
               out.writeInt(header.size());
               for (Map.Entry<String, String> entry : header.entrySet()) {
                  out.writeUTF(entry.getKey());
                  out.writeUTF(entry.getValue());
               }

               copy(in, out, length);
            }

            Files.move(partial, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return output;
         } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e.getMessage(), e);
         }
      }, this.saver);
   }

   private static void copy(InputStream in, OutputStream out, long length) throws IOException {
      byte[] buffer = new byte[1 << 16];
      long left = length;
      while (left > 0L) {
         int read = in.read(buffer, 0, (int)Math.min(buffer.length, left));
         if (read < 0) {
            break;
         }

         out.write(buffer, 0, read);
         left -= read;
      }
   }

   @Override
   public void close() {
      if (this.closed) {
         return;
      }

      this.queue.add(STOP);
      this.closed = true;
      try {
         this.writer.join(5000L);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      }

      this.saver.shutdown();
      try {
         Files.deleteIfExists(this.file);
      } catch (IOException ignored) {
      }
   }

   /** Reads a saved clip replay. */
   public static final class Reader implements AutoCloseable {
      private final Map<String, String> header = new LinkedHashMap<>();
      private final DataInputStream in;

      public Reader(Path path) throws IOException {
         DataInputStream raw = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)));
         try {
            if (raw.readInt() != MAGIC) {
               throw new IOException("not a Kill Clipper replay");
            }

            int version = raw.readInt();
            if (version != VERSION) {
               throw new IOException("unsupported replay version " + version);
            }

            int entries = raw.readInt();
            for (int i = 0; i < entries; i++) {
               this.header.put(raw.readUTF(), raw.readUTF());
            }
         } catch (IOException e) {
            raw.close();
            throw e;
         }

         this.in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(raw, new Inflater(), 1 << 16), 1 << 16));
      }

      public Map<String, String> header() {
         return this.header;
      }

      public long headerLong(String key, long fallback) {
         try {
            return Long.parseLong(this.header.getOrDefault(key, ""));
         } catch (NumberFormatException e) {
            return fallback;
         }
      }

      /** The next record, or null at the end. A clip ends mid-stream, so a cut-off record also means the end. */
      public Entry next() throws IOException {
         try {
            int time = this.in.readInt();
            byte kind = this.in.readByte();
            int length = this.in.readInt();
            if (length < 0 || length > (64 << 20)) {
               throw new IOException("corrupt replay record");
            }

            byte[] data = new byte[length];
            this.in.readFully(data);
            return new Entry(time, kind, data);
         } catch (EOFException e) {
            return null;
         }
      }

      @Override
      public void close() throws IOException {
         this.in.close();
      }
   }

   public record Entry(int timeMs, byte kind, byte[] data) {
   }
}
