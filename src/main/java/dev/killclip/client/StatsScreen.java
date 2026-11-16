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
public final class StatsScreen extends FlatScreen {
   private static final String[] HEADERS = {"Server", "Kills", "Deaths", "K/D", "Top life", "Top multi", "Streaks"};
   private final Screen parent;
   private int page;
   private boolean confirmReset;

   public StatsScreen(Screen parent) {
      super(Text.literal("Kill stats"));
      this.parent = parent;
   }

   private int rows() {
      return Math.max(1, (this.height - 32 - 54 - 30) / 12);
   }

   @Override
   protected void init() {
      int servers = KillClipperClient.instance().stats.servers().size();
      int pages = Math.max(1, (servers + this.rows() - 1) / this.rows());
      this.page = Math.min(this.page, pages - 1);
      int x = this.width / 2 - 150;
      int y = this.height - 28;
      ButtonWidget previous = FlatButton.of(Text.literal("<"), b -> {
         this.page--;
         this.clearAndInit();
      }).dimensions(x, y, 30, 20).build();
      previous.active = this.page > 0;
      this.addDrawableChild(previous);
      ButtonWidget next = FlatButton.of(Text.literal(">"), b -> {
         this.page++;
         this.clearAndInit();
      }).dimensions(x + 34, y, 30, 20).build();
      next.active = this.page < pages - 1;
      this.addDrawableChild(next);
      this.addDrawableChild(FlatButton.of(Text.literal(this.confirmReset ? "Really reset?" : "Reset stats"), b -> {
         if (this.confirmReset) {
            KillClipperClient.instance().stats.reset();
            this.confirmReset = false;
         } else {
            this.confirmReset = true;
         }

         this.clearAndInit();
      }).dimensions(x + 68, y, 110, 20).build());
      this.addDrawableChild(FlatButton.of(Text.literal("Done"), b -> this.close()).dimensions(x + 182, y, 118, 20).build());
   }

   private int tableWidth() {
      return Math.min(this.width - 20, 420);
   }

   @Override
   protected void drawPanels(DrawContext context, float alpha) {
      int left = this.width / 2 - this.tableWidth() / 2;
      Ui.panel(context, left - 8, 24, left + this.tableWidth() + 8, this.height - 34, alpha);
   }

   @Override
   protected void drawContent(DrawContext context, int mouseX, int mouseY, float deltaTicks, float alpha) {
      KillStats stats = KillClipperClient.instance().stats;
      Ui.title(context, this.textRenderer, this.title, this.width / 2, 8, alpha);
      int tableWidth = this.tableWidth();
      int left = this.width / 2 - tableWidth / 2;
      int nameWidth = tableWidth - 6 * 48;
      int y = 32;
      this.row(context, left, y, nameWidth, HEADERS, Ui.fade(Ui.MUTED, alpha));
      y += 12;
      context.fill(left, y, left + tableWidth, y + 1, Ui.fade(Ui.LINE, alpha));
      y += 4;
      // The overall line, highlighted like a card.
      context.fill(left - 4, y - 3, left + tableWidth + 4, y + 11, Ui.fade(0x22FF4655, alpha));
      context.fill(left - 4, y - 3, left - 2, y + 11, Ui.fade(Ui.ACCENT, alpha));
      this.row(context, left, y, nameWidth, cells("All servers", stats.total()), Ui.fade(Ui.TEXT, alpha));
      y += 18;
      List<Map.Entry<String, KillStats.Line>> servers = stats.servers();
      int rows = this.rows();
      int shown = 0;
      for (int i = this.page * rows; i < Math.min(servers.size(), (this.page + 1) * rows); i++) {
         if (shown++ % 2 == 1) {
            // Faint stripes keep long rows easy to follow.
            context.fill(left - 2, y - 2, left + tableWidth + 2, y + 10, Ui.fade(0x0CFFFFFF, alpha));
         }

         this.row(context, left, y, nameWidth, cells(servers.get(i).getKey(), servers.get(i).getValue()), Ui.fade(0xFFD6D9DE, alpha));
         y += 12;
      }

      if (servers.isEmpty()) {
         Ui.centered(context, this.textRenderer, "No kills yet. Stats count from v1.6.0 on.", this.width / 2, y + 6, Ui.fade(Ui.MUTED, alpha));
      }
   }

   private static String[] cells(String name, KillStats.Line line) {
      return new String[]{name, String.valueOf(line.kills), String.valueOf(line.deaths), String.format(Locale.ROOT, "%.2f", line.kd()),
         String.valueOf(line.bestLife), String.valueOf(line.bestStreak), String.valueOf(line.streaks)};
   }

   private void row(DrawContext context, int left, int y, int nameWidth, String[] cells, int color) {
      Ui.text(context, this.textRenderer, FlatButton.trim(this.textRenderer, cells[0], nameWidth - 4), left, y, color);
      for (int i = 1; i < cells.length; i++) {
         int right = left + nameWidth + i * 48;
         Ui.right(context, this.textRenderer, cells[i], right - 4, y, color);
      }
   }

   @Override
   public void close() {
      this.client.setScreen(this.parent);
   }
}
