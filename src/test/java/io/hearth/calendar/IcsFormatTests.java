package io.hearth.calendar;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The format itself: folding, escaping, and reading back what other people's programs send.
 *
 * RFC 5545 is old and particular, and the particularity is load-bearing. A line over 75 octets, an
 * unescaped comma, or a folded line put back together wrongly all produce a file that *some*
 * clients accept -- which is worse than one none of them do, because the failure is invisible until
 * somebody says their calendar looked strange.
 */
public class IcsFormatTests {
  private static Calendar.Event event(String title, String location, LocalDate day) {
    return new Calendar.Event(7, title, "Bring a chair.", location, null,
        Calendar.State.accepted, null, null, "", day, day, "7pm", null, true, false, 0, 0, 0,
        new java.sql.Timestamp(0), null, null, "", null, "uid-7@example.org", 0, null);
  }

  private static String flat(String ics) {
    return String.join("\n", Ics.unfold(ics));
  }

  @Test
  public void everyLineIsInsideTheLimitAndComesBackTogether() {
    String title = "A supper club evening with rather a lot of words in its name, which is the "
        + "sort of thing communities do";
    String ics = Ics.request(event(title, "The Oak", LocalDate.of(2026, 5, 14)), "uid-7@example.org",
        0, "events@example.org", "Example", List.of(), "Example", "https://example.org/events/7",
        "Bring a chair.");
    for (String line : ics.split("\r\n")) {
      assertTrue("over 75 octets: " + line,
          line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 75);
    }
    // the value in the file is escaped, so this asserts the round trip rather than the raw string:
    // unfold, find the line, unescape, and it is what went in
    String summary = Ics.unfold(ics).stream().filter(line -> line.startsWith("SUMMARY:"))
        .findFirst().orElseThrow();
    assertEquals("and unfolding puts it back exactly", title,
        Ics.unescape(summary.substring("SUMMARY:".length())));
  }

  @Test
  public void aFoldNeverSplitsASurrogatePair() {
    // an emoji at the fold boundary, split down the middle, is bytes no parser can reassemble
    String title = "x".repeat(70) + "🍞" + "y".repeat(70);
    String ics = Ics.request(event(title, "", LocalDate.of(2026, 5, 14)), "u@example.org", 0,
        "events@example.org", "Example", List.of(), "Example", "", "");
    assertTrue(flat(ics).contains(title));
    for (String line : ics.split("\r\n")) {
      assertTrue(Character.isHighSurrogate(line.charAt(line.length() - 1)) ? false : true);
    }
  }

  @Test
  public void structureCharactersInsideAValueAreEscapedAndUnescaped() {
    assertEquals("The Oak\\, back room", Ics.escape("The Oak, back room"));
    assertEquals("a\\;b", Ics.escape("a;b"));
    assertEquals("one\\ntwo", Ics.escape("one\ntwo"));
    assertEquals("back\\\\slash", Ics.escape("back\\slash"));
    assertEquals("The Oak, back room", Ics.unescape("The Oak\\, back room"));
    assertEquals("one\ntwo", Ics.unescape("one\\ntwo"));
  }

  @Test
  public void aReplyIsReadHoweverItIsSpelt() {
    // parameter order, case and quoting all differ between clients, and every one of these is a
    // real answer from a real person
    String ics = "begin:VCALENDAR\r\n"
        + "method:reply\r\n"
        + "BEGIN:VEVENT\r\n"
        + "uid:uid-7@example.org\r\n"
        + "SEQUENCE:3\r\n"
        + "ATTENDEE;CN=\"Rivera, Ana\";PARTSTAT=accepted;RSVP=TRUE:MAILTO:Ana@Example.ORG\r\n"
        + "END:VEVENT\r\nEND:VCALENDAR";
    Ics.Incoming incoming = Ics.read(ics);
    assertNotNull(incoming);
    assertEquals(Ics.Method.REPLY, incoming.method());
    assertEquals("uid-7@example.org", incoming.uid());
    assertEquals(3, incoming.sequence());
    assertEquals("the address is the identity, lowercased", "ana@example.org",
        incoming.attendeeEmail());
    assertEquals(Ics.Part.ACCEPTED, incoming.part());
    assertEquals(Calendar.Answer.going, incoming.part().answer());
  }

  @Test
  public void aFoldedReplyIsPutBackBeforeAnythingLooksAtIt() {
    String ics = "BEGIN:VCALENDAR\r\nMETHOD:REPLY\r\nBEGIN:VEVENT\r\n"
        + "UID:a-very-long-identifier-that-some-client-decided-to-f\r\n old@example.org\r\n"
        + "ATTENDEE;PARTSTAT=DECLI\r\n NED:mailto:ana@example.org\r\n"
        + "END:VEVENT\r\nEND:VCALENDAR";
    Ics.Incoming incoming = Ics.read(ics);
    assertNotNull(incoming);
    assertEquals("a-very-long-identifier-that-some-client-decided-to-fold@example.org",
        incoming.uid());
    assertEquals(Ics.Part.DECLINED, incoming.part());
  }

  @Test
  public void anythingThatDoesNotSayWhoIsAnsweringWhatIsRefused() {
    assertNull(Ics.read(null));
    assertNull(Ics.read(""));
    assertNull("no method", Ics.read("BEGIN:VCALENDAR\r\nUID:x\r\nATTENDEE:mailto:a@b.org"));
    assertNull("no uid", Ics.read("METHOD:REPLY\r\nATTENDEE:mailto:a@b.org"));
    assertNull("no attendee", Ics.read("METHOD:REPLY\r\nUID:x"));
    assertNull("a method nobody has heard of", Ics.read("METHOD:SHOUT\r\nUID:x\r\n"
        + "ATTENDEE:mailto:a@b.org"));
    assertNull("an attendee that is not an address",
        Ics.read("METHOD:REPLY\r\nUID:x\r\nATTENDEE:somebody"));
  }

  @Test
  public void aCounterCarriesTheDayItIsAskingFor() {
    Ics.Incoming incoming = Ics.read("METHOD:COUNTER\r\nUID:uid-7@example.org\r\n"
        + "DTSTART;VALUE=DATE:20260521\r\nATTENDEE;PARTSTAT=TENTATIVE:mailto:ana@example.org");
    assertNotNull(incoming);
    assertEquals(LocalDate.of(2026, 5, 21), incoming.proposedStart());

    // ...and a REPLY's own DTSTART is not a proposal, because a reply is not asking for anything
    Ics.Incoming reply = Ics.read("METHOD:REPLY\r\nUID:uid-7@example.org\r\n"
        + "DTSTART;VALUE=DATE:20260521\r\nATTENDEE;PARTSTAT=ACCEPTED:mailto:ana@example.org");
    assertNull(reply.proposedStart());
  }

  @Test
  public void aDateTimeIsReducedToTheDayBecauseADayIsAllThisCalendarHas() {
    assertEquals(LocalDate.of(2026, 5, 21), Ics.date("20260521T190000Z"));
    assertEquals(LocalDate.of(2026, 5, 21), Ics.date("20260521"));
    assertNull(Ics.date("nonsense"));
    assertNull(Ics.date(""));
  }

  @Test
  public void everyAnswerHasAnIcalendarSpellingAndBack() {
    assertEquals(Ics.Part.ACCEPTED, Ics.Part.forAnswer(Calendar.Answer.going));
    assertEquals(Ics.Part.TENTATIVE, Ics.Part.forAnswer(Calendar.Answer.maybe));
    assertEquals(Ics.Part.DECLINED, Ics.Part.forAnswer(Calendar.Answer.no));
    assertEquals("a waitlist is this community's idea, and a maybe is the honest thing to show",
        Ics.Part.TENTATIVE, Ics.Part.forAnswer(Calendar.Answer.waitlist));
    assertEquals(Ics.Part.NEEDS_ACTION, Ics.Part.forAnswer(null));
    assertNull(Ics.Part.NEEDS_ACTION.answer());
    assertEquals(Ics.Part.NEEDS_ACTION, Ics.Part.of("something-else"));
  }

  @Test
  public void aCancellationSaysCancelledAndKeepsTheIdentity() {
    String ics = Ics.cancel(event("Supper", "The Oak", LocalDate.of(2026, 5, 14)),
        "uid-7@example.org", 4, "events@example.org", "Example",
        List.of(new Ics.Attendee("ana@example.org", "Ana", Ics.Part.ACCEPTED)), "Example", "", "");
    String flat = flat(ics);
    assertTrue(flat.contains("METHOD:CANCEL"));
    assertTrue(flat.contains("STATUS:CANCELLED"));
    assertTrue("same uid, higher sequence, or it cancels nothing",
        flat.contains("UID:uid-7@example.org") && flat.contains("SEQUENCE:4"));
    assertTrue("and no alarm on something that is not happening", !flat.contains("VALARM"));
  }

}
