package dev.killclip.client;

import dev.killclip.core.ClipLibrary;
import dev.killclip.core.VideoTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.Identifier;
import net.minecraft.text.Text;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * The clip editor's main-menu library: every clip with a picture of the kill, search, filters, favourites, tags,
 * rename and delete, and the storage limits.
 */
@Environment(EnvType.CLIENT)
public final class ClipLibraryScreen extends FlatScreen {
   private static final int ROW = 26;
   private static final int THUMB_W = 40;
   private static final int THUMB_H = 22;
   private static final int LIST_TOP = 50;
   /** The filter and search are kept while the game runs, so going to the editor and back keeps them. */
   private static ClipLibrary.Filter filter = ClipLibrary.Filter.ALL;
   private static String search = "";
   private final Screen parent;
   private final List<ClipLibrary.Item> all = new ArrayList<>();
   private final List<ClipLibrary.Item> shown = new ArrayList<>();
   private final ClipThumbnails thumbnails = new ClipThumbnails();
   private ClipLibrary library;
   private boolean scanned;
   private boolean rebuild;
   private boolean confirmDelete;
   /** Turn to the page of the selected clip on the next layout (after a rename or import). */
   private boolean revealSelected;
   private int page;
   private Path selected;
   private String status = "";
   private TextFieldWidget searchField;
   private final List<int[]> rowBoxes = new ArrayList<>();

   public ClipLibraryScreen(Screen parent) {
      super(Text.literal("Clips"));
      this.parent = parent;
   }

   void devSelect(Path clip) {
      this.selected = clip;
      this.revealSelected = true;
      this.clearAndInit();
   }

   private Path dir() {
      return KillClipperClient.instance().settings().clipsDir();
   }

   /** Reads the clips folder again (after a clip was added, renamed or deleted). */
   private void rescan() {
      this.scanned = false;
   }

   private void scan() {
      if (this.scanned) {
         return;
      }

      this.scanned = true;
      this.library = ClipLibrary.load(this.dir());
      this.all.clear();
      try {
         this.all.addAll(ClipStorage.scan(this.library));
      } catch (IOException e) {
         this.status = "Could not read the clips folder: " + e.getMessage();
      }

      // The server of each clip is in its replay: look up the ones not known yet, in the background.
      List<Path> unknown = this.all.stream().filter(i -> i.replay() && i.server() == null).map(ClipLibrary.Item::file).toList();
      if (!unknown.isEmpty()) {
         ClipLibrary lib = this.library;
         ClipStorage.WORKER.execute(() -> {
            java.util.Map<Path, String> found = new java.util.HashMap<>();
            for (Path clip : unknown) {
               found.put(clip, ClipStorage.readServer(clip));
            }

            MinecraftClient.getInstance().execute(() -> {
               if (lib != this.library) {
                  return;
               }

               found.forEach(lib::setServer);
               lib.save();
               this.rescan();
               this.rebuild = true;
            });
         });
      }
   }

   private void applyFilter() {
      this.shown.clear();
      for (ClipLibrary.Item item : this.all) {
         if (ClipLibrary.matches(item, filter, search)) {
            this.shown.add(item);
         }
      }
   }

   private int rows() {
      return Math.max(1, (this.height - 52 - 16 - LIST_TOP) / ROW);
   }

   private int listWidth() {
      return Math.min(360, this.width - 20);
   }

   private ClipLibrary.Item selectedItem() {
      for (ClipLibrary.Item item : this.all) {
         if (item.file().equals(this.selected)) {
            return item;
         }
      }

      return null;
   }

   @Override
   protected void init() {
      String error = ReplayPlayer.openError;
      if (error != null) {
         ReplayPlayer.openError = null;
         this.status = error;
      }

      if (!this.scanned) {
         ClipStorage.enforceLater();
      }

      this.scan();
      this.applyFilter();
      int rows = this.rows();
      int pages = Math.max(1, (this.shown.size() + rows - 1) / rows);
      if (this.revealSelected) {
         this.revealSelected = false;
         for (int i = 0; i < this.shown.size(); i++) {
            if (this.shown.get(i).file().equals(this.selected)) {
               this.page = i / rows;
            }
         }
      }

      this.page = Math.max(0, Math.min(this.page, pages - 1));
      int listWidth = this.listWidth();
      int x = this.width / 2 - listWidth / 2;
      // Search box, filter and storage along the top.
      boolean focused = this.searchField != null && this.searchField.isFocused();
      this.searchField = new TextFieldWidget(this.textRenderer, x, 26, listWidth - 164, 18, Text.literal("Search"));
      this.searchField.setMaxLength(60);
      this.searchField.setText(search);
      this.searchField.setPlaceholder(Text.literal("Search: name, server, 3K, replay..."));
      this.searchField.setChangedListener(text -> {
         if (!text.equals(search)) {
            search = text;
            this.page = 0;
            this.rebuild = true;
         }
      });
      this.addDrawableChild(this.searchField);
      if (focused) {
         this.setFocused(this.searchField);
      }

      this.addDrawableChild(FlatButton.of(Text.literal("Show: " + filter.label), b -> {
         filter = filter.next();
         this.page = 0;
         this.clearAndInit();
      }).dimensions(x + listWidth - 160, 25, 96, 20).setting().build());
      this.addDrawableChild(FlatButton.of(Text.literal("Storage"), b -> this.client.setScreen(new StorageScreen(this)))
         .dimensions(x + listWidth - 60, 25, 60, 20).build());
      this.rowBoxes.clear();
      ClipLibrary.Item picked = this.selectedItem();
      for (int i = 0; i < rows; i++) {
         int index = this.page * rows + i;
         if (index >= this.shown.size()) {
            break;
         }

         ClipLibrary.Item item = this.shown.get(index);
         int y = LIST_TOP + i * ROW;
         boolean isSelected = item.file().equals(this.selected);
         int rowWidth = isSelected ? listWidth - 112 : listWidth;
         FlatButton row = FlatButton.of(Text.empty(), b -> {
            this.selected = item.file();
            this.status = "";
            this.confirmDelete = false;
            this.clearAndInit();
         }).dimensions(x, y, rowWidth, ROW - 2).row().build();
         row.selected(isSelected);
         this.addDrawableChild(row);
         this.rowBoxes.add(new int[]{index, x, y, rowWidth});
         if (isSelected) {
            int bx = x + rowWidth + 2;
            this.addDrawableChild(FlatButton.of(Text.literal(item.favourite() ? "\u2605" : "\u2606"), b -> {
               this.library.setFavourite(item.file(), !item.favourite());
               this.rescan();
               this.clearAndInit();
            }).dimensions(bx, y, 22, ROW - 2).build()).setTooltip(Tooltip.of(Text.literal(item.favourite() ? "Unfavourite" : "Favourite: never deleted by the storage limit")));
            this.addDrawableChild(FlatButton.of(Text.literal("Rename"), b -> this.client.setScreen(new RenameScreen(this, item.file(), ClipLibrary.customTitle(item.file()))))
               .dimensions(bx + 24, y, 44, ROW - 2).build());
            this.addDrawableChild(FlatButton.of(Text.literal(this.confirmDelete ? "Sure?" : "Delete"), b -> this.delete(item))
               .dimensions(bx + 70, y, 40, ROW - 2).build());
         }
      }

      if (this.all.isEmpty()) {
         this.status = this.status.isEmpty() ? "No clips yet. Play a match, or import a video." : this.status;
      }

      int bottom = this.height - 52;
      Path replay = picked == null ? null : findReplay(picked.file());
      boolean video = picked != null && VideoTools.isVideo(picked.file());
      // One editor: a clip with its fight replay opens the full editor (trim and camera); a plain video opens trim.
      ButtonWidget edit = FlatButton.of(Text.literal(replay != null ? "Edit" : "Edit (trim only)"), b -> {
         if (replay != null) {
            this.openReplay(replay);
         } else {
            this.client.setScreen(new VideoTrimScreen(this, this.selected));
         }
      }).dimensions(x, bottom, 86, 20).primary().build();
      edit.active = replay != null || video;
      this.addDrawableChild(edit);
      this.addDrawableChild(FlatButton.of(Text.literal("Highlight reel"), b -> this.client.setScreen(new HighlightReelScreen(this))).dimensions(x + 90, bottom, 84, 20).build());
      ButtonWidget share = FlatButton.of(Text.literal("Share..."), b -> this.client.setScreen(new ShareScreen(this, this.selected)))
         .dimensions(x + 178, bottom, 70, 20).build();
      share.active = video;
      this.addDrawableChild(share);
      this.addDrawableChild(FlatButton.of(Text.literal("Import video..."), b -> this.importVideo()).dimensions(x + 252, bottom, listWidth - 252, 20).build());
      ButtonWidget previous = FlatButton.of(Text.literal("<"), b -> {
         this.page--;
         this.clearAndInit();
      }).dimensions(x, bottom + 24, 30, 20).build();
      previous.active = this.page > 0;
      this.addDrawableChild(previous);
      ButtonWidget next = FlatButton.of(Text.literal(">"), b -> {
         this.page++;
         this.clearAndInit();
      }).dimensions(x + 34, bottom + 24, 30, 20).build();
      next.active = this.page < pages - 1;
      this.addDrawableChild(next);
      this.addDrawableChild(FlatButton.of(Text.literal("Stats"), b -> this.client.setScreen(new StatsScreen(this))).dimensions(x + 68, bottom + 24, 60, 20).build());
      this.addDrawableChild(FlatButton.of(Text.literal("Open folder"), b -> KillClipperClient.instance().openClipsFolder()).dimensions(x + 132, bottom + 24, 90, 20).build());
      this.addDrawableChild(FlatButton.of(Text.literal("Done"), b -> this.close()).dimensions(x + 226, bottom + 24, listWidth - 226, 20).build());
   }

   @Override
   public void tick() {
      super.tick();
      if (this.rebuild) {
         this.rebuild = false;
         this.clearAndInit();
      }
   }

   private void delete(ClipLibrary.Item item) {
      if (!this.confirmDelete) {
         this.confirmDelete = true;
         this.clearAndInit();
         return;
      }

      this.confirmDelete = false;
      ReplayPlayer open = ReplayPlayer.active();
      Path replay = findReplay(item.file());
      if (open != null && replay != null && open.file().toAbsolutePath().normalize().equals(replay.toAbsolutePath().normalize())) {
         this.status = "Close the replay of this clip first.";
         this.clearAndInit();
         return;
      }

      this.thumbnails.forget(item.file());
      this.status = this.library.delete(item.file()) ? "Deleted " + item.title() + "." : "Could not delete " + item.file().getFileName() + ".";
      this.selected = null;
      this.rescan();
      this.clearAndInit();
   }

   /** Called by the rename screen. */
   void renamed(Path from, String title) {
      try {
         this.thumbnails.forget(from);
         this.selected = this.library.rename(from, title);
         this.status = "Renamed to " + ClipLibrary.title(this.selected) + ".";
         this.revealSelected = true;
      } catch (IOException e) {
         this.status = "Rename failed: " + e.getMessage();
      }

      this.rescan();
   }

   /** Called by the storage screen after it deleted clips or changed the limits. */
   void storageChanged(String message) {
      this.status = message;
      this.rescan();
   }

   /** The fight replay saved with a video, or null. */
   static Path findReplay(Path clip) {
      if (clip.getFileName().toString().endsWith(".kcreplay")) {
         return clip;
      }

      Path replay = clip.resolveSibling(ClipLibrary.baseName(clip) + ".kcreplay");
      return Files.isRegularFile(replay) ? replay : null;
   }

   /** The kill clip's video saved with a replay, or null. */
   static Path findVideoFor(Path replay) {
      return findVideo(replay);
   }

   private static Path findVideo(Path replay) {
      for (String type : VideoTools.VIDEO_TYPES) {
         Path video = replay.resolveSibling(ClipLibrary.baseName(replay) + "." + type);
         if (Files.isRegularFile(video)) {
            return video;
         }
      }

      return null;
   }

   private void openReplay(Path replay) {
      if (replay == null) {
         return;
      }

      if (ExportJob.active != null) {
         // An export left over from an earlier edit must not hold the game's clock while the replay loads.
         ExportJob.active = null;
      }

      this.status = "Opening the replay...";
      ReplayPlayer.startAsync(this.client, replay, started -> started.openEditorOnLoad = true, error -> {
         this.status = "Could not open the replay: " + error;
         KillClipperClient.LOGGER.warn("Could not open replay {}: {}", replay, error);
      });
   }

   /** Copies a video from anywhere on the computer into the clips folder. */
   private void importVideo() {
      String chosen;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         List<String> types = new ArrayList<>(VideoTools.VIDEO_TYPES);
         PointerBuffer filters = stack.mallocPointer(types.size());
         for (String type : types) {
            filters.put(stack.UTF8("*." + type));
         }

         filters.flip();
         chosen = TinyFileDialogs.tinyfd_openFileDialog("Import a video", null, filters, "Videos (mp4, mov, mkv, ...)", false);
      }

      if (chosen == null) {
         return;
      }

      try {
         Path source = Path.of(chosen);
         Files.createDirectories(this.dir());
         Path target = this.dir().resolve(source.getFileName().toString());
         if (!source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
         }

         this.selected = target;
         this.status = "Imported " + target.getFileName();
         this.revealSelected = true;
         this.rescan();
      } catch (IOException | RuntimeException e) {
         this.status = "Import failed: " + e.getMessage();
      }

      this.clearAndInit();
   }

   @Override
   protected void drawPanels(DrawContext context, float alpha) {
      int listWidth = this.listWidth();
      int x = this.width / 2 - listWidth / 2;
      Ui.panel(context, x - 6, 20, x + listWidth + 6, this.height - 4, alpha);
      // A hairline between the list and the actions.
      context.fill(x, this.height - 57, x + listWidth, this.height - 56, Ui.fade(Ui.LINE, alpha));
   }

   @Override
   protected void drawContent(DrawContext context, int mouseX, int mouseY, float deltaTicks, float alpha) {
      Ui.title(context, this.textRenderer, this.title, this.width / 2, 7, alpha);
      for (int[] box : this.rowBoxes) {
         this.drawRow(context, this.shown.get(box[0]), box[1], box[2], box[3], alpha);
      }

      int rows = this.rows();
      int pages = Math.max(1, (this.shown.size() + rows - 1) / rows);
      int listWidth = this.listWidth();
      int x = this.width / 2 - listWidth / 2;
      String count = this.shown.size() == this.all.size() ? this.all.size() + " clips" : this.shown.size() + " of " + this.all.size() + " clips";
      if (pages > 1) {
         count += "  \u00b7  page " + (this.page + 1) + "/" + pages;
      }

      String status = this.status;
      if (status.isEmpty() && this.shown.isEmpty() && !this.all.isEmpty()) {
         status = "No clips match. Change the search or press Show.";
      }

      if (!status.isEmpty()) {
         Ui.centered(context, this.textRenderer, FlatButton.trim(this.textRenderer, status, listWidth), this.width / 2, this.height - 68, Ui.fade(Ui.statusColor(status), alpha));
      } else {
         Ui.right(context, this.textRenderer, count, x + listWidth, this.height - 68, Ui.fade(Ui.FAINT, alpha));
      }
   }

   private void drawRow(DrawContext context, ClipLibrary.Item item, int x, int y, int width, float alpha) {
      int tx = x + 4;
      int ty = y + 1;
      Identifier thumb = this.thumbnails.get(item.file());
      if (thumb != null) {
         context.drawTexturedQuad(thumb, tx, ty, tx + THUMB_W, ty + THUMB_H, 0.0F, 1.0F, 0.0F, 1.0F);
      } else {
         context.fill(tx, ty, tx + THUMB_W, ty + THUMB_H, Ui.fade(0xFF20232A, alpha));
         Ui.centered(context, this.textRenderer, item.replay() && !VideoTools.isVideo(item.file()) ? "replay" : "...", tx + THUMB_W / 2, ty + 7,
            Ui.fade(Ui.FAINT, alpha));
      }

      Ui.outline(context, tx - 1, ty - 1, tx + THUMB_W + 1, ty + THUMB_H + 1, Ui.fade(Ui.LINE, alpha));
      if (item.favourite()) {
         context.fill(tx, ty, tx + 10, ty + 10, Ui.fade(0xB0000000, alpha));
         context.drawText(this.textRenderer, "\u2605", tx + 2, ty + 1, Ui.fade(0xFFFFD43B, alpha), false);
      }

      int textX = tx + THUMB_W + 6;
      int right = x + width - 6;
      String title = FlatButton.trim(this.textRenderer, item.title(), right - textX);
      context.drawText(this.textRenderer, title, textX, y + 3, Ui.fade(Ui.TEXT, alpha), false);
      // Second line: size and date, then as many tags as fit.
      String when = java.time.Instant.ofEpochMilli(item.modified()).atZone(java.time.ZoneId.systemDefault())
         .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm", java.util.Locale.ROOT));
      String info = ClipStorage.size(item.bytes()) + "  \u00b7  " + when;
      info = FlatButton.trim(this.textRenderer, info, right - textX);
      context.drawText(this.textRenderer, info, textX, y + 14, Ui.fade(Ui.MUTED, alpha), false);
      int tagX = textX + this.textRenderer.getWidth(info) + 6;
      for (String tag : item.tags()) {
         int w = this.textRenderer.getWidth(tag) + 4;
         if (tagX + w > right) {
            break;
         }

         boolean streak = tag.endsWith("K") && tag.length() <= 3;
         context.fill(tagX, y + 13, tagX + w, y + 22, Ui.fade(streak ? 0x55FF4655 : 0x2EFFFFFF, alpha));
         context.drawText(this.textRenderer, tag, tagX + 2, y + 14, Ui.fade(streak ? 0xFFFF9AA2 : 0xFFB4B9C0, alpha), false);
         tagX += w + 3;
      }
   }

   @Override
   public void removed() {
      this.thumbnails.close();
      super.removed();
   }

   @Override
   public void close() {
      if (ReplayPlayer.playing() && this.client.world == null) {
         // Leaving the list while a replay is still opening cancels it.
         ReplayPlayer.stop();
      }

      this.client.setScreen(this.parent);
   }
}
