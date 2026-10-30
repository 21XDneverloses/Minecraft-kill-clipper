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
      // Which session log a fight's replay comes from: lobby (0), duel server (10000), lobby again after the win (40000).
      long[] starts = {0L, 10000L, 40000L};
      check(ReplayLog.fightLog(starts, 25000L, 5000L) == 1, "kill on the duel server");
      check(ReplayLog.fightLog(starts, 39900L, 5000L) == 1, "kill just before the win transfer");
      check(ReplayLog.fightLog(starts, 42000L, 5000L) == 1, "win announced on arrival in the lobby");
      check(ReplayLog.fightLog(starts, 60000L, 5000L) == 2, "kill later in the lobby");
      check(ReplayLog.fightLog(new long[]{5000L}, 1000L, 5000L) == 0, "only log");
      check(ReplayLog.fightLog(new long[0], 1000L, 5000L) == -1, "no logs");
      System.out.println("ReplayLogTest passed (" + count + " records, " + Files.size(out) + " bytes)");
   }

   private static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError(what);
      }
   }
}
