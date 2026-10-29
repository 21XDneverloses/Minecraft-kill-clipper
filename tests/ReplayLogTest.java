import dev.killclip.core.ReplayLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ReplayLogTest {
   public static void main(String[] args) throws Exception {
      Path dir = Path.of(args[0]);
      Files.createDirectories(dir);
      long start = System.nanoTime() / 1000000L;
      ReplayLog log = new ReplayLog(dir.resolve("session.bin"), start);
      for (int i = 0; i < 2000; i++) {
         log.append(start + i, ReplayLog.PACKET, ("packet " + i).getBytes());
      }

      Path out = log.saveClip(start + 500, start + 1999, Map.of("player", "Me"), dir.resolve("clip.kcreplay")).get();
      for (int i = 2000; i < 2100; i++) {
         log.append(start + i, ReplayLog.SELF, new byte[]{1, 2, 3});
      }

      log.close();
      int count = 0;
      try (ReplayLog.Reader reader = new ReplayLog.Reader(out)) {
         check("Me".equals(reader.header().get("player")), "header player");
         check(reader.headerLong("clipStartMs", -1) == 500, "clip start");
         for (ReplayLog.Entry e = reader.next(); e != null; e = reader.next()) {
            check(new String(e.data()).equals("packet " + count), "record " + count);
            count++;
         }
      }

      check(count == 2000, "read " + count + " records");
      check(!Files.exists(dir.resolve("session.bin")), "session file removed");
      System.out.println("ReplayLogTest passed (" + count + " records, " + Files.size(out) + " bytes)");
   }

   private static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError(what);
      }
   }
}
