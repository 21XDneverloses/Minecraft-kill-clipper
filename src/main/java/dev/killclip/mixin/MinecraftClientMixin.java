package dev.killclip.mixin;

import dev.killclip.client.KillClipperClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
   /** A replay that ends in a death must not open the death screen (the game opens it whenever no screen is open). */
   @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
      method = "setScreen",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;showsDeathScreen()Z"),
      require = 0
   )
   private boolean killclip$noDeathScreenInReplays(net.minecraft.client.network.ClientPlayerEntity player,
      com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original) {
      return !dev.killclip.client.ReplayPlayer.playing() && original.call(player);
   }

   /** ...and must not ask the replay to respawn you either. */
   @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
      method = "setScreen",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;requestRespawn()V"),
      require = 0
   )
   private void killclip$noRespawnInReplays(net.minecraft.client.network.ClientPlayerEntity player,
      com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
      if (!dev.killclip.client.ReplayPlayer.playing()) {
         original.call(player);
      }
   }

   @Inject(method = "render", at = @At("TAIL"))
   private void killclip$afterFrame(boolean tick, CallbackInfo ci) {
      KillClipperClient client = KillClipperClient.instance();
      if (client != null) {
         client.onFrameRendered((MinecraftClient)(Object)this);
      }
   }
}
