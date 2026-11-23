package dev.killclip.mixin;

import dev.killclip.client.ReplayCamera;
import dev.killclip.client.ReplayPlayer;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hides your replay stand-in on frames where the camera had to come right up to your head. */
@Mixin(EntityRenderManager.class)
public abstract class EntityRenderManagerMixin {
   @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
   private <E extends Entity> void killclip$hideStand(E entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
      if (ReplayCamera.hidesStand()) {
         ReplayPlayer replay = ReplayPlayer.active();
         if (replay != null && replay.mode() == ReplayPlayer.CameraMode.THIRD_PERSON && replay.isStand(entity)) {
            cir.setReturnValue(false);
         }
      }
   }
}
