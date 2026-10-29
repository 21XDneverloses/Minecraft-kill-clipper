package dev.killclip.mixin;

import dev.killclip.client.KillClipperClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
   @Inject(method = "render", at = @At("TAIL"))
   private void killclip$afterFrame(boolean tick, CallbackInfo ci) {
      KillClipperClient client = KillClipperClient.instance();
      if (client != null) {
         client.onFrameRendered((MinecraftClient)(Object)this);
      }
   }
}
