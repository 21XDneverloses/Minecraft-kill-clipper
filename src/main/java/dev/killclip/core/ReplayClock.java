package dev.killclip.core;

/**
 * How far a replay moves per game tick at a playback speed. Minecraft's client never ticks faster than 20 times a
 * second (MinecraftClient.getTargetMillisPerTick takes the larger of 50 ms and the world's tick time), so slow motion
 * slows the ticks down, while faster playback keeps 20 ticks a second and moves the replay further each tick.
 * Steps keep the fraction of a millisecond they could not use, so a 1.37x part plays exactly 1.37x over its length.
 */
public final class ReplayClock {
   /** One game tick at normal speed, in ms. */
   public static final double TICK_MS = 50.0;

   private double carryMs;
   private long lastStepMs = (long)TICK_MS;

   /** The world's tick rate (ticks per second) for a playback speed. */
   public static float tickRate(float speed) {
      return 20.0F * Math.min(1.0F, speed);
   }

   /** How far the replay moves in one game tick at a playback speed, in ms of recording, before rounding. */
   public static double msPerTick(float speed) {
      return TICK_MS * Math.max(1.0F, speed);
   }

   /** The next tick's step in whole ms of recording; the rounding left over is added to later steps. */
   public long step(float speed) {
      double exact = msPerTick(speed) + this.carryMs;
      long whole = (long)Math.floor(exact);
      this.carryMs = exact - whole;
      this.lastStepMs = whole;
      return whole;
   }

   /** The step the replay made on the last tick, so rendered frames can place themselves between ticks. */
   public long lastStepMs() {
      return this.lastStepMs;
   }

   /** Forgets the leftover fraction, for a seek or a restart. */
   public void reset() {
      this.carryMs = 0.0;
      this.lastStepMs = (long)TICK_MS;
   }

   /** Real time one stretch of recording takes to play at a speed: what the export's video and sound both use. */
   public static double playMs(double recordingMs, float speed) {
      return recordingMs / Math.max(0.125F, speed);
   }
}
