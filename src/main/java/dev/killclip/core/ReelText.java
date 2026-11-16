package dev.killclip.core;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * The text the highlight reel lays over the video (kill popups, the intro title and the closing card), drawn
 * here as see-through PNGs so it looks the same on every PC and needs no font files from FFmpeg.
 */
public final class ReelText {
   /** Bold display fonts most PCs have, best first; the plain bold sans-serif is the fallback. */
   private static final String[] FONTS = {"Impact", "Bahnschrift", "Arial Black", "Helvetica Neue", "DejaVu Sans", "Arial"};
   private static String family;

   public enum Style {
      /** A kill: white with a gold underline glow. */
      KILL(new Color(255, 255, 255), new Color(255, 196, 40)),
      /** A multi-kill: gold. */
      STREAK(new Color(255, 214, 64), new Color(255, 120, 20)),
      /** The intro and the closing card. */
      TITLE(new Color(255, 255, 255), new Color(90, 200, 255)),
      /** The instant replay tag. */
      REPLAY(new Color(255, 255, 255), new Color(255, 70, 70));

      final Color fill;
      final Color accent;

      Style(Color fill, Color accent) {
         this.fill = fill;
         this.accent = accent;
      }
   }

   private ReelText() {
   }

   private static synchronized String family() {
      if (family == null) {
         family = Font.SANS_SERIF;
         try {
            Set<String> installed = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            for (String name : FONTS) {
               if (installed.contains(name)) {
                  family = name;
                  break;
               }
            }
         } catch (Throwable ignored) {
            // No font list (unusual headless setups): the logical font still draws.
         }
      }

      return family;
   }

   /**
    * Draws a popup {@code videoWidth} wide: a big main line and an optional smaller line under it. The image is
    * as wide as the video so FFmpeg only has to place it at the right height.
    */
   public static void write(String main, String sub, Style style, int videoWidth, int videoHeight, Path png) throws IOException {
      int width = Math.max(64, videoWidth / 2 * 2);
      int base = Math.min(videoWidth, videoHeight);
      int mainSize = Math.max(14, (int)(base * (style == Style.TITLE ? 0.12 : 0.085)));
      int subSize = Math.max(10, (int)(mainSize * 0.42));
      int height = (int)(mainSize * 1.45 + (sub == null || sub.isEmpty() ? 0 : subSize * 1.6)) / 2 * 2 + 4;
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = image.createGraphics();
      try {
         g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
         g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
         g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
         g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
         FontRenderContext context = g.getFontRenderContext();
         Font font = fit(new Font(family(), Font.BOLD | Font.ITALIC, mainSize), main, width * 0.92, context);
         TextLayout layout = new TextLayout(main, font, context);
         double textWidth = layout.getBounds().getWidth();
         float x = (float)((width - textWidth) / 2.0 - layout.getBounds().getX());
         float y = (float)(mainSize * 1.1);
         Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(x, y));
         // Soft shadow, dark outline, then the gradient fill: readable on snow, lava and the night sky alike.
         g.setColor(new Color(0, 0, 0, 110));
         g.fill(AffineTransform.getTranslateInstance(mainSize * 0.05, mainSize * 0.07).createTransformedShape(outline));
         g.setStroke(new BasicStroke(Math.max(2.0F, mainSize * 0.11F), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
         g.setColor(new Color(10, 10, 14, 235));
         g.draw(outline);
         g.setPaint(new GradientPaint(0, y - mainSize * 0.8F, style.fill, 0, y, blend(style.fill, style.accent, 0.45F)));
         g.fill(outline);
         if (sub != null && !sub.isEmpty()) {
            Font small = fit(new Font(family(), Font.BOLD, subSize), sub, width * 0.9, context);
            TextLayout subLayout = new TextLayout(sub, small, context);
            float sx = (float)((width - subLayout.getBounds().getWidth()) / 2.0 - subLayout.getBounds().getX());
            float sy = y + mainSize * 0.25F + subSize * 1.15F;
            Shape subOutline = subLayout.getOutline(AffineTransform.getTranslateInstance(sx, sy));
            g.setStroke(new BasicStroke(Math.max(1.5F, subSize * 0.16F), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(10, 10, 14, 220));
            g.draw(subOutline);
            g.setColor(style.accent);
            g.fill(subOutline);
         }
      } finally {
         g.dispose();
      }

      if (!ImageIO.write(image, "png", png.toFile())) {
         throw new IOException("could not write " + png.getFileName());
      }
   }

   /** Shrinks the font until the text fits the width. */
   private static Font fit(Font font, String text, double maxWidth, FontRenderContext context) {
      Font f = font;
      for (int i = 0; i < 12 && new TextLayout(text, f, context).getBounds().getWidth() > maxWidth; i++) {
         f = f.deriveFont(f.getSize2D() * 0.88F);
      }

      return f;
   }

   private static Color blend(Color a, Color b, float t) {
      return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t), Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
         Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
   }
}
