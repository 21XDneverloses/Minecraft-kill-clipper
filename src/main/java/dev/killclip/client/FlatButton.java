package dev.killclip.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * A flat button: dark fill, hairline border, white label, and a smooth accent highlight on hover. Works exactly like
 * a vanilla button (same clicks, keys, sound and narration); only the drawing is different.
 */
@Environment(EnvType.CLIENT)
public final class FlatButton extends ButtonWidget {
   public enum Style {
      /** An ordinary button. */
      NORMAL,
      /** The main action of a screen, filled with the accent colour. */
      PRIMARY,
      /** A setting written "Name: value": the name muted on the left, the value bright on the right. */
      SETTING,
      /** A list row: left-aligned, with an accent bar when selected and an optional badge on the right. */
      ROW
   }

   private final Style style;
   private boolean selected;
   private String badge = "";
   private float hover;
   private long lastFrame;

   private FlatButton(int x, int y, int width, int height, net.minecraft.text.Text message, PressAction onPress, Style style) {
      super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
      this.style = style;
   }

   public static Builder of(net.minecraft.text.Text message, PressAction onPress) {
      return new Builder(message, onPress);
   }

   public FlatButton selected(boolean selected) {
      this.selected = selected;
      return this;
   }

   public FlatButton badge(String badge) {
      this.badge = badge == null ? "" : badge;
      return this;
   }

   @Override
   protected void drawIcon(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
      long now = System.nanoTime();
      float seconds = this.lastFrame == 0L ? 0.0F : Math.min(0.1F, (now - this.lastFrame) / 1.0E9F);
      this.lastFrame = now;
      boolean lit = this.active && (this.isHovered() || this.isFocused());
      this.hover = Ui.approach(this.hover, lit ? 1.0F : 0.0F, seconds, 18.0F);
      float a = this.alpha;
      int x1 = this.getX();
      int y1 = this.getY();
      int x2 = x1 + this.width;
      int y2 = y1 + this.height;
      TextRenderer font = MinecraftClient.getInstance().textRenderer;
      int textY = y1 + (this.height - 8) / 2;
      boolean primary = this.style == Style.PRIMARY && this.active;

      int fill;
      int border;
      if (primary) {
         fill = Ui.mix(Ui.ACCENT_DIM, Ui.ACCENT, this.hover);
         border = Ui.mix(Ui.ACCENT_DIM, 0xFFFF8A94, this.hover);
      } else if (!this.active) {
         fill = 0x90141519;
         border = 0x14FFFFFF;
      } else {
         int idle = this.style == Style.ROW && this.selected ? 0xF0232630 : 0xE0181A1F;
         fill = Ui.mix(idle, Ui.RAISED_HOVER, this.hover);
         border = Ui.mix(this.style == Style.ROW && this.selected ? Ui.LINE_STRONG : Ui.LINE, Ui.fade(Ui.ACCENT, 0.85F), this.hover);
      }

      context.fill(x1, y1, x2, y2, Ui.fade(fill, a));
      Ui.outline(context, x1, y1, x2, y2, Ui.fade(border, a));
      int label = !this.active ? Ui.FAINT : Ui.TEXT;
      switch (this.style) {
         case ROW -> {
            if (this.selected) {
               context.fill(x1, y1, x1 + 2, y2, Ui.fade(Ui.ACCENT, a));
            }

            int right = x2 - 6;
            if (!this.badge.isEmpty()) {
               int badgeWidth = font.getWidth(this.badge) + 6;
               int bx = right - badgeWidth;
               context.fill(bx, textY - 2, right, textY + 9, Ui.fade(0x33FF4655, a));
               context.drawText(font, this.badge, bx + 3, textY, Ui.fade(0xFFFF9AA2, a), false);
               right = bx - 6;
            }

            String text = trim(font, this.getMessage().getString(), right - x1 - 10);
            context.drawText(font, text, x1 + 8, textY, Ui.fade(this.selected ? Ui.TEXT : Ui.mix(0xFFD0D3D8, Ui.TEXT, this.hover), a), false);
         }
         case SETTING -> {
            String message = this.getMessage().getString();
            int split = message.indexOf(": ");
            if (split < 0) {
               this.centered(context, font, message, textY, label, a);
            } else {
               String name = message.substring(0, split);
               String value = message.substring(split + 2);
               int valueWidth = Math.min(font.getWidth(value), this.width - 16);
               value = trim(font, value, valueWidth);
               context.drawText(font, trim(font, name, this.width - valueWidth - 20), x1 + 7, textY,
                  Ui.fade(this.active ? Ui.mix(Ui.MUTED, 0xFFC4C8CE, this.hover) : Ui.FAINT, a), false);
               int valueColor = !this.active ? Ui.FAINT : value.equals("ON") ? Ui.SUCCESS : value.equals("OFF") ? Ui.MUTED : Ui.TEXT;
               context.drawText(font, value, x2 - 7 - font.getWidth(value), textY, Ui.fade(valueColor, a), false);
            }
         }
         default -> this.centered(context, font, this.getMessage().getString(), textY, label, a);
      }

      if (!primary && this.hover > 0.01F && this.style != Style.ROW) {
         // An accent underline that grows from the middle on hover.
         int half = Math.round((this.width - 2) / 2.0F * this.hover);
         int mid = x1 + this.width / 2;
         context.fill(mid - half, y2 - 1, mid + half, y2, Ui.fade(Ui.ACCENT, a));
      }
   }

   private void centered(DrawContext context, TextRenderer font, String message, int y, int color, float alpha) {
      String text = trim(font, message, this.width - 4);
      int x = this.getX() + (this.width - font.getWidth(text)) / 2;
      context.drawText(font, text, x, y, Ui.fade(color, alpha), false);
   }

   /** Text cut to fit, ending in an ellipsis when it was cut. */
   static String trim(TextRenderer font, String text, int width) {
      if (width <= 0) {
         return "";
      }

      if (font.getWidth(text) <= width) {
         return text;
      }

      return font.trimToWidth(text, Math.max(0, width - font.getWidth("..."))) + "...";
   }

   @Environment(EnvType.CLIENT)
   public static final class Builder {
      private final net.minecraft.text.Text message;
      private final PressAction onPress;
      private Style style = Style.NORMAL;
      private int x;
      private int y;
      private int width = 150;
      private int height = 20;

      private Builder(net.minecraft.text.Text message, PressAction onPress) {
         this.message = message;
         this.onPress = onPress;
      }

      public Builder dimensions(int x, int y, int width, int height) {
         this.x = x;
         this.y = y;
         this.width = width;
         this.height = height;
         return this;
      }

      public Builder style(Style style) {
         this.style = style;
         return this;
      }

      public Builder primary() {
         return this.style(Style.PRIMARY);
      }

      public Builder setting() {
         return this.style(Style.SETTING);
      }

      public Builder row() {
         return this.style(Style.ROW);
      }

      public FlatButton build() {
         return new FlatButton(this.x, this.y, this.width, this.height, this.message, this.onPress, this.style);
      }
   }
}
