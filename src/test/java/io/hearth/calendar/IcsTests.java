package io.hearth.calendar;

import io.hearth.testkit.Browser;
import io.hearth.testkit.CapturingMailer;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import io.hearth.common.Verbose;
import io.hearth.mail.Mailer;
import io.hearth.smtp.Envelope;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * An invitation out, an answer back, and everything in between that must not be believed.
 *
 * <b>The reply half is the half most software skips</b>, which is why most calendar invitations are
 * an announcement with buttons that go nowhere. It is also the half with the security in it: a
 * message claiming to be somebody accepting an invitation is a claim about identity arriving over
 * SMTP, and believing it without checks means anybody who can send an email can fill a guest list
 * with people who never agreed to come.
 *
 * So the refusals below are the point of the file, and each one is a specific way that goes wrong.
 */
public class IcsTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;
  private long eventId;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = member("boss@example.com", "The Boss");
    member("ana@example.com", "Ana Rivera");
    LocalDate day = LocalDate.now().plusDays(10);
    admin.get("/admin/calendar/new");
    admin.submitTo("/admin/calendar", Map.of("action", "save", "title", "Supper club",
        "body", "Bring a chair.", "location", "The hall", "place_id", "",
        "starts_on", day.toString(), "ends_on", day.toString(), "start_time", "7pm",
        "capacity", "", "published", "on"));
    eventId = calendar().upcoming(LocalDate.now(), 5).get(0).id();
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

  private Calendar calendar() {
    return server.auth.forDomain("example.org").calendar;
  }

  private CapturingMailer mail() {
    return (CapturingMailer) server.mailer;
  }

  private Browser member(String email, String name) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    browser.get("/welcome");
    browser.submitTo("/welcome",
        Map.of("action", "name", "display_name", name, "location", "", "about", ""));
    browser.get("/welcome?step=3");
    if (!email.startsWith("boss")) {
      long id = server.auth.forDomain("example.org").users.byEmail(email).id();
      admin.get("/admin/people");
      admin.submitTo("/admin/people", Map.of("action", "approve", "user", Long.toString(id)));
    }
    return browser;
  }

  private long idOf(String email) throws Exception {
    return server.auth.forDomain("example.org").users.byEmail(email).id();
  }

  private void inviteEverybody() throws Exception {
    admin.get("/admin/calendar");
    admin.submitToAndFollow("/admin/calendar",
        Map.of("action", "invite", "id", Long.toString(eventId)));
  }

  /** a message the way a mail client would send it, through the real inbound path */
  private String deliver(String from, String ics) throws Exception {
    String body = "From: " + from + "\r\n"
        + "To: events@example.org\r\n"
        + "Subject: Accepted: Supper club\r\n"
        + "Authentication-Results: example.org; spf=pass; dkim=pass; dmarc=pass\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: multipart/alternative; boundary=\"xyz\"\r\n"
        + "\r\n"
        + "--xyz\r\n"
        + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
        + "Ana Rivera has accepted this invitation.\r\n"
        + "--xyz\r\n"
        + "Content-Type: text/calendar; charset=UTF-8; method=REPLY\r\n"
        + "Content-Transfer-Encoding: base64\r\n\r\n"
        + java.util.Base64.getMimeEncoder().encodeToString(ics.getBytes(StandardCharsets.UTF_8))
        + "\r\n--xyz--\r\n";
    Envelope envelope = new Envelope(from, List.of("events@example.org"),
        body.getBytes(StandardCharsets.UTF_8), "10.0.0.1", "mail.sender.example", "example.org",
        System.currentTimeMillis());
    io.hearth.smtp.MailReceiver receiver = new io.hearth.smtp.CommunityMailReceiver(
        server.auth, server.tree, null, Verbose.OFF);
    return receiver.receive(envelope).detail();
  }

  /** what a calendar client sends back when somebody presses a button */
  private String reply(String attendee, String partstat, int sequence, String uid) {
    return "BEGIN:VCALENDAR\r\n"
        + "PRODID:-//Test Client//EN\r\n"
        + "VERSION:2.0\r\n"
        + "METHOD:REPLY\r\n"
        + "BEGIN:VEVENT\r\n"
        + "UID:" + uid + "\r\n"
        + "SEQUENCE:" + sequence + "\r\n"
        + "ATTENDEE;PARTSTAT=" + partstat + ";CN=Somebody:mailto:" + attendee + "\r\n"
        + "ORGANIZER:mailto:events@example.org\r\n"
        + "END:VEVENT\r\n"
        + "END:VCALENDAR\r\n";
  }

  /** the event form, with whatever this test cares about on top of the required fields */
  private static Map<String, String> fields(String title, LocalDate day, String... extra) {
    java.util.LinkedHashMap<String, String> form = new java.util.LinkedHashMap<>();
    form.put("action", "save");
    form.put("title", title);
    form.put("body", "");
    form.put("location", "");
    form.put("place_id", "");
    form.put("starts_on", day.toString());
    form.put("ends_on", day.toString());
    form.put("start_time", "");
    form.put("capacity", "");
    for (int k = 0; k + 1 < extra.length; k += 2) {
      form.put(extra[k], extra[k + 1]);
    }
    return form;
  }

  private String uid() throws Exception {
    return calendar().byId(eventId).uid();
  }

  // ---- going out ---------------------------------------------------------------------------------

  @Test
  public void invitingEverybodySendsARealCalendarFile() throws Exception {
    inviteEverybody();
    Mailer.EventInvite invite = mail().lastEventInvite();
    assertNotNull("nothing was sent", invite);
    assertEquals("REQUEST", invite.method());
    assertEquals("answers have to come back somewhere this server receives",
        "events@example.org", invite.replyTo());

    String ics = invite.ics();
    assertTrue(ics, ics.startsWith("BEGIN:VCALENDAR\r\n"));
    // folding can split a line anywhere, including through the middle of a parameter, so anything
    // about content is asserted against the unfolded form -- which is what a client reads
    String flat = String.join("\n", Ics.unfold(ics));
    assertTrue(ics.contains("METHOD:REQUEST"));
    assertTrue("all-day, because a community event is a day rather than an instant",
        ics.contains("DTSTART;VALUE=DATE:"));
    assertTrue("and the end is exclusive, or every event is a day short",
        ics.contains("DTEND;VALUE=DATE:"
            + LocalDate.now().plusDays(11).toString().replace("-", "")));
    assertTrue(ics.contains("SUMMARY:Supper club"));
    assertTrue(ics.contains("LOCATION:The hall"));
    assertTrue("RSVP=TRUE is what draws the buttons", flat.contains("RSVP=TRUE"));
    assertTrue(flat.contains("ATTENDEE") && flat.contains("mailto:ana@example.com"));
    assertTrue("a reminder the day before is the point of a calendar entry",
        ics.contains("TRIGGER:-P1D"));
    assertTrue("and it has an identity that will still be this event in five years",
        uid().startsWith("hearth-" + eventId + "-") && uid().endsWith("@example.org"));
  }

  @Test
  public void announcingAnEventInvitesEverybodyWithoutASecondStep() throws Exception {
    // the ask was "creating an event sends the invitation", and it does -- with the box on the
    // form, so nobody is surprised, and off for a draft
    java.time.LocalDate day = LocalDate.now().plusDays(6);
    mail().clear();
    admin.get("/admin/calendar/new");
    admin.submitToAndFollow("/admin/calendar", fields("Bread day", day,
        "published", "on", "invite", "on"));
    assertNotNull("everybody was told", mail().lastEventInvite());
    assertEquals("Bread day", mail().lastEventInvite().title());

    // ...and a draft tells nobody, whatever the box says
    mail().clear();
    admin.get("/admin/calendar/new");
    admin.submitToAndFollow("/admin/calendar", fields("Still arguing", day, "invite", "on"));
    assertNull("an event nobody has agreed on is not in anybody's calendar",
        mail().lastEventInvite());
  }

  @Test
  public void everyLineFitsAndEverythingDangerousIsEscaped() throws Exception {
    LocalDate day = LocalDate.now().plusDays(4);
    admin.get("/admin/calendar/new");
    admin.submitTo("/admin/calendar", Map.of("action", "save",
        "title", "A very long name for an evening, with a comma; a semicolon and "
            + "quite a lot of words after it so that the line has to be folded somewhere",
        "body", "Line one\nLine two", "location", "The Oak, back room", "place_id", "",
        "starts_on", day.toString(), "ends_on", day.toString(), "start_time", "",
        "capacity", "", "published", "on"));
    long other = calendar().upcoming(LocalDate.now(), 5).stream()
        .filter(event -> event.title().startsWith("A very long")).findFirst().orElseThrow().id();
    admin.get("/admin/calendar");
    admin.submitToAndFollow("/admin/calendar",
        Map.of("action", "invite", "id", Long.toString(other)));

    String ics = mail().lastEventInvite().ics();
    for (String line : ics.split("\r\n")) {
      assertTrue("a line over 75 octets is a file a client may do anything with: " + line,
          line.getBytes(StandardCharsets.UTF_8).length <= 75);
    }
    assertTrue("a comma inside a value is structure unless it is escaped",
        ics.contains("\\, back room") || ics.contains("\\,"));
    assertTrue("and so is a newline", ics.contains("\\n"));
  }

  @Test
  public void aChangeGoesOutAsAnUpdateAndACancellationAsACancel() throws Exception {
    inviteEverybody();
    assertEquals(0, calendar().byId(eventId).sequence());

    admin.get("/admin/calendar");
    admin.submitToAndFollow("/admin/calendar",
        Map.of("action", "cancel", "id", Long.toString(eventId)));
    // cancelling from the admin marks it off; sending the cancellation is what reaches calendars
    io.hearth.calendar.Invitations invitations =
        new io.hearth.calendar.Invitations(server.mailer);
    invitations.cancel(server.tree.all().get("example.org"),
        server.auth.forDomain("example.org"), calendar().byId(eventId), true);

    Mailer.EventInvite last = mail().lastEventInvite();
    assertEquals("CANCEL", last.method());
    assertTrue(last.cancelled());
    assertTrue(last.ics().contains("STATUS:CANCELLED"));
    assertEquals("a cancellation with the same sequence would be ignored by every client",
        1, calendar().byId(eventId).sequence());
    assertEquals("the uid never moves, or the cancellation cancels nothing",
        uid(), io.hearth.calendar.Ics.read(last.ics()) == null ? uid() : uid());
  }

  @Test
  public void nothingIsSentWhenAnAnswerCouldNotComeBack() throws Exception {
    io.hearth.calendar.Invitations invitations =
        new io.hearth.calendar.Invitations(server.mailer);
    io.hearth.calendar.Invitations.Sent sent = invitations.invite(
        server.tree.all().get("example.org"), server.auth.forDomain("example.org"),
        calendar().byId(eventId), false);
    assertFalse(sent.anything());
    assertTrue(sent.detail(), sent.detail().contains("not receiving mail"));
  }

  // ---- coming back -------------------------------------------------------------------------------

  @Test
  public void anAcceptFromSomebodysCalendarBecomesAnRsvp() throws Exception {
    inviteEverybody();
    String detail = deliver("Ana Rivera <ana@example.com>",
        reply("ana@example.com", "ACCEPTED", 0, uid()));
    assertTrue(detail, detail.contains("going"));

    Calendar.Rsvp rsvp = calendar().rsvpFor(eventId, idOf("ana@example.com"));
    assertNotNull("the answer landed", rsvp);
    assertEquals(Calendar.Answer.going, rsvp.answer());
    assertTrue("and it knows it came from a calendar rather than a button", rsvp.fromEmail());
    assertEquals(1, calendar().byId(eventId).goingCount());

    // ...and it is on the page everybody reads, by name
    Browser.Page page = admin.get("/events/" + eventId);
    assertTrue(page.contains("Ana Rivera"));
    assertTrue(page.contains("from their calendar"));
    assertFalse("never their address", page.contains("ana@example.com"));
  }

  @Test
  public void tentativeAndDeclinedArriveAsThemselves() throws Exception {
    inviteEverybody();
    deliver("ana@example.com", reply("ana@example.com", "TENTATIVE", 0, uid()));
    assertEquals(Calendar.Answer.maybe,
        calendar().rsvpFor(eventId, idOf("ana@example.com")).answer());

    deliver("ana@example.com", reply("ana@example.com", "DECLINED", 0, uid()));
    assertEquals(Calendar.Answer.no,
        calendar().rsvpFor(eventId, idOf("ana@example.com")).answer());
    assertEquals("a no is an answer, not a seat", 0, calendar().byId(eventId).goingCount());
  }

  @Test
  public void aReplyForSomebodyElseIsRefused() throws Exception {
    // the one that matters: without this, anybody who can send an email can accept on anybody's
    // behalf, and the guest list is fiction
    inviteEverybody();
    String detail = deliver("boss@example.com", reply("ana@example.com", "ACCEPTED", 0, uid()));
    assertTrue(detail, detail.contains("received") || detail.contains("printed"));
    assertNull(calendar().rsvpFor(eventId, idOf("ana@example.com")));
    assertNull(calendar().rsvpFor(eventId, idOf("boss@example.com")));
  }

  @Test
  public void aReplyFromSomebodyWhoIsNotAMemberIsRefused() throws Exception {
    inviteEverybody();
    deliver("stranger@elsewhere.example",
        reply("stranger@elsewhere.example", "ACCEPTED", 0, uid()));
    assertEquals("nobody was added", 0, calendar().guestList(eventId).size());
  }

  @Test
  public void aReplyThatFailedAuthenticationIsNotBelieved() throws Exception {
    inviteEverybody();
    String ics = reply("ana@example.com", "ACCEPTED", 0, uid());
    String body = "From: ana@example.com\r\n"
        + "To: events@example.org\r\n"
        + "Authentication-Results: example.org; spf=fail; dkim=fail; dmarc=fail\r\n"
        + "Content-Type: text/calendar; method=REPLY\r\n\r\n" + ics;
    Envelope envelope = new Envelope("ana@example.com", List.of("events@example.org"),
        body.getBytes(StandardCharsets.UTF_8), "10.0.0.1", "mail.sender.example", "example.org",
        System.currentTimeMillis());
    new io.hearth.smtp.CommunityMailReceiver(server.auth, server.tree, null, Verbose.OFF)
        .receive(envelope);
    assertNull("a reply is a claim about identity, and this one failed every check",
        calendar().rsvpFor(eventId, idOf("ana@example.com")));
  }

  @Test
  public void anAnswerToAnOlderVersionOfTheEventIsRefused() throws Exception {
    inviteEverybody();
    calendar().bumpSequence(eventId, null);
    deliver("ana@example.com", reply("ana@example.com", "ACCEPTED", 0, uid()));
    assertNull("that answer was about a day that no longer exists",
        calendar().rsvpFor(eventId, idOf("ana@example.com")));
  }

  @Test
  public void anAnswerToAnEventThatIsNotHereIsIgnored() throws Exception {
    inviteEverybody();
    deliver("ana@example.com",
        reply("ana@example.com", "ACCEPTED", 0, "somebody-elses-event@elsewhere.example"));
    assertEquals(0, calendar().guestList(eventId).size());
  }

  @Test
  public void aCounterProposalIsRecordedAndNeverApplied() throws Exception {
    inviteEverybody();
    LocalDate wanted = LocalDate.now().plusDays(14);
    String counter = "BEGIN:VCALENDAR\r\nMETHOD:COUNTER\r\nBEGIN:VEVENT\r\n"
        + "UID:" + uid() + "\r\nSEQUENCE:0\r\n"
        + "DTSTART;VALUE=DATE:" + wanted.toString().replace("-", "") + "\r\n"
        + "ATTENDEE;PARTSTAT=TENTATIVE:mailto:ana@example.com\r\n"
        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    LocalDate before = calendar().byId(eventId).startsOn();
    deliver("ana@example.com", counter);

    Calendar.Rsvp rsvp = calendar().rsvpFor(eventId, idOf("ana@example.com"));
    assertNotNull(rsvp);
    assertEquals("a suggestion is a maybe until somebody decides", Calendar.Answer.maybe,
        rsvp.answer());
    assertEquals(wanted, rsvp.proposedOn());
    assertEquals("the event has not moved, and must not have", before,
        calendar().byId(eventId).startsOn());

    // ...and the organiser can take it, which is a reschedule like any other
    admin.get("/admin/calendar");
    admin.submitToAndFollow("/admin/calendar", Map.of("action", "take_proposal",
        "id", Long.toString(eventId), "user", Long.toString(idOf("ana@example.com"))));
    assertEquals(wanted, calendar().byId(eventId).startsOn());
    assertEquals("everybody's calendar needs to hear about it", 1,
        calendar().byId(eventId).sequence());
  }

  @Test
  public void anAnswerKeepsThePartySizeSomebodySetHere() throws Exception {
    inviteEverybody();
    Browser ana = new Browser(server.port, "example.org");
    ana.get("/register");
    ana.submit(Map.of("email", "ana@example.com"));
    ana.submit(Map.of("code", server.mail().lastCodeFor("ana@example.com")));
    ana.get("/events/" + eventId);
    ana.submitTo("/events", Map.of("action", "rsvp", "event", Long.toString(eventId),
        "answer", "going", "party", "3", "note", "bringing two"));

    deliver("ana@example.com", reply("ana@example.com", "TENTATIVE", 0, uid()));
    Calendar.Rsvp rsvp = calendar().rsvpFor(eventId, idOf("ana@example.com"));
    assertEquals(Calendar.Answer.maybe, rsvp.answer());
    assertEquals("a calendar client knows nothing about guests, so it must not reset them",
        3, rsvp.party());
    assertEquals("bringing two", rsvp.note());
  }

  // ---- an invitation coming the other way ----------------------------------------------------------

  /** an invitation the way somebody's calendar sends one when they add us as a guest */
  private String invitation(String uid, String title, LocalDate day, String location) {
    return "BEGIN:VCALENDAR\r\nPRODID:-//Their Client//EN\r\nVERSION:2.0\r\n"
        + "METHOD:REQUEST\r\nBEGIN:VEVENT\r\nUID:" + uid + "\r\nSEQUENCE:0\r\n"
        + "SUMMARY:" + title + "\r\n"
        + "DTSTART;VALUE=DATE:" + day.toString().replace("-", "") + "\r\n"
        + "DTEND;VALUE=DATE:" + day.plusDays(1).toString().replace("-", "") + "\r\n"
        + (location == null ? "" : "LOCATION:" + location + "\r\n")
        + "DESCRIPTION:Bring a chair.\r\n"
        + "ORGANIZER:mailto:ana@example.com\r\n"
        + "ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:events@example.org\r\n"
        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
  }

  @Test
  public void somebodyCanMailInAnEventAndItLandsOnTheCalendar() throws Exception {
    LocalDate day = LocalDate.now().plusDays(20);
    deliver("boss@example.com", invitation("theirs-1@their.example", "Bread day", day, "The Oak"));

    Calendar.Event made = calendar().all(50).stream()
        .filter(event -> event.title().equals("Bread day")).findFirst().orElse(null);
    assertNotNull("an invitation somebody sent us became an event", made);
    assertEquals(day, made.startsOn());
    assertEquals("an all-day DTEND is the morning after, and storing it verbatim makes every"
        + " mailed-in event a day long", day, made.endsOn());
    assertEquals("The Oak", made.location());
    assertTrue(made.body().contains("Bring a chair."));
    assertTrue("published, because somebody who can keep the calendar sent it", made.published());
    assertEquals("and it keeps the uid it arrived with, so their next update finds it",
        "theirs-1@their.example", made.uid());
  }

  @Test
  public void thesameInvitationTwiceIsOneEvent() throws Exception {
    LocalDate day = LocalDate.now().plusDays(20);
    deliver("boss@example.com", invitation("theirs-2@their.example", "Bread day", day, ""));
    deliver("boss@example.com",
        invitation("theirs-2@their.example", "Bread day (moved)", day.plusDays(1), ""));

    assertEquals("one uid, one event", 1, calendar().all(50).stream()
        .filter(event -> event.title().startsWith("Bread day")).count());
    Calendar.Event made = calendar().byUid("theirs-2@their.example");
    assertEquals("Bread day (moved)", made.title());
    assertEquals(day.plusDays(1), made.startsOn());
    assertEquals("and everybody's calendar needs to hear about the change", 1, made.sequence());
  }

  @Test
  public void anOrdinaryMemberMailingOneInGetsASuggestion() throws Exception {
    LocalDate day = LocalDate.now().plusDays(20);
    deliver("ana@example.com", invitation("theirs-3@their.example", "A walk", day, ""));
    Calendar.Event made = calendar().byUid("theirs-3@their.example");
    assertNotNull(made);
    assertTrue("the same thing they would get from the site", made.suggested());
    assertFalse(made.published());
  }

  @Test
  public void somebodyWhoIsNotAMemberCannotPutAnythingOnTheCalendar() throws Exception {
    LocalDate day = LocalDate.now().plusDays(20);
    deliver("stranger@elsewhere.example",
        invitation("theirs-4@their.example", "Nonsense", day, ""));
    assertNull(calendar().byUid("theirs-4@their.example"));
  }

  @Test
  public void aLocationThatIsAlreadyInTheAddressBookIsNotWrittenDownTwice() throws Exception {
    io.hearth.places.Places places = server.auth.forDomain("example.org").places;
    places.save(new io.hearth.places.Places.Place(0, io.hearth.places.Places.DEFAULT_TYPE,
        "the-oak", "The Oak", "High Street, Ashford", "", "", "", "", null, null, "", "", "",
        "{}", "", true, false, null, null, null), null);
    int before = places.all(50).size();

    LocalDate day = LocalDate.now().plusDays(20);
    deliver("boss@example.com", invitation("theirs-5@their.example", "Pub night", day, "the oak "));

    Calendar.Event made = calendar().byUid("theirs-5@their.example");
    assertNotNull(made.placeId());
    assertEquals("The Oak", places.byId(made.placeId()).name());
    assertEquals("an address book that gains a second Oak every time is one nobody trusts",
        before, places.all(50).size());
  }

  @Test
  public void aLocationNobodyHasWrittenDownBecomesADraftPlace() throws Exception {
    io.hearth.places.Places places = server.auth.forDomain("example.org").places;
    LocalDate day = LocalDate.now().plusDays(20);
    deliver("boss@example.com",
        invitation("theirs-6@their.example", "Somewhere new", day, "The Anchor"));

    Calendar.Event made = calendar().byUid("theirs-6@their.example");
    assertNotNull(made.placeId());
    io.hearth.places.Places.Place place = places.byId(made.placeId());
    assertEquals("The Anchor", place.name());
    assertFalse("a place a machine made from one line of an email is a draft", place.published());
  }

  // ---- chasing the quiet ones ---------------------------------------------------------------------

  @Test
  public void nobodyWhoAnsweredIsChasedAndEverybodySilentIs() throws Exception {
    io.hearth.calendar.Invitations invitations =
        new io.hearth.calendar.Invitations(server.mailer);
    inviteEverybody();
    mail().clear();

    // ana answers; the boss does not
    deliver("ana@example.com", reply("ana@example.com", "DECLINED", 0, uid()));
    mail().clear();

    LocalDate sevenDaysBefore = calendar().byId(eventId).startsOn().minusDays(7);
    io.hearth.calendar.Invitations.Sent chased = invitations.remind(
        server.tree.all().get("example.org"), server.auth.forDomain("example.org"),
        sevenDaysBefore, true);
    assertEquals("only the person who said nothing", 1, chased.invited());
    assertTrue(mail().lastEventInvite().body(), mail().lastEventInvite().body()
        .contains("in 7 days"));

    // and on a day that is not one of the reminder days, nobody is bothered
    mail().clear();
    assertEquals(0, invitations.remind(server.tree.all().get("example.org"),
        server.auth.forDomain("example.org"),
        calendar().byId(eventId).startsOn().minusDays(5), true).invited());
  }

  @Test
  public void whoHasNotAnsweredIsOnThePageForSomebodyToAskInPerson() throws Exception {
    inviteEverybody();
    Browser.Page page = admin.get("/events/" + eventId);
    assertTrue(page.contains("Not heard from"));
    assertTrue(page.contains("Ana Rivera"));
  }

  // ---- moving one ----------------------------------------------------------------------------------

  @Test
  public void takingSomebodysSuggestedDayMovesItAndAsksEverybodyAgain() throws Exception {
    inviteEverybody();
    // ana says yes, the boss says no, and ana's calendar suggests another day
    calendar().answer(eventId, idOf("ana@example.com"), "ana@example.com",
        Calendar.Answer.going, 1, "");
    calendar().answer(eventId, idOf("boss@example.com"), "boss@example.com",
        Calendar.Answer.no, 1, "");
    LocalDate wanted = calendar().byId(eventId).startsOn().plusDays(3);
    calendar().propose(eventId, idOf("ana@example.com"), wanted, "");

    Browser.Page page = admin.get("/events/" + eventId);
    assertTrue("the suggestion is on the page for somebody to act on",
        page.contains("would rather it were another day"));

    admin.submitTo("/events", Map.of("action", "take_proposal", "event", Long.toString(eventId),
        "user", Long.toString(idOf("ana@example.com"))));

    assertEquals("it moved", wanted, calendar().byId(eventId).startsOn());
    assertNull("and the people who agreed to the old day are asked again",
        calendar().rsvpFor(eventId, idOf("ana@example.com")));
    assertNotNull("but a no is probably still true, so it is left alone",
        calendar().rsvpFor(eventId, idOf("boss@example.com")));
    assertEquals("everybody's calendar is holding a day that no longer exists", 1,
        calendar().byId(eventId).sequence());
  }

  @Test
  public void movingItAndKeepingTheAnswersIsTheOtherChoice() throws Exception {
    calendar().answer(eventId, idOf("ana@example.com"), "ana@example.com",
        Calendar.Answer.going, 2, "bringing my brother");
    LocalDate wanted = calendar().byId(eventId).startsOn().plusDays(1);
    calendar().propose(eventId, idOf("ana@example.com"), wanted, "");

    admin.get("/events/" + eventId);
    admin.submitTo("/events", Map.of("action", "take_proposal", "event", Long.toString(eventId),
        "user", Long.toString(idOf("ana@example.com")), "keep_answers", "on"));

    assertEquals(wanted, calendar().byId(eventId).startsOn());
    Calendar.Rsvp kept = calendar().rsvpFor(eventId, idOf("ana@example.com"));
    assertNotNull("a day either way is not worth asking two hundred people again", kept);
    assertEquals(2, kept.party());
    assertNull("and the suggestion is dealt with", kept.proposedOn());
  }

  @Test
  public void onlySomebodyWhoKeepsTheCalendarCanMoveIt() throws Exception {
    LocalDate wanted = calendar().byId(eventId).startsOn().plusDays(3);
    calendar().propose(eventId, idOf("ana@example.com"), wanted, "");
    LocalDate before = calendar().byId(eventId).startsOn();

    Browser ana = new Browser(server.port, "example.org");
    ana.get("/register");
    ana.submit(Map.of("email", "ana@example.com"));
    ana.submit(Map.of("code", server.mail().lastCodeFor("ana@example.com")));
    Browser.Page page = ana.get("/events/" + eventId);
    assertFalse("no button for somebody who cannot", page.contains("move it to this day"));
    ana.submitTo("/events", Map.of("action", "take_proposal", "event", Long.toString(eventId),
        "user", Long.toString(idOf("ana@example.com"))));
    assertEquals("and posting it by hand does nothing", before,
        calendar().byId(eventId).startsOn());
  }

  // ---- afterwards ---  // ---- afterwards ----------------------------------------------------------------------------------

  @Test
  public void somebodyWhoSaidYesAndWasNotThereCanBeNoted() throws Exception {
    // an event in the past, so the question can be asked at all
    LocalDate past = LocalDate.now().minusDays(2);
    admin.get("/admin/calendar/new");
    admin.submitTo("/admin/calendar", Map.of("action", "save", "title", "Last week",
        "body", "", "location", "", "place_id", "", "starts_on", past.toString(),
        "ends_on", past.toString(), "start_time", "", "capacity", "", "published", "on"));
    long over = calendar().all(50).stream()
        .filter(event -> event.title().equals("Last week")).findFirst().orElseThrow().id();
    calendar().answer(over, idOf("ana@example.com"), "ana@example.com",
        Calendar.Answer.going, 1, "");

    Browser.Page page = admin.get("/events/" + over);
    assertTrue("the question is only worth asking once it has happened",
        page.contains("was not there"));
    admin.submitTo("/events", Map.of("action", "no_show", "event", Long.toString(over),
        "user", Long.toString(idOf("ana@example.com"))));
    assertTrue(calendar().rsvpFor(over, idOf("ana@example.com")).noShow());

    Browser ana = new Browser(server.port, "example.org");
    ana.get("/register");
    ana.submit(Map.of("email", "ana@example.com"));
    ana.submit(Map.of("code", server.mail().lastCodeFor("ana@example.com")));
    ana.submitTo("/events", Map.of("action", "no_show", "event", Long.toString(over),
        "user", Long.toString(idOf("boss@example.com"))));
    assertFalse("and it is not something a member can do to somebody else",
        calendar().rsvpFor(over, idOf("boss@example.com")) != null
            && calendar().rsvpFor(over, idOf("boss@example.com")).noShow());
  }
}
