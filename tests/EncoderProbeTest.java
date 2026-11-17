import dev.killclip.core.EncoderProbe;
import dev.killclip.core.ReplayBuffer;
import java.util.List;

/** Checks the auto-tune choices, the bitrate ceiling, and that the CPU encoder passes a real probe. */
public final class EncoderProbeTest {
   public static void main(String[] args) {
      check(EncoderProbe.tune("hevc_nvenc", 144, 12, 1080).equals(new EncoderProbe.Tuning("hevc_nvenc", 120, 1080)), "NVIDIA on a 144 Hz screen");
      check(EncoderProbe.tune("hevc_nvenc", 60, 12, 1080).fps() == 60, "60 Hz screen records 60 fps");
      check(EncoderProbe.tune("libx264", 144, 4, 1080).equals(new EncoderProbe.Tuning("libx264", 30, 720)), "weak CPU");
      check(EncoderProbe.tune("h264_amf", 144, 8, 768).resolution() == 0, "small screen records native");
      check(ReplayBuffer.bitrateCapKbps("hevc_nvenc", 1920, 1080, 120, 0) == 50000, "Medal's 50 Mbit/s at 1080p120 H.265");
      check(ReplayBuffer.bitrateCapKbps("h264_nvenc", 1920, 1080, 120, 0) == 67500, "H.264 gets a third more");
      check(ReplayBuffer.bitrateCapKbps("hevc_nvenc", 1280, 720, 60, 0) < 20000, "720p60 is much smaller");
      check(ReplayBuffer.bitrateCapKbps("libx264", 1920, 1080, 60, 30) == 30000, "manual ceiling wins");
      check(EncoderProbe.best(java.util.Map.of("hevc_nvenc", false, "h264_nvenc", true, "libx264", true)).equals("h264_nvenc"), "best working");
      var settings = new ReplayBuffer.Settings("ffmpeg", 120, 1080, 23, "hevc_nvenc", 30, List.of(), false, 0);
      List<String> cmd = ReplayBuffer.recordCommand(settings, 1920, 1080, true, java.nio.file.Path.of("x"), 1, 0);
      check(cmd.contains("-maxrate") && cmd.contains("50000k") && !cmd.contains("-vf"), "nv12 at clip size: no FFmpeg scaling, capped bitrate");
      String ffmpeg = args.length > 0 ? args[0] : "ffmpeg";
      check(EncoderProbe.works(ffmpeg, "libx264"), "libx264 probe");
      System.out.println("Encoder results here: " + EncoderProbe.testAll(ffmpeg));
      System.out.println("EncoderProbeTest passed");
   }

   private static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError(what);
      }
   }
}
