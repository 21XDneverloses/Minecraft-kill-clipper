import dev.killclip.core.VanillaDeathMessages;

public final class DeathMessagesTest {
   public static void main(String[] args) {
      var templates = VanillaDeathMessages.englishTemplates();
      check(templates.size() > 20, "credited death messages load from the jar");
      check(templates.get("death.attack.arrow").contains("%2$s"), "arrow death names the killer");
      System.out.println("all DeathMessages checks passed");
   }

   static void check(boolean ok, String name) {
      if (!ok) {
         throw new AssertionError("FAILED: " + name);
      }
   }
}
