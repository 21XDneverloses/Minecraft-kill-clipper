import dev.killclip.core.KillRules;
import dev.killclip.core.VanillaDeathMessages;
import java.util.Set;

public final class DeathMessagesTest {
   public static void main(String[] args) {
      var templates = VanillaDeathMessages.englishTemplates();
      check(templates.size() > 20, "credited death messages load from the jar");
      check(templates.get("death.attack.arrow").contains("%2$s"), "arrow death names the killer");
      var patterns = VanillaDeathMessages.compile(templates.values(), true);
      Set<String> online = Set.of("Me", "Bob");
      check(KillRules.fromMessage("Bob was blown up by Me", patterns, "Me", online).isPresent(), "plain crystal kill message counts");
      check(KillRules.fromMessage("[Duels] | Bob was blown up by Me 6.5/20 ❤", patterns, "Me", online).isPresent(), "decorated crystal kill message counts");
      check(KillRules.fromMessage("Bob tried to swim in lava to escape Me", patterns, "Me", online).isPresent(), "lava escape counts");
      check(KillRules.fromMessage("Bob hit the ground too hard while trying to escape Me", patterns, "Me", online).isPresent(), "fall escape counts");
      check(KillRules.fromMessage("Me was blown up by Bob", patterns, "Me", online).isEmpty(), "your own death is not a kill");
      System.out.println("all DeathMessages checks passed");
   }

   static void check(boolean ok, String name) {
      if (!ok) {
         throw new AssertionError("FAILED: " + name);
      }
   }
}
