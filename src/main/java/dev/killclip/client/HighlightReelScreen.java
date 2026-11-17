package dev.killclip.client;

import dev.killclip.core.BeatDetector;
import dev.killclip.core.ClipMeta;
import dev.killclip.core.HighlightReel;
import dev.killclip.core.ReelMusic;
import dev.killclip.core.ReelText;
import dev.killclip.core.VideoTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * Makes a montage of a session's best kills. Everything is picked automatically (the clips, their order, the
 * length, the cuts on the beat, popups, slow motion and instant replays), so one click on Make is enough; the list
 * shows the picks so they can be removed, moved or swapped first.
 */
@Environment(EnvType.CLIENT)
public final class HighlightReelScreen extends FlatScreen {
   /** 0 is automatic. */
   private static final int[] SIZES = {0, 5, 8, 10, 15, 20, 3};
   private static final double[] GAME_VOLUMES = {0.6, 0.3, 0.0, 1.0};
   private static final List<String> MUSIC_TYPES = List.of("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus");
   private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
   private static final DateTimeFormatter ROW_TIME = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH);
   private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
   /** Instant replays are filmed for this many of the best clips. */
   private static final int MAX_REPLAYS = 2;
   private static final int ROW = 11;

   private enum Music {
      BEAT("Built-in beat"), OWN("Your own file"), NONE("None");

      final String label;

      Music(String label) {
         this.label = label;
      }
   }

   private enum Then {
      OPEN("Open it"), LINK("Get link");

      final String label;

      Then(String label) {
         this.label = label;
      }
   }

   // The choices, the picked clips and a running job outlive the screen, so leaving and coming back shows the same thing.
   private static HighlightReel.Range range = HighlightReel.Range.LATEST;
   private static int size;
   private static HighlightReel.Transition transition = HighlightReel.Transition.MIX;
   private static Music music = Music.BEAT;
   private static int gameVolume;
   private static boolean replays = true;
   private static boolean vertical;
   private static Then then = Then.OPEN;
   private static List<HighlightReel.Clip> plan;
   private static List<HighlightReel.Clip> candidates = List.of();
   private static int selected = -1;
   private static volatile boolean busy;
   private static volatile double progress;
   private static volatile String status = "";

   private final Screen parent;
   private int listTop;
   private int listRows;
   private int scroll;
   private ButtonWidget up;
   private ButtonWidget down;
   private ButtonWidget remove;
   private ButtonWidget swap;

   public HighlightReelScreen(Screen parent) {
      super(Text.literal("Highlight reel"));
      this.parent = parent;
   }

   static boolean busy() {
      return busy || ReelAngles.running();
   }

   static String status() {
      return status;
   }

   static void note(String text) {
      status = text;
   }

   /** Picks the clips again from scratch, throwing away moves and removals. */
   private static void choose() {
      try {
         candidates = HighlightReel.inRange(HighlightReel.scan(KillClipperClient.instance().settings().clipsDir()), range, System.currentTimeMillis());
         plan = new ArrayList<>(HighlightReel.pick(candidates, SIZES[size]));
      } catch (Exception e) {
         candidates = List.of();
         plan = new ArrayList<>();
         status = "Could not read the clips folder: " + e.getMessage();
      }

      selected = -1;
   }

   private static Path musicFile() {
      String file = KillClipperClient.instance().settings().reelMusicFile;
      return file.isBlank() ? null : Path.of(file);
   }

   @Override
   protected void init() {
      if (plan == null || plan.stream().anyMatch(c -> !Files.isRegularFile(c.file()))) {
         choose();
      }

      int x = this.width / 2 - 152;
      int y = 22;
      this.addDrawableChild(FlatButton.of(Text.literal("Clips: " + range.label), b -> {
         range = HighlightReel.Range.values()[(range.ordinal() + 1) % HighlightReel.Range.values().length];
         choose();
         this.clearAndInit();
      }).dimensions(x, y, 150, 20).setting().build());
      this.addDrawableChild(FlatButton.of(Text.literal(SIZES[size] == 0 ? "How many: Auto" : "How many: best " + SIZES[size]), b -> {
         size = (size + 1) % SIZES.length;
         choose();
         this.clearAndInit();
      }).dimensions(x + 154, y, 150, 20).setting().build());
      this.addDrawableChild(FlatButton.of(Text.literal("Transitions: " + transition.label), b -> {
         transition = HighlightReel.Transition.values()[(transition.ordinal() + 1) % HighlightReel.Transition.values().length];
         this.clearAndInit();
      }).dimensions(x, y + 22, 150, 20).setting().build());
      this.addDrawableChild(FlatButton.of(Text.literal("Music: " + music.label), b -> {
         music = Music.values()[(music.ordinal() + 1) % Music.values().length];
         this.clearAndInit();
      }).dimensions(x + 154, y + 22, 150, 20).setting().build());
      int percent = (int)Math.round(GAME_VOLUMES[gameVolume] * 100);
      this.addDrawableChild(FlatButton.of(Text.literal("Game sound: " + (percent == 0 ? "Off" : percent + "%")), b -> {
         gameVolume = (gameVolume + 1) % GAME_VOLUMES.length;
         this.clearAndInit();
      }).dimensions(x, y + 44, 150, 20).setting().build());
      Path own = musicFile();
      String ownLabel = own == null ? "Choose music file..." : this.textRenderer.trimToWidth("File: " + own.getFileName(), 140);
      ButtonWidget pickMusic = FlatButton.of(Text.literal(ownLabel), b -> this.chooseMusic()).dimensions(x + 154, y + 44, 150, 20).build();
      pickMusic.active = music == Music.OWN;
      this.addDrawableChild(pickMusic);
      this.addDrawableChild(FlatButton.of(Text.literal("Replays: " + (replays ? "On" : "Off")), b -> {
         replays = !replays;
         this.clearAndInit();
      }).dimensions(x, y + 66, 72, 20).setting().build());
      this.addDrawableChild(FlatButton.of(Text.literal("Shape: " + (vertical ? "9:16" : "16:9")), b -> {
         vertical = !vertical;
         this.clearAndInit();
      }).dimensions(x + 74, y + 66, 72, 20).setting().build());
      boolean text = KillClipperClient.instance().settings().reelText;
      this.addDrawableChild(FlatButton.of(Text.literal("Text: " + (text ? "On" : "Off")), b -> {
         ClipConfig config = KillClipperClient.instance().settings();
         config.reelText = !config.reelText;
         String error = KillClipperClient.instance().saveSettings(config);
         status = error.isEmpty() ? "" : error;
         this.clearAndInit();
      }).dimensions(x + 148, y + 66, 64, 20).setting().build());
      this.addDrawableChild(FlatButton.of(Text.literal("Then: " + then.label), b -> {
         then = Then.values()[(then.ordinal() + 1) % Then.values().length];
         this.clearAndInit();
      }).dimensions(x + 214, y + 66, 90, 20).setting().build());

      // The picked clips: click one to select it, double-click to watch it.
      this.listTop = y + 102;
      int barTop = this.height - 40;
      this.listRows = Math.max(1, (barTop - 14 - this.listTop) / ROW);
      int side = x + 238;
      this.up = this.addDrawableChild(FlatButton.of(Text.literal("Move up"), b -> this.move(-1)).dimensions(side, this.listTop - 2, 66, 18).build());
      this.down = this.addDrawableChild(FlatButton.of(Text.literal("Move down"), b -> this.move(1)).dimensions(side, this.listTop + 18, 66, 18).build());
      this.remove = this.addDrawableChild(FlatButton.of(Text.literal("Remove"), b -> this.removeSelected()).dimensions(side, this.listTop + 38, 66, 18).build());
      this.swap = this.addDrawableChild(FlatButton.of(Text.literal("Swap"), b -> this.swapSelected()).dimensions(side, this.listTop + 58, 66, 18).build());
      this.updateRowButtons();

      int bottom = this.height - 26;
      ButtonWidget make = FlatButton.of(Text.literal("Make highlight reel"), b -> this.make()).dimensions(x, bottom, 116, 20).primary().build();
      make.active = !busy() && !plan.isEmpty();
      this.addDrawableChild(make);
      ButtonWidget reset = FlatButton.of(Text.literal("Auto pick"), b -> {
         choose();
         this.clearAndInit();
      }).dimensions(x + 120, bottom, 60, 20).build();
      reset.active = !busy();
      this.addDrawableChild(reset);
      this.addDrawableChild(FlatButton.of(Text.literal("Edited folder"), b -> KillClipperClient.instance().openEditedFolder()).dimensions(x + 184, bottom, 80, 20).build());
      this.addDrawableChild(FlatButton.of(Text.literal("Back"), b -> this.close()).dimensions(x + 268, bottom, 36, 20).build());
   }

   private void updateRowButtons() {
      boolean has = selected >= 0 && selected < plan.size() && !busy();
      this.up.active = has && selected > 0;
      this.down.active = has && selected < plan.size() - 1;
      this.remove.active = has;
      this.swap.active = has && this.spare() != null;
   }

   private void move(int by) {
      int to = selected + by;
      if (selected < 0 || to < 0 || to >= plan.size()) {
         return;
      }

      plan.add(to, plan.remove(selected));
      selected = to;
      this.keepVisible();
      this.clearAndInit();
   }

   private void removeSelected() {
      if (selected >= 0 && selected < plan.size()) {
         plan.remove(selected);
         selected = Math.min(selected, plan.size() - 1);
         this.clearAndInit();
      }
   }

   /** The best clip not in the reel (and not the same fight as one in it), to swap in. */
   private HighlightReel.Clip spare() {
      HighlightReel.Clip current = selected >= 0 && selected < plan.size() ? plan.get(selected) : null;
      return candidates.stream()
         .filter(c -> !plan.contains(c))
         .filter(c -> plan.stream().noneMatch(p -> p != current && HighlightReel.sameFight(p, c)))
         .max(Comparator.comparingDouble(HighlightReel::score).thenComparingLong(HighlightReel.Clip::timeMs))
         .orElse(null);
   }

   private void swapSelected() {
      HighlightReel.Clip spare = this.spare();
      if (spare != null && selected >= 0 && selected < plan.size()) {
         plan.set(selected, spare);
         this.clearAndInit();
      }
   }

   private void keepVisible() {
      if (selected < this.scroll) {
         this.scroll = selected;
      } else if (selected >= this.scroll + this.listRows) {
         this.scroll = selected - this.listRows + 1;
      }
   }

   @Override
   public boolean mouseClicked(Click click, boolean doubled) {
      int x = this.width / 2 - 152;
      if (click.button() == 0 && click.x() >= x && click.x() < x + 234 && click.y() >= this.listTop && click.y() < this.listTop + this.listRows * ROW) {
         int row = this.scroll + (int)((click.y() - this.listTop) / ROW);
         if (row < plan.size()) {
            if (doubled && row == selected) {
               KillClipperClient.openVideo(plan.get(row).file());
            }

            selected = row;
            this.updateRowButtons();
            return true;
         }
      }

      return super.mouseClicked(click, doubled);
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
      int max = Math.max(0, plan.size() - this.listRows);
      this.scroll = Math.max(0, Math.min(max, this.scroll - (int)Math.signum(vertical)));
      return true;
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

   /** Everything the reel needs, fixed when Make is pressed. */
   private record Job(String ffmpeg, List<HighlightReel.Clip> clips, HighlightReel.Transition transition, Music music, Path own, double game,
      boolean replays, boolean vertical, Then then, ClipConfig config, Path output, Path work) {
   }

   private void make() {
      KillClipperClient mod = KillClipperClient.instance();
      String ffmpeg = mod.ffmpeg();
      if (busy() || plan.isEmpty()) {
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
      String stamp = LocalDateTime.now().format(FILE_TIME);
      Path output = config.editedDir().resolve(stamp + "_highlight-reel" + (vertical ? "_vertical" : "") + ".mp4");
      // Instant replays need the game to itself: they are filmed from the main menu only.
      boolean films = replays && this.client.world == null && !ReplayPlayer.playing();
      Job job = new Job(ffmpeg, List.copyOf(plan), transition, music, own, GAME_VOLUMES[gameVolume], films, vertical, then, config, output,
         config.clipsDir().resolve(".reel"));
      busy = true;
      progress = 0.0;
      status = "Reading " + job.clips().size() + " clips...";
      this.clearAndInit();
      CompletableFuture.runAsync(() -> prepare(job)).exceptionally(e -> {
         finish("Could not make the reel: " + e.getMessage());
         return null;
      });
   }

   /** Reads the clips and the beat, then films the instant replays (if any) and renders. Runs off the game thread. */
   private static void prepare(Job job) {
      try {
         deleteFolder(job.work());
         Files.createDirectories(job.work());
         BeatDetector.Grid grid = BeatDetector.builtIn();
         Path song = switch (job.music()) {
            case BEAT -> ReelMusic.file(job.config().clipsDir().resolve(".ffmpeg"));
            case OWN -> job.own();
            case NONE -> null;
         };
         if (job.music() == Music.OWN) {
            status = "Finding the beat of your music...";
            BeatDetector.Grid found = BeatDetector.detect(job.ffmpeg(), song);
            if (found != null) {
               grid = found;
               KillClipperClient.LOGGER.info("Highlight reel: {} is {} BPM, first bar at {} s, starting at {} s", song.getFileName(),
                  String.format(Locale.ROOT, "%.1f", found.bpm()), String.format(Locale.ROOT, "%.2f", found.downbeat()), String.format(Locale.ROOT, "%.2f", found.start()));
            } else {
               KillClipperClient.LOGGER.info("Highlight reel: no steady beat found in {}; cutting at 128 BPM", song.getFileName());
            }
         }

         status = "Reading " + job.clips().size() + " clips...";
         Map<HighlightReel.Clip, HighlightReel.Info> infos = new HashMap<>();
         for (HighlightReel.Clip clip : job.clips()) {
            HighlightReel.Info info = HighlightReel.probe(job.ffmpeg(), clip.file());
            if (info == null || info.seconds() < 1.0) {
               KillClipperClient.LOGGER.warn("Highlight reel: skipping unreadable clip {}", clip.file().getFileName());
               continue;
            }

            infos.put(clip, info);
         }

         if (infos.isEmpty()) {
            throw new IOException("none of the clips could be read");
         }

         double t = HighlightReel.transitionSeconds(job.transition(), grid);
         List<ReelAngles.Shot> shots = job.replays() ? shots(job, infos, grid, t) : List.of();
         BeatDetector.Grid beat = grid;
         Path music = song;
         if (shots.isEmpty()) {
            render(job, infos, beat, music, t, Map.of());
         } else {
            status = "Filming instant replays...";
            MinecraftClient.getInstance().execute(() -> ReelAngles.start(shots, filmed -> CompletableFuture.runAsync(() -> {
               try {
                  render(job, infos, beat, music, t, filmed);
               } catch (Exception e) {
                  fail(e);
               }
            })));
         }
      } catch (Exception e) {
         fail(e);
      }
   }

   /** Instant replays for the best clips that have a fight replay. */
   private static List<ReelAngles.Shot> shots(Job job, Map<HighlightReel.Clip, HighlightReel.Info> infos, BeatDetector.Grid grid, double t) {
      List<HighlightReel.Clip> best = new ArrayList<>(infos.keySet());
      best.sort(Comparator.comparingDouble(HighlightReel::score).reversed());
      List<ReelAngles.Shot> shots = new ArrayList<>();
      // Enough game time that, played at half speed, it fills two units of the reel plus the crossfades.
      double window = (2.0 * HighlightReel.unit(grid) + t) * ReelAngles.SPEED + 0.25;
      for (HighlightReel.Clip clip : best) {
         Path replay = ClipLibraryScreen.findReplay(clip.file());
         if (replay == null || !Files.isRegularFile(replay)) {
            continue;
         }

         HighlightReel.Info info = infos.get(clip);
         double kill = clip.moments().isEmpty() ? Math.max(0.0, info.seconds() - job.config().postRollMs / 1000.0) : clip.moments().getLast().at();
         shots.add(new ReelAngles.Shot(clip.file(), replay, kill, window, job.work().resolve("replay-" + shots.size() + ".mp4")));
         if (shots.size() >= MAX_REPLAYS) {
            break;
         }
      }

      return shots;
   }

   private static void render(Job job, Map<HighlightReel.Clip, HighlightReel.Info> infos, BeatDetector.Grid grid, Path music, double t,
      Map<Path, Path> filmed) throws Exception {
      ClipConfig config = job.config();
      List<HighlightReel.Clip> clips = job.clips().stream().filter(infos::containsKey).toList();
      List<HighlightReel.Segment> segments = new ArrayList<>();
      int width = 0;
      int height = 0;
      int kills = 0;
      int bestStreak = 1;
      for (int i = 0; i < clips.size(); i++) {
         HighlightReel.Clip clip = clips.get(i);
         HighlightReel.Info info = infos.get(clip);
         segments.add(HighlightReel.segment(clip, info, config.preRollMs / 1000.0, config.postRollMs / 1000.0, grid, HighlightReel.role(i, clips.size()), t));
         kills += clip.kills();
         bestStreak = Math.max(bestStreak, clip.kills());
         Path replay = filmed.get(clip.file());
         HighlightReel.Info replayInfo = replay == null ? null : HighlightReel.probe(job.ffmpeg(), replay);
         if (replayInfo != null && replayInfo.seconds() > 0.5) {
            segments.add(HighlightReel.replaySegment(replay, replayInfo, grid, t));
         }

         // The reel is as big as the biggest clip; smaller ones are scaled up to match.
         if (info.height() > height) {
            width = info.width() / 2 * 2;
            height = info.height() / 2 * 2;
         }
      }

      if (job.vertical()) {
         int tall = height >= 1080 ? 1920 : height >= 720 ? 1280 : height;
         width = tall == height ? (int)Math.round(height * 9.0 / 16.0) / 2 * 2 : tall * 9 / 16 / 2 * 2;
         height = tall;
      }

      long newest = clips.stream().mapToLong(HighlightReel.Clip::timeMs).max().orElse(System.currentTimeMillis());
      String date = TITLE_DATE.format(Instant.ofEpochMilli(newest).atZone(ZoneId.systemDefault())).toUpperCase(Locale.ENGLISH);
      String killText = kills + (kills == 1 ? " KILL" : " KILLS");
      HighlightReel.Popup intro = new HighlightReel.Popup(0.0, "HIGHLIGHTS", date + " - " + killText, ReelText.Style.TITLE);
      HighlightReel.Popup outro = new HighlightReel.Popup(0.0, "GG", bestStreak > 1 ? "BEST: " + HighlightReel.killName(bestStreak) : killText, ReelText.Style.TITLE);
      status = "Making the reel...";
      // Popups and titles only when asked for; the default reel is just the clips and the music.
      List<HighlightReel.Overlay> overlays = config.reelText ? HighlightReel.overlays(segments, t, intro, outro, width, height, job.work().resolve("text")) : List.of();
      double total = HighlightReel.length(segments, t);
      double musicStart = music != null && job.music() == Music.OWN ? grid.start() : 0.0;
      HighlightReel.Style style = new HighlightReel.Style(job.transition(), t, music, musicStart, 0.8, job.game(), width, height,
         Math.max(30, Math.min(120, config.fps)), job.vertical(), overlays);
      long started = System.nanoTime();
      HighlightReel.render(HighlightReel.command(job.ffmpeg(), segments, style, job.output()), total, job.output(), p -> progress = p);
      KillClipperClient.LOGGER.info("Highlight reel {}: {} clips, {} instant replays, {} s at {} BPM, made in {} s", job.output().getFileName(), clips.size(),
         filmed.size(), String.format(Locale.ROOT, "%.1f", total), String.format(Locale.ROOT, "%.1f", grid.bpm()), (System.nanoTime() - started) / 1_000_000_000L);
      deleteFolder(job.work());
      String saved = "Saved Edited/" + job.output().getFileName() + " (" + time(total) + ")";
      if (job.then() == Then.LINK) {
         share(job, saved);
         return;
      }

      if (config.openAfterExport) {
         KillClipperClient.openVideo(job.output());
      }

      finish(saved);
   }

   /** Uploads the reel and copies its link, like Share does. */
   private static void share(Job job, String saved) {
      status = "Uploading the reel...";
      progress = 0.0;
      Path upload = job.output();
      Path small = job.output().resolveSibling(".share-reel.mp4");
      try {
         upload = VideoTools.fitSize(job.ffmpeg(), job.output(), ShareScreen.UPLOAD_LIMIT, small);
         String link = ShareScreen.post(upload, job.output().getFileName().toString());
         MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().keyboard.setClipboard(link));
         finish("Link copied (paste it with Ctrl+V): " + link);
      } catch (Exception e) {
         KillClipperClient.LOGGER.warn("Highlight reel upload failed", e);
         finish(saved + ". Upload failed: " + e.getMessage());
      } finally {
         try {
            Files.deleteIfExists(small);
         } catch (IOException ignored) {
         }
      }
   }

   private static void fail(Throwable e) {
      Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
      KillClipperClient.LOGGER.warn("Highlight reel failed", cause);
      finish("Could not make the reel: " + cause.getMessage());
   }

   private static void finish(String message) {
      MinecraftClient.getInstance().execute(() -> {
         busy = false;
         status = message;
         if (MinecraftClient.getInstance().currentScreen instanceof HighlightReelScreen screen) {
            screen.clearAndInit();
         }
      });
   }

   private static void deleteFolder(Path dir) throws IOException {
      if (!Files.isDirectory(dir)) {
         return;
      }

      try (Stream<Path> files = Files.walk(dir)) {
         for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(file);
         }
      }
   }

   private static String time(double seconds) {
      int whole = (int)Math.round(seconds);
      return whole / 60 + ":" + String.format(Locale.ROOT, "%02d", whole % 60);
   }

   /** A guess before the clips are read. */
   private static double estimate() {
      return plan.stream().mapToDouble(HighlightReel::estimate).sum();
   }

   /** One line of the list: when, what, and what makes it special. */
   private static String describe(HighlightReel.Clip clip, int index) {
      StringBuilder line = new StringBuilder();
      line.append(index + 1).append(". ").append(ROW_TIME.format(Instant.ofEpochMilli(clip.timeMs()).atZone(ZoneId.systemDefault()))).append("  ");
      line.append(clip.kills() == 1 ? "Kill" : HighlightReel.killName(clip.kills()).charAt(0) + HighlightReel.killName(clip.kills()).substring(1).toLowerCase(Locale.ENGLISH));
      List<String> tags = new ArrayList<>();
      for (ClipMeta.Kill kill : clip.moments()) {
         String tag = kill.health() > 0.0F && kill.health() <= 4.0F ? "clutch" : kill.distance() >= 15.0F ? "long shot"
            : kill.cause() == ClipMeta.Cause.EXPLOSION ? "crystal" : null;
         if (tag != null && !tags.contains(tag)) {
            tags.add(tag);
         }
      }

      if (index == 0 && plan.size() > 1) {
         tags.add(0, "opener");
      } else if (index == plan.size() - 1) {
         tags.add(0, "finale");
      }

      if (!tags.isEmpty()) {
         line.append("  (").append(String.join(", ", tags)).append(')');
      }

      return line.toString();
   }

   @Override
   protected void drawPanels(DrawContext context, float alpha) {
      int x = this.width / 2 - 152;
      Ui.panel(context, x - 8, 18, x + 312, this.height - 30, alpha);
   }

   @Override
   protected void drawContent(DrawContext context, int mouseX, int mouseY, float deltaTicks, float alpha) {
      Ui.title(context, this.textRenderer, this.title, this.width / 2, 5, alpha);
      int x = this.width / 2 - 152;
      int barTop = this.height - 40;
      int y = this.listTop - 13;
      context.fill(x, y - 4, x + 304, y - 3, Ui.fade(Ui.LINE, alpha));
      if (plan.isEmpty()) {
         String none = candidates.isEmpty() && range != HighlightReel.Range.ALL ? "No kill clips in this range. Try \"All clips\"." : "No kill clips yet. Play a match first.";
         Ui.text(context, this.textRenderer, none, x, y + 12, Ui.fade(Ui.MUTED, alpha));
      } else {
         int kills = plan.stream().mapToInt(HighlightReel.Clip::kills).sum();
         Ui.text(context, this.textRenderer, plan.size() + " of " + candidates.size() + " clips", x, y, Ui.fade(Ui.TEXT, alpha));
         Ui.right(context, this.textRenderer, kills + " kills  \u00b7  about " + time(estimate()), x + 234, y, Ui.fade(Ui.MUTED, alpha));
         this.scroll = Math.max(0, Math.min(this.scroll, plan.size() - this.listRows));
         for (int r = 0; r < this.listRows && this.scroll + r < plan.size(); r++) {
            int i = this.scroll + r;
            HighlightReel.Clip clip = plan.get(i);
            int rowY = this.listTop + r * ROW;
            if (i == selected) {
               context.fill(x - 2, rowY - 2, x + 234, rowY + ROW - 2, Ui.fade(Ui.RAISED_HOVER, alpha));
               context.fill(x - 2, rowY - 2, x - 1, rowY + ROW - 2, Ui.fade(Ui.ACCENT, alpha));
            } else if (mouseX >= x && mouseX < x + 234 && mouseY >= rowY - 2 && mouseY < rowY + ROW - 2) {
               context.fill(x - 2, rowY - 2, x + 234, rowY + ROW - 2, Ui.fade(Ui.RAISED, alpha));
            }

            int color = clip.kills() > 1 ? Ui.TEXT : 0xFFB4B9C0;
            Ui.text(context, this.textRenderer, FlatButton.trim(this.textRenderer, describe(clip, i), 232), x, rowY, Ui.fade(color, alpha));
         }

         if (plan.size() > this.listRows) {
            String more = (this.scroll + 1) + "-" + Math.min(plan.size(), this.scroll + this.listRows) + " of " + plan.size();
            Ui.text(context, this.textRenderer, more, x + 238, this.listTop + 80, Ui.fade(Ui.FAINT, alpha));
         }
      }

      boolean working = busy();
      if (working) {
         Ui.progress(context, x, barTop + 4, 304, 3, (float)progress, alpha);
      }

      String line = ReelAngles.running() ? ReelAngles.status() : working && progress > 0.0 ? status + " " + (int)(progress * 100) + "%" : status;
      if (line.isEmpty() && !plan.isEmpty()) {
         Ui.centered(context, this.textRenderer, FlatButton.trim(this.textRenderer, "One click on Make does the rest. Double-click a clip to watch it.", this.width - 20),
            this.width / 2, barTop - 6, Ui.fade(Ui.FAINT, alpha));
      } else if (!line.isEmpty()) {
         Ui.centered(context, this.textRenderer, FlatButton.trim(this.textRenderer, line, this.width - 20), this.width / 2, barTop - 6,
            Ui.fade(working ? Ui.TEXT : Ui.statusColor(line), alpha));
      }
   }

   @Override
   public void close() {
      this.client.setScreen(this.parent);
   }
}
