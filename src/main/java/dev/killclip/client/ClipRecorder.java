package dev.killclip.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.killclip.core.BundledFfmpeg;
import dev.killclip.core.ReplayBuffer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;

/** Owns the replay buffer and feeds it one GPU readback per due video frame. */
@Environment(EnvType.CLIENT)
public final class ClipRecorder {
   private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
   private final Path bufferRoot;
   private final Consumer<String> errors;
   private final CompletableFuture<String> bundledFfmpeg;
   private ReplayBuffer buffer;
   private long retryAtMs;
   private String lastError = "";
   /** The configured hardware encoder that failed, so recording falls back to the CPU encoder. */
   private String failedEncoder = "";

   public ClipRecorder(Path bufferRoot, Path ffmpegCache, Consumer<String> errors) {
      this.bufferRoot = bufferRoot;
      this.errors = errors;
      deleteTree(bufferRoot);
      // Unpack the FFmpeg build shipped in the jar off the render thread; fall back to one on PATH.
      this.bundledFfmpeg = CompletableFuture.supplyAsync(() -> {
         try {
            return BundledFfmpeg.platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""))
               .flatMap(platform -> {
                  try {
                     return BundledFfmpeg.extract(ClipRecorder.class.getClassLoader(), platform, ffmpegCache);
                  } catch (IOException e) {
                     throw new RuntimeException(e);
                  }
               })
               .map(Path::toString)
               .orElse("ffmpeg");
         } catch (RuntimeException e) {
            KillClipperClient.LOGGER.warn("Could not unpack the bundled FFmpeg; trying one on PATH", e);
            return "ffmpeg";
         }
      });
   }

   /** The FFmpeg to run, or null while the bundled one is still unpacking. */
   private String ffmpegPath(ClipConfig config) {
      if (!config.ffmpegPath.isBlank()) {
         return config.ffmpegPath;
      }

      return this.bundledFfmpeg.getNow(null);
   }

   private ReplayBuffer.Settings settingsFrom(ClipConfig config, String ffmpeg) {
      boolean gameAudio = "game".equals(config.audioSource) && GameAudioTap.active();
      String encoder = config.encoder.equals(this.failedEncoder) ? "libx264" : config.encoder;
      return new ReplayBuffer.Settings(ffmpeg, config.fps, config.resolution, config.quality, encoder, config.bufferSeconds, audioInput(config), gameAudio);
   }

   /** FFmpeg input arguments for the chosen audio source. Only system audio is supported. */
   static List<String> audioInput(ClipConfig config) {
      if (!"system".equals(config.audioSource)) {
         return List.of();
      }

      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      String device = config.audioDevice == null ? "" : config.audioDevice.strip();
      if (os.contains("win")) {
         return device.isEmpty() ? List.of() : List.of("-f", "dshow", "-i", "audio=" + device);
      } else if (os.contains("mac")) {
         return List.of("-f", "avfoundation", "-i", ":" + (device.isEmpty() ? "0" : device));
      } else {
         return List.of("-f", "pulse", "-i", device.isEmpty() ? "default" : device);
      }
   }

   public boolean running() {
      return this.buffer != null;
   }

   /** Called on the render thread after every frame. */
   public void onFrameRendered(MinecraftClient client, ClipConfig config, long nowMs) {
      if (!config.recording) {
         this.stop();
         return;
      }

      Framebuffer framebuffer = client.getFramebuffer();
      GpuTexture texture = framebuffer.getColorAttachment();
      if (texture == null || texture.getFormat().pixelSize() != 4) {
         return;
      }

      int width = framebuffer.textureWidth;
      int height = framebuffer.textureHeight;
      String ffmpeg = this.ffmpegPath(config);
      if (ffmpeg == null) {
         return;
      }

      ReplayBuffer.Settings wanted = this.settingsFrom(config, ffmpeg);
      if (this.buffer != null) {
         String failure = this.buffer.failure();
         if (failure != null) {
            String encoder = this.buffer.settings().encoder();
            if (!"libx264".equals(encoder)) {
               // The hardware encoder is missing or unsupported on this PC: switch to the CPU encoder right away.
               this.report("Encoder " + encoder + " failed (" + failure + "); recording with the CPU encoder instead");
               this.failedEncoder = encoder;
               this.retryAtMs = nowMs;
            } else {
               this.report(failure);
               this.retryAtMs = nowMs + 10000L;
            }

            this.stop();
            wanted = this.settingsFrom(config, ffmpeg);
         } else if (this.buffer.width() != width || this.buffer.height() != height || !this.buffer.settings().equals(wanted) || this.buffer.stalled(nowMs)) {
            this.stop();
         }
      }

      if (this.buffer == null) {
         if (nowMs < this.retryAtMs) {
            return;
         }

         try {
            this.buffer = ReplayBuffer.start(wanted, width, height, this.bufferRoot.resolve("session-" + System.nanoTime()), nowMs);
            GameAudioTap.setSink(wanted.gameAudio() ? this.buffer::pushAudio : null);
            this.lastError = "";
         } catch (IOException e) {
            this.report("Could not start FFmpeg (" + ffmpeg + "): " + e.getMessage());
            this.retryAtMs = nowMs + 30000L;
            return;
         }
      }

      ReplayBuffer target = this.buffer;
      int due = target.claimFrames(nowMs);
      if (due == 0) {
         return;
      }

      byte[] pixels = target.borrow();
      if (pixels == null) {
         target.skip(due, null);
         return;
      }

      GpuDevice device = RenderSystem.getDevice();
      GpuBuffer readback = device.createBuffer(() -> "KillClip frame", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long)target.frameBytes());
      CommandEncoder encoder = device.createCommandEncoder();
      encoder.copyTextureToBuffer(texture, readback, 0L, () -> {
         try (GpuBuffer.MappedView view = encoder.mapBuffer(readback, true, false)) {
            view.data().get(0, pixels, 0, target.frameBytes());
            target.submit(pixels, due);
         } catch (RuntimeException e) {
            target.skip(due, pixels);
         } finally {
            readback.close();
         }
      }, 0);
   }

   /** Saves [fromMs, toMs] (monotonic ms) to the clips folder; the callback runs on the client thread. */
   public void save(long fromMs, long toMs, String label, Path clipsDir, Consumer<String> done, Consumer<String> failed) {
      if (this.buffer == null) {
         failed.accept(this.lastError.isEmpty() ? "recording is not running" : this.lastError);
         return;
      }

      Path output = clipsDir.resolve(LocalDateTime.now().format(FILE_TIME) + "_" + label + ".mp4");
      this.buffer.saveClip(fromMs, toMs, output).whenComplete((path, error) -> MinecraftClient.getInstance().execute(() -> {
         if (error == null) {
            done.accept(path.getFileName().toString());
         } else {
            failed.accept(error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
         }
      }));
   }

   public void stop() {
      ReplayBuffer old = this.buffer;
      this.buffer = null;
      GameAudioTap.setSink(null);
      if (old != null) {
         CompletableFuture.runAsync(() -> {
            old.close();
            deleteTree(old.segmentDir());
         });
      }
   }

   private void report(String message) {
      if (!message.equals(this.lastError)) {
         this.lastError = message;
         this.errors.accept(message);
      }
   }

   private static void deleteTree(Path root) {
      if (!Files.exists(root)) {
         return;
      }

      try (Stream<Path> files = Files.walk(root)) {
         for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(file);
         }
      } catch (IOException ignored) {
      }
   }
}
