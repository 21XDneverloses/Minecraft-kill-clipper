package dev.killclip.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remembers which players the local player hit recently. A tracked player
 * who dies within the window counts as a kill, which works on any server
 * whatever its kill messages look like.
 *
 * <p>It also remembers when each of them went down (seen dead, turned spectator or vanished), so a kill that is
 * only announced later, such as a "Winner" line, a victory screen or the move back to the lobby, is placed on
 * the moment the player actually died. Clips, kill markers and highlight reel cuts all center on that moment.
 */
public final class HitTracker {
   /** How far back a later announcement may move a kill to when the player was seen going down. */
   public static final long MAX_BACKDATE_MS = 10000L;
   /** A kill placed on the last hit (nothing better known) sits this long after it, when the blow lands. */
   static final long AFTER_HIT_MS = 250L;
   private final Map<String, Long> lastHit = new HashMap<>();
   private final Map<String, Long> goneAt = new HashMap<>();
   /** Kill times of players hit in the world you just left, for announcements that arrive in the next one. */
   private final Map<String, Long> carried = new HashMap<>();
   private final Set<String> carriedCredited = new HashSet<>();
   private long leftAtMs = Long.MIN_VALUE / 2;

   public void onHit(String victim, long nowMs) {
      this.lastHit.put(victim, nowMs);
   }

   /** Players hit within {@code windowMs}; older entries are forgotten. */
   public List<String> recent(long nowMs, long windowMs) {
      this.lastHit.values().removeIf(time -> nowMs - time > windowMs);
      this.goneAt.keySet().retainAll(this.lastHit.keySet());
      return List.copyOf(this.lastHit.keySet());
   }

   /** A player you hit was seen dead, turned spectator or left the world; the first time counts. */
   public void onGone(String victim, long nowMs) {
      if (this.lastHit.containsKey(victim)) {
         this.goneAt.putIfAbsent(victim, nowMs);
      }
   }

   /** The player is back in the world and alive, so they were not killed then. */
   public void onBack(String victim) {
      this.goneAt.remove(victim);
   }

   /**
    * When the kill of {@code victim}, announced at {@code receiptMs}, most likely happened: when they were seen
    * going down if that was shortly before, otherwise the announcement itself.
    */
   public long killTime(String victim, long receiptMs) {
      Long gone = this.goneAt.get(victim);
      if (gone != null && gone <= receiptMs && receiptMs - gone <= MAX_BACKDATE_MS) {
         return gone;
      }

      Long carriedTime = this.carried.get(victim);
      if (carriedTime != null && receiptMs - this.leftAtMs <= MAX_BACKDATE_MS && receiptMs - carriedTime <= 2L * MAX_BACKDATE_MS) {
         // Announced after the server moved you on (a win shown in the lobby): the fight was in the last world.
         return carriedTime;
      }

      return receiptMs;
   }

   /**
    * When a player credited only because the match ended (you were moved on right after hitting them) died:
    * when they were seen going down, or just after your last hit.
    */
   public long matchEndTime(String victim, long nowMs) {
      Long gone = this.goneAt.get(victim);
      if (gone != null && gone <= nowMs) {
         return gone;
      }

      Long hit = this.lastHit.get(victim);
      return hit == null ? nowMs : Math.min(nowMs, hit + AFTER_HIT_MS);
   }

   /**
    * You left the world (a match ended or the server moved you). Hits are cleared for the next match, but their
    * kill times are kept briefly: a win is often announced only once you are in the lobby. {@code credited} are the
    * players already counted at the match end, so a later announcement does not count them twice.
    */
   public void leaveWorld(long nowMs, long windowMs, Set<String> credited) {
      this.carried.clear();
      this.carriedCredited.clear();
      for (String victim : this.recent(nowMs, windowMs)) {
         this.carried.put(victim, this.matchEndTime(victim, nowMs));
      }

      this.carriedCredited.addAll(credited);
      this.leftAtMs = nowMs;
      this.lastHit.clear();
      this.goneAt.clear();
   }

   /** Whether this kill was already counted when you left the last world a moment ago. */
   public boolean creditedBeforeLeaving(String victim, long nowMs) {
      return nowMs - this.leftAtMs <= MAX_BACKDATE_MS && this.carriedCredited.contains(victim);
   }

   /** Call once a tracked player has been credited, so one death counts once. */
   public void forget(String victim) {
      this.lastHit.remove(victim);
      this.goneAt.remove(victim);
      this.carried.remove(victim);
   }

   public void clear() {
      this.lastHit.clear();
      this.goneAt.clear();
      this.carried.clear();
      this.carriedCredited.clear();
      this.leftAtMs = Long.MIN_VALUE / 2;
   }
}
