package dev.killclip.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.killclip.client.ReplayPlayer;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Zoom for the clip editor, and aim that follows the recording every frame during a replay. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
   @org.spongepowered.asm.mixin.injection.Inject(method = "render", at = @At("HEAD"), require = 0)
   private void killclip$replayAim(net.minecraft.client.render.RenderTickCounter tickCounter, boolean tick,
      org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
      ReplayPlayer replay = ReplayPlayer.active();
      if (replay != null) {
         replay.onFrame(tickCounter.getTickProgress(true));
      }
   }

   @ModifyReturnValue(method = "getFov", at = @At("RETURN"), require = 0)
   private float killclip$zoom(float fov) {
      float zoom = ReplayPlayer.fovDivisor();
      return zoom == 1.0F ? fov : fov / zoom;
   }
}
