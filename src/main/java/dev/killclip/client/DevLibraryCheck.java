package dev.killclip.client;

import dev.killclip.core.ClipLibrary;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.ScreenshotRecorder;

/** Dev check: -Dkillclip.devLibrary=true opens the clip library and walks through search, favourites, rename and storage. */
@Environment(EnvType.CLIENT)
final class DevLibraryCheck {
   private static final boolean ON = Boolean.getBoolean("killclip.devLibrary");
   private static int step;
   private static long stepAt;
   private static ClipLibraryScreen library;

   private DevLibraryCheck() {
   }

   static void tick(MinecraftClient client) {
      if (!ON) {
         return;
      }

      long now = System.nanoTime() / 1000000L;
      if (step > 0 && now - stepAt < 4000L) {
         return;
      }

      try {
         switch (step) {
            case 0 -> {
               if (!(client.currentScreen instanceof TitleScreen) || KillClipperClient.instance().ffmpeg() == null) {
                  return;
               }

               library = new ClipLibraryScreen(client.currentScreen);
               client.setScreen(library);
            }
            case 1 -> {
               // Thumbnails are made in the background: give them a moment, then shoot.
               shot(client, "library.png");
               Path dir = KillClipperClient.instance().settings().clipsDir();
               Path pick;
               try (var files = Files.list(dir)) {
                  pick = files.filter(f -> f.getFileName().toString().endsWith("3-kill-streak.mp4")).findFirst().orElse(null);
               }

               if (pick != null) {
                  ClipLibrary.load(dir).setFavourite(pick, true);
                  library = new ClipLibraryScreen(new TitleScreen());
                  client.setScreen(library);
                  library.devSelect(pick);
               }
            }
            case 2 -> {
               shot(client, "library-selected.png");
               press(client, "Rename");
            }
            case 3 -> {
               shot(client, "library-rename.png");
               client.currentScreen.close();
               press(client, "Show: All");
               press(client, "Show: Favourites");
            }
            case 4 -> {
               shot(client, "library-streaks.png");
               press(client, "Storage");
            }
            case 5 -> {
               press(client, "Max size: No limit");
               press(client, "Delete clips older than: Never");
            }
            case 6 -> {
               shot(client, "library-storage.png");
               ClipLibrary lib = ClipLibrary.load(KillClipperClient.instance().settings().clipsDir());
               lib.setMaxGb(0);
               lib.setMaxDays(0);
               KillClipperClient.LOGGER.info("KILLCLIP_DEVLIBRARY done");
            }
            default -> {
               return;
            }
         }
      } catch (Exception e) {
         KillClipperClient.LOGGER.error("KILLCLIP_DEVLIBRARY step {} failed", step, e);
      }

      step++;
      stepAt = now;
   }

   private static void press(MinecraftClient client, String label) {
      for (var child : client.currentScreen.children()) {
         if (child instanceof ButtonWidget button && button.getMessage().getString().equals(label)) {
            button.onPress(null);
            return;
         }
      }

      KillClipperClient.LOGGER.error("KILLCLIP_DEVLIBRARY no button {}", label);
   }

   private static void shot(MinecraftClient client, String name) {
      ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), 1, text -> KillClipperClient.LOGGER.info("KILLCLIP_DEVLIBRARY {}", text.getString()));
   }
}
