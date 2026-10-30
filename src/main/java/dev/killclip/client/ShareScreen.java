package dev.killclip.client;

import dev.killclip.core.VideoTools;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Shares a clip as a link: uploads it to catbox.moe (free, no account) and copies the link, which plays right in
 * Discord when pasted. Also makes vertical 9:16 copies for YouTube Shorts, TikTok and Reels.
 */
@Environment(EnvType.CLIENT)
final class ShareScreen extends Screen {
   private static final String HOST = "https://catbox.moe/user/api.php";
   /** catbox.moe takes files up to 200 MB. */
   private static final long UPLOAD_LIMIT = 190L * 1000L * 1000L;
   private final Screen parent;
   private final Path clip;
   private boolean vertical;
   private boolean busy;
   private String status = "";
   private String link = "";
   private ButtonWidget copyButton;

   ShareScreen(Screen parent, Path clip) {
      super(Text.literal("Share clip"));
      this.parent = parent;
      this.clip = clip;
   }

   @Override
   protected void init() {
      int x = this.width / 2 - 160;
      int y = 60;
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Get a link (copies it)"), b -> this.upload()).dimensions(x, y, 320, 20).build());
      this.addDrawableChild(ButtonWidget.builder(this.shapeLabel(), b -> {
         this.vertical = !this.vertical;
         b.setMessage(this.shapeLabel());
      }).dimensions(x, y + 24, 158, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Make 9:16 copy only"), b -> {
         KillClipperClient.instance().makeVertical(this.clip);
         this.status = "Making the vertical copy; it appears in your clips folder.";
      }).dimensions(x + 162, y + 24, 158, 20).build());
      this.copyButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Copy link again"), b -> this.copyLink())
         .dimensions(x, y + 48, 158, 20).build());
      this.copyButton.active = !this.link.isEmpty();
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> this.close()).dimensions(x + 162, y + 48, 158, 20).build());
   }

   private Text shapeLabel() {
      return Text.literal("Shape: " + (this.vertical ? "Vertical 9:16" : "Normal 16:9"));
   }

   private void copyLink() {
      if (!this.link.isEmpty()) {
         this.client.keyboard.setClipboard(this.link);
         this.status = "Link copied. Paste it in Discord (Ctrl+V): " + this.link;
      }
   }

   private void upload() {
      String ffmpeg = KillClipperClient.instance().ffmpeg();
      if (this.busy) {
         return;
      }

      if (ffmpeg == null) {
         this.status = "FFmpeg is still unpacking; try again in a moment.";
         return;
      }

      boolean vertical = this.vertical;
      this.busy = true;
      this.status = "Preparing the clip...";
      CompletableFuture.runAsync(() -> {
         String result;
         String url = "";
         Path work = this.clip.resolveSibling(".share");
         try {
            Files.createDirectories(work);
            Path source = this.clip;
            if (vertical) {
               source = work.resolve("share-vertical.mp4");
               VideoTools.vertical(ffmpeg, this.clip, source);
            }

            // An mp4 under the host's limit plays inline in Discord; anything else is converted or shrunk first.
            Path upload = VideoTools.fitSize(ffmpeg, source, UPLOAD_LIMIT, work.resolve("share-upload.mp4"));
            this.setStatus("Uploading " + Files.size(upload) / 100_000 / 10.0 + " MB...");
            String name = this.clip.getFileName().toString().replaceAll("\\.[^.]+$", "") + (vertical ? "_vertical" : "") + ".mp4";
            url = post(upload, name);
            result = "Link copied. Paste it in Discord (Ctrl+V): " + url;
         } catch (Exception e) {
            result = "Upload failed: " + e.getMessage();
         }

         try {
            Files.deleteIfExists(work.resolve("share-vertical.mp4"));
            Files.deleteIfExists(work.resolve("share-upload.mp4"));
            Files.deleteIfExists(work);
         } catch (IOException ignored) {
         }

         String done = result;
         String link = url;
         MinecraftClient.getInstance().execute(() -> {
            this.busy = false;
            this.status = done;
            if (!link.isEmpty()) {
               this.link = link;
               this.client.keyboard.setClipboard(link);
               this.copyButton.active = true;
            }
         });
      });
   }

   private void setStatus(String text) {
      MinecraftClient.getInstance().execute(() -> this.status = text);
   }

   /** Uploads the file as multipart/form-data and returns the link the host answers with. */
   private static String post(Path file, String fileName) throws IOException, InterruptedException {
      String boundary = "----killclip" + UUID.randomUUID();
      String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"reqtype\"\r\n\r\nfileupload\r\n"
         + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"fileToUpload\"; filename=\"" + fileName.replace("\"", "")
         + "\"\r\nContent-Type: video/mp4\r\n\r\n";
      String tail = "\r\n--" + boundary + "--\r\n";
      HttpRequest request = HttpRequest.newBuilder(URI.create(HOST))
         .timeout(Duration.ofMinutes(10))
         .header("Content-Type", "multipart/form-data; boundary=" + boundary)
         .POST(HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofByteArray(head.getBytes(StandardCharsets.UTF_8)),
            HttpRequest.BodyPublishers.ofFile(file),
            HttpRequest.BodyPublishers.ofByteArray(tail.getBytes(StandardCharsets.UTF_8))))
         .build();
      HttpResponse<String> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
         .send(request, HttpResponse.BodyHandlers.ofString());
      String body = response.body().strip();
      if (response.statusCode() / 100 != 2 || !body.startsWith("https://")) {
         throw new IOException("the host answered " + response.statusCode() + (body.isEmpty() ? "" : ": " + body.lines().findFirst().orElse("")));
      }

      return body;
   }

   @Override
   public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
      super.render(context, mouseX, mouseY, deltaTicks);
      context.drawCenteredTextWithShadow(this.textRenderer, this.clip.getFileName().toString(), this.width / 2, 14, 0xFFFFFFFF);
      context.drawCenteredTextWithShadow(this.textRenderer, "Uploads to catbox.moe. Anyone with the link can watch it.", this.width / 2, 36, 0xFFAAAAAA);
      if (!this.status.isEmpty()) {
         int y = 140;
         for (var line : this.textRenderer.wrapLines(Text.literal(this.status), this.width - 40)) {
            context.drawCenteredTextWithShadow(this.textRenderer, line, this.width / 2, y, 0xFFFFFF55);
            y += 10;
         }
      }
   }

   @Override
   public void close() {
      this.client.setScreen(this.parent);
   }
}
