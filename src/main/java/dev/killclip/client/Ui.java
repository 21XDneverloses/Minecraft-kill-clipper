package dev.killclip.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * The mod's look in one place: a flat dark theme with one accent colour, thin borders, and a few drawing helpers so
 * every screen matches. Only looks live here; no screen behaviour depends on it.
 */
@Environment(EnvType.CLIENT)
public final class Ui {
   /** The accent: a warm red used for focus, progress and the main action on a screen. */
   public static final int ACCENT = 0xFFFF4655;
   public static final int ACCENT_DIM = 0xFFB8323E;
   /** The kept part of a clip on a timeline: the accent, deep enough to read white text and the playhead on. */
   public static final int KEPT = 0xFF7E2A35;
   /** Primary text. */
   public static final int TEXT = 0xFFF2F3F5;
   /** Secondary labels and hints. */
   public static final int MUTED = 0xFF9AA0A8;
   /** Disabled text and faint marks. */
   public static final int FAINT = 0xFF5C626B;
   public static final int SUCCESS = 0xFF4ADE80;
   public static final int WARN = 0xFFFBBF24;
   public static final int ERROR = 0xFFF87171;
   /** A see-through panel over the menu background or the game. */
   public static final int PANEL = 0xC80C0D10;
   /** A solid surface, e.g. behind the editor. */
   public static final int SURFACE = 0xFF0E0F12;
   /** Raised parts: tracks, rows, idle buttons. */
   public static final int RAISED = 0xFF1A1C21;
   public static final int RAISED_HOVER = 0xFF262931;
   /** Hairline borders and dividers. */
   public static final int LINE = 0x24FFFFFF;
   public static final int LINE_STRONG = 0x40FFFFFF;

   private Ui() {
   }

   /** The colour with its alpha multiplied by {@code alpha} (0..1). */
   public static int fade(int color, float alpha) {
      int a = Math.round((color >>> 24) * Math.max(0.0F, Math.min(1.0F, alpha)));
      return a << 24 | color & 0xFFFFFF;
   }

   /** A blend between two colours, channel by channel (t 0..1). */
   public static int mix(int from, int to, float t) {
      t = Math.max(0.0F, Math.min(1.0F, t));
      int a = lerp(from >>> 24, to >>> 24, t);
      int r = lerp(from >> 16 & 255, to >> 16 & 255, t);
      int g = lerp(from >> 8 & 255, to >> 8 & 255, t);
      int b = lerp(from & 255, to & 255, t);
      return a << 24 | r << 16 | g << 8 | b;
   }

   private static int lerp(int a, int b, float t) {
      return Math.round(a + (b - a) * t);
   }

   /** Moves {@code value} toward {@code target} the same amount per second at any frame rate. */
   public static float approach(float value, float target, float seconds, float speed) {
      return value + (target - value) * (1.0F - (float)Math.exp(-speed * seconds));
   }

   public static float easeOut(float t) {
      t = Math.max(0.0F, Math.min(1.0F, t));
      float inv = 1.0F - t;
      return 1.0F - inv * inv * inv;
   }

   /** A one-pixel outline inside the box. */
   public static void outline(DrawContext context, int x1, int y1, int x2, int y2, int color) {
      context.fill(x1, y1, x2, y1 + 1, color);
      context.fill(x1, y2 - 1, x2, y2, color);
      context.fill(x1, y1 + 1, x1 + 1, y2 - 1, color);
      context.fill(x2 - 1, y1 + 1, x2, y2 - 1, color);
   }

   /** A see-through panel with a hairline border and an accent line along the top. */
   public static void panel(DrawContext context, int x1, int y1, int x2, int y2, float alpha) {
      context.fill(x1, y1, x2, y2, fade(PANEL, alpha));
      outline(context, x1, y1, x2, y2, fade(LINE, alpha));
      context.fill(x1, y1, x2, y1 + 1, fade(ACCENT, alpha * 0.9F));
   }

   /** The screen title, centred, with a short accent bar under it. */
   public static void title(DrawContext context, TextRenderer font, Text title, int centerX, int y, float alpha) {
      context.drawText(font, title, centerX - font.getWidth(title) / 2, y, fade(TEXT, alpha), false);
      context.fill(centerX - 8, y + 11, centerX + 8, y + 12, fade(ACCENT, alpha));
   }

   public static void title(DrawContext context, TextRenderer font, String title, int centerX, int y, float alpha) {
      title(context, font, Text.literal(title), centerX, y, alpha);
   }

   public static void text(DrawContext context, TextRenderer font, String text, int x, int y, int color) {
      context.drawText(font, text, x, y, color, false);
   }

   public static void centered(DrawContext context, TextRenderer font, String text, int centerX, int y, int color) {
      context.drawText(font, text, centerX - font.getWidth(text) / 2, y, color, false);
   }

   public static void right(DrawContext context, TextRenderer font, String text, int rightX, int y, int color) {
      context.drawText(font, text, rightX - font.getWidth(text), y, color, false);
   }

   /** The colour a status message gets from how it reads: errors red, finished work green, the rest amber. */
   public static int statusColor(String status) {
      String s = status.toLowerCase(java.util.Locale.ROOT);
      if (s.contains("fail") || s.contains("could not") || s.contains("can't") || s.contains("cannot") || s.startsWith("no ")) {
         return s.startsWith("no ") ? MUTED : ERROR;
      }

      if (s.startsWith("saved") || s.startsWith("imported") || s.startsWith("link copied")) {
         return SUCCESS;
      }

      return WARN;
   }

   /** A slim progress bar: dark track, accent fill. */
   public static void progress(DrawContext context, int x, int y, int width, int height, float progress, float alpha) {
      context.fill(x, y, x + width, y + height, fade(RAISED, alpha));
      int filled = Math.round(width * Math.max(0.0F, Math.min(1.0F, progress)));
      if (filled > 0) {
         context.fill(x, y, x + filled, y + height, fade(ACCENT, alpha));
      }
   }

   /** The open animation of a screen: fades and slides content up over a short time, at any frame rate. */
   public static final class Opening {
      private static final float SECONDS = 0.22F;
      private final long start = System.nanoTime();

      /** 0 when the screen opens, 1 once it has settled. */
      public float progress() {
         return easeOut((System.nanoTime() - this.start) / 1.0E9F / SECONDS);
      }

      /** How far below its place the content is drawn, in GUI pixels. */
      public float offset() {
         return (1.0F - this.progress()) * 8.0F;
      }
   }
}
