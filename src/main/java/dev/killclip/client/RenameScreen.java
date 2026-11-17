package dev.killclip.client;

import dev.killclip.core.ClipLibrary;
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Gives a clip a name of your own. Its replay, edit and picture are renamed with it. */
@Environment(EnvType.CLIENT)
final class RenameScreen extends FlatScreen {
   private final ClipLibraryScreen parent;
   private final Path clip;
   private final String current;
   private TextFieldWidget field;

   RenameScreen(ClipLibraryScreen parent, Path clip, String current) {
      super(Text.literal("Rename clip"));
      this.parent = parent;
      this.clip = clip;
      this.current = current;
   }

   private int panelWidth() {
      return Math.min(280, this.width - 20);
   }

   @Override
   protected void init() {
      int w = this.panelWidth();
      int x = this.width / 2 - w / 2;
      int y = this.height / 2 - 30;
      String typed = this.field != null ? this.field.getText() : this.current;
      this.field = new TextFieldWidget(this.textRenderer, x, y, w, 18, Text.literal("Name"));
      this.field.setMaxLength(60);
      this.field.setText(typed);
      this.field.setPlaceholder(Text.literal("Name it, e.g. clutch 1v3"));
      this.addDrawableChild(this.field);
      this.setInitialFocus(this.field);
      int half = (w - 4) / 2;
      this.addDrawableChild(FlatButton.of(Text.literal("Rename"), b -> this.done()).dimensions(x, y + 40, half, 20).primary().build());
      this.addDrawableChild(FlatButton.of(Text.literal("Cancel"), b -> this.close()).dimensions(x + half + 4, y + 40, w - half - 4, 20).build());
   }

   private void done() {
      String name = this.field.getText().strip();
      if (!name.isEmpty() && !name.equals(this.current)) {
         this.parent.renamed(this.clip, name);
      }

      this.close();
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
      this.client.setScreen(this.parent);
   }

   @Override
   protected void drawPanels(DrawContext context, float alpha) {
      int w = this.panelWidth();
      int x = this.width / 2 - w / 2;
      Ui.panel(context, x - 8, this.height / 2 - 56, x + w + 8, this.height / 2 + 38, alpha);
   }

   @Override
   protected void drawContent(DrawContext context, int mouseX, int mouseY, float deltaTicks, float alpha) {
      int w = this.panelWidth();
      int x = this.width / 2 - w / 2;
      Ui.title(context, this.textRenderer, this.title, this.width / 2, this.height / 2 - 48, alpha);
      String file = ClipLibrary.renamedFile(this.clip, this.field.getText());
      Ui.text(context, this.textRenderer, FlatButton.trim(this.textRenderer, "File: " + file, w), x, this.height / 2 - 8, Ui.fade(Ui.FAINT, alpha));
   }
}
