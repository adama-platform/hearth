package io.hearth.calendar;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * iCalendar, written and read: the format every calendar program in the world already speaks.
 *
 * <b>This is the difference between a website that lists events and a community that turns up.</b>
 * An invitation that lands in a calendar is a thing with a reminder attached to it, sitting in the
 * week somebody is already looking at; a link to a page is a thing to remember to click. RFC 5545
 * is old and strange in places, and it is the reason this can work with whatever people already use.
 *
 * <b>No recurrence, deliberately.</b> Every event here is written down on purpose, once. A series
 * expressed as a rule is a thing that keeps happening whether or not anybody decided it should,
 * and the community this is for is one where somebody says "same again next month" and means it --
 * so the second one is a second event, with its own guest list and its own answers.
 *
 * <b>Dates, not instants -- and that decision reaches all the way out here.</b> A community event is
 * "Saturday the 14th", so this writes `DTSTART;VALUE=DATE`, which is an all-day event in every
 * client and asks nobody a timezone question. `start_time` is prose ("doors at 7, music at 8") and
 * goes in the description where a person reads it, because no clock field holds a sentence.
 *
 * Three methods, which is the whole of iMIP for a community:
 *
 * <pre>
 *   REQUEST   here is an event, please answer      (out, on publish and on every change;
 *                                                  in, when somebody mails one to the events
 *                                                  address and it becomes an event here)
 *   CANCEL    it is not happening                  (out, when an event is called off)
 *   REPLY     I am coming / maybe / not            (in, parsed from a reply email)
 *   COUNTER   I would rather it were Tuesday       (in, recorded as a suggestion, never applied)
 * </pre>
 *
 * <b>Folding is not optional.</b> RFC 5545 lines are at most 75 octets and continue with a leading
 * space, and a client that meets a longer one is entitled to do anything at all. The same goes for
 * escaping: a comma, a semicolon or a newline inside a value has to be escaped or it becomes
 * structure. Both are one-line rules that produce a file nobody can read if you get them wrong, and
 * a file that half of clients accept, which is worse.
 */
public final class Ics {
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
  /** what goes in PRODID; a client shows it when something is wrong and it should say who */
  public static final String PRODID = "-//Hearth//Community Calendar//EN";
  private static final int FOLD_AT = 73;

  private Ics() {
  }

  /** what this file is asking the receiving calendar to do */
  public enum Method {
    REQUEST, CANCEL, REPLY, COUNTER, PUBLISH
  }

  /** what somebody's calendar said back */
  public enum Part {
    ACCEPTED, TENTATIVE, DECLINED, NEEDS_ACTION;

    public static Part of(String raw) {
      if (raw == null) {
        return NEEDS_ACTION;
      }
      try {
        return valueOf(raw.trim().toUpperCase().replace('-', '_'));
      } catch (IllegalArgumentException ex) {
        return NEEDS_ACTION;
      }
    }

    /** what this community calls the same thing */
    public Calendar.Answer answer() {
      return switch (this) {
        case ACCEPTED -> Calendar.Answer.going;
        case TENTATIVE -> Calendar.Answer.maybe;
        case DECLINED -> Calendar.Answer.no;
        case NEEDS_ACTION -> null;
      };
    }

    public static Part forAnswer(Calendar.Answer answer) {
      if (answer == null) {
        return NEEDS_ACTION;
      }
      return switch (answer) {
        case going -> ACCEPTED;
        case maybe -> TENTATIVE;
        case no -> DECLINED;
        // a waitlist is this community's idea and has no iCalendar spelling; the honest thing to
        // put on somebody's calendar is a maybe, because that is what a waitlist is
        case waitlist -> TENTATIVE;
      };
    }
  }

  /** one person the invitation is addressed to, and what they have said so far */
  public record Attendee(String email, String name, Part part) {
  }

  // ---- writing -----------------------------------------------------------------------------------

  /**
   * The invitation itself.
   *
   * @param organiser the address a reply comes back to. It has to be an address this server
   *     actually receives at, or every answer people send from their calendar goes nowhere -- which
   *     is the whole reason `calendar.invites` refuses to be on when inbound mail is off.
   */
  public static String request(Calendar.Event event, String uid, int sequence,
                               String organiser, String organiserName, List<Attendee> attendees,
                               String community, String url, String description) {
    return build(Method.REQUEST, event, uid, sequence, organiser, organiserName, attendees,
        community, url, description, "CONFIRMED");
  }

  /**
   * The same event as a file somebody downloads.
   *
   * `PUBLISH` rather than `REQUEST` because nobody was invited: this is a person taking a copy, and
   * a REQUEST addressed to nobody makes some clients show accept buttons that answer on behalf of
   * an attendee who is not in the file. It keeps the UID, so somebody who downloads it and is later
   * invited properly gets one entry rather than two, and it keeps the ORGANIZER, which is the
   * address an answer can still be sent to.
   */
  public static String publish(Calendar.Event event, String uid, int sequence,
                               String organiser, String organiserName, String community,
                               String url, String description) {
    return build(Method.PUBLISH, event, uid, sequence, organiser, organiserName, List.of(),
        community, url, description, event.cancelled() ? "CANCELLED" : "CONFIRMED");
  }

  /** the same event, called off. Same UID, higher sequence: that is what makes it land. */
  public static String cancel(Calendar.Event event, String uid, int sequence,
                              String organiser, String organiserName, List<Attendee> attendees,
                              String community, String url, String description) {
    return build(Method.CANCEL, event, uid, sequence, organiser, organiserName, attendees,
        community, url, description, "CANCELLED");
  }

  private static String build(Method method, Calendar.Event event, String uid, int sequence,
                              String organiser, String organiserName,
                              List<Attendee> attendees, String community, String url,
                              String description, String status) {
    StringBuilder out = new StringBuilder();
    line(out, "BEGIN:VCALENDAR");
    line(out, "PRODID:" + PRODID);
    line(out, "VERSION:2.0");
    line(out, "CALSCALE:GREGORIAN");
    line(out, "METHOD:" + method.name());
    line(out, "BEGIN:VEVENT");
    line(out, "UID:" + uid);
    line(out, "SEQUENCE:" + sequence);
    line(out, "DTSTAMP:" + STAMP.format(java.time.Instant.now().atZone(ZoneOffset.UTC)));
    // all-day, which is what a community event is. The end is exclusive in iCalendar -- a one day
    // event ends the following morning -- and getting that wrong makes everything a day short.
    line(out, "DTSTART;VALUE=DATE:" + DATE.format(event.startsOn()));
    line(out, "DTEND;VALUE=DATE:" + DATE.format(event.endsOn().plusDays(1)));
    line(out, "SUMMARY:" + escape(event.title()));
    if (description != null && !description.isBlank()) {
      line(out, "DESCRIPTION:" + escape(description));
    }
    if (event.location() != null && !event.location().isBlank()) {
      line(out, "LOCATION:" + escape(event.location()));
    }
    if (url != null && !url.isBlank()) {
      line(out, "URL:" + escape(url));
    }
    line(out, "STATUS:" + status);
    line(out, "ORGANIZER;CN=" + escape(organiserName == null ? community : organiserName)
        + ":mailto:" + organiser);
    for (Attendee attendee : attendees) {
      // RSVP=TRUE is what makes a calendar client show the accept/decline buttons at all, and
      // PARTSTAT carries what they have already said so a re-invitation does not reset it
      line(out, "ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=" + attendee.part().name()
          + ";RSVP=TRUE"
          + (attendee.name() == null || attendee.name().isBlank()
              ? "" : ";CN=" + escape(attendee.name()))
          + ":mailto:" + attendee.email());
    }
    if (method != Method.CANCEL) {
      // a reminder the day before, set by us rather than left to whatever the client defaults to.
      // The point of an invitation is being reminded; a calendar entry nobody is nudged about is a
      // calendar entry somebody reads on the way past.
      line(out, "BEGIN:VALARM");
      line(out, "ACTION:DISPLAY");
      line(out, "DESCRIPTION:" + escape(event.title()));
      line(out, "TRIGGER:-P1D");
      line(out, "END:VALARM");
    }
    line(out, "END:VEVENT");
    line(out, "END:VCALENDAR");
    return out.toString();
  }

  /**
   * A UID that will still be this event in five years.
   *
   * Domain-qualified because the spec asks for global uniqueness and because two communities on one
   * box must not collide. The id and the creation stamp together mean a deleted-and-recreated event
   * is a different thing to a calendar, which is the honest answer -- it is a different event.
   */
  public static String uidFor(long eventId, long createdAtMillis, String domain) {
    return "hearth-" + eventId + "-" + createdAtMillis + "@" + domain;
  }

  /** RFC 5545 line folding: 75 octets, continued with a leading space */
  static void line(StringBuilder out, String value) {
    String rest = value;
    boolean first = true;
    while (!rest.isEmpty()) {
      int take = Math.min(first ? FOLD_AT + 2 : FOLD_AT, rest.length());
      // never split a surrogate pair, which would produce bytes no parser can put back together
      if (take < rest.length() && Character.isHighSurrogate(rest.charAt(take - 1))) {
        take--;
      }
      out.append(first ? "" : " ").append(rest, 0, take).append("\r\n");
      rest = rest.substring(take);
      first = false;
    }
  }

  /** a comma, a semicolon, a backslash or a newline inside a value is structure unless escaped */
  static String escape(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "\\n");
  }

  static String unescape(String value) {
    if (value == null) {
      return "";
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
        default -> out.append(next);
      }
    }
    return out.toString();
  }

  // ---- reading -----------------------------------------------------------------------------------

  /**
   * The event inside an invitation somebody sent us.
   *
   * Only the fields a community event has: what it is called, which days, where, and the words.
   * Everything else in a VEVENT -- attendees, alarms, transparency, categories -- belongs to
   * whoever sent it and is not this calendar's business.
   */
  public record Event(String title, String description, String location, LocalDate startsOn,
                      LocalDate endsOn) {
  }

  /**
   * Read the event out of an invitation.
   *
   * <b>The end date is exclusive in iCalendar and inclusive here</b>, which is the single easiest
   * thing to get wrong in both directions: a one-day event arrives with DTEND the following
   * morning, and storing that verbatim makes every mailed-in event a day longer than it is.
   */
  public static Event eventIn(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String title = null;
    String description = "";
    String location = "";
    LocalDate starts = null;
    LocalDate ends = null;
    boolean endWasADate = false;
    for (String line : unfold(text)) {
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String left = line.substring(0, colon);
      String value = line.substring(colon + 1);
      String name = (left.contains(";") ? left.substring(0, left.indexOf(';')) : left)
          .trim().toUpperCase();
      switch (name) {
        case "SUMMARY" -> title = unescape(value).trim();
        case "DESCRIPTION" -> description = unescape(value).trim();
        case "LOCATION" -> location = unescape(value).trim();
        case "DTSTART" -> starts = date(value);
        case "DTEND" -> {
          ends = date(value);
          endWasADate = left.toUpperCase().contains("VALUE=DATE");
        }
        default -> {
        }
      }
    }
    if (title == null || starts == null) {
      return null;
    }
    if (ends == null) {
      ends = starts;
    } else if (endWasADate) {
      // an all-day DTEND is the morning after, so a one-day event says the 15th for the 14th
      ends = ends.minusDays(1);
    }
    if (ends.isBefore(starts)) {
      ends = starts;
    }
    return new Event(title, description, location, starts, ends);
  }

  /** what an incoming calendar file turned out to say */
  public record Incoming(Method method, String uid, int sequence, Map<String, String> attendee,
                         LocalDate proposedStart, String raw) {
    /** the one attendee line that matters: whoever is answering */
    public String attendeeEmail() {
      return attendee.get("email");
    }

    public Part part() {
      return Part.of(attendee.get("PARTSTAT"));
    }

    /** what their calendar said their name was, which is usually a real one */
    public String attendeeName() {
      String name = attendee.get("CN");
      return name == null ? "" : name.trim();
    }
  }

  /**
   * Read a calendar file somebody's program sent back.
   *
   * <b>Deliberately forgiving about shape and strict about meaning.</b> Every mail client on earth
   * folds differently, wraps differently, and disagrees about parameter order and case; refusing on
   * any of that would mean refusing real answers from real people. What it will not do is guess: no
   * METHOD, no UID, or no attendee and this returns null, because a reply that does not say who is
   * answering what is not something to act on.
   *
   * It answers only what the file says. Whether the person is a member, whether the address matches
   * the envelope, whether the sequence is current -- none of that is here, because those are
   * questions about a message rather than about a calendar, and mixing them is how a parser ends up
   * being the thing that decides who may change an RSVP.
   */
  public static Incoming read(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    List<String> lines = unfold(text);
    Method method = null;
    String uid = null;
    int sequence = 0;
    LinkedHashMap<String, String> attendee = null;
    LocalDate proposed = null;
    for (String line : lines) {
      int colon = colonAt(line);
      if (colon < 0) {
        continue;
      }
      String left = line.substring(0, colon);
      String value = line.substring(colon + 1).trim();
      String name = left.contains(";") ? left.substring(0, left.indexOf(';')) : left;
      switch (name.trim().toUpperCase()) {
        case "METHOD" -> {
          try {
            method = Method.valueOf(value.trim().toUpperCase());
          } catch (IllegalArgumentException ex) {
            return null;
          }
        }
        case "UID" -> uid = unescape(value);
        case "SEQUENCE" -> {
          try {
            sequence = Integer.parseInt(value.trim());
          } catch (NumberFormatException ex) {
            sequence = 0;
          }
        }
        case "ATTENDEE" -> {
          // the first attendee wins: a REPLY carries exactly one, and a file that carries several
          // is either a REQUEST we are not reading or something we should not be acting on
          if (attendee == null) {
            attendee = parameters(left);
            attendee.put("email", address(value));
          }
        }
        case "DTSTART" -> proposed = date(value);
        default -> {
        }
      }
    }
    if (method == null || uid == null || uid.isBlank() || attendee == null
        || attendee.get("email") == null) {
      return null;
    }
    return new Incoming(method, uid, sequence, attendee,
        method == Method.COUNTER ? proposed : null, text);
  }

  /** put folded continuation lines back together before anything looks at them */
  public static List<String> unfold(String text) {
    ArrayList<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String raw : text.split("\r\n|\n|\r", -1)) {
      if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
        current.append(raw, 1, raw.length());
        continue;
      }
      if (current.length() > 0) {
        out.add(current.toString());
      }
      current = new StringBuilder(raw);
    }
    if (current.length() > 0) {
      out.add(current.toString());
    }
    return out;
  }

  /** the colon that ends the property name and its parameters, skipping any inside quotes */
  private static int colonAt(String line) {
    boolean quoted = false;
    for (int k = 0; k < line.length(); k++) {
      char ch = line.charAt(k);
      if (ch == '"') {
        quoted = !quoted;
      } else if (ch == ':' && !quoted) {
        return k;
      }
    }
    return -1;
  }

  /** `ATTENDEE;PARTSTAT=ACCEPTED;CN="A Name"` to a map, upper-cased keys, quotes off */
  public static LinkedHashMap<String, String> parameters(String left) {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    String[] parts = left.split(";");
    for (int k = 1; k < parts.length; k++) {
      int equals = parts[k].indexOf('=');
      if (equals <= 0) {
        continue;
      }
      String key = parts[k].substring(0, equals).trim().toUpperCase();
      String value = parts[k].substring(equals + 1).trim();
      if (value.length() >= 2 && value.charAt(0) == '"' && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1);
      }
      out.put(key, value);
    }
    return out;
  }

  /** `mailto:somebody@example.org` to `somebody@example.org`, lowercased */
  static String address(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.toLowerCase().startsWith("mailto:")) {
      trimmed = trimmed.substring("mailto:".length());
    }
    trimmed = trimmed.trim().toLowerCase();
    return trimmed.isEmpty() || trimmed.indexOf('@') <= 0 ? null : trimmed;
  }

  /** a DATE or DATE-TIME, reduced to the day, because a day is all this calendar has */
  static LocalDate date(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.length() < 8) {
      return null;
    }
    try {
      return LocalDate.parse(trimmed.substring(0, 8), DATE);
    } catch (Exception ex) {
      return null;
    }
  }

  /** how many days from now, for the reminder loop's arithmetic */
  public static long daysBetween(LocalDate from, LocalDate to) {
    return ChronoUnit.DAYS.between(from, to);
  }
}
