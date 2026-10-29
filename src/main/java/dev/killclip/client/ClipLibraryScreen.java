package dev.killclip.client;

import dev.killclip.core.VideoTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** The clip editor's main-menu library: every clip in the clips folder, with editing for each. */
@Environment(EnvType.CLIENT)
public final class ClipLibraryScreen extends Screen {
   private final Screen parent;
   private final List<Path> clips = new ArrayList<>();
   private int page;
   private Path selected;
   private String status = "";

   public ClipLibraryScreen(Screen parent) {
      super(Text.literal("Kill Clipper editor"));
      this.parent = parent;
   }

   private Path dir() {
      return KillClipperClient.instance().settings().clipsDir();
   }

   private void scan() {
      this.clips.clear();
      Path dir = this.dir();
      if (!Files.isDirectory(dir)) {
         return;
      }

      try (Stream<Path> files = Files.list(dir)) {
         files.filter(Files::isRegularFile)
            .filter(f -> VideoTools.isVideo(f) || f.getFileName().toString().endsWith(".kcreplay") && findVideo(f) == null)
            .sorted(Comparator.comparingLong(ClipLibraryScreen::modified).reversed())
            .forEach(this.clips::add);
      } catch (IOException e) {
         this.status = "Could not read the clips folder: " + e.getMessage();
      }
   }

   private static long modified(Path file) {
      try {
         return Files.getLastModifiedTime(file).toMillis();
      } catch (IOException e) {
         return 0L;
      }
   }

   private static String baseName(Path file) {
      String name = file.getFileName().toString();
      int dot = name.lastIndexOf('.');
      return dot > 0 ? name.substring(0, dot) : name;
   }

   /** The fight replay saved with a video, or null. */
   static Path findReplay(Path clip) {
      if (clip.getFileName().toString().endsWith(".kcreplay")) {
         return clip;
      }

      Path replay = clip.resolveSibling(baseName(clip) + ".kcreplay");
      return Files.isRegularFile(replay) ? replay : null;
   }

   private static Path findVideo(Path replay) {
      for (String type : VideoTools.VIDEO_TYPES) {
         Path video = replay.resolveSibling(baseName(replay) + "." + type);
         if (Files.isRegularFile(video)) {
            return video;
         }
      }

      return null;
   }

   @Override
   protected void init() {
      this.scan();
      // As many rows as fit above the buttons and the status line.
      int rows = Math.max(1, (this.height - 52 - 16 - 32) / 22);
      int pages = Math.max(1, (this.clips.size() + rows - 1) / rows);
      this.page = Math.min(this.page, pages - 1);
      int listWidth = Math.min(340, this.width - 20);
      int x = this.width / 2 - listWidth / 2;
      int y = 32;
      for (int i = 0; i < rows; i++) {
         int index = this.page * rows + i;
         if (index >= this.clips.size()) {
            break;
         }

         Path clip = this.clips.get(index);
         String label = clip.getFileName().toString() + (findReplay(clip) != null ? "  [camera views]" : "");
         ButtonWidget row = ButtonWidget.builder(Text.literal(this.textRenderer.trimToWidth((clip.equals(this.selected) ? "> " : "") + label, listWidth - 10)), b -> {
            this.selected = clip;
            this.status = "";
            this.clearAndInit();
         }).dimensions(x, y + i * 22, listWidth, 20).build();
         this.addDrawableChild(row);
      }

      if (this.clips.isEmpty()) {
         this.status = "No clips yet. Play a match, or import a video.";
      }

      int bottom = this.height - 52;
      Path replay = this.selected == null ? null : findReplay(this.selected);
      ButtonWidget edit = ButtonWidget.builder(Text.literal("Edit fight (cameras)"), b -> this.openReplay(replay)).dimensions(x, bottom, 120, 20).build();
      edit.active = replay != null;
      this.addDrawableChild(edit);
      boolean video = this.selected != null && VideoTools.isVideo(this.selected);
      ButtonWidget trim = ButtonWidget.builder(Text.literal("Trim / convert"), b -> this.client.setScreen(new VideoTrimScreen(this, this.selected)))
         .dimensions(x + 124, bottom, 100, 20).build();
      trim.active = video;
      this.addDrawableChild(trim);
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Import video..."), b -> this.importVideo()).dimensions(x + 228, bottom, listWidth - 228, 20).build());
      ButtonWidget previous = ButtonWidget.builder(Text.literal("<"), b -> {
         this.page--;
         this.clearAndInit();
      }).dimensions(x, bottom + 24, 30, 20).build();
      previous.active = this.page > 0;
      this.addDrawableChild(previous);
      ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> {
         this.page++;
         this.clearAndInit();
      }).dimensions(x + 34, bottom + 24, 30, 20).build();
      next.active = this.page < pages - 1;
      this.addDrawableChild(next);
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Open folder"), b -> KillClipperClient.instance().openClipsFolder()).dimensions(x + 68, bottom + 24, 100, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close()).dimensions(x + 172, bottom + 24, listWidth - 172, 20).build());
   }

   private void openReplay(Path replay) {
      if (replay == null) {
         return;
      }

      try {
         ReplayPlayer.start(this.client, replay);
      } catch (IOException | RuntimeException e) {
         this.status = "Could not open the replay: " + e.getMessage();
         KillClipperClient.LOGGER.warn("Could not open replay {}", replay, e);
      }
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
      } catch (IOException | RuntimeException e) {
         this.status = "Import failed: " + e.getMessage();
      }

      this.clearAndInit();
   }

   @Override
   public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
      super.render(context, mouseX, mouseY, deltaTicks);
      context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFFFF);
      if (!this.status.isEmpty()) {
         context.drawCenteredTextWithShadow(this.textRenderer, this.status, this.width / 2, this.height - 66, 0xFFFFFF55);
      }
   }

   @Override
   public void close() {
      this.client.setScreen(this.parent);
   }
}
