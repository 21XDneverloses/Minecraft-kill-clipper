import dev.killclip.core.DetectionLog;
import dev.killclip.core.KillRules;
import dev.killclip.core.PendingKills;
import dev.killclip.core.ServerDefaults;
import dev.killclip.core.VanillaDeathMessages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Third-party kill handling, nicknames, case-insensitive names and the detection report. */
public final class DetectionTest {
   public static void main(String[] args) {
      Set<String> online = Set.of("Me", "Bob", "Carl");
      List<Pattern> exact = new ArrayList<>(VanillaDeathMessages.compile(VanillaDeathMessages.englishTemplates().values(), true));
      ServerDefaults.patterns().values().forEach(rules -> rules.forEach(rule -> exact.add(Pattern.compile(rule))));
      List<Pattern> universal = ServerDefaults.UNIVERSAL.stream().map(r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE)).toList();

      // Who got the kill, whoever it was.
      for (String[] c : new String[][] {
         {"Bob was slain by Carl", "Carl", "Bob"}, {"[Duels] | Bob was blown up by Carl 6/20 ❤", "Carl", "Bob"}, {"Carl killed Bob", "Carl", "Bob"},
         {"☠ Bob has been killed by Carl.", "Carl", "Bob"}, {"Bob was knocked into the void by Carl. FINAL KILL!", "Carl", "Bob"},
         {"Bob was slain by Me", "Me", "Bob"}
      }) {
         var kill = KillRules.anyKill(c[0], exact, universal, online);
         check(kill.isPresent() && kill.get().killer().equals(c[1]) && kill.get().victim().equals(c[2]), "any kill: " + c[0]);
      }
      for (String line : new String[] {"<Carl> Bob was slain by Carl", "Carl: Carl killed Bob", "Bob was slain by Zombie", "Bob fell from a high place", "Carl killed Dave"}) {
         check(KillRules.anyKill(line, exact, universal, online).isEmpty(), "no player kill: " + line);
      }
      check(KillRules.anyKillFromTranslation("death.attack.player", List.of("Bob", "Carl"), online).get().killer().equals("Carl"), "vanilla credit");
      check(KillRules.anyKillFromTranslation("death.attack.player", List.of("Bob", "Bob"), online).isEmpty(), "self is not a credit");

      // A death you hit waits, and is dropped if the chat gives it to someone else.
      PendingKills pending = new PendingKills(400L, 3000L);
      check(pending.add("Bob", 1000L), "pending added");
      check(pending.due(1200L).isEmpty(), "not due yet");
      check(pending.creditedToOther("Bob", 1300L), "credited to Carl drops it");
      check(pending.due(2000L).isEmpty(), "dropped death never counts");
      check(!pending.add("Bob", 1500L), "a death the chat already gave away is not re-added");
      check(pending.add("Bob", 9000L), "a later death counts again");
      var due = pending.due(9400L);
      check(due.size() == 1 && due.get(0).getValue() == 9000L, "due keeps the death time");
      pending.add("Carl", 10000L);
      check(pending.drain().size() == 1 && pending.isEmpty(), "drain on leaving");

      // Nicknames and name case.
      check(KillRules.aliasAsName("Bob was slain by Shadow", "Shadow", "Me").equals("Bob was slain by Me"), "own nickname");
      check(KillRules.aliasAsName("Shadowy killed Bob", "Shadow", "Me").equals("Shadowy killed Bob"), "nickname whole word only");
      check(KillRules.aliasAsName("Bob died", "Me", "Me").equals("Bob died"), "same alias no change");
      check(KillRules.searchMessage("me killed Bob", universal, "Me", online).isPresent(), "name case does not matter");
      check(KillRules.searchMessage("Bob killed me", universal, "Me", online).isEmpty(), "lower-case own death still not a kill");

      // The report.
      DetectionLog log = new DetectionLog(3, 2);
      for (int i = 0; i < 5; i++) {
         log.line(1000L * i, "literal", "line " + i, "-");
      }
      log.event(0L, "hit Bob");
      log.event(1L, "KILL counted: Bob");
      log.event(2L, "you died");
      check(log.lines().size() == 3 && log.lines().get(0).endsWith("line 2"), "keeps only the newest lines");
      check(log.events().size() == 2 && log.events().get(1).endsWith("you died"), "keeps only the newest events");
      Map<String, String> details = new LinkedHashMap<>();
      details.put("Server", "example.net");
      List<String> report = log.report("wrong", 5000L, details);
      check(report.get(0).contains("not my kill") && report.contains("Server: example.net") && report.get(report.size() - 1).endsWith("line 4"), "report layout");
      log.line(0L, "literal", "x".repeat(2000), "-");
      check(log.lines().get(2).length() < 700, "long lines are cut");
      System.out.println("all Detection checks passed");
   }

   static void check(boolean ok, String name) {
      if (!ok) {
         throw new AssertionError("FAILED: " + name);
      }
   }
}
