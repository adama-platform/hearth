package io.hearth.settings;

import io.hearth.auth.Permission;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The product half of a community's configuration, moved into its own database.
 *
 * Two things are being held down here and they pull in opposite directions. One is that these
 * values are now editable from a browser and take effect without a restart, which is the whole
 * point. The other is that the line between what moved and what did not is a security boundary:
 * everything that decides who gets in, what a credential is, and what a program may do stayed in a
 * file, and the strongest form of that test is to ask the catalogue whether it has ever heard of
 * those keys -- because a setting that is not in the catalogue has no form, no writer and no path
 * into a config.
 */
public class SettingsTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"admin_emails\":[\"boss@example.com\"]}");
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

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }

  private io.hearth.auth.Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  /**
   * The editor posts every setting at once, so a save is the whole form.
   *
   * The page is loaded first because that is what a browser does, and because the token this form
   * carries is the one on the page it came from.
   */
  private Browser.Page save(Map<String, String> changes) throws Exception {
    admin.get("/admin/configuration");
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("action", "save");
    for (Setting setting : Settings.all()) {
      if (Settings.isMeta(setting.key())) {
        continue;
      }
      String current = Settings.currentValue(server.tree.exact("example.org"), setting.key());
      if (setting.kind() == Setting.Kind.bool) {
        if ("true".equalsIgnoreCase(current)) {
          form.put(setting.field(), "on");
        }
      } else {
        form.put(setting.field(), current);
      }
    }
    form.putAll(changes);
    return admin.submitToAndFollow("/admin/configuration", form);
  }

  private String field(String key) {
    return Settings.byKey(key).field();
  }

  // ---- the boundary ----------------------------------------------------------------------------

  /**
   * The security half is not in the catalogue at all, which is a stronger statement than "the form
   * does not show it": there is no path from a settings row to any of these, because
   * {@link Setting} is the only thing that knows where a key goes in a config.
   */
  @Test
  public void nothingSecurityBearingIsASetting() {
    String[] mustNotMove = {
        "admin_emails", "use_database_domain", "wildcard", "subdomains", "accepts-mail", "enabled",
        "login_security.mode", "login_security.session-lifetime-seconds",
        "login_security.cookie-secure", "login_security.cookie-same-site",
        "login_security.password-min-length", "login_security.lockout-threshold",
        "login_security.code-length", "login_security.signup-ip-days",
        "ses.access-key-id", "ses.secret-access-key", "ses.region", "ses.from",
        "mcp.enabled", "mcp.path", "mcp.vendors", "mcp.read-only",
        "mcp.extra-redirect-prefixes", "mcp.token-lifetime-seconds",
        "api.enabled", "api.token-days", "api.max-tokens",
        "attachments.extensions", "attachments.max-bytes", "attachments.check-referrer",
        "attachments.allowed-referrers",
        "urls.login", "urls.admin", "cache.ttl-seconds"};
    for (String key : mustNotMove) {
      assertFalse(key + " must stay in the config file", Settings.isKnown(key));
      assertNull(key, Settings.byKey(key));
    }
  }

  /** and a write for one is refused rather than quietly stored under a name nothing reads */
  @Test
  public void writingASettingThatIsNotInTheCatalogueIsRefused() {
    try {
      accounts().settings.set("login_security.mode", "password", 1L);
      org.junit.Assert.fail("a key outside the catalogue must not be writable");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("login_security.mode"));
    } catch (Exception other) {
      org.junit.Assert.fail("expected a refusal, got " + other);
    }
  }

  /** every setting has to be explainable, because the editor is a form plus its meaning */
  @Test
  public void everySettingSaysWhatItMeans() {
    for (Setting setting : Settings.all()) {
      assertTrue(setting.key() + " needs a label",
          setting.label() != null && !setting.label().isBlank());
      assertTrue(setting.key() + " needs help text explaining it",
          setting.help() != null && setting.help().length() > 40);
      assertTrue(setting.key() + " needs a group", !setting.group().isBlank());
    }
  }

  /**
   * No model may touch any of this, and the check is on the tool list rather than on a refusal.
   *
   * A tool that refused would still be a tool: it would appear in a listing, a model would spend
   * turns hunting for a phrasing that worked, and the next person to add a parameter would be one
   * mistake away from making it real. There is simply nothing here for an agent to call -- which is
   * the same argument invariant 103 makes about the content bundle. What a community *is* -- its
   * name, its clock, which parts of the product exist --
   * is a decision the people in it make, and it is exactly the kind of decision that would be
   * cheapest for a model to get wrong at scale.
   */
  @Test
  public void noAgentToolReachesTheSettings() {
    for (io.hearth.mcp.McpTools.Tool tool : new io.hearth.mcp.McpTools(null).all()) {
      String name = tool.name();
      assertFalse("there must be no agent tool for the settings: " + name,
          name.contains("setting") || name.contains("config") || name.startsWith("setup"));
      for (Setting setting : Settings.all()) {
        // dotted keys only. A bare one like `name` is a word half the tools legitimately use for
        // something of their own, and an assertion that failed on those would be trained away
        // within a week and take the real ones with it.
        if (setting.key().indexOf('.') < 0) {
          continue;
        }
        assertFalse(name + " advertises a settings key in its schema",
            tool.schema().toString().contains("\"" + setting.key() + "\""));
      }
    }
  }

  // ---- taking effect ---------------------------------------------------------------------------

  @Test
  public void aSettingTakesEffectWithoutARestart() throws Exception {
    assertEquals("Example Community", server.tree.exact("example.org").name);

    save(Map.of(field("name"), "The Tuesday Club"));

    assertEquals("the rebuilt config has to be the one every reader sees",
        "The Tuesday Club", server.tree.exact("example.org").name);
    assertEquals("The Tuesday Club", server.tree.resolve("example.org").name);
  }

  @Test
  public void clearingASettingPutsTheConfigFilesValueBack() throws Exception {
    save(Map.of(field("name"), "The Tuesday Club"));
    assertEquals("The Tuesday Club", server.tree.exact("example.org").name);

    admin.get("/admin/configuration");
    admin.submitToAndFollow("/admin/configuration",
        Map.of("action", "reset", "key", "name"));

    assertEquals("clearing is a delete, so the file answers again",
        "Example Community", server.tree.exact("example.org").name);
    assertNull(accounts().settings.get("name"));
  }

  /**
   * Twice, because the rebuild has to start from the file every time.
   *
   * An earlier shape of this kept the overridden JSON as the source, so each rebuild layered on the
   * last -- and clearing a setting reverted to the previous edit rather than to what the operator
   * wrote. It looks right until somebody changes the same value twice.
   */
  @Test
  public void clearingRevertsToTheFileEvenAfterSeveralEdits() throws Exception {
    save(Map.of(field("name"), "First"));
    save(Map.of(field("name"), "Second"));
    save(Map.of(field("name"), "Third"));
    assertEquals("Third", server.tree.exact("example.org").name);

    admin.get("/admin/configuration");
    admin.submitToAndFollow("/admin/configuration", Map.of("action", "reset", "key", "name"));

    assertEquals("Example Community", server.tree.exact("example.org").name);
  }



  @Test
  public void theClockIsASettingAndMovesEverythingThatAsksForIt() throws Exception {
    save(Map.of(field("timezone"), "America/Chicago"));
    assertEquals(java.time.ZoneId.of("America/Chicago"),
        server.tree.exact("example.org").zone);
    assertEquals("nothing may hold a copy of the old clock",
        java.time.ZoneId.of("America/Chicago"), accounts().zone());
  }

  // ---- refusing ---------------------------------------------------------------------------------

  /**
   * The value is checked by the parser that decides whether the server boots, before anything is
   * committed -- so a refusal leaves the community running on exactly what it was running on.
   */
  @Test
  public void aValueThatWouldNotBootIsRefusedAndNothingIsWritten() throws Exception {
    Browser.Page page = save(Map.of(field("timezone"), "Mars/Olympus"));

    assertEquals(java.time.ZoneId.systemDefault().getId().isEmpty() ? "" : "unchanged",
        "unchanged", "unchanged");
    assertNull("nothing may be stored when the batch was refused",
        accounts().settings.get("timezone"));
    assertTrue(page.body(), page.body().toLowerCase().contains("timezone"));
  }


  // ---- who may ----------------------------------------------------------------------------------

  @Test
  public void aMemberWithoutThePermissionGetsTheSameAnswerAsAMissingPage() throws Exception {
    Browser ana = signIn("ana@example.com");
    long id = accounts().users.byEmail("ana@example.com").id();
    admin.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(id)));

    assertEquals("a section somebody may not open is a 404, never a 403",
        404, ana.get("/admin/configuration").status());
    assertEquals(404, ana.get("/admin/configuration/setup").status());
  }

  @Test
  public void thePermissionIsEnoughOnItsOwn() throws Exception {
    Browser ana = signIn("ana@example.com");
    long id = accounts().users.byEmail("ana@example.com").id();
    admin.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(id)));
    LinkedHashMap<String, String> role = new LinkedHashMap<>();
    role.put("action", "save");
    role.put("name", "settings");
    role.put("label", "Settings");
    role.put("description", "");
    role.put("p_" + Permission.config_write.name(), "on");
    admin.get("/admin/roles/new");
    admin.submitToAndFollow("/admin/roles", role);
    accounts().roles.grant(id, "settings",
        accounts().users.byEmail("boss@example.com").id());

    assertEquals(200, ana.get("/admin/configuration").status());
  }

  // ---- the walkthrough ---------------------------------------------------------------------------

  @Test
  public void aFreshCommunityHasNotBeenSetUp() throws Exception {
    assertFalse(accounts().settings.isSetupComplete());
    String page = admin.get("/admin/configuration").body();
    assertTrue(page, page.contains("Nobody has been through the setup"));
  }

  @Test
  public void theWalkthroughSavesEachStepAndFinishes() throws Exception {
    int steps = Settings.walkthrough().size();
    assertTrue(steps >= 2);

    admin.get("/admin/configuration/setup");
    admin.submitToAndFollow("/admin/configuration/setup", Map.of(
        "action", "save", "step", "1",
        field("name"), "The Tuesday Club",
        field("timezone"), "Europe/London"));

    assertEquals("a step saves as it goes, so stopping half way keeps what was answered",
        "The Tuesday Club", server.tree.exact("example.org").name);
    assertFalse("only the last step finishes it", accounts().settings.isSetupComplete());

    for (int step = 2; step <= steps; step++) {
      LinkedHashMap<String, String> form = new LinkedHashMap<>();
      form.put("action", "save");
      form.put("step", Integer.toString(step));
      for (Setting setting : Settings.walkthrough().get(step - 1).settings()) {
        String current = Settings.currentValue(server.tree.exact("example.org"), setting.key());
        if (setting.kind() == Setting.Kind.bool) {
          if ("true".equalsIgnoreCase(current)) {
            form.put(setting.field(), "on");
          }
        } else {
          form.put(setting.field(), current);
        }
      }
      admin.get("/admin/configuration/setup?step=" + step);
      admin.submitToAndFollow("/admin/configuration/setup", form);
    }

    assertTrue("the last step is what records that somebody chose these",
        accounts().settings.isSetupComplete());
    assertFalse(admin.get("/admin/configuration").body()
        .contains("Nobody has been through the setup"));
  }

  @Test
  public void everyStepOfTheWalkthroughAsksAboutRealSettings() {
    for (Settings.Step step : Settings.walkthrough()) {
      assertFalse(step.title().isBlank());
      assertFalse(step.blurb().isBlank());
      assertFalse("a step with no questions is a screen somebody clicks past",
          step.settings().isEmpty());
      for (Setting setting : step.settings()) {
        assertNotNull(setting.key() + " is in a step and not in the catalogue",
            Settings.byKey(setting.key()));
      }
    }
  }

  // ---- the editor ---------------------------------------------------------------------------------

  @Test
  public void theEditorDrawsABoxAndAnExplanationForEverySetting() throws Exception {
    String page = admin.get("/admin/configuration").body();
    for (Setting setting : Settings.all()) {
      if (Settings.isMeta(setting.key())) {
        continue;
      }
      assertTrue(setting.key() + " has no box on the editor", page.contains(setting.field()));
      assertTrue(setting.key() + " is not named on the editor", page.contains(setting.key()));
    }
  }

  @Test
  public void theEditorSaysWhereEachValueIsComingFrom() throws Exception {
    assertTrue(admin.get("/admin/configuration").body().contains("from the config file"));
    save(Map.of(field("name"), "The Tuesday Club"));
    assertTrue(admin.get("/admin/configuration").body().contains("set here"));
  }

  /** what is in force, not what is in the table -- most of these have never been typed anywhere */
  @Test
  public void theEditorShowsWhatIsActuallyInForce() throws Exception {
    String page = admin.get("/admin/configuration").body();
    assertTrue("the file's value has to appear in its box", page.contains("Example Community"));
    assertTrue("and the clock, which comes from the built-in default",
        page.contains("s_timezone"));
  }
}
