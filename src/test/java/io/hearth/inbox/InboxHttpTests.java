package io.hearth.inbox;

import io.hearth.auth.Accounts;
import io.hearth.common.Verbose;
import io.hearth.smtp.AuthResult;
import io.hearth.smtp.Envelope;
import io.hearth.smtp.Mailboxes;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The mail screens and the calendar, driven over real HTTP.
 *
 * <b>The download path is the one that has to be tested from outside.</b> What a browser is told
 * about an attachment -- the content type, the disposition, `nosniff` -- is the whole difference
 * between handing somebody a file and running a stranger's document in this origin, and none of it
 * is visible from inside the handler.
 */
public class InboxHttpTests {
  private static final String DOMAIN = "example.org";

  private Configs configs;
  private TestServer server;
  private Browser me;
  private long myId;
  private Delivery delivery;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain(DOMAIN,
        "{\"name\":\"Ranch\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    me = new Browser(server.port, DOMAIN);
    me.get("/register");
    me.submit(Map.of("email", "boss@example.com"));
    me.submit(Map.of("code", server.mail().lastCodeFor("boss@example.com")));
    myId = accounts().users.byEmail("boss@example.com").id();
    delivery = new Delivery(server.messageFiles, PushOnArrival.none(), Verbose.OFF);
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

  private Mailboxes.Box mailbox(String local, Long owner) throws Exception {
    accounts().mailboxes.saveBox(0, DOMAIN, local, "", owner, true, null);
    return accounts().mailboxes.box(DOMAIN, local);
  }

  private long deliver(String message) throws Exception {
    Mailboxes.Box box = accounts().mailboxes.box(DOMAIN, "jeff");
    if (box == null) {
      box = mailbox("jeff", myId);
    }
    Envelope envelope = new Envelope("someone@gmail.com", List.of(box.address()),
        message.getBytes(StandardCharsets.UTF_8), "203.0.113.9", "mail.google.com", DOMAIN,
        System.currentTimeMillis(), AuthResult.nothingChecked());
    Delivery.Stored stored = delivery.deliver(accounts(), envelope, box, "/self");
    assertTrue(String.valueOf(stored.problem()), stored.ok());
    return stored.id();
  }

  private static byte[] png() {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
    out.writeBytes(new byte[]{0, 0, 0, 13, 'I', 'H', 'D', 'R'});
    out.writeBytes(new byte[]{0, 0, 0, 16, 0, 0, 0, 16});
    out.writeBytes(new byte[]{8, 6, 0, 0, 0});
    return out.toByteArray();
  }

  private static String withParts() {
    return "From: Someone <someone@gmail.com>\r\n"
        + "Subject: the photos\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
        + "--b\r\nContent-Type: text/plain\r\n\r\nhave a look\r\n"
        + "--b\r\nContent-Type: image/png\r\n"
        + "Content-Disposition: attachment; filename=\"photo.png\"\r\n"
        + "Content-Transfer-Encoding: base64\r\n\r\n"
        + Base64.getMimeEncoder(76, new byte[]{'\r', '\n'}).encodeToString(png()) + "\r\n"
        + "--b\r\nContent-Type: application/octet-stream\r\n"
        + "Content-Disposition: attachment; filename=\"setup.exe\"\r\n\r\nMZ nope\r\n"
        + "--b--\r\n";
  }

  private static final String SIMPLE =
      "From: Someone <someone@gmail.com>\r\nTo: jeff@" + DOMAIN + "\r\n"
          + "Subject: about Thursday\r\nMessage-ID: <abc@gmail.com>\r\n\r\n"
          + "Can you do the ninth?\r\n";

  // ---- the screens -------------------------------------------------------------------------------

  @Test
  public void theInboxSaysSoWhenItIsEmpty() throws Exception {
    Browser.Page page = me.get("/self/mail");
    assertEquals(200, page.status());
    assertTrue(page.body(), page.contains("Nothing to do"));
  }

  @Test
  public void aMessageIsListedAndCanBeOpened() throws Exception {
    long id = deliver(SIMPLE);
    Browser.Page list = me.get("/self/mail");
    assertTrue(list.body(), list.contains("about Thursday"));
    assertTrue(list.contains("Someone"));

    Browser.Page one = me.get("/self/mail/" + id);
    assertEquals(200, one.status());
    assertTrue(one.contains("Can you do the ninth?"));
    assertTrue("opening it is what marks it read",
        accounts().inbox.byId(id, myId).readAt() != null);
  }

  @Test
  public void somebodyElsesMessageIsAFourOhFour() throws Exception {
    long id = deliver(SIMPLE);
    Browser them = new Browser(server.port, DOMAIN);
    them.get("/register");
    them.submit(Map.of("email", "friend@example.com"));
    them.submit(Map.of("code", server.mail().lastCodeFor("friend@example.com")));
    long friend = accounts().users.byEmail("friend@example.com").id();
    accounts().users.approve(friend, null);

    assertEquals(404, them.get("/self/mail/" + id).status());
    assertEquals(404, them.get("/self/mail/part?m=" + id + "&p=1").status());
    assertEquals(404, them.get("/self/mail/raw?m=" + id).status());
  }

  @Test
  public void signedOutIsSentToTheDoorRatherThanShownAnything() throws Exception {
    Browser nobody = new Browser(server.port, DOMAIN);
    Browser.Page page = nobody.get("/self/mail");
    assertEquals(303, page.status());
    assertTrue(String.valueOf(page.location()), page.location().contains("/login"));
  }

  // ---- downloads ---------------------------------------------------------------------------------

  /**
   * An attachment is served as the type this server chose, never the sender's.
   *
   * A message may say whatever it likes about its own attachment, and a browser that believes it
   * is the whole attack.
   */
  @Test
  public void anAllowedAttachmentComesBackWithTheRightHeaders() throws Exception {
    long id = deliver(withParts());
    Messages.Record message = accounts().inbox.byId(id, myId);
    Messages.Attachment photo = message.parts().stream()
        .filter(part -> part.filename().equals("photo.png")).findFirst().orElseThrow();

    Browser.Page download = me.get("/self/mail/part?m=" + id + "&p=" + photo.path());
    assertEquals(200, download.status());
    assertEquals("image/png", download.header("content-type"));
    assertTrue(String.valueOf(download.header("content-disposition")),
        download.header("content-disposition").contains("photo.png"));
    assertEquals("a browser must never sniff its way to a different type", "nosniff",
        download.header("x-content-type-options"));
    assertTrue("somebody else's photograph is never in a shared cache",
        download.header("cache-control").contains("private"));
  }

  @Test
  public void aRefusedAttachmentIsNotServedAtAll() throws Exception {
    long id = deliver(withParts());
    Messages.Record message = accounts().inbox.byId(id, myId);
    Messages.Attachment executable = message.parts().stream()
        .filter(part -> part.filename().equals("setup.exe")).findFirst().orElseThrow();
    assertFalse(executable.allowed());
    assertEquals(404, me.get("/self/mail/part?m=" + id + "&p=" + executable.path()).status());

    // and it is still listed on the page, with the reason
    Browser.Page page = me.get("/self/mail/" + id);
    assertTrue(page.body(), page.contains("setup.exe"));
    assertTrue(page.body(), page.contains("not handed back"));
  }

  /**
   * A part is named by a path of digits and dots, and nothing else reaches the file.
   *
   * Traversal never gets this far -- the scanner shield answers it before routing does, which is
   * why the assertion is "not served" rather than a particular code. What matters is that no shape
   * of `p` produces bytes.
   */
  @Test
  public void aPartPathThatIsNotAPathIsRefused() throws Exception {
    long id = deliver(withParts());
    for (String bad : new String[]{"../../etc/passwd", "1;drop", "", "9.9.9", "1.1'"}) {
      int status = me.get("/self/mail/part?m=" + id + "&p="
          + java.net.URLEncoder.encode(bad, StandardCharsets.UTF_8)).status();
      assertTrue(bad + " answered " + status, status >= 400);
    }
  }

  @Test
  public void theOriginalComesBackAsAnInertDownload() throws Exception {
    long id = deliver(SIMPLE);
    Browser.Page raw = me.get("/self/mail/raw?m=" + id);
    assertEquals(200, raw.status());
    assertEquals("message/rfc822", raw.header("content-type"));
    assertTrue(raw.header("content-disposition").contains("attachment"));
    assertTrue(raw.body(), raw.body().contains("Can you do the ninth?"));
  }

  // ---- zero inbox ----------------------------------------------------------------------------------

  @Test
  public void beingDoneWithSomethingTakesItOutOfTheList() throws Exception {
    long id = deliver(SIMPLE);
    me.get("/self/mail/" + id);
    me.submitToAndFollow("/self/mail", Map.of("action", "archive", "id", String.valueOf(id)));
    assertEquals(0, accounts().inbox.inboxCount(myId));
    assertTrue(me.get("/self/mail").contains("Nothing to do"));
    assertTrue("and it is still findable", me.get("/self/mail?show=archive")
        .contains("about Thursday"));
  }

  @Test
  public void deletingTakesTheRowAndTheOriginal() throws Exception {
    long id = deliver(SIMPLE);
    assertTrue(server.messageFiles.has(id));
    me.submitToAndFollow("/self/mail", Map.of("action", "delete", "id", String.valueOf(id)));
    assertNull(accounts().inbox.byId(id, myId));
    assertFalse("no orphan left behind", server.messageFiles.has(id));
  }

  @Test
  public void thereIsNoActionThisPageDoesNotKnow() throws Exception {
    long id = deliver(SIMPLE);
    Browser.Page landed = me.submitToAndFollow("/self/mail",
        Map.of("action", "quarantine", "id", String.valueOf(id)));
    assertTrue(landed.body(), landed.contains("not something this page can do"));
    assertNotNull("and nothing happened to the message", accounts().inbox.byId(id, myId));
  }

  @Test
  public void aReplyWithNothingInItIsRefused() throws Exception {
    long id = deliver(SIMPLE);
    Browser.Page landed = me.submitToAndFollow("/self/mail",
        Map.of("action", "reply", "id", String.valueOf(id), "body", "  "));
    assertTrue(landed.body(), landed.contains("An empty reply is not a reply"));
  }

  /**
   * With no way to send, the reply screen says so rather than pretending.
   *
   * The test server has no relay on purpose -- a unit test that opened outbound SMTP connections
   * would be a unit test that fails on a train.
   */
  @Test
  public void withNoWayToSendTheScreenSaysSoRatherThanFailingLater() throws Exception {
    long id = deliver(SIMPLE);
    Browser.Page reply = me.get("/self/mail/" + id + "/reply");
    assertEquals(200, reply.status());
    assertTrue(reply.body(), reply.contains("cannot send mail"));

    Browser.Page landed = me.submitToAndFollow("/self/mail",
        Map.of("action", "reply", "id", String.valueOf(id), "body", "yes"));
    assertTrue(landed.body(), landed.contains("cannot send mail"));
    assertFalse("and it stays in the inbox, because it still needs answering",
        accounts().inbox.byId(id, myId).archived());
  }

  @Test
  public void theReplyScreenNamesTheAddressItWillGoOutAs() throws Exception {
    mailbox("receipts", myId);
    Mailboxes.Box box = accounts().mailboxes.box(DOMAIN, "receipts");
    Envelope envelope = new Envelope("someone@gmail.com", List.of(box.address()),
        SIMPLE.getBytes(StandardCharsets.UTF_8), "203.0.113.9", "mx", DOMAIN,
        System.currentTimeMillis(), AuthResult.nothingChecked());
    long id = delivery.deliver(accounts(), envelope, box, "/self").id();

    Browser.Page reply = me.get("/self/mail/" + id + "/reply");
    assertTrue(reply.body(), reply.contains("receipts@" + DOMAIN));
    assertTrue("reply-all is the default", reply.contains("reply to just"));
  }

  // ---- the calendar --------------------------------------------------------------------------------

  @Test
  public void theCalendarLoadsAndAnEventCanBeAdded() throws Exception {
    assertEquals(200, me.get("/self/calendar").status());
    assertEquals(200, me.get("/self/calendar/new").status());

    me.submitToAndFollow("/self/calendar", Map.of("action", "save", "summary", "Squats",
        "starts", "2026-10-08T09:00", "ends", "2026-10-08T10:00", "location", "The barn",
        "rrule", "", "description", ""));
    assertEquals(1, accounts().events.count(myId));
    assertTrue(me.get("/self/calendar").contains("Squats"));
  }

  @Test
  public void anEventThatEndsBeforeItStartsIsRefused() throws Exception {
    Browser.Page landed = me.submitToAndFollow("/self/calendar",
        Map.of("action", "save", "summary", "Backwards", "starts", "2026-10-08T10:00",
            "ends", "2026-10-08T09:00", "location", "", "rrule", "", "description", ""));
    assertTrue(landed.body(), landed.contains("cannot end before it starts"));
    assertEquals(0, accounts().events.count(myId));
  }

  @Test
  public void anEventWithNoNameIsRefused() throws Exception {
    Browser.Page landed = me.submitToAndFollow("/self/calendar",
        Map.of("action", "save", "summary", "  ", "starts", "2026-10-08T10:00",
            "ends", "2026-10-08T11:00", "location", "", "rrule", "", "description", ""));
    assertTrue(landed.body(), landed.contains("needs a name"));
  }

  /**
   * The subscription URL is shown once, at the moment it is minted.
   *
   * The row holds a hash, so this is the only point at which it exists in a readable form. A screen
   * that could redisplay it would mean the server was keeping it.
   */
  @Test
  public void theSubscriptionLinkIsShownOnceAndThenWorks() throws Exception {
    Browser.Page landed = me.submitToAndFollow("/self/calendar", Map.of("action", "feed"));
    assertTrue(landed.body(), landed.contains("shown once"));

    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("/calendar/([A-Za-z0-9_-]+)\\.ics").matcher(landed.body());
    assertTrue("the link is on the page", matcher.find());
    String token = matcher.group(1);

    assertFalse("and never again", me.get("/self/calendar").contains(token));
    assertEquals(Long.valueOf(myId), accounts().events.userForFeed(token));
  }

  /**
   * A calendar client has no session, so the feed answers without one.
   *
   * A wrong token and a revoked one answer identically: this is the one URL anybody on the
   * internet can guess at, and it must not confirm a near miss.
   */
  @Test
  public void theFeedAnswersWithNoSessionAndRefusesAWrongToken() throws Exception {
    long future = System.currentTimeMillis() + 86_400_000L;
    accounts().events.merge(myId, new io.hearth.calendar.IcsFile.Event("gym@x", 0, "Squats", "",
        "", future, future + 3_600_000L, false, "", "", "CONFIRMED", "", List.of()), "typed",
        null, null);
    String token = accounts().events.mintFeedToken(myId);

    Browser nobody = new Browser(server.port, DOMAIN);
    Browser.Page feed = nobody.get("/calendar/" + token + ".ics");
    assertEquals(200, feed.status());
    assertTrue(feed.header("content-type").startsWith("text/calendar"));
    assertTrue("one person's appointments are never in a shared cache",
        feed.header("cache-control").contains("private"));
    assertTrue(feed.body(), feed.body().contains("SUMMARY:Squats"));

    assertEquals(404, nobody.get("/calendar/not-a-real-token.ics").status());
    accounts().events.revokeFeed(myId);
    assertEquals("a revoked one answers exactly the same", 404,
        nobody.get("/calendar/" + token + ".ics").status());
  }

  /**
   * Turning an account off stops its calendar feed, which has no session to revoke.
   *
   * <b>The one credential a disabled person would otherwise keep.</b> Disabling revokes every
   * session, and this URL has none -- so without a check on the account the feed they minted while
   * they were a member goes on serving their calendar, from a phone, for years. It answers the same
   * 404 a wrong token gets: whether an account is disabled is not something an unauthenticated
   * request should be able to find out.
   */
  @Test
  public void aDisabledAccountsCalendarFeedStopsWorking() throws Exception {
    long future = System.currentTimeMillis() + 86_400_000L;
    accounts().events.merge(myId, new io.hearth.calendar.IcsFile.Event("gym@x", 0, "Squats", "",
        "", future, future + 3_600_000L, false, "", "", "CONFIRMED", "", List.of()), "typed",
        null, null);
    String token = accounts().events.mintFeedToken(myId);
    Browser nobody = new Browser(server.port, DOMAIN);
    assertEquals(200, nobody.get("/calendar/" + token + ".ics").status());

    accounts().users.setDisabled(myId, true);
    assertEquals("the same answer a made-up token gets", 404,
        nobody.get("/calendar/" + token + ".ics").status());

    accounts().users.setDisabled(myId, false);
    assertEquals("and it comes back when they do", 200,
        nobody.get("/calendar/" + token + ".ics").status());
  }

  @Test
  public void anEventCanBeEditedAndDeleted() throws Exception {
    me.submitToAndFollow("/self/calendar", Map.of("action", "save", "summary", "Squats",
        "starts", "2026-10-08T09:00", "ends", "2026-10-08T10:00", "location", "", "rrule", "",
        "description", ""));
    long id = accounts().events.between(myId, 0, Long.MAX_VALUE).get(0).id();
    String uid = accounts().events.byId(id, myId).uid();

    assertEquals(200, me.get("/self/calendar/edit/" + id).status());
    me.submitToAndFollow("/self/calendar", Map.of("action", "save", "id", String.valueOf(id),
        "summary", "Deadlifts", "starts", "2026-10-08T09:00", "ends", "2026-10-08T10:00",
        "location", "", "rrule", "", "description", ""));
    assertEquals("Deadlifts", accounts().events.byId(id, myId).summary());
    assertEquals("the uid never changes, or a subscriber sees two events", uid,
        accounts().events.byId(id, myId).uid());
    assertEquals("and the sequence goes up, or a subscriber keeps the old one", 1,
        accounts().events.byId(id, myId).sequence());

    me.submitToAndFollow("/self/calendar",
        Map.of("action", "delete", "id", String.valueOf(id)));
    assertEquals(0, accounts().events.count(myId));
  }

  @Test
  public void anotherPersonsEventIsNotEditable() throws Exception {
    long other = accounts().users.create("friend@example.com", null, true, null).id();
    accounts().events.merge(other, new io.hearth.calendar.IcsFile.Event("theirs@x", 0, "Private",
        "", "", 1000, 2000, false, "", "", "CONFIRMED", "", List.of()), "typed", null, null);
    long id = accounts().events.between(other, 0, Long.MAX_VALUE).get(0).id();

    assertEquals(404, me.get("/self/calendar/edit/" + id).status());
    Browser.Page landed = me.submitToAndFollow("/self/calendar",
        Map.of("action", "delete", "id", String.valueOf(id)));
    assertTrue(landed.body(), landed.contains("not here"));
    assertEquals("still theirs", 1, accounts().events.count(other));
  }
}
