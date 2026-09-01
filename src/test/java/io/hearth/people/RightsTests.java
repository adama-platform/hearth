package io.hearth.people;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The two rights this software's own privacy policy grants, exercised end to end.
 *
 * <b>Every community running this publishes that policy under its own name, with a regulator named
 * two paragraphs below.</b> It says: show you what we hold, give you a copy in a portable form,
 * delete your account and what is attached to it — and until these existed, none of it had a
 * mechanism. An administrator answering a request would have needed a SQL client and an afternoon,
 * and the "delete" they performed left the person's email address in four other tables. The operator
 * is the one who is liable for that, not the software, which is why this is a defect rather than a
 * missing feature.
 *
 * The searching part of this file is the last test: it goes to the database itself and looks for the
 * address, in every table, after an erasure. A promise about deletion is only worth what a `SELECT`
 * says afterwards.
 */
public class RightsTests {
  private static final ObjectMapper JSON = new ObjectMapper();

  private Configs configs;
  private TestServer server;
  private Browser admin;
  private Browser ana;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signedIn("boss@example.com", "The Boss");
    ana = approved("ana@example.com", "Ana Rivera");
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
    browser.submitTo("/welcome", Map.of("action", "name", "display_name", name,
        "location", "Austin", "about", "I make bread."));
    browser.get("/welcome?step=3");
    return browser;
  }

  private Browser approved(String email, String name) throws Exception {
    Browser member = signedIn(email, name);
    admin.get("/admin/people");
    admin.submitTo("/admin/people",
        Map.of("action", "approve", "user", Long.toString(idOf(email))));
    return member;
  }

  private long idOf(String email) throws Exception {
    return server.auth.forDomain("example.org").users.byEmail(email).id();
  }


  // ---- a copy of it ----------------------------------------------------------------------------


  @Test
  public void theExportIsTheirsAndNobodyElsesToAsk() throws Exception {
    Browser bo = approved("bo@example.com", "Bo Chen");
    // the only id it takes is the session's, so there is nothing to point at somebody else
    JsonNode theirs = JSON.readTree(bo.get("/self?tab=data&download=export").body());
    assertEquals("bo@example.com", theirs.path("about").asText());

    // and an administrator can answer a request on somebody's behalf, from the people section
    JsonNode onBehalf = JSON.readTree(
        admin.get("/admin/people/export/" + idOf("ana@example.com")).body());
    assertEquals("ana@example.com", onBehalf.path("about").asText());

    assertEquals("but not a member who is not an administrator", 404,
        bo.get("/admin/people/export/" + idOf("ana@example.com")).status());
  }

  // ---- and getting rid of it -------------------------------------------------------------------




  @Test
  public void aMisclickCannotEndSomebodysMembership() throws Exception {
    long anaId = idOf("ana@example.com");
    ana.get("/self?tab=data");
    Browser.Page refused = ana.submitTo("/self", Map.of("action", "leave", "confirm", ""));
    assertEquals(400, refused.status());
    assertTrue(refused.contains("Type 'delete'"));
    assertNotNull(server.auth.forDomain("example.org").users.byId(anaId));

    admin.get("/admin/people/review/" + anaId);
    Browser.Page alsoRefused = admin.submitToAndFollow("/admin/people",
        Map.of("action", "erase", "user", Long.toString(anaId), "confirm", "yes"));
    assertTrue(alsoRefused.contains("Type 'delete'"));
    assertNotNull(server.auth.forDomain("example.org").users.byId(anaId));
  }

  @Test
  public void aBanOutlivesTheAccountItRemoved() throws Exception {
    long anaId = idOf("ana@example.com");
    admin.get("/admin/bans");
    admin.submitToAndFollow("/admin/bans",
        Map.of("action", "ban", "email", "ana@example.com", "reason", "kept shouting"));

    assertNull("the account went with it",
        server.auth.forDomain("example.org").users.byId(anaId));
    assertTrue("but the ban is the one thing that has to stay",
        server.auth.forDomain("example.org").bans.isBanned("ana@example.com"));
  }

  @Test
  public void anAdminNamedInTheConfigCannotDeleteThemselvesIntoALockedOutCommunity() throws Exception {
    admin.get("/self?tab=data");
    Browser.Page refused = admin.submitTo("/self",
        Map.of("action", "leave", "confirm", "delete"));
    assertEquals(400, refused.status());
    assertTrue(refused.contains("configuration file"));
    assertNotNull(server.auth.forDomain("example.org").users.byEmail("boss@example.com"));
  }

  // ---- how long things are kept ------------------------------------------------------------------

  @Test
  public void theSignUpAddressIsForgottenOnItsOwn() throws Exception {
    long anaId = idOf("ana@example.com");
    assertNotNull("recorded when they joined",
        server.auth.forDomain("example.org").users.byId(anaId).signupIp());

    // ninety days later, on the sweep that runs anyway
    int forgotten = server.auth.forDomain("example.org").users
        .forgetOldSignupIps(System.currentTimeMillis() + 1000);
    assertTrue(forgotten >= 1);
    assertNull(server.auth.forDomain("example.org").users.byId(anaId).signupIp());
  }

  /**
   * Every row in the database that mentions this string, anywhere.
   *
   * The blunt instrument on purpose: an erasure is a claim about the whole file, and checking the
   * tables somebody remembered to check is how four of them got missed the first time.
   */
  private java.util.List<String> rowsMentioning(String needle) throws Exception {
    java.util.ArrayList<String> found = new java.util.ArrayList<>();
    try (Connection connection = server.auth.forDomain("example.org").store.connection();
         Statement statement = connection.createStatement()) {
      java.util.ArrayList<String> tables = new java.util.ArrayList<>();
      try (ResultSet rows = statement.executeQuery(
          "SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'")) {
        while (rows.next()) {
          tables.add(rows.getString(1));
        }
      }
      for (String table : tables) {
        try (Statement each = connection.createStatement();
             ResultSet rows = each.executeQuery("SELECT * FROM " + table)) {
          int columns = rows.getMetaData().getColumnCount();
          while (rows.next()) {
            for (int k = 1; k <= columns; k++) {
              Object value = rows.getObject(k);
              if (value != null && String.valueOf(value).contains(needle)) {
                found.add(table + "." + rows.getMetaData().getColumnName(k));
              }
            }
          }
        }
      }
    }
    return found;
  }
}
