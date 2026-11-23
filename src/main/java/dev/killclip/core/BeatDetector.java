package dev.killclip.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Finds the tempo and the bar lines of any song, so the highlight reel can cut on the beat of the player's own
 * music. FFmpeg decodes the song; the onsets (sudden rises in loudness across the spectrum) are matched against
 * every tempo from 70 to 180 BPM, then the beat and bar positions that line up with the strongest hits are chosen.
 */
public final class BeatDetector {
   private static final int RATE = 11025;
   private static final int WINDOW = 512;
   private static final int HOP = 128;
   /** Onset frames per second. */
   private static final double FPS = RATE / (double)HOP;
   /** Only the first two minutes are read: enough for a steady tempo, and quick. */
   private static final int MAX_SECONDS = 120;

   private BeatDetector() {
   }

   /**
    * Where the beats are. {@code beat} is the time between beats, {@code downbeat} the first bar line, and
    * {@code start} the bar line the reel should start the music on (the first one where the song is in full swing).
    */
   public record Grid(double beat, double downbeat, double start) {
      public double bpm() {
         return 60.0 / this.beat;
      }

      public double bar() {
         return this.beat * 4.0;
      }
   }

   /** The built-in beat: exactly 128 BPM from the very start. */
   public static Grid builtIn() {
      return new Grid(60.0 / ReelMusic.BPM, 0.0, 0.0);
   }

   /** Reads a song with FFmpeg and finds its beat; null if the song can't be read or has no steady beat. */
   public static Grid detect(String ffmpeg, Path song) throws IOException, InterruptedException {
      Process process = new ProcessBuilder(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-i", song.toString(),
         "-t", String.valueOf(MAX_SECONDS), "-vn", "-ac", "1", "-ar", String.valueOf(RATE), "-f", "f32le", "-")).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      float[] samples;
      try (InputStream in = new BufferedInputStream(process.getInputStream())) {
         byte[] bytes = in.readAllBytes();
         ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
         samples = new float[bytes.length / 4];
         buffer.asFloatBuffer().get(samples);
      }

      if (!process.waitFor(30L, TimeUnit.SECONDS)) {
         process.destroyForcibly();
         return null;
      }

      return detect(samples, RATE);
   }

   /** Finds the beat of mono samples at {@code rate} Hz (resampled to 11025 Hz if needed). */
   public static Grid detect(float[] samples, int rate) {
      if (rate != RATE) {
         samples = resample(samples, rate);
      }

      if (samples.length < RATE * 6) {
         return null;
      }

      float[] onset = onsets(samples);
      float[] loud = loudness(samples, onset.length);
      double period = tempo(onset);
      if (period <= 0.0) {
         return null;
      }

      period = doubleTime(onset, period);

      double phase = phase(onset, period);
      // Of the four beats in a bar, the bar line is the one with the strongest hits (kick and crash land there).
      double bestScore = -1.0;
      double downbeat = phase;
      for (int k = 0; k < 4; k++) {
         double first = phase + k * period;
         double score = 0.0;
         for (double t = first; t < onset.length - 1; t += period * 4.0) {
            score += sample(onset, t) + 0.5 * sample(loud, t);
         }

         if (score > bestScore) {
            bestScore = score;
            downbeat = first;
         }
      }

      double barFrames = period * 4.0;
      // Skip a quiet intro: start on the first bar line after which the song is near its usual loudness.
      double typical = mean(loud);
      double start = downbeat;
      for (double t = downbeat; t < loud.length - barFrames * 2; t += barFrames) {
         double sum = 0.0;
         int n = 0;
         for (double u = t; u < t + barFrames * 2 && u < loud.length; u += 1.0) {
            sum += loud[(int)u];
            n++;
         }

         if (n > 0 && sum / n >= typical * 0.7) {
            start = t;
            break;
         }
      }

      // A frame stands for the middle of its window.
      double centre = WINDOW / 2.0 / RATE;
      return new Grid(period / FPS, downbeat / FPS + centre, start / FPS + centre);
   }

   private static float[] resample(float[] samples, int rate) {
      int length = (int)((long)samples.length * RATE / rate);
      float[] out = new float[length];
      double step = rate / (double)RATE;
      for (int i = 0; i < length; i++) {
         double at = i * step;
         int a = (int)at;
         int b = Math.min(samples.length - 1, a + 1);
         double f = at - a;
         out[i] = (float)(samples[a] * (1.0 - f) + samples[b] * f);
      }

      return out;
   }

   /**
    * Spectral flux: how much louder each frequency band got since the previous frame. The lows (kick and bass, which
    * carry the beat in most music) and the rest are measured apart and evened out, so busy hi-hats can't drown the kick.
    */
   static float[] onsets(float[] samples) {
      int frames = Math.max(0, (samples.length - WINDOW) / HOP);
      float[] low = new float[frames];
      float[] high = new float[frames];
      double[] previous = new double[WINDOW / 2];
      double[] re = new double[WINDOW];
      double[] im = new double[WINDOW];
      double[] hann = new double[WINDOW];
      for (int i = 0; i < WINDOW; i++) {
         hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WINDOW - 1));
      }

      // Bins below about 200 Hz.
      int lowBins = Math.max(2, (int)(200.0 * WINDOW / RATE));
      for (int f = 0; f < frames; f++) {
         int at = f * HOP;
         for (int i = 0; i < WINDOW; i++) {
            re[i] = samples[at + i] * hann[i];
            im[i] = 0.0;
         }

         fft(re, im);
         double lows = 0.0;
         double highs = 0.0;
         for (int k = 1; k < WINDOW / 2; k++) {
            double magnitude = Math.log1p(100.0 * Math.hypot(re[k], im[k]));
            double rise = magnitude - previous[k];
            if (rise > 0.0) {
               if (k < lowBins) {
                  lows += rise;
               } else {
                  highs += rise;
               }
            }

            previous[k] = magnitude;
         }

         low[f] = (float)lows;
         high[f] = (float)highs;
      }

      float[] a = peaks(low);
      float[] b = peaks(high);
      double meanA = mean(a);
      double meanB = mean(b);
      float[] out = new float[frames];
      for (int f = 0; f < frames; f++) {
         out[f] = (float)((meanA > 0 ? a[f] / meanA : 0.0) + 0.5 * (meanB > 0 ? b[f] / meanB : 0.0));
      }

      return out;
   }

   private static double mean(float[] values) {
      double sum = 0.0;
      for (float v : values) {
         sum += v;
      }

      return values.length == 0 ? 0.0 : sum / values.length;
   }

   /** Subtracts the local average so only real hits stand out, not loud passages. */
   private static float[] peaks(float[] flux) {
      int frames = flux.length;
      float[] out = new float[frames];
      int half = (int)(FPS * 0.15);
      double running = 0.0;
      int count = 0;
      int lo = 0;
      int hi = -1;
      for (int f = 0; f < frames; f++) {
         while (hi < Math.min(frames - 1, f + half)) {
            running += flux[++hi];
            count++;
         }

         while (lo < f - half) {
            running -= flux[lo++];
            count--;
         }

         out[f] = (float)Math.max(0.0, flux[f] - running / Math.max(1, count));
      }

      return out;
   }

   private static float[] loudness(float[] samples, int frames) {
      float[] out = new float[frames];
      for (int f = 0; f < frames; f++) {
         double sum = 0.0;
         int at = f * HOP;
         for (int i = 0; i < WINDOW && at + i < samples.length; i++) {
            sum += samples[at + i] * samples[at + i];
         }

         out[f] = (float)Math.sqrt(sum / WINDOW);
      }

      return out;
   }

   /**
    * The beat length in onset frames: the period whose beat positions, at their best phase, hit the strongest onsets
    * on average, favouring tempos near 120 BPM. Off-beat patterns (echoes, syncopation) only line up with every
    * other beat, so they score lower than the real beat.
    */
   static double tempo(float[] onset) {
      double minLag = FPS * 60.0 / 180.0;
      double maxLag = FPS * 60.0 / 70.0;
      if (onset.length < maxLag * 8) {
         return -1.0;
      }

      double best = -1.0;
      double bestValue = 0.0;
      for (double lag = minLag; lag <= maxLag; lag += 0.1) {
         double value = weighted(onset, lag);
         if (value > bestValue) {
            bestValue = value;
            best = lag;
         }
      }

      if (best < 0.0) {
         return -1.0;
      }

      double fine = best;
      for (double lag = best - 0.1; lag <= best + 0.1; lag += 0.005) {
         double value = weighted(onset, lag);
         if (value > bestValue) {
            bestValue = value;
            fine = lag;
         }
      }

      return fine;
   }

   private static double weighted(float[] onset, double lag) {
      double bpm = 60.0 * FPS / lag;
      double octaves = Math.log(bpm / 120.0) / Math.log(2.0);
      double beats = (onset.length - 1) / lag;
      return comb(onset, lag) / beats * Math.exp(-0.5 * octaves * octaves / 0.6);
   }

   /**
    * Fast songs (drum and bass, phonk, hardstyle, 150 to 180 BPM) fit half their tempo about as well as their real one,
    * so a slow result is checked: if the half-way points between its beats hit nearly as hard as the beats, the song
    * is really twice as fast. A slow song's off-beats (a hi-hat, an echo) are much weaker than its beats.
    */
   static double doubleTime(float[] onset, double period) {
      double bpm = 60.0 * FPS / period;
      if (bpm >= 95.0 || bpm * 2.0 > 200.0) {
         return period;
      }

      double phase = phase(onset, period);
      double on = 0.0;
      double off = 0.0;
      int n = 0;
      for (double t = phase; t + period / 2.0 < onset.length - 1; t += period) {
         on += near(onset, t);
         off += near(onset, t + period / 2.0);
         n++;
      }

      return n > 4 && on > 0.0 && off >= 0.7 * on ? period / 2.0 : period;
   }

   /** How well a beat of {@code period} frames fits, at its best phase. */
   private static double comb(float[] onset, double period) {
      double best = 0.0;
      for (double phase = 0.0; phase < period; phase += 1.0) {
         double sum = 0.0;
         for (double t = phase; t < onset.length - 1; t += period) {
            sum += sample(onset, t);
         }

         best = Math.max(best, sum);
      }

      return best;
   }

   /** Where the beats fall, in frames from the start, as the first beat. */
   private static double phase(float[] onset, double period) {
      double bestPhase = 0.0;
      double best = -1.0;
      for (double phase = 0.0; phase < period; phase += 0.25) {
         double sum = 0.0;
         for (double t = phase; t < onset.length - 1; t += period) {
            sum += sample(onset, t);
         }

         if (sum > best) {
            best = sum;
            bestPhase = phase;
         }
      }

      return bestPhase;
   }

   /** The strongest onset within a frame of {@code t}: a hit a little early or late still counts. */
   private static double near(float[] values, double t) {
      return Math.max(sample(values, t), Math.max(sample(values, t - 1.0), sample(values, t + 1.0)));
   }

   private static double sample(float[] values, double t) {
      int i = (int)t;
      if (i < 0 || i >= values.length - 1) {
         return 0.0;
      }

      double f = t - i;
      return values[i] * (1.0 - f) + values[i + 1] * f;
   }

   /** In-place radix-2 FFT; the length must be a power of two. */
   private static void fft(double[] re, double[] im) {
      int n = re.length;
      for (int i = 1, j = 0; i < n; i++) {
         int bit = n >> 1;
         for (; (j & bit) != 0; bit >>= 1) {
            j ^= bit;
         }

         j ^= bit;
         if (i < j) {
            double t = re[i];
            re[i] = re[j];
            re[j] = t;
            t = im[i];
            im[i] = im[j];
            im[j] = t;
         }
      }

      for (int len = 2; len <= n; len <<= 1) {
         double angle = -2 * Math.PI / len;
         double wRe = Math.cos(angle);
         double wIm = Math.sin(angle);
         for (int i = 0; i < n; i += len) {
            double curRe = 1.0;
            double curIm = 0.0;
            for (int k = 0; k < len / 2; k++) {
               int a = i + k;
               int b = a + len / 2;
               double tRe = re[b] * curRe - im[b] * curIm;
               double tIm = re[b] * curIm + im[b] * curRe;
               re[b] = re[a] - tRe;
               im[b] = im[a] - tIm;
               re[a] += tRe;
               im[a] += tIm;
               double next = curRe * wRe - curIm * wIm;
               curIm = curRe * wIm + curIm * wRe;
               curRe = next;
            }
         }
      }
   }
}
