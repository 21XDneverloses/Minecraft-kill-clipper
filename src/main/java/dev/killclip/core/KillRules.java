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
               Optional<KillRules.Kill> result = validate(killer(match, local), match.group("victim"), local, online);
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

            Optional<KillRules.Kill> result = validate(killer(match, local), match.group("victim"), local, online);
            if (result.isPresent()) {
               return result;
            }
         }
      }

      return Optional.empty();
   }

   /** Rules without a killer group ("KILL! on Bob") are messages only the killer sees, so the killer is you. */
   private static String killer(Matcher match, String local) {
      return match.pattern().pattern().contains("(?<killer>") ? match.group("killer") : local;
   }

   private static final Pattern YOU = Pattern.compile("(?i)(?<![A-Za-z0-9_'])you(?![A-Za-z0-9_'])");

   /**
    * Many servers say "You" instead of your name ("You killed Bob", "Bob was slain by you"). Writing your name
    * in its place lets every rule read those lines; who killed whom stays the same, so "Bob killed you" still
    * makes Bob the killer and is never counted.
    */
   public static String youAsName(String message, String local, Set<String> online) {
      if (online.contains("You") || online.contains("you")) {
         return message;
      }

      return YOU.matcher(message).replaceAll(Matcher.quoteReplacement(local));
   }

   public static boolean mentionsYou(String message, String local) {
      return message.contains(local) || YOU.matcher(message).find();
   }

   /**
    * Who the chat says killed whom, whoever the killer is: the exact rules first, then the shapes searched
    * anywhere in the line. Used to notice when another player got the kill on someone you had hit, so that
    * death is not counted as yours. Both names must be online players and different from each other.
    */
   public static Optional<KillRules.Kill> anyKill(String message, List<Pattern> exact, List<Pattern> search, Set<String> online) {
      String plain = clean(message);
      if (plain.length() > 1024 || plain.startsWith("<")) {
         return Optional.empty();
      }

      for (Pattern pattern : exact) {
         Matcher match = pattern.matcher(plain);
         if (match.matches() && pattern.pattern().contains("(?<killer>")) {
            Optional<KillRules.Kill> result = credit(match.group("killer"), match.group("victim"), online);
            if (result.isPresent()) {
               return result;
            }
         }
      }

      for (Pattern pattern : search) {
         if (!pattern.pattern().contains("(?<killer>")) {
            continue;
         }

         Matcher match = pattern.matcher(plain);
         while (match.find()) {
            String before = plain.substring(0, match.start());
            if (before.contains(":") || before.contains("<")) {
               continue;
            }

            Optional<KillRules.Kill> result = credit(match.group("killer"), match.group("victim"), online);
            if (result.isPresent()) {
               return result;
            }
         }
      }

      return Optional.empty();
   }

   /** A vanilla death message (by translation key), whoever the killer is. */
   public static Optional<KillRules.Kill> anyKillFromTranslation(String key, List<String> args, Set<String> online) {
      return VanillaDeathMessages.englishTemplates().containsKey(key) && args.size() >= 2
         ? credit(clean(args.get(1)), clean(args.get(0)), online)
         : Optional.empty();
   }

   private static Optional<KillRules.Kill> credit(String killer, String victim, Set<String> online) {
      return !killer.equalsIgnoreCase(victim) && online.contains(killer) && online.contains(victim)
         ? Optional.of(new KillRules.Kill(killer, victim))
         : Optional.empty();
   }

   /**
    * Writes your real name in place of a nickname the server shows for you, so rules that look for your
    * name also see lines that use the nickname. Only whole words are replaced.
    */
   public static String aliasAsName(String message, String alias, String local) {
      if (alias == null || alias.length() < 3 || alias.equals(local)) {
         return message;
      }

      return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(alias) + "(?![A-Za-z0-9_])").matcher(message).replaceAll(Matcher.quoteReplacement(local));
   }

   private static final Pattern NAME_TOKEN = Pattern.compile("[A-Za-z0-9_]{1,16}");
   private static final Pattern KILL_WORD = Pattern.compile(
      "(?i)\\b(?:killed|kills|slain|slew|slayed|eliminated|defeated|destroyed|murdered|finished|obliterated|annihilated|blown|blew"
         + "|exploded|crystall?ed|popped|anchored|bombed|nuked|shot|sniped|knocked|pushed|doomed|died|fell|perished|lost|beat|won"
         + "|rekt|wrecked|clapped|deleted|bested|one-?tapped|pwned|owned|taken\\s+out|took\\s+out|sent\\s+to|wiped|smoked|outplayed|fragged|demolished|crushed|dominated|humiliated|eviscerated|executed|slaughtered|smashed|stomped|bodied|dismantled|erased|vaporized|terminated|neutralized|dispatched|shattered|butchered|bonked|melted|cooked|fried|farmed|disintegrated|decimated|massacred|squashed|pummeled|pummelled|zapped|sliced|stabbed|impaled|beheaded|knocked\\s+out)\\b"
   );
   /** Words right after a name that mean that player is the one who died or lost. */
   private static final Pattern LOSER_VERB = Pattern.compile("(?i)^\\W*(?:was|were|got|has\\s+been|have\\s+been|has\\s+(?:died|fallen|perished|lost)|is|died|fell|perished|lost|tried|hit|drowned|burned|burnt|went|suffocated|starved|froze|withered)\\b");
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
      int localLength = 0;
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
         if (token.equals(local) || token.equalsIgnoreCase("you")) {
            // Custom messages often say "You" instead of your name.
            if (localAt < 0) {
               localAt = names.start();
               localLength = token.length();
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

      boolean localActiveFirst = localAt < otherAt
         && !LOSER_VERB.matcher(plain.substring(localAt + localLength)).find()
         && KILL_WORD.matcher(plain.substring(localAt, otherAt)).find();
      boolean otherIsLoser = localAt > otherAt && LOSER_VERB.matcher(plain.substring(otherAt + other.length())).find();
      // "Bob lost to You" and "Bob was killed by You" count; "Bob won against You" (you lost) does not.
      return localActiveFirst || otherIsLoser && KILLER_PREFIX.matcher(beforeLocal).find()
         ? validate(local, other, local, online)
         : Optional.empty();
   }

   private static final Pattern YOU_DIED = Pattern.compile("(?i)^\\W*(?:you\\s+(?:died|were\\s+(?:killed|slain|eliminated|defeated)|lost|have\\s+died|got\\s+killed)|game\\s+over|defeat)\\b");

   /**
    * True when a line says you died or lost: a vanilla death message about you, a rule match with you as the
    * victim, or common wording ("Bob killed You", "You were slain by Bob", "You died"). Used to keep your own
    * deaths out of kill clips.
    */
   public static boolean isOwnDeath(String message, List<Pattern> patterns, String local, Set<String> online) {
      String plain = clean(message);
      if (plain.length() > 300 || plain.startsWith("<") || plain.matches("^[^:]{0,48}:\\s.*")) {
         return false;
      }

      if (YOU_DIED.matcher(plain).find()) {
         return true;
      }

      for (Pattern pattern : patterns) {
         Matcher match = pattern.matcher(plain);
         if (match.matches() && local.equals(match.group("victim")) && !local.equals(match.group("killer"))) {
            return true;
         }
      }

      if (!KILL_WORD.matcher(plain).find()) {
         return false;
      }

      int localAt = -1;
      int localLength = 0;
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
         if ((token.equals(local) || token.equalsIgnoreCase("you")) && localAt < 0) {
            localAt = names.start();
            localLength = token.length();
         } else if (other == null && online.contains(token) && !token.equals(local)) {
            other = token;
            otherAt = names.start();
         }
      }

      if (localAt < 0) {
         return false;
      }

      String afterLocal = plain.substring(localAt + localLength);
      if (other == null) {
         // "You died", "Steve fell from a high place" with no other player named.
         return LOSER_VERB.matcher(afterLocal).find();
      }

      boolean otherFirst = otherAt < localAt
         && !LOSER_VERB.matcher(plain.substring(otherAt + other.length())).find()
         && KILL_WORD.matcher(plain.substring(otherAt, localAt)).find()
         && !OTHER_IS_KILLER.matcher(plain.substring(0, localAt)).find();
      boolean localLost = localAt < otherAt && LOSER_VERB.matcher(afterLocal).find()
         && KILLER_PREFIX.matcher(plain.substring(0, otherAt)).find();
      return otherFirst || localLost;
   }

   /** True for a vanilla death message (by translation key) about you. */
   public static boolean isOwnDeathTranslation(String key, List<String> args, String local) {
      return VanillaDeathMessages.englishTemplates().containsKey(key) && !args.isEmpty() && local.equals(clean(args.get(0)));
   }

   private static boolean isNameChar(char c) {
      return Character.isLetterOrDigit(c) || c == '_';
   }

   private static Optional<KillRules.Kill> validate(String killer, String victim, String local, Set<String> online) {
      return killer.equalsIgnoreCase(local) && !victim.equalsIgnoreCase(local) && online.contains(victim) ? Optional.of(new KillRules.Kill(killer, victim)) : Optional.empty();
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
