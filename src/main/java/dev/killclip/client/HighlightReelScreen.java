package dev.killclip.client;

import dev.killclip.core.HighlightReel;
import dev.killclip.core.ReelMusic;
import dev.killclip.core.VideoTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** Makes a montage of a session's best kills: picks the clips, then joins them with transitions and music. */
@Environment(EnvType.CLIENT)
public final class HighlightReelScreen extends Screen {
   private static final int[] SIZES = {8, 10, 15, 20, 3, 5};
   private static final double[] GAME_VOLUMES = {0.6, 0.3, 0.0, 1.0};
   private static final List<String> MUSIC_TYPES = List.of("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus");
   private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

   private enum Music {
      BEAT("Built-in beat"), OWN("Your own file"), NONE("None");

      final String label;

      Music(String label) {
         this.label = label;
      }
   }

   // The choices and a running job outlive the screen, so leaving and coming back shows the same thing.
   private static HighlightReel.Range range = HighlightReel.Range.LATEST;
   private static int size;
   private static HighlightReel.Transition transition = HighlightReel.Transition.MIX;
   private static Music music = Music.BEAT;
   private static int gameVolume;
   private static volatile boolean busy;
   private static volatile double progress;
   private static volatile String status = "";

   private final Screen parent;
   private List<HighlightReel.Clip> picked = List.of();
   private int available;

   public HighlightReelScreen(Screen parent) {
      super(Text.literal("Highlight reel"));
      this.parent = parent;
   }

   static boolean busy() {
      return busy;
   }

   static String status() {
      return status;
   }

   private void choose() {
      try {
         List<HighlightReel.Clip> inRange = HighlightReel.inRange(HighlightReel.scan(KillClipperClient.instance().settings().clipsDir()), range, System.currentTimeMillis());
         this.available = inRange.size();
         this.picked = HighlightReel.pick(inRange, SIZES[size]);
      } catch (Exception e) {
         this.picked = List.of();
         this.available = 0;
         status = "Could not read the clips folder: " + e.getMessage();
      }
   }

   private static Path musicFile() {
      String file = KillClipperClient.instance().settings().reelMusicFile;
      return file.isBlank() ? null : Path.of(file);
   }

   @Override
   protected void init() {
      this.choose();
      int x = this.width / 2 - 152;
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Clips: " + range.label), b -> {
         range = HighlightReel.Range.values()[(range.ordinal() + 1) % HighlightReel.Range.values().length];
         this.clearAndInit();
      }).dimensions(x, 26, 150, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Use the best: " + SIZES[size] + " clips"), b -> {
         size = (size + 1) % SIZES.length;
         this.clearAndInit();
      }).dimensions(x + 154, 26, 150, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Transitions: " + transition.label), b -> {
         transition = HighlightReel.Transition.values()[(transition.ordinal() + 1) % HighlightReel.Transition.values().length];
         this.clearAndInit();
      }).dimensions(x, 48, 150, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Music: " + music.label), b -> {
         music = Music.values()[(music.ordinal() + 1) % Music.values().length];
         this.clearAndInit();
      }).dimensions(x + 154, 48, 150, 20).build());
      int percent = (int)Math.round(GAME_VOLUMES[gameVolume] * 100);
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Game sound: " + (percent == 0 ? "Off" : percent + "%")), b -> {
         gameVolume = (gameVolume + 1) % GAME_VOLUMES.length;
         this.clearAndInit();
      }).dimensions(x, 70, 150, 20).build());
      Path own = musicFile();
      String ownLabel = own == null ? "Choose music file..." : this.textRenderer.trimToWidth("File: " + own.getFileName(), 140);
      ButtonWidget pickMusic = ButtonWidget.builder(Text.literal(ownLabel), b -> this.chooseMusic()).dimensions(x + 154, 70, 150, 20).build();
      pickMusic.active = music == Music.OWN;
      this.addDrawableChild(pickMusic);

      int bottom = this.height - 26;
      ButtonWidget make = ButtonWidget.builder(Text.literal("Make highlight reel"), b -> this.make()).dimensions(x, bottom, 130, 20).build();
      make.active = !busy && !this.picked.isEmpty();
      this.addDrawableChild(make);
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Open Edited folder"), b -> KillClipperClient.instance().openEditedFolder()).dimensions(x + 134, bottom, 106, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> this.close()).dimensions(x + 244, bottom, 60, 20).build());
   }

   private void chooseMusic() {
      String chosen;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer filters = stack.mallocPointer(MUSIC_TYPES.size());
         for (String type : MUSIC_TYPES) {
            filters.put(stack.UTF8("*." + type));
         }

         filters.flip();
         chosen = TinyFileDialogs.tinyfd_openFileDialog("Music for the highlight reel", null, filters, "Music (mp3, wav, ogg, ...)", false);
      }

      if (chosen != null) {
         ClipConfig config = KillClipperClient.instance().settings();
         config.reelMusicFile = chosen;
         String error = KillClipperClient.instance().saveSettings(config);
         status = error.isEmpty() ? "" : error;
      }

      this.clearAndInit();
   }

   private void make() {
      KillClipperClient mod = KillClipperClient.instance();
      String ffmpeg = mod.ffmpeg();
      if (busy || this.picked.isEmpty()) {
         return;
      }

      if (ffmpeg == null) {
         status = "The recorder is still starting. Try again in a moment.";
         return;
      }

      Path own = musicFile();
      if (music == Music.OWN && (own == null || !Files.isRegularFile(own))) {
         status = "Choose a music file first.";
         return;
      }

      ClipConfig config = mod.settings();
      List<HighlightReel.Clip> clips = List.copyOf(this.picked);
      HighlightReel.Transition how = transition;
      Music track = music;
      double game = GAME_VOLUMES[gameVolume];
      Path output = config.editedDir().resolve(LocalDateTime.now().format(FILE_TIME) + "_highlight-reel.mp4");
      busy = true;
      progress = 0.0;
      status = "Reading " + clips.size() + " clips...";
      this.clearAndInit();
      CompletableFuture.runAsync(() -> {
         String result;
         try {
            List<HighlightReel.Segment> segments = new ArrayList<>();
            int width = 0;
            int height = 0;
            for (HighlightReel.Clip clip : clips) {
               HighlightReel.Info info = HighlightReel.probe(ffmpeg, clip.file());
               if (info == null || info.seconds() < 1.0) {
                  KillClipperClient.LOGGER.warn("Highlight reel: skipping unreadable clip {}", clip.file().getFileName());
                  continue;
               }

               segments.add(HighlightReel.segment(clip, info, config.preRollMs / 1000.0, config.postRollMs / 1000.0, how.seconds));
               // The reel is as big as the biggest clip; smaller ones are scaled up to match.
               if (info.height() > height) {
                  width = info.width() / 2 * 2;
                  height = info.height() / 2 * 2;
               }
            }

            if (segments.isEmpty()) {
               throw new java.io.IOException("none of the clips could be read");
            }

            Path song = switch (track) {
               case BEAT -> ReelMusic.file(config.clipsDir().resolve(".ffmpeg"));
               case OWN -> own;
               case NONE -> null;
            };
            double total = HighlightReel.length(segments, how);
            status = "Making the reel...";
            long started = System.nanoTime();
            HighlightReel.render(HighlightReel.command(ffmpeg, segments, how, song, 0.8, game, width, height, Math.max(30, Math.min(120, config.fps)), output),
               total, output, p -> progress = p);
            KillClipperClient.LOGGER.info("Highlight reel {}: {} clips, {} s, made in {} s", output.getFileName(), segments.size(),
               String.format(Locale.ROOT, "%.1f", total), (System.nanoTime() - started) / 1_000_000_000L);
            result = "Saved Edited/" + output.getFileName() + " (" + time(total) + ")";
            if (config.openAfterExport) {
               KillClipperClient.openVideo(output);
            }
         } catch (Exception e) {
            KillClipperClient.LOGGER.warn("Highlight reel failed", e);
            result = "Could not make the reel: " + e.getMessage();
         }

         String message = result;
         MinecraftClient.getInstance().execute(() -> {
            busy = false;
            status = message;
            if (MinecraftClient.getInstance().currentScreen instanceof HighlightReelScreen screen) {
               screen.clearAndInit();
            }
         });
      });
   }

   private static String time(double seconds) {
      int whole = (int)Math.round(seconds);
      return whole / 60 + ":" + String.format(Locale.ROOT, "%02d", whole % 60);
   }

   /** A guess before the clips are read: three bars for a kill, about five for a streak. */
   private double estimate() {
      double total = 0.0;
      for (HighlightReel.Clip clip : this.picked) {
         total += (clip.kills() > 1 ? 5 : 3) * ReelMusic.BAR_SECONDS;
      }

      return total;
   }

   @Override
   public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
      super.render(context, mouseX, mouseY, deltaTicks);
      context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFFFF);
      int x = this.width / 2 - 152;
      int y = 96;
      int barTop = this.height - 40;
      if (this.picked.isEmpty()) {
         String none = this.available == 0 && range != HighlightReel.Range.ALL ? "No kill clips in this range. Try \"All clips\"." : "No kill clips yet. Play a match first.";
         context.drawCenteredTextWithShadow(this.textRenderer, none, this.width / 2, y, 0xFFFFFF55);
      } else {
         int kills = this.picked.stream().mapToInt(HighlightReel.Clip::kills).sum();
         String summary = this.picked.size() + " of " + this.available + " clips, " + kills + " kills, about " + time(this.estimate());
         context.drawTextWithShadow(this.textRenderer, summary, x, y, 0xFF55FF55);
         y += 12;
         int rows = Math.max(0, (barTop - 14 - y) / 10);
         for (int i = 0; i < this.picked.size() && i < rows; i++) {
            HighlightReel.Clip clip = this.picked.get(i);
            boolean last = i == rows - 1 && this.picked.size() > rows;
            String line = last ? "... and " + (this.picked.size() - i) + " more" : (i + 1) + ". " + clip.file().getFileName() + (clip.kills() > 1 ? "  (" + clip.kills() + " kills)" : "");
            context.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(line, 304), x, y + i * 10, clip.kills() > 1 && !last ? 0xFFFFAA00 : 0xFFAAAAAA);
         }
      }

      if (busy) {
         context.fill(x, barTop, x + 304, barTop + 6, 0xFF333333);
         context.fill(x, barTop, x + (int)(304 * Math.max(0.0, Math.min(1.0, progress))), barTop + 6, 0xFF55FF55);
      }

      String line = busy && progress > 0.0 ? status + " " + (int)(progress * 100) + "%" : status;
      if (!line.isEmpty()) {
         context.drawCenteredTextWithShadow(this.textRenderer, line, this.width / 2, barTop - 12, 0xFFFFFF55);
      }
   }

   @Override
   public void close() {
      this.client.setScreen(this.parent);
   }
}
