package io.hearth.availability;

import io.hearth.auth.UserRecord;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * When this community could actually meet, as a week-shaped grid.
 *
 * <b>Two numbers per hour, and the gap between them is the useful part.</b> `ideal` is how many
 * people would like to be free then; `clear` is how many of those are also free on every occurrence
 * of that hour between now and the horizon. Sixteen people who love Tuesday evenings and four of
 * them clear for the next month is a different fact from four people who like Tuesdays, and an
 * average would hide it.
 *
 * <b>Somebody who has said nothing is still counted, from an assumption.</b> Weekday evenings and
 * most of a weekend day. That is wrong about individuals and roughly right about groups, and it is
 * the whole reason the screen is worth opening before anybody has filled anything in -- a tool that
 * only worked once everybody had used it would never be used by anybody. Anyone who says otherwise
 * overrides it completely, which is the point: the people who care most are the ones who move the
 * picture.
 *
 * <b>It is a fold, not a forecast.</b> Every occurrence of Tuesday 7pm between today and the
 * horizon is collapsed into one cell, so a fortnight away shows up as an hour that is nearly clear
 * rather than as a hole in one particular week. What it is for is choosing an evening, not
 * scheduling a specific date -- for that, put the event up and watch the answers come in.
 */
public final class Heatmap {
  /** the whole week, one cell per hour */
  public static final int HOURS = 24;

  private Heatmap() {
  }

  /** one hour of one weekday */
  public record Cell(DayOfWeek day, int hour, int ideal, int clear) {
    /** how much of the interest survived contact with everybody's calendar, 0 to 1 */
    public double survival() {
      return ideal == 0 ? 0 : (double) clear / ideal;
    }
  }

  /** the whole grid, and what it was built from */
  public record Grid(List<Cell> cells, int people, int said, int linked, int assumed,
                     LocalDate from, LocalDate to, int best) {
    public Cell at(DayOfWeek day, int hour) {
      for (Cell cell : cells) {
        if (cell.day() == day && cell.hour() == hour) {
          return cell;
        }
      }
      return new Cell(day, hour, 0, 0);
    }
  }

  /** what one person contributes: their windows, and the times they are already spoken for */
  public record Person(long id, List<Availability.Window> windows, List<BusyCalendar.Block> busy,
                       boolean assumed) {
  }

  /**
   * The windows somebody has, or the ones this server assumes for them.
   *
   * <b>Weekday evenings from four, and a weekend day from nine.</b> Not because anybody's life
   * looks like that, but because a community planning around "the evenings and the weekend" is
   * doing what it would do anyway, and the grid then says something on the first day rather than
   * after a campaign to get everybody to fill a form in.
   */
  public static List<Availability.Window> windowsOrAssumed(long userId,
                                                           List<Availability.Window> theirs) {
    if (theirs != null && !theirs.isEmpty()) {
      return theirs;
    }
    ArrayList<Availability.Window> assumed = new ArrayList<>();
    for (DayOfWeek day : DayOfWeek.values()) {
      boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
      int from = (weekend ? AvailabilityConfig.ASSUMED_WEEKEND_HOUR
          : AvailabilityConfig.ASSUMED_EVENING_HOUR) * 60;
      assumed.add(new Availability.Window(0, userId, day, from,
          AvailabilityConfig.ASSUMED_NIGHT_HOUR * 60, "", null, null));
    }
    return assumed;
  }

  /**
   * Fold everybody onto one week.
   *
   * @param horizonDays how far ahead the busy blocks are believed. Every occurrence of an hour
   *     inside it has to be free for that hour to count as clear for that person, because the
   *     question being asked is "could we make this our Tuesday" rather than "is next Tuesday
   *     free".
   */
  public static Grid of(List<Person> people, LocalDate today, int horizonDays, ZoneId zone) {
    LinkedHashMap<String, int[]> tally = new LinkedHashMap<>();
    for (DayOfWeek day : DayOfWeek.values()) {
      for (int hour = 0; hour < HOURS; hour++) {
        tally.put(key(day, hour), new int[]{0, 0});
      }
    }
    int said = 0;
    int linked = 0;
    int assumed = 0;

    // when each weekday-hour happens between today and the horizon, as epoch seconds
    Map<String, List<long[]>> occurrences = occurrencesIn(today, horizonDays, zone);

    for (Person person : people) {
      if (person.assumed()) {
        assumed++;
      } else {
        said++;
      }
      if (!person.busy().isEmpty()) {
        linked++;
      }
      for (Availability.Window window : person.windows()) {
        for (int hour = 0; hour < HOURS; hour++) {
          int minute = hour * 60;
          // an hour counts when the window covers any of it, so 16:30 to 22:00 includes four
          if (window.endsAt() <= minute || window.startsAt() >= minute + 60) {
            continue;
          }
          int[] cell = tally.get(key(window.day(), hour));
          if (cell == null) {
            continue;
          }
          cell[0]++;
          if (isClear(person.busy(), occurrences.get(key(window.day(), hour)))) {
            cell[1]++;
          }
        }
      }
    }

    ArrayList<Cell> cells = new ArrayList<>();
    int best = 0;
    for (DayOfWeek day : DayOfWeek.values()) {
      for (int hour = 0; hour < HOURS; hour++) {
        int[] counts = tally.get(key(day, hour));
        cells.add(new Cell(day, hour, counts[0], counts[1]));
        best = Math.max(best, counts[1]);
      }
    }
    return new Grid(cells, people.size(), said, linked, assumed, today,
        today.plusDays(horizonDays), best);
  }

  /**
   * Is this person free at every occurrence of this hour between now and the horizon?
   *
   * <b>Every one, not most.</b> An hour somebody is busy for two of the next four weeks is not an
   * hour a community can make its own, and softening this into an average is how a screen ends up
   * confidently recommending the one evening half the group cannot do.
   */
  static boolean isClear(List<BusyCalendar.Block> busy, List<long[]> when) {
    if (when == null || busy.isEmpty()) {
      return true;
    }
    for (long[] slot : when) {
      for (BusyCalendar.Block block : busy) {
        if (block.overlaps(slot[0], slot[1])) {
          return false;
        }
      }
    }
    return true;
  }

  /** every real hour inside the horizon, grouped by which cell of the week it lands in */
  static Map<String, List<long[]>> occurrencesIn(LocalDate today, int horizonDays, ZoneId zone) {
    HashMap<String, List<long[]>> out = new HashMap<>();
    for (int offset = 0; offset < horizonDays; offset++) {
      LocalDate day = today.plusDays(offset);
      for (int hour = 0; hour < HOURS; hour++) {
        long from = day.atTime(hour, 0).atZone(zone).toEpochSecond();
        out.computeIfAbsent(key(day.getDayOfWeek(), hour), unused -> new ArrayList<>())
            .add(new long[]{from, from + 3600});
      }
    }
    return out;
  }

  private static String key(DayOfWeek day, int hour) {
    return day.getValue() + ":" + hour;
  }

  /**
   * The handful of hours worth telling somebody about.
   *
   * A grid is for looking at; a sentence is for acting on. This is what the admin overview and the
   * event form say out loud -- "Tuesday 19:00 works for 14 of 16" -- because the whole complaint
   * this feature answers is that somebody picking a night is guessing.
   */
  public static List<Cell> bestHours(Grid grid, int howMany) {
    ArrayList<Cell> sorted = new ArrayList<>(grid.cells());
    sorted.removeIf(cell -> cell.clear() == 0);
    sorted.sort((left, right) -> {
      int byClear = Integer.compare(right.clear(), left.clear());
      if (byClear != 0) {
        return byClear;
      }
      int byDay = Integer.compare(left.day().getValue(), right.day().getValue());
      return byDay != 0 ? byDay : Integer.compare(left.hour(), right.hour());
    });
    // one entry per day: five consecutive hours of the same Tuesday is one suggestion, not five
    ArrayList<Cell> best = new ArrayList<>();
    java.util.HashSet<DayOfWeek> seen = new java.util.HashSet<>();
    for (Cell cell : sorted) {
      if (seen.add(cell.day())) {
        best.add(cell);
      }
      if (best.size() >= howMany) {
        break;
      }
    }
    return best;
  }

  /** "Tuesday 19:00" */
  public static String describe(Cell cell) {
    String day = cell.day().getDisplayName(java.time.format.TextStyle.FULL,
        java.util.Locale.getDefault());
    return day + " " + String.format("%02d:00", cell.hour());
  }

  /** somebody to fold in, whether or not they have said anything */
  public static Person personOf(UserRecord user, List<Availability.Window> windows,
                                List<BusyCalendar.Block> busy) {
    boolean assumed = windows == null || windows.isEmpty();
    return new Person(user.id(), windowsOrAssumed(user.id(), windows),
        busy == null ? List.of() : busy, assumed);
  }
}
