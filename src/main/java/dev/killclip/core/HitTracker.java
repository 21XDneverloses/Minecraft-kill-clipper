package dev.killclip.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers which players the local player hit recently. A tracked player
 * who dies within the window counts as a kill, which works on any server
 * whatever its kill messages look like.
 */
public final class HitTracker {
   private final Map<String, Long> lastHit = new HashMap<>();

   public void onHit(String victim, long nowMs) {
      this.lastHit.put(victim, nowMs);
   }

   /** Players hit within {@code windowMs}; older entries are forgotten. */
   public List<String> recent(long nowMs, long windowMs) {
      this.lastHit.values().removeIf(time -> nowMs - time > windowMs);
      return List.copyOf(this.lastHit.keySet());
   }

   /** Call once a tracked player has been credited, so one death counts once. */
   public void forget(String victim) {
      this.lastHit.remove(victim);
   }

   public void clear() {
      this.lastHit.clear();
   }
}
