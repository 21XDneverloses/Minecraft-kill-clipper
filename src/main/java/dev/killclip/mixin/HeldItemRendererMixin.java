package dev.killclip.mixin;

import dev.killclip.client.ReplayCapture;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the first-person "use" bob of your hand (placing crystals and blocks) for replays. */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
   @Inject(method = "resetEquipProgress", at = @At("HEAD"), require = 0)
   private void killclip$use(Hand hand, CallbackInfo ci) {
      ReplayCapture.onOwnUse(hand);
   }
}
