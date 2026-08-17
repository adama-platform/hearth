package io.hearth.calendar;

import io.hearth.common.Verbose;
import io.hearth.smtp.Envelope;
import io.hearth.testkit.Browser;
import io.hearth.testkit.CapturingMailer;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * An event anybody may come to, and the people who say they are coming.
 *
 * Two things are being kept apart here, and mixing them is the failure this whole shape exists to
 * avoid. A stranger's answer is real -- somebody heard about a thing and said they would be there --
 * and it is not a seat: the capacity of a room is a promise to the people a community can actually
 * reach. So it goes somewhere of its own, counts nobody in, and becomes an ordinary answer at the
 * moment they are let in.
 */
public class PublicEventTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;
  private long eventId;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com", "The Boss");
    LocalDate day = LocalDate.now().plusDays(10);
    admin.get("/admin/calendar/new");
    admin.submitTo("/admin/calendar", Map.of("action", "save", "title", "Supper club",
        "body", "Bring a chair.", "location", "The hall", "place_id", "",
        "starts_on", day.toString(), "ends_on", day.toString(), "start_time", "7pm",
        "capacity", "", "published", "on"));
    eventId = calendar().upcoming(LocalDate.now(), 5).get(0).id();
    // an event has no calendar identity until somebody is invited, and a reply needs one
    calendar().stampUid(eventId, "supper-1@example.org");
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

  // ---- the file ---------------------------------------------------------------------------------

  @Test
  public void aMemberCanTakeTheFileForAnyEventTheyCanRead() throws Exception {
    Browser ana = signIn("ana@example.com", "Ana Rivera");
    Browser.Page file = ana.get("/events/" + eventId + ".ics");
    assertEquals(200, file.status());
    assertTrue(file.body().contains("BEGIN:VCALENDAR"));
    assertTrue("published rather than a request, because nobody was invited by this",
        file.body().contains("METHOD:PUBLISH"));
    assertTrue(file.body().contains("SUMMARY:Supper club"));
    assertTrue("and it keeps the identity, so a later invitation is the same entry",
        file.body().contains("UID:supper-1@example.org"));
    assertFalse("with nobody named as an attendee", file.body().contains("ATTENDEE"));
  }

  @Test
  public void anEventThatIsNotOpenIsNotAFileAnybodyCanFetch() throws Exception {
    try (Http http = new Http()) {
      Http.Response answer = http.get(server.port, "example.org", "/events/" + eventId + ".ics");
      assertEquals("a 404 rather than a redirect: a calendar program has nowhere to sign in",
          404, answer.status);
      assertFalse(answer.body.contains("BEGIN:VCALENDAR"));
    }
  }

  @Test
  public void openingItMakesTheFilePublic() throws Exception {
    open();
    try (Http http = new Http()) {
      Http.Response answer = http.get(server.port, "example.org", "/events/" + eventId + ".ics");
      assertEquals(200, answer.status);
      assertTrue(answer.body.contains("SUMMARY:Supper club"));
      assertTrue("addressed to the calendar's own address, so an answer has somewhere to go",
          answer.body.contains("mailto:events@example.org"));
      assertEquals("text/calendar; charset=utf-8", answer.header("content-type"));
    }
  }

  @Test
  public void aDraftIsNeverAFileEvenWhenItIsOpen() throws Exception {
    LocalDate day = LocalDate.now().plusDays(20);
    admin.get("/admin/calendar/new");
    admin.submitTo("/admin/calendar", Map.of("action", "save", "title", "Not announced",
        "body", "", "location", "", "place_id", "", "starts_on", day.toString(),
        "ends_on", day.toString(), "start_time", "", "capacity", ""));
    long draft = calendar().all(50).stream().filter(event -> event.title().equals("Not announced"))
        .findFirst().orElseThrow().id();
    calendar().openToPublic(draft, true, null);
    try (Http http = new Http()) {
      assertEquals(404, http.get(server.port, "example.org", "/events/" + draft + ".ics").status);
    }
  }

  // ---- the answers ------------------------------------------------------------------------------

  @Test
  public void ananswerFromOutsideIsIgnoredUntilTheEventIsOpen() throws Exception {
    // accepted and ignored, never bounced: a 550 to a calendar client teaches it this address is
    // broken, and the honest failure is that the answer did not register
    assertEquals("received", deliver("stranger@elsewhere.example", "ACCEPTED"));
    assertEquals(0, calendar().outsiders(eventId).size());
  }

  @Test
  public void anAnswerFromOutsideAnOpenEventIsWrittenDown() throws Exception {
    open();
    deliver("stranger@elsewhere.example", "ACCEPTED");

    List<Calendar.Outsider> outside = calendar().outsiders(eventId);
    assertEquals(1, outside.size());
    assertEquals("stranger@elsewhere.example", outside.get(0).email());
    assertEquals(Calendar.Answer.going, outside.get(0).answer());
    assertEquals("what their calendar said they are called", "Somebody", outside.get(0).name());
    assertFalse(outside.get(0).invited());
    assertFalse(outside.get(0).converted());
  }

  @Test
  public void itTakesNobodysSeat() throws Exception {
    open();
    deliver("stranger@elsewhere.example", "ACCEPTED");
    Calendar.Event event = calendar().byId(eventId);
    assertEquals("the count on the event is members, and a stranger is not one",
        0, event.goingCount());
    assertEquals("and the guest list is untouched", 0, calendar().guestList(eventId).size());
  }

  @Test
  public void changingTheirMindChangesTheOneRow() throws Exception {
    open();
    deliver("stranger@elsewhere.example", "ACCEPTED");
    deliver("stranger@elsewhere.example", "DECLINED");
    List<Calendar.Outsider> outside = calendar().outsiders(eventId);
    assertEquals("one person, one answer", 1, outside.size());
    assertEquals(Calendar.Answer.no, outside.get(0).answer());
  }

  @Test
  public void aMemberAnsweringByEmailIsStillAMember() throws Exception {
    // the two paths are told apart by the address, and getting that backwards would put members
    // on the outside list and leave the guest list empty
    signIn("ana@example.com", "Ana Rivera");
    open();
    deliver("ana@example.com", "ACCEPTED");
    assertEquals(1, calendar().guestList(eventId).size());
    assertEquals(0, calendar().outsiders(eventId).size());
  }

  @Test
  public void aClosedEventStopsTakingThemAgain() throws Exception {
    open();
    deliver("stranger@elsewhere.example", "ACCEPTED");
    open();
    assertEquals("received", deliver("another@elsewhere.example", "ACCEPTED"));
    assertEquals("and what already arrived is kept, because those people still said it",
        1, calendar().outsiders(eventId).size());
  }

  // ---- and what it is all for -------------------------------------------------------------------

  @Test
  public void anAdminSeesTheAddressAndCanInviteThem() throws Exception {
    open();
    deliver("stranger@elsewhere.example", "ACCEPTED");

    Browser.Page form = admin.get("/admin/calendar/edit/" + eventId);
    assertTrue("the address, because the decision being made here is about an address",
        form.contains("stranger@elsewhere.example"));

    long guest = calendar().outsiders(eventId).get(0).id();
    Browser.Page done = admin.submitToAndFollow("/admin/calendar", Map.of("action",
        "invite_outsider", "id", Long.toString(eventId), "guest", Long.toString(guest)));
    assertTrue(done.body(), done.contains("has been invited"));
    assertTrue(calendar().outsiders(eventId).get(0).invited());
    assertEquals(1, mail().forFlow("invite").size());
  }

  @Test
  public void amemberPageNamesThemAndNeverAddressesThem() throws Exception {
    open();
    deliver("stranger@elsewhere.example", "ACCEPTED");
    Browser ana = signIn("ana@example.com", "Ana Rivera");
    Browser.Page page = ana.get("/events/" + eventId);
    assertTrue("somebody coming to the same evening is worth knowing about",
        page.contains("From outside"));
    assertTrue(page.contains("Somebody"));
    assertFalse("but an address is not a name here any more than it is anywhere else",
        page.contains("stranger@elsewhere.example"));
  }

  @Test
  public void joiningTurnsWhatTheySaidIntoWhatTheySaid() throws Exception {
    open();
    deliver("stranger@elsewhere.example", "ACCEPTED");

    // they join, and somebody approves them
    Browser them = new Browser(server.port, "example.org");
    them.get("/register");
    them.submit(Map.of("email", "stranger@elsewhere.example"));
    them.submit(Map.of("code", server.mail().lastCodeFor("stranger@elsewhere.example")));
    long id = server.auth.forDomain("example.org").users.byEmail("stranger@elsewhere.example").id();
    assertEquals("not yet, because they are not somebody this community can reach",
        0, calendar().guestList(eventId).size());

    admin.get("/admin/people");
    Browser.Page done = admin.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(id)));
    assertTrue(done.body(), done.contains("now on the guest list"));

    assertEquals(1, calendar().guestList(eventId).size());
    assertEquals(Calendar.Answer.going, calendar().rsvpFor(eventId, id).answer());
    assertEquals("and the seat is counted now that there is somebody in it",
        1, calendar().byId(eventId).goingCount());
    assertTrue("the outside row stays, marked, because it is how they got here",
        calendar().outsiders(eventId).get(0).converted());
  }

  @Test
  public void nobodyIsAddedToSomethingThatAlreadyHappened() throws Exception {
    LocalDate gone = LocalDate.now().minusDays(3);
    admin.get("/admin/calendar/new");
    admin.submitTo("/admin/calendar", Map.of("action", "save", "title", "Last month",
        "body", "", "location", "", "place_id", "", "starts_on", gone.toString(),
        "ends_on", gone.toString(), "start_time", "", "capacity", "", "published", "on"));
    long past = calendar().all(50).stream().filter(event -> event.title().equals("Last month"))
        .findFirst().orElseThrow().id();
    calendar().answerPublicly(past, "stranger@elsewhere.example", "Somebody",
        Calendar.Answer.going, 1, "", "email");

    long id = server.auth.forDomain("example.org").users.byEmail("boss@example.com").id();
    server.auth.forDomain("example.org").calendar.adopt(id, "stranger@elsewhere.example",
        LocalDate.now());
    assertEquals("turning up in a guest list for last March is inventing a history",
        0, calendar().guestList(past).size());
    assertFalse(calendar().outsiders(past).get(0).converted());
  }

  @Test
  public void erasingSomebodyTakesWhatTheySaidFromOutsideWithIt() throws Exception {
    open();
    deliver("stranger@elsewhere.example", "ACCEPTED");
    Browser them = new Browser(server.port, "example.org");
    them.get("/register");
    them.submit(Map.of("email", "stranger@elsewhere.example"));
    them.submit(Map.of("code", server.mail().lastCodeFor("stranger@elsewhere.example")));
    long id = server.auth.forDomain("example.org").users.byEmail("stranger@elsewhere.example").id();

    io.hearth.people.Erasure.erase(server.auth.forDomain("example.org"), null,
        server.auth.forDomain("example.org").users.byId(id), null, false);
    assertEquals("an address is the only thing identifying those rows, which is why they go",
        0, calendar().outsiders(eventId).size());
  }

  // ---- plumbing ---------------------------------------------------------------------------------

  private void open() throws Exception {
    admin.get("/admin/calendar/edit/" + eventId);
    admin.submitToAndFollow("/admin/calendar",
        Map.of("action", "open_public", "id", Long.toString(eventId)));
  }

  private Calendar calendar() {
    return server.auth.forDomain("example.org").calendar;
  }

  private CapturingMailer mail() {
    return (CapturingMailer) server.mailer;
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

  /** a reply the way a calendar program sends one, through the real inbound path */
  private String deliver(String from, String partstat) throws Exception {
    String ics = "BEGIN:VCALENDAR\r\n"
        + "PRODID:-//Test Client//EN\r\n"
        + "VERSION:2.0\r\n"
        + "METHOD:REPLY\r\n"
        + "BEGIN:VEVENT\r\n"
        + "UID:supper-1@example.org\r\n"
        + "SEQUENCE:0\r\n"
        + "ATTENDEE;PARTSTAT=" + partstat + ";CN=Somebody:mailto:" + from + "\r\n"
        + "ORGANIZER:mailto:events@example.org\r\n"
        + "END:VEVENT\r\n"
        + "END:VCALENDAR\r\n";
    String body = "From: " + from + "\r\n"
        + "To: events@example.org\r\n"
        + "Authentication-Results: example.org; spf=pass; dkim=pass; dmarc=pass\r\n"
        + "Content-Type: text/calendar; charset=UTF-8; method=REPLY\r\n\r\n" + ics;
    Envelope envelope = new Envelope(from, List.of("events@example.org"),
        body.getBytes(StandardCharsets.UTF_8), "10.0.0.1", "mail.sender.example", "example.org",
        System.currentTimeMillis());
    return new io.hearth.smtp.CommunityMailReceiver(server.auth, server.tree, null, Verbose.OFF)
        .receive(envelope).detail();
  }
}
