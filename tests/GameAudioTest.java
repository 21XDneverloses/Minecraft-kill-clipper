import dev.killclip.core.ReplayBuffer;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Records synthetic frames plus a 440 Hz tone pushed as game audio, then saves a clip. Needs ffmpeg on PATH. */
public final class GameAudioTest {
   public static void main(String[] args) throws Exception {
      Path dir = Path.of(args[0]);
      var settings = new ReplayBuffer.Settings("ffmpeg", 30, 0, 23, "libx264", 30, List.of(), true);
      long t0 = System.nanoTime() / 1_000_000L;
      long pushedFrames = 0;
      try (ReplayBuffer rb = ReplayBuffer.start(settings, 320, 240, dir.resolve("segments"), t0)) {
         CompletableFuture<Path> pending = null;
         while (System.nanoTime() / 1_000_000L - t0 < 9000) {
            long now = System.nanoTime() / 1_000_000L;
            if (pending == null && now - t0 >= 6000) pending = rb.saveClip(t0 + 2000, t0 + 5000, dir.resolve("clip.mp4"));
            int due = rb.claimFrames(now);
            if (due > 0) {
               byte[] px = rb.borrow();
               if (px == null) rb.skip(due, null);
               else { java.util.Arrays.fill(px, (byte)((now - t0) / 30)); rb.submit(px, due); }
            }
            // Push audio up to "now", like the game tap does every few ms.
            long target = (now - t0) * ReplayBuffer.AUDIO_RATE / 1000;
            int frames = (int)(target - pushedFrames);
            if (frames > 0) {
               byte[] pcm = new byte[frames * ReplayBuffer.AUDIO_FRAME_BYTES];
               for (int i = 0; i < frames; i++) {
                  short v = (short)(Math.sin(2 * Math.PI * 440 * (pushedFrames + i) / ReplayBuffer.AUDIO_RATE) * 8000);
                  for (int ch = 0; ch < 2; ch++) { pcm[i * 4 + ch * 2] = (byte)v; pcm[i * 4 + ch * 2 + 1] = (byte)(v >> 8); }
               }
               rb.pushAudio(pcm, pcm.length);
               pushedFrames = target;
            }
            if (rb.failure() != null) throw new AssertionError(rb.failure());
            Thread.sleep(15);
         }
         Path saved = pending.get();
         System.out.println("saved " + saved + " size=" + Files.size(saved));
      }
   }
}
