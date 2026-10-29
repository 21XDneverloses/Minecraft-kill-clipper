package dev.killclip.client;

import dev.killclip.core.ReplayLog;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.state.ConfigurationStates;
import net.minecraft.network.state.NetworkState;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.registry.DynamicRegistryManager;

/** Dev check: decodes every packet in a saved replay to prove it can be played back. */
@Environment(EnvType.CLIENT)
final class ReplayCheck {
   private ReplayCheck() {
   }

   static String verify(Path replay, DynamicRegistryManager registries) throws IOException {
      NetworkState<?> config = ConfigurationStates.S2C;
      NetworkState<?> play = PlayStateFactories.S2C.bind(RegistryByteBuf.makeFactory(registries));
      NetworkState<?> current = null;
      int packets = 0;
      int self = 0;
      int failed = 0;
      String firstError = "";
      try (ReplayLog.Reader reader = new ReplayLog.Reader(replay)) {
         for (ReplayLog.Entry entry = reader.next(); entry != null; entry = reader.next()) {
            if (entry.kind() == ReplayLog.PHASE) {
               NetworkPhase phase = NetworkPhase.values()[entry.data()[0]];
               current = phase == NetworkPhase.PLAY ? play : phase == NetworkPhase.CONFIGURATION ? config : null;
            } else if (entry.kind() == ReplayLog.PACKET && current != null) {
               try {
                  current.codec().decode(Unpooled.wrappedBuffer(entry.data()));
                  packets++;
               } catch (RuntimeException e) {
                  if (failed++ == 0) {
                     firstError = e.toString();
                  }
               }
            } else if (entry.kind() == ReplayLog.SELF) {
               self++;
            }
         }
      }

      return packets + " packets decoded, " + self + " own-state ticks, " + failed + " failed" + (failed > 0 ? " (first: " + firstError + ")" : "");
   }
}
