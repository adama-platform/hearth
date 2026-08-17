package io.hearth.people;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What one member can learn about another, on every screen that mentions people.
 *
 * <b>An address is not a name, and this product had it both ways.</b> `/members` was built with the
 * rule that a directory carries no email addresses -- a member list is the easiest thing in the
 * world to screenshot, and a community of two hundred should not hand each of them a machine
 * readable list of the other hundred and ninety nine. Every other screen printed addresses anyway:
 * the board on every post and comment, the guest list once per guest, the dashboard once per thread,
 * and a notification that said "ana@example.com replied to you" in somebody's inbox and again in
 * their email.
 *
 * So this walks every one of them and asserts the same thing: a person is named, and the address is
 * not there. The admin section is the exception it has always been -- approving somebody is a
 * decision about an address -- and that is asserted too, because a review that quietly removed it
 * would break the job it exists for.
 */
public class MemberDataTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;
  private Browser ana;
  private Browser bo;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signedIn("boss@example.com", "The Boss");
    ana = approved("ana@example.com", "Ana Rivera");
    bo = approved("bo@example.com", "Bo Chen");
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

  private Browser signedIn(String email, String name) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    browser.get("/welcome");
    browser.submitTo("/welcome",
        Map.of("action", "name", "display_name", name, "location", "Austin", "about", ""));
    browser.get("/welcome?step=3");
    return browser;
  }

  private Browser approved(String email, String name) throws Exception {
    Browser member = signedIn(email, name);
    long id = server.auth.forDomain("example.org").users.byEmail(email).id();
    admin.get("/admin/people");
    admin.submitTo("/admin/people", Map.of("action", "approve", "user", Long.toString(id)));
    return member;
  }

  /** every address in this community, so a page can be checked against all of them at once */
  private static void carriesNoAddresses(String label, Browser.Page page) {
    for (String email : new String[]{"ana@example.com", "bo@example.com", "boss@example.com"}) {
      assertFalse(label + " should not carry " + email, page.contains(email));
    }
  }

  // ---- the board ------------------------------------------------------------------------------------

  @Test
  public void aThreadNamesPeopleRatherThanAddressingThem() throws Exception {
    ana.get("/board");
    ana.submitTo("/board", Map.of("action", "post", "title", "Bread day",
        "body", "Who is baking?"));
    long postId = server.auth.forDomain("example.org").board.feed(10).get(0).id();
    bo.get("/board/" + postId);
    bo.submitTo("/board", Map.of("action", "reply", "post", Long.toString(postId),
        "body", "I will."));

    Browser.Page feed = bo.get("/board");
    assertTrue(feed.contains("Ana Rivera"));
    carriesNoAddresses("the feed", feed);

    Browser.Page thread = bo.get("/board/" + postId);
    assertTrue(thread.contains("Ana Rivera"));
    assertTrue(thread.contains("Bo Chen"));
    carriesNoAddresses("a thread", thread);
  }

  @Test
  public void theDashboardNamesThemToo() throws Exception {
    ana.get("/board");
    ana.submitTo("/board", Map.of("action", "post", "title", "Chairs", "body", "We need some."));
    Browser.Page home = bo.get("/home");
    assertTrue(home.contains("Ana Rivera"));
    carriesNoAddresses("the dashboard", home);
  }

  @Test
  public void anInboxAndItsEmailSayAName() throws Exception {
    ana.get("/board");
    ana.submitTo("/board", Map.of("action", "post", "title", "Bread day", "body", "?"));
    long postId = server.auth.forDomain("example.org").board.feed(10).get(0).id();
    bo.get("/board/" + postId);
    bo.submitTo("/board", Map.of("action", "reply", "post", Long.toString(postId),
        "body", "I will."));

    Browser.Page inbox = ana.get("/self?tab=inbox");
    assertTrue(inbox.contains("Bo Chen"));
    // her own address is on her own page, which is the only one she is entitled to
    assertFalse("an inbox", inbox.contains("bo@example.com"));
    assertFalse("an inbox", inbox.contains("boss@example.com"));

    // the note is what the notifier mails out, so the address would have travelled with it
    io.hearth.board.Inbox.Note note = server.auth.forDomain("example.org").inbox
        .forUser(server.auth.forDomain("example.org").users.byEmail("ana@example.com").id(), 5)
        .get(0);
    assertEquals("Bo Chen", note.actorName());
    assertFalse(note.text().contains("@example.com"));
  }

  // ---- the calendar ---------------------------------------------------------------------------------

  @Test
  public void aGuestListIsPeopleRatherThanAMailingList() throws Exception {
    LocalDate day = LocalDate.now().plusDays(3);
    admin.get("/admin/calendar/new");
    admin.submitTo("/admin/calendar", Map.of("action", "save", "title", "Supper",
        "body", "", "location", "The hall", "place_id", "", "starts_on", day.toString(),
        "ends_on", day.toString(), "start_time", "7pm", "capacity", "", "published", "on"));
    long id = server.auth.forDomain("example.org").calendar.upcoming(LocalDate.now(), 5).get(0).id();

    ana.get("/events/" + id);
    ana.submitTo("/events", Map.of("action", "rsvp", "event", Long.toString(id),
        "answer", "going", "party", "1", "note", ""));

    Browser.Page page = bo.get("/events/" + id);
    assertTrue("who else is coming is the point of the list", page.contains("Ana Rivera"));
    carriesNoAddresses("a guest list", page);
  }

  // ---- the directory --------------------------------------------------------------------------------

  @Test
  public void theDirectoryAndAMemberPageStillCarryNone() throws Exception {
    carriesNoAddresses("the directory", bo.get("/members"));
    long anaId = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    Browser.Page page = bo.get("/members/" + anaId);
    assertTrue(page.contains("Ana Rivera"));
    carriesNoAddresses("a member page", page);
  }

  // ---- and the one place they belong ----------------------------------------------------------------

  @Test
  public void theAdminSectionStillShowsAddressesBecauseThatIsTheJob() throws Exception {
    assertTrue("approving somebody is a decision about an address",
        admin.get("/admin/people/list").contains("ana@example.com"));
    long anaId = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    assertTrue(admin.get("/admin/people/review/" + anaId).contains("ana@example.com"));
  }

  @Test
  public void somebodyWithNoNameIsAMemberRatherThanHalfAnAddress() throws Exception {
    // a local part is still most of an address and usually most of a real name
    Browser quiet = new Browser(server.port, "example.org");
    quiet.get("/register");
    quiet.submit(Map.of("email", "quiet@example.com"));
    quiet.submit(Map.of("code", server.mail().lastCodeFor("quiet@example.com")));
    long id = server.auth.forDomain("example.org").users.byEmail("quiet@example.com").id();
    admin.get("/admin/people");
    admin.submitTo("/admin/people", Map.of("action", "approve", "user", Long.toString(id)));

    quiet.get("/board");
    quiet.submitTo("/board", Map.of("action", "post", "title", "Hello", "body", "New here."));

    Browser.Page feed = ana.get("/board");
    assertTrue(feed.contains(Names.UNKNOWN));
    assertFalse("not the part before the @", feed.contains(">quiet<"));
    assertFalse(feed.contains("quiet@example.com"));
  }
}
