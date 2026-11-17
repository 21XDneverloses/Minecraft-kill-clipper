package dev.killclip.client;

import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Writes or changes one piece of text on the video: the words, where it sits, its size, colour and how long it shows. */
@Environment(EnvType.CLIENT)
final class TextOverlayScreen extends FlatScreen {
   private static final long[] LENGTHS = {1000L, 2000L, 3000L, 4000L, 5000L, 8000L};
   private final Screen parent;
   private final ReplayPlayer replay;
   /** The text in the edit, or null when this is a new one. */
   private final ReplayEdit.Caption original;
   private final ReplayEdit.Caption working;
   private TextFieldWidget field;

   TextOverlayScreen(Screen parent, ReplayPlayer replay, ReplayEdit.Caption original, ReplayEdit.Caption fresh) {
      super(Text.literal((original == null ? "Add text at " : "Edit text at ") + ReplayEditorScreen.clock((original != null ? original : fresh).startMs)));
      this.parent = parent;
      this.replay = replay;
      this.original = original;
      this.working = original != null ? original.copy() : fresh;
   }

   private int panelWidth() {
      return Math.min(300, this.width - 20);
   }

   @Override
   protected void init() {
      int w = this.panelWidth();
      int x = this.width / 2 - w / 2;
      int y = 36;
      String typed = this.field != null ? this.field.getText() : this.working.text;
      this.field = new TextFieldWidget(this.textRenderer, x, y, w, 18, Text.literal("Text"));
      this.field.setMaxLength(120);
      this.field.setText(typed);
      this.field.setPlaceholder(Text.literal("Type your text here"));
      this.addDrawableChild(this.field);
      this.setInitialFocus(this.field);
      int half = (w - 4) / 2;
      y += 24;
      this.addDrawableChild(FlatButton.of(Text.literal("Position: " + ReplayEdit.Caption.POSITIONS[this.working.position]), b -> {
         this.working.position = (this.working.position + 1) % ReplayEdit.Caption.POSITIONS.length;
         this.clearAndInit();
      }).dimensions(x, y, half, 20).setting().build());
      this.addDrawableChild(FlatButton.of(Text.literal("Size: " + ReplayEdit.Caption.SIZES[this.working.size]), b -> {
         this.working.size = (this.working.size + 1) % ReplayEdit.Caption.SIZES.length;
         this.clearAndInit();
      }).dimensions(x + half + 4, y, half, 20).setting().build());
      y += 24;
      this.addDrawableChild(FlatButton.of(Text.literal("Colour: " + ReplayEdit.Caption.COLOR_NAMES[this.working.color]), b -> {
         this.working.color = (this.working.color + 1) % ReplayEdit.Caption.COLORS.length;
         this.clearAndInit();
      }).dimensions(x, y, half, 20).setting().build());
      this.addDrawableChild(FlatButton.of(Text.literal("Shows for: " + seconds(this.working.endMs - this.working.startMs)), b -> {
         this.nextLength();
         this.clearAndInit();
      }).dimensions(x + half + 4, y, half, 20).setting().build());
      int bottom = this.height - 28;
      int third = (w - 8) / 3;
      this.addDrawableChild(FlatButton.of(Text.literal("Done"), b -> this.done()).dimensions(x, bottom, third, 20).primary().build());
      ButtonWidget delete = FlatButton.of(Text.literal("Delete"), b -> {
         this.replay.edit.texts.remove(this.original);
         this.replay.edit.save();
         this.back();
      }).dimensions(x + third + 4, bottom, third, 20).build();
      delete.active = this.original != null;
      this.addDrawableChild(delete);
      this.addDrawableChild(FlatButton.of(Text.literal("Cancel"), b -> this.back()).dimensions(x + 2 * third + 8, bottom, w - 2 * third - 8, 20).build());
   }

   private static String seconds(long ms) {
      return String.format(Locale.ROOT, "%.1f s", ms / 1000.0).replace(".0 s", " s");
   }

   private void nextLength() {
      long length = this.working.endMs - this.working.startMs;
      long clipLeft = Math.max(500L, this.replay.clipLengthMs() - this.working.startMs);
      long chosen = LENGTHS[0];
      for (long option : LENGTHS) {
         if (option > length + 50L) {
            chosen = option;
            break;
         }
      }

      if (chosen <= length + 50L || chosen > clipLeft && length >= clipLeft - 50L) {
         chosen = LENGTHS[0];
      }

      this.working.endMs = this.working.startMs + Math.min(chosen, clipLeft);
   }

   private void done() {
      String text = this.field.getText().strip();
      if (text.isEmpty()) {
         if (this.original != null) {
            this.replay.edit.texts.remove(this.original);
            this.replay.edit.save();
         }

         this.back();
         return;
      }

      this.working.text = text;
      int index = this.original == null ? -1 : this.replay.edit.texts.indexOf(this.original);
      if (index >= 0) {
         this.replay.edit.texts.set(index, this.working);
      } else {
         this.replay.edit.texts.add(this.working);
      }

      this.replay.edit.save();
      this.back();
   }

   private void back() {
      this.client.setScreen(this.parent);
   }

   @Override
   public boolean keyPressed(KeyInput input) {
      if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
         this.done();
         return true;
      }

      return super.keyPressed(input);
   }

   @Override
   public void close() {
      this.back();
   }

   @Override
   public boolean shouldPause() {
      return false;
   }

   @Override
   protected void drawPanels(DrawContext context, float alpha) {
      int w = this.panelWidth();
      int x = this.width / 2 - w / 2;
      Ui.panel(context, x - 8, 26, x + w + 8, this.height - 4, alpha);
   }

   @Override
   protected void drawContent(DrawContext context, int mouseX, int mouseY, float deltaTicks, float alpha) {
      Ui.title(context, this.textRenderer, this.title, this.width / 2, 10, alpha);
      int w = this.panelWidth();
      int x = this.width / 2 - w / 2;
      // A preview of how it will look on the video.
      int top = 36 + 24 + 48;
      int bottom = this.height - 34;
      int h = Math.max(20, Math.min(bottom - top, w * 9 / 16));
      int pw = h * 16 / 9;
      int px = this.width / 2 - pw / 2;
      context.fill(px, top, px + pw, top + h, Ui.fade(0xFF2A3A2A, alpha));
      context.fill(px, top + h * 2 / 3, px + pw, top + h, Ui.fade(0xFF3D5A2E, alpha));
      Ui.outline(context, px - 1, top - 1, px + pw + 1, top + h + 1, Ui.fade(Ui.LINE_STRONG, alpha));
      ReplayEdit.Caption preview = this.working.copy();
      preview.text = this.field.getText().isBlank() ? "Your text" : this.field.getText();
      TextOverlays.drawOne(context, this.textRenderer, preview, px, top, pw, h, false, alpha);
   }
}
