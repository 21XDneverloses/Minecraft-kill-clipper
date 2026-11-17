package dev.killclip.client;

import dev.killclip.core.ClipLibrary;
import java.io.IOException;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * How much space the clips may take: a size cap and an age limit. The oldest clips go first; favourites and edited
 * videos are never deleted. The limits are checked when a clip is saved and when the library opens.
 */
@Environment(EnvType.CLIENT)
final class StorageScreen extends FlatScreen {
   private static final double[] SIZES = {0, 5, 10, 20, 50, 100};
   private static final int[] DAYS = {0, 7, 14, 30, 60, 90};
   private final ClipLibraryScreen parent;
   private ClipLibrary library;
   private List<ClipLibrary.Item> items = List.of();
   private boolean confirm;
   private String status = "";

   StorageScreen(ClipLibraryScreen parent) {
      super(Text.literal("Clip storage"));
      this.parent = parent;
   }

   private int panelWidth() {
      return Math.min(300, this.width - 20);
   }

   @Override
   protected void init() {
      this.library = ClipLibrary.load(ClipStorage.clipsDir());
      try {
         this.items = ClipStorage.scan(this.library);
      } catch (IOException e) {
         this.items = List.of();
         this.status = "Could not read the clips folder: " + e.getMessage();
      }

      int w = this.panelWidth();
      int x = this.width / 2 - w / 2;
      int y = 70;
      double gb = this.library.maxGb();
      this.addDrawableChild(FlatButton.of(Text.literal("Max size: " + (gb <= 0 ? "No limit" : trim(gb) + " GB")), b -> {
         this.library.setMaxGb(next(SIZES, gb));
         this.confirm = false;
         this.clearAndInit();
      }).dimensions(x, y, w, 20).setting().build());
      int days = this.library.maxDays();
      this.addDrawableChild(FlatButton.of(Text.literal("Delete clips older than: " + (days <= 0 ? "Never" : days + " days")), b -> {
         int nextDays = DAYS[0];
         for (int option : DAYS) {
            if (option > days) {
               nextDays = option;
               break;
            }
         }

         this.library.setMaxDays(nextDays);
         this.confirm = false;
         this.clearAndInit();
      }).dimensions(x, y + 24, w, 20).setting().build());
      List<ClipLibrary.Item> over = ClipStorage.overLimits(this.library, this.items);
      long bytes = over.stream().mapToLong(ClipLibrary.Item::bytes).sum();
      String clean = over.isEmpty() ? "Nothing to clean up" : this.confirm ? "Delete " + over.size() + " clips? Click again"
         : "Clean up now: " + over.size() + " clips, " + ClipStorage.size(bytes);
      ButtonWidget cleanUp = FlatButton.of(Text.literal(clean), b -> {
         if (!this.confirm) {
            this.confirm = true;
         } else {
            this.confirm = false;
            int deleted = ClipStorage.deleteAll(this.library, over);
            this.status = "Deleted " + deleted + " old clips.";
            this.parent.storageChanged(this.status);
         }

         this.clearAndInit();
      }).dimensions(x, y + 54, w, 20).build();
      cleanUp.active = !over.isEmpty();
      this.addDrawableChild(cleanUp);
      this.addDrawableChild(FlatButton.of(Text.literal("Done"), b -> this.close()).dimensions(x, this.height - 28, w, 20).primary().build());
   }

   private static double next(double[] options, double current) {
      for (double option : options) {
         if (option > current + 0.001) {
            return option;
         }
      }

      return options[0];
   }

   private static String trim(double value) {
      return value == Math.floor(value) ? String.valueOf((long)value) : String.valueOf(value);
   }

   @Override
   public void close() {
      this.parent.storageChanged(this.status);
      this.client.setScreen(this.parent);
   }

   @Override
   protected void drawPanels(DrawContext context, float alpha) {
      int w = this.panelWidth();
      int x = this.width / 2 - w / 2;
      Ui.panel(context, x - 8, 24, x + w + 8, this.height - 4, alpha);
   }

   @Override
   protected void drawContent(DrawContext context, int mouseX, int mouseY, float deltaTicks, float alpha) {
      Ui.title(context, this.textRenderer, this.title, this.width / 2, 8, alpha);
      int w = this.panelWidth();
      int x = this.width / 2 - w / 2;
      long total = this.items.stream().filter(i -> !i.edited()).mapToLong(ClipLibrary.Item::bytes).sum();
      long favourites = this.items.stream().filter(ClipLibrary.Item::favourite).count();
      Ui.text(context, this.textRenderer, "Your clips use " + ClipStorage.size(total) + " (" + this.items.size() + " clips, " + favourites + (favourites == 1 ? " favourite)." : " favourites)."),
         x, 34, Ui.fade(Ui.TEXT, alpha));
      Ui.text(context, this.textRenderer, FlatButton.trim(this.textRenderer, "Oldest go first. Favourites and edits are never deleted.", w), x, 48,
         Ui.fade(Ui.MUTED, alpha));
      if (!this.status.isEmpty()) {
         Ui.centered(context, this.textRenderer, this.status, this.width / 2, 70 + 82, Ui.fade(Ui.statusColor(this.status), alpha));
      }
   }
}
