package dev.killclip.mixin;

import dev.killclip.client.ReplayCapture;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.state.NetworkState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds the server connection to the fight replay log. */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {
   @Inject(method = "transitionInbound", at = @At("HEAD"))
   private void killclip$transition(NetworkState<?> state, PacketListener listener, CallbackInfo ci) {
      ReplayCapture.onTransition((ClientConnection)(Object)this, state);
   }

   @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"))
   private void killclip$read(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
      ReplayCapture.onPacket((ClientConnection)(Object)this, packet);
   }
}
