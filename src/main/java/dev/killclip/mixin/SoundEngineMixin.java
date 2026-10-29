package dev.killclip.mixin;

import dev.killclip.client.GameAudioTap;
import java.nio.IntBuffer;
import net.minecraft.client.sound.SoundEngine;
import org.lwjgl.openal.ALC10;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opens the sound engine on a loopback device when game audio capture is on. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
   @Shadow
   private long devicePointer;

   @Shadow
   private static long openDeviceOrFallback(String deviceSpecifier) {
      throw new AssertionError();
   }

   @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sound/SoundEngine;openDeviceOrFallback(Ljava/lang/String;)J"))
   private long killclip$openDevice(String deviceSpecifier) {
      long loopback = GameAudioTap.openLoopback(deviceSpecifier);
      return loopback != 0L ? loopback : openDeviceOrFallback(deviceSpecifier);
   }

   @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J", remap = false))
   private long killclip$createContext(long device, IntBuffer attributes) {
      if (!GameAudioTap.isLoopback(device)) {
         return ALC10.alcCreateContext(device, attributes);
      }

      IntBuffer withFormat = GameAudioTap.loopbackAttributes(attributes);
      try {
         return ALC10.alcCreateContext(device, withFormat);
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
