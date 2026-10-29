import dev.killclip.core.ReplayBuffer;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Records 10 s of NV12 frames at 120 fps through the real ReplayBuffer, saves seconds 2..8 and checks that the
 * clip has no timing hitch at the one-second segment joins and that the audio length matches the video.
 * Args: segment dir, ffmpeg path, ffprobe path.
 */
public final class ReplayBufferSeamTest {
   public static void main(String[] args) throws Exception {
      Path dir = Path.of(args[0]);
      String ffmpeg = args[1];
      String ffprobe = args[2];
      int fps = 120;
      var settings = new ReplayBuffer.Settings(ffmpeg, fps, 0, 23, "libx264", 30, List.of(), true);
      long t0 = System.nanoTime() / 1_000_000L;
      Path clip = dir.resolve("clip.mp4");
      try (ReplayBuffer rb = ReplayBuffer.start(settings, 320, 180, true, dir.resolve("segments"), t0)) {
         byte[] tone = new byte[480 * ReplayBuffer.AUDIO_FRAME_BYTES];
         java.util.concurrent.CompletableFuture<Path> pending = null;
         long nextAudio = t0;
         while (System.nanoTime() / 1_000_000L - t0 < 10000) {
            long now = System.nanoTime() / 1_000_000L;
            if (pending == null && now - t0 >= 8500) pending = rb.saveClip(t0 + 2000, t0 + 8000, clip);
            while (nextAudio <= now) { rb.pushAudio(tone, tone.length); nextAudio += 10; }
            int due = rb.claimFrames(now);
            if (due > 0) {
               byte[] px = rb.borrow();
               if (px == null) rb.skip(due, null);
               else { java.util.Arrays.fill(px, (byte)((now - t0) / 8)); rb.submit(px, due); }
            }
            Thread.sleep(6); // the game renders faster than the 120 fps target
            if (rb.failure() != null) throw new AssertionError(rb.failure());
         }
         pending.get();
      }

      Process probe = new ProcessBuilder(ffprobe, "-v", "error", "-select_streams", "v", "-show_entries", "packet=pts_time", "-of", "csv=p=0", clip.toString()).start();
      List<Double> pts = new ArrayList<>();
      for (String line : new String(probe.getInputStream().readAllBytes()).split("\\R")) if (!line.isBlank()) pts.add(Double.parseDouble(line.strip().replace(",", "")));
      pts.sort(null);
      int hitches = 0;
      double worst = 0;
      for (int i = 1; i < pts.size(); i++) {
         double gap = pts.get(i) - pts.get(i - 1);
         worst = Math.max(worst, gap);
         if (gap > 1.5 / fps) hitches++;
      }
      Process dur = new ProcessBuilder(ffprobe, "-v", "error", "-show_entries", "stream=codec_type,duration", "-of", "csv=p=0", clip.toString()).start();
      String durations = new String(dur.getInputStream().readAllBytes()).strip().replace("\n", " ");
      System.out.printf("frames=%d hitches=%d worstGapMs=%.1f durations=[%s]%n", pts.size(), hitches, worst * 1000, durations);
      if (hitches > 0) throw new AssertionError("clip has " + hitches + " timing hitches at segment joins");
   }
}
