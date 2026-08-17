package io.hearth.board;

import io.hearth.auth.Accounts;
import io.hearth.common.Verbose;
import io.hearth.testkit.Browser;
import io.hearth.testkit.CapturingMailer;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Who hears about the board, how soon, and how many times.
 *
 * The interesting cases are all about *not* sending: not twice, not to the person who did it, not
 * about something that has aged out, and not at all to somebody who said no. A delivery test that
 * only proves a mail went out proves the easy half.
 *
 * Time is a parameter to {@link Notifier#sweep} rather than the clock, because a weekly digest is
 * not something anybody should have to wait a week to see work.
 */
public class NotifyTests {
  private Configs configs;
  private TestServer server;
  private Notifier notifier;
  private Browser ana;
  private Browser ben;
  private Browser cass;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"ana@example.com\",\"ben@example.com\","
            + "\"cass@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    // period is irrelevant: every test drives sweep() by hand so nothing waits on a timer
    notifier = new Notifier(server.auth, server.tree.all(), server.mailer,
        io.hearth.sms.NoSms.INSTANCE, Verbose.capturing().verbose, 3600);
    ana = signIn("ana@example.com");
    ben = signIn("ben@example.com");
    cass = signIn("cass@example.com");
    server.mail().clear();
  }

  @After
  public void tearDown() {
    if (notifier != null) {
      notifier.shutdown();
    }
    if (server != null) {
      server.close();
    }
    if (configs != null) {
      configs.delete();
    }
  }

  // ---- what somebody gets before they have chosen anything -------------------------------------

  @Test
  public void somebodyWithNoRowGetsTheDefaults() throws Exception {
    NotifyPrefs.Prefs prefs = prefs().forUser(idOf("ana@example.com"));
    assertEquals("a reply aimed at you is a conversation waiting on an answer",
        NotifyPrefs.Mode.immediate, prefs.responseMode());
    assertEquals("a thread you watch is news, and news can wait for the morning",
        NotifyPrefs.Mode.daily, prefs.replyMode());
    assertTrue(prefs.email());
    assertFalse("nothing sends text messages yet, so nothing claims to", prefs.sms());
    assertEquals("the defaults carry the user they were asked about",
        idOf("ana@example.com"), prefs.userId());
  }

  @Test
  public void anUnreadableModeFallsBackRatherThanThrowing() {
    assertEquals(NotifyPrefs.Mode.daily, NotifyPrefs.Mode.of("whenever", NotifyPrefs.Mode.daily));
    assertEquals(NotifyPrefs.Mode.daily, NotifyPrefs.Mode.of(null, NotifyPrefs.Mode.daily));
    assertEquals("case and space are not somebody's mistake to pay for",
        NotifyPrefs.Mode.weekly, NotifyPrefs.Mode.of(" Weekly ", NotifyPrefs.Mode.daily));
  }

  @Test
  public void savingWritesARowAndReadingItBackAgrees() throws Exception {
    long id = idOf("ana@example.com");
    prefs().save(id, NotifyPrefs.Mode.off, NotifyPrefs.Mode.weekly, true, false, "+15551234567");
    NotifyPrefs.Prefs saved = prefs().forUser(id);
    assertEquals(NotifyPrefs.Mode.off, saved.replyMode());
    assertEquals(NotifyPrefs.Mode.weekly, saved.responseMode());
    assertEquals("+15551234567", saved.phone());

    // and again, to prove the upsert updates rather than inserting a second row
    prefs().save(id, NotifyPrefs.Mode.immediate, NotifyPrefs.Mode.immediate, false, false, null);
    NotifyPrefs.Prefs again = prefs().forUser(id);
    assertEquals(NotifyPrefs.Mode.immediate, again.replyMode());
    assertFalse(again.email());
    assertNull("an empty number is no number, not an empty string", again.phone());
  }

  // ---- immediate -------------------------------------------------------------------------------

  @Test
  public void aReplyAimedAtSomebodyReachesThemOnTheNextPass() throws Exception {
    long post = post(ana, "Where should we meet?", "The back room is small.");
    reply(ben, post, null, "The library has a room.");

    assertEquals("nothing is sent from inside the click", 0, server.mail().count());
    assertEquals(1, notifier.sweep(now()));

    List<CapturingMailer.Sent> sent = server.mail().forFlow("board_notice");
    assertEquals(1, sent.size());
    assertEquals("ana@example.com", sent.get(0).email());
    assertTrue("and says it was aimed at her", sent.get(0).note().contains("replied to you"));
    assertTrue("with a link to the thread", sent.get(0).link().endsWith("/board/" + post));
  }

  @Test
  public void nothingIsSentTwiceHoweverOftenTheSweepRuns() throws Exception {
    long post = post(ana, "Where should we meet?", "The back room is small.");
    reply(ben, post, null, "The library has a room.");

    assertEquals(1, notifier.sweep(now()));
    assertEquals("the watermark is on the row, so a second pass finds nothing",
        0, notifier.sweep(now()));
    assertEquals(1, server.mail().forFlow("board_notice").size());
  }

  @Test
  public void theReplierNeverHearsAboutTheirOwnReply() throws Exception {
    long post = post(ana, "Where should we meet?", "The back room is small.");
    reply(ben, post, null, "The library has a room.");
    reply(ben, post, null, "Or the cafe.");
    notifier.sweep(now());

    for (CapturingMailer.Sent sent : server.mail().all()) {
      assertFalse("ben caused all of this and hears none of it",
          "ben@example.com".equals(sent.email()));
    }
  }

  @Test
  public void aReplyInAWatchedThreadIsNewsAndWaitsForTheDigest() throws Exception {
    long post = post(ana, "Where should we meet?", "The back room is small.");
    reply(ben, post, null, "The library has a room.");
    notifier.sweep(now());
    server.mail().clear();

    // cass now joins, which makes her a watcher; ben replies again
    reply(cass, post, null, "I can book it.");
    notifier.sweep(now());
    server.mail().clear();
    reply(ben, post, null, "Booked?");
    notifier.sweep(now());

    // cass was watching and was not answered, so her notification is a 'reply' on the daily
    // setting -- and she has already had today's digest from the pass before this one
    List<CapturingMailer.Sent> immediate = server.mail().forFlow("board_notice");
    for (CapturingMailer.Sent sent : immediate) {
      assertFalse("cass is on the daily setting for thread news",
          "cass@example.com".equals(sent.email()));
    }
  }

  // ---- digests ---------------------------------------------------------------------------------

  @Test
  public void aDigestGathersEverythingIntoOneMessage() throws Exception {
    long post = post(ana, "Where should we meet?", "The back room is small.");
    // ana wants everything as a daily summary, including replies aimed at her
    prefs().save(idOf("ana@example.com"), NotifyPrefs.Mode.daily, NotifyPrefs.Mode.daily, true,
        false, null);
    reply(ben, post, null, "The library has a room.");
    reply(cass, post, null, "Or the cafe.");
    reply(ben, post, null, "Either works.");

    notifier.sweep(now());
    // ben and cass are watching too, and each of them is due a first digest of their own -- so
    // this counts ana's, not everybody's
    List<CapturingMailer.Sent> digests = digestsFor("ana@example.com");
    assertEquals("three replies, one message", 1, digests.size());
    assertEquals("and nothing separate alongside it", 0, noticesFor("ana@example.com").size());

    String body = digests.get(0).note();
    assertTrue(body, body.startsWith("today"));
    assertEquals("naming everybody who was involved", 2, body.split("Ben", -1).length - 1);
    assertTrue(body.contains("Cass"));
    assertFalse("a digest is not a way to collect addresses", body.contains("@example.com"));
  }

  @Test
  public void aSecondDigestWaitsAWholeDay() throws Exception {
    long post = post(ana, "Where should we meet?", "The back room is small.");
    prefs().save(idOf("ana@example.com"), NotifyPrefs.Mode.daily, NotifyPrefs.Mode.daily, true,
        false, null);
    reply(ben, post, null, "The library has a room.");
    Timestamp start = now();
    notifier.sweep(start);
    assertEquals(1, digestsFor("ana@example.com").size());

    reply(cass, post, null, "Or the cafe.");
    notifier.sweep(plusHours(start, 1));
    assertEquals("an hour later is not a new day", 1, digestsFor("ana@example.com").size());

    notifier.sweep(plusHours(start, 25));
    assertEquals("and a day later it is", 2, digestsFor("ana@example.com").size());
    assertTrue("carrying what was waiting all along",
        digestsFor("ana@example.com").get(1).note().contains("Cass"));
  }

  @Test
  public void aWeeklyDigestWaitsAWholeWeek() throws Exception {
    long post = post(ana, "Where should we meet?", "The back room is small.");
    prefs().save(idOf("ana@example.com"), NotifyPrefs.Mode.weekly, NotifyPrefs.Mode.weekly, true,
        false, null);
    reply(ben, post, null, "The library has a room.");
    Timestamp start = now();
    notifier.sweep(start);
    assertEquals(1, digestsFor("ana@example.com").size());
    assertTrue(digestsFor("ana@example.com").get(0).note().startsWith("this week"));

    reply(cass, post, null, "Or the cafe.");
    notifier.sweep(plusHours(start, 48));
    assertEquals("two days is not a week", 1, digestsFor("ana@example.com").size());
    notifier.sweep(plusHours(start, 24 * 8));
    assertEquals("eight days is", 2, digestsFor("ana@example.com").size());
  }

  @Test
  public void whatIsWaitingForADigestIsNotStampedUntilItGoes() throws Exception {
    long post = post(ana, "Where should we meet?", "The back room is small.");
    prefs().save(idOf("ana@example.com"), NotifyPrefs.Mode.daily, NotifyPrefs.Mode.daily, true,
        false, null);
    Timestamp start = now();
    notifier.sweep(start);
    reply(ben, post, null, "The library has a room.");
    notifier.sweep(start);
    server.mail().clear();

    // the first sweep gave her today's digest, so this one is held rather than dropped
    reply(cass, post, null, "Or the cafe.");
    notifier.sweep(plusHours(start, 1));
    assertEquals("held, not sent", 0, digestsFor("ana@example.com").size());
    assertTrue("and still in the queue",
        inbox().undelivered(50).stream().anyMatch(note -> note.userId() == anaId()));

    notifier.sweep(plusHours(start, 25));
    assertEquals("it arrives tomorrow", 1, digestsFor("ana@example.com").size());
    assertTrue(digestsFor("ana@example.com").get(0).note().contains("Cass"));
    assertFalse(inbox().undelivered(50).stream().anyMatch(note -> note.userId() == anaId()));
  }

  // ---- saying no -------------------------------------------------------------------------------

  @Test
  public void somebodyWhoWantsNothingGetsNothingAndTheQueueStillDrains() throws Exception {
    long post = post(ana, "Where should we meet?", "The back room is small.");
    prefs().save(idOf("ana@example.com"), NotifyPrefs.Mode.off, NotifyPrefs.Mode.off, true, false,
        null);
    reply(ben, post, null, "The library has a room.");

    assertEquals(0, notifier.sweep(now()));
    assertEquals(0, server.mail().count());
    assertEquals("stamped anyway: an unstamped row nothing will act on is a queue that grows"
        + " forever", 0, inbox().undelivered(50).size());
    assertEquals("and it is still in her inbox on the site", 1, inbox().unreadCount(
        idOf("ana@example.com")));
  }

  @Test
  public void turningEmailOffStopsEveryChannelAtOnce() throws Exception {
    long post = post(ana, "Where should we meet?", "The back room is small.");
    prefs().save(idOf("ana@example.com"), NotifyPrefs.Mode.immediate, NotifyPrefs.Mode.immediate,
        false, false, null);
    reply(ben, post, null, "The library has a room.");

    assertEquals(0, notifier.sweep(now()));
    assertEquals(0, server.mail().count());
    assertEquals(0, inbox().undelivered(50).size());
  }

  @Test
  public void nothingIsMailedAboutAThreadThatHasAlreadyAgedOut() throws Exception {
    long ana = idOf("ana@example.com");
    inbox().add(ana, Inbox.Kind.response, 1L, 2L, "ben@example.com", "ben replied to you",
        new Timestamp(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5)));

    assertEquals(0, notifier.sweep(now()));
    assertEquals("mailing somebody about a conversation that is gone is worse than saying nothing",
        0, server.mail().count());
  }

  // ---- who is being answered -------------------------------------------------------------------

  @Test
  public void aReplyToACommentAnswersItsAuthorAndNotThePoster() throws Exception {
    long post = post(ana, "Where should we meet?", "The back room is small.");
    reply(ben, post, null, "The library has a room.");
    notifier.sweep(now());
    long benComment = board().thread(post).get(0).id();
    server.mail().clear();

    reply(cass, post, benComment, "Is it free?");
    notifier.sweep(now());

    // ben was answered, so it is a response and reaches him straight away; ana is watching, so
    // hers is thread news and waits for a digest she has already had today
    List<CapturingMailer.Sent> notices = server.mail().forFlow("board_notice");
    assertEquals(1, notices.size());
    assertEquals("ben@example.com", notices.get(0).email());
    assertTrue(notices.get(0).note().contains("replied to you"));
  }

  @Test
  public void answeringYourselfIsNotANotification() throws Exception {
    long post = post(ana, "Where should we meet?", "The back room is small.");
    reply(ana, post, null, "Thinking out loud.");
    notifier.sweep(now());
    assertEquals("a train of thought is not a conversation waiting on an answer",
        0, server.mail().count());
  }

  // ---- the settings page -----------------------------------------------------------------------

  @Test
  public void theSettingsPageShowsWhatIsChosenAndSavesWhatIsPicked() throws Exception {
    Browser.Page page = ana.get("/self?tab=notifications");
    assertEquals(200, page.status());
    assertTrue("the two questions are asked separately", page.contains("replies to you"));
    assertTrue(page.contains("conversation you are watching"));
    assertTrue("and the default is preselected", page.contains("value=\"immediate\" checked"));

    Browser.Page saved = ana.submitTo("/self", Map.of("action", "notifications",
        "reply_mode", "weekly", "response_mode", "off", "email", "1"));
    assertEquals(303, saved.status());
    assertTrue(saved.location(), saved.location().contains("tab=notifications"));

    NotifyPrefs.Prefs prefs = prefs().forUser(idOf("ana@example.com"));
    assertEquals(NotifyPrefs.Mode.weekly, prefs.replyMode());
    assertEquals(NotifyPrefs.Mode.off, prefs.responseMode());
    assertTrue(prefs.email());
  }

  @Test
  public void anUncheckedBoxMeansOffRatherThanUnchanged() throws Exception {
    Browser.Page saved = ana.submitTo("/self", Map.of("action", "notifications",
        "reply_mode", "daily", "response_mode", "immediate"));
    assertEquals(303, saved.status());
    assertFalse("a checkbox nobody ticked is a checkbox somebody unticked",
        prefs().forUser(idOf("ana@example.com")).email());
  }

  @Test
  public void theSettingsPageDoesNotOfferAChannelNothingDeliversOn() throws Exception {
    Browser.Page page = ana.get("/self?tab=notifications");
    assertTrue("it says so rather than showing a box that stores a broken promise",
        page.contains("not available on this server"));
    assertFalse(page.contains("name=\"phone\""));
  }

  @Test
  public void theNotificationsTabIsReachableFromTheTabStrip() throws Exception {
    Browser.Page page = ana.get("/self");
    // mustache.java escapes '=' inside an attribute and the compactor's parser decodes it again on
    // the way out, since an attribute value has no need of it. Both spellings are the same link;
    // this asserts the one that actually ships.
    assertTrue(page.contains("tab=notifications"));
    assertTrue(page.contains(">Notifications<"));
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  private NotifyPrefs prefs() {
    return accounts().notifyPrefs;
  }

  private Inbox inbox() {
    return accounts().inbox;
  }

  private Board board() {
    return accounts().board;
  }

  private long idOf(String email) throws Exception {
    return accounts().users.byEmail(email).id();
  }

  private List<CapturingMailer.Sent> digestsFor(String email) {
    return server.mail().forFlow("digest").stream()
        .filter(sent -> sent.email().equalsIgnoreCase(email)).toList();
  }

  private List<CapturingMailer.Sent> noticesFor(String email) {
    return server.mail().forFlow("board_notice").stream()
        .filter(sent -> sent.email().equalsIgnoreCase(email)).toList();
  }

  private long anaId() {
    try {
      return idOf("ana@example.com");
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private static Timestamp now() {
    return new Timestamp(System.currentTimeMillis());
  }

  private static Timestamp plusHours(Timestamp from, int hours) {
    return new Timestamp(from.getTime() + TimeUnit.HOURS.toMillis(hours));
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

  private long post(Browser who, String title, String body) throws Exception {
    who.get("/board");
    Browser.Page done = who.submitTo("/board",
        Map.of("action", "post", "title", title, "body", body));
    assertEquals(303, done.status());
    return Long.parseLong(done.location().substring("/board/".length()));
  }

  private void reply(Browser who, long postId, Long parentId, String body) throws Exception {
    who.get("/board/" + postId);
    var form = new java.util.LinkedHashMap<String, String>();
    form.put("action", "reply");
    form.put("post", Long.toString(postId));
    if (parentId != null) {
      form.put("parent", Long.toString(parentId));
    }
    form.put("body", body);
    Browser.Page done = who.submitTo("/board", form);
    assertEquals(303, done.status());
    assertNotNull(done.location());
  }
}
