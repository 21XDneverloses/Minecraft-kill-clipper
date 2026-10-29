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

   /** Like {@link #fromMessage}, but each pattern may match anywhere in the line. */
   public static Optional<KillRules.Kill> searchMessage(String message, List<Pattern> patterns, String local, Set<String> online) {
      String plain = clean(message);
      if (plain.length() > 1024) {
         return Optional.empty();
      }

      for (Pattern pattern : patterns) {
         Matcher match = pattern.matcher(plain);
         while (match.find()) {
            if (plain.substring(0, match.start()).contains(":") || plain.substring(0, match.start()).contains("<")) {
               // Text after "Name:" or "<Name>" is a player talking, not a kill message.
               continue;
            }

            Optional<KillRules.Kill> result = validate(match.group("killer"), match.group("victim"), local, online);
            if (result.isPresent()) {
               return result;
            }
         }
      }

      return Optional.empty();
   }

   private static final Pattern NAME_TOKEN = Pattern.compile("[A-Za-z0-9_]{1,16}");
   private static final Pattern KILL_WORD = Pattern.compile(
      "(?i)\\b(?:killed|kills|slain|slew|slayed|eliminated|defeated|destroyed|murdered|finished|obliterated|annihilated|blown|blew"
         + "|exploded|crystall?ed|popped|anchored|bombed|nuked|shot|sniped|knocked|pushed|doomed|died|fell|perished|lost|beat|won"
         + "|rekt|wrecked|clapped|deleted|bested|one-?tapped|pwned|owned|taken\\s+out|took\\s+out|sent\\s+to|wiped|smoked|ended|outplayed|fragged)\\b"
   );
   /** Words right after a name that mean that player is the one who died or lost. */
   private static final Pattern LOSER_VERB = Pattern.compile("(?i)^\\W*(?:was|were|got|has\\s+been|have\\s+been|is|died|fell|perished|lost|tried|hit|drowned|burned|burnt|went|suffocated|starved|froze|withered)\\b");
   /** Words right before a name that make that player the killer ("by You", "to You", "fighting You"). */
   /** "by Bob" or "to Bob": the other player did it, so it is not your kill. */
   private static final Pattern OTHER_IS_KILLER = Pattern.compile("(?i)\\b(?:by|to)\\s+(?:[\\[(][^\\])]{1,16}[\\])]\\s*)?$");
   private static final Pattern KILLER_PREFIX = Pattern.compile("(?i)\\b(?:by|to|against|fighting|escape|escaping|vs\\.?|versus)\\s+(?:[\\[(][^\\])]{1,16}[\\])]\\s*)?$");

   /**
    * Last resort for kill messages in wording no rule knows. Counts a line that has a kill word, names you
    * and exactly one other online player, and makes you the killer: either "by/to/against You", or you come
    * first with an active verb ("You popped Bob"). Lines that look like player chat are ignored.
    */
   public static Optional<KillRules.Kill> guess(String message, String local, Set<String> online) {
      String plain = clean(message);
      if (plain.length() > 300 || plain.startsWith("<") || plain.matches("^[^:]{0,48}:\\s.*") || !KILL_WORD.matcher(plain).find()) {
         return Optional.empty();
      }

      int localAt = -1;
      int otherAt = -1;
      String other = null;
      Matcher names = NAME_TOKEN.matcher(plain);
      while (names.find()) {
         boolean wordStart = names.start() == 0 || !isNameChar(plain.charAt(names.start() - 1));
         boolean wordEnd = names.end() == plain.length() || !isNameChar(plain.charAt(names.end()));
         if (!wordStart || !wordEnd) {
            continue;
         }

         String token = names.group();
         if (token.equals(local)) {
            if (localAt < 0) {
               localAt = names.start();
            }
         } else if (online.contains(token)) {
            if (other != null && !other.equals(token)) {
               return Optional.empty();
            }

            if (other == null) {
               other = token;
               otherAt = names.start();
            }
         }
      }

      if (localAt < 0 || other == null) {
         return Optional.empty();
      }

      String beforeLocal = plain.substring(0, localAt);
      String beforeOther = plain.substring(0, otherAt);
      if (OTHER_IS_KILLER.matcher(beforeOther).find()) {
         return Optional.empty();
      }

      boolean localByPreposition = localAt > otherAt && KILLER_PREFIX.matcher(beforeLocal).find();
      boolean localActiveFirst = localAt < otherAt
         && !LOSER_VERB.matcher(plain.substring(localAt + local.length())).find()
         && KILL_WORD.matcher(plain.substring(localAt, otherAt)).find();
      boolean otherIsLoser = localAt > otherAt && LOSER_VERB.matcher(plain.substring(otherAt + other.length())).find();
      return localByPreposition || localActiveFirst || otherIsLoser && KILLER_PREFIX.matcher(beforeLocal).find()
         ? validate(local, other, local, online)
         : Optional.empty();
   }

   private static boolean isNameChar(char c) {
      return Character.isLetterOrDigit(c) || c == '_';
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
