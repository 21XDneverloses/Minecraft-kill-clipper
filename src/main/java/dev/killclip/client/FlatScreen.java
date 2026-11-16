package dev.killclip.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/**
 * A screen in the mod's flat style. The content fades in and slides up a few pixels when the screen opens; subclasses
 * draw their panels in {@link #drawPanels} (under the buttons) and their text in {@link #drawContent} (over them).
 */
@Environment(EnvType.CLIENT)
abstract class FlatScreen extends Screen {
   private Ui.Opening opening = new Ui.Opening();

   protected FlatScreen(Text title) {
      super(title);
   }

   /** Plays the open animation again, e.g. when coming back from another screen. */
   protected void replayOpening() {
      this.opening = new Ui.Opening();
   }

   @Override
   public final void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
      float alpha = this.opening.progress();
      int offset = Math.round(this.opening.offset());
      for (Element child : this.children()) {
         if (child instanceof ClickableWidget widget) {
            widget.setAlpha(alpha);
         }
      }

      context.getMatrices().pushMatrix();
      context.getMatrices().translate(0.0F, offset);
      this.drawPanels(context, alpha);
      super.render(context, mouseX, mouseY - offset, deltaTicks);
      this.drawContent(context, mouseX, mouseY - offset, deltaTicks, alpha);
      context.getMatrices().popMatrix();
   }

   /** Panels and backdrops, drawn under the buttons. */
   protected void drawPanels(DrawContext context, float alpha) {
   }

   /** Titles, labels and status text, drawn over the buttons. */
   protected abstract void drawContent(DrawContext context, int mouseX, int mouseY, float deltaTicks, float alpha);
}
