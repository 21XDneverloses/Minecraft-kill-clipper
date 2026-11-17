package dev.killclip.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * The last server chat lines and what kill detection made of each one (kill, own death, ignored), plus
 * detection events such as hits and confirmed kills. Kept in memory only; written to a file when you report
 * a missed or wrong kill, so the rules can be fixed from what real servers send.
 */
public final class DetectionLog {
   private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
   private final int maxLines;
   private final int maxEvents;
   private final Deque<String> lines = new ArrayDeque<>();
   private final Deque<String> events = new ArrayDeque<>();

   public DetectionLog(int maxLines, int maxEvents) {
      this.maxLines = maxLines;
      this.maxEvents = maxEvents;
   }

   /** One server chat line, its translation keys and the decision made about it. */
   public void line(long wallMs, String keys, String text, String decision) {
      String clipped = text.length() > 512 ? text.substring(0, 512) + "..." : text;
      add(this.lines, this.maxLines, stamp(wallMs) + " | " + decision + " | " + keys + " | " + clipped.replace('\n', ' ').replace('\r', ' '));
   }

   /** Something detection did that is not a chat line: a hit, a death seen in the world, a kill counted. */
   public void event(long wallMs, String what) {
      add(this.events, this.maxEvents, stamp(wallMs) + " | " + what);
   }

   public List<String> lines() {
      return List.copyOf(this.lines);
   }

   public List<String> events() {
      return List.copyOf(this.events);
   }

   public void clear() {
      this.lines.clear();
      this.events.clear();
   }

   /**
    * The report file's text.
    *
    * @param kind     "missed" (a kill was not clipped) or "wrong" (something that was not your kill was clipped)
    * @param details  name/value pairs about the game and settings, in order
    */
   public List<String> report(String kind, long wallMs, Map<String, String> details) {
      List<String> out = new ArrayList<>();
      out.add("Kill Clipper detection report: " + ("wrong".equals(kind) ? "a clip was saved for something that was not my kill" : "my kill was not clipped"));
      out.add("Made " + Instant.ofEpochMilli(wallMs) + " (local time " + stamp(wallMs) + ")");
      out.add("This file stays on your computer. It has recent server chat, which can include private messages: read it before sharing.");
      out.add("");
      details.forEach((name, value) -> out.add(name + ": " + value));
      out.add("");
      out.add("== Detection events, oldest first ==");
      out.addAll(this.events);
      out.add("");
      out.add("== Server chat, oldest first (time | decision | translation keys | text) ==");
      out.addAll(this.lines);
      return out;
   }

   private static String stamp(long wallMs) {
      return TIME.format(Instant.ofEpochMilli(wallMs));
   }

   private static void add(Deque<String> into, int max, String entry) {
      into.addLast(entry);
      while (into.size() > max) {
         into.removeFirst();
      }
   }
}
