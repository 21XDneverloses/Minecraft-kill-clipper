package dev.killclip.client;

import dev.killclip.core.ClipLibrary;
import dev.killclip.core.ClipMeta;
import dev.killclip.core.VideoTools;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/**
 * Small pictures of each clip for the library: a frame from the moment of the kill, made once with FFmpeg and kept
 * in clips/.meta/thumbs, then loaded as a texture while the library is open.
 */
@Environment(EnvType.CLIENT)
final class ClipThumbnails {
   private final Map<Path, Identifier> loaded = new HashMap<>();
   /** Clips being made or that have no picture (no FFmpeg yet, or not a video). */
   private final Set<Path> pending = new HashSet<>();
   private int counter;

   /** The texture for a clip, or null while it is being made; starts making it the first time it is asked for. */
   Identifier get(Path clip) {
      Identifier id = this.loaded.get(clip);
      if (id != null || this.pending.contains(clip)) {
         return id;
      }

      Path png = ClipLibrary.thumbnail(clip);
      if (Files.isRegularFile(png)) {
         this.load(clip, png);
         return this.loaded.get(clip);
      }

      String ffmpeg = KillClipperClient.instance().ffmpeg();
      if (ffmpeg == null || !VideoTools.isVideo(clip)) {
         return null;
      }

      this.pending.add(clip);
      ClipStorage.WORKER.execute(() -> {
         boolean made = false;
         try {
            // The moment of the first kill, if the clip says when it was; otherwise a second in.
            double at = ClipMeta.read(clip).stream().findFirst().map(k -> Math.max(0.0, k.at() - 0.15)).orElse(1.0);
            made = VideoTools.thumbnail(ffmpeg, clip, at, png);
         } catch (Exception e) {
            KillClipperClient.LOGGER.debug("No thumbnail for {}", clip, e);
         }

         boolean ok = made;
         MinecraftClient.getInstance().execute(() -> {
            if (ok) {
               this.pending.remove(clip);
               this.load(clip, png);
            }
         });
      });
      return null;
   }

   private void load(Path clip, Path png) {
      try (InputStream in = Files.newInputStream(png)) {
         NativeImage image = NativeImage.read(in);
         Identifier id = Identifier.of("killclipper", "thumb/" + this.counter++);
         MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "Kill Clipper thumbnail", image));
         this.loaded.put(clip, id);
      } catch (Exception e) {
         this.pending.add(clip);
      }
   }

   /** Frees the textures (the PNG files stay for next time). */
   void close() {
      for (Identifier id : this.loaded.values()) {
         MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
      }

      this.loaded.clear();
      this.pending.clear();
   }

   /** Forgets one clip's picture (after a rename or delete). */
   void forget(Path clip) {
      Identifier id = this.loaded.remove(clip);
      if (id != null) {
         MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
      }

      this.pending.remove(clip);
   }
}
