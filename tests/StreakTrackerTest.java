import dev.killclip.core.StreakTracker;

public final class StreakTrackerTest {
   public static void main(String[] args) {
      StreakTracker t = new StreakTracker(10_000);
      check(t.onKill(0).isEmpty(), "first kill starts a group, nothing finished");
      check(t.onKill(5_000).isEmpty(), "second kill inside gap extends the group");
      check(t.flush(9_000).isEmpty(), "still inside gap, nothing finished");
      var s = t.flush(16_000).orElseThrow();
      check(s.kills() == 2 && s.firstKillMs() == 0 && s.lastKillMs() == 5_000, "group of 2 finishes after gap");
      check(t.flush(30_000).isEmpty(), "no kills left, nothing finished again");

      StreakTracker single = new StreakTracker(10_000);
      single.onKill(0);
      check(single.flush(20_000).orElseThrow().kills() == 1, "a single kill finishes as a group of 1");

      StreakTracker t2 = new StreakTracker(10_000);
      t2.onKill(0);
      t2.onKill(3_000);
      var closed = t2.onKill(20_000).orElseThrow();
      check(closed.kills() == 2, "kill after gap closes the previous group");
      check(t2.flush(40_000).orElseThrow().kills() == 1, "the new kill finishes as a group of 1");

      System.out.println("all StreakTracker checks passed");
   }

   static void check(boolean ok, String name) {
      if (!ok) {
         throw new AssertionError("FAILED: " + name);
      }
      System.out.println("ok - " + name);
   }
}
