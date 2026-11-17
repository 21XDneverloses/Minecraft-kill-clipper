package dev.killclip.client;

import dev.killclip.core.ClipLibrary;
import dev.killclip.core.ReplayLog;
import dev.killclip.core.VideoTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** Finds the clips on disk for the library, and keeps the clips folder inside the storage limits. */
@Environment(EnvType.CLIENT)
public final class ClipStorage {
   /** One background thread for thumbnails, server look-ups and clean-ups, behind the game. */
   static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "Kill Clipper library");
      thread.setDaemon(true);
      thread.setPriority(Thread.MIN_PRIORITY);
      return thread;
   });

   private ClipStorage() {
   }

   static Path clipsDir() {
      return KillClipperClient.instance().settings().clipsDir();
   }

   /** Every clip in the clips folder and the Edited folder, newest first. */
   static List<ClipLibrary.Item> scan(ClipLibrary library) throws IOException {
      Path dir = clipsDir();
      List<ClipLibrary.Item> items = new ArrayList<>();
      if (!Files.isDirectory(dir)) {
         return items;
      }

      Path edited = KillClipperClient.instance().settings().editedDir();
      try (Stream<Path> files = Stream.concat(Files.list(dir), Files.isDirectory(edited) ? Files.list(edited) : Stream.empty())) {
         for (Path file : files.filter(Files::isRegularFile).toList()) {
            boolean replayOnly = file.getFileName().toString().endsWith(".kcreplay");
            if (!VideoTools.isVideo(file) && !(replayOnly && ClipLibraryScreen.findVideoFor(file) == null)) {
               continue;
            }

            long modified;
            try {
               modified = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException e) {
               modified = 0L;
            }

            items.add(new ClipLibrary.Item(file, modified, ClipLibrary.bytes(file), ClipLibraryScreen.findReplay(file) != null,
               file.getParent().equals(edited), library.favourite(file), library.server(file)));
         }
      }

      items.sort((a, b) -> Long.compare(b.modified(), a.modified()));
      return items;
   }

   /** The server written in a clip's replay, or "" (read once, then kept in the library file). */
   static String readServer(Path clip) {
      Path replay = ClipLibraryScreen.findReplay(clip);
      if (replay == null) {
         return "";
      }

      try (ReplayLog.Reader reader = new ReplayLog.Reader(replay)) {
         return reader.header().getOrDefault("server", "");
      } catch (IOException | RuntimeException e) {
         return "";
      }
   }

   /** Deletes clips over the storage limits in the background (called when a clip is saved and when the library opens). */
   public static void enforceLater() {
      WORKER.execute(() -> {
         try {
            int deleted = enforce();
            if (deleted > 0) {
               KillClipperClient.LOGGER.info("Storage limit: deleted {} old clips", deleted);
            }
         } catch (Exception e) {
            KillClipperClient.LOGGER.warn("Storage clean-up failed", e);
         }
      });
   }

   /** Deletes the clips the limits pick; returns how many. Never favourites, edited videos or the replay that is open. */
   static int enforce() throws IOException {
      ClipLibrary library = ClipLibrary.load(clipsDir());
      if (library.maxGb() <= 0.0 && library.maxDays() <= 0) {
         return 0;
      }

      return deleteAll(library, overLimits(library, scan(library)));
   }

   static List<ClipLibrary.Item> overLimits(ClipLibrary library, List<ClipLibrary.Item> items) {
      return ClipLibrary.overLimits(items, (long)(library.maxGb() * 1024 * 1024 * 1024), library.maxDays(), System.currentTimeMillis());
   }

   static int deleteAll(ClipLibrary library, List<ClipLibrary.Item> items) {
      ReplayPlayer open = ReplayPlayer.active();
      Path openFile = open == null ? null : open.file().toAbsolutePath().normalize();
      int deleted = 0;
      for (ClipLibrary.Item item : items) {
         Path replay = ClipLibraryScreen.findReplay(item.file());
         if (openFile != null && replay != null && replay.toAbsolutePath().normalize().equals(openFile)) {
            continue;
         }

         if (library.delete(item.file())) {
            deleted++;
         }
      }

      return deleted;
   }

   static String size(long bytes) {
      if (bytes >= 1024L * 1024 * 1024) {
         return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
      }

      return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
   }
}
