package dev.killclip.client;

import dev.killclip.core.ReplayLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;

/**
 * Films instant replays for the highlight reel: for its best kills that have a fight replay, the replay is opened,
 * played around the kill in slow motion from the third-person camera and saved as a short video, one after another.
 * Nothing is saved to the clip's own edits. If a replay can't be filmed it is skipped and the reel is made without it.
 */
@Environment(EnvType.CLIENT)
final class ReelAngles {
   /** In-game playback speed while filming: slow motion with every frame really drawn. */
   static final float SPEED = 0.5F;
   /** Longest a single replay may take to open and film before it is skipped. */
   private static final long SHOT_TIMEOUT_MS = 90_000L;

   /**
    * One replay to film: the clip it belongs to, its fight replay, when the kill is in the clip's video, how much
    * game time to film around it, and where the video goes.
    */
   record Shot(Path clip, Path replay, double killVideoSeconds, double windowSeconds, Path output) {
   }

   private static ReelAngles running;

   private final Deque<Shot> queue;
   private final int total;
   private final Map<Path, Path> done = new HashMap<>();
   private final Consumer<Map<Path, Path>> finished;
   private Shot current;
   private long shotStartedMs;
   private boolean waitingForIdle = true;
   private String note = "";

   private ReelAngles(List<Shot> shots, Consumer<Map<Path, Path>> finished) {
      this.queue = new ArrayDeque<>(shots);
      this.total = shots.size();
      this.finished = finished;
   }

   static boolean running() {
      return running != null;
   }

   /** What is being filmed, for the reel screen. */
   static String status() {
      ReelAngles job = running;
      if (job == null) {
         return "";
      }

      return "Filming instant replay " + Math.min(job.total, job.total - job.queue.size()) + " of " + job.total + "...";
   }

   /** Films the shots one by one, then calls {@code finished} on the game thread with clip -> replay video. */
   static void start(List<Shot> shots, Consumer<Map<Path, Path>> finished) {
      running = new ReelAngles(shots, finished);
   }

   /** Stops filming (Esc pressed): what was filmed so far is used. */
   static void cancel(String why) {
      ReelAngles job = running;
      if (job != null) {
         KillClipperClient.LOGGER.info("Highlight reel replays: {}", why);
         job.note = why;
         job.queue.clear();
      }
   }

   /** The replay player finished (or gave up on) a shot; {@code video} is null if nothing was saved. */
   static void shotFinished(Shot shot, Path video, String problem) {
      ReelAngles job = running;
      if (job == null || job.current != shot) {
         return;
      }

      if (video != null && Files.isRegularFile(video)) {
         job.done.put(shot.clip(), video);
         KillClipperClient.LOGGER.info("Highlight reel: filmed an instant replay of {} in {} ms", shot.clip().getFileName(), now() - job.shotStartedMs);
      } else {
         KillClipperClient.LOGGER.warn("Highlight reel: no instant replay for {}: {}", shot.clip().getFileName(), problem);
      }

      job.endShot();
   }

   private void endShot() {
      if (this.current != null) {
         ReplayEdit.clearOverride(this.current.replay());
      }

      this.current = null;
      this.waitingForIdle = true;
      ReplayPlayer.stop();
   }

   private static long now() {
      return System.nanoTime() / 1_000_000L;
   }

   /** Called every client tick: opens the next replay once the last one has closed, and watches for stuck ones. */
   static void tick(MinecraftClient client) {
      ReelAngles job = running;
      if (job == null) {
         return;
      }

      if (job.current != null) {
         ReplayPlayer player = ReplayPlayer.active();
         boolean lost = player == null || player.autoShot != job.current;
         if (lost && now() - job.shotStartedMs > 3000L || now() - job.shotStartedMs > SHOT_TIMEOUT_MS) {
            KillClipperClient.LOGGER.warn("Highlight reel: instant replay of {} stopped ({})", job.current.clip().getFileName(), lost ? "the replay closed" : "took too long");
            job.endShot();
         }

         return;
      }

      if (job.waitingForIdle && (client.world != null || ReplayPlayer.active() != null)) {
         return;
      }

      job.waitingForIdle = false;
      Shot next = job.queue.poll();
      if (next == null) {
         running = null;
         client.setScreen(new HighlightReelScreen(new ClipLibraryScreen(new TitleScreen())));
         if (!job.note.isEmpty()) {
            HighlightReelScreen.note(job.note);
         }

         job.finished.accept(job.done);
         return;
      }

      job.film(client, next);
   }

   private void film(MinecraftClient client, Shot shot) {
      this.current = shot;
      this.shotStartedMs = now();
      long lead;
      try (ReplayLog.Reader reader = new ReplayLog.Reader(shot.replay())) {
         lead = parse(reader.header().get("videoLeadMs"));
      } catch (Exception e) {
         KillClipperClient.LOGGER.warn("Highlight reel: can't read the replay {}: {}", shot.replay().getFileName(), e.getMessage());
         this.current = null;
         return;
      }

      // The kill sits a little past the middle, so the lead-up and the hit are both in the shot.
      long kill = Math.round(shot.killVideoSeconds() * 1000.0) - lead;
      long window = Math.round(shot.windowSeconds() * 1000.0);
      long start = Math.max(0L, kill - window * 65L / 100L);
      ReplayEdit edit = ReplayEdit.temporary();
      edit.trimStartMs = start;
      edit.trimEndMs = start + window;
      edit.switches.add(new ReplayEdit.Switch(0L, ReplayPlayer.CameraMode.THIRD_PERSON, 0.0, 0.0, 0.0, 0.0F, 0.0F, SPEED, 1.0F));
      ReplayEdit.override(shot.replay(), edit);
      ReplayPlayer.startAsync(client, shot.replay(), player -> player.autoShot = shot, error -> shotFinished(shot, null, error));
   }

   private static long parse(String value) {
      try {
         return value == null ? 0L : Long.parseLong(value.trim());
      } catch (NumberFormatException e) {
         return 0L;
      }
   }
}
