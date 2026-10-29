package dev.killclip.core;

import java.util.Optional;

public final class StreakTracker {
   private final long gapMs;
   private int kills;
   private long firstKillMs;
   private long lastKillMs;

   public StreakTracker(long gapMs) {
      this.gapMs = gapMs;
   }

   public Optional<Streak> onKill(long nowMs) {
      Optional<Streak> finished = this.flush(nowMs);
      if (this.kills == 0) {
         this.firstKillMs = nowMs;
      }

      this.kills++;
      this.lastKillMs = nowMs;
      return finished;
   }

   public Optional<Streak> flush(long nowMs) {
      if (this.kills == 0 || nowMs - this.lastKillMs <= this.gapMs) {
         return Optional.empty();
      } else {
         Streak streak = new Streak(this.kills, this.firstKillMs, this.lastKillMs);
         this.kills = 0;
         return Optional.of(streak);
      }
   }

   public void clear() {
      this.kills = 0;
   }

   public record Streak(int kills, long firstKillMs, long lastKillMs) {
   }
}
