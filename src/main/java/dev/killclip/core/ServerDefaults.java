package dev.killclip.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ServerDefaults {
   public static final String FLOW = "^(?<killer>[A-Za-z0-9_]{1,16}) has killed (?<victim>[A-Za-z0-9_]{1,16})\\.$";
   public static final String HQ = "^(?:[⚔🗡☠✘✖]️?\\s*)?(?<victim>[A-Za-z0-9_]{1,16}) has been killed by (?<killer>[A-Za-z0-9_]{1,16})\\.?$";
   public static final String MCPVP = "^(?:[^\\r\\n:]{1,48}\\|\\s*)?(?<victim>[A-Za-z0-9_]{1,16}) was slain by (?<killer>[A-Za-z0-9_]{1,16})(?:\\s+\\d+(?:\\.\\d+)?/\\d+(?:\\.\\d+)?\\s*[♥❤]️?)?$";

   private static final String NAME = "(?:[\\[(][^\\])]{1,16}[\\])]\\s*)?(?<![A-Za-z0-9_])(?<%s>[A-Za-z0-9_]{1,16})(?![A-Za-z0-9_])";
   private static final String KILLER = String.format(NAME, "killer");
   private static final String VICTIM = String.format(NAME, "victim");

   /**
    * Kill-message shapes used by many servers, searched anywhere in a chat line so rank tags, emoji and
    * health suffixes around them do not matter. A match only counts when you are the killer and the
    * victim is a player who was online, so these are safe to use on every server.
    */
   public static final List<String> UNIVERSAL = List.of(
      KILLER + "\\s+(?:has\\s+|just\\s+)?(?:killed|slain|slew|slayed|eliminated|destroyed|defeated|murdered|finished(?:\\s+off)?|obliterated|annihilated|clapped|wrecked|rekt|pwned|bested|sniped|one-?tapped|crystall?ed|crystal\\'?d|popped|anchored|bombed|nuked|exploded|blew\\s+up)\\s+" + VICTIM,
      VICTIM + "\\s+(?:has\\s+been|was|got)\\s+(?:killed|slain|eliminated|destroyed|defeated|murdered|finished\\s+off|obliterated|annihilated|clapped|bested|sniped|one-?tapped|blown\\s+up|crystall?ed|crystal\\'?d|popped|anchored|bombed|nuked|exploded|shot|knocked\\s+(?:out|off|into\\s+the\\s+void)|pushed\\s+(?:off|into\\s+the\\s+void)|thrown\\s+(?:off|into\\s+the\\s+void)|doomed\\s+to\\s+fall)\\s+(?:\\S+\\s+){0,3}?by\\s+" + KILLER,
      VICTIM + "\\s+(?:died|fell|perished|lost(?:\\s+the\\s+(?:duel|fight|match|round))?)\\s+(?:to|against)\\s+" + KILLER,
      KILLER + "\\s+(?:won|has\\s+won|beat)\\s+(?:the\\s+(?:duel|fight|match|round)\\s+)?(?:against|vs\\.?|versus)\\s+" + VICTIM,
      KILLER + "\\s+beat\\s+" + VICTIM
   );

   private ServerDefaults() {
   }

   public static Map<String, List<String>> patterns() {
      Map<String, List<String>> defaults = new LinkedHashMap<>();
      defaults.put(
         "mcpvp.com",
         List.of(
            "^(?:[^\\r\\n:]{1,48}\\|\\s*)?(?<victim>[A-Za-z0-9_]{1,16}) was slain by (?<killer>[A-Za-z0-9_]{1,16})(?:\\s+\\d+(?:\\.\\d+)?/\\d+(?:\\.\\d+)?\\s*[♥❤]️?)?$"
         )
      );

      for (String host : List.of(
         "flowpvp.gg", "eu.flowpvp.gg", "as.flowpvp.gg", "pvphq.com", "play.pvphq.com", "play.lax-us.pvphq.com", "play.dal-us.pvphq.com"
      )) {
         defaults.put(
            host,
            List.of(
               "^(?<killer>[A-Za-z0-9_]{1,16}) has killed (?<victim>[A-Za-z0-9_]{1,16})\\.$",
               "^(?:[⚔☠✘✖]️?\\s*)?(?<victim>[A-Za-z0-9_]{1,16}) has been killed by (?<killer>[A-Za-z0-9_]{1,16})\\.?$"
            )
         );
      }

      return defaults;
   }

   public static List<String> hosts() {
      List<String> hosts = new ArrayList<>(patterns().keySet());
      hosts.add("mcpvp.club");
      return hosts;
   }
}
