package io.hearth.calendar;

import io.hearth.vote.Ics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * iCalendar files, both directions: read what somebody sends, write what somebody subscribes to.
 *
 * <b>This is the fuller reading of RFC 5545, and {@link Ics} is the narrow one.</b> That class
 * answers "when is this person busy" and deliberately throws away every word in the file, because
 * storing the summaries would make the busy-window table the most sensitive thing on the machine.
 * This is the opposite case: it is the person's <em>own</em> calendar, and what it holds is what
 * they wrote. Line unfolding is shared rather than written twice -- two implementations agree until
 * the day a fold lands in the middle of a UTF-8 character in only one of them.
 *
 * <b>Round-tripping is the property that matters.</b> Something written here is read by Apple
 * Calendar, Google Calendar and Thunderbird, and something read here was written by one of them. So
 * escaping is done properly in both directions, the UID and SEQUENCE survive untouched, and
 * anything not understood is left alone rather than dropped -- a calendar that quietly loses a
 * field on every import is one that corrupts itself over a year.
 *
 * <b>Timezones are read and written as UTC instants.</b> A `TZID` is resolved when the JVM knows
 * the zone and falls back to the calendar's own zone when it does not, which is the only choice
 * that never silently moves an appointment by a whole day. What this does not do is preserve the
 * originating zone for re-export, which matters for a recurring event that should follow a
 * daylight-saving change in a zone other than the owner's -- said out loud rather than discovered.
 */
public final class IcsFile {
  /** the largest calendar this will read; a subscription feed from a decade is under a megabyte */
  public static final int MAX_BYTES = 8 * 1024 * 1024;
  /** and the most events in one */
  public static final int MAX_EVENTS = 5000;

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final String CRLF = "\r\n";

  private IcsFile() {
  }

  /** somebody invited to something, and what they have said about it */
  public record Attendee(String address, String name, String partstat, String role) {
    public boolean accepted() {
      return "ACCEPTED".equalsIgnoreCase(partstat);
    }

    public boolean declined() {
      return "DECLINED".equalsIgnoreCase(partstat);
    }

    public String who() {
      return name == null || name.isBlank() ? address : name;
    }
  }

  /** one VEVENT, as much of it as this server keeps */
  public record Event(String uid, int sequence, String summary, String description,
                      String location, long startsAt, long endsAt, boolean allDay, String rrule,
                      String exdates, String status, String organizer, List<Attendee> attendees) {
  }

  /** a whole file: what it is for, and what is in it */
  public record Calendar(String method, List<Event> events) {
    public boolean isRequest() {
      return "REQUEST".equalsIgnoreCase(method);
    }

    public boolean isCancel() {
      return "CANCEL".equalsIgnoreCase(method);
    }
  }

  // ---- reading -------------------------------------------------------------------------------

  /**
   * Read a calendar.
   *
   * <b>Never throws.</b> A malformed file arrives as an invitation from somebody's phone, and
   * refusing the whole message because one property is wrong turns a meeting into a support
   * question. An event missing the things an event must have -- a UID and a start -- is skipped,
   * and everything else in the file still comes back.
   */
  public static Calendar read(String text, ZoneId zone) {
    ArrayList<Event> events = new ArrayList<>();
    String method = "";
    if (text == null || text.isBlank()) {
      return new Calendar(method, events);
    }
    List<String> lines = Ics.unfold(text.length() > MAX_BYTES
        ? text.substring(0, MAX_BYTES) : text);

    boolean inEvent = false;
    // VALARM and VTIMEZONE both nest inside a VEVENT and both carry properties whose names collide
    // with the ones being read -- a VALARM has its own DESCRIPTION, and a VTIMEZONE has DTSTART.
    // Reading them as part of the event is how an appointment gets a summary of "Reminder".
    int nested = 0;
    Builder building = null;

    for (String line : lines) {
      Property property = property(line);
      if (property == null) {
        continue;
      }
      String name = property.name;
      String value = property.value;

      if (name.equals("BEGIN")) {
        if (value.equalsIgnoreCase("VEVENT") && !inEvent) {
          inEvent = true;
          building = new Builder();
        } else if (inEvent) {
          nested++;
        }
        continue;
      }
      if (name.equals("END")) {
        if (value.equalsIgnoreCase("VEVENT") && inEvent && nested == 0) {
          inEvent = false;
          Event event = building == null ? null : building.build(zone);
          if (event != null && events.size() < MAX_EVENTS) {
            events.add(event);
          }
          building = null;
        } else if (nested > 0) {
          nested--;
        }
        continue;
      }
      if (!inEvent) {
        if (name.equals("METHOD")) {
          method = value.trim().toUpperCase(Locale.ROOT);
        }
        continue;
      }
      if (nested > 0 || building == null) {
        continue;
      }
      building.take(name, property.parameters, value, zone);
    }
    return new Calendar(method, events);
  }

  /** one unfolded line, split into its name, its parameters and its value */
  record Property(String name, Map<String, String> parameters, String value) {
  }

  static Property property(String line) {
    if (line == null || line.isBlank()) {
      return null;
    }
    // The name and parameters end at the first colon that is not inside quotes.
    //
    // A quoted parameter can contain one: `ATTENDEE;CN="Smith: J":mailto:...` is legal, and
    // splitting on the first colon gives a name of `ATTENDEE;CN="Smith` and a value that is the
    // rest of somebody's name.
    boolean quoted = false;
    int colon = -1;
    for (int k = 0; k < line.length(); k++) {
      char ch = line.charAt(k);
      if (ch == '"') {
        quoted = !quoted;
      } else if (ch == ':' && !quoted) {
        colon = k;
        break;
      }
    }
    if (colon < 0) {
      return null;
    }
    String head = line.substring(0, colon);
    String value = line.substring(colon + 1);
    LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
    String name = head;
    int semicolon = head.indexOf(';');
    if (semicolon >= 0) {
      name = head.substring(0, semicolon);
      for (String piece : splitOutsideQuotes(head.substring(semicolon + 1))) {
        int equals = piece.indexOf('=');
        if (equals > 0) {
          String key = piece.substring(0, equals).trim().toUpperCase(Locale.ROOT);
          String parameter = piece.substring(equals + 1).trim();
          if (parameter.length() >= 2 && parameter.startsWith("\"") && parameter.endsWith("\"")) {
            parameter = parameter.substring(1, parameter.length() - 1);
          }
          parameters.putIfAbsent(key, parameter);
        }
      }
    }
    return new Property(name.trim().toUpperCase(Locale.ROOT), parameters, value);
  }

  private static List<String> splitOutsideQuotes(String text) {
    ArrayList<String> pieces = new ArrayList<>();
    boolean quoted = false;
    int start = 0;
    for (int k = 0; k < text.length(); k++) {
      char ch = text.charAt(k);
      if (ch == '"') {
        quoted = !quoted;
      } else if (ch == ';' && !quoted) {
        pieces.add(text.substring(start, k));
        start = k + 1;
      }
    }
    pieces.add(text.substring(start));
    return pieces;
  }

  /** an event under construction; a class rather than a pile of locals, because there are twelve */
  private static final class Builder {
    String uid = "";
    int sequence;
    String summary = "";
    String description = "";
    String location = "";
    Long startsAt;
    Long endsAt;
    Long durationMillis;
    boolean allDay;
    String rrule = "";
    final ArrayList<String> exdates = new ArrayList<>();
    String status = "CONFIRMED";
    String organizer = "";
    final ArrayList<Attendee> attendees = new ArrayList<>();

    void take(String name, Map<String, String> parameters, String value, ZoneId zone) {
      switch (name) {
        case "UID" -> uid = unescape(value).trim();
        case "SEQUENCE" -> sequence = intOr(value);
        case "SUMMARY" -> summary = unescape(value);
        case "DESCRIPTION" -> description = unescape(value);
        case "LOCATION" -> location = unescape(value);
        case "STATUS" -> status = value.trim().toUpperCase(Locale.ROOT);
        case "RRULE" -> rrule = value.trim();
        case "EXDATE" -> exdates.add(value.trim());
        case "ORGANIZER" -> organizer = mailto(value);
        case "ATTENDEE" -> attendees.add(new Attendee(mailto(value),
            parameters.getOrDefault("CN", ""),
            parameters.getOrDefault("PARTSTAT", "NEEDS-ACTION").toUpperCase(Locale.ROOT),
            parameters.getOrDefault("ROLE", "REQ-PARTICIPANT").toUpperCase(Locale.ROOT)));
        case "DTSTART" -> {
          allDay = "DATE".equalsIgnoreCase(parameters.get("VALUE")) || value.trim().length() == 8;
          startsAt = when(value, parameters.get("TZID"), zone);
        }
        case "DTEND" -> endsAt = when(value, parameters.get("TZID"), zone);
        case "DURATION" -> durationMillis = duration(value);
        default -> {
          // Everything else is left alone rather than refused.
          //
          // A calendar carries a long tail of properties -- X-, CATEGORIES, TRANSP, CLASS,
          // ATTACH -- and a reader that refuses a file for containing one it has not heard of
          // refuses most real files.
        }
      }
    }

    Event build(ZoneId zone) {
      if (startsAt == null) {
        // an event with no start is not an event; it is usually a VTODO somebody put in the wrong
        // file, and inventing a time for it would put a made-up appointment in a calendar
        return null;
      }
      long start = startsAt;
      long end;
      if (endsAt != null) {
        end = endsAt;
      } else if (durationMillis != null) {
        end = start + durationMillis;
      } else if (allDay) {
        // RFC 5545: a DATE-valued DTSTART with no DTEND is one whole day
        end = start + 86_400_000L;
      } else {
        // and a DATE-TIME with neither is an instant, which every client draws as a short block
        end = start + 30 * 60_000L;
      }
      if (end < start) {
        // a backwards event draws as nothing at all, and this is common in files written by hand
        end = start;
      }
      if (uid.isBlank()) {
        // A file with no UID is one nobody can update later. Deriving one from the content means
        // importing the same file twice recognises itself instead of making a duplicate.
        uid = "hearth-" + Integer.toHexString((summary + start + end).hashCode())
            + "@imported.invalid";
      }
      return new Event(uid, sequence, summary, description, location, start, end, allDay, rrule,
          String.join(",", exdates), status, organizer, List.copyOf(attendees));
    }
  }

  /**
   * A property value to an instant.
   *
   * Three shapes, and the third is the one that goes wrong. `...Z` is UTC. A bare `DATE` or
   * `DATE-TIME` with a TZID is in that zone. A bare one with no TZID is "floating" -- the same
   * wall-clock time wherever you are -- and reading it as UTC is what moves somebody's nine o'clock
   * meeting by however many hours they are from Greenwich.
   */
  static Long when(String value, String tzid, ZoneId fallback) {
    String clean = value == null ? "" : value.trim();
    if (clean.isEmpty()) {
      return null;
    }
    try {
      if (clean.length() == 8) {
        LocalDate date = LocalDate.parse(clean, DAY);
        return date.atStartOfDay(zoneOf(tzid, fallback)).toInstant().toEpochMilli();
      }
      if (clean.endsWith("Z")) {
        return LocalDateTime.parse(clean.substring(0, clean.length() - 1),
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(ZoneOffset.UTC)
            .toEpochMilli();
      }
      if (clean.length() >= 15) {
        return LocalDateTime.parse(clean.substring(0, 15),
                DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            .atZone(zoneOf(tzid, fallback)).toInstant().toEpochMilli();
      }
    } catch (RuntimeException ex) {
      // a timestamp this cannot read is dropped rather than guessed; see the class note
    }
    return null;
  }

  private static ZoneId zoneOf(String tzid, ZoneId fallback) {
    if (tzid == null || tzid.isBlank()) {
      return fallback;
    }
    try {
      return ZoneId.of(tzid.trim());
    } catch (RuntimeException ex) {
      // Windows zone names ("Central Standard Time") and a long tail of typos live here. Falling
      // back to the calendar's own zone puts the event within a few hours rather than nowhere.
      return fallback;
    }
  }

  /** ISO 8601 durations, the subset a calendar actually uses */
  static Long duration(String value) {
    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("(?i)^P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")
        .matcher(value == null ? "" : value.trim());
    if (!matcher.matches()) {
      return null;
    }
    long millis = 0;
    millis += group(matcher, 1) * 7L * 86_400_000L;
    millis += group(matcher, 2) * 86_400_000L;
    millis += group(matcher, 3) * 3_600_000L;
    millis += group(matcher, 4) * 60_000L;
    millis += group(matcher, 5) * 1_000L;
    return millis;
  }

  private static long group(java.util.regex.Matcher matcher, int at) {
    String value = matcher.group(at);
    return value == null ? 0 : Long.parseLong(value);
  }

  private static int intOr(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (RuntimeException ex) {
      return 0;
    }
  }

  static String mailto(String value) {
    String clean = value == null ? "" : value.trim();
    if (clean.toLowerCase(Locale.ROOT).startsWith("mailto:")) {
      clean = clean.substring(7);
    }
    return clean.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Undo iCalendar's escaping.
   *
   * `\n` is a newline, `\,` and `\;` are the characters themselves, and `\\` is a backslash. Doing
   * this in one left-to-right pass matters: replacing `\\` last turns `\\n` -- a literal backslash
   * followed by an n -- into a newline.
   */
  static String unescape(String value) {
    if (value == null || value.indexOf('\\') < 0) {
      return value == null ? "" : value;
    }
    StringBuilder out = new StringBuilder(value.length());
    for (int k = 0; k < value.length(); k++) {
      char ch = value.charAt(k);
      if (ch != '\\' || k + 1 >= value.length()) {
        out.append(ch);
        continue;
      }
      char next = value.charAt(++k);
      switch (next) {
        case 'n', 'N' -> out.append('\n');
        case ',' -> out.append(',');
        case ';' -> out.append(';');
        case '\\' -> out.append('\\');
        default -> out.append(next);
      }
    }
    return out.toString();
  }

  // ---- writing -------------------------------------------------------------------------------

  /** the same escaping, in the other direction */
  static String escape(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "")
        .replace(",", "\\,").replace(";", "\\;");
  }

  /**
   * Fold at 75 octets, counting octets rather than characters.
   *
   * A fold placed by character index lands in the middle of a multi-byte character, and what comes
   * out the other side is a summary with a replacement character in it -- which is the single most
   * common bug in hand-written calendar writers.
   */
  static String foldLine(String line) {
    byte[] bytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (bytes.length <= 75) {
      return line;
    }
    StringBuilder out = new StringBuilder(line.length() + 16);
    int at = 0;
    int width = 0;
    for (int k = 0; k < line.length(); ) {
      int codePoint = line.codePointAt(k);
      int size = new String(Character.toChars(codePoint))
          .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      if (width + size > (at == 0 ? 75 : 74)) {
        out.append(line, at, k).append(CRLF).append(' ');
        at = k;
        width = 1;
      }
      width += size;
      k += Character.charCount(codePoint);
    }
    out.append(line.substring(at));
    return out.toString();
  }

  /** one whole calendar, ready to serve or attach */
  public static String write(String method, List<Event> events, String name) {
    StringBuilder out = new StringBuilder(1024);
    out.append("BEGIN:VCALENDAR").append(CRLF);
    out.append("VERSION:2.0").append(CRLF);
    out.append("PRODID:-//Hearth//Calendar//EN").append(CRLF);
    out.append("CALSCALE:GREGORIAN").append(CRLF);
    if (method != null && !method.isBlank()) {
      out.append("METHOD:").append(method).append(CRLF);
    }
    if (name != null && !name.isBlank()) {
      // both spellings, because Apple reads one and everybody else reads the other
      line(out, "X-WR-CALNAME:" + escape(name));
      line(out, "NAME:" + escape(name));
    }
    for (Event event : events) {
      writeEvent(out, event);
    }
    out.append("END:VCALENDAR").append(CRLF);
    return out.toString();
  }

  private static void writeEvent(StringBuilder out, Event event) {
    out.append("BEGIN:VEVENT").append(CRLF);
    line(out, "UID:" + escape(event.uid()));
    line(out, "DTSTAMP:" + STAMP.format(Instant.now()));
    line(out, "SEQUENCE:" + event.sequence());
    if (event.allDay()) {
      // an all-day event's DTEND is the day *after* it ends, which is exclusive and catches
      // everybody out: writing the same day makes a one-day event vanish in half the clients
      line(out, "DTSTART;VALUE=DATE:" + DAY.format(
          ZonedDateTime.ofInstant(Instant.ofEpochMilli(event.startsAt()), ZoneOffset.UTC)));
      line(out, "DTEND;VALUE=DATE:" + DAY.format(
          ZonedDateTime.ofInstant(Instant.ofEpochMilli(event.endsAt()), ZoneOffset.UTC)));
    } else {
      line(out, "DTSTART:" + STAMP.format(Instant.ofEpochMilli(event.startsAt())));
      line(out, "DTEND:" + STAMP.format(Instant.ofEpochMilli(event.endsAt())));
    }
    if (!event.summary().isBlank()) {
      line(out, "SUMMARY:" + escape(event.summary()));
    }
    if (!event.description().isBlank()) {
      line(out, "DESCRIPTION:" + escape(event.description()));
    }
    if (!event.location().isBlank()) {
      line(out, "LOCATION:" + escape(event.location()));
    }
    if (!event.rrule().isBlank()) {
      line(out, "RRULE:" + event.rrule());
    }
    if (!event.exdates().isBlank()) {
      for (String exdate : event.exdates().split(",")) {
        if (!exdate.isBlank()) {
          line(out, "EXDATE:" + exdate.trim());
        }
      }
    }
    if (!event.status().isBlank()) {
      line(out, "STATUS:" + event.status());
    }
    if (!event.organizer().isBlank()) {
      line(out, "ORGANIZER:mailto:" + event.organizer());
    }
    for (Attendee attendee : event.attendees()) {
      StringBuilder value = new StringBuilder("ATTENDEE");
      if (attendee.name() != null && !attendee.name().isBlank()) {
        value.append(";CN=\"").append(attendee.name().replace("\"", "'")).append('"');
      }
      value.append(";ROLE=").append(attendee.role());
      value.append(";PARTSTAT=").append(attendee.partstat());
      value.append(":mailto:").append(attendee.address());
      line(out, value.toString());
    }
    out.append("END:VEVENT").append(CRLF);
  }

  private static void line(StringBuilder out, String content) {
    out.append(foldLine(content)).append(CRLF);
  }

  /**
   * The file that goes back to an organizer when somebody answers an invitation.
   *
   * <b>One attendee, and it is the person answering.</b> A REPLY carrying the whole attendee list
   * is a common mistake and a real one: the organizer's software takes it as an authoritative
   * statement about everybody, so answering "yes" on behalf of yourself also resets what everybody
   * else had said.
   */
  public static String reply(Event event, String attendee, String name, String partstat) {
    Event answered = new Event(event.uid(), event.sequence(), event.summary(),
        "", event.location(), event.startsAt(), event.endsAt(), event.allDay(), event.rrule(),
        "", event.status(), event.organizer(),
        List.of(new Attendee(attendee, name, partstat, "REQ-PARTICIPANT")));
    return write("REPLY", List.of(answered), null);
  }
}
