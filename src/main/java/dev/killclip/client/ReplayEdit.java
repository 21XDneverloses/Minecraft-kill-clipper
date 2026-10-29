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
 * smoothly from the first spot to the second.
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

   private static final class Saved {
      List<Switch> switches = new ArrayList<>();
   }

   private final transient Path file;
   final List<Switch> switches = new ArrayList<>();

   private ReplayEdit(Path file) {
      this.file = file;
   }

   static Path editFile(Path replay) {
      String name = replay.getFileName().toString();
      int dot = name.lastIndexOf('.');
      return replay.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".kcedit");
   }

   static ReplayEdit load(Path replay) {
      ReplayEdit edit = new ReplayEdit(editFile(replay));
      if (Files.isRegularFile(edit.file)) {
         try {
            Saved saved = GSON.fromJson(Files.readString(edit.file), Saved.class);
            if (saved != null && saved.switches != null) {
               saved.switches.stream().filter(s -> s.mode != null).forEach(edit.switches::add);
               edit.sort();
            }
         } catch (IOException | RuntimeException e) {
            KillClipperClient.LOGGER.warn("Could not read camera edits {}", edit.file, e);
         }
      }

      return edit;
   }

   void save() {
      Saved saved = new Saved();
      saved.switches = this.switches;
      try {
         Files.writeString(this.file, GSON.toJson(saved));
      } catch (IOException e) {
         KillClipperClient.LOGGER.warn("Could not save camera edits {}", this.file, e);
      }
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

   Switch after(Switch current) {
      int index = this.switches.indexOf(current);
      return index >= 0 && index + 1 < this.switches.size() ? this.switches.get(index + 1) : null;
   }
}
