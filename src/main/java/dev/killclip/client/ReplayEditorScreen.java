package dev.killclip.client;

import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * The editing bar shown over a playing replay (E): a timeline with the camera switches on it, buttons to add
 * and remove switches, and export. The game keeps playing behind it.
 */
@Environment(EnvType.CLIENT)
final class ReplayEditorScreen extends Screen {
   private static final List<String> FORMATS = List.of("mp4", "mov", "mkv");
   private static final int[] MODE_COLORS = {0xFF55FF55, 0xFF55AAFF, 0xFFFFAA00};
   private final ReplayPlayer replay;
   private String format;
   private ButtonWidget playButton;
   private ButtonWidget cameraButton;
   private boolean dragging;

   ReplayEditorScreen(ReplayPlayer replay) {
      super(Text.literal("Clip editor"));
      this.replay = replay;
      this.format = KillClipperClient.instance().settings().fileFormat;
   }

   @Override
   protected void init() {
      int y = this.height - 26;
      int x = 8;
      this.playButton = this.addDrawableChild(ButtonWidget.builder(this.playLabel(), b -> {
         this.replay.setPaused(!this.replay.paused());
         b.setMessage(this.playLabel());
      }).dimensions(x, y, 44, 20).build());
      x += 48;
      this.cameraButton = this.addDrawableChild(ButtonWidget.builder(this.cameraLabel(), b -> {
         ReplayPlayer.CameraMode[] modes = ReplayPlayer.CameraMode.values();
         this.replay.setMode(modes[(this.replay.mode().ordinal() + 1) % modes.length]);
         b.setMessage(this.cameraLabel());
      }).dimensions(x, y, 100, 20).build());
      x += 104;
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Add switch"), b -> this.replay.addSwitchHere()).dimensions(x, y, 70, 20).build());
      x += 74;
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Remove"), b -> this.replay.edit.removeNear(this.replay.clipTimeMs())).dimensions(x, y, 46, 20).build());
      x += 50;
      this.addDrawableChild(ButtonWidget.builder(this.formatLabel(), b -> {
         this.format = FORMATS.get((FORMATS.indexOf(this.format) + 1) % FORMATS.size());
         b.setMessage(this.formatLabel());
      }).dimensions(x, y, 36, 20).build());
      x += 40;
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Export"), b -> this.replay.export(this.format)).dimensions(x, y, 44, 20).build());
      x += 48;
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Leave"), b -> ReplayPlayer.stop()).dimensions(x, y, 40, 20).build());
   }

   private Text playLabel() {
      return Text.literal(this.replay.paused() ? "Play" : "Pause");
   }

   private Text cameraLabel() {
      return Text.literal(this.replay.mode().shortLabel);
   }

   private Text formatLabel() {
      return Text.literal(this.format.toUpperCase(Locale.ROOT));
   }

   private int barLeft() {
      return 8;
   }

   private int barRight() {
      return this.width - 8;
   }

   private int barTop() {
      return this.height - 44;
   }

   @Override
   public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
      // Keep the replay visible: only a dark strip behind the controls.
      context.fill(0, this.height - 62, this.width, this.height, 0xA0000000);
   }

   @Override
   public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
      if (ReplayPlayer.active() != this.replay) {
         // Jumping back restarts the replay; reopen the editor with E once it has loaded.
         this.client.setScreen(null);
         return;
      }

      super.render(context, mouseX, mouseY, deltaTicks);
      this.playButton.setMessage(this.playLabel());
      this.cameraButton.setMessage(this.cameraLabel());
      int left = this.barLeft();
      int right = this.barRight();
      int top = this.barTop();
      long length = Math.max(1L, this.replay.clipLengthMs());
      long now = this.replay.clipTimeMs();
      context.fill(left, top, right, top + 8, 0xFF333333);
      int played = left + (int)((right - left) * Math.min(1.0, (double)now / length));
      context.fill(left, top, played, top + 8, 0xFFAAAAAA);
      for (ReplayEdit.Switch s : this.replay.edit.switches) {
         int x = left + (int)((right - left) * Math.min(1.0, (double)s.timeMs / length));
         context.fill(x - 1, top - 3, x + 2, top + 11, MODE_COLORS[s.mode.ordinal()]);
      }

      context.fill(played - 1, top - 4, played + 1, top + 12, 0xFFFFFFFF);
      String time = clock(now) + " / " + clock(length) + (this.replay.speed() != 1.0F ? "   " + this.replay.speed() + "x" : "");
      context.drawTextWithShadow(this.textRenderer, time, left, top - 14, 0xFFFFFFFF);
      String help = "Switches: green first person, blue third person, orange spectator. Two spectator switches in a row fly the camera between them.";
      context.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(help, right - left - 90), left + 90, top - 14, 0xFFBBBBBB);
      if (this.replay.exporting()) {
         context.drawCenteredTextWithShadow(this.textRenderer, "Exporting...", this.width / 2, 8, 0xFFFF5555);
      }
   }

   private static String clock(long ms) {
      return String.format(Locale.ROOT, "%d:%04.1f", ms / 60000L, (ms % 60000L) / 1000.0);
   }

   @Override
   public boolean mouseClicked(Click click, boolean doubled) {
      if (click.button() == 0 && click.y() >= this.barTop() - 4 && click.y() <= this.barTop() + 12 && click.x() >= this.barLeft() && click.x() <= this.barRight()) {
         this.dragging = true;
         return true;
      }

      return super.mouseClicked(click, doubled);
   }

   @Override
   public boolean mouseDragged(Click click, double offsetX, double offsetY) {
      if (this.dragging) {
         return true;
      }

      return super.mouseDragged(click, offsetX, offsetY);
   }

   @Override
   public boolean mouseReleased(Click click) {
      if (this.dragging) {
         this.dragging = false;
         this.seekTo(click.x());
         return true;
      }

      return super.mouseReleased(click);
   }

   private void seekTo(double mouseX) {
      double fraction = (mouseX - this.barLeft()) / Math.max(1.0, this.barRight() - this.barLeft());
      this.replay.seekClip((long)(Math.max(0.0, Math.min(1.0, fraction)) * this.replay.clipLengthMs()));
   }

   @Override
   public boolean keyPressed(KeyInput input) {
      if (input.key() == GLFW.GLFW_KEY_E) {
         this.close();
         return true;
      }

      return super.keyPressed(input);
   }

   @Override
   public boolean shouldPause() {
      return false;
   }
}
