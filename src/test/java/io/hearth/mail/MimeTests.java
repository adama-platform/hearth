package io.hearth.mail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The one message this server builds by hand, and the reason it does.
 *
 * A calendar invitation only draws accept/maybe/decline buttons when the calendar file is a *third
 * alternative* alongside the text and the HTML. As an attachment it is a file somebody has to
 * notice. The whole feature turns on that structure being right, and nothing else here needs a MIME
 * builder at all.
 */
public class MimeTests {
  private static String build() {
    return new String(Mime.withCalendar("Example <no-reply@example.org>", "ana@example.org",
        "Supper club — Saturday", "the text half", "<p>the html half</p>",
        "BEGIN:VCALENDAR\r\nEND:VCALENDAR", "REQUEST", "invite.ics", "abc123"),
        StandardCharsets.UTF_8);
  }

  @Test
  public void theCalendarIsAnAlternativeAndAlsoAnAttachment() {
    String message = build();
    assertTrue(message.contains("Content-Type: multipart/mixed; boundary=\"hearth-abc123\""));
    assertTrue(message.contains(
        "Content-Type: multipart/alternative; boundary=\"hearth-alt-abc123\""));
    assertTrue("the part that draws the buttons",
        message.contains("Content-Type: text/calendar; charset=UTF-8; method=REQUEST"));
    assertTrue("and a file for the clients that ignore it",
        message.contains("Content-Disposition: attachment; filename=\"invite.ics\""));
    assertTrue("the alternative closes before the attachment",
        message.indexOf("--hearth-alt-abc123--") < message.indexOf("invite.ics"));
    assertTrue(message.trim().endsWith("--hearth-abc123--"));
  }

  @Test
  public void theBoundariesDifferBetweenTheTwoLevels() {
    // a boundary that appears inside its own part ends it early, and one that matches the outer
    // level ends the wrong thing
    String message = build();
    assertFalse(message.contains("boundary=\"hearth-abc123\"\r\nContent-Type: multipart/alternative;"
        + " boundary=\"hearth-abc123\""));
  }

  @Test
  public void everyBodyIsBase64InLinesNobodyCanBreak() {
    String message = build();
    assertEquals("three parts plus the attachment", 4,
        message.split("Content-Transfer-Encoding: base64").length - 1);
    assertTrue(message.contains(
        Base64.getEncoder().encodeToString("the text half".getBytes(StandardCharsets.UTF_8))));
    for (String line : message.split("\r\n")) {
      assertTrue("a base64 line over 76 characters is not one: " + line, line.length() <= 998);
    }
  }

  @Test
  public void aSubjectWithAnAccentInItSurvives() {
    String message = build();
    assertTrue("RFC 2047, because a community whose events have accents in them is normal",
        message.contains("Subject: =?UTF-8?B?"));
    String encoded = message.substring(message.indexOf("=?UTF-8?B?") + 10);
    encoded = encoded.substring(0, encoded.indexOf("?="));
    assertEquals("Supper club — Saturday",
        new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
  }

  @Test
  public void aPlainSubjectIsLeftPlain() {
    String message = new String(Mime.withCalendar("a@example.org", "b@example.org", "Supper club",
        "t", "<p>h</p>", "BEGIN:VCALENDAR", "REQUEST", "invite.ics", "x"), StandardCharsets.UTF_8);
    assertTrue("an encoded word that was not needed is one some old client shows verbatim",
        message.contains("Subject: Supper club\r\n"));
  }

  @Test
  public void nothingHeaderShapedSurvivesInAHeader() {
    // a newline in a display name is an extra Bcc: written by whoever chose their own name
    String message = new String(Mime.withCalendar("Ana\r\nBcc: everybody@example.org <a@b.org>",
        "c@d.org", "Hello", "t", "h", "BEGIN:VCALENDAR", "REQUEST", "i.ics", "x"),
        StandardCharsets.UTF_8);
    assertFalse(message.contains("\r\nBcc:"));
    assertEquals("one From line and no others", 1,
        message.split("\r\nFrom: ").length - 1 + (message.startsWith("From: ") ? 1 : 0));
  }
}
