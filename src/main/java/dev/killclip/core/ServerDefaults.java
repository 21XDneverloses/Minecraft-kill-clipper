package dev.killclip.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ServerDefaults {
   public static final String FLOW = "^(?<killer>[A-Za-z0-9_]{1,16}) has killed (?<victim>[A-Za-z0-9_]{1,16})\\.$";
   public static final String HQ = "^(?:[⚔☠✘✖]️?\\s*)?(?<victim>[A-Za-z0-9_]{1,16}) has been killed by (?<killer>[A-Za-z0-9_]{1,16})\\.?$";
   public static final String MCPVP = "^(?:[^\\r\\n:]{1,48}\\|\\s*)?(?<victim>[A-Za-z0-9_]{1,16}) was slain by (?<killer>[A-Za-z0-9_]{1,16})(?:\\s+\\d+(?:\\.\\d+)?/\\d+(?:\\.\\d+)?\\s*[♥❤]️?)?$";

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
