package dev.killclip.client;

import dev.killclip.core.EncoderProbe;
import dev.killclip.core.ReplayBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.text.Text;

/**
 * The welcome screen shown once on first launch: it tests which video encoders work on this PC, picks clip settings
 * to match, checks the clips folder and shows the clip key. It can be opened again from the settings.
 */
@Environment(EnvType.CLIENT)
public final class SetupScreen extends FlatScreen {
   private static final int HALF = 165;
   private static final int ROW = 30;
   private final Screen parent;
   private CompletableFuture<Map<String, Boolean>> probe;
   private Map<String, Boolean> encoders;
   private EncoderProbe.Tuning tuning;
   private String folderStatus = "";
   private boolean folderOk;
   private FlatButton apply;

   public SetupScreen(Screen parent) {
      super(Text.literal("Welcome to Kill Clipper"));
      this.parent = parent;
   }

   @Override
   protected void init() {
      if (this.probe == null) {
         this.probe = KillClipperClient.instance().testEncoders();
         this.checkFolder();
      }

      int x = this.width / 2 - HALF + 8;
      int top = this.top();
      int right = this.width / 2 + HALF - 8;
      this.addDrawableChild(FlatButton.of(Text.literal("Open"), b -> KillClipperClient.instance().openClipsFolder())
         .dimensions(right - 64, top + ROW * 2 + 4, 64, 18).build());
      this.addDrawableChild(FlatButton.of(Text.literal("Change key"), b -> this.client.setScreen(new KeybindsScreen(this, this.client.options)))
         .dimensions(right - 64, top + ROW * 3 + 4, 64, 18).build());
      this.addDrawableChild(FlatButton.of(Text.literal("Skip"), b -> this.finish(false))
         .dimensions(x, top + ROW * 4 + 22, 100, 20).build());
      this.apply = this.addDrawableChild(FlatButton.of(Text.literal("Use these settings"), b -> this.finish(true))
         .dimensions(x + 104, top + ROW * 4 + 22, right - x - 104, 20).primary().build());
      this.apply.active = this.tuning != null;
   }

   private int top() {
      return Math.max(34, this.height / 2 - 92);
   }

   private void checkFolder() {
      Path dir = KillClipperClient.instance().settings().clipsDir();
      try {
         Files.createDirectories(dir);
         Path probeFile = Files.createTempFile(dir, ".write-test", ".tmp");
         Files.delete(probeFile);
         this.folderOk = true;
         this.folderStatus = shorten(dir.toAbsolutePath().normalize().toString(), 44);
      } catch (Exception e) {
         this.folderOk = false;
         this.folderStatus = "Can't write here: " + shorten(dir.toString(), 30);
      }
   }

   private static String shorten(String path, int max) {
      return path.length() <= max ? path : "..." + path.substring(path.length() - max + 3);
   }

   @Override
   public void tick() {
      if (this.tuning == null && this.probe != null && this.probe.isDone()) {
         this.encoders = this.probe.getNow(Map.of());
         MinecraftClient client = MinecraftClient.getInstance();
         int refresh = client.getWindow().getRefreshRate();
         int screenHeight = client.getWindow().getMonitor() == null ? 0 : client.getWindow().getMonitor().getCurrentVideoMode().getHeight();
         this.tuning = EncoderProbe.tune(EncoderProbe.best(this.encoders), refresh, Runtime.getRuntime().availableProcessors(), screenHeight);
         if (this.apply != null) {
            this.apply.active = true;
         }

         if (Boolean.getBoolean("killclip.devSetupApply")) {
            // Dev check: press "Use these settings" as soon as the test is done.
            this.finish(true);
         }
      }
   }

   private void finish(boolean applyTuning) {
      ClipConfig config = KillClipperClient.instance().settings();
      if (applyTuning && this.tuning != null) {
         config.encoder = this.tuning.encoder();
         config.fps = this.tuning.fps();
         config.resolution = this.tuning.resolution();
         config.maxBitrateMbps = 0;
      }

      config.setupDone = true;
      String error = KillClipperClient.instance().saveSettings(config);
      if (!error.isEmpty()) {
         KillClipperClient.LOGGER.warn("Setup: {}", error);
      }

      this.client.setScreen(this.parent);
   }

   @Override
   public void close() {
      this.finish(false);
   }

   @Override
   protected void drawPanels(DrawContext graphics, float alpha) {
      int top = this.top();
      Ui.panel(graphics, this.width / 2 - HALF, top - 6, this.width / 2 + HALF, top + ROW * 4 + 48, alpha);
      for (int i = 1; i < 4; i++) {
         graphics.fill(this.width / 2 - HALF + 8, top + ROW * i - 2, this.width / 2 + HALF - 8, top + ROW * i - 1, Ui.fade(Ui.LINE, alpha));
      }
   }

   @Override
   protected void drawContent(DrawContext graphics, int mouseX, int mouseY, float delta, float alpha) {
      int top = this.top();
      int x = this.width / 2 - HALF + 8;
      Ui.title(graphics, this.textRenderer, this.title, this.width / 2, top - 26, alpha);

      String encoderLine;
      int encoderColor;
      if (this.tuning == null) {
         long dots = System.currentTimeMillis() / 400L % 4L;
         encoderLine = "Testing your graphics card" + ".".repeat((int)dots);
         encoderColor = Ui.WARN;
      } else if (this.tuning.encoder().equals("libx264")) {
         encoderLine = "No GPU encoder found: using the CPU (lighter settings)";
         encoderColor = Ui.WARN;
      } else {
         encoderLine = "✓ " + encoderName(this.tuning.encoder()) + " works: almost no fps cost";
         encoderColor = Ui.SUCCESS;
      }

      this.row(graphics, x, top, "Video encoder", encoderLine, encoderColor, alpha);

      String clipLine = "...";
      if (this.tuning != null) {
         int height = this.tuning.resolution() == 0 ? MinecraftClient.getInstance().getWindow().getFramebufferHeight() : this.tuning.resolution();
         int width = height * 16 / 9;
         int mbps = ReplayBuffer.bitrateCapKbps(this.tuning.encoder(), width, height, this.tuning.fps(), 0) / 1000;
         clipLine = (this.tuning.resolution() == 0 ? "Native" : this.tuning.resolution() + "p") + " · " + this.tuning.fps() + " fps · up to " + mbps + " Mbit/s";
      }

      this.row(graphics, x, top + ROW, "Clip quality", clipLine, Ui.TEXT, alpha);
      this.row(graphics, x, top + ROW * 2, "Clips folder", (this.folderOk ? "✓ " : "") + this.folderStatus, this.folderOk ? Ui.TEXT : Ui.ERROR, alpha);
      this.row(graphics, x, top + ROW * 3, "Clip right now", "Press " + KillClipperClient.clipKeyName() + " any time", Ui.TEXT, alpha);
      Ui.text(graphics, this.textRenderer, "Every kill gets clipped. Streaks of 2+ kills save as one clip.", x, top + ROW * 4 + 6, Ui.fade(Ui.MUTED, alpha));
   }

   private void row(DrawContext graphics, int x, int y, String label, String value, int color, float alpha) {
      Ui.text(graphics, this.textRenderer, label, x, y + 2, Ui.fade(Ui.MUTED, alpha));
      Ui.text(graphics, this.textRenderer, value, x, y + 14, Ui.fade(color, alpha));
   }

   static String encoderName(String encoder) {
      return switch (encoder) {
         case "hevc_nvenc" -> "NVIDIA H.265";
         case "h264_nvenc" -> "NVIDIA H.264";
         case "h264_amf" -> "AMD";
         case "h264_qsv" -> "Intel";
         default -> "CPU";
      };
   }
}
