import dev.killclip.core.ReplayBuffer;
import java.nio.file.*;

public final class ReplayBufferTest {
   public static void main(String[] args) throws Exception {
      Path dir = Path.of(args[0]);
      var settings = new ReplayBuffer.Settings("ffmpeg", 30, 0, 23, "libx264", 30);
      long t0 = System.nanoTime() / 1_000_000L;
      try (ReplayBuffer rb = ReplayBuffer.start(settings, 320, 240, dir.resolve("segments"), t0)) {
         int rendered = 0;
         java.util.concurrent.CompletableFuture<Path> pending = null;
         while (System.nanoTime() / 1_000_000L - t0 < 9000) {
            if (pending == null && System.nanoTime() / 1_000_000L - t0 >= 6000) pending = rb.saveClip(t0 + 2000, t0 + 5000, dir.resolve("clip.mp4"));
            long now = System.nanoTime() / 1_000_000L;
            int due = rb.claimFrames(now);
            if (due > 0) {
               byte[] px = rb.borrow();
               if (px == null) { rb.skip(due, null); }
               else {
                  byte shade = (byte)((now - t0) / 30);
                  java.util.Arrays.fill(px, shade);
                  rb.submit(px, due);
               }
            }
            rendered++;
            Thread.sleep(20); // ~50 renders/s, slower than nothing but faster than 30fps
            if (rb.failure() != null) throw new AssertionError(rb.failure());
         }
         Path saved = pending.get();
         System.out.println("saved " + saved + " size=" + Files.size(saved) + " renders=" + rendered);
         try (var s = Files.list(dir.resolve("segments"))) { System.out.println("segments on disk: " + s.filter(p -> p.toString().endsWith(".ts")).count()); }
      }
   }
}
