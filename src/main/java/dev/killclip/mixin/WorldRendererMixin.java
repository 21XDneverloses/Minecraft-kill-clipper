package dev.killclip.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.killclip.client.ReplayPlayer;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla never draws your own player unless the camera is on it; a replay's free camera needs to see them. */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
   @ModifyExpressionValue(
      method = "fillEntityRenderStates",
      at = @At(value = "CONSTANT", args = "classValue=net/minecraft/client/network/ClientPlayerEntity")
   )
   private boolean killclip$showRecordedPlayer(boolean isLocalPlayer) {
      return isLocalPlayer && !ReplayPlayer.playing();
   }
}
