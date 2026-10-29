package dev.killclip.core;

import java.util.HashMap;
import java.util.Map;

public final class KillDeduplicator {
   private final Map<String, Long> seen = new HashMap<>();

   public boolean accept(String victim, long nowMs, long windowMs) {
      this.seen.entrySet().removeIf(entry -> nowMs - entry.getValue() >= windowMs);
      if (this.seen.containsKey(victim)) {
         return false;
      } else {
         this.seen.put(victim, nowMs);
         return true;
      }
   }

   public void clear() {
      this.seen.clear();
   }
}
