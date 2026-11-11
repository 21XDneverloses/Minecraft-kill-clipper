package dev.killclip.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;

/** Dev check: -Dkillclip.devEditor=replayFile walks through the editor screens and exports an edited clip. */
@Environment(EnvType.CLIENT)
final class DevEditorCheck {
   private static final String FILE = System.getProperty("killclip.devEditor", "");
   private static int step;
   private static long stepAt;

   private DevEditorCheck() {
   }

   static void tick(MinecraftClient client) {
      if (FILE.isEmpty()) {
         return;
      }

      long now = System.nanoTime() / 1000000L;
      if (step > 0 && now - stepAt < 3000L) {
         return;
      }

      try {
         switch (step) {
            case 0 -> {
               if (!(client.currentScreen instanceof TitleScreen)) {
                  return;
               }

               shot(client, "editor-title.png");
            }
            case 1 -> client.setScreen(new ClipLibraryScreen(client.currentScreen));
            case 2 -> shot(client, "editor-library.png");
            case 3 -> {
               Path video;
               try (Stream<Path> files = Files.list(KillClipperClient.instance().settings().clipsDir())) {
                  video = files.filter(dev.killclip.core.VideoTools::isVideo).findFirst().orElseThrow();
               }

               client.setScreen(new VideoTrimScreen(client.currentScreen, video));
            }
            case 4 -> {
               shot(client, "editor-trim.png");
               KillClipperClient.instance().stats.kill("dev.example.net");
               KillClipperClient.instance().stats.streak("dev.example.net", 3);
               client.setScreen(new StatsScreen(client.currentScreen));
            }
            case 5 -> {
               shot(client, "editor-stats.png");
               client.setScreen(new ShareScreen(client.currentScreen, Path.of(FILE).resolveSibling("x.mp4")));
            }
            case 6 -> {
               shot(client, "editor-share.png");
               if (Boolean.getBoolean("killclip.devViaLibrary")) {
                  // Like a player: pick the clip in the library and press Edit.
                  ClipLibraryScreen library = new ClipLibraryScreen(new TitleScreen());
                  client.setScreen(library);
                  library.devSelect(Path.of(FILE.replace(".kcreplay", ".mp4")));
                  for (var child : library.children()) {
                     if (child instanceof net.minecraft.client.gui.widget.ButtonWidget button && button.getMessage().getString().startsWith("Edit")) {
                        KillClipperClient.LOGGER.info("KILLCLIP_DEVEDITOR pressing {} (active {})", button.getMessage().getString(), button.active);
                        button.onPress(null);
                     }
                  }
               } else {
                  ReplayPlayer.start(client, Path.of(FILE));
                  ReplayPlayer.active().openEditorOnLoad = true;
               }
            }
            case 7 -> {
               ReplayPlayer replay = ReplayPlayer.active();
               if (replay == null || client.world == null || !(client.currentScreen instanceof ReplayEditorScreen)) {
                  return;
               }

               shot(client, "editor-open.png");
               replay.edit.switches.clear();
               net.minecraft.client.network.ClientPlayerEntity p = client.player;
               replay.edit.put(new ReplayEdit.Switch(0L, ReplayPlayer.CameraMode.FIRST_PERSON, 0, 0, 0, 0, 0));
               replay.edit.put(new ReplayEdit.Switch(3000L, ReplayPlayer.CameraMode.SPECTATOR, p.getX() + 6, p.getY() + 3, p.getZ(), 90.0F, 25.0F, 1.0F, 1.0F));
               replay.edit.put(new ReplayEdit.Switch(6000L, ReplayPlayer.CameraMode.SPECTATOR, p.getX(), p.getY() + 3, p.getZ() + 6, 180.0F, 25.0F, 0.25F, 3.0F));
               replay.edit.put(new ReplayEdit.Switch(8000L, ReplayPlayer.CameraMode.THIRD_PERSON, 0, 0, 0, 0, 0));
               replay.setTrim(1000L, 9000L);
               KillClipperClient.LOGGER.info("KILLCLIP_DEVEDITOR editor opened by itself, trim {}", java.util.Arrays.toString(replay.trim()));
               client.setScreen(new ReplayEditorScreen(replay));
            }
            case 8 -> shot(client, "editor-timeline.png");
            case 9 -> {
               ReplayPlayer.active().export("mp4", true);
               KillClipperClient.LOGGER.info("KILLCLIP_DEVEDITOR export started");
            }
            case 10 -> {
               ExportJob job = ExportJob.active;
               if (job != null && job.progress > 0.4F && !barShot) {
                  barShot = true;
                  shot(client, "editor-exporting.png");
               }

               if (!ReplayPlayer.exportStatus.startsWith("Saved") && !ReplayPlayer.exportStatus.startsWith("Export failed")) {
                  return;
               }

               KillClipperClient.LOGGER.info("KILLCLIP_DEVEDITOR status: {}", ReplayPlayer.exportStatus);
               shot(client, "editor-saved.png");
            }
            case 11 -> {
               // Back to the library and press Edit again, like a player after an export.
               ReplayPlayer.stop();
               ClipLibraryScreen library = new ClipLibraryScreen(new TitleScreen());
               client.setScreen(library);
               library.devSelect(Path.of(FILE.replace(".kcreplay", ".mp4")));
               for (var child : library.children()) {
                  if (child instanceof net.minecraft.client.gui.widget.ButtonWidget button && button.getMessage().getString().startsWith("Edit")) {
                     button.onPress(null);
                  }
               }
            }
            case 12 -> {
               if (!(client.currentScreen instanceof ReplayEditorScreen)) {
                  if (now - stepAt > 30000L) {
                     KillClipperClient.LOGGER.info("KILLCLIP_DEVEDITOR reopen FAILED, screen {}", client.currentScreen);
                     step = 99;
                  }
                  return;
               }

               KillClipperClient.LOGGER.info("KILLCLIP_DEVEDITOR reopened editor after export");
               shot(client, "editor-reopened.png");
            }
            default -> {
               return;
            }
         }
      } catch (Exception e) {
         KillClipperClient.LOGGER.error("KILLCLIP_DEVEDITOR step {} failed", step, e);
      }

      step++;
      stepAt = now;
   }

   private static boolean barShot;

   private static void shot(MinecraftClient client, String name) {
      ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), 1,
         text -> KillClipperClient.LOGGER.info("KILLCLIP_DEVEDITOR {}", text.getString()));
   }
}
