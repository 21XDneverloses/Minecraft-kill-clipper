package dev.killclip.core;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KillRules {
   private KillRules() {
   }

   public static String clean(String text) {
      return text.replaceAll("§[0-9A-FK-ORa-fk-or]", "").strip();
   }

   public static Optional<KillRules.Kill> fromTranslation(String key, List<String> args, String local, Set<String> online) {
      return VanillaDeathMessages.englishTemplates().containsKey(key) && args.size() >= 2
         ? validate(clean(args.get(1)), clean(args.get(0)), local, online)
         : Optional.empty();
   }

   public static Optional<KillRules.Kill> fromMessage(String message, List<Pattern> patterns, String local, Set<String> online) {
      String plain = clean(message);
      if (plain.length() > 1024) {
         return Optional.empty();
      } else {
         for (Pattern pattern : patterns) {
            Matcher match = pattern.matcher(plain);
            if (match.matches()) {
               Optional<KillRules.Kill> result = validate(match.group("killer"), match.group("victim"), local, online);
               if (result.isPresent()) {
                  return result;
               }
            }
         }

         return Optional.empty();
      }
   }

   private static Optional<KillRules.Kill> validate(String killer, String victim, String local, Set<String> online) {
      return killer.equals(local) && !victim.equals(local) && online.contains(victim) ? Optional.of(new KillRules.Kill(killer, victim)) : Optional.empty();
   }

   public static String normalizeHost(String address) {
      return address.toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "").replaceFirst("\\.$", "");
   }

   public static boolean hostAllowed(String address, Collection<String> allowed) {
      String host = normalizeHost(address);
      return allowed.stream().map(KillRules::normalizeHost).anyMatch(host::equals);
   }

   public record Kill(String killer, String victim) {
   }
}
