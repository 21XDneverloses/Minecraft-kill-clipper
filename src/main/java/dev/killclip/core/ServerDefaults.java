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
   /** Health or stats some servers print right after a name: "Me (12.5❤)", "Bob [3/20]". */
   private static final String STATS = "(?:\\s*[\\[(][^\\])]{1,24}[\\])])?";
   /** Words that follow "Bob was ... by Me" without meaning a kill (party invites, staff actions, damage). */
   private static final String NOT_A_KILL = "(?:invited|kicked|promoted|demoted|added|removed|healed|revived|respawned|teleported|tp'?d|muted|unmuted|banned|unbanned"
      + "|warned|reported|challenged|sent\\s+an?|accepted|denied|declined|rejected|requested|mentioned|tagged|combat\\s+tagged|hit|damaged|attacked|targeted"
      + "|marked|spectated|followed|summoned|pulled|joined|given|granted|paid|tipped|thanked|trusted|untrusted|frozen|unfrozen|whitelisted|vanished|queued|matched"
      + "|selected|chosen|picked|saved|rescued|carried|assisted|helped|duel(?:l)?ed|messaged|replied|ignored|blocked|unblocked|friended|unfriended|gifted)\\b";

   /**
    * Kill-message shapes used by many servers, searched anywhere in a chat line so rank tags, emoji and
    * health suffixes around them do not matter. A match only counts when you are the killer and the
    * victim is a player who was online, so these are safe to use on every server.
    */
   public static final List<String> UNIVERSAL = List.of(
      KILLER + STATS + "\\s+(?:has\\s+|have\\s+|just\\s+)?(?:killed|slain|slew|slayed|eliminated|destroyed|defeated|murdered|finished(?:\\s+off)?|obliterated|annihilated|clapped|wrecked|rekt|pwned|bested|sniped|one-?tapped|crystall?ed|crystal\\'?d|popped|anchored|bombed|nuked|exploded|blew\\s+up)\\s+" + VICTIM,
      VICTIM + STATS + "\\s+(?:has\\s+been|was|got)\\s+(?:killed|slain|eliminated|destroyed|defeated|murdered|finished\\s+off|obliterated|annihilated|clapped|bested|sniped|one-?tapped|blown\\s+up|crystall?ed|crystal\\'?d|popped|anchored|bombed|nuked|exploded|shot|knocked\\s+(?:out|off|into\\s+the\\s+void)|pushed\\s+(?:off|into\\s+the\\s+void)|thrown\\s+(?:off|into\\s+the\\s+void)|doomed\\s+to\\s+fall)\\s+(?:\\S+\\s+){0,3}?by\\s+" + KILLER,
      VICTIM + STATS + "\\s+(?:has\\s+)?(?:died|fallen|fell|perished|lost(?:\\s+the\\s+(?:duel|fight|match|round))?)\\s+(?:to|against)\\s+" + KILLER,
      KILLER + "\\s+(?:won|has\\s+won|beat)\\s+(?:the\\s+(?:duel|fight|match|round)\\s+)?(?:against|vs\\.?|versus)\\s+" + VICTIM,
      KILLER + "\\s+beat\\s+" + VICTIM,
      // Themed kill messages (Hypixel Bed Wars/SkyWars cosmetics, anarchy servers): "Bob was struck down by Me.",
      // "Bob was blown to bits by Me's End Crystal. FINAL KILL!". The line has to end soon after the killer.
      VICTIM + STATS + "\\s+(?:was|got|has\\s+been)\\s+(?!" + NOT_A_KILL + ")(?:(?!by\\b|assist)[^\\s:]+\\s+){1,6}?by\\s+" + KILLER
         + "(?:'s?\\s+[^.!:]{1,32}?)?" + STATS + "\\s*(?:[.!]+\\s*)?(?:FINAL\\s+KILL!?|\\d+(?:\\.\\d+)?\\s*/\\s*\\d+(?:\\.\\d+)?\\s*[♥❤]️?|\\([^)]{0,32}\\)|\\[[^\\]]{0,32}\\]|[.!]|\\s)*$",
      // Practice/duel servers: "Winner: Me (12.5❤) Loser: Bob", "Winner » Me | Loser » Bob".
      "(?<![A-Za-z])Winners?\\s*[:»\\-]\\s*" + KILLER + "\\b[^:»]{0,40}?Losers?\\s*[:»\\-]\\s*" + VICTIM,
      // The Pit style, where the killer is you: "KILL! on [120] Bob +12XP", "DOUBLE KILL! on Bob".
      "^(?:(?:DOUBLE|TRIPLE|QUADRA|PENTA|MULTI|MEGA)\\s+)?KILL!\\s+on\\s+" + VICTIM,
      // Bounty and lifesteal servers.
      KILLER + "\\s+(?:has\\s+)?(?:claimed|collected)\\s+(?:the\\s+|a\\s+)?(?:\\$?[\\d,.]+[kmb]?\\s+)?bounty\\s+(?:on|of|for)\\s+" + VICTIM,
      KILLER + "\\s+(?:has\\s+)?(?:stolen|stole|took|taken)\\s+(?:a|one|an?\\s+extra|\\d+)\\s+hearts?\\s+from\\s+" + VICTIM,
      // Combat logging counts as your kill on most PvP servers.
      VICTIM + "\\s+(?:has\\s+)?(?:combat\\s+logged|(?:logged\\s+out|disconnected|quit|left)\\s+(?:while\\s+|during\\s+)?(?:in\\s+)?(?:combat|a\\s+fight|pvp))\\s+(?:with|against|vs\\.?)\\s+" + KILLER
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
