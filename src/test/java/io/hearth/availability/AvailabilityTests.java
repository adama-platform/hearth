package io.hearth.availability;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * When people can come, and the grid that adds it up.
 *
 * The thing being protected here is trust in a number. A grid that counts somebody as free on the
 * evening they are never free, or that quietly stops counting a member whose calendar link broke in
 * March, is worse than no grid -- because somebody will pick a Tuesday from it and half the
 * community will not turn up.
 */
public class AvailabilityTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    TestServer.fetching.set(CalendarFetch.NONE);
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com", "The Boss");
  }

  @After
  public void tearDown() {
    TestServer.fetching.set(CalendarFetch.NONE);
    if (server != null) {
      server.close();
    }
    if (configs != null) {
      configs.delete();
    }
  }

  // ---- what one person says ----------------------------------------------------------------------

  @Test
  public void somebodyDrawsTheirWeekAndItStays() throws Exception {
    admin.get("/when");
    admin.submitToAndFollow("/when", Map.of("action", "add_window", "day", "TUESDAY",
        "from", "19:00", "to", "22:00", "note", "not on match nights"));

    List<Availability.Window> mine = availability().windowsFor(me());
    assertEquals(1, mine.size());
    assertEquals(DayOfWeek.TUESDAY, mine.get(0).day());
    assertEquals(19 * 60, mine.get(0).startsAt());
    assertEquals("not on match nights", mine.get(0).note());
    Browser.Page page = admin.get("/when");
    assertTrue(page.contains("19:00"));
    assertTrue(page.contains("not on match nights"));
  }

  @Test
  public void aWindowThatEndsBeforeItStartsIsATypo() throws Exception {
    admin.get("/when");
    Browser.Page done = admin.submitTo("/when", Map.of("action", "add_window", "day", "TUESDAY",
        "from", "22:00", "to", "19:00", "note", ""));
    assertTrue(done.body(), done.contains("has to come after"));
    assertEquals(0, availability().windowsFor(me()).size());
  }

  @Test
  public void nobodyRemovesSomebodyElsesWindow() throws Exception {
    admin.get("/when");
    admin.submitToAndFollow("/when", Map.of("action", "add_window", "day", "TUESDAY",
        "from", "19:00", "to", "22:00", "note", ""));
    long id = availability().windowsFor(me()).get(0).id();

    Browser ana = signIn("ana@example.com", "Ana Rivera");
    ana.get("/when");
    ana.submitTo("/when", Map.of("action", "remove_window", "window", Long.toString(id)));
    assertEquals("an id from somewhere else removes nothing", 1,
        availability().windowsFor(me()).size());
  }

  // ---- the calendars ------------------------------------------------------------------------------

  @Test
  public void aCalendarAddressHasToBeOneThisServerWillFetch() throws Exception {
    admin.get("/when");
    assertTrue(refused("http://example.com/cal.ics").contains("https://"));
    assertTrue("the classic: a member pastes the cloud metadata service",
        refused("https://169.254.169.254/latest/meta-data/").contains("private network"));
    assertTrue(refused("https://localhost/cal.ics").contains("private network"));
    assertTrue(refused("https://127.0.0.1/cal.ics").contains("private network"));
    assertTrue(refused("https://192.168.1.1/cal.ics").contains("private network"));
    assertTrue(refused("https://[::1]/cal.ics").contains("private network"));
    assertEquals("and nothing was written down", 0, availability().linksFor(me()).size());
  }

  @Test
  public void aWebcalAddressIsTheOnePeopleActuallyHave() throws Exception {
    assertEquals("https://example.com/x.ics", CalendarFetch.clean("webcal://example.com/x.ics"));
    assertEquals("https://example.com/x.ics", CalendarFetch.clean("WEBCAL://example.com/x.ics"));
  }

  @Test
  public void addingOneAndTakingItAwayTakesWhatItSaidWithIt() throws Exception {
    link("https://calendar.example.com/mine.ics", "work");
    assertEquals(1, availability().linksFor(me()).size());

    TestServer.fetching.set((url, timeout) -> CalendarFetch.Fetched.of(feed(
        LocalDate.now().plusDays(2), 19, 21, "FREQ=WEEKLY")));
    indexer().pullEverything();
    assertEquals(1, availability().cachedFor(me()).size());
    assertTrue(availability().cachedFor(me()).get(0).blocks() > 0);

    long id = availability().linksFor(me()).get(0).id();
    admin.submitToAndFollow("/when", Map.of("action", "remove_link", "link", Long.toString(id)));
    assertEquals("a calendar somebody unlinked stops counting immediately, not at the next pass",
        0, availability().cachedFor(me()).size());
  }

  @Test
  public void aCalendarThatStoppedWorkingIsSaidOutLoud() throws Exception {
    link("https://calendar.example.com/mine.ics", "work");
    TestServer.fetching.set((url, timeout) -> CalendarFetch.Fetched.of(
        feed(LocalDate.now().plusDays(1), 19, 21, null)));
    indexer().pullEverything();

    TestServer.fetching.set((url, timeout) -> CalendarFetch.Fetched.no("that calendar answered 404"));
    indexer().pullEverything();

    Availability.Cached cached = availability().cachedFor(me()).get(0);
    assertFalse(cached.ok());
    assertTrue("kept, because one bad night must not make somebody look free for a fortnight",
        cached.blocks() > 0);
    assertTrue("and shown to the person whose calendar it is",
        admin.get("/when").contains("that calendar answered 404"));
  }

  @Test
  public void nothingIsKeptButTheTimes() throws Exception {
    link("https://calendar.example.com/mine.ics", "work");
    TestServer.fetching.set((url, timeout) -> CalendarFetch.Fetched.of(
        feed(LocalDate.now().plusDays(1), 19, 21, null)));
    indexer().pullEverything();
    String stored = availability().cachedFor(me()).get(0).busy();
    assertFalse("what somebody is doing on Thursday is not this community's business",
        stored.contains("something private"));
    assertTrue(stored.startsWith("[["));
  }

  // ---- the grid -----------------------------------------------------------------------------------

  @Test
  public void somebodyWhoSaidNothingIsStillCounted() throws Exception {
    // the whole reason the screen is worth opening on the first day
    indexer().rebuild();
    Heatmap.Grid grid = indexer().grid();
    assertEquals(1, grid.people());
    assertEquals(0, grid.said());
    assertEquals(1, grid.assumed());
    assertEquals("weekday evenings from four", 1, grid.at(DayOfWeek.TUESDAY, 19).ideal());
    assertEquals(0, grid.at(DayOfWeek.TUESDAY, 11).ideal());
    assertEquals("and most of a weekend day", 1, grid.at(DayOfWeek.SATURDAY, 10).ideal());
  }

  @Test
  public void whatSomebodySaysReplacesWhatWasAssumedAboutThem() throws Exception {
    admin.get("/when");
    admin.submitToAndFollow("/when", Map.of("action", "add_window", "day", "WEDNESDAY",
        "from", "10:00", "to", "12:00", "note", ""));
    indexer().settle();
    Heatmap.Grid grid = indexer().grid();
    assertEquals(1, grid.said());
    assertEquals(0, grid.assumed());
    assertEquals(1, grid.at(DayOfWeek.WEDNESDAY, 10).ideal());
    assertEquals("the assumption is gone entirely, not merged with it",
        0, grid.at(DayOfWeek.TUESDAY, 19).ideal());
  }

  @Test
  public void aWeeklyCommitmentCutsTheHourOutOfTheirWeek() throws Exception {
    admin.get("/when");
    admin.submitToAndFollow("/when", Map.of("action", "add_window", "day", "TUESDAY",
        "from", "18:00", "to", "22:00", "note", ""));
    link("https://calendar.example.com/mine.ics", "choir");

    LocalDate tuesday = next(DayOfWeek.TUESDAY);
    TestServer.fetching.set((url, timeout) ->
        CalendarFetch.Fetched.of(feed(tuesday, 19, 20, "FREQ=WEEKLY")));
    indexer().pullEverything();
    indexer().settle();

    Heatmap.Grid grid = indexer().grid();
    assertEquals("they would like it", 1, grid.at(DayOfWeek.TUESDAY, 19).ideal());
    assertEquals("and they are never free for it", 0, grid.at(DayOfWeek.TUESDAY, 19).clear());
    assertEquals("the hour beside it is untouched", 1, grid.at(DayOfWeek.TUESDAY, 21).clear());
  }

  @Test
  public void oneWeekAwayIsEnoughToCostTheHour() throws Exception {
    // "could this be our Tuesday" is a different question from "is next Tuesday free", and
    // softening it into an average is how a screen recommends the evening half the group cannot do
    admin.get("/when");
    admin.submitToAndFollow("/when", Map.of("action", "add_window", "day", "THURSDAY",
        "from", "18:00", "to", "22:00", "note", ""));
    link("https://calendar.example.com/mine.ics", "away");
    LocalDate thursday = next(DayOfWeek.THURSDAY).plusDays(7);
    TestServer.fetching.set((url, timeout) ->
        CalendarFetch.Fetched.of(feed(thursday, 18, 23, null)));
    indexer().pullEverything();
    indexer().settle();

    assertEquals(1, indexer().grid().at(DayOfWeek.THURSDAY, 19).ideal());
    assertEquals(0, indexer().grid().at(DayOfWeek.THURSDAY, 19).clear());
  }

  @Test
  public void theGridCountsAndNeverNames() throws Exception {
    signIn("ana@example.com", "Ana Rivera");
    indexer().rebuild();
    Browser.Page page = admin.get("/when");
    assertTrue("whoever plans things sees it", page.contains("When everybody can come"));
    assertFalse("who is free on Thursday is a question nobody agreed to answer",
        page.contains("Ana Rivera"));
    assertFalse(page.contains("ana@example.com"));
  }

  @Test
  public void anOrdinaryMemberSeesTheirOwnWeekAndNotTheGrid() throws Exception {
    Browser ana = signIn("ana@example.com", "Ana Rivera");
    Browser.Page page = ana.get("/when");
    assertEquals(200, page.status());
    assertTrue(page.contains("A normal week"));
    assertFalse("the aggregate is for whoever keeps the calendar",
        page.contains("When everybody can come"));
  }

  @Test
  public void theEventFormSaysWhenPeopleCanCome() throws Exception {
    admin.get("/when");
    admin.submitToAndFollow("/when", Map.of("action", "add_window", "day", "TUESDAY",
        "from", "19:00", "to", "21:00", "note", ""));
    indexer().settle();
    Browser.Page form = admin.get("/admin/calendar/new");
    assertTrue(form.body(), form.contains("When people can come"));
    assertTrue(form.contains("Tuesday 19:00"));
  }

  @Test
  public void theBestHoursAreOnePerDayAndOrderedByHowManyCanCome() {
    ArrayList<Heatmap.Person> people = new ArrayList<>();
    people.add(person(1, window(DayOfWeek.TUESDAY, 18, 22)));
    people.add(person(2, window(DayOfWeek.TUESDAY, 19, 22)));
    people.add(person(3, window(DayOfWeek.THURSDAY, 19, 21)));
    Heatmap.Grid grid = Heatmap.of(people, LocalDate.now(), 28, ZoneId.systemDefault());
    List<Heatmap.Cell> best = Heatmap.bestHours(grid, 3);
    assertEquals("five hours of the same Tuesday is one suggestion, not five",
        2, best.size());
    assertEquals(DayOfWeek.TUESDAY, best.get(0).day());
    assertEquals(2, best.get(0).clear());
    assertEquals(DayOfWeek.THURSDAY, best.get(1).day());
  }

  @Test
  public void erasingSomebodyTakesTheirWeekAndTheirCalendarsWithIt() throws Exception {
    Browser ana = signIn("ana@example.com", "Ana Rivera");
    long id = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    ana.get("/when");
    ana.submitToAndFollow("/when", Map.of("action", "add_window", "day", "FRIDAY",
        "from", "19:00", "to", "22:00", "note", ""));
    ana.submitToAndFollow("/when", Map.of("action", "add_link",
        "url", "https://calendar.example.com/ana.ics", "label", "hers"));

    io.hearth.people.Erasure.erase(server.auth.forDomain("example.org"), null,
        server.auth.forDomain("example.org").users.byId(id), null, false);
    assertEquals(0, availability().windowsFor(id).size());
    assertEquals(0, availability().linksFor(id).size());
  }

  @Test
  public void aCommunityCanSwitchTheWholeThingOff() throws Exception {
    Configs quiet = Configs.dir().domain("quiet.example.org",
        "{\"name\":\"Quiet\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"disabled\":[\"availability\"]}");
    try (TestServer other = TestServer.ofConfigs(quiet.file())) {
      Browser boss = new Browser(other.port, "quiet.example.org");
      boss.get("/register");
      boss.submit(Map.of("email", "boss@example.com"));
      boss.submit(Map.of("code", other.mail().lastCodeFor("boss@example.com")));
      assertEquals(404, boss.get("/when").status());
      assertFalse("nor is it in the bar", boss.get("/home").contains("\"/when\""));
    } finally {
      quiet.delete();
    }
  }

  @Test
  public void theNightlyPullIsOwedOncePerDayAndNotPerRestart() {
    AvailabilityIndexer when = indexer();
    java.time.ZonedDateTime midnight = java.time.ZonedDateTime.now()
        .withHour(0).withMinute(5);
    assertTrue("a server that was off overnight does the work when it comes back",
        when.isPullDue(midnight));
    when.pullEverything();
    assertFalse("and four restarts in an evening are still one pass",
        when.isPullDue(midnight));
    assertEquals(LocalDate.now(), when.lastPull());
  }

  // ---- plumbing ------------------------------------------------------------------------------------

  private Availability availability() {
    return server.auth.forDomain("example.org").availability;
  }

  private AvailabilityIndexer indexer() {
    return server.availabilities.forDomain("example.org");
  }

  private long me() throws Exception {
    return server.auth.forDomain("example.org").users.byEmail("boss@example.com").id();
  }

  private static Availability.Window window(DayOfWeek day, int from, int to) {
    return new Availability.Window(0, 0, day, from * 60, to * 60, "", null, null);
  }

  private static Heatmap.Person person(long id, Availability.Window... windows) {
    return new Heatmap.Person(id, List.of(windows), List.of(), false);
  }

  private static LocalDate next(DayOfWeek day) {
    LocalDate at = LocalDate.now().plusDays(1);
    while (at.getDayOfWeek() != day) {
      at = at.plusDays(1);
    }
    return at;
  }

  /** a feed with one event on a day, which the parser reduces to two numbers */
  private static String feed(LocalDate day, int fromHour, int toHour, String rule) {
    java.time.format.DateTimeFormatter stamp =
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
    String date = stamp.format(day);
    return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n"
        + "SUMMARY:something private\r\n"
        + "DTSTART:" + date + "T" + String.format("%02d", fromHour) + "0000\r\n"
        + "DTEND:" + date + "T" + String.format("%02d", toHour) + "0000\r\n"
        + (rule == null ? "" : "RRULE:" + rule + "\r\n")
        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
  }

  private String refused(String url) throws Exception {
    return admin.submitTo("/when", Map.of("action", "add_link", "url", url, "label", "x")).body();
  }

  private void link(String url, String label) throws Exception {
    admin.get("/when");
    // straight to the store: the page refuses an address that does not resolve, and a test must
    // never depend on a name in DNS
    Availability.Link made = availability().addLink(me(), url, label);
    assertNotNull(made);
  }

  private Browser signIn(String email, String name) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    browser.get("/welcome");
    browser.submitTo("/welcome",
        Map.of("action", "name", "display_name", name, "location", "", "about", ""));
    if (!email.startsWith("boss")) {
      long id = server.auth.forDomain("example.org").users.byEmail(email).id();
      admin.get("/admin/people");
      admin.submitTo("/admin/people", Map.of("action", "approve", "user", Long.toString(id)));
    }
    return browser;
  }
}
