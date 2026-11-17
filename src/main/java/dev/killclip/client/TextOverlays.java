package dev.killclip.client;

import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;

/**
 * Draws the text you put on a replay. The same drawing is used over the game while the replay plays or exports
 * (so it is in the exported video) and over the small picture in the editor, scaled to the picture's size.
 */
@Environment(EnvType.CLIENT)
public final class TextOverlays {
   private static final float[] SCALES = {0.75F, 1.15F, 1.7F};

   private TextOverlays() {
   }

   /** Over the game, after the HUD: only while a replay plays without the editor covering it. */
   public static void drawInGame(DrawContext context) {
      ReplayPlayer replay = ReplayPlayer.active();
      MinecraftClient client = MinecraftClient.getInstance();
      if (replay == null || client.world == null || client.currentScreen instanceof ReplayEditorScreen || replay.edit.texts.isEmpty()) {
         return;
      }

      draw(context, client.textRenderer, replay.edit.textsAt(replay.clipTimeMs()), replay.clipTimeMs(), 0, 0, context.getScaledWindowWidth(),
         context.getScaledWindowHeight(), replay.exportingVertical(), 1.0F);
   }

   /** Draws the texts into the picture area {x, y, w, h}; {@code vertical} keeps them inside the middle 9:16. */
   static void draw(DrawContext context, TextRenderer font, List<ReplayEdit.Caption> texts, long nowMs, int x, int y, int w, int h, boolean vertical,
      float alpha) {
      for (ReplayEdit.Caption caption : texts) {
         // A quick fade in and out at the ends.
         float fade = Math.min(1.0F, Math.min(nowMs - caption.startMs, caption.endMs - nowMs) / 150.0F);
         drawOne(context, font, caption, x, y, w, h, vertical, alpha * Math.max(0.0F, fade));
      }
   }

   static void drawOne(DrawContext context, TextRenderer font, ReplayEdit.Caption caption, int x, int y, int w, int h, boolean vertical, float alpha) {
      if (alpha <= 0.02F || caption.text == null || caption.text.isBlank()) {
         return;
      }

      float scale = h / 180.0F * SCALES[Math.floorMod(caption.size, SCALES.length)];
      int usable = vertical ? Math.min(w, Math.round(h * 9.0F / 16.0F)) : w;
      int wrap = Math.max(20, (int)(usable * 0.86F / scale));
      List<OrderedText> lines = font.wrapLines(StringVisitable.plain(caption.text), wrap);
      int lineHeight = font.fontHeight + 2;
      int blockWidth = 0;
      for (OrderedText line : lines) {
         blockWidth = Math.max(blockWidth, font.getWidth(line));
      }

      int blockHeight = lines.size() * lineHeight - 2;
      float centerX = x + w / 2.0F;
      float top = switch (caption.position) {
         case 0 -> y + h * 0.08F;
         case 1 -> y + (h - blockHeight * scale) / 2.0F;
         default -> y + h * 0.86F - blockHeight * scale;
      };
      context.getMatrices().pushMatrix();
      context.getMatrices().translate(centerX, top);
      context.getMatrices().scale(scale, scale);
      // A soft dark box behind the words so they read on any background.
      int pad = 4;
      context.fill(-blockWidth / 2 - pad, -pad + 1, blockWidth / 2 + pad + 1, blockHeight + pad - 1, Ui.fade(0x8C000000, alpha));
      int color = Ui.fade(caption.rgb(), alpha);
      for (int i = 0; i < lines.size(); i++) {
         OrderedText line = lines.get(i);
         context.drawText(font, line, -font.getWidth(line) / 2, i * lineHeight, color, true);
      }

      context.getMatrices().popMatrix();
   }
}
