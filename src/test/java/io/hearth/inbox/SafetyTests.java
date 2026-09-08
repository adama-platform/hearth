package io.hearth.inbox;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What may leave this server, and what a file has to actually be before it does.
 *
 * <b>The attacks these refuse are all the same shape: a name that lies about the bytes.</b> An
 * executable called `invoice.pdf`, a script called `photo.jpg.exe`, a nine-kilobyte PNG that
 * becomes four gigabytes in a decoder. None of them is exotic and all of them arrive by email.
 */
public class SafetyTests {
  private static byte[] png(int width, int height) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
    out.writeBytes(new byte[]{0, 0, 0, 13, 'I', 'H', 'D', 'R'});
    out.writeBytes(be32(width));
    out.writeBytes(be32(height));
    out.writeBytes(new byte[]{8, 6, 0, 0, 0});
    return out.toByteArray();
  }

  private static byte[] be32(int value) {
    return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8),
        (byte) value};
  }

  private static byte[] gif(int width, int height) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes("GIF89a".getBytes(StandardCharsets.US_ASCII));
    out.write(width & 0xFF);
    out.write(width >> 8 & 0xFF);
    out.write(height & 0xFF);
    out.write(height >> 8 & 0xFF);
    return out.toByteArray();
  }

  private static byte[] jpeg(int width, int height) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    // a comment segment first, so the frame is not at a fixed offset
    out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 4, 'h', 'i'});
    out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xC0, 0, 17, 8});
    out.write(height >> 8 & 0xFF);
    out.write(height & 0xFF);
    out.write(width >> 8 & 0xFF);
    out.write(width & 0xFF);
    out.writeBytes(new byte[8]);
    return out.toByteArray();
  }

  private static byte[] pdf() {
    return "%PDF-1.7\nthe rest of a document".getBytes(StandardCharsets.US_ASCII);
  }

  // ---- the allow list ----------------------------------------------------------------------------

  @Test
  public void whatIsOnTheListIsServedAsTheTypeThisServerChose() {
    Safety.Verdict verdict = Safety.check("report.pdf", pdf());
    assertTrue(verdict.reason(), verdict.allowed());
    // never the sender's content type: a message may say whatever it likes about its own
    // attachment, and a browser that believes it is the whole attack
    assertEquals("application/pdf", verdict.contentType());
  }

  @Test
  public void anythingNotOnTheListIsRefusedWithASentence() {
    Safety.Verdict verdict = Safety.check("thing.xyz", pdf());
    assertFalse(verdict.allowed());
    assertTrue(verdict.reason(), verdict.reason().contains(".xyz"));
  }

  @Test
  public void theOnesSomebodyWillActuallyMeetAreExplained() {
    for (String extension : new String[]{"zip", "html", "svg", "exe", "docm", "lnk", "iso"}) {
      Safety.Verdict verdict = Safety.check("thing." + extension, pdf());
      assertFalse(extension, verdict.allowed());
      // a sentence rather than a code, because the person reading it has to decide what to do
      assertTrue(extension + ": " + verdict.reason(),
          verdict.reason().startsWith("." + extension + " is "));
    }
    assertTrue(Safety.explain("svg").contains("script"));
    assertTrue(Safety.explain("zip").contains("cannot see"));
  }

  /**
   * `invoice.pdf.exe` is an executable.
   *
   * Reading the first extension is exactly the mistake the double-extension trick exists to
   * provoke, and it is still one of the most effective things in mail.
   */
  @Test
  public void theLastExtensionIsTheOneThatCounts() {
    assertEquals("exe", Safety.extensionOf("invoice.pdf.exe"));
    assertEquals("pdf", Safety.extensionOf("invoice.exe.pdf"));
    assertFalse(Safety.check("invoice.pdf.exe", pdf()).allowed());
  }

  @Test
  public void aNameWithNoExtensionIsRefused() {
    assertEquals("", Safety.extensionOf("invoice"));
    assertFalse(Safety.check("invoice", pdf()).allowed());
  }

  // ---- the bytes have to agree ---------------------------------------------------------------------

  /**
   * An executable named `.pdf` is refused, and the refusal says what it actually is.
   *
   * This is the check that makes the allow list mean anything: without it the list is a list of
   * names, and a name is chosen by whoever sent the message.
   */
  @Test
  public void anExecutableWearingAPdfsNameIsRefused() {
    byte[] executable = "MZ\u0090\u0000this is a windows binary"
        .getBytes(StandardCharsets.ISO_8859_1);
    Safety.Verdict verdict = Safety.check("invoice.pdf", executable);
    assertFalse(verdict.allowed());
    assertTrue(verdict.reason(), verdict.reason().contains("executable"));
  }

  @Test
  public void aPngNamedJpgIsRefused() {
    Safety.Verdict verdict = Safety.check("photo.jpg", png(10, 10));
    assertFalse(verdict.allowed());
    assertTrue(verdict.reason(), verdict.reason().contains("PNG"));
  }

  @Test
  public void anOfficeDocumentIsAZipAndThatIsFine() {
    // docx, xlsx and pptx are all zip containers; the signature check has to know that or it
    // refuses every modern Office document
    byte[] docx = new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0};
    assertTrue(Safety.check("report.docx", docx).allowed());
    assertFalse("and a real .zip is still refused, by the allow list rather than the signature",
        Safety.check("report.zip", docx).allowed());
  }

  // ---- images ------------------------------------------------------------------------------------

  @Test
  public void theDimensionsComeOutOfEachFormatsHeader() {
    Safety.Verdict asPng = Safety.check("a.png", png(640, 480));
    assertTrue(asPng.reason(), asPng.allowed());
    assertEquals(640, asPng.width());
    assertEquals(480, asPng.height());

    Safety.Verdict asGif = Safety.check("a.gif", gif(320, 200));
    assertTrue(asGif.allowed());
    assertEquals(320, asGif.width());
    assertEquals(200, asGif.height());

    Safety.Verdict asJpeg = Safety.check("a.jpg", jpeg(1024, 768));
    assertTrue(asJpeg.reason(), asJpeg.allowed());
    assertEquals("the frame header is not at a fixed offset and has to be walked to",
        1024, asJpeg.width());
    assertEquals(768, asJpeg.height());
  }

  /**
   * A decompression bomb is refused before anything decodes it.
   *
   * The whole point is that the header is read and the pixels are not: a 60,000 by 60,000 PNG is
   * about nine kilobytes on the wire and fourteen gigabytes in a decoder.
   */
  @Test
  public void anAbsurdlyLargePictureIsRefusedFromItsHeaderAlone() {
    byte[] bomb = png(60_000, 60_000);
    assertTrue("tiny on the wire, which is the attack", bomb.length < 100);
    Safety.Verdict verdict = Safety.check("bomb.png", bomb);
    assertFalse(verdict.allowed());
    assertTrue(verdict.reason(), verdict.reason().contains("60000 by 60000"));
  }

  @Test
  public void aPictureWithNoDimensionsAtAllIsRefused() {
    assertFalse(Safety.check("empty.png", png(0, 0)).allowed());
  }

  @Test
  public void somethingClaimingToBeAPictureAndBeginningLikeNothingIsRefused() {
    Safety.Verdict verdict = Safety.check("photo.png", "just some text".getBytes());
    assertFalse(verdict.allowed());
    assertTrue(verdict.reason(), verdict.reason().contains("does not begin like any picture"));
  }

  @Test
  public void anEmptyPartIsRefused() {
    assertFalse(Safety.check("a.pdf", new byte[0]).allowed());
  }

  // ---- names -------------------------------------------------------------------------------------

  /**
   * A filename never reaches a header or a path in the shape it arrived in.
   *
   * A name carrying a CRLF is header injection; one carrying `../` is a path. Both arrive.
   */
  @Test
  public void aNameIsMadeSafeBeforeItGoesAnywhere() {
    assertEquals("passwd", Safety.safeName("../../etc/passwd", "x"));
    // one underscore per character, so nothing collapses two names into one
    assertEquals("a__b.pdf", Safety.safeName("a\r\nb.pdf", "x"));
    assertEquals("windows.pdf", Safety.safeName("C:\\temp\\windows.pdf", "x"));
    assertEquals("x", Safety.safeName("", "x"));
    assertEquals("x", Safety.safeName("...", "x"));
    assertTrue(Safety.safeName("a name (2).pdf", "x").equals("a name (2).pdf"));
  }

  @Test
  public void aVeryLongNameIsCutRatherThanRefused() {
    String long_ = "a".repeat(500) + ".pdf";
    assertTrue(Safety.safeName(long_, "x").length() <= 120);
  }

  @Test
  public void theAllowListIsClosedAndSmall() {
    // a list that has quietly grown to include something executable is the failure this guards
    for (String extension : Safety.allowed()) {
      assertFalse(extension + " must not be executable",
          java.util.List.of("exe", "js", "html", "htm", "svg", "jar", "bat", "sh", "com", "scr",
              "vbs", "ps1", "msi", "dll", "apk", "docm", "xlsm", "pptm").contains(extension));
    }
  }
}
