package dev.killclip.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * The third-person camera in replays. Minecraft's own third-person camera only backs away from blocks: with a wall,
 * a ceiling or a hole edge behind you it slides right up to your head, and it goes straight through other players,
 * so in a close fight it often ends up inside your body or inside your opponent's. In a replay it instead swings round
 * (up or to the side) to the nearest angle where it has room, stops in front of players and crystals instead of inside
 * them, eases between angles instead of jumping, and hides your stand-in on the rare frames it still has to sit at
 * your head. Your own game's third person is untouched.
 */
@Environment(EnvType.CLIENT)
public final class ReplayCamera {
   /**
    * Orbit offsets to try, {yaw, pitch} in degrees from straight behind you, best first: a little higher, then to
    * either side, then higher still.
    */
   private static final float[][] ANGLES = {
      {0.0F, 0.0F}, {0.0F, 15.0F}, {-30.0F, 10.0F}, {30.0F, 10.0F}, {0.0F, 30.0F}, {-55.0F, 15.0F}, {55.0F, 15.0F},
      {-30.0F, 35.0F}, {30.0F, 35.0F}, {0.0F, 50.0F}, {-90.0F, 20.0F}, {90.0F, 20.0F}, {-120.0F, 35.0F}, {120.0F, 35.0F}
   };
   /** An angle counts as clear when the camera gets at least this share of its normal distance there. */
   private static final float ROOM = 0.85F;
   /** How far the camera eases towards its chosen angle each frame. */
   private static final float EASE = 0.12F;
   /** Frames the straight-behind view must stay clear before the camera swings back to it, so it doesn't flicker. */
   private static final int BACK_AFTER_FRAMES = 15;
   /** Closer than this to your eyes, the stand-in is hidden so the picture isn't the inside of your head. */
   private static final float HIDE_STAND_WITHIN = 0.9F;
   /** Room kept between the camera and a player or crystal it stops in front of. */
   private static final double ENTITY_MARGIN = 0.3;

   /** Turns the camera to an angle. */
   public interface Rotator {
      void rotate(float yaw, float pitch);
   }

   /** Minecraft's block check: how far back the camera can go, up to the given distance, at the current angle. */
   public interface BlockClip {
      float clip(float distance);
   }

   private static Entity lastFocused;
   private static Vec3d lastEye = Vec3d.ZERO;
   private static int target;
   private static float yawOffset;
   private static float pitchOffset;
   private static float distance = -1.0F;
   private static int clearFrames;
   private static boolean standHidden;

   private ReplayCamera() {
   }

   /** The stand-in is hidden this frame because the camera had to come right up to your head. */
   public static boolean hidesStand() {
      return standHidden;
   }

   /** Forgets the camera's angle, for a new replay or a cut. */
   public static void reset() {
      lastFocused = null;
      target = 0;
      yawOffset = 0.0F;
      pitchOffset = 0.0F;
      distance = -1.0F;
      clearFrames = 0;
      standHidden = false;
   }

   /**
    * Places the camera for this frame. The camera already sits at your eyes, facing your yaw and pitch; this turns it
    * to the angle it should film from and returns how far back it goes (Minecraft then moves it back that far).
    */
   public static float place(World world, Entity focused, Vec3d eye, float yaw, float pitch, float wanted, Rotator rotator, BlockClip blocks) {
      if (focused != lastFocused || distance < 0.0F || eye.squaredDistanceTo(lastEye) > 64.0) {
         // A new replay, a jump in time or a teleport: start from straight behind, with no easing from the old spot.
         reset();
         lastFocused = focused;
      }

      lastEye = eye;
      float need = wanted * ROOM;
      boolean behindClear = room(world, focused, eye, yaw, pitch, 0, wanted, rotator, blocks) >= need;
      clearFrames = behindClear ? clearFrames + 1 : 0;
      if (target != 0 && clearFrames >= BACK_AFTER_FRAMES) {
         target = 0;
      } else if (!behindClear && (target == 0 || room(world, focused, eye, yaw, pitch, target, wanted, rotator, blocks) < need)) {
         target = pick(world, focused, eye, yaw, pitch, wanted, need, rotator, blocks);
      }

      boolean jump = distance < 0.0F;
      yawOffset = jump ? ANGLES[target][0] : yawOffset + MathHelper.wrapDegrees(ANGLES[target][0] - yawOffset) * EASE;
      pitchOffset = jump ? ANGLES[target][1] : pitchOffset + (ANGLES[target][1] - pitchOffset) * EASE;
      float finalYaw = yaw + yawOffset;
      float finalPitch = clampPitch(pitch + pitchOffset);
      rotator.rotate(finalYaw, finalPitch);
      float clear = Math.min(blocks.clip(wanted), entityClip(world, focused, eye, finalYaw, finalPitch, wanted));
      // Pull in at once (never through a wall or a player), but back out smoothly.
      distance = jump || clear < distance ? clear : Math.min(clear, distance + wanted * 0.04F);
      standHidden = distance < HIDE_STAND_WITHIN;
      return distance;
   }

   /** The first angle with room, or the one with the most room if none has enough. */
   private static int pick(World world, Entity focused, Vec3d eye, float yaw, float pitch, float wanted, float need, Rotator rotator, BlockClip blocks) {
      int best = 0;
      float bestRoom = -1.0F;
      for (int i = 1; i < ANGLES.length; i++) {
         float room = room(world, focused, eye, yaw, pitch, i, wanted, rotator, blocks);
         if (room >= need) {
            return i;
         }

         if (room > bestRoom + 0.05F) {
            best = i;
            bestRoom = room;
         }
      }

      return best;
   }

   private static float room(World world, Entity focused, Vec3d eye, float yaw, float pitch, int angle, float wanted, Rotator rotator, BlockClip blocks) {
      float y = yaw + ANGLES[angle][0];
      float p = clampPitch(pitch + ANGLES[angle][1]);
      rotator.rotate(y, p);
      return Math.min(blocks.clip(wanted), entityClip(world, focused, eye, y, p, wanted));
   }

   /** How far back the camera can go before it would be inside a player, mob or crystal. */
   static float entityClip(World world, Entity focused, Vec3d eye, float yaw, float pitch, float wanted) {
      Vec3d back = Vec3d.fromPolar(pitch, yaw).multiply(-1.0);
      Vec3d end = eye.add(back.multiply(wanted + ENTITY_MARGIN));
      float clear = wanted;
      for (Entity entity : world.getOtherEntities(focused, new Box(eye, end).expand(1.0), ReplayCamera::blocksView)) {
         Box box = entity.getBoundingBox().expand(ENTITY_MARGIN);
         if (box.contains(eye)) {
            // Standing inside each other already: nothing the camera can do about that one.
            continue;
         }

         var hit = box.raycast(eye, end);
         if (hit.isPresent()) {
            clear = Math.min(clear, (float) hit.get().distanceTo(eye));
         }
      }

      return Math.max(0.0F, clear);
   }

   private static boolean blocksView(Entity entity) {
      ReplayPlayer replay = ReplayPlayer.active();
      if (entity == MinecraftClient.getInstance().player || replay != null && replay.isStand(entity) || entity.isInvisible()) {
         return false;
      }

      return entity instanceof LivingEntity living && !living.isSpectator() && living.isAlive() || entity instanceof EndCrystalEntity;
   }

   private static float clampPitch(float pitch) {
      return MathHelper.clamp(pitch, -89.0F, 89.0F);
   }
}
