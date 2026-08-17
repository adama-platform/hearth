package io.hearth.theme;

import io.hearth.mail.MailBrand;
import io.hearth.mail.Mailer;
import io.hearth.mail.Messages;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The colours: chosen once, and then everywhere.
 *
 * "Everywhere" is the part worth testing. A palette that reaches the pages and not the emails is
 * the failure this replaced -- the invitation used to build its own document, so it would have been
 * the one message that ignored whatever a community chose.
 */
public class AppearanceTests {

  /** an envelope carrying a brand and no community wording, which a fresh install has */
  private static Mailer.Envelope to(MailBrand brand) {
    return new Mailer.Envelope(brand.domain(), brand.nameOr(), "somebody@example.org", "127.0.0.1",
        brand);
  }
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

  // ---- the palette on its own -------------------------------------------------------------------

  @Test
  public void onlyAHexColourIsAColour() {
    assertTrue(Theme.isColour("#abc"));
    assertTrue(Theme.isColour("#A1B2C3"));
    assertFalse(Theme.isColour("red"));
    assertFalse(Theme.isColour("#12345"));
    assertFalse(Theme.isColour("#ffffff;}body{display:none"));
    assertFalse(Theme.isColour(null));
  }

  @Test
  public void aShortSpellingBecomesTheLongOne() {
    // so that two ways of writing one colour compare equal, which is what "is this still the
    // default" rests on
    assertEquals("#aabbcc", Theme.normalize("#ABC"));
    assertEquals("#aabbcc", Theme.normalize("#AABBCC"));
  }

  @Test
  public void nonsenseInTheDatabaseIsDecorationRatherThanAnOutage() {
    Theme theme = Theme.fromJson(Theme.Scope.site,
        "{\"light\":{\"accent\":\"red\",\"fg\":\"#112233\"},\"dark\":null}");
    assertEquals("the unreadable slot falls back", Theme.SITE_LIGHT.accent(),
        theme.light.accent());
    assertEquals("and the readable one is kept", "#112233", theme.light.fg());
    assertEquals(Theme.SITE_DARK, theme.dark);
  }

  @Test
  public void nothingButHexEverReachesTheStylesheet() {
    // this is interpolated raw into a <style> block, so a value that could carry a '}' would be
    // somebody else's CSS on every page
    Theme theme = Theme.fromJson(Theme.Scope.site,
        "{\"light\":{\"bg\":\"#fff;}body{display:none}\"}}");
    assertFalse(theme.css().contains("display:none"));
  }

  @Test
  public void semanticColoursAreNotOnOffer() {
    // red means refused and green means it worked; a community that could recolour them could end
    // up with a red "approved"
    assertTrue(Theme.defaultFor(Theme.Scope.site).css().contains("--bad:" + Theme.BAD));
    for (String[] slot : Theme.SLOTS) {
      assertFalse("bad".equals(slot[0]));
      assertFalse("good".equals(slot[0]));
    }
  }

  // ---- through the admin ------------------------------------------------------------------------

  @Test
  public void whatAnAdminPicksShowsUpOnEveryPage() throws Exception {
    assertEquals(200, admin.get("/admin/appearance").status());
    admin.submitToAndFollow("/admin/appearance", Map.of(
        "action", "save", "scope", "site", "light_accent", "#aa0044", "light_bg", "#fffdf7"));

    try (Http http = new Http()) {
      Http.Response home = http.get(server.port, "example.org", "/");
      assertTrue(home.bodyContains("--accent:#aa0044"));
      assertTrue(home.bodyContains("--bg:#fffdf7"));
      assertTrue("and the slots nobody touched keep what they had",
          home.bodyContains("--fg:" + Theme.SITE_LIGHT.fg()));
    }
  }

  @Test
  public void theAdminPaletteIsSeparateAndCarriesTheLegalPages() throws Exception {
    admin.submitToAndFollow("/admin/appearance",
        Map.of("action", "save", "scope", "admin", "light_accent", "#116644"));
    admin.submitToAndFollow("/admin/appearance",
        Map.of("action", "save", "scope", "site", "light_accent", "#aa0044"));

    assertTrue("the admin section wears the administration's colours",
        admin.get("/admin").contains("--accent:#116644"));
    try (Http http = new Http()) {
      assertTrue("and so do the community's promises",
          http.get(server.port, "example.org", "/legal/terms-of-service")
              .bodyContains("--accent:#116644"));
      assertTrue("while the site keeps its own",
          http.get(server.port, "example.org", "/").bodyContains("--accent:#aa0044"));
    }
  }

  @Test
  public void aValueThatIsNotAColourLeavesTheRestAlone() throws Exception {
    admin.submitToAndFollow("/admin/appearance", Map.of(
        "action", "save", "scope", "site", "light_accent", "#aa0044", "light_fg", "chartreuse"));
    try (Http http = new Http()) {
      Http.Response home = http.get(server.port, "example.org", "/");
      assertTrue("the one it could read", home.bodyContains("--accent:#aa0044"));
      assertTrue("and the eleven it could not are not lost", home.bodyContains("--fg:#1a1a1a"));
    }
  }

  @Test
  public void resettingPutsItBack() throws Exception {
    admin.submitToAndFollow("/admin/appearance",
        Map.of("action", "save", "scope", "site", "light_accent", "#aa0044"));
    admin.submitToAndFollow("/admin/appearance", Map.of("action", "reset", "scope", "site"));
    try (Http http = new Http()) {
      assertTrue(http.get(server.port, "example.org", "/")
          .bodyContains("--accent:" + Theme.SITE_LIGHT.accent()));
    }
    assertTrue("and the screen says nobody has chosen anything",
        admin.get("/admin/appearance").contains("using the defaults"));
  }

  @Test
  public void somebodyWithoutThePermissionSeesNoDoorAtAll() throws Exception {
    Browser member = signIn("member@example.com");
    assertEquals(404, member.get("/admin/appearance").status());
  }

  @Test
  public void aPaletteThisServerDoesNotHaveIsRefusedRatherThanCreated() throws Exception {
    Browser.Page done = admin.submitToAndFollow("/admin/appearance",
        Map.of("action", "save", "scope", "wallpaper", "light_accent", "#aa0044"));
    assertTrue(done.contains("not a palette"));
    assertTrue("and nothing was written", admin.get("/admin/appearance")
        .contains("using the defaults"));
  }

  @Test
  public void anActionNobodyListedDoesNothing() throws Exception {
    assertTrue(admin.submitToAndFollow("/admin/appearance",
        Map.of("action", "randomise", "scope", "site")).contains("not something this page can do"));
  }

  @Test
  public void aSaveThatChangesNothingIsStillASave() throws Exception {
    // every picker posts its current value, so the ordinary case is twelve values that already
    // match. It must not read as "nothing valid was sent" and leave the row absent.
    admin.submitToAndFollow("/admin/appearance", Map.of("action", "save", "scope", "site",
        "light_accent", Theme.SITE_LIGHT.accent(), "dark_accent", Theme.SITE_DARK.accent()));
    assertTrue(admin.get("/admin/appearance").contains("using the defaults"));
  }

  @Test
  public void bothPalettesAreOnTheOneScreen() throws Exception {
    Browser.Page page = admin.get("/admin/appearance");
    assertTrue(page.contains("The community"));
    assertTrue(page.contains("The administration"));
    for (String[] slot : Theme.SLOTS) {
      assertTrue(slot[0], page.contains("name=\"light_" + slot[0] + "\""));
      assertTrue(slot[0], page.contains("name=\"dark_" + slot[0] + "\""));
    }
  }

  // ---- and in the mail --------------------------------------------------------------------------

  @Test
  public void everyKindOfMessageWearsTheCommunitysColours() {
    MailBrand brand = new MailBrand("example.org", "Example Community",
        Theme.SITE_LIGHT.with("accent", "#aa0044"));
    for (String html : java.util.List.of(
        Messages.loginCode(to(brand), "123456", 10).html(),
        Messages.registrationCode(to(brand), "123456", 10).html(),
        Messages.passwordReset(to(brand), "123456", "https://example.org/reset", 10).html(),
        Messages.twoFactorCode(to(brand), "123456", 10).html(),
        Messages.passwordChanged(to(brand)).html())) {
      assertTrue("a message that ignored the palette is the one nobody would notice",
          html.contains("#aa0044"));
    }
  }

  @Test
  public void everyKindOfMessageLinksToTheTerms() {
    MailBrand brand = MailBrand.standard("example.org", "Example Community");
    for (Messages.Built built : java.util.List.of(
        Messages.loginCode(to(brand), "123456", 10),
        Messages.registrationCode(to(brand), "123456", 10),
        Messages.passwordReset(to(brand), "123456", "https://example.org/reset", 10),
        Messages.twoFactorCode(to(brand), "123456", 10),
        Messages.passwordChanged(to(brand)),
        Messages.boardNotice(to(brand), new io.hearth.mail.Mailer.Notice("replied", "ana", "hi",
            "https://example.org/board/1")),
        Messages.digest(to(brand), new io.hearth.mail.Mailer.Digest("today",
            java.util.List.of(new io.hearth.mail.Mailer.Notice("posted", "ana", "", "x")),
            "https://example.org/board", null)))) {
      assertTrue("the html half", built.html().contains("https://example.org/legal/terms-of-service"));
      assertTrue("and the text half, which is what a filter reads",
          built.text().contains("https://example.org/legal/terms-of-service"));
      assertTrue("and it says what the link is for",
          built.html().contains("means you accept its"));
    }
  }

  @Test
  public void aPaleAccentGetsDarkTextOnItsButtons() {
    // a community that picks pale yellow should not get white on white, and whoever chose the
    // colour is the last person who would notice
    MailBrand pale = new MailBrand("example.org", "Example",
        Theme.SITE_LIGHT.with("accent", "#ffe680"));
    String html = Messages.passwordReset(to(pale), "1", "https://example.org/x", 10).html();
    assertTrue(html.contains("color:#1a1a1a;text-decoration:none"));

    MailBrand dark = new MailBrand("example.org", "Example",
        Theme.SITE_LIGHT.with("accent", "#101820"));
    assertTrue(Messages.passwordReset(to(dark), "1", "https://example.org/x", 10).html()
        .contains("color:#ffffff;text-decoration:none"));
  }

  @Test
  public void whatSomebodyTypedIsStillEscapedInAMessage() {
    MailBrand brand = MailBrand.standard("example.org", "Example \"quoted\" & <script>");
    String html = Messages.loginCode(to(brand), "1", 10).html();
    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("&lt;script&gt;"));
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
