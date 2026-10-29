package dev.killclip.client;

import dev.killclip.core.ReplayLog;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.BundlePacket;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.state.NetworkState;

/**
 * Logs the current server connection for fight replays: every clientbound packet, re-encoded with the
 * connection's own codec, plus the player's own position and gear each tick.
 */
@Environment(EnvType.CLIENT)
public final class ReplayCapture {
   /** Gear slots saved for the player's own model in third person. */
   static final EquipmentSlot[] SLOTS = {
      EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
   };
   private static final Object LOCK = new Object();
   private static Path sessionDir;
   private static volatile boolean enabled = true;
   private static ClientConnection connection;
   private static NetworkState<?> state;
   private static ReplayLog log;
   /** Connections created by replay playback, which must never be recorded. */
   private static ClientConnection ignored;
   private static final ItemStack[] lastGear = new ItemStack[SLOTS.length];

   private ReplayCapture() {
   }

   static void init(Path dir) {
      sessionDir = dir;
   }

   static void setEnabled(boolean on) {
      enabled = on;
      if (!on) {
         stop();
      }
   }

   public static void ignore(ClientConnection playback) {
      ignored = playback;
   }

   /** Called when a connection starts decoding a new phase. */
   public static void onTransition(ClientConnection conn, NetworkState<?> next) {
      if (conn == ignored) {
         ReplayPlayer.onTransition(conn, next);
         return;
      }

      if (conn.getSide() != NetworkSide.CLIENTBOUND || sessionDir == null) {
         return;
      }

      synchronized (LOCK) {
         // A server switch behind a proxy re-runs configuration and rebuilds the whole world, so older packets
         // are never needed again: start a fresh log to keep each clip's replay small and quick to save.
         boolean rebuilt = conn == connection && next.id() == NetworkPhase.CONFIGURATION && state != null && state.id() == NetworkPhase.PLAY;
         if (conn != connection || rebuilt) {
            if (!enabled || next.id() != NetworkPhase.CONFIGURATION) {
               return;
            }

            stopLocked();
            try {
               log = new ReplayLog(sessionDir.resolve("session-" + System.nanoTime() + ".bin"), nowMs());
               connection = conn;
            } catch (IOException e) {
               KillClipperClient.LOGGER.warn("Could not start the fight replay log", e);
               return;
            }
         }

         state = next;
         log.append(nowMs(), ReplayLog.PHASE, new byte[]{(byte)next.id().ordinal()});
         if (next.id() == NetworkPhase.PLAY) {
            java.util.Arrays.fill(lastGear, null);
         }
      }
   }

   /** Called on the network thread for every packet, before it is handled. */
   @SuppressWarnings({"unchecked", "rawtypes"})
   public static void onPacket(ClientConnection conn, Packet<?> packet) {
      ReplayLog target;
      NetworkState<?> current;
      synchronized (LOCK) {
         if (conn != connection || log == null) {
            return;
         }

         target = log;
         current = state;
      }

      if (current == null || current.id() != NetworkPhase.PLAY && current.id() != NetworkPhase.CONFIGURATION) {
         return;
      }

      long now = nowMs();
      if (packet instanceof BundlePacket<?> bundle) {
         for (Packet<?> part : bundle.getPackets()) {
            encode(target, (PacketCodec)current.codec(), part, now);
         }
      } else {
         encode(target, (PacketCodec)current.codec(), packet, now);
      }
   }

   private static void encode(ReplayLog target, PacketCodec<ByteBuf, Packet<?>> codec, Packet<?> packet, long now) {
      ByteBuf buf = Unpooled.buffer(256);
      try {
         codec.encode(buf, packet);
         byte[] data = new byte[buf.readableBytes()];
         buf.readBytes(data);
         target.append(now, ReplayLog.PACKET, data);
      } catch (RuntimeException e) {
         // Some modded payloads can't be written back; the replay just skips them.
      } finally {
         buf.release();
      }
   }

   /** Records the player's own state; call once per client tick. */
   static void recordSelf(MinecraftClient client) {
      ReplayLog target;
      synchronized (LOCK) {
         target = log;
      }

      ClientPlayerEntity player = client.player;
      if (target == null || player == null || client.world == null || state == null || state.id() != NetworkPhase.PLAY) {
         return;
      }

      RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(128), client.world.getRegistryManager());
      try {
         buf.writeVarInt(player.getId());
         buf.writeDouble(player.getX());
         buf.writeDouble(player.getY());
         buf.writeDouble(player.getZ());
         buf.writeFloat(player.getYaw());
         buf.writeFloat(player.getPitch());
         buf.writeFloat(player.headYaw);
         buf.writeFloat(player.bodyYaw);
         buf.writeVarInt(player.getPose().ordinal());
         int flags = (player.isOnGround() ? 1 : 0)
            | (player.isSneaking() ? 2 : 0)
            | (player.isSprinting() ? 4 : 0)
            | (player.isUsingItem() ? 8 : 0)
            | (player.getActiveHand() == net.minecraft.util.Hand.OFF_HAND ? 16 : 0)
            | (player.handSwinging ? 32 : 0)
            | (player.preferredHand == net.minecraft.util.Hand.OFF_HAND ? 64 : 0)
            | (player.isDead() ? 128 : 0);
         buf.writeVarInt(flags);
         buf.writeVarInt(player.handSwingTicks);
         int changed = 0;
         ItemStack[] gear = new ItemStack[SLOTS.length];
         for (int i = 0; i < SLOTS.length; i++) {
            gear[i] = player.getEquippedStack(SLOTS[i]);
            if (lastGear[i] == null || !ItemStack.areEqual(lastGear[i], gear[i])) {
               changed |= 1 << i;
            }
         }

         buf.writeVarInt(changed);
         for (int i = 0; i < SLOTS.length; i++) {
            if ((changed & 1 << i) != 0) {
               ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, gear[i]);
               lastGear[i] = gear[i].copy();
            }
         }

         byte[] data = new byte[buf.readableBytes()];
         buf.readBytes(data);
         target.append(nowMs(), ReplayLog.SELF, data);
      } catch (RuntimeException e) {
         KillClipperClient.LOGGER.debug("Could not record own state", e);
      } finally {
         buf.release();
      }
   }

   /** Saves the replay for a clip next to its video. */
   static CompletableFuture<Path> save(long fromMs, long toMs, Path output) {
      ReplayLog target;
      synchronized (LOCK) {
         target = log;
      }

      if (target == null) {
         return CompletableFuture.failedFuture(new IllegalStateException("no server connection was recorded"));
      }

      MinecraftClient client = MinecraftClient.getInstance();
      Map<String, String> info = new LinkedHashMap<>();
      info.put("minecraft", SharedConstants.getGameVersion().name());
      info.put("protocol", String.valueOf(SharedConstants.getProtocolVersion()));
      if (client.player != null) {
         info.put("player", client.player.getGameProfile().name());
         info.put("uuid", client.player.getUuidAsString());
      }

      if (client.getCurrentServerEntry() != null) {
         info.put("server", client.getCurrentServerEntry().address);
      }

      return target.saveClip(fromMs, toMs, info, output);
   }

   static void stop() {
      synchronized (LOCK) {
         stopLocked();
      }
   }

   private static void stopLocked() {
      ReplayLog old = log;
      log = null;
      connection = null;
      state = null;
      if (old != null) {
         // Clip saves still copying from this session finish first; the log closes on its own thread.
         CompletableFuture.runAsync(() -> {
            try {
               Thread.sleep(15000L);
            } catch (InterruptedException ignored) {
            }

            old.close();
         });
      }
   }

   private static long nowMs() {
      return System.nanoTime() / 1000000L;
   }
}
