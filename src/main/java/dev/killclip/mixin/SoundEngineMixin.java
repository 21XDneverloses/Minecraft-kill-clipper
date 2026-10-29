package dev.killclip.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.killclip.client.GameAudioTap;
import java.nio.IntBuffer;
import net.minecraft.client.sound.SoundEngine;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Opens the sound engine on a loopback device when game audio capture is on.
 *
 * <p>Uses only injectors whose {@code at} is an array ({@code @Inject}, {@code @WrapOperation}). Loom writes
 * every {@code at} as an array, and older MixinExtras versions crash on a {@code @Redirect} written that way.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
   @Shadow
   private long devicePointer;

   @Inject(method = "openDeviceOrFallback", at = @At("HEAD"), cancellable = true)
   private static void killclip$openDevice(String deviceSpecifier, CallbackInfoReturnable<Long> cir) {
      long loopback = GameAudioTap.openLoopback(deviceSpecifier);
      if (loopback != 0L) {
         cir.setReturnValue(loopback);
      }
   }

   @WrapOperation(
      method = "init",
      at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J", remap = false)
   )
   private long killclip$createContext(long device, IntBuffer attributes, Operation<Long> original) {
      if (!GameAudioTap.isLoopback(device)) {
         return original.call(device, attributes);
      }
      IntBuffer withFormat = GameAudioTap.loopbackAttributes(attributes);
      try {
         return original.call(device, withFormat);
      } finally {
         MemoryUtil.memFree(withFormat);
      }
   }

   @Inject(method = "init", at = @At("TAIL"))
   private void killclip$startTap(String deviceSpecifier, boolean directionalAudio, CallbackInfo ci) {
      GameAudioTap.start(this.devicePointer);
   }

   @Inject(method = "close", at = @At("HEAD"))
   private void killclip$stopTap(CallbackInfo ci) {
      GameAudioTap.stop();
   }
}
