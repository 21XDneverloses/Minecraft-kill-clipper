package dev.killclip.mixin;

import dev.killclip.client.KillClipperClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server sends entity status 3 when a player dies. Hit tracking uses it to see deaths of players you hit
 * even on servers that hide other players' health, where a dead player never shows 0 health.
 */
@Mixin(LivingEntity.class)
public abstract class PlayerDeathStatusMixin {
   @Inject(method = "handleStatus(B)V", at = @At("HEAD"), require = 0)
   private void killclip$deathStatus(byte status, CallbackInfo ci) {
      if (status == 3 && (Object)this instanceof OtherClientPlayerEntity player && KillClipperClient.instance() != null) {
         KillClipperClient.instance().onPlayerDeathStatus(player.getGameProfile().name());
      }
   }
}
