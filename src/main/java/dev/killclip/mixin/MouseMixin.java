package dev.killclip.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.killclip.client.ReplayPlayer;
import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** During a replay the mouse turns the spectator camera instead of the recorded player. */
@Mixin(Mouse.class)
public abstract class MouseMixin {
   @WrapOperation(
      method = "updateMouse",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V")
   )
   private void killclip$look(ClientPlayerEntity player, double dx, double dy, Operation<Void> original) {
      ReplayPlayer replay = ReplayPlayer.active();
      if (replay == null) {
         original.call(player, dx, dy);
      } else {
         replay.turnCamera(dx, dy);
      }
   }
}
