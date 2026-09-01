package io.hearth.theme;

import io.hearth.legal.LegalDoc;
import io.hearth.legal.LegalDocs;
import io.hearth.mail.MailBrand;
import io.hearth.mail.MailLayout;
import io.hearth.mail.Mailer;
import io.hearth.mail.Messages;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The two stores that sit in front of a table nobody wants to query per page load, and the message
 * builder that reads from them.
 *
 * Both caches have the same shape and the same reason: a colour is on the critical path of every
 * response and a footer link is on every email. What is worth testing is the edges -- a scope
 * nobody has written, a document somebody emptied, and a value that came back unreadable.
 */
public class ThemeStoreTests {

  /** an envelope carrying a brand and no community wording, which a fresh install has */
  private static Mailer.Envelope to(MailBrand brand) {
    return new Mailer.Envelope(brand.domain(), brand.nameOr(), "somebody@example.org", "127.0.0.1",
        brand);
  }
  private Configs configs;
  private TestServer server;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
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

  private Themes themes() {
    return server.auth.forDomain("example.org").themes;
  }

  private LegalDocs legal() {
    return server.auth.forDomain("example.org").legal;
  }

  // ---- themes -----------------------------------------------------------------------------------

  @Test
  public void aScopeNobodyHasTouchedIsTheDefaultAndSaysSo() {
    assertTrue(themes().of(Theme.Scope.site).isDefault());
    assertTrue(themes().of(Theme.Scope.admin).isDefault());
    assertEquals(Theme.defaultFor(Theme.Scope.site).css(), themes().css(Theme.Scope.site));
  }

  @Test
  public void savingReplacesTheCachedValueInTheSameBreath() throws Exception {
    Theme.Palette light = Theme.SITE_LIGHT.with("accent", "#123456");
    themes().save(new Theme(Theme.Scope.site, light, Theme.SITE_DARK), null);
    assertTrue("no invalidation to wait for; there is one writer",
        themes().css(Theme.Scope.site).contains("#123456"));
    assertFalse(themes().of(Theme.Scope.site).isDefault());

    themes().load();
    assertTrue("and it survives being read back off the disk",
        themes().css(Theme.Scope.site).contains("#123456"));
  }

  @Test
  public void savingTwiceUpdatesRatherThanDuplicates() throws Exception {
    themes().save(new Theme(Theme.Scope.site, Theme.SITE_LIGHT.with("accent", "#111111"),
        Theme.SITE_DARK), 1L);
    themes().save(new Theme(Theme.Scope.site, Theme.SITE_LIGHT.with("accent", "#222222"),
        Theme.SITE_DARK), 1L);
    themes().load();
    assertTrue(themes().css(Theme.Scope.site).contains("#222222"));
    assertFalse(themes().css(Theme.Scope.site).contains("#111111"));
  }

  @Test
  public void resettingDeletesTheRowRatherThanWritingTheDefaultsIntoIt() throws Exception {
    themes().save(new Theme(Theme.Scope.admin, Theme.ADMIN_LIGHT.with("accent", "#654321"),
        Theme.ADMIN_DARK), null);
    themes().reset(Theme.Scope.admin, null);
    assertTrue(themes().of(Theme.Scope.admin).isDefault());
    themes().load();
    assertTrue("which is what makes 'has anybody chosen colours here' answerable",
        themes().of(Theme.Scope.admin).isDefault());
  }

  @Test
  public void aScopeSpeltWrongIsNothingRatherThanAGuess() {
    assertNull(Theme.Scope.of("looks"));
    assertNull(Theme.Scope.of(null));
    assertEquals(Theme.Scope.admin, Theme.Scope.of("  ADMIN "));
  }

  @Test
  public void aPaletteRoundTripsThroughItsJson() {
    Theme theme = new Theme(Theme.Scope.site, Theme.SITE_LIGHT.with("panel", "#fafafa"),
        Theme.SITE_DARK.with("line", "#333333"));
    Theme read = Theme.fromJson(Theme.Scope.site, theme.toJson());
    assertEquals("#fafafa", read.light.panel());
    assertEquals("#333333", read.dark.line());
  }

  @Test
  public void unreadableStoredColoursFallBackWithoutThrowing() {
    assertTrue(Theme.fromJson(Theme.Scope.site, "not json at all").isDefault());
    assertTrue(Theme.fromJson(Theme.Scope.site, "").isDefault());
    assertTrue(Theme.fromJson(Theme.Scope.site, null).isDefault());
    assertTrue(Theme.fromJson(Theme.Scope.admin, "[1,2,3]").isDefault());
  }

  @Test
  public void anUnknownSlotIsAnEmptyStringRatherThanAnException() {
    assertEquals("", Theme.SITE_LIGHT.get("chartreuse"));
    assertEquals("a slot nobody has heard of changes nothing", Theme.SITE_LIGHT,
        Theme.SITE_LIGHT.with("chartreuse", "#000000"));
  }

  @Test
  public void everySlotTheEditorShowsIsOneThePaletteHas() {
    for (java.util.Map<String, Object> row : Theme.defaultFor(Theme.Scope.site).rows()) {
      assertFalse("the editor cannot offer a box that saves nothing",
          Theme.SITE_LIGHT.get(String.valueOf(row.get("slot"))).isEmpty());
      assertNotNull(row.get("hint"));
    }
  }

  @Test
  public void everySlotOfAPaletteFallsBackOnItsOwn() {
    Theme.Palette nonsense = new Theme.Palette("a", "b", "c", "d", "e", "f");
    assertEquals(Theme.SITE_LIGHT, nonsense);
    Theme.Palette half = new Theme.Palette("#123456", "x", null, "", "  ", "#abc");
    assertEquals("#123456", half.accent());
    assertEquals("#aabbcc", half.line());
    assertEquals(Theme.SITE_LIGHT.fg(), half.fg());
    assertEquals(Theme.SITE_LIGHT.bg(), half.bg());
    assertEquals(Theme.SITE_LIGHT.panel(), half.panel());
    assertEquals(Theme.SITE_LIGHT.dim(), half.dim());
  }

  @Test
  public void aStoreNobodyLoadedReadsThroughRatherThanRefusing() throws Exception {
    // a DAO built by hand in a test, or one whose owner forgot to call load(). Reading through
    // means nothing has to remember.
    Themes fresh = new Themes(server.stores.forDomain("example.org"));
    assertTrue(fresh.of(Theme.Scope.site).isDefault());
    themes().save(new Theme(Theme.Scope.site, Theme.SITE_LIGHT.with("accent", "#0f0f0f"),
        Theme.SITE_DARK), null);
    assertTrue("and it sees what is actually there",
        new Themes(server.stores.forDomain("example.org")).css(Theme.Scope.site)
            .contains("#0f0f0f"));

    LegalDocs freshLegal = new LegalDocs(server.stores.forDomain("example.org"));
    assertFalse(freshLegal.of(LegalDoc.terms).overridden());
  }

  // ---- legal ------------------------------------------------------------------------------------

  @Test
  public void bothDocumentsStartAsTheOnesInTheJar() {
    for (LegalDoc doc : LegalDoc.values()) {
      LegalDocs.Text text = legal().of(doc);
      assertFalse(text.overridden());
      assertEquals(doc.standard(), text.markdown());
      assertNull(text.updatedAt());
    }
  }

  @Test
  public void anOverrideIsRememberedWithWhoWroteIt() throws Exception {
    legal().save(LegalDoc.terms, "# Ours", 7L);
    LegalDocs.Text text = legal().of(LegalDoc.terms);
    assertTrue(text.overridden());
    assertEquals(Long.valueOf(7L), text.updatedBy());
    assertNotNull(text.updatedAt());

    legal().save(LegalDoc.terms, "# Ours, again", 7L);
    legal().load();
    assertEquals("# Ours, again", legal().of(LegalDoc.terms).markdown());
  }

  @Test
  public void anOverrideWrittenWithNobodyBehindItIsStillAnOverride() throws Exception {
    // the reminder loop and any future automated writer have no user id; a null there must not be
    // a reason to refuse the write
    legal().save(LegalDoc.privacy, "# Ours", null);
    assertTrue(legal().of(LegalDoc.privacy).overridden());
    assertNull(legal().of(LegalDoc.privacy).updatedBy());
  }

  @Test
  public void anEmptyOrBlankBodyIsARequestForTheDefaultBack() throws Exception {
    legal().save(LegalDoc.terms, "# Ours", null);
    legal().save(LegalDoc.terms, "   ", null);
    assertFalse(legal().of(LegalDoc.terms).overridden());
    legal().save(LegalDoc.privacy, "# Ours", null);
    legal().save(LegalDoc.privacy, null, null);
    assertFalse(legal().of(LegalDoc.privacy).overridden());
  }

  @Test
  public void aSlugNobodyPublishesIsNothing() {
    assertNull(LegalDoc.bySlug("cookie-policy"));
    assertNull(LegalDoc.bySlug(null));
    assertEquals(LegalDoc.privacy, LegalDoc.bySlug("privacy-policy"));
  }

  @Test
  public void theSubstitutionIsLiteralAndSurvivesNulls() {
    assertEquals("Example at example.org",
        LegalDoc.fill("{{community}} at {{domain}}", "Example", "example.org"));
    assertEquals("a community with no name is its address", "example.org at example.org",
        LegalDoc.fill("{{community}} at {{domain}}", "  ", "example.org"));
    assertEquals("", LegalDoc.fill(null, "Example", "example.org"));
    assertEquals("nothing here evaluates anything", "{{whatever}}",
        LegalDoc.fill("{{whatever}}", "Example", "example.org"));
  }

  // ---- the brand the mail wears -----------------------------------------------------------------

  @Test
  public void theBrandFollowsWhateverTheCommunityChose() throws Exception {
    themes().save(new Theme(Theme.Scope.site, Theme.SITE_LIGHT.with("accent", "#abcdef"),
        Theme.SITE_DARK), null);
    MailBrand brand = Mailer.Envelope.brandOf(
        server.tree.resolve("example.org"), server.auth.forDomain("example.org"));
    assertEquals("#abcdef", brand.palette().accent());
    assertEquals("https://example.org/legal/terms-of-service", brand.termsUrl());
    assertEquals("https://example.org/legal/privacy-policy", brand.privacyUrl());
    assertEquals("https://example.org/", brand.siteUrl());
  }

  @Test
  public void withNoCommunityToAskTheDefaultsAreTheHonestAnswer() {
    MailBrand brand = Mailer.Envelope.brandOf(server.tree.resolve("example.org"), null);
    assertEquals(Theme.SITE_LIGHT, brand.palette());
    assertEquals("Example", brand.nameOr());
  }

  @Test
  public void aCommunityWithNoNameIsCalledByItsAddress() {
    assertEquals("example.org", new MailBrand("example.org", "  ", null).nameOr());
    assertEquals("and a null palette is the default rather than a crash",
        Theme.SITE_LIGHT, new MailBrand("example.org", "x", null).palette());
  }

  @Test
  public void aMessageWithNoLinkStillBuilds() {
    MailBrand brand = MailBrand.standard("example.org", "Example");
    String html = Messages.passwordReset(to(brand), "123456", null, 10).html();
    assertFalse("no button when there is nowhere to send them", html.contains("<a href=\"\""));
    assertTrue(html.contains("123456"));
  }



  @Test
  public void theTextFooterSaysTheSameThingWithoutAReason() {
    MailBrand brand = MailBrand.standard("example.org", "Example");
    String foot = MailLayout.textFooter(brand, null);
    assertTrue(foot.contains("https://example.org/legal/terms-of-service"));
    assertFalse("no blank line where a reason would have been", foot.contains("\n\n"));
  }
}
