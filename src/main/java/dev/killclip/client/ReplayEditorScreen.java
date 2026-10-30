package dev.killclip.client;

import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import org.lwjgl.glfw.GLFW;

/**
 * The editor over a playing replay, laid out like a video editor: a clip track with trim handles, a camera track
 * split into parts (click one to change its POV, speed and zoom), and the controls. The game keeps playing behind it.
 */
@Environment(EnvType.CLIENT)
final class ReplayEditorScreen extends Screen {
   private static final List<String> FORMATS = List.of("mp4", "mov", "mkv");
   private static final float[] SPEEDS = {0.25F, 0.5F, 1.0F, 1.5F, 2.0F};
   private static final float[] ZOOMS = {1.0F, 1.5F, 2.0F, 3.0F, 4.0F};
   private static final int[] MODE_COLORS = {0xFF3E9E4F, 0xFF3A78C2, 0xFFC7821E};
   private static final int PANEL = 100;
   private static final int LABELS = 46;
   private static final String[][] KEYS = {
      {"P", "Play / pause"},
      {"Left / Right", "Jump 5 s back / forward"},
      {"Click clip track", "Jump to that time"},
      {"Drag yellow ends", "Trim the start and end"},
      {"I / O", "Trim start / end at the playhead"},
      {"Click camera part", "Pick it to change POV, speed, zoom"},
      {"V", "Change the POV of the part"},
      {"X", "Split: new camera part from here"},
      {"K", "Spectator keyframe at the camera's spot"},
      {"Delete", "Merge the part into the one before"},
      {"WASD Space Shift", "Fly the spectator camera (Ctrl faster)"},
      {"Drag on the video", "Turn the spectator camera"},
      {"E", "Hide / show the editor (full screen)"},
      {"C", "Move the keyframe here to the camera's spot"},
      {"H", "Show / hide these keys"}
   };
   /** The keys list (H or the Keys button); off at first so it doesn't cover the video. Kept between openings. */
   private static boolean showKeys;
   private static final Identifier PREVIEW_ID = Identifier.of("killclipper", "editor_preview");
   private static PreviewTexture preview;
   private boolean turning;
   private final ReplayPlayer replay;
   private String format;
   private ButtonWidget playButton;
   private ButtonWidget povButton;
   private ButtonWidget speedButton;
   private ButtonWidget zoomButton;
   private ButtonWidget cameraHereButton;
   private ButtonWidget splitButton;
   private ButtonWidget linkButton;
   private boolean vertical;
   /** What the mouse is dragging: 0 nothing, 1 the playhead, 2 the trim start, 3 the trim end, 4 a camera part. */
   private int dragging;
   private long dragMs;

   ReplayEditorScreen(ReplayPlayer replay) {
      super(Text.literal("Clip editor"));
      this.replay = replay;
      this.format = KillClipperClient.instance().settings().fileFormat;
   }

   @Override
   protected void init() {
      int top = this.panelTop();
      int right = this.barRight();
      // Top row: trim buttons on the left, settings of the picked camera part on the right.
      int x = 94;
      this.addDrawableChild(ButtonWidget.builder(Text.literal("In"), b -> this.trimStartHere()).dimensions(x, top + 3, 26, 18).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Out"), b -> this.trimEndHere()).dimensions(x + 28, top + 3, 30, 18).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal(this.vertical ? "9:16" : "16:9"), b -> {
         this.vertical = !this.vertical;
         b.setMessage(Text.literal(this.vertical ? "9:16" : "16:9"));
      }).dimensions(right - 36, top + 3, 36, 18).build());
      this.zoomButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
         ReplayEdit.Switch part = this.replay.partHere();
         part.zoom = next(ZOOMS, part.zoom());
         this.replay.changedPart(part);
      }).dimensions(right - 96, top + 3, 58, 18).build());
      this.speedButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
         ReplayEdit.Switch part = this.replay.partHere();
         part.speed = next(SPEEDS, part.speed());
         this.replay.changedPart(part);
      }).dimensions(right - 162, top + 3, 64, 18).build());
      this.cameraHereButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Set spot"), b -> this.saveCameraSpot())
         .dimensions(right - 230, top + 3, 66, 18).build());
      // Bottom row.
      int y = this.height - 24;
      x = 8;
      this.playButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), b -> this.togglePlay()).dimensions(x, y, 40, 20).build());
      x += 42;
      this.povButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), b -> this.nextPov()).dimensions(x, y, 84, 20).build());
      x += 86;
      this.splitButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Split"), b -> this.split()).dimensions(x, y, 40, 20).build());
      x += 42;
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Merge"), b -> this.merge()).dimensions(x, y, 42, 20).build());
      x += 44;
      this.addDrawableChild(ButtonWidget.builder(Text.literal(this.format.toUpperCase(Locale.ROOT)), b -> {
         this.format = FORMATS.get((FORMATS.indexOf(this.format) + 1) % FORMATS.size());
         b.setMessage(Text.literal(this.format.toUpperCase(Locale.ROOT)));
      }).dimensions(x, y, 36, 20).build());
      x += 38;
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Export"), b -> this.replay.export(this.format, this.vertical)).dimensions(x, y, 44, 20).build());
      x += 46;
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Keys"), b -> showKeys = !showKeys).dimensions(x, y, 34, 20).build());
      x += 36;
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Leave"), b -> ReplayPlayer.stop()).dimensions(x, y, 40, 20).build());
      this.linkButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Get link"), b -> {
         java.nio.file.Path file = ReplayPlayer.exportedFile;
         if (file != null) {
            this.client.setScreen(new ShareScreen(this, file));
         }
      }).dimensions(0, 0, 56, 14).build());
      this.linkButton.visible = false;
      this.refreshLabels();
   }

   private static float next(float[] options, float current) {
      for (float option : options) {
         if (option > current + 0.001F) {
            return option;
         }
      }

      return options[0];
   }

   private static String trim(float value) {
      // Values blend between parts, so round them for the label.
      return new java.math.BigDecimal(Math.round(value * 100.0F) / 100.0).stripTrailingZeros().toPlainString();
   }

   /** The camera part under the playhead, or null before the first one. */
   private ReplayEdit.Switch part() {
      return this.replay.edit.at(this.replay.clipTimeMs());
   }

   private void refreshLabels() {
      ReplayEdit.Switch part = this.part();
      ReplayPlayer.CameraMode mode = part != null ? part.mode : this.replay.mode();
      this.playButton.setMessage(Text.literal(this.replay.paused() ? "Play" : "Pause"));
      this.povButton.setMessage(Text.literal("POV: " + mode.shortLabel));
      this.speedButton.setMessage(Text.literal("Speed " + trim(part != null ? part.speed() : 1.0F) + "x"));
      this.zoomButton.setMessage(Text.literal("Zoom " + trim(part != null ? part.zoom() : 1.0F) + "x"));
      this.cameraHereButton.visible = mode == ReplayPlayer.CameraMode.SPECTATOR;
      // In spectator a split is a camera keyframe: the camera glides through every keyframe's spot.
      this.splitButton.setMessage(Text.literal(mode == ReplayPlayer.CameraMode.SPECTATOR ? "+Key" : "Split"));
   }

   private void togglePlay() {
      long[] trim = this.replay.trim();
      if (this.replay.paused() && this.replay.clipTimeMs() >= trim[1] - 50L) {
         // At the end of the trim, play starts again from the trim start.
         this.replay.seekClip(trim[0]);
      }

      this.replay.setPaused(!this.replay.paused());
   }

   private void nextPov() {
      ReplayEdit.Switch part = this.replay.partHere();
      ReplayPlayer.CameraMode[] modes = ReplayPlayer.CameraMode.values();
      part.mode = modes[(part.mode.ordinal() + 1) % modes.length];
      this.replay.changedPart(part);
   }

   private void split() {
      this.replay.addSwitchHere();
   }

   private void keyframe() {
      this.replay.addKeyframeHere();
   }

   private void merge() {
      ReplayEdit.Switch part = this.part();
      if (part != null) {
         this.replay.edit.switches.remove(part);
         this.replay.edit.save();
         ReplayEdit.Switch now = this.part();
         if (now != null) {
            this.replay.changedPart(now);
         }
      }
   }

   private void saveCameraSpot() {
      ReplayEdit.Switch part = this.replay.partHere();
      this.replay.moveCameraOf(part);
   }

   private void trimStartHere() {
      long[] trim = this.replay.trim();
      this.replay.setTrim(this.replay.clipTimeMs(), trim[1]);
   }

   private void trimEndHere() {
      long[] trim = this.replay.trim();
      this.replay.setTrim(trim[0], this.replay.clipTimeMs());
   }

   private int barLeft() {
      return 8 + LABELS;
   }

   private int barRight() {
      return this.width - 8;
   }

   private int panelTop() {
      return this.height - PANEL;
   }

   private int clipTrackTop() {
      return this.panelTop() + 35;
   }

   private int cameraTrackTop() {
      return this.panelTop() + 53;
   }

   private int xOf(long ms) {
      long length = Math.max(1L, this.replay.clipLengthMs());
      return this.barLeft() + (int)((this.barRight() - this.barLeft()) * Math.min(1.0, Math.max(0.0, (double)ms / length)));
   }

   private long msAt(double x) {
      double fraction = (x - this.barLeft()) / Math.max(1.0, this.barRight() - this.barLeft());
      return (long)(Math.max(0.0, Math.min(1.0, fraction)) * this.replay.clipLengthMs());
   }

   /** A copy of the game picture, drawn smaller above the timeline like the preview in a video editor. */
   private static final class PreviewTexture extends AbstractTexture {
      final int width;
      final int height;

      PreviewTexture(int width, int height) {
         this.width = width;
         this.height = height;
         this.glTexture = RenderSystem.getDevice().createTexture("Kill Clipper editor preview", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
            TextureFormat.RGBA8, width, height, 1, 1);
         this.glTextureView = RenderSystem.getDevice().createTextureView(this.glTexture);
         this.sampler = RenderSystem.getSamplerCache().get(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false);
      }
   }

   /** Where the game picture goes: the area above the panel, keeping its shape. {x, y, width, height}. */
   private int[] previewArea() {
      int areaHeight = this.panelTop() - 4;
      int areaWidth = this.width - 16;
      double aspect = (double)this.client.getWindow().getFramebufferWidth() / Math.max(1, this.client.getWindow().getFramebufferHeight());
      int w = Math.min(areaWidth, (int)(areaHeight * aspect));
      int h = Math.min(areaHeight, (int)(w / aspect));
      return new int[]{(this.width - w) / 2, 2, w, h};
   }

   @Override
   public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
      context.fill(0, 0, this.width, this.height, 0xFF101010);
      GpuTexture source = this.client.getFramebuffer().getColorAttachment();
      if (source == null) {
         return;
      }

      // The world is already drawn into the game's picture; the editor is drawn over it later, so copy it now.
      int w = source.getWidth(0);
      int h = source.getHeight(0);
      if (preview == null || preview.width != w || preview.height != h) {
         if (preview != null) {
            this.client.getTextureManager().destroyTexture(PREVIEW_ID);
         }

         preview = new PreviewTexture(w, h);
         this.client.getTextureManager().registerTexture(PREVIEW_ID, preview);
      }

      RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source, preview.getGlTexture(), 0, 0, 0, 0, 0, w, h);
      int[] area = this.previewArea();
      context.fill(area[0] - 1, area[1] - 1, area[0] + area[2] + 1, area[1] + area[3] + 1, 0xFF333333);
      // The game picture is stored bottom row first, so flip it.
      context.drawTexturedQuad(PREVIEW_ID, area[0], area[1], area[0] + area[2], area[1] + area[3], 0.0F, 1.0F, 1.0F, 0.0F);
      context.fill(0, this.panelTop(), this.width, this.panelTop() + 1, 0xFF444444);
   }

   @Override
   public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
      if (ReplayPlayer.active() != this.replay) {
         // Jumping back restarts the replay, which opens the editor again once it has loaded.
         this.client.setScreen(null);
         return;
      }

      this.refreshLabels();
      super.render(context, mouseX, mouseY, deltaTicks);
      int left = this.barLeft();
      int right = this.barRight();
      long length = Math.max(1L, this.replay.clipLengthMs());
      long now = this.dragging == 1 ? this.dragMs : this.replay.clipTimeMs();
      long[] trim = this.replay.trim();
      if (this.dragging == 2) {
         trim = new long[]{Math.min(this.dragMs, trim[1] - 500L), trim[1]};
      } else if (this.dragging == 3) {
         trim = new long[]{trim[0], Math.max(this.dragMs, trim[0] + 500L)};
      }

      int top = this.panelTop();
      context.drawTextWithShadow(this.textRenderer, clock(now) + " / " + clock(length), 8, top + 8, 0xFFFFFFFF);
      // Time ruler.
      int rulerY = top + 24;
      long step = length > 60000L ? 10000L : length > 20000L ? 5000L : 1000L;
      for (long t = 0L; t <= length; t += step) {
         int x = this.xOf(t);
         context.fill(x, rulerY + 6, x + 1, rulerY + 10, 0xFF777777);
         if (t % (step * (length > 20000L ? 2L : 5L)) == 0L) {
            context.drawTextWithShadow(this.textRenderer, clock(t).replaceAll("\\.0$", ""), x + 2, rulerY - 1, 0xFF888888);
         }
      }

      // Clip track: the whole fight, with the trimmed part bright and yellow handles at both ends.
      int clipTop = this.clipTrackTop();
      context.drawTextWithShadow(this.textRenderer, "Clip", 8, clipTop + 3, 0xFFCCCCCC);
      context.fill(left, clipTop, right, clipTop + 14, 0xFF1C1C1C);
      int in = this.xOf(trim[0]);
      int out = this.xOf(trim[1]);
      context.fill(in, clipTop, out, clipTop + 14, 0xFF4A6FA5);
      String clipName = this.replay.file().getFileName().toString().replace(".kcreplay", "");
      String name = this.textRenderer.trimToWidth(clock(trim[1] - trim[0]) + "  " + clipName, Math.max(0, out - in - 12));
      context.drawTextWithShadow(this.textRenderer, name, in + 6, clipTop + 3, 0xFFFFFFFF);
      context.fill(in, clipTop - 1, in + 3, clipTop + 15, 0xFFFFDD33);
      context.fill(out - 3, clipTop - 1, out, clipTop + 15, 0xFFFFDD33);
      // Camera track: one coloured part per camera switch, the picked one outlined.
      int camTop = this.cameraTrackTop();
      context.drawTextWithShadow(this.textRenderer, "Camera", 8, camTop + 4, 0xFFCCCCCC);
      List<ReplayEdit.Switch> parts = this.replay.edit.switches;
      long firstStart = parts.isEmpty() ? length : parts.get(0).timeMs;
      if (firstStart > 0L) {
         this.drawPart(context, this.xOf(0L), this.xOf(firstStart), camTop, 0xFF3A3A3A, "Recorded POV", false);
      }

      ReplayEdit.Switch picked = this.part();
      for (int i = 0; i < parts.size(); i++) {
         ReplayEdit.Switch part = parts.get(i);
         long end = i + 1 < parts.size() ? parts.get(i + 1).timeMs : length;
         String label = part.mode.shortLabel;
         if (part.speed() != 1.0F) {
            label = label + " " + trim(part.speed()) + "x";
         }

         if (part.zoom() != 1.0F) {
            label = label + " zoom " + trim(part.zoom());
         }

         this.drawPart(context, this.xOf(part.timeMs), this.xOf(end), camTop, MODE_COLORS[part.mode.ordinal()], label, part == picked);
         if (part.mode == ReplayPlayer.CameraMode.SPECTATOR) {
            // Keyframe marker.
            int kx = this.xOf(part.timeMs);
            context.fill(kx - 2, camTop - 4, kx + 3, camTop + 1, 0xFFFFFFFF);
         }
      }

      // Playhead across the ruler and both tracks.
      int played = this.xOf(now);
      context.fill(played - 1, rulerY + 4, played + 1, camTop + 17, 0xFFFFFFFF);
      context.fill(played - 3, rulerY + 2, played + 3, rulerY + 5, 0xFFFFFFFF);
      if (this.replay.exporting()) {
         context.drawCenteredTextWithShadow(this.textRenderer, "Exporting...", this.width / 2, 8, 0xFFFF5555);
      }

      if (showKeys) {
         this.drawKeys(context);
      }

      String status = this.statusText();
      if (!status.isEmpty()) {
         int[] box = this.statusBox(status);
         context.fill(box[0], box[1], box[2], box[3], 0xD0000000);
         int color = status.startsWith("Export failed") ? 0xFFFF6666 : status.startsWith("Saved") ? 0xFF66FF66 : 0xFFFFDD55;
         context.drawCenteredTextWithShadow(this.textRenderer, status, (box[0] + box[2]) / 2, box[1] + 3, color);
      }

      boolean saved = ReplayPlayer.exportStatus.startsWith("Saved") && ReplayPlayer.exportedFile != null;
      this.linkButton.visible = saved;
      if (saved) {
         int[] box = this.statusBox(status);
         this.linkButton.setX(Math.min(this.width - 60, box[2] + 4));
         this.linkButton.setY(box[1]);
      }
   }

   private void drawPart(DrawContext context, int x0, int x1, int y, int color, String label, boolean picked) {
      if (x1 <= x0) {
         return;
      }

      if (picked) {
         context.fill(x0, y - 1, x1, y + 17, 0xFFFFFFFF);
      }

      context.fill(x0 + 1, y, x1 - 1, y + 16, color);
      String text = this.textRenderer.trimToWidth(label, Math.max(0, x1 - x0 - 6));
      context.drawTextWithShadow(this.textRenderer, text, x0 + 4, y + 4, 0xFFFFFFFF);
   }

   private void drawKeys(DrawContext context) {
      int keyWidth = 0;
      int textWidth = 0;
      for (String[] key : KEYS) {
         keyWidth = Math.max(keyWidth, this.textRenderer.getWidth(key[0]));
         textWidth = Math.max(textWidth, this.textRenderer.getWidth(key[1]));
      }

      int x = 8;
      int y = 8;
      int w = keyWidth + textWidth + 22;
      int h = KEYS.length * 11 + 20;
      // Shrink the list on small screens so it never covers the timeline.
      float scale = Math.min(1.0F, Math.min((this.panelTop() - 12.0F) / h, (this.width * 0.6F) / w));
      context.getMatrices().pushMatrix();
      context.getMatrices().translate(x, y);
      context.getMatrices().scale(scale, scale);
      context.getMatrices().translate(-x, -y);
      context.fill(x, y, x + w, y + h, 0xC0000000);
      context.drawTextWithShadow(this.textRenderer, "Editor keys", x + 6, y + 5, 0xFFFFDD55);
      for (int i = 0; i < KEYS.length; i++) {
         int ly = y + 18 + i * 11;
         context.drawTextWithShadow(this.textRenderer, KEYS[i][0], x + 6, ly, 0xFFFFFFFF);
         context.drawTextWithShadow(this.textRenderer, KEYS[i][1], x + 14 + keyWidth, ly, 0xFFBBBBBB);
      }

      context.getMatrices().popMatrix();
   }

   private String statusText() {
      String status = ReplayPlayer.exportStatus;
      if (status.startsWith("Saved")) {
         status = status + " Click to open the folder.";
      }

      return this.textRenderer.trimToWidth(status, this.width - 90);
   }

   /** The export message box above the timeline: {x1, y1, x2, y2}. */
   private int[] statusBox(String status) {
      int w = this.textRenderer.getWidth(status) + 10;
      int y = this.panelTop() - 16;
      int center = this.width / 2 - 30;
      return new int[]{center - w / 2, y, center + w / 2, y + 14};
   }

   private static String clock(long ms) {
      return String.format(Locale.ROOT, "%d:%04.1f", ms / 60000L, (ms % 60000L) / 1000.0);
   }

   @Override
   public boolean mouseClicked(Click click, boolean doubled) {
      String status = this.statusText();
      if (ReplayPlayer.exportStatus.startsWith("Saved")) {
         int[] box = this.statusBox(status);
         if (click.x() >= box[0] && click.x() <= box[2] && click.y() >= box[1] && click.y() <= box[3]) {
            KillClipperClient.instance().openClipsFolder();
            return true;
         }
      }

      int[] area = this.previewArea();
      if (click.y() < area[1] + area[3] && click.x() >= area[0] && click.x() <= area[0] + area[2] && this.hoveredElement(click.x(), click.y()).isEmpty()) {
         // Dragging on the picture turns the spectator camera.
         this.turning = true;
         return true;
      }

      if (click.button() == 0 && click.x() >= this.barLeft() - 4 && click.x() <= this.barRight() + 4) {
         double y = click.y();
         int clipTop = this.clipTrackTop();
         if (y >= this.panelTop() + 22 && y < clipTop + 16) {
            long[] trim = this.replay.trim();
            if (y >= clipTop - 2 && Math.abs(click.x() - this.xOf(trim[0])) <= 5) {
               this.dragging = 2;
            } else if (y >= clipTop - 2 && Math.abs(click.x() - this.xOf(trim[1])) <= 5) {
               this.dragging = 3;
            } else {
               this.dragging = 1;
            }

            this.dragMs = this.msAt(click.x());
            return true;
         }

         int camTop = this.cameraTrackTop();
         if (y >= camTop - 1 && y <= camTop + 17) {
            this.dragging = 4;
            this.dragMs = this.msAt(click.x());
            return true;
         }
      }

      return super.mouseClicked(click, doubled);
   }

   @Override
   public boolean mouseDragged(Click click, double offsetX, double offsetY) {
      if (this.turning) {
         double scale = this.client.getWindow().getScaleFactor();
         this.replay.turnCamera(offsetX * scale, offsetY * scale);
         return true;
      }

      if (this.dragging != 0) {
         this.dragMs = this.msAt(click.x());
         return true;
      }

      return super.mouseDragged(click, offsetX, offsetY);
   }

   @Override
   public boolean mouseReleased(Click click) {
      if (this.turning) {
         this.turning = false;
         return true;
      }

      if (this.dragging == 0) {
         return super.mouseReleased(click);
      }

      int what = this.dragging;
      this.dragging = 0;
      long ms = this.msAt(click.x());
      long[] trim = this.replay.trim();
      switch (what) {
         case 2 -> this.replay.setTrim(ms, trim[1]);
         case 3 -> this.replay.setTrim(trim[0], ms);
         case 4 -> {
            // Picking a part jumps to its start, so it is the part being changed and shown.
            ReplayEdit.Switch part = this.replay.edit.at(ms);
            this.replay.seekClip(part != null ? part.timeMs : ms);
         }
         default -> this.replay.seekClip(ms);
      }

      return true;
   }

   @Override
   public boolean keyPressed(KeyInput input) {
      switch (input.key()) {
         case GLFW.GLFW_KEY_E -> this.close();
         case GLFW.GLFW_KEY_H -> showKeys = !showKeys;
         case GLFW.GLFW_KEY_P -> this.togglePlay();
         case GLFW.GLFW_KEY_V -> this.nextPov();
         case GLFW.GLFW_KEY_X -> this.split();
         case GLFW.GLFW_KEY_K -> this.keyframe();
         // Camera flying keys: the replay reads them itself; don't let them press a focused button.
         case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_LEFT_SHIFT,
            GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_ENTER -> {
         }
         case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> this.merge();
         case GLFW.GLFW_KEY_I -> this.trimStartHere();
         case GLFW.GLFW_KEY_O -> this.trimEndHere();
         case GLFW.GLFW_KEY_C -> this.saveCameraSpot();
         case GLFW.GLFW_KEY_LEFT -> this.replay.seekClip(this.replay.clipTimeMs() - 5000L);
         case GLFW.GLFW_KEY_RIGHT -> this.replay.seekClip(this.replay.clipTimeMs() + 5000L);
         default -> {
            return super.keyPressed(input);
         }
      }

      return true;
   }

   @Override
   public boolean shouldPause() {
      return false;
   }
}
