package dev.killclip.client;

import dev.killclip.core.VideoTools;
import java.io.ByteArrayOutputStream;
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
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Shares a clip: posts it to a Discord channel through a webhook (shrunk to fit Discord's upload limit), and/or
 * makes a vertical 9:16 copy for YouTube Shorts, TikTok and Reels.
 */
@Environment(EnvType.CLIENT)
final class ShareScreen extends Screen {
   /** Discord's upload limit for a webhook without a boosted server is 10 MB. */
   private static final long DISCORD_LIMIT = 10L * 1000L * 1000L - 200_000L;
   private final Screen parent;
   private final Path clip;
   private TextFieldWidget webhook;
   private TextFieldWidget message;
   private boolean vertical;
   private boolean busy;
   private String status = "";

   ShareScreen(Screen parent, Path clip) {
      super(Text.literal("Share clip"));
      this.parent = parent;
      this.clip = clip;
   }

   @Override
   protected void init() {
      int x = this.width / 2 - 160;
      int y = 50;
      this.webhook = new TextFieldWidget(this.textRenderer, x, y, 320, 20, Text.literal("Discord webhook URL"));
      this.webhook.setMaxLength(300);
      this.webhook.setText(KillClipperClient.instance().settings().discordWebhook);
      this.webhook.setPlaceholder(Text.literal("https://discord.com/api/webhooks/..."));
      this.addDrawableChild(this.webhook);
      this.message = new TextFieldWidget(this.textRenderer, x, y + 40, 320, 20, Text.literal("Message"));
      this.message.setMaxLength(1500);
      this.message.setPlaceholder(Text.literal("Message (optional)"));
      this.addDrawableChild(this.message);
      this.addDrawableChild(ButtonWidget.builder(this.shapeLabel(), b -> {
         this.vertical = !this.vertical;
         b.setMessage(this.shapeLabel());
      }).dimensions(x, y + 70, 158, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Make 9:16 copy only"), b -> {
         KillClipperClient.instance().makeVertical(this.clip);
         this.status = "Making the vertical copy; it appears in your clips folder.";
      }).dimensions(x + 162, y + 70, 158, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Send to Discord"), b -> this.send()).dimensions(x, y + 100, 158, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> this.close()).dimensions(x + 162, y + 100, 158, 20).build());
   }

   private Text shapeLabel() {
      return Text.literal("Send as: " + (this.vertical ? "Vertical 9:16" : "Normal 16:9"));
   }

   private static boolean isWebhook(String url) {
      return url.matches("https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[\\w-]+");
   }

   private void send() {
      String url = this.webhook.getText().strip();
      String ffmpeg = KillClipperClient.instance().ffmpeg();
      if (this.busy) {
         return;
      }

      if (!isWebhook(url)) {
         this.status = "Paste a Discord webhook URL: Channel settings > Integrations > Webhooks > Copy URL.";
         return;
      }

      if (ffmpeg == null) {
         this.status = "FFmpeg is still unpacking; try again in a moment.";
         return;
      }

      ClipConfig config = KillClipperClient.instance().settings();
      if (!url.equals(config.discordWebhook)) {
         config.discordWebhook = url;
         KillClipperClient.instance().saveSettings(config);
      }

      String text = this.message.getText().strip();
      boolean vertical = this.vertical;
      this.busy = true;
      this.status = "Preparing the clip...";
      CompletableFuture.runAsync(() -> {
         String result;
         Path work = this.clip.resolveSibling(".share");
         try {
            Files.createDirectories(work);
            Path source = this.clip;
            if (vertical) {
               source = work.resolve("share-vertical.mp4");
               VideoTools.vertical(ffmpeg, this.clip, source);
            }

            Path upload = VideoTools.fitSize(ffmpeg, source, DISCORD_LIMIT, work.resolve("share-upload.mp4"));
            this.setStatus("Uploading " + Files.size(upload) / 100_000 / 10.0 + " MB to Discord...");
            String name = this.clip.getFileName().toString().replaceAll("\\.[^.]+$", "") + (vertical ? "_vertical" : "") + ".mp4";
            int code = post(url, text, upload, name);
            result = code / 100 == 2 ? "Sent to Discord." : "Discord refused the upload (HTTP " + code + ").";
         } catch (Exception e) {
            result = "Share failed: " + e.getMessage();
         }

         // The shrunk copies are only for the upload.
         try {
            Files.deleteIfExists(work.resolve("share-vertical.mp4"));
            Files.deleteIfExists(work.resolve("share-upload.mp4"));
            Files.deleteIfExists(work);
         } catch (IOException ignored) {
         }

         String done = result;
         MinecraftClient.getInstance().execute(() -> {
            this.busy = false;
            this.status = done;
         });
      });
   }

   private void setStatus(String text) {
      MinecraftClient.getInstance().execute(() -> this.status = text);
   }

   /** Posts one file to a webhook as multipart/form-data; returns the HTTP status. */
   private static int post(String url, String text, Path file, String fileName) throws IOException, InterruptedException {
      String boundary = "----killclip" + UUID.randomUUID();
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      String json = "{\"content\":" + jsonString(text) + "}";
      body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n" + json + "\r\n")
         .getBytes(StandardCharsets.UTF_8));
      body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"" + fileName.replace("\"", "")
         + "\"\r\nContent-Type: video/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      body.write(Files.readAllBytes(file));
      body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
         .timeout(Duration.ofMinutes(3))
         .header("Content-Type", "multipart/form-data; boundary=" + boundary)
         .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
         .build();
      HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      return response.statusCode();
   }

   private static String jsonString(String text) {
      StringBuilder out = new StringBuilder("\"");
      for (char c : text.toCharArray()) {
         switch (c) {
            case '"' -> out.append("\\\"");
            case '\\' -> out.append("\\\\");
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            default -> {
               if (c < 0x20) {
                  out.append(String.format("\\u%04x", (int)c));
               } else {
                  out.append(c);
               }
            }
         }
      }

      return out.append('"').toString();
   }

   @Override
   public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
      super.render(context, mouseX, mouseY, deltaTicks);
      int x = this.width / 2 - 160;
      context.drawCenteredTextWithShadow(this.textRenderer, this.clip.getFileName().toString(), this.width / 2, 14, 0xFFFFFFFF);
      context.drawTextWithShadow(this.textRenderer, "Discord webhook (Channel settings > Integrations > Webhooks)", x, 38, 0xFFAAAAAA);
      context.drawTextWithShadow(this.textRenderer, "Message", x, 78, 0xFFAAAAAA);
      if (!this.status.isEmpty()) {
         context.drawCenteredTextWithShadow(this.textRenderer, this.status, this.width / 2, 180, 0xFFFFFF55);
      }
   }

   @Override
   public void close() {
      this.client.setScreen(this.parent);
   }
}
