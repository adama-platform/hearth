package io.hearth.web;

import io.hearth.content.Markdown;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The parser, in both directions: what it refuses to let through, and what it takes out.
 *
 * These are the two jobs jsoup does here, and they fail in opposite ways. A cleaner that is too
 * loose is a security hole; a compactor that is too eager is a visible bug in somebody's sentence.
 * Both directions are tested, because either one alone would let the other regress.
 */
public class HtmlTests {
  // ---- cleaning ---------------------------------------------------------------------------------

  @Test
  public void aScriptTagDoesNotSurviveAMemberWritingOne() {
    String cleaned = Html.clean("<p>hello</p><script>alert(1)</script>");
    assertFalse(cleaned.contains("<script"));
    assertTrue("and the paragraph is still there", cleaned.contains("<p>hello</p>"));
  }

  @Test
  public void theWordsSurviveEvenWhenTheMarkupDoesNot() {
    // dropping the text with the tag would mean a post that silently lost half of what somebody
    // typed, which is worse than showing them their own angle brackets
    assertTrue(Html.clean("<marquee>read me</marquee>").contains("read me"));
    assertFalse(Html.clean("<marquee>read me</marquee>").contains("<marquee"));
  }

  @Test
  public void aScriptGoesEntirely() {
    // the one place text does not survive, and rightly: a script's contents are a program, not
    // prose, and somebody writing about code uses a fenced block, which renders as escaped text
    // inside <pre><code> and comes through untouched
    assertFalse(Html.clean("<script>alert(1)</script>").contains("alert(1)"));
    assertTrue(Markdown.toSafeHtml("```\nalert(1)\n```").contains("alert(1)"));
  }

  @Test
  public void anEventHandlerIsNotAnAttribute() {
    String cleaned = Html.clean("<p onclick=\"steal()\">hello</p><b onmouseover=\"x()\">hi</b>");
    assertFalse(cleaned.contains("onclick"));
    assertFalse(cleaned.contains("onmouseover"));
  }

  @Test
  public void aJavascriptUrlIsNotALink() {
    String cleaned = Html.clean("<a href=\"javascript:alert(1)\">press</a>");
    assertFalse(cleaned.contains("javascript:"));
    assertTrue("and the words stay", cleaned.contains("press"));
  }

  @Test
  public void anOrdinaryLinkSurvivesAndSaysWhoWroteIt() {
    String cleaned = Html.clean("<a href=\"https://example.org/x\">there</a>");
    assertTrue(cleaned.contains("https://example.org/x"));
    assertTrue("somebody else's link, and it says so", cleaned.contains("nofollow"));
    assertTrue("and cannot reach back through window.opener", cleaned.contains("noopener"));
  }

  @Test
  public void anImageIsNotSomethingAMemberCanPointAtAnotherServer() {
    // a remote image in a post is a request to somebody else's machine carrying every reader's
    // address, and default-src 'self' would refuse to load it anyway
    assertFalse(Html.clean("<img src=\"https://tracker.example/pixel.gif\">").contains("<img"));
    assertFalse(Html.clean("<img src=x onerror=alert(1)>").contains("onerror"));
  }

  @Test
  public void anIframeAndAFormAreBothGone() {
    String cleaned = Html.clean(
        "<iframe src=\"https://elsewhere\"></iframe><form action=\"https://elsewhere\">"
            + "<input name=\"password\"></form>");
    assertFalse(cleaned.contains("<iframe"));
    assertFalse("an injected form that looks like part of the site is the phishing path",
        cleaned.contains("<form"));
    assertFalse(cleaned.contains("<input"));
  }

  @Test
  public void whatAMemberIsAllowedToWriteStillWorks() {
    String cleaned = Markdown.toSafeHtml("# Heading\n\nSome **bold** and a list:\n\n- one\n- two\n\n"
        + "| a | b |\n|---|---|\n| 1 | 2 |\n");
    assertTrue(cleaned.contains("<h1"));
    assertTrue(cleaned.contains("<strong>bold</strong>"));
    assertTrue(cleaned.contains("<li>one</li>"));
    assertTrue("tables are the feature people notice missing first", cleaned.contains("<table>"));
  }

  @Test
  public void theTrustedRendererStillPassesEverythingThrough() {
    // an operator can replace the whole page with a 'page' kind document, so filtering their
    // markdown would be a lock on a door with no wall around it
    assertTrue(Markdown.toHtml("<script>ok()</script>").contains("<script>"));
  }

  // ---- compacting -------------------------------------------------------------------------------

  @Test
  public void whitespaceBetweenBlocksGoesAway() {
    assertEquals("<p>one</p><p>two</p>", Html.compact("<p>one</p>\n  <p>two</p>\n"));
  }

  @Test
  public void whitespaceBetweenInlineElementsIsAWord() {
    // this is the whole reason a parser is used instead of a regular expression: deleting this one
    // space runs two words together in somebody's sentence
    assertTrue(Html.compact("<p><a>one</a> <a>two</a></p>").contains("</a> <a>"));
    assertTrue(Html.compact("<p><b>one</b>\n<i>two</i></p>").contains("</b> <i>"));
  }

  @Test
  public void aRunOfWhitespaceInsideTextBecomesOneSpace() {
    assertEquals("<p>one two</p>", Html.compact("<p>one   \n   two</p>"));
  }

  @Test
  public void preformattedTextIsLeftExactlyAsItIs() {
    String out = Html.compact("<pre>line one\n    indented\n</pre>");
    assertTrue(out.contains("line one\n    indented"));
  }

  @Test
  public void aWholeDocumentKeepsItsDoctypeAndAFragmentStaysAFragment() {
    assertTrue(Html.compact("<!doctype html><html><body><p>x</p></body></html>")
        .startsWith("<!doctype html>"));
    assertEquals("a refreshable panel is not a document and must not become one",
        "<p class=\"count\">3 shown</p>", Html.compact("<p class=\"count\">3 shown</p>\n"));
  }

  @Test
  public void nothingAtAllIsNothingAtAll() {
    assertEquals("", Html.clean(null));
    assertEquals("", Html.clean(""));
    assertEquals("", Html.compact(""));
    org.junit.Assert.assertNull(Html.compact(null));
  }

  @Test
  public void aTextareaKeepsWhatIsInsideIt() {
    // the editor posts what is between these tags; collapsing it would rewrite somebody's page
    // every time they opened the form
    assertTrue(Html.compact("<textarea>line one\n  line two</textarea>")
        .contains("line one\n  line two"));
  }

  @Test
  public void aSpaceInsideAnInlineElementSurvives() {
    assertEquals("<p><b> one </b>two</p>", Html.compact("<p><b>\n one \n</b>two</p>"));
  }

  @Test
  public void aStyleBlockIsNotTouchedEither() {
    // the palette lives in one of these, and a rewritten stylesheet is every page at once
    assertTrue(Html.compact("<style>:root{--fg:#111}\nbody{margin:0}</style>")
        .contains(":root{--fg:#111}\nbody{margin:0}"));
  }

  @Test
  public void anAttributeValueSurvivesBeingReserialised() {
    // the minted form's blob rides in one of these, and the admin panels' data- attributes do too
    // jsoup normalises a single-quoted attribute to a double-quoted one and escapes the quotes
    // inside it, which is the same value: the browser's parser decodes it on the way in. That is
    // exactly why invariant 22 puts configuration in an attribute rather than in a script.
    String out = Html.compact("<div data-config='{\"a\":1,\"b\":\"x&y\"}'>hi</div>");
    assertTrue(out, out.contains("&amp;y"));
    assertTrue(out, out.contains("&quot;a&quot;:1"));
  }

  @Test
  public void aDocumentIsRecognisedHoweverItIsSpelt() {
    assertTrue(Html.compact("<HTML><body><p>x</p></body></HTML>").startsWith("<html>"));
    assertTrue(Html.compact("\n  <!DOCTYPE HTML><html><body><p>x</p></body></html>")
        .startsWith("<!doctype"));
  }

  @Test
  public void aFragmentThatOpensWithTextIsStillAFragment() {
    assertEquals("plain words", Html.compact("plain words"));
    assertEquals("<b>x</b> y", Html.compact("<b>x</b>   y"));
  }

  @Test
  public void aScriptBlockIsNotTouched() {
    // jsoup keeps script contents as data rather than text, which is what makes it safe to run
    // every page through this -- the form proof would not survive being reformatted
    String script = "<script nonce=\"abc\">const a = {x: 1};\nif (a.x > 0) go();</script>";
    assertTrue(Html.compact("<div>" + script + "</div>").contains("const a = {x: 1};\nif"));
  }
}
