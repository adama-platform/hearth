package io.hearth.vote;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Recurrence as a maybe, and evenings weighed rather than filtered.
 *
 * The thing being proved is the one that makes this usable: for five adults there is no evening
 * nobody objects to, so a scheduler that answers "none work" is right and worthless. What has to
 * come out is the evening that costs least, what it costs, and who it costs it to.
 *
 * The sharpest assertion here is that a standing Tuesday call does not remove Tuesday.
 */
public class WeighingTests {
  private Configs configs;
  private TestServer server;
  private Browser me;

  /** a Thursday evening, far enough out that nothing else in these tests lands on it */
  private static final String THURSDAY = "2026-10-08T19:00:00Z";
  private static final String FRIDAY = "2026-10-09T19:00:00Z";

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Ranch\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    me = signIn("boss@example.com");
    server.auth.forDomain("example.org").users.approve(idOf("boss@example.com"), null);
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.close();
    }
    if (configs != null) {
      configs.delete();
    }
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }

  private long member(String name) throws Exception {
    signIn(name + "@example.com");
    long id = server.auth.forDomain("example.org").users.byEmail(name + "@example.com").id();
    server.auth.forDomain("example.org").users.approve(id, null);
    server.auth.forDomain("example.org").people.saveProfile(id,
        name.substring(0, 1).toUpperCase() + name.substring(1), "", "", "", "");
    return id;
  }

  private long idOf(String email) throws Exception {
    return server.auth.forDomain("example.org").users.byEmail(email).id();
  }

  private Votes votes() {
    return server.auth.forDomain("example.org").votes;
  }

  private List<Weighing.Weighed> weigh(String slug) throws Exception {
    return Weighing.weigh(votes().bySlug(slug), server.auth.forDomain("example.org"),
        ZoneOffset.UTC);
  }

  private Weighing.Weighed find(List<Weighing.Weighed> all, String label) {
    return all.stream().filter(each -> each.option().equals(label)).findFirst().orElseThrow();
  }

  // ---- recurrence as a maybe ---------------------------------------------------------------------

  /**
   * A weekly commitment produces occurrences, and every one of them is a maybe.
   *
   * Before this, a repeating event was seen once and the other fifty-one weeks looked free. Seeing
   * them is the improvement; marking them soft is what stops the improvement making things worse.
   */
  @Test
  public void aWeeklyRuleBecomesManyMaybesRatherThanOneCertainty() {
    String ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n"
        + "DTSTART:20261006T190000Z\r\nDTEND:20261006T200000Z\r\n"
        + "RRULE:FREQ=WEEKLY;COUNT=6\r\nSUMMARY:Standing call\r\nEND:VEVENT\r\nEND:VCALENDAR";
    List<Ics.Busy> busy = Ics.busy(ics, ZoneOffset.UTC);
    assertEquals(6, busy.size());
    for (Ics.Busy window : busy) {
      assertFalse("a repeat is never firm", window.firm());
    }
  }

  @Test
  public void aOneOffIsFirmAndARepeatIsNot() {
    String ics = "BEGIN:VCALENDAR\r\n"
        + "BEGIN:VEVENT\r\nDTSTART:20261009T190000Z\r\nDTEND:20261009T220000Z\r\nEND:VEVENT\r\n"
        + "BEGIN:VEVENT\r\nDTSTART:20261006T190000Z\r\nDTEND:20261006T200000Z\r\n"
        + "RRULE:FREQ=WEEKLY;COUNT=2\r\nEND:VEVENT\r\nEND:VCALENDAR";
    List<Ics.Busy> busy = Ics.busy(ics, ZoneOffset.UTC);
    assertEquals(3, busy.size());
    assertEquals("one flight and two maybes", 1,
        busy.stream().filter(Ics.Busy::firm).count());
  }

  @Test
  public void everyOtherTuesdayAndThursdayIsBothDays() {
    String ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n"
        + "DTSTART:20261006T190000Z\r\nDTEND:20261006T200000Z\r\n"
        + "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH;COUNT=4\r\nEND:VEVENT\r\nEND:VCALENDAR";
    List<Ics.Busy> busy = Ics.busy(ics, ZoneOffset.UTC);
    assertEquals("a real calendar is full of these", 4, busy.size());
    // the 6th is a Tuesday; the 8th is the Thursday of the same week
    assertTrue(busy.stream().anyMatch(window ->
        window.start() == Instant.parse("2026-10-08T19:00:00Z").toEpochMilli()));
  }

  @Test
  public void anEndlessDailyRuleIsBoundedRatherThanInfinite() {
    String ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n"
        + "DTSTART:20261006T190000Z\r\nDTEND:20261006T193000Z\r\n"
        + "RRULE:FREQ=DAILY\r\nEND:VEVENT\r\nEND:VCALENDAR";
    List<Ics.Busy> busy = Ics.busy(ics, ZoneOffset.UTC);
    assertTrue("bounded both by days ahead and by a hard cap", busy.size() <= Ics.MAX_OCCURRENCES);
    assertTrue(busy.size() > 1);
  }

  @Test
  public void aClashIsThreeWaysRatherThanTwo() {
    long at = Instant.parse("2026-10-08T19:00:00Z").toEpochMilli();
    assertEquals(Ics.Clash.free, Ics.clash(List.of(), at, at + 1000));
    assertEquals(Ics.Clash.maybe,
        Ics.clash(List.of(new Ics.Busy(at, at + 5000, false)), at, at + 1000));
    assertEquals("firm beats maybe when both overlap", Ics.Clash.firm,
        Ics.clash(List.of(new Ics.Busy(at, at + 5000, false),
            new Ics.Busy(at, at + 5000, true)), at, at + 1000));
  }

  // ---- weighing ------------------------------------------------------------------------------------

  /**
   * A standing Tuesday call does not remove Tuesday.
   *
   * This is the whole change in one assertion. Everybody has a weekly something; if that made an
   * evening impossible, a group of five would have no evenings at all and the answer would be "none
   * work", which is right and useless.
   */
  @Test
  public void aRepeatingCommitmentCostsAnEveningRatherThanRemovingIt() throws Exception {
    long ana = member("ana");
    withCalendar(ana, "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n"
        + "DTSTART:20261008T190000Z\r\nDTEND:20261008T210000Z\r\n"
        + "RRULE:FREQ=WEEKLY;COUNT=8\r\nEND:VEVENT\r\nEND:VCALENDAR");

    votes().open("games", "Games", "?", List.of(), idOf("boss@example.com"), "Boss");
    votes().propose("games", "Thursday", null, "Thursday", stamp(THURSDAY), 0,
        idOf("boss@example.com"), "Boss");

    Weighing.Weighed thursday = find(weigh("games"), "Thursday");
    assertEquals("she would have to move it, not miss it", 1, thursday.wouldMove());
    assertEquals(0, thursday.cannotCome());
    assertTrue(thursday.verdict(), thursday.verdict().contains("could probably move"));
    assertTrue("and the reason is in the breakdown",
        thursday.people().stream().anyMatch(p -> p.from().contains("repeating")));
  }

  @Test
  public void aOneOffRemovesTheEveningForThatPerson() throws Exception {
    long ana = member("ana");
    withCalendar(ana, "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n"
        + "DTSTART:20261008T180000Z\r\nDTEND:20261008T230000Z\r\nEND:VEVENT\r\nEND:VCALENDAR");

    votes().open("games", "Games", "?", List.of(), idOf("boss@example.com"), "Boss");
    votes().propose("games", "Thursday", null, "Thursday", stamp(THURSDAY), 0,
        idOf("boss@example.com"), "Boss");

    Weighing.Weighed thursday = find(weigh("games"), "Thursday");
    assertEquals(1, thursday.cannotCome());
    assertTrue(thursday.verdict(), thursday.verdict().contains("1 cannot"));
  }

  /**
   * A ballot beats a calendar, in both directions.
   *
   * A calendar is an inference and a vote is a person speaking. Somebody who says yes on an evening
   * their calendar objects to has already decided to move it, and somebody who blocks an evening
   * that looks clear knows something the file does not.
   */
  @Test
  public void aBallotBeatsACalendarWhicheverWayItPoints() throws Exception {
    long ana = member("ana");
    withCalendar(ana, "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n"
        + "DTSTART:20261008T180000Z\r\nDTEND:20261008T230000Z\r\nEND:VEVENT\r\nEND:VCALENDAR");

    votes().open("games", "Games", "?", List.of(), idOf("boss@example.com"), "Boss");
    votes().propose("games", "Thursday", null, "Thursday", stamp(THURSDAY), 0,
        idOf("boss@example.com"), "Boss");
    votes().propose("games", "Friday", null, "Friday", stamp(FRIDAY), 0,
        idOf("boss@example.com"), "Boss");

    votes().cast("games", "Thursday", Votes.Ballot.yes, "I moved it", ana, "Ana");
    votes().cast("games", "Friday", Votes.Ballot.blocked, "something not in my calendar",
        ana, "Ana");

    Weighing.Weighed thursday = find(weigh("games"), "Thursday");
    assertEquals("she said she can, whatever the file says", 0, thursday.cannotCome());
    assertTrue(thursday.people().stream().anyMatch(p -> p.from().equals("they voted")));

    Weighing.Weighed friday = find(weigh("games"), "Friday");
    assertEquals("and she said she cannot, whatever the file says", 1, friday.cannotCome());
  }

  @Test
  public void theEveningThatCostsLeastComesFirst() throws Exception {
    long ana = member("ana");
    long bo = member("bo");
    votes().open("games", "Games", "?", List.of(), idOf("boss@example.com"), "Boss");
    votes().propose("games", "Thursday", null, "Thursday", stamp(THURSDAY), 0,
        idOf("boss@example.com"), "Boss");
    votes().propose("games", "Friday", null, "Friday", stamp(FRIDAY), 0,
        idOf("boss@example.com"), "Boss");

    votes().cast("games", "Thursday", Votes.Ballot.yes, null, ana, "Ana");
    votes().cast("games", "Thursday", Votes.Ballot.yes, null, bo, "Bo");
    votes().cast("games", "Friday", Votes.Ballot.blocked, "away", bo, "Bo");
    votes().cast("games", "Friday", Votes.Ballot.yes, null, ana, "Ana");

    List<Weighing.Weighed> weighed = weigh("games");
    assertEquals("Thursday", weighed.get(0).option());
    assertTrue("and it says so in a sentence somebody can read",
        weighed.get(0).verdict().contains("can come"));
  }

  @Test
  public void aHostWhoCannotSinksTheOptionWhateverElseIsTrue() throws Exception {
    long ana = member("ana");
    long bo = member("bo");
    votes().open("games", "Games", "?", List.of(), Votes.Mode.majority, ana,
        idOf("boss@example.com"), "Boss");
    votes().propose("games", "Thursday", null, "Thursday", stamp(THURSDAY), 0,
        idOf("boss@example.com"), "Boss");
    votes().propose("games", "Friday", null, "Friday", stamp(FRIDAY), 0,
        idOf("boss@example.com"), "Boss");
    votes().cast("games", "Thursday", Votes.Ballot.yes, null, bo, "Bo");
    votes().cast("games", "Thursday", Votes.Ballot.blocked, "not home", ana, "Ana");
    votes().cast("games", "Friday", Votes.Ballot.fine, null, ana, "Ana");

    List<Weighing.Weighed> weighed = weigh("games");
    assertEquals("Friday", weighed.get(0).option());
    assertFalse(find(weighed, "Thursday").hostCanHost());
    assertTrue(find(weighed, "Thursday").verdict(),
        find(weighed, "Thursday").verdict().contains("host cannot"));
  }

  @Test
  public void anOptionWithNoTimeIsWeighedOnBallotsAlone() throws Exception {
    long ana = member("ana");
    withCalendar(ana, "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n"
        + "DTSTART:20261008T180000Z\r\nDTEND:20261008T230000Z\r\nEND:VEVENT\r\nEND:VCALENDAR");
    votes().open("games", "Games", "?", List.of("Sometime in October"),
        idOf("boss@example.com"), "Boss");

    Weighing.Weighed vague = find(weigh("games"), "Sometime in October");
    assertEquals("nothing to check it against, and no pretending otherwise",
        0, vague.cannotCome());
    assertTrue(vague.people().stream()
        .anyMatch(p -> p.from().contains("no time to check") || p.from().contains("no calendar")));
  }

  /**
   * Somebody who has said nothing counts as nothing, in either direction.
   *
   * Silence is not agreement and it is not refusal. Counting it either way would make the ranking a
   * statement about people who have not spoken.
   */
  @Test
  public void silenceCountsForNothingRatherThanAgainst() throws Exception {
    member("ana");
    votes().open("games", "Games", "?", List.of(), idOf("boss@example.com"), "Boss");
    votes().propose("games", "Thursday", null, "Thursday", stamp(THURSDAY), 0,
        idOf("boss@example.com"), "Boss");

    Weighing.Weighed thursday = find(weigh("games"), "Thursday");
    assertEquals(2, thursday.unknown());
    assertEquals(0, thursday.canCome());
    assertEquals(0, thursday.cannotCome());
    assertTrue(thursday.verdict(), thursday.verdict().contains("have not said"));
  }

  // ---- the seed ------------------------------------------------------------------------------------

  /**
   * An agent is told who hosts and how movable each person is.
   *
   * This is the difference between a good first proposal and a round of guessing: one person hosts
   * and has a full calendar, another is free most evenings and immovable on three, and the same
   * proposal is right for one and wrong for the other.
   */
  @Test
  public void hostingAndFlexibilityAreCarriedSeparatelyFromFreeBusy() throws Exception {
    long zed = member("zed");
    server.auth.forDomain("example.org").availability.save(zed, "{}", "complex week", "",
        true, Availability.Flexibility.tightly_booked);
    server.auth.forDomain("example.org").availability.save(idOf("boss@example.com"),
        "{\"most\":\"evenings\"}", "unless there is a big session", "",
        false, Availability.Flexibility.mostly_free);

    Availability.Record host = server.auth.forDomain("example.org").availability.of(zed);
    assertTrue(host.hosts());
    assertEquals(Availability.Flexibility.tightly_booked, host.flexibility());
    assertTrue(host.flexibility().advice.contains("Do not assume"));

    Availability.Record grinder =
        server.auth.forDomain("example.org").availability.of(idOf("boss@example.com"));
    assertFalse(grinder.hosts());
    assertEquals(Availability.Flexibility.mostly_free, grinder.flexibility());
    assertTrue(grinder.flexibility().advice.contains("Propose freely"));
  }

  @Test
  public void somebodyWhoOnlySaysTheyCanHostStillAppearsToAnAgent() throws Exception {
    long zed = member("zed");
    server.auth.forDomain("example.org").availability.save(zed, "", "", "",
        true, Availability.Flexibility.it_depends);
    Availability.Record host = server.auth.forDomain("example.org").availability.of(zed);
    assertEquals("nothing about their week, but the hosting is worth knowing",
        Availability.Kind.unknown, host.kind());
    assertTrue(host.hosts());
  }

  /**
   * Give somebody a calendar, through the real caching path with a stubbed fetch.
   *
   * The host has to be one that actually resolves, because {@code PublicAddress.refuse} resolves
   * before fetching -- a name that does not resolve is refused, which is the SSRF guard doing its
   * job and was the first thing this test ran into. `example.com` is reserved for exactly this and
   * is never contacted: the stub answers before any socket is opened.
   */
  private void withCalendar(long userId, String ics) throws Exception {
    String url = "https://example.com/" + userId + ".ics";
    server.auth.forDomain("example.org").availability.save(userId, "", "", url, false,
        Availability.Flexibility.it_depends);
    new Calendars(server.auth.forDomain("example.org").store, StubCalendar.answering(ics))
        .of(userId, url, ZoneOffset.UTC);
  }

  private static long stamp(String iso) {
    return Instant.parse(iso).toEpochMilli();
  }
}
