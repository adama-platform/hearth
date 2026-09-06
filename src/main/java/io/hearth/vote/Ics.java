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
 * <b>A repeating event is a maybe, not a wall.</b> This expands the common RRULE shapes -- daily,
 * weekly with BYDAY, monthly, with INTERVAL, COUNT and UNTIL -- over a bounded window, and marks
 * every occurrence {@code firm = false}. That flag is the whole point: a standing Tuesday call is a
 * real commitment and it is also the kind of thing somebody moves for a friend's birthday, whereas
 * a flight on the 9th is not. Treating both as "busy" throws away the difference that decides
 * whether an evening is worth proposing.
 *
 * <b>What is still not here:</b> VTIMEZONE resolution, EXDATE, RDATE, BYSETPOS, VALARM, attendee
 * status. A bare local timestamp is read in the reader's zone, which is wrong when the two differ.
 * An event excluded by EXDATE is still counted -- as a maybe, which is the direction that fails
 * safely: somebody says "actually that week is fine" rather than losing the evening silently.
 *
 * The general rule this parser follows: <b>an uncertain conflict must never be reported as a
 * certain one.</b> A missed busy window makes this propose a time somebody then blocks, which is
 * the mechanism working. A wrongly-firm one silently removes a good evening nobody ever knew was
 * considered.
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

  /** how far ahead a repeating event is expanded */
  public static final int EXPAND_DAYS = 120;

  /** the most occurrences one rule may produce, so a daily forever rule cannot fill memory */
  public static final int MAX_OCCURRENCES = 200;

  /**
   * One window somebody is not free, and how certain that is.
   *
   * <b>`firm` is the field that matters.</b> A one-off dated event is firm: a flight on the 9th is
   * not moving. A recurrence is not: a standing Tuesday call is a real commitment and also exactly
   * the kind of thing somebody shifts for a friend's fortieth. Collapsing the two into "busy" is
   * what makes a scheduler propose nothing at all for a group of five, because everybody has a
   * standing something.
   */
  public record Busy(long start, long end, boolean firm) {
    public Busy(long start, long end) {
      this(start, end, true);
    }

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
    String rule = null;
    for (String line : unfold(text)) {
      String upper = line.toUpperCase(Locale.ROOT);
      if (upper.startsWith("BEGIN:VEVENT")) {
        inEvent = true;
        start = -1;
        end = -1;
        allDay = false;
        rule = null;
        continue;
      }
      if (upper.startsWith("END:VEVENT")) {
        if (inEvent && start > 0) {
          long finish = end > start ? end
              : (allDay ? start + 24L * 60 * 60 * 1000 : start + 60L * 60 * 1000);
          if (rule == null) {
            windows.add(new Busy(start, finish, true));
          } else {
            // every occurrence is a maybe: see the class note about firmness
            for (long when : occurrences(start, rule, here)) {
              windows.add(new Busy(when, when + (finish - start), false));
              if (windows.size() >= MAX_EVENTS) {
                return windows;
              }
            }
          }
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
      } else if (upper.startsWith("RRULE")) {
        rule = valueOf(line);
      }
      // SUMMARY, DESCRIPTION, LOCATION, ATTENDEE and the rest are read past on purpose: see the
      // class note. This parser knows when, never what or who.
    }
    return windows;
  }

  /**
   * When a repeating event actually falls, over a bounded window.
   *
   * <b>The common shapes and nothing clever.</b> FREQ of DAILY, WEEKLY or MONTHLY, with INTERVAL,
   * COUNT, UNTIL and -- the one that matters in practice -- BYDAY, because "every other Tuesday and
   * Thursday" is what a real calendar is full of. A FREQ this does not understand produces the
   * first occurrence alone, which is what the parser did for everything before recurrence existed.
   *
   * <b>Bounded twice, on purpose.</b> {@link #EXPAND_DAYS} ahead and {@link #MAX_OCCURRENCES} in
   * total: a DAILY rule with no COUNT and no UNTIL is legal, infinite, and would otherwise be a
   * shared calendar that fills this machine's memory.
   */
  static List<Long> occurrences(long start, String rule, ZoneId zone) {
    ArrayList<Long> out = new ArrayList<>();
    out.add(start);
    java.util.Map<String, String> parts = new java.util.HashMap<>();
    for (String piece : rule.split(";")) {
      int equals = piece.indexOf('=');
      if (equals > 0) {
        parts.put(piece.substring(0, equals).trim().toUpperCase(Locale.ROOT),
            piece.substring(equals + 1).trim().toUpperCase(Locale.ROOT));
      }
    }
    String freq = parts.getOrDefault("FREQ", "");
    int interval = Math.max(1, intOr(parts.get("INTERVAL"), 1));
    int count = intOr(parts.get("COUNT"), 0);
    long until = parts.containsKey("UNTIL") ? millisOf(parts.get("UNTIL"), zone) : -1;
    long horizon = start + EXPAND_DAYS * 24L * 60 * 60 * 1000;
    if (until > 0) {
      horizon = Math.min(horizon, until);
    }

    java.time.ZonedDateTime first = java.time.Instant.ofEpochMilli(start).atZone(zone);
    java.util.List<java.time.DayOfWeek> days = byDay(parts.get("BYDAY"));

    if (freq.equals("WEEKLY") && !days.isEmpty()) {
      // Walk weeks, and inside each take the named days. Written this way rather than "add seven
      // days" because BYDAY=TU,TH is two occurrences a week and stepping by interval alone finds
      // only one of them.
      java.time.ZonedDateTime weekStart = first.minusDays(
          first.getDayOfWeek().getValue() - 1L);
      for (int week = 0; out.size() < MAX_OCCURRENCES; week++) {
        java.time.ZonedDateTime cursor = weekStart.plusWeeks((long) week * interval);
        if (cursor.toInstant().toEpochMilli() > horizon) {
          break;
        }
        for (java.time.DayOfWeek day : days) {
          java.time.ZonedDateTime at = cursor.with(
              java.time.temporal.TemporalAdjusters.nextOrSame(day))
              .withHour(first.getHour()).withMinute(first.getMinute())
              .withSecond(first.getSecond());
          long millis = at.toInstant().toEpochMilli();
          if (millis > start && millis <= horizon && !out.contains(millis)) {
            out.add(millis);
          }
        }
        if (count > 0 && out.size() >= count) {
          break;
        }
      }
      out.sort(Long::compare);
      return trim(out, count);
    }

    java.time.ZonedDateTime cursor = first;
    while (out.size() < MAX_OCCURRENCES) {
      cursor = switch (freq) {
        case "DAILY" -> cursor.plusDays(interval);
        case "WEEKLY" -> cursor.plusWeeks(interval);
        case "MONTHLY" -> cursor.plusMonths(interval);
        case "YEARLY" -> cursor.plusYears(interval);
        // a FREQ this does not understand falls back to the first occurrence alone, which is what
        // everything did before recurrence was read at all
        default -> null;
      };
      if (cursor == null) {
        break;
      }
      long millis = cursor.toInstant().toEpochMilli();
      if (millis > horizon) {
        break;
      }
      out.add(millis);
      if (count > 0 && out.size() >= count) {
        break;
      }
    }
    return trim(out, count);
  }

  private static List<Long> trim(List<Long> out, int count) {
    return count > 0 && out.size() > count ? new ArrayList<>(out.subList(0, count)) : out;
  }

  private static List<java.time.DayOfWeek> byDay(String value) {
    ArrayList<java.time.DayOfWeek> days = new ArrayList<>();
    if (value == null || value.isBlank()) {
      return days;
    }
    for (String piece : value.split(",")) {
      // a leading ordinal like "2TU" (the second Tuesday) is read as the weekday and the ordinal
      // dropped: over-reporting a maybe is the safe direction, and BYSETPOS is out of scope
      String code = piece.replaceAll("[^A-Z]", "");
      java.time.DayOfWeek day = switch (code) {
        case "MO" -> java.time.DayOfWeek.MONDAY;
        case "TU" -> java.time.DayOfWeek.TUESDAY;
        case "WE" -> java.time.DayOfWeek.WEDNESDAY;
        case "TH" -> java.time.DayOfWeek.THURSDAY;
        case "FR" -> java.time.DayOfWeek.FRIDAY;
        case "SA" -> java.time.DayOfWeek.SATURDAY;
        case "SU" -> java.time.DayOfWeek.SUNDAY;
        default -> null;
      };
      if (day != null && !days.contains(day)) {
        days.add(day);
      }
    }
    return days;
  }

  private static int intOr(String value, int fallback) {
    try {
      return value == null ? fallback : Integer.parseInt(value.trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
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

  /** is somebody definitely busy at any point in this window? */
  public static boolean busyBetween(List<Busy> windows, long from, long to) {
    return clash(windows, from, to) == Clash.firm;
  }

  /** what stands in the way of a window, if anything */
  public enum Clash {
    /** nothing on the calendar */
    free,
    /** only repeating commitments, which somebody might move */
    maybe,
    /** a one-off dated event; this is not happening */
    firm
  }

  /**
   * What a calendar says about one window.
   *
   * A firm clash beats a maybe, because "there is a flight" is the answer even when there is also a
   * standing call. The three-way answer is the whole reason recurrence is expanded at all: with a
   * boolean, a group of five where everybody has one weekly commitment has no free evenings, and
   * the honest answer is that they have several evenings somebody would have to move something for.
   */
  public static Clash clash(List<Busy> windows, long from, long to) {
    Clash worst = Clash.free;
    for (Busy window : windows) {
      if (!window.overlaps(from, to)) {
        continue;
      }
      if (window.firm()) {
        return Clash.firm;
      }
      worst = Clash.maybe;
    }
    return worst;
  }
}
