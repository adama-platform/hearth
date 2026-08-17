package io.hearth.people;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Who is here, and who may look.
 *
 * The directory is the page most worth getting wrong: it is the most valuable thing on this server
 * to somebody who should not have it, and the easiest to leave open by accident.
 */
public class MembersTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com");
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

  @Test
  public void theDirectoryShowsNameLocationAndTheFirstOfWhatSomebodyWrote() throws Exception {
    Browser ana = member("ana@example.com");
    ana.submitToAndFollow("/self", Map.of("action", "profile", "display_name", "Ana Ruiz",
        "headline", "bakes things", "location", "Kansas City",
        "about", "I have been coming since the second supper club. " + "Lorem ".repeat(80)));

    Browser.Page page = ana.get("/members");
    assertEquals(200, page.status());
    assertTrue(page.contains("Ana Ruiz"));
    assertTrue(page.contains("Kansas City"));
    assertTrue(page.contains("bakes things"));
    assertTrue("the first of what they wrote", page.contains("since the second supper club"));
    assertTrue("and not all of it", page.contains("…"));
    assertFalse("a listing carries no markup somebody else wrote", page.contains("<p>Lorem"));
  }

  @Test
  public void clickingThroughShowsTheWholeThing() throws Exception {
    Browser ana = member("ana@example.com");
    long id = idOf("ana@example.com");
    ana.submitToAndFollow("/self", Map.of("action", "profile", "display_name", "Ana",
        "about", "One. " + "Lorem ".repeat(120) + "The very end."));

    Browser.Page page = ana.get("/members/" + id);
    assertEquals(200, page.status());
    assertTrue(page.contains("The very end."));
  }

  @Test
  public void whatSomebodyWroteIsNeverMarkupOnSomebodyElsesScreen() throws Exception {
    Browser ana = member("ana@example.com");
    long id = idOf("ana@example.com");
    ana.submitToAndFollow("/self", Map.of("action", "profile", "display_name", "Ana",
        "about", "<script>alert(1)</script> hello"));
    Browser bo = member("bo@example.com");
    assertFalse(bo.get("/members/" + id).contains("<script>alert"));
    assertFalse(bo.get("/members").contains("<script>alert"));
  }

  @Test
  public void onlyPeopleWhoAreActuallyMembersAreOnIt() throws Exception {
    member("ana@example.com");
    signIn("newcomer@example.com");
    Browser gone = member("removed@example.com");
    admin.submitToAndFollow("/admin/people",
        Map.of("action", "disable", "user", Long.toString(idOf("removed@example.com"))));

    Browser.Page page = admin.get("/members");
    assertTrue(page.contains("ana"));
    assertFalse("somebody waiting to be let in is not a member yet", page.contains("newcomer"));
    assertFalse("and somebody turned off is not one any more", page.contains("removed"));
  }

  @Test
  public void searchingLooksAtTheNameThePlaceAndTheWords() throws Exception {
    Browser ana = member("ana@example.com");
    ana.submitToAndFollow("/self", Map.of("action", "profile", "display_name", "Ana",
        "location", "Kansas City", "about", "I keep bees."));
    member("bo@example.com");

    assertTrue(admin.get("/members?q=kansas").contains("Ana"));
    assertTrue(admin.get("/members?q=bees").contains("Ana"));
    assertFalse(admin.get("/members?q=kansas").contains(">bo<"));
    assertTrue(admin.get("/members?q=zzzz").contains("Nobody matches that"));
  }

  @Test
  public void somebodyWhoHasWrittenNothingStillHasAPage() throws Exception {
    Browser quiet = member("quiet@example.com");
    long id = idOf("quiet@example.com");
    assertEquals(200, quiet.get("/members/" + id).status());
    assertTrue("called by the part of their address before the @, never the whole thing",
        quiet.get("/members/" + id).contains("quiet"));
    assertFalse(quiet.get("/members/" + id).contains("quiet@example.com"));
  }

  @Test
  public void nobodyByThatNameIsAbsentRatherThanForbidden() throws Exception {
    Browser ana = member("ana@example.com");
    assertEquals(404, ana.get("/members/99999").status());
    assertEquals(404, ana.get("/members/not-a-number").status());
  }

  @Test
  public void theNavigationCallsItYourProfile() throws Exception {
    Browser ana = member("ana@example.com");
    Browser.Page home = ana.get("/");
    assertTrue(home.contains(">Your profile<"));
    assertFalse(home.contains(">Your page<"));
    assertTrue(home.contains(">Members<"));
  }

  @Test
  public void aCommunityCanTurnTheDirectoryOff() throws Exception {
    server.close();
    configs.delete();
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"],\"disabled\":[\"members\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com");
    Browser ana = member("ana@example.com");
    assertFalse(ana.get("/").contains(">Members<"));
    assertFalse(ana.get("/members").contains("class=\"members\""));
  }

  private long idOf(String email) throws Exception {
    return server.auth.forDomain("example.org").users.byEmail(email).id();
  }

  private Browser member(String email) throws Exception {
    Browser browser = signIn(email);
    admin.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(idOf(email))));
    return browser;
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
