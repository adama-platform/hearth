package io.hearth.availability;

import io.hearth.calendar.Ics;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Somebody else's calendar, reduced to "cannot come then".
 *
 * <b>It reads a feed and throws almost all of it away.</b> What comes out is pairs of instants and
 * nothing else -- no titles, no locations, no guests, no organiser. Partly because that is all the
 * grid needs, and mostly because a table holding what a member is doing on Thursday evening is a
 * table somebody would eventually put on a screen. The parser never keeps it, so the question of
 * who may see it never arises.
 *
 * <b>Recurrence is expanded, within limits, because it is the whole point.</b> A member's weekly
 * standup and their every-other-Thursday choir are exactly the commitments that decide when a
 * community can meet, and a reader that ignored `RRULE` would call somebody free on the one evening
 * they never are. Daily and weekly rules are expanded properly, including `INTERVAL`, `BYDAY`,
 * `COUNT`, `UNTIL` and `EXDATE`. Monthly and yearly rules contribute their first occurrence only:
 * getting "the third Thursday" right is a fortnight of work for something that moves one hour of one
 * week, and pretending otherwise would be worse than admitting it.
 *
 * <b>An all-day event blocks the working day, not the night.</b> A calendar full of `VALUE=DATE`
 * entries -- birthdays, deliveries, "leave" -- would otherwise black out somebody's entire week.
 * Somebody on holiday is unavailable all day; somebody with "bin day" is not, and there is no way
 * to tell the two apart from a feed, so the honest reading is the one that does not swallow every
 * evening in the calendar.
 */
public final class BusyCalendar {
  /** how many instances one recurring event may contribute; a loop with a bad rule stops here */
  private static final int MAX_INSTANCES = 400;
  /** how many events one feed may contribute at all */
  private static final int MAX_EVENTS = 2000;
  /** when an all-day entry is taken to start and end, local time */
  static final int ALL_DAY_FROM = 9;
  static final int ALL_DAY_TO = 22;

  private BusyCalendar() {
  }

  /** one stretch somebody is not free, as epoch seconds */
  public record Block(long from, long to) {
    public boolean overlaps(long start, long end) {
      return from < end && start < to;
    }
  }

  /**
   * Read a feed into busy blocks between two days.
   *
   * @param zone the community's own timezone, which is what an all-day entry and a floating time
   *     are read in. A calendar that carries a timezone of its own is honoured; one that does not
   *     is being written by somebody who lives where this community does.
   */
  public static List<Block> read(String text, LocalDate from, LocalDate to, ZoneId zone) {
    ArrayList<Block> blocks = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return blocks;
    }
    Instant floor = from.atStartOfDay(zone).toInstant();
    Instant ceiling = to.plusDays(1).atStartOfDay(zone).toInstant();

    List<String> lines = Ics.unfold(text);
    boolean inEvent = false;
    boolean transparent = false;
    boolean cancelled = false;
    LinkedHashMap<String, String> startParams = null;
    String start = null;
    String end = null;
    String duration = null;
    String rule = null;
    LinkedHashSet<String> excluded = new LinkedHashSet<>();
    int events = 0;

    for (String line : lines) {
      String upper = line.toUpperCase(java.util.Locale.ROOT);
      if (upper.startsWith("BEGIN:VEVENT")) {
        inEvent = true;
        transparent = false;
        cancelled = false;
        startParams = null;
        start = null;
        end = null;
        duration = null;
        rule = null;
        excluded = new LinkedHashSet<>();
        continue;
      }
      if (upper.startsWith("END:VEVENT")) {
        inEvent = false;
        if (start != null && !transparent && !cancelled && events++ < MAX_EVENTS) {
          expand(blocks, startParams, start, end, duration, rule, excluded, floor, ceiling, zone);
        }
        continue;
      }
      if (!inEvent) {
        continue;
      }
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String left = line.substring(0, colon);
      String value = line.substring(colon + 1).trim();
      String name = (left.contains(";") ? left.substring(0, left.indexOf(';')) : left)
          .trim().toUpperCase(java.util.Locale.ROOT);
      switch (name) {
        case "DTSTART" -> {
          start = value;
          startParams = Ics.parameters(left);
        }
        case "DTEND" -> end = value;
        case "DURATION" -> duration = value;
        case "RRULE" -> rule = value;
        // "I am free while this happens" -- a birthday, a reminder, an out-of-office marker
        case "TRANSP" -> transparent = value.equalsIgnoreCase("TRANSPARENT");
        case "STATUS" -> cancelled = value.equalsIgnoreCase("CANCELLED");
        case "EXDATE" -> {
          for (String one : value.split(",")) {
            excluded.add(one.trim());
          }
        }
        default -> {
        }
      }
    }
    blocks.sort((left, right) -> Long.compare(left.from(), right.from()));
    return merge(blocks);
  }

  private static void expand(List<Block> into, Map<String, String> params, String start, String end,
                             String duration, String rule, Set<String> excluded, Instant floor,
                             Instant ceiling, ZoneId zone) {
    boolean allDay = params != null && "DATE".equalsIgnoreCase(params.get("VALUE"));
    ZoneId eventZone = zoneOf(params, zone);
    LocalDateTime first = when(start, allDay, eventZone);
    if (first == null) {
      return;
    }
    long minutes = lengthMinutes(first, end, duration, allDay, eventZone);
    if (minutes <= 0) {
      return;
    }
    LinkedHashSet<LocalDate> skip = new LinkedHashSet<>();
    for (String one : excluded) {
      LocalDateTime dropped = when(one, allDay, eventZone);
      if (dropped != null) {
        skip.add(dropped.toLocalDate());
      }
    }

    for (LocalDateTime at : occurrences(first, rule, floor, ceiling, eventZone)) {
      if (skip.contains(at.toLocalDate())) {
        continue;
      }
      Instant from = at.atZone(eventZone).toInstant();
      Instant to = at.plusMinutes(minutes).atZone(eventZone).toInstant();
      // exclusive at both ends: a block that starts exactly at the ceiling is the day after
      if (!to.isAfter(floor) || !from.isBefore(ceiling)) {
        continue;
      }
      into.add(new Block(Math.max(from.getEpochSecond(), floor.getEpochSecond()),
          Math.min(to.getEpochSecond(), ceiling.getEpochSecond())));
    }
  }

  /**
   * Every time this event happens inside the window.
   *
   * The bound is the point: `MAX_INSTANCES` and the ceiling mean a rule nobody understands costs a
   * few hundred iterations rather than a thread.
   */
  static List<LocalDateTime> occurrences(LocalDateTime first, String rule, Instant floor,
                                         Instant ceiling, ZoneId zone) {
    ArrayList<LocalDateTime> out = new ArrayList<>();
    if (rule == null || rule.isBlank()) {
      out.add(first);
      return out;
    }
    Map<String, String> parts = ruleOf(rule);
    String frequency = parts.getOrDefault("FREQ", "").toUpperCase(java.util.Locale.ROOT);
    int interval = Math.max(1, number(parts.get("INTERVAL"), 1));
    int count = number(parts.get("COUNT"), 0);
    LocalDateTime until = untilOf(parts.get("UNTIL"), zone);
    Set<DayOfWeek> days = daysOf(parts.get("BYDAY"));

    if (!frequency.equals("DAILY") && !frequency.equals("WEEKLY")) {
      // monthly and yearly contribute the one instance we can be sure of
      out.add(first);
      return out;
    }

    LocalDateTime at = first;
    int made = 0;
    int steps = 0;
    while (steps++ < MAX_INSTANCES) {
      Instant instant = at.atZone(zone).toInstant();
      if (instant.isAfter(ceiling)) {
        break;
      }
      if (until != null && at.isAfter(until)) {
        break;
      }
      if (frequency.equals("WEEKLY") && !days.isEmpty()) {
        // one step is a week; every named day inside it is an occurrence
        for (DayOfWeek day : days) {
          LocalDateTime one = at.with(java.time.temporal.TemporalAdjusters.previousOrSame(
              at.getDayOfWeek())).plusDays(day.getValue() - at.getDayOfWeek().getValue());
          if (one.isBefore(first)) {
            continue;
          }
          if (until != null && one.isAfter(until)) {
            continue;
          }
          if (!one.atZone(zone).toInstant().isAfter(ceiling)) {
            out.add(one);
            made++;
          }
        }
      } else {
        out.add(at);
        made++;
      }
      if (count > 0 && made >= count) {
        break;
      }
      at = frequency.equals("DAILY") ? at.plusDays(interval) : at.plusWeeks(interval);
    }
    return out;
  }

  private static Map<String, String> ruleOf(String rule) {
    LinkedHashMap<String, String> parts = new LinkedHashMap<>();
    for (String piece : rule.split(";")) {
      int equals = piece.indexOf('=');
      if (equals > 0) {
        parts.put(piece.substring(0, equals).trim().toUpperCase(java.util.Locale.ROOT),
            piece.substring(equals + 1).trim());
      }
    }
    return parts;
  }

  private static Set<DayOfWeek> daysOf(String byDay) {
    LinkedHashSet<DayOfWeek> days = new LinkedHashSet<>();
    if (byDay == null) {
      return days;
    }
    for (String one : byDay.split(",")) {
      String clean = one.trim().toUpperCase(java.util.Locale.ROOT);
      // a positional prefix (2TU, -1FR) is a monthly idea; the day part is still the useful half
      clean = clean.replaceAll("^[+-]?[0-9]+", "");
      switch (clean) {
        case "MO" -> days.add(DayOfWeek.MONDAY);
        case "TU" -> days.add(DayOfWeek.TUESDAY);
        case "WE" -> days.add(DayOfWeek.WEDNESDAY);
        case "TH" -> days.add(DayOfWeek.THURSDAY);
        case "FR" -> days.add(DayOfWeek.FRIDAY);
        case "SA" -> days.add(DayOfWeek.SATURDAY);
        case "SU" -> days.add(DayOfWeek.SUNDAY);
        default -> {
        }
      }
    }
    return days;
  }

  private static LocalDateTime untilOf(String until, ZoneId zone) {
    return until == null ? null : when(until, until.length() == 8, zone);
  }

  private static int number(String raw, int fallback) {
    try {
      return raw == null ? fallback : Integer.parseInt(raw.trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  /** the zone a property names, or the community's own */
  private static ZoneId zoneOf(Map<String, String> params, ZoneId fallback) {
    String named = params == null ? null : params.get("TZID");
    if (named == null || named.isBlank()) {
      return fallback;
    }
    try {
      return ZoneId.of(named.trim());
    } catch (RuntimeException ex) {
      // a zone this JVM has never heard of is a calendar written by something unusual; the
      // community's own zone is a better guess than refusing the whole feed
      return fallback;
    }
  }

  /**
   * One iCalendar timestamp, in local terms.
   *
   * Three shapes in the wild: `20260814` (a date), `20260814T190000` (floating local time) and
   * `20260814T180000Z` (UTC). All three appear in real feeds from real clients.
   */
  static LocalDateTime when(String value, boolean allDay, ZoneId zone) {
    if (value == null) {
      return null;
    }
    String raw = value.trim();
    try {
      if (raw.length() == 8) {
        LocalDate day = LocalDate.of(Integer.parseInt(raw.substring(0, 4)),
            Integer.parseInt(raw.substring(4, 6)), Integer.parseInt(raw.substring(6, 8)));
        return allDay ? day.atTime(ALL_DAY_FROM, 0) : day.atStartOfDay();
      }
      if (raw.length() >= 15) {
        LocalDateTime local = LocalDateTime.of(
            Integer.parseInt(raw.substring(0, 4)), Integer.parseInt(raw.substring(4, 6)),
            Integer.parseInt(raw.substring(6, 8)), Integer.parseInt(raw.substring(9, 11)),
            Integer.parseInt(raw.substring(11, 13)), Integer.parseInt(raw.substring(13, 15)));
        if (raw.endsWith("Z")) {
          return local.atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime();
        }
        return local;
      }
    } catch (NumberFormatException | java.time.DateTimeException ex) {
      // a timestamp nobody can read is one event skipped, never a feed refused
    }
    return null;
  }

  private static long lengthMinutes(LocalDateTime start, String end, String duration,
                                    boolean allDay, ZoneId zone) {
    if (end != null) {
      LocalDateTime finish = when(end, allDay, zone);
      if (finish != null) {
        if (allDay) {
          // DTEND is exclusive for a date: a one-day entry ends the following morning, and the
          // useful reading is "that day, during the day"
          long days = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(
              start.toLocalDate(), finish.toLocalDate()));
          return (days - 1) * 24 * 60 + (ALL_DAY_TO - ALL_DAY_FROM) * 60L;
        }
        long minutes = java.time.temporal.ChronoUnit.MINUTES.between(start, finish);
        return minutes > 0 ? minutes : 0;
      }
    }
    if (duration != null) {
      long minutes = durationMinutes(duration);
      if (minutes > 0) {
        return minutes;
      }
    }
    // no end and no duration: an hour, which is what most clients mean and what none of them say
    return allDay ? (ALL_DAY_TO - ALL_DAY_FROM) * 60L : 60;
  }

  /** an ISO-8601 duration, in the shapes calendars actually emit */
  static long durationMinutes(String value) {
    String raw = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    if (!raw.startsWith("P")) {
      return 0;
    }
    long minutes = 0;
    long number = 0;
    boolean time = false;
    for (int k = 1; k < raw.length(); k++) {
      char ch = raw.charAt(k);
      if (Character.isDigit(ch)) {
        number = number * 10 + (ch - '0');
        continue;
      }
      switch (ch) {
        case 'T' -> time = true;
        case 'W' -> minutes += number * 7 * 24 * 60;
        case 'D' -> minutes += number * 24 * 60;
        case 'H' -> minutes += number * 60;
        case 'M' -> minutes += time ? number : 0;
        default -> {
        }
      }
      number = 0;
    }
    return minutes;
  }

  /** overlapping blocks are one block; a calendar with three meetings at once is one busy hour */
  static List<Block> merge(List<Block> blocks) {
    ArrayList<Block> out = new ArrayList<>();
    for (Block block : blocks) {
      if (!out.isEmpty() && block.from() <= out.get(out.size() - 1).to()) {
        Block last = out.remove(out.size() - 1);
        out.add(new Block(last.from(), Math.max(last.to(), block.to())));
      } else {
        out.add(block);
      }
    }
    return out;
  }
}
