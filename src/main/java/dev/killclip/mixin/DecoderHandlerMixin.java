package dev.killclip.mixin;

import dev.killclip.client.ReplayCapture;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.handler.DecoderHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Copies each incoming packet's bytes for the fight replay before the game decodes them. */
@Mixin(DecoderHandler.class)
public abstract class DecoderHandlerMixin {
   @Inject(method = "decode", at = @At("HEAD"), require = 0)
   private void killclip$copy(ChannelHandlerContext context, ByteBuf buf, List<Object> objects, CallbackInfo ci) {
      ReplayCapture.onRawPacket(context, buf);
   }
}
