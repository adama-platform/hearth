package io.hearth.calendar;

import io.hearth.auth.Accounts;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The calendar, and the one thing it must never get wrong.
 *
 * Which is capacity. Everything else here is a listing; the seat counting is where a calendar can
 * quietly tell twenty people they have a place in a room that holds twelve, and nobody finds out
 * until they are standing outside it. So most of these tests are about the last seat.
 */
public class CalendarTests {
  private Configs configs;
  private TestServer server;
  private Browser boss;
  private Browser ana;
  private Browser ben;
  private Browser cass;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\",\"ana@example.com\","
            + "\"ben@example.com\",\"cass@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    boss = signIn("boss@example.com");
    ana = signIn("ana@example.com");
    ben = signIn("ben@example.com");
    cass = signIn("cass@example.com");
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

  // ---- making one ------------------------------------------------------------------------------

  @Test
  public void anAdminAnnouncesSomethingAndMembersSeeIt() throws Exception {
    long id = event("Summer social", soon(7), null, "The back room", null, true);

    Browser.Page list = ana.get("/events");
    assertEquals(200, list.status());
    assertTrue(list.contains("Summer social"));
    assertTrue(list.contains("The back room"));

    Browser.Page page = ana.get("/events/" + id);
    assertEquals(200, page.status());
    assertTrue(page.contains("Are you coming?"));
  }

  @Test
  public void aDraftIsInvisibleToMembersAndVisibleToAdmins() throws Exception {
    long id = event("Not announced yet", soon(7), null, "", null, false);

    assertFalse(ana.get("/events").contains("Not announced yet"));
    assertEquals("an admin can look at their own draft", 200, boss.get("/events/" + id).status());
    // ana is an admin here too, so the refusal is checked against the store rather than the page
    assertFalse(calendar().upcoming(LocalDate.now(), 10).stream()
        .anyMatch(e -> e.id() == id));
  }

  @Test
  public void anEndBeforeAStartIsReadAsOneDayRatherThanASpan() throws Exception {
    long id = event("Typo day", soon(10), soon(3), "", null, true);
    Calendar.Event event = calendar().byId(id);
    assertEquals("the shorter reading is the safe one",
        event.startsOn(), event.endsOn());
    assertFalse(event.spansDays());
  }

  @Test
  public void aSpanReadsAsASpan() throws Exception {
    long id = event("Long weekend", soon(10), soon(12), "", null, true);
    assertTrue(calendar().byId(id).spansDays());
    assertTrue(ana.get("/events/" + id).contains("to "));
  }

  @Test
  public void theCalendarNeedsYouSignedIn() throws Exception {
    event("Members only", soon(7), null, "", null, true);
    Browser stranger = new Browser(server.port, "example.org");
    Browser.Page page = stranger.get("/events");
    assertEquals(303, page.status());
    assertTrue(page.location(), page.location().startsWith("/login"));
    assertTrue("and comes back afterwards", page.location().contains("next="));
  }

  // ---- answering -------------------------------------------------------------------------------

  @Test
  public void sayingYesPutsYouOnTheList() throws Exception {
    long id = event("Summer social", soon(7), null, "", null, true);
    rsvp(ana, id, "going", 1, "bringing cake");

    Calendar.Event event = calendar().byId(id);
    assertEquals(1, event.goingCount());
    Browser.Page page = ben.get("/events/" + id);
    assertTrue("the guest list is names, not a number", page.contains("Ana"));
    assertFalse("and names rather than addresses", page.contains("ana@example.com"));
    assertTrue("with what they said", page.contains("bringing cake"));
  }

  @Test
  public void changingYourMindChangesTheAnswerRatherThanAddingASecondOne() throws Exception {
    long id = event("Summer social", soon(7), null, "", null, true);
    rsvp(ana, id, "going", 1, "");
    rsvp(ana, id, "maybe", 1, "");

    assertEquals(1, calendar().guestList(id).size());
    Calendar.Event event = calendar().byId(id);
    assertEquals(0, event.goingCount());
    assertEquals(1, event.maybeCount());
  }

  @Test
  public void aPartyTakesAsManyPlacesAsItHasPeople() throws Exception {
    long id = event("Dinner", soon(7), null, "", 10, true);
    rsvp(ana, id, "going", 4, "");
    assertEquals("four chairs, not one", 4, calendar().byId(id).goingCount());
    assertEquals(6, calendar().byId(id).seatsLeft());
  }

  @Test
  public void takingYourAnswerBackRemovesItEntirely() throws Exception {
    long id = event("Summer social", soon(7), null, "", null, true);
    rsvp(ana, id, "going", 1, "");
    ana.get("/events/" + id);
    ana.submitTo("/events", Map.of("action", "withdraw", "event", Long.toString(id)));

    assertEquals(0, calendar().guestList(id).size());
    assertEquals(0, calendar().byId(id).goingCount());
  }

  @Test
  public void anEventThatHasHappenedTakesNoMoreAnswers() throws Exception {
    long id = event("Last month", soon(-40), soon(-40), "", null, true);
    rsvp(ana, id, "going", 1, "");
    assertEquals("the page hides the box and the server refuses it anyway",
        0, calendar().guestList(id).size());
  }

  @Test
  public void aCancelledEventTakesNoMoreAnswersAndKeepsTheOnesItHad() throws Exception {
    long id = event("Summer social", soon(7), null, "", null, true);
    rsvp(ana, id, "going", 1, "");
    calendar().cancel(id, true, null);

    rsvp(ben, id, "going", 1, "");
    assertEquals("ben was refused", 1, calendar().guestList(id).size());
    Browser.Page page = ana.get("/events/" + id);
    assertTrue("and ana can still see it is off", page.contains("cancelled"));
    assertTrue("and that she had said yes", page.contains("Ana"));
  }

  // ---- the last seat ---------------------------------------------------------------------------

  @Test
  public void thePersonWhoFillsTheRoomGetsTheLastSeat() throws Exception {
    long id = event("Small room", soon(7), null, "", 2, true);
    rsvp(ana, id, "going", 1, "");
    rsvp(ben, id, "going", 1, "");

    assertTrue(calendar().byId(id).full());
    assertEquals(Calendar.Answer.going, calendar().rsvpFor(id, idOf("ben@example.com")).answer());
  }

  @Test
  public void thePersonAfterThatIsToldTheyAreWaitingRatherThanShownATick() throws Exception {
    long id = event("Small room", soon(7), null, "", 2, true);
    rsvp(ana, id, "going", 1, "");
    rsvp(ben, id, "going", 1, "");
    rsvp(cass, id, "going", 1, "");

    assertEquals("she asked to come; the answer stored is what the room could give her",
        Calendar.Answer.waitlist, calendar().rsvpFor(id, idOf("cass@example.com")).answer());
    assertEquals(2, calendar().byId(id).goingCount());
    assertEquals(1, calendar().byId(id).waitlistCount());

    Browser.Page page = cass.get("/events/" + id);
    assertTrue("and the page says so in words", page.contains("waitlist"));
  }

  @Test
  public void aPartyThatDoesNotFitWaitsEvenWhenSomeSeatsAreLeft() throws Exception {
    long id = event("Small room", soon(7), null, "", 4, true);
    rsvp(ana, id, "going", 3, "");
    rsvp(ben, id, "going", 3, "");

    assertEquals("three into one does not go",
        Calendar.Answer.waitlist, calendar().rsvpFor(id, idOf("ben@example.com")).answer());
    assertEquals(3, calendar().byId(id).goingCount());
  }

  @Test
  public void somebodyDroppingOutPromotesTheLongestWaitThatFits() throws Exception {
    long id = event("Small room", soon(7), null, "", 2, true);
    rsvp(ana, id, "going", 1, "");
    rsvp(ben, id, "going", 1, "");
    rsvp(cass, id, "going", 1, "");
    assertEquals(Calendar.Answer.waitlist, calendar().rsvpFor(id, idOf("cass@example.com")).answer());

    rsvp(ana, id, "no", 1, "");
    assertEquals("cass was waiting longest and fits",
        Calendar.Answer.going, calendar().rsvpFor(id, idOf("cass@example.com")).answer());
    assertEquals(2, calendar().byId(id).goingCount());
    assertEquals(0, calendar().byId(id).waitlistCount());
  }

  @Test
  public void oneBigPartyWaitingDoesNotBlockTheSmallOnesBehindIt() throws Exception {
    long id = event("Small room", soon(7), null, "", 6, true);
    rsvp(ana, id, "going", 6, "");
    rsvp(ben, id, "going", 5, "");
    rsvp(cass, id, "going", 1, "");
    assertEquals(Calendar.Answer.waitlist, calendar().rsvpFor(id, idOf("ben@example.com")).answer());
    assertEquals(Calendar.Answer.waitlist, calendar().rsvpFor(id, idOf("cass@example.com")).answer());

    rsvp(ana, id, "going", 5, "");
    assertEquals("ben's five still do not fit in the one seat freed",
        Calendar.Answer.waitlist, calendar().rsvpFor(id, idOf("ben@example.com")).answer());
    assertEquals("but cass's one does, and is not held up behind him",
        Calendar.Answer.going, calendar().rsvpFor(id, idOf("cass@example.com")).answer());
  }

  @Test
  public void raisingTheCapacitySeatsEverybodyWhoWasWaiting() throws Exception {
    long id = event("Small room", soon(7), null, "", 1, true);
    rsvp(ana, id, "going", 1, "");
    rsvp(ben, id, "going", 1, "");
    rsvp(cass, id, "going", 1, "");
    assertEquals(2, calendar().byId(id).waitlistCount());

    Calendar.Event event = calendar().byId(id);
    calendar().update(id, event.title(), event.body(), event.location(), event.startsOn(),
        event.endsOn(), event.startTime(), 10, true, null);
    assertEquals(3, calendar().byId(id).goingCount());
    assertEquals(0, calendar().byId(id).waitlistCount());
  }

  @Test
  public void removingTheLimitAltogetherSeatsEverybody() throws Exception {
    long id = event("Small room", soon(7), null, "", 1, true);
    rsvp(ana, id, "going", 1, "");
    rsvp(ben, id, "going", 1, "");

    Calendar.Event event = calendar().byId(id);
    calendar().update(id, event.title(), event.body(), event.location(), event.startsOn(),
        event.endsOn(), event.startTime(), null, true, null);
    assertEquals(2, calendar().byId(id).goingCount());
    assertEquals(0, calendar().byId(id).waitlistCount());
  }

  @Test
  public void loweringTheCapacityNeverTakesBackASeatSomebodyAlreadyHas() throws Exception {
    long id = event("Small room", soon(7), null, "", 10, true);
    rsvp(ana, id, "going", 1, "");
    rsvp(ben, id, "going", 1, "");
    rsvp(cass, id, "going", 1, "");

    Calendar.Event event = calendar().byId(id);
    calendar().update(id, event.title(), event.body(), event.location(), event.startsOn(),
        event.endsOn(), event.startTime(), 1, true, null);
    assertEquals("taking back a yes is a thing a person does, not a sweep",
        3, calendar().byId(id).goingCount());
  }

  @Test
  public void aMaybeIsNeverASeat() throws Exception {
    long id = event("Small room", soon(7), null, "", 1, true);
    rsvp(ana, id, "maybe", 4, "");
    rsvp(ben, id, "going", 1, "");

    assertEquals("the maybe did not fill the room", Calendar.Answer.going,
        calendar().rsvpFor(id, idOf("ben@example.com")).answer());
    assertEquals(1, calendar().byId(id).goingCount());
    assertEquals(4, calendar().byId(id).maybeCount());
  }

  @Test
  public void changingYourOwnPartySizeDoesNotCountYouTwice() throws Exception {
    long id = event("Small room", soon(7), null, "", 4, true);
    rsvp(ana, id, "going", 3, "");
    rsvp(ana, id, "going", 4, "");

    assertEquals("her old three are not still holding chairs",
        Calendar.Answer.going, calendar().rsvpFor(id, idOf("ana@example.com")).answer());
    assertEquals(4, calendar().byId(id).goingCount());
  }

  // ---- deleting --------------------------------------------------------------------------------

  @Test
  public void deletingAnEventTakesTheAnswersWithIt() throws Exception {
    long id = event("Summer social", soon(7), null, "", null, true);
    rsvp(ana, id, "going", 1, "");
    calendar().delete(id, null);

    assertNull(calendar().byId(id));
    assertEquals(0, calendar().guestList(id).size());
    assertEquals(404, ana.get("/events/" + id).status());
  }

  @Test
  public void somebodyLeavingTheCommunityFreesTheirSeat() throws Exception {
    long id = event("Small room", soon(7), null, "", 1, true);
    rsvp(ana, id, "going", 1, "");
    rsvp(ben, id, "going", 1, "");
    assertEquals(Calendar.Answer.waitlist, calendar().rsvpFor(id, idOf("ben@example.com")).answer());

    calendar().forget(idOf("ana@example.com"));
    assertEquals("ben moves up", Calendar.Answer.going,
        calendar().rsvpFor(id, idOf("ben@example.com")).answer());
  }

  // ---- the admin screen ------------------------------------------------------------------------

  @Test
  public void theAdminListingShowsDraftsAndCounts() throws Exception {
    event("Announced", soon(7), null, "", 10, true);
    event("A draft", soon(9), null, "", null, false);

    Browser.Page page = boss.get("/admin/calendar");
    assertEquals(200, page.status());
    assertTrue(page.contains("Announced"));
    assertTrue(page.contains("A draft"));
    assertTrue(page.contains("draft"));
  }

  @Test
  public void anEventNeedsANameAndADay() throws Exception {
    Browser.Page noName = boss.submitToAndFollow("/admin/calendar", form("", "2099-01-01"));
    assertTrue(noName.contains("needs a name"));

    Browser.Page noDay = boss.submitToAndFollow("/admin/calendar", form("Something", "next week"));
    assertTrue(noDay.contains("YYYY-MM-DD"));
    assertEquals(0, calendar().count());
  }

  @Test
  public void cancellingKeepsThePageAndDeletingDoesNot() throws Exception {
    long id = event("Summer social", soon(7), null, "", null, true);
    rsvp(ana, id, "going", 1, "");

    boss.submitToAndFollow("/admin/calendar",
        Map.of("action", "cancel", "id", Long.toString(id)));
    assertTrue(calendar().byId(id).cancelled());
    assertEquals("the people who planned around it can still see it", 200,
        ana.get("/events/" + id).status());

    boss.submitToAndFollow("/admin/calendar",
        Map.of("action", "delete", "id", Long.toString(id)));
    assertNull(calendar().byId(id));
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  private Calendar calendar() {
    return accounts().calendar;
  }

  private long idOf(String email) throws Exception {
    return accounts().users.byEmail(email).id();
  }

  private static String soon(int days) {
    return LocalDate.now().plusDays(days).toString();
  }

  private static LinkedHashMap<String, String> form(String title, String starts) {
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("action", "save");
    form.put("title", title);
    form.put("starts_on", starts);
    return form;
  }

  private long event(String title, String starts, String ends, String where, Integer capacity,
                     boolean published) throws Exception {
    LinkedHashMap<String, String> form = form(title, starts);
    form.put("ends_on", ends == null ? "" : ends);
    form.put("location", where);
    form.put("capacity", capacity == null ? "" : capacity.toString());
    form.put("body", "Come along.");
    if (published) {
      form.put("published", "on");
    }
    boss.submitToAndFollow("/admin/calendar", form);
    List<Calendar.Event> all = calendar().all(100);
    for (Calendar.Event event : all) {
      if (event.title().equals(title)) {
        return event.id();
      }
    }
    throw new IllegalStateException("event was not created: " + title);
  }

  private void rsvp(Browser who, long eventId, String answer, int party, String note)
      throws Exception {
    who.get("/events/" + eventId);
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("action", "rsvp");
    form.put("event", Long.toString(eventId));
    form.put("answer", answer);
    form.put("party", Integer.toString(party));
    form.put("note", note);
    Browser.Page done = who.submitTo("/events", form);
    assertEquals(303, done.status());
    assertNotNull(done.location());
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    // ...and a name, because a name is what members are shown of each other
    String name = Character.toUpperCase(email.charAt(0)) + email.substring(1, email.indexOf('@'));
    browser.get("/self");
    browser.submitTo("/self", Map.of("action", "profile", "display_name", name,
        "headline", "", "about", "", "location", "", "links", ""));
    return browser;
  }
}
