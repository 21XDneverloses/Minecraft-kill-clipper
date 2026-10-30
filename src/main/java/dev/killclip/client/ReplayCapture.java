package dev.killclip.client;

import dev.killclip.core.ReplayLog;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
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
   /** Logs of earlier connections or servers, kept a while so a clip that started there still gets its replay. */
   private static final java.util.ArrayDeque<ReplayLog> retired = new java.util.ArrayDeque<>();
   private static final long RETIRED_KEEP_MS = 180000L;
   /** A kill seen this soon after joining a server was announced on arrival, not fought there. */
   private static final long JUST_JOINED_MS = 5000L;
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

   /**
    * Called on the network thread with each packet's bytes just before the game decodes them. Copying the bytes
    * is far cheaper than writing every packet out again, which slowed down joining servers (thousands of chunks).
    */
   public static void onRawPacket(ChannelHandlerContext context, ByteBuf buf) {
      ReplayLog target;
      NetworkState<?> current;
      synchronized (LOCK) {
         if (log == null || connection == null || context.pipeline().get("packet_handler") != connection) {
            return;
         }

         target = log;
         current = state;
      }

      if (current == null || current.id() != NetworkPhase.PLAY && current.id() != NetworkPhase.CONFIGURATION) {
         return;
      }

      byte[] data = new byte[buf.readableBytes()];
      buf.getBytes(buf.readerIndex(), data);
      target.append(nowMs(), ReplayLog.PACKET, data);
   }

   /** Called on the network thread for every packet, before it is handled. Only singleplayer needs this path. */
   @SuppressWarnings({"unchecked", "rawtypes"})
   public static void onPacket(ClientConnection conn, Packet<?> packet) {
      ReplayLog target;
      NetworkState<?> current;
      synchronized (LOCK) {
         if (conn != connection || log == null || !conn.isLocal()) {
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

   private static float lastLookYaw = Float.NaN;
   private static float lastLookPitch = Float.NaN;

   /**
    * Records where you look on every rendered frame. Ticks are only 20 a second, far too few for PvP aim: played
    * back from ticks alone, flicks and tracking look jittery.
    */
   static void recordLook(MinecraftClient client) {
      ReplayLog target;
      synchronized (LOCK) {
         target = log;
      }

      ClientPlayerEntity player = client.player;
      if (target == null || player == null || client.world == null || state == null || state.id() != NetworkPhase.PLAY) {
         return;
      }

      float yaw = player.getYaw();
      float pitch = player.getPitch();
      if (yaw == lastLookYaw && pitch == lastLookPitch) {
         return;
      }

      lastLookYaw = yaw;
      lastLookPitch = pitch;
      byte[] data = new byte[8];
      int y = Float.floatToIntBits(yaw);
      int p = Float.floatToIntBits(pitch);
      for (int i = 0; i < 4; i++) {
         data[i] = (byte)(y >>> 24 - i * 8);
         data[4 + i] = (byte)(p >>> 24 - i * 8);
      }

      target.append(nowMs(), ReplayLog.LOOK, data);
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

   /**
    * Claims the log of the server the fight happened on, so a server switch or disconnect before the video is
    * saved can't swap or close it. {@code killMs} is when the kill was seen: the log running then is the fight's,
    * not the one covering the clip's start (a short duel starts after the pre-roll, which still shows the lobby).
    * A kill seen just after joining a server was announced on arrival (a win sends you to the lobby first), so the
    * server before it is used. Null when nothing was recorded.
    */
   static ReplayLog.Hold hold(long killMs) {
      synchronized (LOCK) {
         java.util.List<ReplayLog> logs = new java.util.ArrayList<>(retired);
         if (log != null) {
            logs.add(log);
         }

         long[] starts = logs.stream().mapToLong(ReplayLog::startMs).toArray();
         int pick = ReplayLog.fightLog(starts, killMs, JUST_JOINED_MS);
         for (int i = pick; i >= 0; i--) {
            ReplayLog.Hold hold = logs.get(i).hold();
            if (hold != null) {
               return hold;
            }
         }

         return null;
      }
   }

   /** Saves the replay for a clip next to its video. */
   static CompletableFuture<Path> save(long fromMs, long toMs, Path output) {
      ReplayLog.Hold hold = hold(toMs);
      if (hold == null) {
         return CompletableFuture.failedFuture(new IllegalStateException("no server connection was recorded"));
      }

      return save(hold, fromMs, toMs, output);
   }

   /** Saves the replay for a clip from a log claimed with {@link #hold}; the hold is released when done. */
   static CompletableFuture<Path> save(ReplayLog.Hold hold, long fromMs, long toMs, Path output) {

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

      return hold.saveClip(fromMs, toMs, info, output);
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
         // Keep it for clips that started on this server (a win sends you back to the lobby); it closes once
         // no clip save needs it any more.
         retired.addLast(old);
         CompletableFuture.delayedExecutor(RETIRED_KEEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> {
            synchronized (LOCK) {
               retired.remove(old);
            }

            old.closeWhenUnused();
         });
      }
   }

   private static long nowMs() {
      return System.nanoTime() / 1000000L;
   }
}
