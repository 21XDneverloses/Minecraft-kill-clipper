import dev.killclip.core.ReplayClock;

public final class ReplayClockTest {
   public static void main(String[] args) {
      check(ReplayClock.tickRate(1.0F) == 20.0F, "normal speed ticks 20 times a second");
      check(ReplayClock.tickRate(2.0F) == 20.0F, "2x keeps 20 ticks a second (the game cannot tick faster)");
      check(ReplayClock.tickRate(0.5F) == 10.0F, "slow motion slows the ticks down");

      ReplayClock clock = new ReplayClock();
      check(clock.step(1.0F) == 50L, "1x moves 50 ms per tick");
      check(clock.step(2.0F) == 100L && clock.lastStepMs() == 100L, "2x moves 100 ms per tick");
      check(clock.step(4.0F) == 200L, "4x moves 200 ms per tick");
      check(clock.step(0.5F) == 50L, "slow motion still moves 50 ms per (slower) tick");

      // A 31 s clip with its middle 18 s at 2x: the export must take 13 + 9 = 22 s, not 31 s.
      ReplayClock export = new ReplayClock();
      long recording = 0L;
      int ticks = 0;
      while (recording < 31_000L) {
         float speed = recording >= 6_000L && recording < 24_000L ? 2.0F : 1.0F;
         recording += export.step(speed);
         ticks++;
      }
      double videoMs = ticks * ReplayClock.TICK_MS;
      double audioMs = ReplayClock.playMs(6_000, 1.0F) + ReplayClock.playMs(18_000, 2.0F) + ReplayClock.playMs(7_000, 1.0F);
      check(Math.abs(videoMs - 22_000.0) < 1.0, "2x part shortens the export to 22 s (got " + videoMs + " ms)");
      check(Math.abs(videoMs - audioMs) < 1.0, "video and sound are the same length");

      // Odd speeds from the ramps between parts must not drift from rounding.
      ReplayClock odd = new ReplayClock();
      long moved = 0L;
      for (int i = 0; i < 1000; i++) {
         moved += odd.step(1.37F);
      }
      check(Math.abs(moved - 1000 * 50.0 * 1.37F) <= 1.0, "1.37x stays exact over many ticks (moved " + moved + " ms)");

      System.out.println("all ReplayClock checks passed");
   }

   static void check(boolean ok, String name) {
      if (!ok) {
         throw new AssertionError(name);
      }
      System.out.println("ok: " + name);
   }
}
