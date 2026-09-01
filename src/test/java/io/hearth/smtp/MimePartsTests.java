package io.hearth.smtp;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Finding the calendar part of a message, the way real clients actually send one.
 *
 * <b>Every one of these shapes came from somebody's mail client.</b> Outlook likes base64 and
 * nested multiparts; Apple Mail likes quoted-printable; something out there sends the file as
 * `application/octet-stream` with a `.ics` name and nothing else to go on. A parser that handles
 * only the tidy case works for about half the people who press Accept, and the half it fails are
 * invisible: their answer simply never arrives.
 *
 * The other half of the job is giving up gracefully. Anything unreadable has to come back as
 * nothing, because throwing turns "somebody accepted an invitation" into an SMTP failure and a
 * bounce to a person who did nothing wrong.
 */
public class MimePartsTests {
  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String b64(String text) {
    return Base64.getMimeEncoder().encodeToString(bytes(text));
  }

  @Test
  public void aPlainMessageIsOnePart() {
    List<MimeParts.Part> parts = MimeParts.of(bytes(
        "From: a@example.org\r\nContent-Type: text/plain\r\n\r\nhello"));
    assertEquals(1, parts.size());
    assertEquals("text/plain", parts.get(0).contentType());
    assertEquals("hello", parts.get(0).text());
  }







  @Test
  public void anAddressIsPulledOutOfWhateverItWasDressedUpIn() {
    assertEquals("ana@example.org", MimeParts.addressIn("Ana Rivera <ana@example.org>"));
    assertEquals("ana@example.org", MimeParts.addressIn("  ANA@example.ORG "));
    assertEquals("ana@example.org",
        MimeParts.addressIn("\"Rivera, Ana\" <Ana@Example.org>"));
    assertNull(MimeParts.addressIn("not an address"));
    assertNull(MimeParts.addressIn(null));
    assertNull(MimeParts.addressIn("@example.org"));
  }

  @Test
  public void anEpilogueAndAPreambleAreNotParts() {
    String message = "Content-Type: multipart/mixed; boundary=\"x\"\r\n\r\n"
        + "This is a preamble that MIME clients write and nobody reads.\r\n"
        + "--x\r\nContent-Type: text/plain\r\n\r\nreal\r\n"
        + "--x--\r\n"
        + "and an epilogue\r\n";
    List<MimeParts.Part> parts = MimeParts.of(bytes(message));
    assertEquals(1, parts.size());
    assertEquals("real", parts.get(0).text().trim());
    assertFalse(parts.get(0).text().contains("epilogue"));
  }
}
