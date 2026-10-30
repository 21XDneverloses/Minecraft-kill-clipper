package dev.killclip.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.killclip.client.ReplayPlayer;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Zoom for the clip editor: narrows the field of view while a replay is zoomed in. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
   @ModifyReturnValue(method = "getFov", at = @At("RETURN"), require = 0)
   private float killclip$zoom(float fov) {
      float zoom = ReplayPlayer.fovDivisor();
      return zoom == 1.0F ? fov : fov / zoom;
   }
}
