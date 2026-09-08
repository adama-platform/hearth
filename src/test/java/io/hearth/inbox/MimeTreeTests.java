package io.hearth.inbox;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Taking a message apart, including the ones that are wrong.
 *
 * <b>Real mail is malformed constantly</b> -- a boundary that never closes, a charset that does not
 * exist, base64 with a stray character, a filename split across three headers. Every one of those
 * is somebody's actual message, so the test that matters is not "does it parse a textbook example"
 * but "does the broken one still come back with something in it".
 */
public class MimeTreeTests {
  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String base64(String text) {
    return Base64.getMimeEncoder(76, new byte[]{'\r', '\n'})
        .encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  // ---- the simple case ---------------------------------------------------------------------------

  @Test
  public void aPlainMessageIsOnePart() {
    MimeTree.Message parsed = MimeTree.parse(bytes(
        "From: a@b.example\r\nSubject: hello\r\n\r\nthe body\r\n"));
    assertEquals("a@b.example", parsed.header("from"));
    assertEquals("hello", parsed.header("subject"));
    assertEquals(1, parsed.leaves().size());
    assertEquals("the body\r\n", parsed.leaves().get(0).text());
  }

  @Test
  public void aFoldedHeaderIsOneValue() {
    // RFC 5322 wraps a long header and continues it with leading whitespace; a parser reading line
    // by line sees a truncated subject and a line that starts with a space
    MimeTree.Message parsed = MimeTree.parse(bytes(
        "Subject: the first half\r\n  and the second\r\n\r\nbody\r\n"));
    assertEquals("the first half and the second", parsed.header("subject"));
  }

  @Test
  public void aMessageWithNoBlankLineIsAllHeaders() {
    // legal and rare; treating it as headers is what a receiver's own parser does with it
    MimeTree.Message parsed = MimeTree.parse(bytes("Subject: nothing else\r\n"));
    assertEquals("nothing else", parsed.header("subject"));
  }

  // ---- multipart ---------------------------------------------------------------------------------

  private static final String ALTERNATIVE =
      "From: Someone <someone@example.com>\r\n"
          + "Subject: both halves\r\n"
          + "MIME-Version: 1.0\r\n"
          + "Content-Type: multipart/alternative; boundary=\"xyz\"\r\n"
          + "\r\n"
          + "--xyz\r\n"
          + "Content-Type: text/plain; charset=utf-8\r\n"
          + "\r\n"
          + "the plain half\r\n"
          + "--xyz\r\n"
          + "Content-Type: text/html; charset=utf-8\r\n"
          + "\r\n"
          + "<p>the html half</p>\r\n"
          + "--xyz--\r\n";

  @Test
  public void bothHalvesOfAnAlternativeComeBack() {
    List<MimeTree.Part> leaves = MimeTree.parse(bytes(ALTERNATIVE)).leaves();
    assertEquals(2, leaves.size());
    assertEquals("text/plain", leaves.get(0).contentType());
    assertTrue(leaves.get(0).text().contains("the plain half"));
    assertEquals("text/html", leaves.get(1).contentType());
    assertTrue(leaves.get(1).text().contains("the html half"));
    assertEquals("1.1", leaves.get(0).path());
    assertEquals("1.2", leaves.get(1).path());
  }

  @Test
  public void nestedMultipartsAreWalkedToTheLeaves() {
    String message = "Content-Type: multipart/mixed; boundary=\"outer\"\r\n\r\n"
        + "--outer\r\n"
        + "Content-Type: multipart/alternative; boundary=\"inner\"\r\n\r\n"
        + "--inner\r\nContent-Type: text/plain\r\n\r\nplain\r\n"
        + "--inner\r\nContent-Type: text/html\r\n\r\n<p>rich</p>\r\n"
        + "--inner--\r\n"
        + "--outer\r\n"
        + "Content-Type: application/pdf\r\n"
        + "Content-Disposition: attachment; filename=\"note.pdf\"\r\n\r\n"
        + "%PDF-1.4 pretend\r\n"
        + "--outer--\r\n";
    List<MimeTree.Part> leaves = MimeTree.parse(bytes(message)).leaves();
    assertEquals(3, leaves.size());
    assertEquals("1.1.1", leaves.get(0).path());
    assertEquals("1.1.2", leaves.get(1).path());
    assertEquals("1.2", leaves.get(2).path());
    assertEquals("note.pdf", leaves.get(2).filename());
    assertTrue(leaves.get(2).isAttachment());
  }

  /**
   * An unterminated multipart still yields its parts.
   *
   * The closing `--boundary--` is missing from more real mail than anybody would believe, usually
   * because something truncated the message. Treating that as unparseable throws away every part
   * that did arrive intact.
   */
  @Test
  public void aMultipartThatNeverClosesStillGivesUpItsParts() {
    String message = "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
        + "--b\r\nContent-Type: text/plain\r\n\r\nfirst\r\n"
        + "--b\r\nContent-Type: text/plain\r\n\r\nsecond and then the file stops";
    List<MimeTree.Part> leaves = MimeTree.parse(bytes(message)).leaves();
    assertEquals(2, leaves.size());
    assertTrue(leaves.get(1).text().contains("second"));
  }

  /**
   * A short boundary does not match a longer one that starts with it.
   *
   * Plenty of mailers choose short boundaries and nest them, and a prefix match takes the message
   * apart in the wrong places -- which does not throw, it just produces nonsense.
   */
  @Test
  public void aBoundaryIsMatchedWholeRatherThanAsAPrefix() {
    String message = "Content-Type: multipart/mixed; boundary=\"x\"\r\n\r\n"
        + "--x\r\nContent-Type: text/plain\r\n\r\nkeep this\r\n"
        + "--xyz-not-a-boundary\r\n"
        + "still the same part\r\n"
        + "--x--\r\n";
    List<MimeTree.Part> leaves = MimeTree.parse(bytes(message)).leaves();
    assertEquals(1, leaves.size());
    assertTrue(leaves.get(0).text(), leaves.get(0).text().contains("still the same part"));
  }

  @Test
  public void aMultipartWithNoBoundaryIsTreatedAsOneBlobRatherThanLost() {
    MimeTree.Message parsed = MimeTree.parse(bytes(
        "Content-Type: multipart/mixed\r\n\r\nwhatever this is\r\n"));
    assertEquals(1, parsed.leaves().size());
    assertTrue(parsed.leaves().get(0).text().contains("whatever this is"));
  }

  // ---- encodings ---------------------------------------------------------------------------------

  @Test
  public void base64BodiesComeBackAsTheirBytes() {
    String message = "Content-Type: text/plain; charset=utf-8\r\n"
        + "Content-Transfer-Encoding: base64\r\n\r\n" + base64("héllo — this is it\n");
    assertEquals("héllo — this is it\n", MimeTree.parse(bytes(message)).leaves().get(0).text());
  }

  @Test
  public void quotedPrintableIsDecodedIncludingSoftBreaks() {
    String message = "Content-Type: text/plain; charset=utf-8\r\n"
        + "Content-Transfer-Encoding: quoted-printable\r\n\r\n"
        + "caf=C3=A9 and a very long line that the sender wrapped=\r\n right here\r\n";
    String text = MimeTree.parse(bytes(message)).leaves().get(0).text();
    assertTrue(text, text.contains("café"));
    assertTrue("a soft break is formatting and vanishes", text.contains("wrapped right here"));
  }

  @Test
  public void aLoneEqualsInQuotedPrintableIsLiteralRatherThanFatal() {
    String message = "Content-Transfer-Encoding: quoted-printable\r\n\r\n2 + 2 = 4\r\n";
    assertTrue(MimeTree.parse(bytes(message)).leaves().get(0).text().contains("2 + 2 = 4"));
  }

  @Test
  public void base64ThatIsNotBase64GivesBackTheBytesRatherThanNothing() {
    // the message is still readable and the original is still downloadable, which beats an empty
    // body and a support question
    String message = "Content-Transfer-Encoding: base64\r\n\r\n!!!! not base64 at all !!!!\r\n";
    assertTrue(MimeTree.parse(bytes(message)).leaves().get(0).size() > 0);
  }

  /**
   * A charset this JVM has never heard of shows the message rather than none of it.
   *
   * `unicode-1-1-utf-8`, `cp-850` and a long tail of typos all exist in real mail.
   */
  @Test
  public void anImpossibleCharsetFallsBackRatherThanFailing() {
    String message = "Content-Type: text/plain; charset=\"totally-made-up\"\r\n\r\nhello\r\n";
    assertEquals("hello\r\n", MimeTree.parse(bytes(message)).leaves().get(0).text());
  }

  // ---- headers -----------------------------------------------------------------------------------

  @Test
  public void encodedWordsInHeadersAreDecoded() {
    MimeTree.Message parsed = MimeTree.parse(bytes(
        "Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString(
            "café ☕".getBytes(StandardCharsets.UTF_8)) + "?=\r\n\r\nbody\r\n"));
    assertEquals("café ☕", parsed.header("subject"));
  }

  @Test
  public void quotedPrintableEncodedWordsTreatUnderscoreAsSpace() {
    // true only inside an encoded word, which is the rule everybody forgets
    assertEquals("a b", MimeTree.decodeWords("=?UTF-8?Q?a_b?="));
  }

  /**
   * Two adjacent encoded words join with nothing between them.
   *
   * That is what the RFC asks for, and it is what stops a subject split mid-character from
   * arriving with a space wedged into the middle of a letter.
   */
  @Test
  public void adjacentEncodedWordsJoinWithoutTheWhitespaceBetweenThem() {
    assertEquals("abcd", MimeTree.decodeWords("=?UTF-8?Q?ab?= =?UTF-8?Q?cd?="));
    assertEquals("ab cd", MimeTree.decodeWords("=?UTF-8?Q?ab?= cd"));
  }

  @Test
  public void aMalformedEncodedWordKeepsItsOwnText() {
    assertEquals("=?UTF-8?nonsense", MimeTree.decodeWords("=?UTF-8?nonsense"));
  }

  @Test
  public void theFirstOccurrenceOfAHeaderWins() {
    // two Subject lines is either broken or trying something, and neither is a reason to prefer
    // the second
    MimeTree.Message parsed = MimeTree.parse(bytes(
        "Subject: the real one\r\nSubject: the other one\r\n\r\nbody\r\n"));
    assertEquals("the real one", parsed.header("subject"));
  }

  // ---- filenames ---------------------------------------------------------------------------------

  @Test
  public void aQuotedFilenameSurvivesItsSpaces() {
    MimeTree.Message parsed = MimeTree.parse(bytes(
        "Content-Type: application/pdf\r\n"
            + "Content-Disposition: attachment; filename=\"the big report.pdf\"\r\n\r\nx\r\n"));
    assertEquals("the big report.pdf", parsed.leaves().get(0).filename());
  }

  @Test
  public void anRfc2231FilenameIsDecoded() {
    // filename*=UTF-8''caf%C3%A9.pdf, which every second mailer sends and a naive parser hands
    // back verbatim
    MimeTree.Message parsed = MimeTree.parse(bytes(
        "Content-Disposition: attachment; filename*=UTF-8''caf%C3%A9.pdf\r\n\r\nx\r\n"));
    assertEquals("café.pdf", parsed.leaves().get(0).filename());
  }

  @Test
  public void anRfc2231FilenameSplitAcrossLinesIsReassembled() {
    MimeTree.Message parsed = MimeTree.parse(bytes(
        "Content-Disposition: attachment; filename*0=\"the-\"; filename*1=\"report.pdf\"\r\n"
            + "\r\nx\r\n"));
    assertEquals("the-report.pdf", parsed.leaves().get(0).filename());
  }

  @Test
  public void aSemicolonInsideAQuotedParameterDoesNotSplitIt() {
    MimeTree.Message parsed = MimeTree.parse(bytes(
        "Content-Disposition: attachment; filename=\"a; b.pdf\"\r\n\r\nx\r\n"));
    assertEquals("a; b.pdf", parsed.leaves().get(0).filename());
  }

  @Test
  public void theOlderNameParameterIsUsedWhenThereIsNoFilename() {
    MimeTree.Message parsed = MimeTree.parse(bytes(
        "Content-Type: application/pdf; name=\"old-style.pdf\"\r\n\r\nx\r\n"));
    assertEquals("old-style.pdf", parsed.leaves().get(0).filename());
  }

  // ---- what counts as an attachment --------------------------------------------------------------

  /**
   * An inline image referred to by the HTML is furniture, not an attachment.
   *
   * Listing it beside a real document trains somebody to ignore the list, which is exactly when
   * they miss the contract.
   */
  @Test
  public void anInlineImageWithAContentIdIsNotAnAttachment() {
    MimeTree.Message parsed = MimeTree.parse(bytes(
        "Content-Type: image/png\r\n"
            + "Content-ID: <logo@example>\r\n"
            + "Content-Disposition: inline; filename=\"logo.png\"\r\n\r\nx\r\n"));
    MimeTree.Part part = parsed.leaves().get(0);
    assertEquals("logo@example", part.contentId());
    assertFalse(part.isAttachment());
  }

  @Test
  public void aPartWithAFilenameAndNoDispositionIsStillAnAttachment() {
    // plenty of mailers attach a document with no disposition at all
    MimeTree.Message parsed = MimeTree.parse(bytes(
        "Content-Type: application/pdf; name=\"note.pdf\"\r\n\r\nx\r\n"));
    assertTrue(parsed.leaves().get(0).isAttachment());
  }

  @Test
  public void aCalendarPartIsRecognisedHoweverItIsTyped() {
    assertTrue(MimeTree.parse(bytes("Content-Type: text/calendar; method=REQUEST\r\n\r\nx\r\n"))
        .leaves().get(0).isCalendar());
    assertTrue(MimeTree.parse(bytes(
        "Content-Type: application/octet-stream\r\n"
            + "Content-Disposition: attachment; filename=\"invite.ics\"\r\n\r\nx\r\n"))
        .leaves().get(0).isCalendar());
  }

  // ---- bounds ------------------------------------------------------------------------------------

  /**
   * A nesting bomb is stopped by arithmetic rather than by a stack overflow.
   *
   * A message is attacker-supplied input and this is four lines of Python to generate.
   */
  @Test
  public void aDeeplyNestedMessageIsBounded() {
    StringBuilder message = new StringBuilder();
    for (int k = 0; k < 40; k++) {
      message.append("Content-Type: multipart/mixed; boundary=\"b").append(k).append("\"\r\n\r\n")
          .append("--b").append(k).append("\r\n");
    }
    message.append("Content-Type: text/plain\r\n\r\nthe middle\r\n");
    MimeTree.Message parsed = MimeTree.parse(bytes(message.toString()));
    assertNotNull(parsed);
    assertTrue("bounded rather than unbounded", parsed.leaves().size() <= MimeTree.MAX_PARTS);
  }

  @Test
  public void anEmptyMessageParsesToAnEmptyPart() {
    MimeTree.Message parsed = MimeTree.parse(new byte[0]);
    assertEquals(1, parsed.leaves().size());
    assertEquals("", parsed.leaves().get(0).text());
  }

  @Test
  public void aPartCanBeFoundBackByItsPath() {
    MimeTree.Message parsed = MimeTree.parse(bytes(ALTERNATIVE));
    assertNotNull(parsed.byPath("1.2"));
    assertEquals("text/html", parsed.byPath("1.2").contentType());
    assertNull(parsed.byPath("9.9"));
  }
}
