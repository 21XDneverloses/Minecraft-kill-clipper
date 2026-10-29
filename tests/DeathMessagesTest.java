import dev.killclip.core.KillRules;
import dev.killclip.core.ServerDefaults;
import java.util.regex.Pattern;
import dev.killclip.core.VanillaDeathMessages;
import java.util.Set;

public final class DeathMessagesTest {
   public static void main(String[] args) {
      var templates = VanillaDeathMessages.englishTemplates();
      check(templates.size() > 20, "credited death messages load from the jar");
      check(templates.get("death.attack.arrow").contains("%2$s"), "arrow death names the killer");
      var patterns = VanillaDeathMessages.compile(templates.values(), true);
      Set<String> online = Set.of("Me", "Bob", "Locked_Gears");
      check(KillRules.fromMessage("Bob was blown up by Me", patterns, "Me", online).isPresent(), "plain crystal kill message counts");
      check(KillRules.fromMessage("[Duels] | Bob was blown up by Me 6.5/20 ❤", patterns, "Me", online).isPresent(), "decorated crystal kill message counts");
      check(KillRules.fromMessage("Bob tried to swim in lava to escape Me", patterns, "Me", online).isPresent(), "lava escape counts");
      check(KillRules.fromMessage("Bob hit the ground too hard while trying to escape Me", patterns, "Me", online).isPresent(), "fall escape counts");
      check(KillRules.fromMessage("Me was blown up by Bob", patterns, "Me", online).isEmpty(), "your own death is not a kill");
      var universal = ServerDefaults.UNIVERSAL.stream().map(r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE)).toList();
      for (String line : new String[] {
         "☠ Bob has been killed by Me", "[Duels] Me killed Bob (4.5❤)", "Bob was slain by Me 3/20 ❤", "Bob was blown up by Me",
         "Bob lost the duel to Me", "Me won the duel against Bob", "Bob was knocked into the void by Me", "[VIP] Me eliminated [MVP] Bob!",
         "Bob was shot by Me using [Bow]", "Me crystalled Bob", "Bob was crystaled by Me", "Bob was popped by Me", "Me blew up Bob", "Bob was blown up by Me using End Crystal", "Bob was anchored by Me", "🗡 Locked_Gears has been killed by Me."
      }) {
         check(KillRules.searchMessage(line, universal, "Me", online).isPresent(), "universal: " + line);
      }
      for (String line : new String[] {
         "Bob: Me killed Bob lol", "<Bob> Me killed Bob", "Bob killed Me", "Bob was invited by Me", "Me -> Bob: hi", "Carl was killed by Me"
      }) {
         check(KillRules.searchMessage(line, universal, "Me", online).isEmpty(), "not a kill: " + line);
      }
      for (String line : new String[] {
         "Me killed Bob", "Bob got rekt by Me", "Me destroyed Bob in 12s", "[Arena] Bob was taken out by Me!", "Bob fell to their death fighting Me",
         "☠ Me popped Bob", "Me has won the fight vs Bob", "Bob was sent to the shadow realm by [VIP] Me", "You obliterated Bob!", "You destroyed Bob (+25 coins)", "Bob was demolished by you", "⚔ You eviscerated Bob"
      }) {
         check(KillRules.guess(line, "Me", online).isPresent(), "guess counts: " + line);
      }
      for (String line : new String[] {
         "Bob killed Me", "Me was killed by Bob", "Me died to Bob", "Me lost to Bob", "Me fell while fighting Bob", "Bob: Me killed Bob",
         "<Bob> lol Me killed Bob", "Me killed a Zombie", "Me sent Bob a duel request", "Me killed Bob and Carl", "Bob was slain by Locked_Gears near Me",
         "Me got popped by Bob", "Bob destroyed you", "You were obliterated by Bob", "You lost to Bob", "Bob killed you"
      }) {
         check(KillRules.guess(line, "Me", online.contains("Carl") ? online : Set.of("Me", "Bob", "Carl", "Locked_Gears")).isEmpty(), "guess ignores: " + line);
      }
      System.out.println("all DeathMessages checks passed");
   }

   static void check(boolean ok, String name) {
      if (!ok) {
         throw new AssertionError("FAILED: " + name);
      }
   }
}
