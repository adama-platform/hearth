package io.hearth.mail;

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
 * Every message this server sends, in the community's own words.
 *
 * The two things worth proving here are the two that were wrong before this existed. One: the
 * wording that ships is not written into the database, so a community that never opens the screen
 * keeps getting the improvements. Two: what an administrator types actually reaches the message --
 * an editing screen whose output nothing reads is a setting somebody believes in and does not have.
 */
public class SystemTemplateTests {
  private Configs configs;
  private TestServer server;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
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
  public void aCommunityThatHasWrittenNothingStillSendsGoodMessages() throws Exception {
    assertEquals("nothing in the table until somebody changes something", 0, overrides());
    Messages.Built built = Messages.loginCode(envelope(), "123456", 10);
    assertEquals("Your Example Community sign-in code", built.subject());
    assertTrue(built.text().contains("Somebody asked to sign in to Example Community."));
    assertTrue("and the values are in it rather than the machinery",
        built.text().contains("good for 10 minutes"));
    assertFalse(built.html().contains("{{"));
  }

  @Test
  public void whatAnAdminWritesIsWhatGoesOut() throws Exception {
    Browser admin = signIn("boss@example.com");
    admin.submitToAndFollow("/admin/messages", Map.of("action", "save", "slug", "login_code",
        "subject", "Come on in to {{community}}", "lead", "Here is the key to {{domain}}.",
        "body", "It lasts {{minutes}} minutes."));

    Messages.Built built = Messages.loginCode(envelope(), "123456", 10);
    assertEquals("Come on in to Example Community", built.subject());
    assertTrue(built.text().contains("Here is the key to example.org."));
    assertTrue(built.text().contains("It lasts 10 minutes."));
    assertTrue("the shape around the words is still the software's",
        built.text().contains("  123456"));
    assertTrue("including the footer, which is not optional",
        built.text().contains("/legal/terms-of-service"));
  }

  @Test
  public void aParameterThatDoesNotExistBecomesNothingRatherThanShowing() throws Exception {
    Browser admin = signIn("boss@example.com");
    admin.submitToAndFollow("/admin/messages", Map.of("action", "save", "slug", "password_changed",
        "subject", "Changed at {{community}}", "lead", "Hello {{first_name}}.", "body", ""));
    Messages.Built built = Messages.passwordChanged(envelope());
    assertTrue(built.text().startsWith("Hello ."));
    assertFalse("a message with the braces showing is worse than one with a hole in it",
        built.text().contains("{{first_name}}"));
  }

  @Test
  public void savingTheShippedWordingBackTakesTheOverrideAway() throws Exception {
    Browser admin = signIn("boss@example.com");
    admin.submitToAndFollow("/admin/messages", Map.of("action", "save", "slug", "two_factor",
        "subject", "Ours", "lead", "x", "body", ""));
    assertEquals(1, overrides());

    Browser.Page done = admin.submitToAndFollow("/admin/messages",
        Map.of("action", "save", "slug", "two_factor",
            "subject", SystemTemplate.two_factor.subject,
            "lead", SystemTemplate.two_factor.lead,
            "body", SystemTemplate.two_factor.body));
    assertTrue(done.contains("the override was removed"));
    assertEquals("so it goes on improving with the software", 0, overrides());
  }

  @Test
  public void resettingPutsTheShippedWordingBack() throws Exception {
    Browser admin = signIn("boss@example.com");
    admin.submitToAndFollow("/admin/messages", Map.of("action", "save", "slug", "digest",
        "subject", "Ours", "lead", "x", "body", ""));
    assertTrue(admin.get("/admin/messages").contains("yours"));

    admin.submitToAndFollow("/admin/messages", Map.of("action", "reset", "slug", "digest"));
    assertEquals(0, overrides());
    assertEquals("4 new on Example Community today",
        Messages.digest(envelope(), new Mailer.Digest("today",
            java.util.List.of(new Mailer.Notice("posted", "ana", "", "x"),
                new Mailer.Notice("posted", "bo", "", "x"),
                new Mailer.Notice("posted", "cy", "", "x"),
                new Mailer.Notice("posted", "di", "", "x")),
            "https://example.org/board", null)).subject());
  }

  @Test
  public void aMessageWithNoSubjectIsRefused() throws Exception {
    // the subject is the only part a mail client is guaranteed to show, and a blank one reads as
    // spam to a person and to a filter
    Browser admin = signIn("boss@example.com");
    assertTrue(admin.submitToAndFollow("/admin/messages",
        Map.of("action", "save", "slug", "board_notice", "subject", "  ", "lead", "x", "body", ""))
        .contains("needs a subject"));
    assertEquals(0, overrides());
  }

  @Test
  public void somethingThisServerDoesNotSendIsRefused() throws Exception {
    Browser admin = signIn("boss@example.com");
    assertTrue(admin.submitToAndFollow("/admin/messages",
        Map.of("action", "save", "slug", "shipping_notice", "subject", "x", "lead", "", "body", ""))
        .contains("not a message this server sends"));
    assertTrue(admin.submitToAndFollow("/admin/messages",
        Map.of("action", "burn", "slug", "digest")).contains("not something this page can do"));
  }

  @Test
  public void theEditorShowsWhatIsBeingSentAndWhatCanGoInIt() throws Exception {
    Browser admin = signIn("boss@example.com");
    Browser.Page form = admin.get("/admin/messages/edit/event_invite");
    assertEquals(200, form.status());
    assertTrue("pre-filled with the wording rather than an empty box",
        form.contains("You are invited to"));
    assertTrue("the parameters this flow has", form.contains("{{where}}"));
    assertTrue("and the ones every flow has", form.contains("{{community}}"));
    assertFalse("but not one belonging to a different flow", form.contains("{{excerpt}}"));
    assertTrue("the preview has believable values in it rather than the braces",
        form.contains("Supper club"));
  }

  @Test
  public void anEditorAskedForAMessageThatIsNotThereLandsOnOne() throws Exception {
    Browser admin = signIn("boss@example.com");
    assertEquals(200, admin.get("/admin/messages/edit/nonsense").status());
  }

  @Test
  public void somebodyWithoutThePermissionSeesNoDoorAtAll() throws Exception {
    Browser member = signIn("member@example.com");
    assertEquals("a 403 would confirm what is behind it", 404,
        member.get("/admin/messages").status());
    assertFalse(member.get("/admin").contains("/admin/messages"));
  }

  @Test
  public void everyFlowIsOnTheScreenAndEveryParameterItNamesIsFillable() throws Exception {
    // a flow missing from the listing is a message nobody can change; a parameter declared and
    // never supplied is a hole in a message with nothing to notice it
    Browser admin = signIn("boss@example.com");
    String page = admin.get("/admin/messages").body();
    for (SystemTemplate template : SystemTemplate.values()) {
      assertTrue(template.name(), page.contains("/admin/messages/edit/" + template.name()));
      assertTrue(template.name() + " has to name what it uses",
          template.availableParameters().containsAll(used(template)));
    }
  }

  /** every `{{name}}` in a flow's shipped wording */
  private static java.util.List<String> used(SystemTemplate template) {
    java.util.ArrayList<String> names = new java.util.ArrayList<>();
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{\\{([a-z_]+)}}")
        .matcher(template.subject + " " + template.lead + " " + template.body);
    while (matcher.find()) {
      names.add(matcher.group(1));
    }
    return names;
  }

  @Test
  public void fillingIsAReplaceAndNotATemplateEngine() {
    // what an administrator types must never be something the server evaluates
    Map<String, String> values = Map.of("community", "Example");
    assertEquals("Example", SystemTemplates.fill("{{community}}", values));
    assertEquals("a section is not a thing", "", SystemTemplates.fill("{{#community}}x{{/community}}",
        values).replace("x", ""));
    assertEquals("an unclosed brace is text", "{{community",
        SystemTemplates.fill("{{community", values));
    assertEquals("", SystemTemplates.fill(null, values));
  }

  private Mailer.Envelope envelope() throws Exception {
    return Mailer.Envelope.to(server.tree.resolve("example.org"),
        server.auth.forDomain("example.org"), "somebody@example.org", null);
  }

  /** how many rows the table holds, which is how many things somebody has changed */
  private int overrides() throws Exception {
    try (java.sql.Connection connection =
             server.auth.forDomain("example.org").store.connection();
         java.sql.Statement statement = connection.createStatement();
         java.sql.ResultSet rows = statement.executeQuery(
             "SELECT COUNT(*) FROM " + io.hearth.store.Schema.SYSTEM_TEMPLATES)) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
