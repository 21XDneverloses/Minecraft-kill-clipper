package dev.killclip.mixin;

import dev.killclip.client.ReplayPlayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Joining a real server always ends any replay first, so the two can never fight over the game. */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
   @Inject(
      method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;ZLnet/minecraft/client/network/CookieStorage;)V",
      at = @At("HEAD"),
      require = 0
   )
   private static void killclip$endReplay(Screen screen, MinecraftClient client, ServerAddress address, ServerInfo info, boolean quickPlay, CookieStorage cookies, CallbackInfo ci) {
      ReplayPlayer.leaveForRealGame("joining " + address);
   }
}
