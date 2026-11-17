package dev.killclip.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Camera switches for one replay, saved next to it as {@code <clip>.kcedit} so edits are kept.
 *
 * <p>Each switch picks a camera from its time on. Two spectator switches in a row make the free camera fly
 * smoothly from the first spot to the second. An edit also holds freeze-frames and the text you put on the video.
 * Every change is saved straight away and can be undone.
 */
@Environment(EnvType.CLIENT)
final class ReplayEdit {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

   static final class Switch {
      long timeMs;
      ReplayPlayer.CameraMode mode;
      double x;
      double y;
      double z;
      float yaw;
      float pitch;
      /** Playback speed from here on (0.25 is slow-mo); blends smoothly to the next switch's speed. */
      float speed = 1.0F;
      /** Zoom from here on (2 is twice as close); blends smoothly to the next switch's zoom. */
      float zoom = 1.0F;

      Switch(long timeMs, ReplayPlayer.CameraMode mode, double x, double y, double z, float yaw, float pitch, float speed, float zoom) {
         this(timeMs, mode, x, y, z, yaw, pitch);
         this.speed = speed;
         this.zoom = zoom;
      }

      /** Edits saved before speed and zoom existed read them as 0. */
      float speed() {
         return this.speed > 0.0F ? this.speed : 1.0F;
      }

      float zoom() {
         return this.zoom > 0.0F ? this.zoom : 1.0F;
      }

      Switch(long timeMs, ReplayPlayer.CameraMode mode, double x, double y, double z, float yaw, float pitch) {
         this.timeMs = timeMs;
         this.mode = mode;
         this.x = x;
         this.y = y;
         this.z = z;
         this.yaw = yaw;
         this.pitch = pitch;
      }
   }

   /** A freeze-frame: the picture holds still at {@code timeMs} for {@code durationMs}, then the clip goes on. */
   static final class Freeze {
      long timeMs;
      long durationMs;

      Freeze(long timeMs, long durationMs) {
         this.timeMs = timeMs;
         this.durationMs = durationMs;
      }
   }

   /** Text you wrote, shown on the video from {@code startMs} to {@code endMs} (clip time). */
   static final class Caption {
      static final String[] POSITIONS = {"Top", "Middle", "Bottom"};
      static final String[] SIZES = {"Small", "Medium", "Large"};
      static final String[] COLOR_NAMES = {"White", "Red", "Yellow", "Green", "Blue"};
      static final int[] COLORS = {0xFFFFFFFF, 0xFFFF4655, 0xFFFFD43B, 0xFF4ADE80, 0xFF60A5FA};
      long startMs;
      long endMs;
      String text = "";
      /** 0 top, 1 middle, 2 bottom. */
      int position = 2;
      /** 0 small, 1 medium, 2 large. */
      int size = 1;
      int color;

      Caption(long startMs, long endMs) {
         this.startMs = startMs;
         this.endMs = endMs;
      }

      Caption copy() {
         Caption copy = new Caption(this.startMs, this.endMs);
         copy.text = this.text;
         copy.position = this.position;
         copy.size = this.size;
         copy.color = this.color;
         return copy;
      }

      int rgb() {
         return COLORS[Math.floorMod(this.color, COLORS.length)];
      }
   }

   private static final class Saved {
      List<Switch> switches = new ArrayList<>();
      long trimStartMs;
      long trimEndMs;
      List<Freeze> freezes = new ArrayList<>();
      List<Caption> texts = new ArrayList<>();
   }

   private static final int UNDO_LIMIT = 100;

   /** Edits made by the highlight reel for its instant replays: used instead of the saved edit, never written. */
   private static final java.util.Map<Path, ReplayEdit> OVERRIDES = new java.util.concurrent.ConcurrentHashMap<>();
   private final transient Path file;
   final List<Switch> switches = new ArrayList<>();
   /** Where the export starts, in ms into the clip. */
   long trimStartMs;
   /** Where the export ends, in ms into the clip; 0 means the end of the clip. */
   long trimEndMs;
   final List<Freeze> freezes = new ArrayList<>();
   final List<Caption> texts = new ArrayList<>();
   /** The edit as last saved, and the states before it (undo) and after it (redo), as saved text. */
   private String savedJson;
   private final java.util.ArrayDeque<String> undo = new java.util.ArrayDeque<>();
   private final java.util.ArrayDeque<String> redo = new java.util.ArrayDeque<>();
   private long lastUndoStepMs;
   /** Real time of the last save, for the "Saved" mark in the editor; 0 before any change. */
   long lastSaveMs;

   private ReplayEdit(Path file) {
      this.file = file;
   }

   /**
    * Edits live in a hidden ".edits" folder next to the clips: a .kcedit beside the video looked like the export.
    * One from an older version is moved there.
    */
   static Path editFile(Path replay) {
      String name = replay.getFileName().toString();
      int dot = name.lastIndexOf('.');
      String file = (dot > 0 ? name.substring(0, dot) : name) + ".kcedit";
      Path edit = replay.resolveSibling(".edits").resolve(file);
      Path old = replay.resolveSibling(file);
      if (!Files.exists(edit) && Files.isRegularFile(old)) {
         try {
            createEditsDir(edit.getParent());
            Files.move(old, edit);
         } catch (IOException e) {
            return old;
         }
      }

      return edit;
   }

   private static void createEditsDir(Path dir) throws IOException {
      if (!Files.isDirectory(dir)) {
         Files.createDirectories(dir);
         try {
            Files.setAttribute(dir, "dos:hidden", true);
         } catch (IOException | UnsupportedOperationException | IllegalArgumentException ignored) {
            // Not Windows: the dot in the name already hides it.
         }
      }
   }

   /** An edit kept in memory only (nothing is saved), for the highlight reel's instant replays. */
   static ReplayEdit temporary() {
      return new ReplayEdit(null);
   }

   static void override(Path replay, ReplayEdit edit) {
      OVERRIDES.put(replay.toAbsolutePath().normalize(), edit);
   }

   static void clearOverride(Path replay) {
      OVERRIDES.remove(replay.toAbsolutePath().normalize());
   }

   static ReplayEdit load(Path replay) {
      ReplayEdit temporary = OVERRIDES.get(replay.toAbsolutePath().normalize());
      if (temporary != null) {
         return temporary;
      }

      ReplayEdit edit = new ReplayEdit(editFile(replay));
      if (Files.isRegularFile(edit.file)) {
         try {
            edit.apply(Files.readString(edit.file));
         } catch (IOException | RuntimeException e) {
            KillClipperClient.LOGGER.warn("Could not read camera edits {}", edit.file, e);
         }
      }

      edit.savedJson = edit.toJson();
      return edit;
   }

   /** Replaces everything in this edit with a saved state. */
   private void apply(String json) {
      Saved saved = GSON.fromJson(json, Saved.class);
      this.switches.clear();
      this.freezes.clear();
      this.texts.clear();
      this.trimStartMs = 0L;
      this.trimEndMs = 0L;
      if (saved == null) {
         return;
      }

      if (saved.switches != null) {
         saved.switches.stream().filter(s -> s != null && s.mode != null).forEach(this.switches::add);
         this.sort();
      }

      if (saved.freezes != null) {
         saved.freezes.stream().filter(f -> f != null && f.durationMs > 0L).forEach(this.freezes::add);
         this.freezes.sort(Comparator.comparingLong(f -> f.timeMs));
      }

      if (saved.texts != null) {
         saved.texts.stream().filter(t -> t != null && t.text != null && !t.text.isBlank() && t.endMs > t.startMs).forEach(this.texts::add);
      }

      this.trimStartMs = Math.max(0L, saved.trimStartMs);
      this.trimEndMs = Math.max(0L, saved.trimEndMs);
   }

   private String toJson() {
      Saved saved = new Saved();
      saved.switches = this.switches;
      saved.trimStartMs = this.trimStartMs;
      saved.trimEndMs = this.trimEndMs;
      saved.freezes = this.freezes;
      saved.texts = this.texts;
      return GSON.toJson(saved);
   }

   /**
    * Saves the edit (every change is saved straight away, so nothing is lost if the game closes) and keeps the
    * state before it for undo. Changes within a tenth of a second are one step, like a click that adds a camera
    * part and then changes it.
    */
   void save() {
      if (this.file == null) {
         return;
      }

      String json = this.toJson();
      if (json.equals(this.savedJson)) {
         return;
      }

      long now = System.currentTimeMillis();
      if (this.savedJson != null && now - this.lastUndoStepMs > 100L) {
         this.undo.push(this.savedJson);
         while (this.undo.size() > UNDO_LIMIT) {
            this.undo.removeLast();
         }
      }

      this.lastUndoStepMs = now;
      this.redo.clear();
      this.savedJson = json;
      this.write(json);
   }

   private void write(String json) {
      try {
         createEditsDir(this.file.getParent());
         // Write a copy first and swap it in, so a crash mid-save never leaves a broken edit.
         Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
         Files.writeString(temp, json);
         try {
            Files.move(temp, this.file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
         } catch (IOException e) {
            Files.move(temp, this.file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
         }

         this.lastSaveMs = System.currentTimeMillis();
      } catch (IOException e) {
         KillClipperClient.LOGGER.warn("Could not save camera edits {}", this.file, e);
      }
   }

   boolean canUndo() {
      return !this.undo.isEmpty();
   }

   boolean canRedo() {
      return !this.redo.isEmpty();
   }

   /** Goes back one change; returns false when there is nothing to undo. */
   boolean undo() {
      if (this.file == null || this.undo.isEmpty()) {
         return false;
      }

      this.redo.push(this.savedJson);
      this.restore(this.undo.pop());
      return true;
   }

   /** Does again the last change that was undone. */
   boolean redo() {
      if (this.file == null || this.redo.isEmpty()) {
         return false;
      }

      this.undo.push(this.savedJson);
      this.restore(this.redo.pop());
      return true;
   }

   private void restore(String json) {
      this.apply(json);
      this.savedJson = json;
      this.lastUndoStepMs = 0L;
      this.write(json);
   }

   /** The freeze-frame within a quarter second of {@code timeMs}, or null. */
   Freeze freezeNear(long timeMs) {
      for (Freeze f : this.freezes) {
         if (Math.abs(f.timeMs - timeMs) < 250L) {
            return f;
         }
      }

      return null;
   }

   /** The first freeze-frame after {@code fromMs} and up to {@code toMs}, or null. */
   Freeze freezeCrossed(long fromMs, long toMs) {
      for (Freeze f : this.freezes) {
         if (f.timeMs > fromMs && f.timeMs <= toMs) {
            return f;
         }
      }

      return null;
   }

   void addFreeze(Freeze freeze) {
      this.freezes.add(freeze);
      this.freezes.sort(Comparator.comparingLong(f -> f.timeMs));
      this.save();
   }

   /** The texts showing at {@code timeMs}. */
   List<Caption> textsAt(long timeMs) {
      List<Caption> shown = new ArrayList<>();
      for (Caption caption : this.texts) {
         if (timeMs >= caption.startMs && timeMs < caption.endMs) {
            shown.add(caption);
         }
      }

      return shown;
   }

   private void sort() {
      this.switches.sort(Comparator.comparingLong(s -> s.timeMs));
   }

   /** Adds a switch, replacing one within a quarter second of the same time. */
   void put(Switch added) {
      this.switches.removeIf(s -> Math.abs(s.timeMs - added.timeMs) < 250L);
      this.switches.add(added);
      this.sort();
      this.save();
   }

   /** Removes the switch nearest to {@code timeMs} (within 1.5 s); returns whether one was removed. */
   boolean removeNear(long timeMs) {
      Switch nearest = null;
      for (Switch s : this.switches) {
         if (Math.abs(s.timeMs - timeMs) <= 1500L && (nearest == null || Math.abs(s.timeMs - timeMs) < Math.abs(nearest.timeMs - timeMs))) {
            nearest = s;
         }
      }

      if (nearest == null) {
         return false;
      }

      this.switches.remove(nearest);
      this.save();
      return true;
   }

   /** The switch in effect at {@code timeMs}, or null before the first one. */
   Switch at(long timeMs) {
      Switch found = null;
      for (Switch s : this.switches) {
         if (s.timeMs > timeMs) {
            break;
         }

         found = s;
      }

      return found;
   }

   /** The trim range inside a clip of {@code lengthMs}: {start, end}, at least half a second long. */
   long[] trim(long lengthMs) {
      long end = this.trimEndMs <= 0L ? lengthMs : Math.min(this.trimEndMs, lengthMs);
      long start = Math.min(this.trimStartMs, Math.max(0L, end - 500L));
      return new long[]{start, end};
   }

   Switch before(Switch current) {
      int index = this.switches.indexOf(current);
      return index > 0 ? this.switches.get(index - 1) : null;
   }

   Switch after(Switch current) {
      int index = this.switches.indexOf(current);
      return index >= 0 && index + 1 < this.switches.size() ? this.switches.get(index + 1) : null;
   }
}
