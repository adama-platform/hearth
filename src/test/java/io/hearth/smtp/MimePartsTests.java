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
  public void aCalendarPartIsFoundInsideMultipartAlternative() {
    String message = "Content-Type: multipart/alternative; boundary=\"b1\"\r\n\r\n"
        + "--b1\r\nContent-Type: text/plain\r\n\r\nAna accepted.\r\n"
        + "--b1\r\nContent-Type: text/calendar; charset=UTF-8; method=REPLY\r\n\r\n"
        + "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"
        + "--b1--\r\n";
    List<MimeParts.Part> parts = MimeParts.of(bytes(message));
    assertEquals(2, parts.size());
    MimeParts.Part calendar = parts.get(1);
    assertTrue(calendar.isCalendar());
    assertEquals("REPLY", calendar.method());
    assertTrue(calendar.text().contains("BEGIN:VCALENDAR"));
  }

  @Test
  public void base64AndQuotedPrintableBothComeBackAsText() {
    String message = "Content-Type: multipart/mixed; boundary=\"x\"\r\n\r\n"
        + "--x\r\nContent-Type: text/calendar\r\nContent-Transfer-Encoding: base64\r\n\r\n"
        + b64("BEGIN:VCALENDAR\r\nUID:one\r\nEND:VCALENDAR") + "\r\n"
        + "--x\r\nContent-Type: text/plain\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n"
        + "caf=C3=A9 and a soft=\r\n break\r\n"
        + "--x--\r\n";
    List<MimeParts.Part> parts = MimeParts.of(bytes(message));
    assertTrue(parts.get(0).text().contains("UID:one"));
    assertEquals("café and a soft break", parts.get(1).text().trim());
  }

  @Test
  public void aNestedMultipartIsWalkedThrough() {
    // what Outlook sends: alternative inside mixed, with the calendar two levels down
    String message = "Content-Type: multipart/mixed; boundary=\"out\"\r\n\r\n"
        + "--out\r\nContent-Type: multipart/alternative; boundary=\"in\"\r\n\r\n"
        + "--in\r\nContent-Type: text/plain\r\n\r\nAccepted.\r\n"
        + "--in\r\nContent-Type: text/calendar; method=REPLY\r\n\r\nBEGIN:VCALENDAR\r\n"
        + "--in--\r\n"
        + "--out--\r\n";
    List<MimeParts.Part> parts = MimeParts.of(bytes(message));
    assertEquals(2, parts.size());
    assertTrue(parts.get(1).isCalendar());
  }

  @Test
  public void anIcsSentAsAnOpaqueAttachmentIsStillACalendar() {
    String message = "Content-Type: multipart/mixed; boundary=\"x\"\r\n\r\n"
        + "--x\r\nContent-Type: application/octet-stream; name=\"invite.ics\"\r\n"
        + "Content-Transfer-Encoding: base64\r\n\r\n" + b64("BEGIN:VCALENDAR") + "\r\n"
        + "--x--\r\n";
    assertTrue("somebody's client really does this",
        MimeParts.of(bytes(message)).get(0).isCalendar());
  }

  @Test
  public void aHeaderFoldedOverThreeLinesIsOneHeader() {
    var headers = MimeParts.headers("Content-Type: text/calendar;\r\n charset=UTF-8;\r\n"
        + " method=REPLY\r\nFrom: a@example.org");
    assertEquals("text/calendar; charset=UTF-8; method=REPLY", headers.get("content-type"));
    assertEquals("a@example.org", headers.get("from"));
  }

  @Test
  public void aMessageThisCannotReadComesBackEmptyRatherThanThrowing() {
    // the mild failure rather than the sharp one: an answer that does not register, and a nudge
    // that asks again, beats a bounce to somebody who pressed a button
    // the MIME decoder is deliberately lenient and skips what is not in its alphabet, so nonsense
    // decodes to nonsense rather than throwing -- and nonsense is not a calendar, which is where
    // it stops. Either way nothing is bounced.
    String message = "Content-Type: multipart/mixed; boundary=\"x\"\r\n\r\n"
        + "--x\r\nContent-Type: text/calendar\r\nContent-Transfer-Encoding: base64\r\n\r\n"
        + "!!!! not base64 at all !!!!\r\n--x--\r\n";
    assertNull("nonsense is not an answer to act on",
        io.hearth.calendar.Ics.read(MimeParts.of(bytes(message)).get(0).text()));

    assertTrue(MimeParts.of(new byte[0]).isEmpty());
    assertTrue("a multipart with no boundary declared is not a shape to guess at",
        MimeParts.of(bytes("Content-Type: multipart/mixed\r\n\r\nwhatever")).isEmpty());
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
