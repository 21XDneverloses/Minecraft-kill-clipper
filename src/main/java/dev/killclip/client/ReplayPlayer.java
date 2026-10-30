package dev.killclip.client;

import com.mojang.authlib.GameProfile;
import dev.killclip.core.ReplayLog;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientConfigurationNetworkHandler;
import net.minecraft.client.network.ClientConnectionState;
import net.minecraft.client.network.ClientDynamicRegistryType;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientChunkLoadProgress;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MarkerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.ClearDialogS2CPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackRemoveS2CPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.network.packet.s2c.common.ServerTransferS2CPacket;
import net.minecraft.network.packet.s2c.common.ShowDialogS2CPacket;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenWrittenBookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import net.minecraft.network.packet.s2c.play.SetCameraEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.SignEditorOpenS2CPacket;
import net.minecraft.network.packet.s2c.play.TickStepS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateTickRateS2CPacket;
import net.minecraft.network.state.ConfigurationStates;
import net.minecraft.network.state.NetworkState;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.ServerLinks;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.GameMode;
import org.lwjgl.glfw.GLFW;

/**
 * Plays a saved fight back inside Minecraft: the recorded packets are fed to a real client network handler over
 * an in-memory connection, so the world, players and effects look exactly as they did.
 *
 * <p>The recorded player becomes {@code client.player} and follows the saved movement. The camera can look
 * through their eyes, from behind them, or fly freely as a spectator.
 */
@Environment(EnvType.CLIENT)
public final class ReplayPlayer {
   public enum CameraMode {
      FIRST_PERSON("First person", "1st person"),
      THIRD_PERSON("Third person", "3rd person"),
      SPECTATOR("Spectator (free camera)", "Spectator");

      final String label;
      final String shortLabel;

      CameraMode(String label, String shortLabel) {
         this.label = label;
         this.shortLabel = shortLabel;
      }
   }

   private static ReplayPlayer active;

   private final MinecraftClient client;
   private final Path file;
   private final List<ReplayLog.Entry> entries;
   private final long clipStartMs;
   private final long clipEndMs;
   private final ClientConnection connection;
   private final EmbeddedChannel channel;
   private NetworkState<?> state;
   private int next;
   /** Replay time in ms, on the recording's clock. */
   private long timeMs;
   private long seekTargetMs = -1L;
   private boolean paused;
   private float speed = 1.0F;
   private CameraMode mode = CameraMode.FIRST_PERSON;
   private MarkerEntity freeCamera;
   private final boolean[] keysDown = new boolean[GLFW.GLFW_KEY_LAST + 1];
   private int lastSelfId = -1;
   /** The last applied recording of your own movement: time and {x, y, z, yaw, pitch, headYaw, bodyYaw}. */
   private long lastSelfTimeMs = -1L;
   private double[] lastSelfPose;
   private boolean closed;
   private boolean hidingHud;
   private boolean hudWasHidden;
   final ReplayEdit edit;
   private ReplayEdit.Switch lastSwitch;
   /** Opens the editor bar as soon as the replay has loaded (set when opened from the clip list). */
   boolean openEditorOnLoad;
   /** You moved or turned the spectator camera yourself, so it stays where you put it until the next camera part. */
   private boolean manualCamera;
   /** Current zoom (1 = normal field of view). */
   private float zoom = 1.0F;
   /** Export: the clip is played from the start with the camera switches while the recorder captures it. */
   private String exportFormat;
   private boolean exportVertical;
   private long exportStartRealMs;
   private net.minecraft.network.message.ChatVisibility chatBeforeExport;

   private ReplayPlayer(MinecraftClient client, Path file, List<ReplayLog.Entry> entries, Map<String, String> header) {
      this.client = client;
      this.file = file;
      this.entries = entries;
      this.clipStartMs = parse(header.get("clipStartMs"), 0L);
      this.clipEndMs = parse(header.get("clipEndMs"), entries.isEmpty() ? 0L : entries.getLast().timeMs());
      this.edit = ReplayEdit.load(file);
      this.connection = new ClientConnection(NetworkSide.CLIENTBOUND);
      ReplayCapture.ignore(this.connection);
      // Everything the client tries to send to the "server" is dropped.
      this.channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
         @Override
         public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            ReferenceCountUtil.release(message);
            promise.setSuccess();
         }
      }, this.connection);
      UUID uuid;
      try {
         uuid = UUID.fromString(header.getOrDefault("uuid", ""));
      } catch (IllegalArgumentException e) {
         uuid = UUID.randomUUID();
      }

      GameProfile profile = new GameProfile(uuid, header.getOrDefault("player", "Player"));
      this.connection.transitionInbound(ConfigurationStates.S2C, new ClientConfigurationNetworkHandler(client, this.connection, new ClientConnectionState(
         new ClientChunkLoadProgress(),
         profile,
         client.getTelemetryManager().createWorldSession(false, null, null),
         ClientDynamicRegistryType.createCombinedDynamicRegistries().getCombinedRegistryManager(),
         FeatureFlags.DEFAULT_ENABLED_FEATURES,
         null,
         null,
         null,
         new HashMap<>(),
         null,
         Map.of(),
         ServerLinks.EMPTY,
         Map.of(),
         true
      )));
      this.connection.transitionOutbound(ConfigurationStates.C2S);
      this.state = ConfigurationStates.S2C;
      // Jump to a little before the clip so the world and players are in place when it starts.
      this.seekTargetMs = Math.max(0L, this.clipStartMs);
   }

   private static long parse(String value, long fallback) {
      try {
         return value == null ? fallback : Long.parseLong(value);
      } catch (NumberFormatException e) {
         return fallback;
      }
   }

   /** Opens a replay file and starts playing it. */
   public static void start(MinecraftClient client, Path file) throws IOException {
      stop();
      List<ReplayLog.Entry> entries = new ArrayList<>();
      Map<String, String> header;
      try (ReplayLog.Reader reader = new ReplayLog.Reader(file)) {
         header = new HashMap<>(reader.header());
         for (ReplayLog.Entry entry = reader.next(); entry != null; entry = reader.next()) {
            entries.add(entry);
         }
      }

      if (entries.isEmpty()) {
         throw new IOException("the replay is empty");
      }

      active = new ReplayPlayer(client, file, entries, header);
   }

   public static ReplayPlayer active() {
      return active;
   }

   public static boolean playing() {
      return active != null;
   }

   public static void stop() {
      ReplayPlayer old = active;
      active = null;
      if (old != null) {
         old.close();
      }
   }

   /** The game left the replay world (Disconnect in the pause menu, or the replay closed). */
   static void onDisconnected(ClientConnection conn) {
      ReplayPlayer player = active;
      if (player != null && conn == player.connection) {
         active = null;
         player.closed = true;
         player.restoreView();
         player.channel.close();
      }
   }

   /** The playback connection switched phase; packets after this decode with the new state. */
   static void onTransition(ClientConnection conn, NetworkState<?> next) {
      ReplayPlayer player = active;
      if (player != null && conn == player.connection) {
         player.state = next;
      }
   }

   public Path file() {
      return this.file;
   }

   public long clipLengthMs() {
      return Math.max(0L, this.clipEndMs - this.clipStartMs);
   }

   /** Time into the clip, in ms. */
   public long clipTimeMs() {
      return Math.max(0L, this.timeMs - this.clipStartMs);
   }

   public boolean paused() {
      return this.paused;
   }

   public float speed() {
      return this.speed;
   }

   public CameraMode mode() {
      return this.mode;
   }

   public void setPaused(boolean paused) {
      this.paused = paused;
      if (this.client.world != null) {
         this.client.world.getTickManager().setFrozen(paused);
      }
   }

   public void setSpeed(float speed) {
      this.speed = MathHelper.clamp(speed, 0.125F, 4.0F);
      if (this.client.world != null) {
         this.client.world.getTickManager().setTickRate(20.0F * this.speed);
      }
   }

   /** Jumps to a time in the clip. Going back restarts the replay and fast-forwards. */
   public void seekClip(long clipMs) {
      long target = this.clipStartMs + MathHelper.clamp(clipMs, 0L, this.clipLengthMs());
      if (target >= this.timeMs) {
         this.seekTargetMs = target;
         return;
      }

      CameraMode keepMode = this.mode;
      boolean keepPaused = this.paused;
      float keepSpeed = this.speed;
      String keepExport = this.exportFormat;
      boolean keepVertical = this.exportVertical;
      net.minecraft.network.message.ChatVisibility keepChat = this.chatBeforeExport;
      boolean keepEditor = this.openEditorOnLoad || this.client.currentScreen instanceof ReplayEditorScreen;
      // The restarted replay takes over the export; this one must not undo it when it closes.
      this.exportFormat = null;
      this.chatBeforeExport = null;
      try {
         start(this.client, this.file);
         ReplayPlayer restarted = active;
         restarted.seekTargetMs = target;
         restarted.mode = keepMode;
         restarted.paused = keepPaused;
         restarted.speed = keepSpeed;
         restarted.exportFormat = keepExport;
         restarted.exportVertical = keepVertical;
         restarted.chatBeforeExport = keepChat;
         restarted.openEditorOnLoad = keepEditor && keepExport == null;
      } catch (IOException e) {
         KillClipperClient.LOGGER.warn("Could not restart the replay", e);
         if (keepChat != null) {
            this.client.options.getChatVisibility().setValue(keepChat);
         }
      }
   }

   public void setMode(CameraMode mode) {
      this.mode = mode;
      this.applyCamera();
      this.client.inGameHud.setOverlayMessage(Text.literal("Camera: " + mode.label), false);
   }

   public float zoom() {
      return this.zoom;
   }

   public void setZoom(float zoom) {
      this.zoom = MathHelper.clamp(zoom, 1.0F, 8.0F);
   }

   /** Field of view divisor for the game renderer: 1 unless a replay is zoomed in. */
   public static float fovDivisor() {
      ReplayPlayer player = active;
      return player == null ? 1.0F : player.zoom;
   }

   public boolean exporting() {
      return this.exportFormat != null;
   }

   /** How long the trimmed part takes to play with the speed of each camera part, in real ms. */
   long exportPlayMs() {
      long[] trim = this.trim();
      double total = 0.0;
      for (long t = trim[0]; t < trim[1]; t += 50L) {
         ReplayEdit.Switch current = this.edit.at(t);
         float speed = 1.0F;
         if (current != null) {
            speed = current.speed();
            ReplayEdit.Switch next = this.edit.after(current);
            if (next != null && next.timeMs > current.timeMs) {
               float f = MathHelper.clamp((float)(t - current.timeMs) / (float)(next.timeMs - current.timeMs), 0.0F, 1.0F);
               speed = MathHelper.lerp(f * f * (3.0F - 2.0F * f), current.speed(), next.speed());
            }
         }

         total += Math.min(50L, trim[1] - t) / (double)Math.max(0.125F, speed);
      }

      return (long)total;
   }

   /** The trim range as {start, end} in ms into the clip. */
   long[] trim() {
      return this.edit.trim(this.clipLengthMs());
   }

   void setTrim(long startMs, long endMs) {
      long length = this.clipLengthMs();
      this.edit.trimStartMs = MathHelper.clamp(startMs, 0L, Math.max(0L, length - 500L));
      long end = MathHelper.clamp(endMs, this.edit.trimStartMs + 500L, length);
      this.edit.trimEndMs = end >= length ? 0L : end;
      this.edit.save();
   }

   /**
    * The camera part under the playhead, for changing its POV, speed or zoom. Before the first switch there is
    * none yet, so one is made at the start of the clip with the current camera.
    */
   ReplayEdit.Switch partHere() {
      ReplayEdit.Switch part = this.edit.at(this.clipTimeMs());
      if (part == null) {
         long now = this.clipTimeMs();
         this.addSwitchAt(0L);
         part = this.edit.at(now);
      }

      return part;
   }

   /** Saves a change to a camera part and shows it right away. */
   void changedPart(ReplayEdit.Switch part) {
      this.edit.save();
      this.lastSwitch = null;
      this.manualCamera = false;
      if (this.edit.at(this.clipTimeMs()) == part) {
         this.setMode(part.mode);
         this.setSpeed(part.speed());
         this.setZoom(part.zoom());
         if (part.mode == CameraMode.SPECTATOR && this.freeCamera != null) {
            this.freeCamera.setPosition(part.x, part.y, part.z);
            this.freeCamera.setYaw(part.yaw);
            this.freeCamera.setPitch(part.pitch);
            this.freeCamera.resetPosition();
         }
      }
   }

   /** Moves a spectator part's camera to where the free camera is now. */
   void moveCameraOf(ReplayEdit.Switch part) {
      MarkerEntity camera = this.freeCamera;
      if (camera != null) {
         part.x = camera.getX();
         part.y = camera.getY();
         part.z = camera.getZ();
         part.yaw = camera.getYaw();
         part.pitch = camera.getPitch();
         this.edit.save();
      }
   }

   /** Adds a camera switch at the current time with the current camera (and the free camera's spot). */
   void addSwitchHere() {
      this.addSwitchAt(this.clipTimeMs());
   }

   private void addSwitchAt(long timeMs) {
      MarkerEntity camera = this.freeCamera;
      ClientPlayerEntity player = this.client.player;
      double x = camera != null ? camera.getX() : player != null ? player.getX() : 0.0;
      double y = camera != null ? camera.getY() : player != null ? player.getEyeY() : 0.0;
      double z = camera != null ? camera.getZ() : player != null ? player.getZ() : 0.0;
      float yaw = camera != null ? camera.getYaw() : player != null ? player.getYaw() : 0.0F;
      float pitch = camera != null ? camera.getPitch() : player != null ? player.getPitch() : 0.0F;
      ReplayEdit.Switch added = new ReplayEdit.Switch(timeMs, this.mode, x, y, z, yaw, pitch, this.speed, this.zoom);
      this.edit.put(added);
      this.lastSwitch = added;
   }

   /** Plays the clip from the start with the camera switches and saves it as a new video. */
   void export(String format, boolean vertical) {
      if (this.exportFormat != null) {
         return;
      }

      // Slow-mo makes the export play longer than the clip; check it fits the buffer before playing it all.
      long playMs = this.exportPlayMs();
      int buffer = KillClipperClient.instance().settings().effectiveBufferSeconds();
      if (playMs > buffer * 1000L) {
         this.client.inGameHud.setOverlayMessage(Text.literal("Export plays " + playMs / 1000L + " s but the buffer keeps " + buffer
            + " s. Trim it shorter, use less slow-mo, or raise Buffer in the settings."), false);
         return;
      }

      this.exportFormat = format;
      this.exportVertical = vertical;
      this.exportStartRealMs = 0L;
      this.chatBeforeExport = this.client.options.getChatVisibility().getValue();
      this.client.options.getChatVisibility().setValue(net.minecraft.network.message.ChatVisibility.HIDDEN);
      this.client.setScreen(null);
      this.paused = false;
      this.speed = 1.0F;
      ReplayPlayer before = active;
      this.seekClip(this.trim()[0]);
      if (active == before) {
         this.lastSwitch = null;
      }
   }

   private void finishExport(boolean saved) {
      String format = this.exportFormat;
      long start = this.exportStartRealMs;
      this.exportFormat = null;
      if (this.chatBeforeExport != null) {
         this.client.options.getChatVisibility().setValue(this.chatBeforeExport);
         this.chatBeforeExport = null;
      }

      if (saved && format != null && start > 0L) {
         // Stop just before the "saving" message shows up on screen.
         KillClipperClient.instance().exportEdit(start, System.nanoTime() / 1000000L - 100L, this.file, format, this.exportVertical);
      }
   }

   /** Follows the camera switches while playing; a paused replay leaves the camera to you. */
   private void followSwitches() {
      if (this.edit.switches.isEmpty() || this.paused && !this.exporting()) {
         return;
      }

      ReplayEdit.Switch current = this.edit.at(this.clipTimeMs());
      if (current == null) {
         return;
      }

      if (current != this.lastSwitch) {
         this.lastSwitch = current;
         this.manualCamera = false;
         this.mode = current.mode;
         this.applyCamera();
      }

      // Speed ramps and zoom blend smoothly from this switch to the next one.
      ReplayEdit.Switch next = this.edit.after(current);
      float blend = 0.0F;
      if (next != null && next.timeMs > current.timeMs) {
         float t = MathHelper.clamp((float)(this.clipTimeMs() - current.timeMs) / (float)(next.timeMs - current.timeMs), 0.0F, 1.0F);
         blend = t * t * (3.0F - 2.0F * t);
      }

      float targetSpeed = next == null ? current.speed() : MathHelper.lerp(blend, current.speed(), next.speed());
      if (Math.abs(targetSpeed - this.speed) > 0.001F) {
         this.setSpeed(targetSpeed);
      }

      this.setZoom(next == null ? current.zoom() : MathHelper.lerp(blend, current.zoom(), next.zoom()));
      if (current.mode == CameraMode.SPECTATOR && this.freeCamera != null && !(this.manualCamera && !this.exporting())) {
         double x = current.x;
         double y = current.y;
         double z = current.z;
         float yaw = current.yaw;
         float pitch = current.pitch;
         if (next != null && next.mode == CameraMode.SPECTATOR && next.timeMs > current.timeMs) {
            float t = MathHelper.clamp((float)(this.clipTimeMs() - current.timeMs) / (float)(next.timeMs - current.timeMs), 0.0F, 1.0F);
            float smooth = t * t * (3.0F - 2.0F * t);
            x = MathHelper.lerp(smooth, current.x, next.x);
            y = MathHelper.lerp(smooth, current.y, next.y);
            z = MathHelper.lerp(smooth, current.z, next.z);
            yaw = MathHelper.lerpAngleDegrees(smooth, current.yaw, next.yaw);
            pitch = MathHelper.lerp(smooth, current.pitch, next.pitch);
         }

         boolean jump = current == this.lastSwitch && this.freeCamera.squaredDistanceTo(x, y, z) > 400.0;
         this.freeCamera.setPosition(x, y, z);
         this.freeCamera.setYaw(yaw);
         this.freeCamera.setPitch(pitch);
         if (jump) {
            this.freeCamera.resetPosition();
         }
      }
   }

   /** Called once per client tick. */
   void tick() {
      if (this.closed) {
         return;
      }

      if (this.client.world == null) {
         // Before the world exists the connection isn't ticked by the game.
         this.connection.tick();
      }

      this.handleKeys();
      long target;
      if (this.seekTargetMs >= 0L) {
         target = this.seekTargetMs;
         this.seekTargetMs = -1L;
      } else if (this.paused || this.client.world == null && this.next > 0 && this.timeMs >= this.clipStartMs) {
         target = this.timeMs;
      } else {
         target = this.timeMs + 50L;
      }

      long before = this.timeMs;
      this.feedUntil(target);
      this.timeMs = Math.max(this.timeMs, target);
      this.smoothSelf(this.timeMs);
      if (this.client.world != null) {
         this.client.world.getTickManager().setFrozen(this.paused);
         this.client.world.getTickManager().setTickRate(20.0F * this.speed);
      }

      ClientPlayerEntity player = this.client.player;
      if (player != null) {
         if (!(player.input.getClass() == Input.class)) {
            player.input = new Input();
         }

         if (this.client.interactionManager != null && this.client.interactionManager.getCurrentGameMode() != GameMode.ADVENTURE) {
            this.client.interactionManager.setGameMode(GameMode.ADVENTURE);
         }

         player.noClip = true;
         player.setNoGravity(true);
         this.applyCamera();
         this.moveFreeCamera();
         this.followSwitches();
      }

      long[] trim = this.trim();
      if (this.openEditorOnLoad && !this.exporting() && this.client.world != null && this.client.currentScreen == null
         && this.timeMs >= this.clipStartMs && this.seekTargetMs < 0L) {
         this.openEditorOnLoad = false;
         this.client.setScreen(new ReplayEditorScreen(this));
      }

      if (this.exporting() && this.exportStartRealMs == 0L && this.client.world != null && this.client.currentScreen == null
         && this.timeMs >= this.clipStartMs + trim[0] && this.seekTargetMs < 0L) {
         this.exportStartRealMs = System.nanoTime() / 1000000L;
         this.client.inGameHud.getChatHud().clear(false);
      }

      long end = this.clipStartMs + trim[1];
      if (!this.paused && (this.timeMs >= this.clipEndMs || this.exportStartRealMs > 0L && this.timeMs >= end || before < end && this.timeMs >= end)) {
         this.setPaused(true);
         if (this.exporting()) {
            this.finishExport(true);
         } else {
            this.client.inGameHud.setOverlayMessage(Text.literal("End of the clip. Press E for the editor."), false);
         }
      }
   }

   private void feedUntil(long target) {
      while (this.next < this.entries.size() && this.entries.get(this.next).timeMs() <= target) {
         ReplayLog.Entry entry = this.entries.get(this.next++);
         if (entry.kind() == ReplayLog.PACKET) {
            this.deliver(entry.data());
         } else if (entry.kind() == ReplayLog.SELF) {
            this.applySelf(entry.data());
            this.lastSelfTimeMs = entry.timeMs();
            this.lastSelfPose = readPose(entry.data());
         }

         if (this.closed) {
            return;
         }
      }
   }

   /** x, y, z, yaw, pitch, head yaw and body yaw from a recording of your own movement, or null. */
   private static double[] readPose(byte[] data) {
      ByteBuf buf = Unpooled.wrappedBuffer(data);
      try {
         net.minecraft.network.encoding.VarInts.read(buf);
         return new double[]{buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()};
      } catch (RuntimeException e) {
         return null;
      }
   }

   /**
    * Your movement was recorded once per game tick, but those ticks never line up exactly with playback ticks, so
    * playing the records as they come makes your aim stutter (two records in one tick, none in the next). Instead,
    * place you between the last record and the next one by time, so the aim moves as smoothly as it was played.
    */
   private void smoothSelf(long nowMs) {
      ClientPlayerEntity player = this.client.player;
      double[] from = this.lastSelfPose;
      if (player == null || from == null || this.lastSelfTimeMs < 0L) {
         return;
      }

      for (int i = this.next; i < this.entries.size() && i < this.next + 2000; i++) {
         ReplayLog.Entry entry = this.entries.get(i);
         if (entry.timeMs() - this.lastSelfTimeMs > 500L) {
            return;
         }

         if (entry.kind() != ReplayLog.SELF) {
            continue;
         }

         double[] to = readPose(entry.data());
         long span = entry.timeMs() - this.lastSelfTimeMs;
         if (to == null || span <= 0L) {
            return;
         }

         double dx = to[0] - from[0];
         double dy = to[1] - from[1];
         double dz = to[2] - from[2];
         if (dx * dx + dy * dy + dz * dz > 64.0) {
            // A teleport or respawn: jump, don't glide.
            return;
         }

         float f = MathHelper.clamp((float)(nowMs - this.lastSelfTimeMs) / span, 0.0F, 1.0F);
         player.setPosition(MathHelper.lerp(f, from[0], to[0]), MathHelper.lerp(f, from[1], to[1]), MathHelper.lerp(f, from[2], to[2]));
         player.setYaw(MathHelper.lerpAngleDegrees(f, (float)from[3], (float)to[3]));
         player.setPitch(MathHelper.lerp(f, (float)from[4], (float)to[4]));
         player.setHeadYaw(MathHelper.lerpAngleDegrees(f, (float)from[5], (float)to[5]));
         player.setBodyYaw(MathHelper.lerpAngleDegrees(f, (float)from[6], (float)to[6]));
         return;
      }
   }

   @SuppressWarnings("unchecked")
   private void deliver(byte[] data) {
      NetworkState<?> current = this.state;
      if (current == null) {
         return;
      }

      Packet<?> packet;
      try {
         packet = (Packet<?>)((net.minecraft.network.codec.PacketCodec<ByteBuf, ?>)current.codec()).decode(Unpooled.wrappedBuffer(data));
      } catch (RuntimeException e) {
         return;
      }

      if (skipped(packet)) {
         return;
      }

      try {
         this.channel.pipeline().fireChannelRead(packet);
         this.channel.checkException();
      } catch (Throwable e) {
         KillClipperClient.LOGGER.debug("Replay packet {} failed", packet.getPacketType(), e);
      }
   }

   /** Packets that would kick you, open menus or change your own controls are left out of playback. */
   private static boolean skipped(Packet<?> packet) {
      if (packet instanceof GameStateChangeS2CPacket change) {
         GameStateChangeS2CPacket.Reason reason = change.getReason();
         return reason == GameStateChangeS2CPacket.GAME_MODE_CHANGED || reason == GameStateChangeS2CPacket.GAME_WON
            || reason == GameStateChangeS2CPacket.DEMO_MESSAGE_SHOWN;
      }

      return packet instanceof net.minecraft.network.packet.BundleSplitterPacket<?>
         || packet instanceof DisconnectS2CPacket
         || packet instanceof ServerTransferS2CPacket
         || packet instanceof ResourcePackSendS2CPacket
         || packet instanceof ResourcePackRemoveS2CPacket
         || packet instanceof ShowDialogS2CPacket
         || packet instanceof ClearDialogS2CPacket
         || packet instanceof DeathMessageS2CPacket
         || packet instanceof OpenScreenS2CPacket
         || packet instanceof OpenWrittenBookS2CPacket
         || packet instanceof SignEditorOpenS2CPacket
         || packet instanceof CloseScreenS2CPacket
         || packet instanceof PlayerAbilitiesS2CPacket
         || packet instanceof SetCameraEntityS2CPacket
         || packet instanceof UpdateTickRateS2CPacket
         || packet instanceof TickStepS2CPacket;
   }

   /** Moves the recorded player to where they were on this tick. */
   private void applySelf(byte[] data) {
      ClientPlayerEntity player = this.client.player;
      if (player == null || this.client.world == null) {
         return;
      }

      RegistryByteBuf buf = new RegistryByteBuf(Unpooled.wrappedBuffer(data), this.client.world.getRegistryManager());
      try {
         buf.readVarInt();
         double x = buf.readDouble();
         double y = buf.readDouble();
         double z = buf.readDouble();
         float yaw = buf.readFloat();
         float pitch = buf.readFloat();
         float headYaw = buf.readFloat();
         float bodyYaw = buf.readFloat();
         int pose = buf.readVarInt();
         int flags = buf.readVarInt();
         int swingTicks = buf.readVarInt();
         int changed = buf.readVarInt();
         boolean jump = this.lastSelfId != player.getId() || player.squaredDistanceTo(x, y, z) > 64.0;
         this.lastSelfId = player.getId();
         player.setPosition(x, y, z);
         player.setVelocity(0.0, 0.0, 0.0);
         player.setYaw(yaw);
         player.setPitch(pitch);
         player.setHeadYaw(headYaw);
         player.setBodyYaw(bodyYaw);
         if (jump) {
            // A respawn or teleport: don't slide across the map.
            player.lastX = player.lastRenderX = x;
            player.lastY = player.lastRenderY = y;
            player.lastZ = player.lastRenderZ = z;
            player.lastYaw = yaw;
            player.lastPitch = pitch;
            player.lastHeadYaw = headYaw;
            player.lastBodyYaw = bodyYaw;
         }

         EntityPose[] poses = EntityPose.values();
         if (pose >= 0 && pose < poses.length) {
            player.setPose(poses[pose]);
         }

         player.setSneaking((flags & 2) != 0);
         player.setSprinting((flags & 4) != 0);
         boolean swinging = (flags & 32) != 0;
         if (swinging && swingTicks <= 1) {
            Hand hand = (flags & 64) != 0 ? Hand.OFF_HAND : Hand.MAIN_HAND;
            player.handSwinging = true;
            player.handSwingTicks = -1;
            player.preferredHand = hand;
         }

         for (int i = 0; i < ReplayCapture.SLOTS.length; i++) {
            if ((changed & 1 << i) != 0) {
               ItemStack stack = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);
               player.equipStack(ReplayCapture.SLOTS[i], stack);
            }
         }
      } catch (RuntimeException e) {
         KillClipperClient.LOGGER.debug("Bad own-state record in replay", e);
      } finally {
         buf.release();
      }
   }

   private void applyCamera() {
      ClientPlayerEntity player = this.client.player;
      if (player == null || this.client.world == null) {
         return;
      }

      switch (this.mode) {
         case FIRST_PERSON -> {
            this.client.setCameraEntity(player);
            this.client.options.setPerspective(Perspective.FIRST_PERSON);
         }
         case THIRD_PERSON -> {
            this.client.setCameraEntity(player);
            this.client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
         }
         case SPECTATOR -> {
            if (this.freeCamera == null || this.freeCamera.getEntityWorld() != this.client.world) {
               this.freeCamera = new MarkerEntity(EntityType.MARKER, this.client.world);
               // Start a few blocks behind and above the player, looking at them.
               net.minecraft.util.math.Vec3d look = player.getRotationVector(0.0F, player.getYaw());
               this.freeCamera.refreshPositionAndAngles(player.getX() - look.x * 4.0, player.getEyeY() + 1.0, player.getZ() - look.z * 4.0, player.getYaw(), 15.0F);
               this.freeCamera.resetPosition();
            }

            this.client.options.setPerspective(Perspective.FIRST_PERSON);
            this.client.setCameraEntity(this.freeCamera);
         }
      }

      // The free camera shows a clean picture: no hand and no HUD.
      boolean free = this.mode == CameraMode.SPECTATOR;
      if (free != this.hidingHud) {
         if (free) {
            this.hudWasHidden = this.client.options.hudHidden;
            this.client.options.hudHidden = true;
         } else {
            this.client.options.hudHidden = this.hudWasHidden;
         }

         this.hidingHud = free;
      }
   }

   /** Spectator camera: WASD to move, Space up, Shift down, Ctrl faster. Mouse looks around. */
   private void moveFreeCamera() {
      MarkerEntity camera = this.freeCamera;
      boolean screenAllows = this.client.currentScreen == null || this.client.currentScreen instanceof ReplayEditorScreen;
      if (this.mode != CameraMode.SPECTATOR || camera == null || !screenAllows || this.exporting()) {
         if (camera != null) {
            camera.resetPosition();
         }

         return;
      }

      camera.resetPosition();
      double forward = (key(this.client.options.forwardKey) ? 1 : 0) - (key(this.client.options.backKey) ? 1 : 0);
      double strafe = (key(this.client.options.leftKey) ? 1 : 0) - (key(this.client.options.rightKey) ? 1 : 0);
      double up = (key(this.client.options.jumpKey) ? 1 : 0) - (key(this.client.options.sneakKey) ? 1 : 0);
      double speed = key(this.client.options.sprintKey) ? 1.2 : 0.5;
      if (forward == 0 && strafe == 0 && up == 0) {
         return;
      }

      this.manualCamera = true;
      double yaw = Math.toRadians(camera.getYaw());
      double dx = (-Math.sin(yaw) * forward + Math.cos(yaw) * strafe) * speed;
      double dz = (Math.cos(yaw) * forward + Math.sin(yaw) * strafe) * speed;
      camera.setPosition(camera.getX() + dx, camera.getY() + up * speed, camera.getZ() + dz);
   }

   private boolean key(net.minecraft.client.option.KeyBinding binding) {
      if (this.client.currentScreen == null) {
         return binding.isPressed();
      }

      // Key bindings don't update while a screen is open, so read the bound key itself (editor open).
      InputUtil.Key bound = InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey());
      return bound.getCategory() == InputUtil.Type.KEYSYM && bound.getCode() >= 0 && InputUtil.isKeyPressed(this.client.getWindow(), bound.getCode());
   }

   /** Turns the spectator camera with the mouse; returns false when the mouse should do nothing. */
   public boolean turnCamera(double dx, double dy) {
      if (this.mode == CameraMode.SPECTATOR && this.freeCamera != null && !this.exporting() && (dx != 0.0 || dy != 0.0)) {
         this.freeCamera.changeLookDirection(dx, dy);
         this.manualCamera = true;
      }

      return false;
   }

   /** V camera, P pause, arrows seek and change speed. */
   private void handleKeys() {
      if (this.client.currentScreen != null || this.exporting()) {
         return;
      }

      if (pressed(GLFW.GLFW_KEY_E)) {
         this.client.setScreen(new ReplayEditorScreen(this));
         return;
      }

      if (pressed(GLFW.GLFW_KEY_V)) {
         this.setMode(CameraMode.values()[(this.mode.ordinal() + 1) % CameraMode.values().length]);
      }

      if (pressed(GLFW.GLFW_KEY_C) && this.mode == CameraMode.SPECTATOR) {
         this.moveCameraOf(this.partHere());
         this.client.inGameHud.setOverlayMessage(Text.literal("Saved this camera spot to the part"), false);
      }

      if (pressed(GLFW.GLFW_KEY_P)) {
         this.setPaused(!this.paused);
         this.client.inGameHud.setOverlayMessage(Text.literal(this.paused ? "Paused" : "Playing"), false);
      }

      if (pressed(GLFW.GLFW_KEY_RIGHT)) {
         this.seekClip(this.clipTimeMs() + 5000L);
      }

      if (pressed(GLFW.GLFW_KEY_LEFT)) {
         this.seekClip(this.clipTimeMs() - 5000L);
      }

      if (pressed(GLFW.GLFW_KEY_UP)) {
         this.setSpeed(this.speed * 2.0F);
         this.client.inGameHud.setOverlayMessage(Text.literal("Speed: " + this.speed + "x"), false);
      }

      if (pressed(GLFW.GLFW_KEY_DOWN)) {
         this.setSpeed(this.speed / 2.0F);
         this.client.inGameHud.setOverlayMessage(Text.literal("Speed: " + this.speed + "x"), false);
      }
   }

   private boolean pressed(int key) {
      boolean down = InputUtil.isKeyPressed(this.client.getWindow(), key);
      boolean was = this.keysDown[key];
      this.keysDown[key] = down;
      return down && !was;
   }

   private void close() {
      if (this.closed) {
         return;
      }

      this.closed = true;
      if (this.client.world != null && this.client.getNetworkHandler() != null && this.client.getNetworkHandler().getConnection() == this.connection) {
         this.client.disconnect(new ClipLibraryScreen(new net.minecraft.client.gui.screen.TitleScreen()), false);
      }

      this.restoreView();
      this.channel.close();
   }

   private void restoreView() {
      if (this.chatBeforeExport != null) {
         this.client.options.getChatVisibility().setValue(this.chatBeforeExport);
         this.chatBeforeExport = null;
         this.exportFormat = null;
      }

      this.client.options.setPerspective(Perspective.FIRST_PERSON);
      if (this.hidingHud) {
         this.client.options.hudHidden = this.hudWasHidden;
         this.hidingHud = false;
      }
   }
}
