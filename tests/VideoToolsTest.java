import dev.killclip.core.VideoTools;
import java.nio.file.Files;
import java.nio.file.Path;

public class VideoToolsTest {
   public static void main(String[] args) throws Exception {
      Path dir = Path.of(args[0]);
      Files.createDirectories(dir);
      Path source = dir.resolve("source.mov");
      Process make = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30", "-f", "lavfi", "-i", "sine",
         "-t", "6", "-c:v", "mpeg4", "-c:a", "pcm_s16le", source.toString()).inheritIO().start();
      check(make.waitFor() == 0, "make source");
      double length = VideoTools.duration("ffmpeg", source);
      check(Math.abs(length - 6.0) < 0.2, "duration " + length);
      VideoTools.frame("ffmpeg", source, 2.0, 160, dir.resolve("frame.png"));
      check(Files.size(dir.resolve("frame.png")) > 0, "frame");
      for (String type : new String[]{"mp4", "mkv", "mov"}) {
         Path out = dir.resolve("cut." + type);
         VideoTools.trim("ffmpeg", source, 1.0, 4.0, out);
         double cut = VideoTools.duration("ffmpeg", out);
         check(Math.abs(cut - 3.0) < 0.3, type + " cut length " + cut);
      }

      check(VideoTools.isVideo(Path.of("a.MKV")) && !VideoTools.isVideo(Path.of("a.kcreplay")), "types");
      System.out.println("VideoToolsTest passed");
   }

   private static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError(what);
      }
   }
}
