package dev.killclip.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * The highlight reel's built-in backing track: a 30-second, 128 BPM loop made here from plain maths
 * (kick, clap, hats, bass, chords and an arpeggio), so the mod ships no music file and the track is free to post anywhere.
 */
public final class ReelMusic {
   public static final int RATE = 48000;
   public static final double BPM = 128.0;
   /** Seconds in one bar; the reel cuts on bars so the kills land on the beat. */
   public static final double BAR_SECONDS = 4 * 60.0 / BPM;
   private static final int BEAT = (int)(RATE * 60 / BPM);
   private static final int BARS = 16;
   private static final int LENGTH = BEAT * 4 * BARS;
   /** A minor, F, C, G: root notes as semitones from A2, two bars each. */
   private static final int[] ROOTS = {0, -4, 3, -2};
   private static final int[][] CHORDS = {{0, 3, 7}, {0, 4, 7}, {0, 4, 7}, {0, 4, 7}};

   private ReelMusic() {
   }

   /** Writes the loop as a 16-bit stereo WAV unless it is already there, and returns the file. */
   public static Path file(Path dir) throws IOException {
      Path wav = dir.resolve("reel-beat-v1.wav");
      if (Files.isRegularFile(wav) && Files.size(wav) == 44L + LENGTH * 4L) {
         return wav;
      }

      Files.createDirectories(dir);
      Path temp = dir.resolve("reel-beat-v1.wav.tmp");
      float[][] mix = render();
      ByteBuffer data = ByteBuffer.allocate(44 + LENGTH * 4).order(ByteOrder.LITTLE_ENDIAN);
      data.put("RIFF".getBytes()).putInt(36 + LENGTH * 4).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)2)
         .putInt(RATE).putInt(RATE * 4).putShort((short)4).putShort((short)16).put("data".getBytes()).putInt(LENGTH * 4);
      for (int i = 0; i < LENGTH; i++) {
         data.putShort((short)Math.round(mix[0][i] * 32000.0F));
         data.putShort((short)Math.round(mix[1][i] * 32000.0F));
      }

      try (OutputStream out = Files.newOutputStream(temp)) {
         out.write(data.array());
      }

      Files.move(temp, wav, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      return wav;
   }

   private static double hz(double semitonesFromA2) {
      return 110.0 * Math.pow(2.0, semitonesFromA2 / 12.0);
   }

   /** Left and right channels, each within -1..1, looping without a seam. */
   static float[][] render() {
      float[] drums = new float[LENGTH];
      float[] bass = new float[LENGTH];
      float[] padL = new float[LENGTH];
      float[] padR = new float[LENGTH];
      float[] arp = new float[LENGTH];
      Random random = new Random(21L);
      int sixteenth = BEAT / 4;
      for (int bar = 0; bar < BARS; bar++) {
         int chord = bar / 2 % 4;
         int root = ROOTS[chord];
         // The last bar of each half drops the kick for a breath before the loop comes round.
         boolean breakBar = bar == 7 || bar == 15;
         for (int step = 0; step < 16; step++) {
            int at = bar * BEAT * 4 + step * sixteenth;
            if (step % 4 == 0 && !(breakBar && step >= 8)) {
               kick(drums, at);
            }

            if (step % 8 == 4) {
               noise(drums, at, random, 0.11, 0.42F, 0.12F);
            }

            if (breakBar && step >= 8) {
               // Snare roll into the next section, getting louder.
               noise(drums, at, random, 0.05, 0.16F + 0.03F * (step - 8), 0.12F);
            }

            if (step % 2 == 0) {
               noise(drums, at, random, step % 4 == 2 ? 0.05 : 0.018, step % 4 == 2 ? 0.16F : 0.08F, 0.6F);
            }

            if (step % 4 == 2 || step % 4 == 3 && step != 15) {
               bass(bass, at, hz(root - 12), step % 4 == 2 ? 0.16 : 0.09);
            }

            // Up-and-down arpeggio over two octaves of the chord; the second half of the loop plays it an octave up.
            int[] tones = CHORDS[chord];
            int[] pattern = {0, 1, 2, 3, 2, 1, 2, 3, 4, 3, 2, 3, 2, 1, 2, 1};
            int p = pattern[step];
            int note = root + tones[p % 3] + 12 * (p / 3) + (bar >= 8 ? 24 : 12);
            pluck(arp, at, hz(note), bar >= 8 ? 0.11F : 0.13F);
         }

         for (int tone : CHORDS[chord]) {
            pad(padL, padR, bar * BEAT * 4, BEAT * 4, hz(root + tone + 12));
         }
      }

      // Dotted-eighth ping-pong echo on the arpeggio, wrapped round so the loop has no seam.
      int delay = sixteenth * 3;
      float[] echoL = new float[LENGTH];
      float[] echoR = new float[LENGTH];
      for (int pass = 0; pass < 2; pass++) {
         for (int i = 0; i < LENGTH; i++) {
            int from = Math.floorMod(i - delay, LENGTH);
            echoL[i] = (arp[from] + echoR[from]) * 0.42F;
            echoR[i] = echoL[from] * 0.75F;
         }
      }

      float[][] out = new float[2][LENGTH];
      for (int i = 0; i < LENGTH; i++) {
         // Everything but the drums ducks under each kick, the pumping sound of dance music.
         double sinceBeat = (i % BEAT) / (double)RATE;
         float duck = (float)(1.0 - 0.65 * Math.exp(-sinceBeat / 0.09));
         float centre = drums[i] + bass[i] * duck;
         float left = centre + (padL[i] + arp[i] * 0.8F + echoL[i]) * duck;
         float right = centre + (padR[i] + arp[i] * 0.8F + echoR[i]) * duck;
         out[0][i] = (float)Math.tanh(left * 1.1);
         out[1][i] = (float)Math.tanh(right * 1.1);
      }

      return out;
   }

   private static void kick(float[] out, int at) {
      int n = (int)(RATE * 0.28);
      double phase = 0.0;
      for (int i = 0; i < n; i++) {
         double t = i / (double)RATE;
         phase += 2 * Math.PI * (46.0 + 130.0 * Math.exp(-t / 0.028)) / RATE;
         out[(at + i) % LENGTH] += (float)(Math.sin(phase) * Math.exp(-t / 0.085) * 0.9 * Math.min(1.0, (n - i) / 400.0));
      }
   }

   /** A burst of noise with the lows taken out: claps and hats. A higher {@code cut} removes more, leaving only hiss. */
   private static void noise(float[] out, int at, Random random, double decay, float gain, float cut) {
      int n = (int)(RATE * decay * 5);
      float low = 0.0F;
      for (int i = 0; i < n; i++) {
         float white = random.nextFloat() * 2.0F - 1.0F;
         low += (white - low) * cut;
         float sample = white - low;
         out[(at + i) % LENGTH] += (float)(sample * gain * Math.exp(-i / (RATE * decay)));
      }
   }

   private static void bass(float[] out, int at, double hz, double decay) {
      int n = (int)(RATE * decay * 4);
      float low = 0.0F;
      for (int i = 0; i < n; i++) {
         double t = i / (double)RATE;
         double phase = t * hz % 1.0;
         float saw = (float)(2.0 * phase - 1.0);
         // The filter closes as the note fades, so each note starts bright and ends round.
         low += (saw - low) * (float)(0.04 + 0.25 * Math.exp(-t / 0.05));
         float sub = (float)Math.sin(2 * Math.PI * hz * t);
         out[(at + i) % LENGTH] += (float)((low * 0.55 + sub * 0.5) * 0.5 * Math.exp(-t / decay) * Math.min(1.0, i / 60.0));
      }
   }

   private static void pluck(float[] out, int at, double hz, float gain) {
      int n = (int)(RATE * 0.3);
      float low = 0.0F;
      for (int i = 0; i < n; i++) {
         double t = i / (double)RATE;
         double phase = t * hz % 1.0;
         float wave = (phase < 0.35 ? 1.0F : -1.0F) * 0.6F + (float)(2.0 * phase - 1.0) * 0.4F;
         low += (wave - low) * (float)(0.08 + 0.5 * Math.exp(-t / 0.04));
         out[(at + i) % LENGTH] += (float)(low * gain * Math.exp(-t / 0.07) * Math.min(1.0, i / 40.0));
      }
   }

   private static void pad(float[] left, float[] right, int at, int n, double hz) {
      float lowL = 0.0F;
      float lowR = 0.0F;
      for (int i = 0; i < n; i++) {
         double t = i / (double)RATE;
         // Two slightly detuned saws per side give the chord its width.
         float l = (float)(2.0 * (t * hz * 1.004 % 1.0) - 1.0 + 2.0 * (t * hz * 0.998 % 1.0) - 1.0);
         float r = (float)(2.0 * (t * hz * 0.996 % 1.0) - 1.0 + 2.0 * (t * hz * 1.002 % 1.0) - 1.0);
         lowL += (l - lowL) * 0.09F;
         lowR += (r - lowR) * 0.09F;
         double edge = Math.min(1.0, Math.min(i, n - i) / 2400.0);
         left[(at + i) % LENGTH] += (float)(lowL * 0.05 * edge);
         right[(at + i) % LENGTH] += (float)(lowR * 0.05 * edge);
      }
   }
}
