import dev.killclip.core.BundledFfmpeg;
import dev.killclip.core.HitTracker;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Hit tracking and bundled-FFmpeg unpacking checks (the unpack test uses fake files; nothing is executed). */
public final class HitTrackerTest {
   public static void main(String[] args) throws Exception {
      HitTracker hits = new HitTracker();
      hits.onHit("Steve", 0);
      hits.onHit("Alex", 4000);
      check(hits.recent(5000, 6000).containsAll(List.of("Steve", "Alex")), "both hits are recent inside the window");
      check(hits.recent(7000, 6000).equals(List.of("Alex")), "old hit expires after the window");
      hits.forget("Alex");
      check(hits.recent(7000, 6000).isEmpty(), "forget removes a credited player");

      check(BundledFfmpeg.platform("Windows 11", "amd64").orElseThrow().equals("windows-x86_64-gpl"), "windows x64 platform");
      check(BundledFfmpeg.platform("Mac OS X", "aarch64").orElseThrow().equals("macosx-arm64-gpl"), "mac arm platform");
      check(BundledFfmpeg.platform("Linux", "amd64").orElseThrow().equals("linux-x86_64-gpl"), "linux x64 platform");
      check(BundledFfmpeg.platform("Windows 11", "aarch64").isEmpty(), "windows arm has no bundled build");

      Path jar = Files.createTempDirectory("fakejar");
      Path res = Files.createDirectories(jar.resolve("ffmpeg/linux-x86_64-gpl"));
      Files.writeString(res.resolve("files.txt"), "version=1.0\nffmpeg\nlibavutil.so.60\n");
      Files.writeString(res.resolve("ffmpeg"), "fake exe");
      Files.writeString(res.resolve("libavutil.so.60"), "fake lib");
      Path cache = Files.createTempDirectory("ffcache");
      try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
         Path exe = BundledFfmpeg.extract(loader, "linux-x86_64-gpl", cache).orElseThrow();
         check(Files.readString(exe).equals("fake exe") && Files.isExecutable(exe), "unpacks the executable and marks it runnable");
         check(Files.isRegularFile(exe.resolveSibling("libavutil.so.60")), "unpacks libraries next to it");
         long stamp = Files.getLastModifiedTime(exe).toMillis();
         Thread.sleep(20);
         Path again = BundledFfmpeg.extract(loader, "linux-x86_64-gpl", cache).orElseThrow();
         check(again.equals(exe) && Files.getLastModifiedTime(again).toMillis() == stamp, "second launch reuses the unpacked copy");
         check(BundledFfmpeg.extract(loader, "windows-x86_64-gpl", cache).equals(Optional.empty()), "missing platform gives empty");
      }

      System.out.println("all HitTracker/BundledFfmpeg checks passed");
   }

   static void check(boolean ok, String name) {
      if (!ok) throw new AssertionError("FAILED: " + name);
      System.out.println("ok - " + name);
   }
}
