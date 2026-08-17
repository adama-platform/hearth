package io.hearth.availability;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Reading somebody else's calendar down to "cannot come then".
 *
 * Two kinds of failure matter here and they fail in opposite directions. A reader that ignores
 * `RRULE` calls somebody free on the one evening they never are -- their standup, their choir, the
 * shift they work every Thursday -- and a reader that swallows every all-day entry blacks out the
 * whole week because somebody's calendar knows when the bins go out. Both produce a grid that is
 * confidently wrong, which is worse than no grid.
 */
public class BusyCalendarTests {
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final LocalDate FROM = LocalDate.of(2026, 5, 4);   // a Monday
  private static final LocalDate TO = FROM.plusDays(28);

  @Test
  public void oneEventIsOneBlock() {
    List<BusyCalendar.Block> busy = read(event("20260505T190000Z", "20260505T210000Z", null));
    assertEquals(1, busy.size());
    assertEquals("2026-05-05T19:00:00Z", at(busy.get(0).from()));
    assertEquals("2026-05-05T21:00:00Z", at(busy.get(0).to()));
  }

  @Test
  public void aWeeklyStandupIsBusyEveryWeek() {
    // the whole reason recurrence is expanded: this person is never free on a Tuesday at seven,
    // and a reader that took only the first instance would say they are free three weeks in four
    List<BusyCalendar.Block> busy = read(
        event("20260505T190000Z", "20260505T200000Z", "FREQ=WEEKLY"));
    assertEquals(4, busy.size());
    assertEquals("2026-05-12T19:00:00Z", at(busy.get(1).from()));
    assertEquals("2026-05-26T19:00:00Z", at(busy.get(3).from()));
  }

  @Test
  public void everyOtherWeekIsEveryOtherWeek() {
    List<BusyCalendar.Block> busy = read(
        event("20260505T190000Z", "20260505T200000Z", "FREQ=WEEKLY;INTERVAL=2"));
    assertEquals(2, busy.size());
    assertEquals("2026-05-19T19:00:00Z", at(busy.get(1).from()));
  }

  @Test
  public void aRuleThatStopsStops() {
    assertEquals(2, read(event("20260505T190000Z", "20260505T200000Z",
        "FREQ=WEEKLY;COUNT=2")).size());
    assertEquals(2, read(event("20260505T190000Z", "20260505T200000Z",
        "FREQ=WEEKLY;UNTIL=20260513T000000Z")).size());
  }

  @Test
  public void namedDaysAreEveryNamedDay() {
    List<BusyCalendar.Block> busy = read(event("20260504T090000Z", "20260504T093000Z",
        "FREQ=WEEKLY;BYDAY=MO,WE,FR"));
    // May 4 to June 1 inclusive: four full weeks plus the Monday that closes the horizon
    assertEquals("three a week, every week in the window", 13, busy.size());
  }

  @Test
  public void aDayTheyCancelledIsNotBusy() {
    String feed = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n"
        + "DTSTART:20260505T190000Z\r\nDTEND:20260505T200000Z\r\n"
        + "RRULE:FREQ=WEEKLY\r\nEXDATE:20260512T190000Z\r\n"
        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    List<BusyCalendar.Block> busy = read(feed);
    assertEquals(3, busy.size());
    for (BusyCalendar.Block block : busy) {
      assertFalse("the week they said they are not doing it", at(block.from()).startsWith("2026-05-12"));
    }
  }

  @Test
  public void monthlyContributesTheOneInstanceWeCanBeSureOf() {
    // "the third Thursday" is a fortnight of work for one hour of one week, and guessing at it
    // would be worse than admitting the limit
    assertEquals(1, read(event("20260505T190000Z", "20260505T200000Z",
        "FREQ=MONTHLY;BYDAY=1TU")).size());
  }

  @Test
  public void anAllDayEntryBlocksTheDayAndNotTheNight() {
    String feed = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n"
        + "DTSTART;VALUE=DATE:20260506\r\nDTEND;VALUE=DATE:20260507\r\n"
        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    List<BusyCalendar.Block> busy = read(feed);
    assertEquals(1, busy.size());
    assertEquals("2026-05-06T09:00:00Z", at(busy.get(0).from()));
    assertEquals("a calendar that knows when the bins go out must not black out every evening",
        "2026-05-06T22:00:00Z", at(busy.get(0).to()));
  }

  @Test
  public void aWeekAwayIsAWeekAway() {
    String feed = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n"
        + "DTSTART;VALUE=DATE:20260511\r\nDTEND;VALUE=DATE:20260518\r\n"
        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    List<BusyCalendar.Block> busy = read(feed);
    assertEquals(1, busy.size());
    assertEquals("2026-05-11T09:00:00Z", at(busy.get(0).from()));
    assertTrue("seven days of it", at(busy.get(0).to()).startsWith("2026-05-17"));
  }

  @Test
  public void somethingTheyAreFreeDuringIsNotBusy() {
    String feed = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n"
        + "DTSTART:20260505T190000Z\r\nDTEND:20260505T200000Z\r\nTRANSP:TRANSPARENT\r\n"
        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    assertTrue("a birthday reminder is not a reason to miss a supper", read(feed).isEmpty());
  }

  @Test
  public void aCancelledEventIsNotBusyEither() {
    String feed = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n"
        + "DTSTART:20260505T190000Z\r\nDTEND:20260505T200000Z\r\nSTATUS:CANCELLED\r\n"
        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    assertTrue(read(feed).isEmpty());
  }

  @Test
  public void aDurationIsAsGoodAsAnEnd() {
    List<BusyCalendar.Block> busy = read("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n"
        + "DTSTART:20260505T190000Z\r\nDURATION:PT1H30M\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n");
    assertEquals(1, busy.size());
    assertEquals("2026-05-05T20:30:00Z", at(busy.get(0).to()));
    assertEquals(90, BusyCalendar.durationMinutes("PT1H30M"));
    assertEquals(24 * 60, BusyCalendar.durationMinutes("P1D"));
  }

  @Test
  public void overlappingMeetingsAreOneBusyStretch() {
    String feed = "BEGIN:VCALENDAR\r\n"
        + "BEGIN:VEVENT\r\nDTSTART:20260505T190000Z\r\nDTEND:20260505T203000Z\r\nEND:VEVENT\r\n"
        + "BEGIN:VEVENT\r\nDTSTART:20260505T200000Z\r\nDTEND:20260505T210000Z\r\nEND:VEVENT\r\n"
        + "END:VCALENDAR\r\n";
    List<BusyCalendar.Block> busy = read(feed);
    assertEquals(1, busy.size());
    assertEquals("2026-05-05T21:00:00Z", at(busy.get(0).to()));
  }

  @Test
  public void aFoldedLineIsStillALine() {
    // every client on earth folds differently, and refusing on that would refuse real calendars
    String feed = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART:2026050"
        + "5T190000Z\r\n".replace("5T", "5T")
        + "DTEND:20260505T20\r\n 0000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
    List<BusyCalendar.Block> busy = read(feed);
    assertEquals(1, busy.size());
    assertEquals("2026-05-05T20:00:00Z", at(busy.get(0).to()));
  }

  @Test
  public void nonsenseIsSkippedRatherThanRefused() {
    String feed = "BEGIN:VCALENDAR\r\n"
        + "BEGIN:VEVENT\r\nDTSTART:not-a-date\r\nDTEND:also-not\r\nEND:VEVENT\r\n"
        + "BEGIN:VEVENT\r\nDTSTART:20260505T190000Z\r\nDTEND:20260505T200000Z\r\nEND:VEVENT\r\n"
        + "END:VCALENDAR\r\n";
    assertEquals("one bad event is one event skipped, not a feed thrown away", 1, read(feed).size());
    assertTrue(read("").isEmpty());
    assertTrue(read("not a calendar at all").isEmpty());
  }

  @Test
  public void aTimezoneOnTheEventIsHonoured() {
    String feed = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n"
        + "DTSTART;TZID=America/New_York:20260505T190000\r\n"
        + "DTEND;TZID=America/New_York:20260505T200000\r\n"
        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    List<BusyCalendar.Block> busy = read(feed);
    assertEquals(1, busy.size());
    assertEquals("seven in New York is eleven in UTC", "2026-05-05T23:00:00Z", at(busy.get(0).from()));
  }

  @Test
  public void nothingOutsideTheHorizonComesBack() {
    assertTrue("a meeting next year is not this month's problem",
        read(event("20271005T190000Z", "20271005T200000Z", null)).isEmpty());
    assertTrue(read(event("20200505T190000Z", "20200505T200000Z", null)).isEmpty());
  }

  @Test
  public void aRuleNobodyUnderstandsIsBounded() {
    // a feed with a pathological rule costs a few hundred iterations, never a thread
    List<BusyCalendar.Block> busy = read(
        event("20260504T000000Z", "20260504T000100Z", "FREQ=DAILY;INTERVAL=0"));
    assertTrue("one a day and not one more", busy.size() <= 29);
  }

  private static String event(String start, String end, String rule) {
    return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n"
        + "SUMMARY:something private\r\n"
        + "DTSTART:" + start + "\r\nDTEND:" + end + "\r\n"
        + (rule == null ? "" : "RRULE:" + rule + "\r\n")
        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
  }

  private static List<BusyCalendar.Block> read(String feed) {
    return BusyCalendar.read(feed, FROM, TO, ZONE);
  }

  private static String at(long epochSecond) {
    return java.time.Instant.ofEpochSecond(epochSecond).toString();
  }
}
