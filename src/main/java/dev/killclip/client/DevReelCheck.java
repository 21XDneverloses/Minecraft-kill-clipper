package dev.killclip.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.ScreenshotRecorder;

/** Dev check: -Dkillclip.devReel=true opens the highlight reel from the clip list and makes one from the clips folder. */
@Environment(EnvType.CLIENT)
final class DevReelCheck {
   private static final boolean ON = Boolean.getBoolean("killclip.devReel");
   private static int step;
   private static long stepAt;

   private DevReelCheck() {
   }

   static void tick(MinecraftClient client) {
      if (!ON) {
         return;
      }

      long now = System.nanoTime() / 1000000L;
      if (step > 0 && now - stepAt < 2500L) {
         return;
      }

      switch (step) {
         case 0 -> {
            if (!(client.currentScreen instanceof TitleScreen) || KillClipperClient.instance().ffmpeg() == null) {
               return;
            }

            ClipLibraryScreen library = new ClipLibraryScreen(client.currentScreen);
            client.setScreen(library);
            // Show a picked clip in the screenshot when the dev clips folder has the self-test one.
            java.nio.file.Path picked = KillClipperClient.instance().settings().clipsDir().resolve("selftest.mp4");
            if (java.nio.file.Files.isRegularFile(picked)) {
               library.devSelect(picked);
            }
         }
         case 1 -> {
            shot(client, "reel-library.png");
            press(client, "Highlight reel");
         }
         case 2 -> {
            shot(client, "reel-screen.png");
            press(client, "Make highlight reel");
         }
         case 3 -> shot(client, "reel-making.png");
         case 4 -> {
            if (HighlightReelScreen.busy()) {
               return;
            }

            KillClipperClient.LOGGER.info("KILLCLIP_DEVREEL finished: {}", HighlightReelScreen.status());
            shot(client, "reel-done.png");
            client.setScreen(new KillClipSettingsScreen(client.currentScreen));
         }
         case 5 -> shot(client, "reel-settings.png");
         default -> {
            return;
         }
      }

      step++;
      stepAt = now;
   }

   private static void press(MinecraftClient client, String label) {
      for (var child : client.currentScreen.children()) {
         if (child instanceof ButtonWidget button && button.getMessage().getString().equals(label)) {
            KillClipperClient.LOGGER.info("KILLCLIP_DEVREEL pressing {} (active {})", label, button.active);
            button.onPress(null);
            return;
         }
      }

      KillClipperClient.LOGGER.error("KILLCLIP_DEVREEL no button {}", label);
   }

   private static void shot(MinecraftClient client, String name) {
      ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), 1, text -> KillClipperClient.LOGGER.info("KILLCLIP_DEVREEL {}", text.getString()));
   }
}
