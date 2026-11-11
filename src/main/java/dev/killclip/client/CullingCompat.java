package dev.killclip.client;

import java.lang.reflect.Method;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

/**
 * Keeps entity-culling mods (EntityCulling) from hiding players during a replay. They decide what is hidden from
 * the camera a few frames late, so when the replay camera jumps or flies (spectator, seeking, export) players blink
 * out. Uses the mod's own "show this entity for now" call when it is installed; does nothing otherwise.
 */
@Environment(EnvType.CLIENT)
final class CullingCompat {
   private static final String[] CULLABLE = {
      "dev.tr7zw.entityculling.versionless.access.Cullable",
      "dev.tr7zw.entityculling.access.Cullable"
   };
   private static Class<?> cullable;
   private static Method setTimeout;
   private static boolean looked;
   private static boolean failed;

   private CullingCompat() {
   }

   static void keepVisible(MinecraftClient client) {
      if (client.world == null || failed || !find()) {
         return;
      }

      try {
         for (Entity entity : client.world.getEntities()) {
            if (cullable.isInstance(entity)) {
               setTimeout.invoke(entity);
            }
         }
      } catch (ReflectiveOperationException | RuntimeException e) {
         failed = true;
         KillClipperClient.LOGGER.warn("Could not keep players visible with the entity culling mod: {}", e.toString());
      }
   }

   private static boolean find() {
      if (!looked) {
         looked = true;
         for (String name : CULLABLE) {
            try {
               Class<?> type = Class.forName(name, false, CullingCompat.class.getClassLoader());
               setTimeout = type.getMethod("setTimeout");
               cullable = type;
               KillClipperClient.LOGGER.info("Replays keep players visible with {}", name);
               break;
            } catch (ReflectiveOperationException | LinkageError ignored) {
            }
         }
      }

      return cullable != null;
   }
}
