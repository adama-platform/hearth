package io.hearth.mail;

import io.hearth.theme.Theme;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The one shape every message has.
 *
 * Email is the place where a mistake is invisible until somebody tells you: nothing throws, the
 * message arrives, and it is simply wrong in one client. So the pieces that exist because of a
 * specific client bug -- the table-cell button, the inline styles, the preheader, the text half --
 * are asserted rather than assumed.
 */
public class MailLayoutTests {
  private static final MailBrand BRAND = MailBrand.standard("example.org", "Example");

  /** an envelope carrying a brand and no community wording, which is what a fresh install has */
  private static Mailer.Envelope to(MailBrand brand) {
    return new Mailer.Envelope("example.org", "Example", "somebody@example.org", "127.0.0.1",
        brand);
  }

  @Test
  public void theShapeIsTheOneEmailClientsCanRender() {
    String html = new MailLayout(BRAND, "Hello", "the preheader")
        .because("you asked")
        .lead("Lead.")
        .paragraph("A paragraph.")
        .button("Press", "https://example.org/x")
        .html(null);
    assertTrue("outlook renders through word: tables, not divs", html.contains("role=\"presentation\""));
    assertTrue("gmail discards a stylesheet", html.contains("style=\""));
    assertFalse("so there had better not be one", html.contains("</style>"));
    assertTrue("a styled anchor loses its background; a cell does not",
        html.contains("bgcolor=\""));
    assertTrue("the grey line beside the subject", html.contains("the preheader"));
    assertTrue("and a client that inverts should know we thought about it",
        html.contains("name=\"color-scheme\""));
  }

  @Test
  public void aBlankPreheaderIsNoPreheaderRatherThanAnEmptyOne() {
    assertFalse(new MailLayout(BRAND, "Hello", "  ").lead("x").html(null)
        .contains("max-height:0"));
    assertFalse(new MailLayout(BRAND, "Hello", null).lead("x").html(null)
        .contains("max-height:0"));
  }

  @Test
  public void aPixelIsLastAndOnlyWhenThereIsOne() {
    assertFalse(new MailLayout(BRAND, "x", null).lead("x").html(null).contains("<img"));
    assertFalse(new MailLayout(BRAND, "x", null).lead("x").html("  ").contains("<img"));
    String html = new MailLayout(BRAND, "x", null).lead("x")
        .html("https://example.org/p/abc.gif");
    assertTrue(html.contains("width=\"1\" height=\"1\" alt=\"\""));
    assertTrue("after everything that matters has already rendered",
        html.indexOf("<img") > html.indexOf("means you accept"));
  }

  @Test
  public void aFooterWithNoReasonStillSaysWhatMatters() {
    String html = new MailLayout(BRAND, "x", null).lead("x").html(null);
    assertTrue(html.contains("legal/terms-of-service"));
    assertTrue(html.contains("legal/privacy-policy"));
  }

  @Test
  public void newlinesBecomeBreaksInTheBlocksThatTakeProse() {
    assertTrue(new MailLayout(BRAND, "x", null).paragraph("one\ntwo").html(null)
        .contains("one<br>two"));
    assertTrue(new MailLayout(BRAND, "x", null).note("one\ntwo").html(null)
        .contains("one<br>two"));
  }

  @Test
  public void aQuoteAndAListBothRender() {
    assertTrue(new MailLayout(BRAND, "x", null).quote("their words").html(null)
        .contains("border-left:3px solid"));
    String list = new MailLayout(BRAND, "x", null)
        .items(java.util.List.of("ana replied", "bo posted")).html(null);
    assertTrue(list.contains("ana replied"));
    assertTrue(list.contains("bo posted"));
    assertFalse("an empty list is an empty table, not a crash",
        new MailLayout(BRAND, "x", null).items(java.util.List.of()).html(null).isEmpty());
  }

  @Test
  public void everythingSomebodyTypedIsEscapedIncludingQuotes() {
    // half of this lands inside an attribute, so the quote characters matter as much as the
    // angle brackets do
    assertEquals("&amp;&lt;&gt;&quot;&#39;", MailLayout.esc("&<>\"'"));
    assertEquals("", MailLayout.esc(null));
    assertTrue(new MailLayout(BRAND, "x", null).linkAsText("https://example.org/a?b=1&c=2")
        .html(null).contains("b=1&amp;c=2"));
  }

  @Test
  public void theCodeBoxIsMonospacedAndSelectable() {
    String html = new MailLayout(BRAND, "x", null).code("482913").html(null);
    assertTrue(html.contains("482913"));
    assertTrue("read off one screen and typed into another", html.contains("letter-spacing"));
    assertTrue("one tap should select the whole thing", html.contains("user-select:all"));
  }

  @Test
  public void allThreeInvitationsRenderOnTheSameLayout() throws Exception {
    // the invitation used to build its own document, which is how it would have become the one
    // message that ignored a community's colours
    for (InviteMail.Touch touch : InviteMail.Touch.values()) {
      InviteMail.Invitation invitation = new InviteMail.Invitation("Example", "example.org",
          touch, "https://example.org/register?invite=abc", "https://example.org/p/abc.gif",
          "Ana suggested I ask you", "ana@example.com",
          io.hearth.people.InviteConfig.defaults());
      String html = InviteMail.html(BRAND, invitation);
      assertTrue(touch.name(), html.contains("legal/terms-of-service"));
      assertTrue(touch.name(), html.contains("Ana suggested I ask you"));
      assertTrue(touch.name(), html.contains("<img"));
      String text = InviteMail.text(BRAND, invitation);
      assertFalse(touch.name(), text.contains("<"));
      assertTrue(touch.name(), text.contains("legal/privacy-policy"));
      assertTrue("and the footer explains an invitation, which needs explaining",
          text.contains("invited this address"));
    }
  }

  @Test
  public void anInvitationWithNothingOptionalFilledInStillReads() throws Exception {
    io.hearth.people.InviteConfig bare = new io.hearth.people.InviteConfig(
        new io.hearth.common.ConfigObject(
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("tagline", "").put("about", "").put("sign-off", ""), "invites"));
    InviteMail.Invitation invitation = new InviteMail.Invitation("Example", "example.org",
        InviteMail.Touch.welcome, "https://example.org/r?invite=abc", null, null, null, bare);
    String html = InviteMail.html(BRAND, invitation);
    assertTrue(html.contains("Somebody invited you"));
    assertFalse("no pixel, no image", html.contains("<img"));
    assertTrue(InviteMail.text(BRAND, invitation).contains("https://example.org/r?invite=abc"));
  }

  @Test
  public void everyFlowSurvivesTheThingsThatCanBeMissing() {
    // each of these is a real shape: a reset with no link, a notice with nothing quotable, a
    // digest with a settings link and one without
    assertTrue(Messages.passwordReset(to(BRAND), "1", "", 10).text().contains("123456".substring(0, 1)));
    assertFalse(Messages.passwordReset(to(BRAND), "1", "  ", 10).text().contains("Or open"));
    assertTrue(Messages.passwordReset(to(BRAND), "1", "https://example.org/x", 10).text()
        .contains("Or open"));
    assertFalse(Messages.boardNotice(to(BRAND),
        new Mailer.Notice("replied", "ana", null, "https://example.org/b")).html()
        .contains("border-left:3px solid"));
    assertTrue(Messages.boardNotice(to(BRAND),
        new Mailer.Notice("replied", "ana", "the words", "https://example.org/b")).text()
        .contains("the words"));
    assertTrue(Messages.digest(to(BRAND), new Mailer.Digest("this week",
        java.util.List.of(new Mailer.Notice("posted", "ana", "", "x")),
        "https://example.org/board", "/self?tab=notifications")).text()
        .contains("Change how often"));
  }

  @Test
  public void aBrandKnowsWhereEverythingIs() {
    assertEquals("https://example.org/legal/terms-of-service", BRAND.termsUrl());
    assertEquals("https://example.org/anything", BRAND.url("/anything"));
    assertEquals("Example", BRAND.nameOr());
    assertEquals("a community with no name at all is its address", "example.org",
        new MailBrand("example.org", null, Theme.SITE_LIGHT).nameOr());
  }

  @Test
  public void aPaletteWithNonsenseInItStillProducesAMessage() {
    // the palette is validated on the way in, but this runs after a database read and an email
    // that throws is an email nobody gets
    MailBrand odd = new MailBrand("example.org", "Example",
        new Theme.Palette("nonsense", null, "", "#fff", "#6b6b6b", "#e3e3e0"));
    assertTrue(Messages.loginCode(to(odd), "1", 10).html().contains("</html>"));
  }
}
