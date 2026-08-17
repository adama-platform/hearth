package io.hearth.auth;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The two escalations the sections added since the last review opened, proved from outside.
 *
 * Both are the same shape as the first escalation this project found, and both were invisible for
 * the same reason: a
 * permission that reads like a small job reaching a button that is not one.
 */
public class EscalationMoreTests {
  private Configs configs;
  private TestServer server;
  private Browser boss;
  private Browser mild;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    boss = signIn("boss@example.com");
    mild = signIn("mild@example.com");
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
    return server.auth.forDomain("example.org");
  }

  private void give(String email, String role, Permission... permissions) throws Exception {
    accounts().roleDefs.save(role, role, "", EnumSet.copyOf(java.util.List.of(permissions)),
        "blue", null);
    accounts().roles.grant(accounts().users.byEmail(email).id(), role, null);
  }

  @Test
  public void decidingOnSuggestionsDoesNotLetYouDeleteTheCalendar() throws Exception {
    // /admin/calendar/suggestions opens for calendar_review -- "decide what members put forward".
    // Every button on both event screens posts under it, so a handler checking only the section
    // would have let a reviewer delete an event and everybody's answers with it.
    boss.submitToAndFollow("/admin/calendar", Map.of("action", "save", "title", "Supper club",
        "starts_on", "2030-04-09", "published", "on"));
    long id = accounts().calendar.all(10).get(0).id();
    give("mild@example.com", "reviewer", Permission.calendar_review);

    assertEquals("the queue itself is theirs", 200,
        mild.get("/admin/calendar/suggestions").status());

    mild.submitToAndFollow("/admin/calendar/suggestions",
        Map.of("action", "delete", "id", Long.toString(id)));
    assertNotNull("deciding is not deleting", accounts().calendar.byId(id));

    mild.submitToAndFollow("/admin/calendar/suggestions",
        Map.of("action", "cancel", "id", Long.toString(id)));
    assertFalse("nor calling something off", accounts().calendar.byId(id).cancelled());

    mild.submitToAndFollow("/admin/calendar/suggestions",
        Map.of("action", "save", "id", Long.toString(id), "title", "Renamed",
            "starts_on", "2030-04-09"));
    assertEquals("nor rewriting it", "Supper club", accounts().calendar.byId(id).title());
  }

  @Test
  public void aReviewerCanStillDoTheirJob() throws Exception {
    Browser ana = signIn("ana@example.com");
    boss.submitToAndFollow("/admin/people", Map.of("action", "approve",
        "user", Long.toString(accounts().users.byEmail("ana@example.com").id())));
    ana.submitToAndFollow("/events", Map.of("action", "suggest", "title", "Karaoke",
        "starts_on", "2030-04-09"));
    long id = accounts().calendar.suggestions(10).get(0).id();
    give("mild@example.com", "reviewer", Permission.calendar_review);

    mild.submitToAndFollow("/admin/calendar/suggestions",
        Map.of("action", "accept", "id", Long.toString(id)));
    assertTrue(accounts().calendar.byId(id).live());
  }

  @Test
  public void nobodyCanGiveAwayAPowerTheyDoNotHave() throws Exception {
    // people_roles was the whole server by a longer route: invent a role holding everything except
    // the word `everything`, grant it to yourself, and you are an administrator in all but name.
    give("mild@example.com", "granter", Permission.people_roles);

    mild.submitToAndFollow("/admin/roles", Map.of("action", "save", "name", "sneaky",
        "label", "Sneaky", "p_content_write", "1", "p_places_write", "1"));
    assertEquals("the role is not created at all", null, accounts().roleDefs.byName("sneaky"));

    // and what they do hold, they can still delegate -- otherwise the permission does nothing
    mild.submitToAndFollow("/admin/roles", Map.of("action", "save", "name", "helper",
        "label", "Helper", "p_people_roles", "1"));
    assertNotNull(accounts().roleDefs.byName("helper"));
  }

  @Test
  public void anAdminCanStillGiveAwayAnything() throws Exception {
    boss.submitToAndFollow("/admin/roles", Map.of("action", "save", "name", "editor",
        "label", "Editor", "p_content_write", "1", "p_places_write", "1"));
    assertNotNull(accounts().roleDefs.byName("editor"));
  }

  @Test
  public void theMembersDirectoryIsForMembers() throws Exception {
    // the single most valuable page on this server to somebody who should not have it
    try (io.hearth.testkit.Http http = new io.hearth.testkit.Http()) {
      assertEquals(303, http.get(server.port, "example.org", "/members").status);
      assertEquals(303, http.get(server.port, "example.org", "/members/1").status);
    }
    // signed in but not approved: the community itself is what approval gates
    Browser waiting = signIn("waiting@example.com");
    assertFalse(waiting.get("/members").contains("class=\"members\""));
  }

  @Test
  public void aMemberPageNeverCarriesAnEmailAddress() throws Exception {
    Browser ana = signIn("ana@example.com");
    long id = accounts().users.byEmail("ana@example.com").id();
    boss.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(id)));
    ana.submitToAndFollow("/self", Map.of("action", "profile", "display_name", "Ana",
        "location", "Kansas City", "about", "I bring the bread."));

    Browser.Page directory = ana.get("/members");
    assertTrue(directory.contains("Ana"));
    assertTrue(directory.contains("Kansas City"));
    assertFalse("a member list is the easiest thing in the world to screenshot",
        directory.contains("ana@example.com"));
    assertFalse(ana.get("/members/" + id).contains("ana@example.com"));
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
