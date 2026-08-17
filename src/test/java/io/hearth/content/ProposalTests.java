package io.hearth.content;

import io.hearth.auth.Permission;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Suggested edits, and the review that lets them in.
 *
 * The version history looking forwards. What has to be true: a suggestion changes nothing until
 * somebody says yes, approving it produces an ordinary version, declining keeps the record, and a
 * page that moved underneath a suggestion says so instead of quietly reverting somebody's work.
 */
public class ProposalTests {
  private Configs configs;
  private TestServer server;
  private Browser boss;
  private Browser writer;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    boss = signIn("boss@example.com");
    writer = signIn("writer@example.com");
    // somebody who may suggest but never publish -- the whole reason this queue exists
    accounts().roleDefs.save("suggester", "Suggester", "", EnumSet.of(Permission.content_propose),
        "blue", null);
    accounts().roles.grant(accounts().users.byEmail("writer@example.com").id(), "suggester", null);
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

  // ---- suggesting ------------------------------------------------------------------------------

  @Test
  public void aSuggestionChangesNothingUntilSomebodySaysYes() throws Exception {
    long id = page("/about", "About", "The original words.");
    suggest(id, "/about", "About", "Words somebody suggested.");

    assertEquals("the page is untouched", "The original words.", store().byId(id).body());
    assertEquals(1, proposals().openCount());
    assertEquals("and no version was recorded", 1, versions().history(id).size());
  }

  @Test
  public void approvingAppliesItAndRecordsAnOrdinaryVersion() throws Exception {
    long id = page("/about", "About", "The original words.");
    suggest(id, "/about", "About", "Words somebody suggested.");
    long proposal = proposals().open(10).get(0).id();

    boss.submitToAndFollow("/admin/content/proposals",
        Map.of("action", "approve", "id", Long.toString(proposal)));

    assertEquals("Words somebody suggested.", store().byId(id).body());
    assertEquals("an approved suggestion is a version like any other",
        2, versions().history(id).size());
    assertEquals(Proposals.State.approved, proposals().byId(proposal).state());
    assertEquals(0, proposals().openCount());
  }

  @Test
  public void decliningKeepsTheSuggestionAndTheReason() throws Exception {
    long id = page("/about", "About", "The original words.");
    suggest(id, "/about", "About", "Words somebody suggested.");
    long proposal = proposals().open(10).get(0).id();

    boss.submitToAndFollow("/admin/content/proposals", Map.of("action", "decline",
        "id", Long.toString(proposal), "note", "We say it the other way round here."));

    assertEquals("The original words.", store().byId(id).body());
    Proposals.Proposal declined = proposals().byId(proposal);
    assertEquals(Proposals.State.declined, declined.state());
    assertTrue("somebody spent time on this; 'no, because' is a thing they should read",
        declined.decisionNote().contains("other way round"));
    assertEquals("boss@example.com", declined.decidedByEmail());
  }

  @Test
  public void aSuggestionCannotBeDecidedTwice() throws Exception {
    long id = page("/about", "About", "The original words.");
    suggest(id, "/about", "About", "First suggestion.");
    long proposal = proposals().open(10).get(0).id();

    boss.submitToAndFollow("/admin/content/proposals",
        Map.of("action", "approve", "id", Long.toString(proposal)));
    Browser.Page again = boss.submitToAndFollow("/admin/content/proposals",
        Map.of("action", "decline", "id", Long.toString(proposal)));

    assertTrue(again.contains("already"));
    assertEquals("and it did not apply a second time", 2, versions().history(id).size());
  }

  // ---- who may do what -------------------------------------------------------------------------

  @Test
  public void somebodyWhoMayOnlySuggestCannotSaveByPostingTheOtherAction() throws Exception {
    long id = page("/about", "About", "The original words.");

    LinkedHashMap<String, String> form = contentForm("/about", "About", "Straight to the page.");
    form.put("action", "save");
    form.put("id", Long.toString(id));
    Browser.Page done = writer.submitToAndFollow("/admin/content", form);

    assertTrue(done.body(), done.contains("not able to save"));
    assertEquals("the button they see is a courtesy; this is the rule",
        "The original words.", store().byId(id).body());
  }

  @Test
  public void somebodyWhoMayOnlySuggestCannotApproveTheirOwn() throws Exception {
    long id = page("/about", "About", "The original words.");
    suggest(id, "/about", "About", "Words somebody suggested.");
    long proposal = proposals().open(10).get(0).id();

    Browser.Page done = writer.submitToAndFollow("/admin/content/proposals",
        Map.of("action", "approve", "id", Long.toString(proposal)));
    assertTrue(done.body(), done.contains("not able to approve"));
    assertEquals("The original words.", store().byId(id).body());
  }

  @Test
  public void theEditorOffersSuggestRatherThanSaveToSomebodyWhoCannotPublish() throws Exception {
    long id = page("/about", "About", "The original words.");
    String html = writer.get("/admin/content/edit/" + id).body();

    assertTrue(html.contains("value=\"suggest\""));
    assertFalse("no save button for somebody who cannot save", html.contains("value=\"save\""));
    assertTrue(html.contains("nothing goes live until"));
  }

  @Test
  public void anAdminGetsBothButtons() throws Exception {
    long id = page("/about", "About", "The original words.");
    String html = boss.get("/admin/content/edit/" + id).body();
    assertTrue(html.contains("value=\"save\""));
    assertTrue("an admin can still suggest, which is how a second pair of eyes gets asked for",
        html.contains("value=\"suggest\""));
  }

  // ---- the page moving underneath --------------------------------------------------------------

  @Test
  public void aSuggestionWrittenAgainstAnOlderPageIsFlaggedRatherThanBlocked() throws Exception {
    long id = page("/about", "About", "The original words.");
    suggest(id, "/about", "About", "Words somebody suggested.");
    long proposal = proposals().open(10).get(0).id();
    assertFalse(proposals().isStale(proposals().byId(proposal)));

    // somebody else edits the page in the meantime
    edit(id, "/about", "About", "Words the editor wrote.");

    assertTrue("the reviewer is the only one who can tell whether these conflict",
        proposals().isStale(proposals().byId(proposal)));
    assertTrue("and the screen says so", boss.get("/admin/content/proposals")
        .contains("page moved since"));
    assertEquals("but it is still approvable", Proposals.State.open,
        proposals().byId(proposal).state());
  }

  @Test
  public void theQueueIsOldestFirst() throws Exception {
    long id = page("/about", "About", "The original words.");
    suggest(id, "/about", "About", "First.");
    suggest(id, "/about", "About", "Second.");
    assertTrue("a queue people jump is a queue nobody trusts",
        proposals().open(10).get(0).document().contains("First."));
  }

  @Test
  public void theChangesViewShowsWhatWouldChange() throws Exception {
    long id = page("/about", "About", "The original words.");
    suggest(id, "/about", "About", "Words somebody suggested.");
    long proposal = proposals().open(10).get(0).id();

    Browser.Page page = boss.get("/admin/content/proposals/changes/" + proposal);
    assertEquals(200, page.status());
    assertTrue(page.contains("Words somebody suggested."));
    assertTrue("both sides, because the removed line is the point of looking",
        page.contains("The original words."));
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private io.hearth.auth.Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  private ContentStore store() {
    return accounts().site.store();
  }

  private Proposals proposals() {
    return store().proposals();
  }

  private ContentVersions versions() {
    return store().versions();
  }

  private static LinkedHashMap<String, String> contentForm(String uri, String title, String body) {
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("kind", "markdown");
    form.put("template_name", "");
    form.put("published", "on");
    form.put("uri", uri);
    form.put("title", title);
    form.put("body", body);
    return form;
  }

  private long page(String uri, String title, String body) throws Exception {
    LinkedHashMap<String, String> form = contentForm(uri, title, body);
    form.put("action", "save");
    boss.submitToAndFollow("/admin/content", form);
    return store().byUri(uri).id();
  }

  private void edit(long id, String uri, String title, String body) throws Exception {
    LinkedHashMap<String, String> form = contentForm(uri, title, body);
    form.put("action", "save");
    form.put("id", Long.toString(id));
    boss.submitToAndFollow("/admin/content", form);
  }

  private void suggest(long id, String uri, String title, String body) throws Exception {
    LinkedHashMap<String, String> form = contentForm(uri, title, body);
    form.put("action", "suggest");
    form.put("id", Long.toString(id));
    Browser.Page done = writer.submitToAndFollow("/admin/content", form);
    assertNotNull(done);
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
