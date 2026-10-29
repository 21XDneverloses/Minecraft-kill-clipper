package dev.killclip.client;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public final class KillClipSettingsScreen extends Screen {
   private static final List<Integer> FPS = List.of(30, 60, 120);
   private static final List<Integer> RESOLUTIONS = List.of(480, 720, 1080, 1440, 0);
   private static final List<Integer> QUALITIES = List.of(28, 23, 18);
   private static final List<String> ENCODERS = List.of("libx264", "h264_nvenc", "h264_amf", "h264_qsv");
   private static final List<Integer> BUFFERS = List.of(15, 30, 60, 90);
   private static final List<Integer> STREAK_KILLS = List.of(2, 3, 4, 5);
   private static final List<Integer> STREAK_GAPS = List.of(5000, 10000, 15000, 20000);
   private static final List<Integer> PRE_ROLLS = List.of(3000, 5000, 8000, 10000);
   private final Screen parent;
   private ClipConfig draft;
   private String error = "";

   public KillClipSettingsScreen(Screen parent) {
      super(Text.literal("Kill Clipper"));
      this.parent = parent;
      this.draft = KillClipperClient.instance().settings();
   }

   protected void init() {
      int x = this.width / 2 - 150;
      int y = Math.max(36, this.height / 2 - 104);
      this.button(x, y, 300, () -> onOff("Automatic clipping", this.draft.enabled), c -> c.enabled = !c.enabled);
      this.button(x, y + 24, 148, () -> onOff("Recording", this.draft.recording), c -> c.recording = !c.recording);
      this.button(x + 152, y + 24, 148, () -> onOff("Notifications", this.draft.feedback), c -> c.feedback = !c.feedback);
      this.button(x, y + 48, 148, () -> "FPS: " + this.draft.fps, c -> c.fps = next(FPS, c.fps));
      this.button(x + 152, y + 48, 148, () -> "Resolution: " + (this.draft.resolution == 0 ? "Native" : this.draft.resolution + "p"),
         c -> c.resolution = next(RESOLUTIONS, c.resolution));
      this.button(x, y + 72, 148, () -> "Quality: " + qualityName(this.draft.quality), c -> c.quality = next(QUALITIES, c.quality));
      this.button(x + 152, y + 72, 148, () -> "Encoder: " + encoderName(this.draft.encoder), c -> c.encoder = next(ENCODERS, c.encoder));
      this.button(x, y + 96, 148, () -> "Buffer: " + this.draft.bufferSeconds + " s", c -> c.bufferSeconds = next(BUFFERS, c.bufferSeconds));
      this.button(x + 152, y + 96, 148, () -> "Before kill: " + this.draft.preRollMs / 1000 + " s", c -> c.preRollMs = next(PRE_ROLLS, c.preRollMs));
      this.button(x, y + 120, 148, () -> "Streak: " + this.draft.streakMinKills + "+ kills", c -> c.streakMinKills = next(STREAK_KILLS, c.streakMinKills));
      this.button(x + 152, y + 120, 148, () -> "Streak window: " + this.draft.streakGapMs / 1000 + " s", c -> {
         c.streakGapMs = next(STREAK_GAPS, c.streakGapMs);
         c.postRollMs = Math.min(c.postRollMs, c.streakGapMs);
      });
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Open clips folder"), button -> KillClipperClient.instance().openClipsFolder())
         .dimensions(x, y + 148, 148, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Test clip (last 10 s)"), button -> {
         this.client.setScreen(this.parent);
         KillClipperClient.instance().testClip();
      }).dimensions(x + 152, y + 148, 148, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> this.close()).dimensions(x, y + 172, 300, 20).build());
   }

   private void button(int x, int y, int width, Supplier<String> label, Consumer<ClipConfig> edit) {
      this.addDrawableChild(ButtonWidget.builder(Text.literal(label.get()), button -> {
         this.change(edit);
         button.setMessage(Text.literal(label.get()));
      }).dimensions(x, y, width, 20).build());
   }

   private static <T> T next(List<T> options, T current) {
      return options.get((options.indexOf(current) + 1) % options.size());
   }

   private static String onOff(String label, boolean enabled) {
      return label + ": " + (enabled ? "ON" : "OFF");
   }

   private static String qualityName(int crf) {
      return crf <= 18 ? "High" : crf <= 23 ? "Medium" : "Low";
   }

   private static String encoderName(String encoder) {
      return switch (encoder) {
         case "h264_nvenc" -> "NVIDIA";
         case "h264_amf" -> "AMD";
         case "h264_qsv" -> "Intel";
         default -> "CPU";
      };
   }

   private void change(Consumer<ClipConfig> edit) {
      ClipConfig previous = this.draft.copy();
      edit.accept(this.draft);
      this.error = KillClipperClient.instance().saveSettings(this.draft);
      if (!this.error.isEmpty()) {
         this.draft = previous;
      }
   }

   public void render(DrawContext graphics, int mouseX, int mouseY, float delta) {
      super.render(graphics, mouseX, mouseY, delta);
      graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 16, -1);
      int bottom = Math.max(36, this.height / 2 - 104) + 198;
      graphics.drawCenteredTextWithShadow(this.textRenderer, "Clips save as mp4. Needs FFmpeg installed. Settings save automatically.", this.width / 2, bottom, -5592406);
      if (!this.error.isEmpty()) {
         graphics.drawCenteredTextWithShadow(this.textRenderer, this.error, this.width / 2, bottom + 12, -34953);
      }
   }

   public void close() {
      this.client.setScreen(this.parent);
   }
}
