package io.hearth.vote;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Enough of an ICS file to know when somebody is busy, and deliberately no more.
 *
 * <b>Only the windows come out. Never the words.</b> An ICS carries who somebody was meeting, about
 * what, and where — and a scheduler needs none of it. Keeping the summaries would make this the most
 * sensitive table on the machine in exchange for nothing a vote can use, so `SUMMARY`,
 * `DESCRIPTION`, `LOCATION` and `ATTENDEE` are read past and dropped on the floor.
 *
 * <b>This is not a calendar library and must not become one.</b> There is no RRULE expansion, no
 * VTIMEZONE resolution, no VALARM, no attendee status. What that costs is honest and worth writing
 * down: <b>a repeating event is seen once, on its first occurrence</b>, so a weekly commitment looks
 * like a single evening. The alternative is implementing recurrence, which is a genuinely hard
 * corner of the spec and the place every naive calendar implementation goes wrong.
 *
 * That limitation is safe in the direction it fails: a missed busy window makes this server propose
 * a time somebody then votes `blocked` on, which is the mechanism working. A *wrongly* busy window
 * would silently remove a good evening and nobody would ever know it had been considered.
 *
 * <b>Unfolding comes first because everything else depends on it.</b> RFC 5545 wraps long lines at
 * 75 octets and continues them with a leading space; a parser that reads line by line without
 * unfolding sees a truncated DTSTART and a line beginning with a space, and quietly gets the wrong
 * day.
 */
public final class Ics {
  /** the most events read out of one file, so a shared calendar cannot be a denial of service */
  public static final int MAX_EVENTS = 2000;

  /** the biggest file this will read at all */
  public static final int MAX_BYTES = 4 * 1024 * 1024;

  private Ics() {
  }

  /** one window somebody is not free, in epoch millis */
  public record Busy(long start, long end) {
    public boolean overlaps(long from, long to) {
      return start < to && from < end;
    }
  }

  /**
   * Every busy window in a file.
   *
   * A `VEVENT` with no end is treated as an hour, because a calendar entry with a start and no
   * duration is somebody's reminder and an hour is the least surprising guess. An all-day event
   * covers the day in the reader's zone.
   */
  public static List<Busy> busy(String text, ZoneId zone) {
    ArrayList<Busy> windows = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return windows;
    }
    ZoneId here = zone == null ? ZoneOffset.UTC : zone;
    boolean inEvent = false;
    long start = -1;
    long end = -1;
    boolean allDay = false;
    for (String line : unfold(text)) {
      String upper = line.toUpperCase(Locale.ROOT);
      if (upper.startsWith("BEGIN:VEVENT")) {
        inEvent = true;
        start = -1;
        end = -1;
        allDay = false;
        continue;
      }
      if (upper.startsWith("END:VEVENT")) {
        if (inEvent && start > 0) {
          long finish = end > start ? end
              : (allDay ? start + 24L * 60 * 60 * 1000 : start + 60L * 60 * 1000);
          windows.add(new Busy(start, finish));
          if (windows.size() >= MAX_EVENTS) {
            return windows;
          }
        }
        inEvent = false;
        continue;
      }
      if (!inEvent) {
        continue;
      }
      if (upper.startsWith("DTSTART")) {
        allDay = upper.contains("VALUE=DATE") && !upper.contains("VALUE=DATE-TIME");
        start = millisOf(valueOf(line), here);
      } else if (upper.startsWith("DTEND")) {
        end = millisOf(valueOf(line), here);
      }
      // SUMMARY, DESCRIPTION, LOCATION, ATTENDEE and the rest are read past on purpose: see the
      // class note. This parser knows when, never what or who.
    }
    return windows;
  }

  /**
   * RFC 5545 line unfolding: a line beginning with a space or tab continues the one before it.
   *
   * First, because everything downstream is wrong without it. A folded DTSTART read line by line is
   * a truncated timestamp followed by a line starting with a space, and both parse to something --
   * which is how a parser gets the wrong day without failing.
   */
  static List<String> unfold(String text) {
    ArrayList<String> lines = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String raw : text.split("\r\n|\n|\r")) {
      if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
        current.append(raw, 1, raw.length());
        continue;
      }
      if (current.length() > 0) {
        lines.add(current.toString());
      }
      current.setLength(0);
      current.append(raw);
    }
    if (current.length() > 0) {
      lines.add(current.toString());
    }
    return lines;
  }

  /** everything after the first colon; the parameters before it are not values */
  private static String valueOf(String line) {
    int colon = line.indexOf(':');
    return colon < 0 ? "" : line.substring(colon + 1).trim();
  }

  /**
   * The three shapes a date-time comes in, and nothing else.
   *
   * `...Z` is UTC. A bare `YYYYMMDDTHHMMSS` is local to whoever wrote it, and is read in the
   * reader's zone -- which is wrong when the two differ and is the honest limit of not resolving
   * VTIMEZONE. `YYYYMMDD` is an all-day date. Anything else returns -1 and the event is skipped,
   * because a timestamp this cannot read is better dropped than guessed at.
   */
  static long millisOf(String value, ZoneId zone) {
    if (value == null) {
      return -1;
    }
    String clean = value.trim();
    try {
      if (clean.length() == 16 && clean.endsWith("Z")) {
        return Instant.parse(clean.substring(0, 4) + "-" + clean.substring(4, 6) + "-"
            + clean.substring(6, 8) + "T" + clean.substring(9, 11) + ":"
            + clean.substring(11, 13) + ":" + clean.substring(13, 15) + "Z").toEpochMilli();
      }
      if (clean.length() == 15 && clean.charAt(8) == 'T') {
        LocalDateTime local = LocalDateTime.of(
            Integer.parseInt(clean.substring(0, 4)), Integer.parseInt(clean.substring(4, 6)),
            Integer.parseInt(clean.substring(6, 8)), Integer.parseInt(clean.substring(9, 11)),
            Integer.parseInt(clean.substring(11, 13)), Integer.parseInt(clean.substring(13, 15)));
        return local.atZone(zone).toInstant().toEpochMilli();
      }
      if (clean.length() == 8) {
        LocalDate day = LocalDate.of(Integer.parseInt(clean.substring(0, 4)),
            Integer.parseInt(clean.substring(4, 6)), Integer.parseInt(clean.substring(6, 8)));
        return day.atStartOfDay(zone).toInstant().toEpochMilli();
      }
    } catch (RuntimeException ex) {
      return -1;
    }
    return -1;
  }

  /** is somebody busy at any point in this window? */
  public static boolean busyBetween(List<Busy> windows, long from, long to) {
    for (Busy window : windows) {
      if (window.overlaps(from, to)) {
        return true;
      }
    }
    return false;
  }
}
