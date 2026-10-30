package dev.killclip.client;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Kills, deaths, K/D and streaks, overall and for each server. */
@Environment(EnvType.CLIENT)
public final class StatsScreen extends Screen {
   private static final String[] HEADERS = {"Server", "Kills", "Deaths", "K/D", "Top life", "Top multi", "Streaks"};
   private final Screen parent;
   private int page;
   private boolean confirmReset;

   public StatsScreen(Screen parent) {
      super(Text.literal("Kill stats"));
      this.parent = parent;
   }

   private int rows() {
      return Math.max(1, (this.height - 32 - 50 - 30) / 12);
   }

   @Override
   protected void init() {
      int servers = KillClipperClient.instance().stats.servers().size();
      int pages = Math.max(1, (servers + this.rows() - 1) / this.rows());
      this.page = Math.min(this.page, pages - 1);
      int x = this.width / 2 - 150;
      int y = this.height - 28;
      ButtonWidget previous = ButtonWidget.builder(Text.literal("<"), b -> {
         this.page--;
         this.clearAndInit();
      }).dimensions(x, y, 30, 20).build();
      previous.active = this.page > 0;
      this.addDrawableChild(previous);
      ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> {
         this.page++;
         this.clearAndInit();
      }).dimensions(x + 34, y, 30, 20).build();
      next.active = this.page < pages - 1;
      this.addDrawableChild(next);
      this.addDrawableChild(ButtonWidget.builder(Text.literal(this.confirmReset ? "Really reset?" : "Reset stats"), b -> {
         if (this.confirmReset) {
            KillClipperClient.instance().stats.reset();
            this.confirmReset = false;
         } else {
            this.confirmReset = true;
         }

         this.clearAndInit();
      }).dimensions(x + 68, y, 110, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close()).dimensions(x + 182, y, 118, 20).build());
   }

   @Override
   public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
      super.render(context, mouseX, mouseY, deltaTicks);
      KillStats stats = KillClipperClient.instance().stats;
      context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFFFF);
      int tableWidth = Math.min(this.width - 20, 420);
      int left = this.width / 2 - tableWidth / 2;
      int nameWidth = tableWidth - 6 * 48;
      int y = 30;
      this.row(context, left, y, nameWidth, HEADERS, 0xFFFFFF55);
      y += 14;
      this.row(context, left, y, nameWidth, cells("All servers", stats.total()), 0xFF55FF55);
      y += 16;
      List<Map.Entry<String, KillStats.Line>> servers = stats.servers();
      int rows = this.rows();
      for (int i = this.page * rows; i < Math.min(servers.size(), (this.page + 1) * rows); i++) {
         this.row(context, left, y, nameWidth, cells(servers.get(i).getKey(), servers.get(i).getValue()), 0xFFFFFFFF);
         y += 12;
      }

      if (servers.isEmpty()) {
         context.drawCenteredTextWithShadow(this.textRenderer, "No kills yet. Stats count from v1.6.0 on.", this.width / 2, y + 6, 0xFFAAAAAA);
      }
   }

   private static String[] cells(String name, KillStats.Line line) {
      return new String[]{name, String.valueOf(line.kills), String.valueOf(line.deaths), String.format(Locale.ROOT, "%.2f", line.kd()),
         String.valueOf(line.bestLife), String.valueOf(line.bestStreak), String.valueOf(line.streaks)};
   }

   private void row(DrawContext context, int left, int y, int nameWidth, String[] cells, int color) {
      context.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(cells[0], nameWidth - 4), left, y, color);
      for (int i = 1; i < cells.length; i++) {
         int right = left + nameWidth + i * 48;
         context.drawTextWithShadow(this.textRenderer, cells[i], right - 4 - this.textRenderer.getWidth(cells[i]), y, color);
      }
   }

   @Override
   public void close() {
      this.client.setScreen(this.parent);
   }
}
