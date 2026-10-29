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
         "☠ Me popped Bob", "Me has won the fight vs Bob", "Bob was sent to the shadow realm by [VIP] Me", "Bob lost to Me", "You obliterated Bob!", "You destroyed Bob (+25 coins)", "Bob was demolished by you", "⚔ You eviscerated Bob"
      }) {
         check(KillRules.guess(line, "Me", online).isPresent(), "guess counts: " + line);
      }
      for (String line : new String[] {
         "Bob killed Me", "Me was killed by Bob", "Me died to Bob", "Me lost to Bob", "Me fell while fighting Bob", "Bob: Me killed Bob",
         "<Bob> lol Me killed Bob", "Me killed a Zombie", "Me sent Bob a duel request", "Me killed Bob and Carl", "Bob was slain by Locked_Gears near Me",
         "Me got popped by Bob", "Bob won against Me", "Bob won the duel against Me", "Bob has won the fight vs Me", "Bob destroyed you", "You were obliterated by Bob", "You lost to Bob", "Bob killed you"
      }) {
         check(KillRules.guess(line, "Me", online.contains("Carl") ? online : Set.of("Me", "Bob", "Carl", "Locked_Gears")).isEmpty(), "guess ignores: " + line);
      }
      // Formats from more servers: Hypixel themed and final kills, The Pit, practice results, anarchy crystals,
      // bounty and lifesteal servers, combat logging, health after names, and lines that say "You".
      for (String line : new String[] {
         "Bob was struck down by Me.", "Bob was knocked into the void by Me. FINAL KILL!", "Bob was turned to dust by Me. FINAL KILL!",
         "Bob was blown to bits by Me's End Crystal", "Bob was sent to the shadow realm by Me!", "KILL! on [120] Bob +12XP +11.50g",
         "DOUBLE KILL! on [XII-87] Bob", "Winner: Me (12.5❤) Loser: Bob", "Winner » Me | Loser » Bob", "Me claimed the $5,000 bounty on Bob",
         "Me stole a heart from Bob", "Bob logged out while in combat with Me", "Bob combat logged against Me", "Me (12.5❤) killed Bob",
         "Bob has died to Me", "Bob [3/20] was slain by Me", "Bob was killed by Me (5.5❤)"
      }) {
         check(KillRules.searchMessage(line, universal, "Me", online).isPresent(), "more servers: " + line);
      }
      for (String line : new String[] {
         "You killed Bob!", "Bob was slain by you", "You have eliminated Bob", "Bob was struck down by You.", "Winner: You Loser: Bob",
         "You claimed the bounty on Bob", "Bob lost the duel to you"
      }) {
         check(KillRules.mentionsYou(line, "Me"), "reaches the rules: " + line);
         check(KillRules.searchMessage(KillRules.youAsName(line, "Me", online), universal, "Me", online).isPresent(), "you as name: " + line);
      }
      for (String line : new String[] {
         "Bob was invited by Me", "Bob was invited to the party by Me", "Bob was kicked from the party by Me", "Bob was hit by Me",
         "Bob was combat tagged by Me", "Bob was killed by Carl, assisted by Me", "Bob was killed by Carl. Assisted by Me",
         "Bob was teleported to Me", "Bob was healed by Me", "Me was struck down by Bob.", "Winner: Bob Loser: Me",
         "Bob claimed the bounty on Me", "Me logged out while in combat with Bob", "Bob was struck down by Me in a duel request from Carl"
      }) {
         check(KillRules.searchMessage(line, universal, "Me", online).isEmpty(), "more servers ignore: " + line);
      }
      for (String line : new String[] {
         "You were killed by Bob", "Bob killed you", "You were struck down by Bob.", "Bob has killed You.", "Winner: Bob Loser: You",
         "You lost the duel to Bob", "Bob: you killed Me lol", "You logged out while in combat with Bob"
      }) {
         check(KillRules.searchMessage(KillRules.youAsName(line, "Me", online), universal, "Me", online).isEmpty(), "your death with You: " + line);
      }
      check(KillRules.youAsName("Thank you Bob", "Me", online).equals("Thank Me Bob"), "you is replaced as a whole word");
      check(KillRules.youAsName("Bob is young", "Me", online).equals("Bob is young"), "young is not you");
      System.out.println("all DeathMessages checks passed");
   }

   static void check(boolean ok, String name) {
      if (!ok) {
         throw new AssertionError("FAILED: " + name);
      }
   }
}
