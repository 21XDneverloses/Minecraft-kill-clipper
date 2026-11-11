package dev.killclip.mixin;

import dev.killclip.client.ReplayCapture;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records your own arm swings for replays: the server never sends you your own swing. */
@Mixin(ClientPlayerEntity.class)
public abstract class HandSwingMixin {
   @Inject(method = "swingHand(Lnet/minecraft/util/Hand;)V", at = @At("HEAD"), require = 0)
   private void killclip$swing(Hand hand, CallbackInfo ci) {
      ReplayCapture.onOwnSwing(hand);
   }
}
