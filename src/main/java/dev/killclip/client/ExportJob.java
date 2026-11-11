package dev.killclip.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.killclip.core.ExportEncoder;
import java.nio.file.Path;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.util.Util;

/**
 * One editor export rendered frame by frame. While it runs, the game's clock moves exactly one video frame per
 * captured frame, so a slow PC makes the export take longer instead of making it choppy. The clock never runs
 * ahead of real time, so the replay plays at normal speed on a fast PC.
 */
public final class ExportJob {
   static volatile ExportJob active;

   final Path output;
   final int fps;
   final String encoder;
   final int quality;
   final int outputHeight;
   private final long baseMs;
   private int frame;
   /** Real time of the last captured frame, to notice an export that stopped making progress. */
   private long lastCaptureRealMs;
   /** Share of the clip done, 0..1, for the bar on screen. */
   volatile float progress;
   ExportEncoder video;
   volatile String failure;

   ExportJob(Path output, int fps, String encoder, int quality, int outputHeight) {
      this.output = output;
      this.fps = fps;
      this.encoder = encoder;
      this.quality = quality;
      this.outputHeight = outputHeight;
      this.baseMs = Util.getMeasuringTimeMs();
      this.lastCaptureRealMs = this.baseMs;
   }

   public static boolean running() {
      return active != null;
   }

   /** The game time for the frame being rendered: one frame step per captured frame. */
   long frameTimeMs() {
      return this.baseMs + Math.round(this.frame * 1000.0 / this.fps);
   }

   /** Called when the frame was handed to the encoder: the next render moves one frame on. */
   void frameCaptured() {
      this.frame++;
      this.lastCaptureRealMs = Util.getMeasuringTimeMs();
   }

   int frames() {
      return this.frame;
   }

   /**
    * Replaces the time the game uses for this frame. Waits for real time to catch up when the export runs
    * ahead, so the replay (and its sound) plays at its normal pace.
    */
   public static long clock(long realMs) {
      ExportJob job = active;
      if (job == null) {
         return realMs;
      }

      if (job.failure == null && realMs - job.lastCaptureRealMs > 15000L) {
         job.failure = "no frame could be captured for 15 s";
      }

      if (job.failure != null || !ReplayPlayer.playing()) {
         // A failed or orphaned export must never hold the game's clock: the game would stop ticking.
         if (!ReplayPlayer.playing()) {
            active = null;
         }

         return realMs;
      }

      long target = job.frameTimeMs();
      long wait = target - realMs;
      if (wait > 0L && wait < 1000L) {
         try {
            Thread.sleep(wait);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      }

      return target;
   }

   /** Draws the export bar over the picture, after the frame was captured, so it never shows in the video. */
   public static void drawBar(MinecraftClient client) {
      ExportJob job = active;
      if (job == null) {
         return;
      }

      Framebuffer framebuffer = client.getFramebuffer();
      GpuTexture color = framebuffer.getColorAttachment();
      GpuTexture depth = framebuffer.getDepthAttachment();
      if (color == null || depth == null) {
         return;
      }

      int width = framebuffer.textureWidth;
      int height = framebuffer.textureHeight;
      int barWidth = Math.max(40, width * 3 / 5);
      int barHeight = Math.max(6, height / 60);
      int border = Math.max(2, barHeight / 4);
      int x = (width - barWidth) / 2;
      // Scissor boxes count from the bottom of the picture: this puts the bar near the top.
      int y = height - height / 12 - barHeight;
      if (x < 0 || y < 0 || x + barWidth > width || y + barHeight > height) {
         return;
      }

      var encoder = RenderSystem.getDevice().createCommandEncoder();
      encoder.clearColorAndDepthTextures(color, 0xFF000000, depth, 1.0, x - border, y - border, barWidth + border * 2, barHeight + border * 2);
      encoder.clearColorAndDepthTextures(color, 0xFF3A3A3A, depth, 1.0, x, y, barWidth, barHeight);
      int filled = Math.round(barWidth * Math.max(0.0F, Math.min(1.0F, job.progress)));
      if (filled > 0) {
         encoder.clearColorAndDepthTextures(color, 0xFF55DD55, depth, 1.0, x, y, filled, barHeight);
      }

      com.mojang.blaze3d.opengl.GlStateManager._disableScissorTest();
   }
}
