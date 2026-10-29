package dev.killclip.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VanillaDeathMessages {
   private static final Pattern ARGUMENT = Pattern.compile("%([123])\\$s");
   private static final Map<String, String> ENGLISH = loadEnglish();

   private VanillaDeathMessages() {
   }

   public static Map<String, String> englishTemplates() {
      return ENGLISH;
   }

   private static Map<String, String> loadEnglish() {
      try {
         Map var3;
         try (InputStream stream = VanillaDeathMessages.class.getResourceAsStream("/killclip/credited_deaths.properties")) {
            if (stream == null) {
               throw new IllegalStateException("Missing credited death-message resource");
            }

            Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            Map<String, String> result = new TreeMap<>();
            properties.forEach((key, value) -> result.put((String)key, (String)value));
            var3 = Collections.unmodifiableMap(result);
         }

         return var3;
      } catch (IOException var6) {
         throw new UncheckedIOException(var6);
      }
   }

   public static List<Pattern> compile(Collection<String> templates, boolean mcpvpDecoration) {
      return new LinkedHashSet<>(templates).stream().map(template -> compileTemplate(template, mcpvpDecoration)).toList();
   }

   static Pattern compileTemplate(String template, boolean mcpvpDecoration) {
      if (template.contains("%1$s") && template.contains("%2$s")) {
         StringBuilder regex = new StringBuilder("^");
         if (mcpvpDecoration) {
            regex.append("(?:[^\\r\\n:]{1,48}\\|\\s*)?");
         }

         Matcher placeholders = ARGUMENT.matcher(template);
         Set<String> seen = new HashSet<>();

         int offset;
         for (offset = 0; placeholders.find(); offset = placeholders.end()) {
            regex.append(Pattern.quote(template.substring(offset, placeholders.start())));
            String var7 = placeholders.group(1);

            String name = switch (var7) {
               case "1" -> "victim";
               case "2" -> "killer";
               default -> "weapon";
            };
            if (!seen.add(name)) {
               regex.append("\\k<").append(name).append('>');
            } else if (name.equals("weapon")) {
               regex.append("(?<weapon>.{1,256}?)");
            } else {
               regex.append("(?<").append(name).append(">[A-Za-z0-9_]{1,16})");
            }
         }

         regex.append(Pattern.quote(template.substring(offset)));
         if (mcpvpDecoration) {
            regex.append("(?:\\s+\\d+(?:\\.\\d+)?/\\d+(?:\\.\\d+)?\\s*[♥❤]️?)?");
         }

         return Pattern.compile(regex.append('$').toString());
      } else {
         throw new IllegalArgumentException("A credited death template needs victim and killer arguments");
      }
   }
}
