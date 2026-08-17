package io.hearth.people;

import io.hearth.auth.Accounts;
import io.hearth.board.Notifier;
import io.hearth.common.Verbose;
import io.hearth.mail.InviteMail;
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
import static org.junit.Assert.assertTrue;

/**
 * The three-touch invitation: a welcome, a friendly reminder, an apology.
 *
 * The half worth testing is when it *stops*. A sequence that keeps going after somebody joined, or
 * after they were revoked, or past its own third message, is a community earning a spam complaint
 * -- and a complaint costs the whole sending domain rather than the one message.
 */
public class InviteSequenceTests {
  /** the colours and the terms link a community that has chosen nothing sends with */
  private static final io.hearth.mail.MailBrand BRAND =
      io.hearth.mail.MailBrand.standard("example.org", "Example");

  private Configs configs;
  private TestServer server;
  private Notifier notifier;
  private Invitations invitations;
  private Browser boss;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"invites\":{\"tagline\":\"A supper club\",\"about\":\"We eat meat and argue.\","
            + "\"reminder-after-days\":3,\"apology-after-days\":7}}");
    server = TestServer.ofConfigs(configs.file());
    notifier = new Notifier(server.auth, server.tree.all(), server.mailer,
        io.hearth.sms.NoSms.INSTANCE, Verbose.capturing().verbose, 3600);
    invitations = new Invitations(server.mailer);
    boss = signIn("boss@example.com");
    // an invitation says who is asking, so the sender needs a name before one can be written
    boss.get("/self");
    boss.submitTo("/self", Map.of("action", "profile", "display_name", "The Boss",
        "headline", "", "about", "", "location", "", "links", ""));
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

  // ---- the sequence ----------------------------------------------------------------------------

  @Test
  public void theFirstMessageIsAWelcomeAndTheNextIsDueThreeDaysLater() throws Exception {
    invite("newcomer@example.com");

    assertEquals(List.of("welcome"), server.mail().inviteTouches());
    Invites.Invite invite = invites().all(10).get(0);
    assertEquals(1, invite.touches());
    assertNotNull("the schedule is written when the message leaves", invite.nextTouchAt());
    long days = TimeUnit.MILLISECONDS.toDays(
        invite.nextTouchAt().getTime() - System.currentTimeMillis());
    assertTrue("about three days away, not the same afternoon", days >= 2 && days <= 3);
  }

  @Test
  public void nothingIsSentBeforeItIsDue() throws Exception {
    invite("newcomer@example.com");
    server.mail().clear();

    assertEquals(0, notifier.sweep(now()));
    assertEquals("a follow-up the same day reads as a machine", 0, server.mail().count());
  }

  @Test
  public void theWholeSequenceIsThreeMessagesAndThenItStops() throws Exception {
    invite("newcomer@example.com");
    Timestamp start = now();

    notifier.sweep(plusDays(start, 4));
    notifier.sweep(plusDays(start, 12));
    assertEquals(List.of("welcome", "reminder", "apology"), server.mail().inviteTouches());

    // and then never again, however long anybody waits
    notifier.sweep(plusDays(start, 90));
    notifier.sweep(plusDays(start, 400));
    assertEquals("a fourth is nagging, and the third said it was the last",
        3, server.mail().inviteTouches().size());
  }

  @Test
  public void somebodyWhoJoinsStopsHearingFromUs() throws Exception {
    invite("newcomer@example.com");
    Timestamp start = now();
    signUp("newcomer@example.com");
    server.mail().clear();

    notifier.sweep(plusDays(start, 4));
    notifier.sweep(plusDays(start, 12));
    assertEquals("the one thing this must never do", 0,
        server.mail().forFlow("invite").size());
  }

  @Test
  public void aRevokedInvitationStopsToo() throws Exception {
    invite("newcomer@example.com");
    Timestamp start = now();
    invites().revoke(invites().all(10).get(0).id(), null);
    server.mail().clear();

    notifier.sweep(plusDays(start, 4));
    assertEquals(0, server.mail().forFlow("invite").size());
  }

  @Test
  public void oneThatWasWrittenButNeverSentIsNeverReminded() throws Exception {
    invitations.invite(config(), accounts(), "newcomer@example.com", "", null, "boss@example.com",
        false, false);
    assertEquals(0, server.mail().count());
    notifier.sweep(plusDays(now(), 30));
    assertEquals("a reminder about a message nobody received is a first contact by another name",
        0, server.mail().count());
  }

  @Test
  public void turningRemindersOffSendsOnlyTheWelcome() throws Exception {
    Configs quiet = Configs.dir().domain("quiet.example.org",
        "{\"name\":\"Quiet\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"invites\":{\"reminders\":false}}");
    try (TestServer other = TestServer.ofConfigs(quiet.file())) {
      Invitations sender = new Invitations(other.mailer);
      Accounts theirs = other.auth.forDomain("quiet.example.org");
      sender.invite(other.tree.all().get("quiet.example.org"), theirs, "a@example.com", "", null,
          "boss@example.com", true, false);
      assertEquals(1, other.mail().inviteTouches().size());
      assertNull0(theirs.invites.all(10).get(0).nextTouchAt());
    } finally {
      quiet.delete();
    }
  }

  @Test
  public void aFailedSendStillAdvancesTheScheduleRatherThanRetryingForever() throws Exception {
    // the capturing mailer always succeeds, so the schedule is checked through markTouched itself
    invite("newcomer@example.com");
    Invites.Invite invite = invites().all(10).get(0);
    invites().markTouched(invite.id(), "SES said 554", java.time.LocalDate.now(), 0);

    Invites.Invite after = invites().byId(invite.id());
    assertEquals(2, after.touches());
    assertNull0("nothing is owed, so the loop will not pick it up every minute forever",
        after.nextTouchAt());
  }

  // ---- what the message says -------------------------------------------------------------------

  @Test
  public void theThreeMessagesDoNotReadTheSame() throws Exception {
    invite("newcomer@example.com");
    Timestamp start = now();
    notifier.sweep(plusDays(start, 4));
    notifier.sweep(plusDays(start, 12));

    List<String> subjects = server.mail().inviteSubjects();
    assertEquals(3, subjects.size());
    assertTrue(subjects.get(0), subjects.get(0).contains("invited you"));
    assertTrue(subjects.get(1), subjects.get(1).contains("Still a place"));
    assertTrue(subjects.get(2), subjects.get(2).contains("Last note"));

    List<String> bodies = server.mail().inviteHtml();
    assertTrue("the second assumes it got buried", bodies.get(1).contains("got buried"));
    assertTrue("the third says it is the last one", bodies.get(2).contains("last one"));
    assertTrue("and how to never hear from us again",
        bodies.get(2).contains("not hear from us about this again"));
  }

  @Test
  public void theSiteWideParametersAppearWithoutAnybodyTypingThem() throws Exception {
    invite("newcomer@example.com");
    String html = server.mail().inviteHtml().get(0);
    assertTrue("the tagline is set once for the whole community", html.contains("A supper club"));
    assertTrue(html.contains("We eat meat and argue."));
    assertTrue(html.contains("Accept the invitation"));
  }

  @Test
  public void theAboutBlurbIsOnlyInTheWelcome() throws Exception {
    invite("newcomer@example.com");
    notifier.sweep(plusDays(now(), 4));
    List<String> bodies = server.mail().inviteHtml();
    assertTrue(bodies.get(0).contains("We eat meat and argue."));
    assertFalse("a reminder that re-explains the community is one nobody finishes",
        bodies.get(1).contains("We eat meat and argue."));
  }

  @Test
  public void allThreeCarryTheSameLinkBecauseItIsOneInvitation() throws Exception {
    invite("newcomer@example.com");
    Timestamp start = now();
    notifier.sweep(plusDays(start, 4));
    notifier.sweep(plusDays(start, 12));

    List<CapturingMailer.Sent> sent = server.mail().forFlow("invite");
    assertEquals(3, sent.size());
    assertEquals(sent.get(0).link(), sent.get(1).link());
    assertEquals(sent.get(1).link(), sent.get(2).link());
    assertEquals("one row, so one conversion however many messages it took",
        1, invites().count());
  }

  @Test
  public void everyMessageCarriesThePixelAndItIsLast() throws Exception {
    invite("newcomer@example.com");
    String html = server.mail().inviteHtml().get(0);
    int pixel = html.indexOf("width=\"1\" height=\"1\"");
    assertTrue("there is one", pixel > 0);
    assertTrue("and it is after the sign-off, so a blocked image is not a hole in the middle",
        pixel > html.indexOf("Accept the invitation"));
    assertTrue("with empty alt, so a blocked image shows nothing rather than a broken icon",
        html.contains("alt=\"\""));
  }

  // ---- the HTML has to survive email clients ---------------------------------------------------

  @Test
  public void theMessageIsBuiltTheWayEmailHasToBeBuilt() throws Exception {
    invite("newcomer@example.com");
    String html = server.mail().inviteHtml().get(0);

    assertTrue("tables, because Outlook has no flexbox", html.contains("<table"));
    assertFalse("a stylesheet is discarded by Gmail", html.contains("<style"));
    assertFalse(html.contains("class="));
    assertTrue("the button is a table cell with a background, not a styled anchor",
        html.contains("bgcolor=\"#2f5cff\""));
    assertTrue("600px, and fluid under it", html.contains("max-width:600px"));
    assertTrue("a preheader, or the client shows the community name twice",
        html.contains("max-height:0"));
    assertTrue("dark mode is declared rather than left to be inverted for us",
        html.contains("color-scheme"));
  }

  @Test
  public void theLinkIsAlsoThereAsTextForClientsThatDisableLinks() throws Exception {
    invite("newcomer@example.com");
    String html = server.mail().inviteHtml().get(0);
    assertTrue(html.contains("Or paste this into a browser"));
    assertEquals("the link appears in the button and in the text, and nowhere else", 2,
        html.split("\\?invite=", -1).length - 1);
  }

  @Test
  public void thereIsAlwaysAPlainTextHalf() throws Exception {
    InviteMail.Invitation invitation = new InviteMail.Invitation("Example", "example.org",
        InviteMail.Touch.welcome, "https://example.org/register?invite=abc", null,
        "Ana suggested I ask you", "ana@example.com", InviteConfig.defaults());
    String text = InviteMail.text(BRAND, invitation);
    assertFalse("what a screen reader and a text client get", text.contains("<"));
    assertTrue(text.contains("https://example.org/register?invite=abc"));
    assertTrue(text.contains("Ana suggested I ask you"));
  }

  @Test
  public void whatSomebodyTypedIsEscapedEverywhere() throws Exception {
    InviteMail.Invitation invitation = new InviteMail.Invitation(
        "Example \"quoted\" & <script>", "example.org", InviteMail.Touch.welcome,
        "https://example.org/r?a=1&b=2", null, "<script>alert(1)</script>", "a@example.com",
        InviteConfig.defaults());
    String html = InviteMail.html(BRAND, invitation);
    assertFalse("a note is somebody else's text in somebody's mail client",
        html.contains("<script>alert(1)</script>"));
    assertTrue(html.contains("&lt;script&gt;"));
    assertTrue("and an ampersand in the link survives being an attribute",
        html.contains("a=1&amp;b=2"));
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private static void assertNull0(Object value) {
    assertNull0(null, value);
  }

  private static void assertNull0(String message, Object value) {
    org.junit.Assert.assertNull(message, value);
  }

  private Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  private io.hearth.vhost.DomainConfig config() {
    return server.tree.all().get("example.org");
  }

  private Invites invites() {
    return accounts().invites;
  }

  private long bossId() throws Exception {
    return accounts().users.byEmail("boss@example.com").id();
  }

  private void invite(String email) throws Exception {
    Invitations.Result result = invitations.invite(config(), accounts(), email,
        "Ana suggested I ask you", bossId(), "boss@example.com", true, false);
    assertTrue(result.detail(), result.ok());
  }

  private void signUp(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
  }

  private static Timestamp now() {
    return new Timestamp(System.currentTimeMillis());
  }

  private static Timestamp plusDays(Timestamp from, int days) {
    return new Timestamp(from.getTime() + TimeUnit.DAYS.toMillis(days));
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
