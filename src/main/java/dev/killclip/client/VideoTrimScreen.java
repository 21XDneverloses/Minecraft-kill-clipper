package dev.killclip.client;

import dev.killclip.core.VideoTools;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Trims any video and saves it as MP4, MOV or MKV, with a preview frame of where you are. */
@Environment(EnvType.CLIENT)
final class VideoTrimScreen extends Screen {
   private static final List<String> FORMATS = List.of("mp4", "mov", "mkv");
   private static final Identifier PREVIEW = Identifier.of("killclipper", "editor_preview");
   private final Screen parent;
   private final Path video;
   private double length = -1.0;
   private double position;
   private double start;
   private double end = -1.0;
   private String format;
   private String status = "Reading video...";
   private boolean busy;
   private double previewAt = -1.0;
   private boolean previewRunning;
   private int previewWidth;
   private int previewHeight;
   private boolean dragging;

   VideoTrimScreen(Screen parent, Path video) {
      super(Text.literal("Trim / convert"));
      this.parent = parent;
      this.video = video;
      String type = VideoTools.extension(video);
      this.format = FORMATS.contains(type) ? type : "mp4";
      String ffmpeg = KillClipperClient.instance().ffmpeg();
      CompletableFuture.supplyAsync(() -> {
         try {
            return ffmpeg == null ? -1.0 : VideoTools.duration(ffmpeg, video);
         } catch (Exception e) {
            return -1.0;
         }
      }).thenAccept(seconds -> net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
         this.length = seconds;
         this.end = seconds;
         this.status = seconds > 0 ? "" : "FFmpeg can't read this video";
      }));
   }

   @Override
   protected void init() {
      int y = this.height - 28;
      int x = this.width / 2 - 200;
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Set start"), b -> this.start = Math.min(this.position, this.end - 0.1)).dimensions(x, y, 70, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Set end"), b -> this.end = Math.max(this.position, this.start + 0.1)).dimensions(x + 74, y, 70, 20).build());
      this.addDrawableChild(ButtonWidget.builder(this.formatLabel(), b -> {
         this.format = FORMATS.get((FORMATS.indexOf(this.format) + 1) % FORMATS.size());
         b.setMessage(this.formatLabel());
      }).dimensions(x + 148, y, 90, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Export"), b -> this.export()).dimensions(x + 242, y, 76, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> this.close()).dimensions(x + 322, y, 78, 20).build());
   }

   private Text formatLabel() {
      return Text.literal("Save as " + this.format.toUpperCase(Locale.ROOT));
   }

   private void export() {
      String ffmpeg = KillClipperClient.instance().ffmpeg();
      if (this.busy || this.length <= 0 || ffmpeg == null) {
         return;
      }

      String name = this.video.getFileName().toString();
      int dot = name.lastIndexOf('.');
      Path output = this.video.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + "_cut." + this.format);
      double from = this.start;
      double to = this.end;
      this.busy = true;
      this.status = "Saving " + output.getFileName() + "...";
      CompletableFuture.runAsync(() -> {
         String result;
         try {
            VideoTools.trim(ffmpeg, this.video, from, to, output);
            result = "Saved " + output.getFileName();
         } catch (Exception e) {
            result = "Export failed: " + e.getMessage();
         }

         String message = result;
         net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
            this.busy = false;
            this.status = message;
         });
      });
   }

   private void refreshPreview() {
      String ffmpeg = KillClipperClient.instance().ffmpeg();
      if (this.previewRunning || this.length <= 0 || ffmpeg == null || Math.abs(this.previewAt - this.position) < 0.05) {
         return;
      }

      this.previewRunning = true;
      double at = this.position;
      Path png = this.video.resolveSibling(".ffmpeg").resolve("editor-preview.png");
      CompletableFuture.runAsync(() -> {
         NativeImage image = null;
         try {
            Files.createDirectories(png.getParent());
            VideoTools.frame(ffmpeg, this.video, Math.min(at, Math.max(0.0, this.length - 0.05)), 640, png);
            try (InputStream in = Files.newInputStream(png)) {
               image = NativeImage.read(in);
            }
         } catch (Exception e) {
            KillClipperClient.LOGGER.debug("No preview frame", e);
         }

         NativeImage loaded = image;
         net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
            this.previewRunning = false;
            this.previewAt = at;
            if (loaded != null) {
               this.previewWidth = loaded.getWidth();
               this.previewHeight = loaded.getHeight();
               this.client.getTextureManager().registerTexture(PREVIEW, new NativeImageBackedTexture(() -> "Kill Clipper preview", loaded));
            }
         });
      });
   }

   private int barLeft() {
      return this.width / 2 - 200;
   }

   private int barRight() {
      return this.width / 2 + 200;
   }

   private int barTop() {
      return this.height - 48;
   }

   @Override
   public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
      super.render(context, mouseX, mouseY, deltaTicks);
      this.refreshPreview();
      context.drawCenteredTextWithShadow(this.textRenderer, this.video.getFileName().toString(), this.width / 2, 8, 0xFFFFFFFF);
      int areaTop = 22;
      int areaBottom = this.barTop() - 16;
      if (this.previewWidth > 0) {
         int maxW = this.width - 40;
         int maxH = areaBottom - areaTop;
         double scale = Math.min((double)maxW / this.previewWidth, (double)maxH / this.previewHeight);
         int w = (int)(this.previewWidth * scale);
         int h = (int)(this.previewHeight * scale);
         int x = this.width / 2 - w / 2;
         context.drawTexture(RenderPipelines.GUI_TEXTURED, PREVIEW, x, areaTop, 0.0F, 0.0F, w, h, w, h);
      }

      int left = this.barLeft();
      int right = this.barRight();
      int top = this.barTop();
      double length = Math.max(0.001, this.length);
      context.fill(left, top, right, top + 8, 0xFF333333);
      if (this.end > 0) {
         int s = left + (int)((right - left) * this.start / length);
         int e = left + (int)((right - left) * this.end / length);
         context.fill(s, top, e, top + 8, 0xFF55AA55);
      }

      int p = left + (int)((right - left) * Math.min(1.0, this.position / length));
      context.fill(p - 1, top - 4, p + 1, top + 12, 0xFFFFFFFF);
      String times = String.format(Locale.ROOT, "%.1f s   (keep %.1f - %.1f s of %.1f s)", this.position, this.start, Math.max(0, this.end), Math.max(0, this.length));
      context.drawTextWithShadow(this.textRenderer, times, left, top - 12, 0xFFFFFFFF);
      if (!this.status.isEmpty()) {
         context.drawCenteredTextWithShadow(this.textRenderer, this.status, this.width / 2, areaBottom + 2, 0xFFFFFF55);
      }
   }

   @Override
   public boolean mouseClicked(Click click, boolean doubled) {
      if (click.button() == 0 && click.y() >= this.barTop() - 4 && click.y() <= this.barTop() + 12 && click.x() >= this.barLeft() - 2 && click.x() <= this.barRight() + 2) {
         this.dragging = true;
         this.scrub(click.x());
         return true;
      }

      return super.mouseClicked(click, doubled);
   }

   @Override
   public boolean mouseDragged(Click click, double offsetX, double offsetY) {
      if (this.dragging) {
         this.scrub(click.x());
         return true;
      }

      return super.mouseDragged(click, offsetX, offsetY);
   }

   @Override
   public boolean mouseReleased(Click click) {
      this.dragging = false;
      return super.mouseReleased(click);
   }

   private void scrub(double mouseX) {
      if (this.length > 0) {
         double fraction = (mouseX - this.barLeft()) / (double)(this.barRight() - this.barLeft());
         this.position = Math.max(0.0, Math.min(1.0, fraction)) * this.length;
      }
   }

   @Override
   public void close() {
      this.client.getTextureManager().destroyTexture(PREVIEW);
      this.client.setScreen(this.parent);
   }
}
