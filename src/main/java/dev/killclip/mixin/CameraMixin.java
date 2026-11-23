package dev.killclip.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.killclip.client.ReplayCamera;
import dev.killclip.client.ReplayPlayer;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** In a replay's third person, the camera finds a clear angle instead of ending up inside you or your opponent. */
@Mixin(Camera.class)
public abstract class CameraMixin {
   @Shadow
   private World area;
   @Shadow
   private Entity focusedEntity;
   @Shadow
   private Vec3d pos;
   @Shadow
   private float yaw;
   @Shadow
   private float pitch;

   @Shadow
   protected abstract void setRotation(float yaw, float pitch);

   @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;clipToSpace(F)F"), require = 0)
   private float killclip$replayCamera(Camera camera, float wanted, Operation<Float> original) {
      ReplayPlayer replay = ReplayPlayer.active();
      if (replay == null || this.area == null || !replay.guidesCamera(this.focusedEntity)) {
         return original.call(camera, wanted);
      }

      return ReplayCamera.place(this.area, this.focusedEntity, this.pos, this.yaw, this.pitch, wanted, this::setRotation,
         distance -> original.call(camera, distance));
   }
}
