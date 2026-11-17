package dev.killclip.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deaths of players you hit wait here a moment before they count. If the chat says someone else got the kill
 * in that time ("Bob was slain by Carl"), the death is dropped, so third-party kills in free-for-all fights
 * don't turn into your clips. The kill keeps the time of the death, so clips are not shifted.
 */
public final class PendingKills {
   private final Map<String, Long> pending = new LinkedHashMap<>();
   /** Players the chat just credited to someone else, and when. */
   private final Map<String, Long> creditedElsewhere = new HashMap<>();
   private final long delayMs;
   private final long creditMemoryMs;

   public PendingKills(long delayMs, long creditMemoryMs) {
      this.delayMs = delayMs;
      this.creditMemoryMs = creditMemoryMs;
   }

   /** A player you hit died. Ignored when the chat already gave that death to someone else. */
   public boolean add(String victim, long deathMs) {
      Long credited = this.creditedElsewhere.get(victim);
      if (credited != null && Math.abs(deathMs - credited) <= this.creditMemoryMs) {
         return false;
      }

      this.pending.putIfAbsent(victim, deathMs);
      return true;
   }

   /** The chat says another player killed {@code victim}. Returns true if a pending death was dropped. */
   public boolean creditedToOther(String victim, long nowMs) {
      this.creditedElsewhere.put(victim, nowMs);
      return this.pending.remove(victim) != null;
   }

   public void remove(String victim) {
      this.pending.remove(victim);
   }

   public boolean isEmpty() {
      return this.pending.isEmpty();
   }

   /** Deaths that waited long enough, with their death times; they are removed from the list. */
   public List<Map.Entry<String, Long>> due(long nowMs) {
      this.creditedElsewhere.values().removeIf(time -> nowMs - time > this.creditMemoryMs);
      List<Map.Entry<String, Long>> ready = new ArrayList<>();
      this.pending.entrySet().removeIf(entry -> {
         if (nowMs - entry.getValue() >= this.delayMs) {
            ready.add(Map.entry(entry.getKey(), entry.getValue()));
            return true;
         }

         return false;
      });
      return ready;
   }

   /** Everything still waiting, for when the fight ends before the delay is over. */
   public List<Map.Entry<String, Long>> drain() {
      List<Map.Entry<String, Long>> all = new ArrayList<>(this.pending.entrySet().stream().map(e -> Map.entry(e.getKey(), e.getValue())).toList());
      this.pending.clear();
      return all;
   }

   public Map<String, Long> snapshot() {
      return Map.copyOf(this.pending);
   }

   public void clear() {
      this.pending.clear();
      this.creditedElsewhere.clear();
   }
}
