package dev.killclip.client;

import dev.killclip.core.ReplayBuffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.BiConsumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.system.MemoryUtil;

/**
 * Captures Minecraft's own sound output with no extra software. When game
 * audio is on, the sound engine is opened on an OpenAL Soft loopback device;
 * this class renders the mixed output, plays it to the speakers through Java
 * Sound, and hands a copy to the recorder. If anything is unavailable, the
 * normal output device is used and only recording audio is lost.
 */
@Environment(EnvType.CLIENT)
public final class GameAudioTap {
   private static final int CHUNK_FRAMES = 480;
   private static final int LINE_BUFFER_BYTES = ReplayBuffer.AUDIO_RATE / 25 * ReplayBuffer.AUDIO_FRAME_BYTES;
   private static final AudioFormat FORMAT = new AudioFormat(ReplayBuffer.AUDIO_RATE, 16, 2, true, false);
   private static volatile boolean wanted;
   private static volatile BiConsumer<byte[], Integer> sink;
   /** Test hook for machines with no speakers: capture without playback, paced by the clock. */
   private static final boolean WITHOUT_SPEAKERS = Boolean.getBoolean("killclip.audioWithoutSpeakers");
   private static SourceDataLine pendingLine;
   private static boolean pendingReady;
   private static long loopbackDevice;
   private static Thread thread;
   private static volatile boolean running;

   private GameAudioTap() {
   }

   public static void setWanted(boolean value) {
      wanted = value;
   }

   public static boolean wanted() {
      return wanted;
   }

   public static boolean active() {
      return running;
   }

   /** Where rendered audio goes for recording; null while not recording. */
   public static void setSink(@Nullable BiConsumer<byte[], Integer> target) {
      sink = target;
   }

   /** Opens a loopback device for the sound engine, or returns 0 to use the normal device. */
   public static long openLoopback(@Nullable String deviceSpecifier) {
      if (!wanted) {
         return 0L;
      }

      SourceDataLine line = null;
      try {
         if (!ALC10.alcIsExtensionPresent(0L, "ALC_SOFT_loopback")) {
            KillClipperClient.LOGGER.warn("Game audio: OpenAL loopback is not available; using the normal sound device");
            return 0L;
         }

         try {
            line = openLine(deviceSpecifier);
         } catch (Exception e) {
            if (!WITHOUT_SPEAKERS) {
               throw e;
            }
         }

         long device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence)null);
         if (device == 0L || !SOFTLoopback.alcIsRenderFormatSupportedSOFT(device, ReplayBuffer.AUDIO_RATE, SOFTLoopback.ALC_STEREO_SOFT, SOFTLoopback.ALC_SHORT_SOFT)) {
            if (device != 0L) {
               ALC10.alcCloseDevice(device);
            }

            if (line != null) {
               line.close();
            }

            return 0L;
         }

         pendingLine = line;
         pendingReady = true;
         loopbackDevice = device;
         return device;
      } catch (Throwable t) {
         if (line != null) {
            line.close();
         }

         KillClipperClient.LOGGER.warn("Game audio: could not set up capture; using the normal sound device", t);
         return 0L;
      }
   }

   public static boolean isLoopback(long device) {
      return device != 0L && device == loopbackDevice;
   }

   /** Context attributes with the loopback render format added. Free the result with MemoryUtil.memFree. */
   public static IntBuffer loopbackAttributes(@Nullable IntBuffer original) {
      IntBuffer result = MemoryUtil.memAllocInt((original == null ? 0 : original.capacity()) + 7);
      if (original != null) {
         for (int i = 0; i + 1 < original.capacity() && original.get(i) != 0; i += 2) {
            result.put(original.get(i)).put(original.get(i + 1));
         }
      }

      result.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT);
      result.put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT);
      result.put(ALC10.ALC_FREQUENCY).put(ReplayBuffer.AUDIO_RATE);
      result.put(0).flip();
      return result;
   }

   /** Starts rendering once the sound engine has its context. */
   public static void start(long device) {
      SourceDataLine line = pendingLine;
      boolean ready = pendingReady;
      pendingLine = null;
      pendingReady = false;
      if (!ready || !isLoopback(device)) {
         return;
      }

      running = true;
      thread = new Thread(() -> run(device, line), "killclip-game-audio");
      thread.setDaemon(true);
      thread.setPriority(Thread.MAX_PRIORITY);
      thread.start();
      KillClipperClient.LOGGER.info("Game audio capture on (playing through {})", line == null ? "nothing: no speakers" : line.getLineInfo());
   }

   /** Stops rendering before the sound engine closes its device. */
   public static void stop() {
      running = false;
      Thread current = thread;
      thread = null;
      if (current != null) {
         try {
            current.join(1000L);
         } catch (InterruptedException ignored) {
         }
      }

      if (pendingLine != null) {
         pendingLine.close();
         pendingLine = null;
      }

      pendingReady = false;

      loopbackDevice = 0L;
   }

   private static void run(long device, @Nullable SourceDataLine line) {
      ByteBuffer rendered = MemoryUtil.memAlloc(CHUNK_FRAMES * ReplayBuffer.AUDIO_FRAME_BYTES);
      byte[] bytes = new byte[CHUNK_FRAMES * ReplayBuffer.AUDIO_FRAME_BYTES];
      long startNanos = System.nanoTime();
      long renderedFrames = 0L;
      try {
         if (line != null) {
            line.start();
         }

         while (running) {
            SOFTLoopback.alcRenderSamplesSOFT(device, rendered, CHUNK_FRAMES);
            rendered.get(0, bytes);
            renderedFrames += CHUNK_FRAMES;
            if (line != null) {
               // Blocks while the speaker buffer is full, which paces rendering in real time.
               line.write(bytes, 0, bytes.length);
            } else {
               long aheadMs = renderedFrames * 1000L / ReplayBuffer.AUDIO_RATE - (System.nanoTime() - startNanos) / 1000000L;
               if (aheadMs > 0L) {
                  Thread.sleep(aheadMs);
               }
            }

            BiConsumer<byte[], Integer> target = sink;
            if (target != null) {
               target.accept(bytes, bytes.length);
            }
         }
      } catch (Throwable t) {
         running = false;
         KillClipperClient.LOGGER.error("Game audio capture stopped", t);
      } finally {
         if (line != null) {
            line.stop();
            line.close();
         }

         MemoryUtil.memFree(rendered);
      }
   }

   /** The Java Sound output matching Minecraft's chosen device, or the system default. */
   private static SourceDataLine openLine(@Nullable String deviceSpecifier) throws Exception {
      DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
      SourceDataLine line = null;
      if (deviceSpecifier != null && !deviceSpecifier.isBlank()) {
         String wantedName = deviceSpecifier.replaceFirst("^OpenAL Soft on ", "").strip();
         for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            String name = mixerInfo.getName();
            if (!name.isBlank() && (name.contains(wantedName) || wantedName.contains(name))) {
               Mixer mixer = AudioSystem.getMixer(mixerInfo);
               if (mixer.isLineSupported(info)) {
                  line = (SourceDataLine)mixer.getLine(info);
                  break;
               }
            }
         }
      }

      if (line == null) {
         line = (SourceDataLine)AudioSystem.getLine(info);
      }

      line.open(FORMAT, LINE_BUFFER_BYTES);
      return line;
   }
}
