package io.hearth.calendar;

import io.hearth.auth.Accounts;
import io.hearth.common.Verbose;
import io.hearth.inbox.Delivery;
import io.hearth.inbox.PushOnArrival;
import io.hearth.smtp.AuthResult;
import io.hearth.smtp.Envelope;
import io.hearth.smtp.Mailboxes;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A calendar somebody keeps, and the files that go in and out of it.
 *
 * <b>Round-tripping is the property everything else rests on.</b> What is written here is read by
 * Apple Calendar and Google Calendar; what is read here was written by one of them. So the tests
 * that matter are the ones about escaping, folding, timezones and the exclusive end of an all-day
 * event -- each of which is a place where being one out means an appointment on the wrong day.
 */
public class CalendarTests {
  private static final String DOMAIN = "ranch.example.org";
  private static final ZoneId UTC = ZoneOffset.UTC;

  private Configs configs;
  private TestServer server;
  private long me;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain(DOMAIN,
        "{\"name\":\"Ranch\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    me = accounts().users.create("boss@example.com", null, true, null).id();
    accounts().users.approve(me, null);
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

  private Accounts accounts() {
    return server.auth.forDomain(DOMAIN);
  }

  private static final String INVITE =
      "BEGIN:VCALENDAR\r\n"
          + "VERSION:2.0\r\n"
          + "METHOD:REQUEST\r\n"
          + "BEGIN:VEVENT\r\n"
          + "UID:meeting-1@example.com\r\n"
          + "SEQUENCE:0\r\n"
          + "DTSTART:20261008T180000Z\r\n"
          + "DTEND:20261008T190000Z\r\n"
          + "SUMMARY:Board games\r\n"
          + "LOCATION:Ana's place\r\n"
          + "DESCRIPTION:Bring something\\, anything\r\n"
          + "ORGANIZER:mailto:ana@elsewhere.example\r\n"
          + "ATTENDEE;CN=\"Jeff\";PARTSTAT=NEEDS-ACTION:mailto:jeff@" + DOMAIN + "\r\n"
          + "END:VEVENT\r\n"
          + "END:VCALENDAR\r\n";

  // ---- reading -----------------------------------------------------------------------------------

  @Test
  public void anInvitationIsReadWithEverythingOnIt() {
    IcsFile.Calendar calendar = IcsFile.read(INVITE, UTC);
    assertTrue(calendar.isRequest());
    assertEquals(1, calendar.events().size());
    IcsFile.Event event = calendar.events().get(0);
    assertEquals("meeting-1@example.com", event.uid());
    assertEquals("Board games", event.summary());
    assertEquals("Ana's place", event.location());
    assertEquals("an escaped comma is a comma", "Bring something, anything", event.description());
    assertEquals("ana@elsewhere.example", event.organizer());
    assertEquals(1, event.attendees().size());
    assertEquals("jeff@" + DOMAIN, event.attendees().get(0).address());
    assertEquals("Jeff", event.attendees().get(0).name());
    assertEquals(3_600_000L, event.endsAt() - event.startsAt());
  }

  /**
   * A VALARM inside a VEVENT has its own SUMMARY and DESCRIPTION.
   *
   * Reading them as part of the event is how an appointment ends up called "Reminder".
   */
  @Test
  public void anAlarmInsideAnEventIsNotReadAsTheEvent() {
    String withAlarm = INVITE.replace("END:VEVENT",
        "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Reminder\r\nSUMMARY:Alarm\r\n"
            + "TRIGGER:-PT15M\r\nEND:VALARM\r\nEND:VEVENT");
    IcsFile.Event event = IcsFile.read(withAlarm, UTC).events().get(0);
    assertEquals("Board games", event.summary());
    assertEquals("Bring something, anything", event.description());
  }

  @Test
  public void aQuotedColonInAParameterDoesNotSplitTheLine() {
    // ATTENDEE;CN="Smith: J":mailto:... is legal, and splitting on the first colon gives a name of
    // `ATTENDEE;CN="Smith` and a value that is the rest of somebody's name
    String ics = INVITE.replace("CN=\"Jeff\"", "CN=\"Smith: John\"");
    IcsFile.Event event = IcsFile.read(ics, UTC).events().get(0);
    assertEquals("Smith: John", event.attendees().get(0).name());
    assertEquals("jeff@" + DOMAIN, event.attendees().get(0).address());
  }

  @Test
  public void aFloatingTimeIsReadInTheCalendarsOwnZoneRatherThanUtc() {
    // reading a bare DATE-TIME as UTC moves somebody's nine o'clock by however many hours they are
    // from Greenwich
    ZoneId chicago = ZoneId.of("America/Chicago");
    Long floating = IcsFile.when("20261008T090000", null, chicago);
    Long asUtc = IcsFile.when("20261008T090000Z", null, chicago);
    assertNotNull(floating);
    assertFalse("they are not the same instant", floating.equals(asUtc));
    assertEquals(9, java.time.Instant.ofEpochMilli(floating).atZone(chicago).getHour());
  }

  @Test
  public void aTzidIsHonouredAndAnUnknownOneFallsBack() {
    ZoneId chicago = ZoneId.of("America/Chicago");
    Long known = IcsFile.when("20261008T090000", "America/Chicago", UTC);
    assertEquals(9, java.time.Instant.ofEpochMilli(known).atZone(chicago).getHour());
    // Windows zone names and a long tail of typos; falling back puts the event within a few hours
    // rather than nowhere
    assertNotNull(IcsFile.when("20261008T090000", "Central Standard Time", UTC));
  }

  @Test
  public void aDurationIsUsedWhenThereIsNoEnd() {
    String ics = INVITE.replace("DTEND:20261008T190000Z", "DURATION:PT90M");
    IcsFile.Event event = IcsFile.read(ics, UTC).events().get(0);
    assertEquals(90 * 60_000L, event.endsAt() - event.startsAt());
  }

  @Test
  public void anAllDayEventWithNoEndIsOneWholeDay() {
    String ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\n"
        + "DTSTART;VALUE=DATE:20261008\r\nSUMMARY:A day off\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
    IcsFile.Event event = IcsFile.read(ics, UTC).events().get(0);
    assertTrue(event.allDay());
    assertEquals(86_400_000L, event.endsAt() - event.startsAt());
  }

  @Test
  public void anEventWithNoStartIsSkippedRatherThanInvented() {
    String ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nSUMMARY:no time\r\n"
        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    assertTrue(IcsFile.read(ics, UTC).events().isEmpty());
  }

  @Test
  public void nothingInAMalformedFileThrows() {
    for (String broken : new String[]{"", "BEGIN:VCALENDAR", "nonsense",
        "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART:next tuesday\r\nEND:VCALENDAR"}) {
      assertNotNull(broken, IcsFile.read(broken, UTC));
    }
  }

  @Test
  public void escapingIsUndoneInOnePassSoABackslashNIsNotANewline() {
    // replacing \\ last turns a literal backslash followed by an n into a newline
    assertEquals("a\\n b", IcsFile.unescape("a\\\\n b"));
    assertEquals("a\nb", IcsFile.unescape("a\\nb"));
    assertEquals("a;b,c", IcsFile.unescape("a\\;b\\,c"));
  }

  // ---- writing -----------------------------------------------------------------------------------

  @Test
  public void whatIsWrittenIsReadBackTheSame() {
    IcsFile.Event original = IcsFile.read(INVITE, UTC).events().get(0);
    String written = IcsFile.write(null, List.of(original), "Ranch");
    IcsFile.Event again = IcsFile.read(written, UTC).events().get(0);

    assertEquals(original.uid(), again.uid());
    assertEquals(original.summary(), again.summary());
    assertEquals("a comma survives being written and read", original.description(),
        again.description());
    assertEquals(original.location(), again.location());
    assertEquals(original.startsAt(), again.startsAt());
    assertEquals(original.endsAt(), again.endsAt());
    assertEquals(original.organizer(), again.organizer());
    assertEquals(original.attendees().size(), again.attendees().size());
  }

  /**
   * A fold placed by character index lands in the middle of a multi-byte character.
   *
   * What comes out the other side is a summary with a replacement character in it, which is the
   * single most common bug in a hand-written calendar writer.
   */
  @Test
  public void foldingCountsOctetsRatherThanCharacters() {
    String summary = "☕".repeat(60);
    IcsFile.Event event = new IcsFile.Event("x@y", 0, summary, "", "", 1000, 2000, false, "", "",
        "CONFIRMED", "", List.of());
    String written = IcsFile.write(null, List.of(event), null);
    for (String line : written.split("\r\n")) {
      assertTrue("line of " + line.getBytes(StandardCharsets.UTF_8).length + " octets",
          line.getBytes(StandardCharsets.UTF_8).length <= 76);
    }
    assertEquals("and nothing was cut in half",
        summary, IcsFile.read(written, UTC).events().get(0).summary());
  }

  /**
   * An all-day event's DTEND is the day after it ends.
   *
   * It is exclusive, it catches everybody out, and writing the same day makes a one-day event
   * vanish in half the clients that read it.
   */
  @Test
  public void anAllDayEventIsWrittenWithAnExclusiveEnd() {
    long start = java.time.LocalDate.of(2026, 10, 8).atStartOfDay(UTC).toInstant().toEpochMilli();
    IcsFile.Event event = new IcsFile.Event("x@y", 0, "A day off", "", "", start,
        start + 86_400_000L, true, "", "", "CONFIRMED", "", List.of());
    String written = IcsFile.write(null, List.of(event), null);
    assertTrue(written, written.contains("DTSTART;VALUE=DATE:20261008"));
    assertTrue(written, written.contains("DTEND;VALUE=DATE:20261009"));
  }

  /**
   * A REPLY carries exactly one attendee: the person answering.
   *
   * The organizer's software takes a REPLY as authoritative, so sending the whole list back would
   * reset what everybody else had said.
   */
  @Test
  public void aReplyCarriesOnlyThePersonAnswering() {
    IcsFile.Event event = IcsFile.read(INVITE, UTC).events().get(0);
    String reply = IcsFile.reply(event, "jeff@" + DOMAIN, "Jeff", "ACCEPTED");
    assertTrue(reply, reply.contains("METHOD:REPLY"));
    assertTrue(reply, reply.contains("PARTSTAT=ACCEPTED"));
    assertEquals("one ATTENDEE line and no more", 1,
        reply.split("ATTENDEE", -1).length - 1);
    assertTrue("and the same UID, or the organizer cannot match it", reply.contains(event.uid()));
  }

  // ---- the calendar itself -------------------------------------------------------------------------

  @Test
  public void anEventIsAddedAndThenUpdatedInPlaceByItsUid() throws Exception {
    IcsFile.Event event = IcsFile.read(INVITE, UTC).events().get(0);
    assertEquals(Events.Outcome.added,
        accounts().events.merge(me, event, "invitation", null, null));
    assertEquals(1, accounts().events.count(me));

    IcsFile.Event moved = new IcsFile.Event(event.uid(), 1, "Board games", "", "Bo's place",
        event.startsAt() + 86_400_000L, event.endsAt() + 86_400_000L, false, "", "", "CONFIRMED",
        event.organizer(), event.attendees());
    assertEquals(Events.Outcome.updated,
        accounts().events.merge(me, moved, "invitation", null, null));
    assertEquals("one event, not two", 1, accounts().events.count(me));
    assertEquals("Bo's place", accounts().events.byUid(me, event.uid()).location());
  }

  /**
   * A lower sequence arriving later is a stale copy and is ignored.
   *
   * Mail is not ordered: a REQUEST and its correction can arrive in either order, and taking the
   * most recent delivery as the truth means a message delayed twenty minutes undoes a change
   * everybody has already seen.
   */
  @Test
  public void anOlderRevisionArrivingLaterIsIgnored() throws Exception {
    IcsFile.Event event = IcsFile.read(INVITE, UTC).events().get(0);
    IcsFile.Event newer = new IcsFile.Event(event.uid(), 5, "Moved", "", "", event.startsAt(),
        event.endsAt(), false, "", "", "CONFIRMED", event.organizer(), event.attendees());
    accounts().events.merge(me, newer, "invitation", null, null);
    assertEquals(Events.Outcome.ignoredAsStale,
        accounts().events.merge(me, event, "invitation", null, null));
    assertEquals("Moved", accounts().events.byUid(me, event.uid()).summary());
  }

  /**
   * An organizer's update does not un-accept a meeting somebody accepted.
   *
   * Their REQUEST names everybody's PARTSTAT as they last heard it, which for a change of room is
   * out of date the moment it is sent.
   */
  @Test
  public void anUpdateFromTheOrganizerKeepsWhatThisPersonAlreadySaid() throws Exception {
    IcsFile.Event event = IcsFile.read(INVITE, UTC).events().get(0);
    accounts().events.merge(me, event, "invitation", null, null);
    accounts().events.answer(me, event.uid(), "ACCEPTED");

    IcsFile.Event update = new IcsFile.Event(event.uid(), 1, "Board games", "", "A new room",
        event.startsAt(), event.endsAt(), false, "", "", "CONFIRMED", event.organizer(),
        event.attendees());
    accounts().events.merge(me, update, "invitation", null, null);
    assertEquals("ACCEPTED", accounts().events.byUid(me, event.uid()).myAnswer());
  }

  /**
   * A cancellation keeps the row with its status rather than deleting it.
   *
   * A meeting that silently vanishes from a morning somebody planned around reads as "that never
   * existed" rather than "that was called off", and the second is what they need to know.
   */
  @Test
  public void aCancellationIsRecordedRatherThanDeleted() throws Exception {
    IcsFile.Event event = IcsFile.read(INVITE, UTC).events().get(0);
    accounts().events.merge(me, event, "invitation", null, null);
    IcsFile.Event cancelled = new IcsFile.Event(event.uid(), 1, event.summary(), "", "",
        event.startsAt(), event.endsAt(), false, "", "", "CANCELLED", event.organizer(),
        event.attendees());
    assertEquals(Events.Outcome.cancelled,
        accounts().events.merge(me, cancelled, "invitation", null, null));
    assertTrue(accounts().events.byUid(me, event.uid()).cancelled());
    assertEquals("and it is still there", 1, accounts().events.count(me));
  }

  @Test
  public void onlyUnansweredInvitationsAreNaggedAbout() throws Exception {
    IcsFile.Event event = IcsFile.read(INVITE, UTC).events().get(0);
    long future = System.currentTimeMillis() + 7 * 86_400_000L;
    IcsFile.Event soon = new IcsFile.Event(event.uid(), 0, event.summary(), "", "", future,
        future + 3_600_000L, false, "", "", "CONFIRMED", event.organizer(), event.attendees());
    accounts().events.merge(me, soon, "invitation", null, null);
    assertEquals(1, accounts().events.unanswered(me).size());

    accounts().events.answer(me, event.uid(), "DECLINED");
    assertEquals(0, accounts().events.unanswered(me).size());
  }

  @Test
  public void somethingTypedHereIsNotAnInvitationAndIsNeverNaggedAbout() throws Exception {
    long future = System.currentTimeMillis() + 86_400_000L;
    accounts().events.merge(me, new IcsFile.Event("mine@x", 0, "Gym", "", "", future,
        future + 3_600_000L, false, "", "", "CONFIRMED", "", List.of()), "typed", null, null);
    assertEquals("nobody invited you to your own event", 0, accounts().events.unanswered(me).size());
  }

  // ---- repeats -------------------------------------------------------------------------------------

  /**
   * A repeat is fetched however old its start, because a weekly meeting set up two years ago has a
   * start far outside any window somebody is looking at.
   */
  @Test
  public void aRepeatingEventAppearsInAWindowItsStartIsNowhereNear() throws Exception {
    long longAgo = System.currentTimeMillis() - 400L * 86_400_000L;
    accounts().events.merge(me, new IcsFile.Event("weekly@x", 0, "Standup", "", "", longAgo,
        longAgo + 1_800_000L, false, "FREQ=WEEKLY", "", "CONFIRMED", "", List.of()), "typed",
        null, null);

    long now = System.currentTimeMillis();
    List<CalendarRoutes.Occurrence> soon = CalendarRoutes.occurrencesBetween(accounts(), me, now,
        now + 21L * 86_400_000L, accounts().zone());
    assertTrue("a standup from last year is still on next week", soon.size() >= 2);
    for (CalendarRoutes.Occurrence occurrence : soon) {
      assertTrue(occurrence.start() >= now);
    }
  }

  @Test
  public void aCancelledEventIsNotDrawnOnTheAgendaAndIsStillInTheFeed() throws Exception {
    long future = System.currentTimeMillis() + 86_400_000L;
    accounts().events.merge(me, new IcsFile.Event("off@x", 1, "Called off", "", "", future,
        future + 3_600_000L, false, "", "", "CANCELLED", "ana@elsewhere.example", List.of()),
        "invitation", null, null);
    long now = System.currentTimeMillis();
    assertTrue(CalendarRoutes.occurrencesBetween(accounts(), me, now, now + 7L * 86_400_000L,
        accounts().zone()).isEmpty());
    assertTrue("a subscriber needs to be told it was called off",
        accounts().events.feed(me, "Ranch", accounts().zone()).contains("STATUS:CANCELLED"));
  }

  // ---- the feed ------------------------------------------------------------------------------------

  /**
   * The token is stored as a hash, like a session token.
   *
   * The URL goes into a phone's settings and stays there for years, so a stolen database file must
   * not be a list of working calendar subscriptions.
   */
  @Test
  public void theFeedTokenIsNeverStoredInTheFormItIsHandedOut() throws Exception {
    String token = accounts().events.mintFeedToken(me);
    assertEquals(Long.valueOf(me), accounts().events.userForFeed(token));
    try (java.sql.Connection connection = accounts().store.connection();
         java.sql.PreparedStatement statement = connection.prepareStatement(
             "SELECT token_hash FROM calendar_feeds WHERE user_id = ?")) {
      statement.setLong(1, me);
      try (java.sql.ResultSet found = statement.executeQuery()) {
        assertTrue(found.next());
        assertFalse("the token itself is nowhere on disk",
            token.equals(found.getString("token_hash")));
      }
    }
  }

  @Test
  public void mintingASecondTokenStopsTheFirstOneWorking() throws Exception {
    String first = accounts().events.mintFeedToken(me);
    String second = accounts().events.mintFeedToken(me);
    assertNull("revoking is minting a new one", accounts().events.userForFeed(first));
    assertEquals(Long.valueOf(me), accounts().events.userForFeed(second));

    accounts().events.revokeFeed(me);
    assertNull(accounts().events.userForFeed(second));
    assertFalse(accounts().events.hasFeed(me));
  }

  @Test
  public void aWrongTokenIsNobody() throws Exception {
    assertNull(accounts().events.userForFeed("not-a-token"));
    assertNull(accounts().events.userForFeed(""));
    assertNull(accounts().events.userForFeed(null));
  }

  @Test
  public void theFeedIsACalendarAClientCanRead() throws Exception {
    long future = System.currentTimeMillis() + 3 * 86_400_000L;
    accounts().events.merge(me, new IcsFile.Event("gym@x", 0, "Squats", "heavy day", "The barn",
        future, future + 3_600_000L, false, "", "", "CONFIRMED", "", List.of()), "typed", null,
        null);
    String feed = accounts().events.feed(me, "Ranch", accounts().zone());
    assertTrue(feed.startsWith("BEGIN:VCALENDAR"));
    assertTrue(feed, feed.contains("X-WR-CALNAME:Ranch"));
    assertTrue(feed, feed.contains("SUMMARY:Squats"));
    assertTrue(feed.trim().endsWith("END:VCALENDAR"));
    assertEquals("and it reads back", 1, IcsFile.read(feed, UTC).events().size());
  }

  // ---- invitations that arrive by mail ---------------------------------------------------------------

  /**
   * An invitation that comes with a message lands in the calendar, unanswered.
   *
   * Imported rather than applied: a reader that silently accepts every invitation fills somebody's
   * week with meetings they never agreed to.
   */
  @Test
  public void anInvitationArrivingByMailIsImportedAndWaitsForAnAnswer() throws Exception {
    accounts().mailboxes.saveBox(0, DOMAIN, "jeff", "", me, true, null);
    Mailboxes.Box box = accounts().mailboxes.box(DOMAIN, "jeff");
    long future = System.currentTimeMillis() + 5 * 86_400_000L;
    String starts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(UTC).format(java.time.Instant.ofEpochMilli(future));
    String invite = INVITE.replace("20261008T180000Z", starts)
        .replace("20261008T190000Z", starts);

    String message = "From: Ana <ana@elsewhere.example>\r\n"
        + "Subject: Board games\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
        + "--b\r\nContent-Type: text/plain\r\n\r\nAre you free?\r\n"
        + "--b\r\nContent-Type: text/calendar; method=REQUEST; charset=utf-8\r\n\r\n"
        + invite
        + "--b--\r\n";

    Delivery delivery = new Delivery(server.messageFiles, PushOnArrival.none(), Verbose.OFF);
    Delivery.Stored stored = delivery.deliver(accounts(), new Envelope("ana@elsewhere.example",
        List.of(box.address()), message.getBytes(StandardCharsets.UTF_8), "203.0.113.9",
        "mx.elsewhere.example", DOMAIN, System.currentTimeMillis(),
        AuthResult.nothingChecked()), box, "/self");
    assertTrue(stored.problem(), stored.ok());

    assertTrue("the screen offers to answer it",
        accounts().inbox.byId(stored.id(), me).hasCalendar());
    Events.Record event = accounts().events.byUid(me, "meeting-1@example.com");
    assertNotNull(event);
    assertEquals("Board games", event.summary());
    assertEquals("invitation", event.source());
    assertEquals(Long.valueOf(stored.id()), event.fromMessage());
    assertEquals("nothing is accepted on somebody's behalf", "NEEDS-ACTION", event.myAnswer());
  }

  @Test
  public void aCancellationArrivingByMailMarksTheEventCancelled() throws Exception {
    accounts().mailboxes.saveBox(0, DOMAIN, "jeff", "", me, true, null);
    Mailboxes.Box box = accounts().mailboxes.box(DOMAIN, "jeff");
    accounts().events.merge(me, IcsFile.read(INVITE, UTC).events().get(0), "invitation", null,
        null);

    String cancel = "From: ana@elsewhere.example\r\nSubject: cancelled\r\n"
        + "Content-Type: text/calendar; method=CANCEL; charset=utf-8\r\n\r\n"
        + INVITE.replace("METHOD:REQUEST", "METHOD:CANCEL").replace("SEQUENCE:0", "SEQUENCE:1");
    new Delivery(server.messageFiles, PushOnArrival.none(), Verbose.OFF)
        .deliver(accounts(), new Envelope("ana@elsewhere.example", List.of(box.address()),
            cancel.getBytes(StandardCharsets.UTF_8), "203.0.113.9", "mx.example", DOMAIN,
            System.currentTimeMillis(), AuthResult.nothingChecked()), box, "/self");

    assertTrue(accounts().events.byUid(me, "meeting-1@example.com").cancelled());
  }

  // ---- times a person types ------------------------------------------------------------------------

  @Test
  public void aTypedTimeIsReadInTheCommunitysClockRatherThanTheMachines() {
    ZoneId chicago = ZoneId.of("America/Chicago");
    Long at = CalendarRoutes.parseLocal("2026-10-08T09:00", chicago, false, false);
    assertNotNull(at);
    assertEquals(9, java.time.Instant.ofEpochMilli(at).atZone(chicago).getHour());
  }

  @Test
  public void anAllDayEndIsTheStartOfTheNextDay() {
    Long end = CalendarRoutes.parseLocal("2026-10-08T00:00", UTC, true, true);
    Long start = CalendarRoutes.parseLocal("2026-10-08T00:00", UTC, true, false);
    assertEquals("exclusive, which is what every client expects", 86_400_000L, end - start);
  }

  @Test
  public void anUnreadableTimeIsNothingRatherThanNow() {
    assertNull(CalendarRoutes.parseLocal("next tuesday", UTC, false, false));
    assertNull(CalendarRoutes.parseLocal("", UTC, false, false));
  }
}
