package io.hearth.smtp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The rewriting that keeps a forwarded message passing SPF, and the MAC that stops it being an
 * open relay.
 *
 * Two properties are worth more than everything else here. An address this server wrote reverses
 * to exactly what went in, or a bounce never reaches whoever sent the message. An address this
 * server did not write reverses to nothing, or anybody can post to a made-up return path and have
 * their mail relayed to a stranger with this machine's name on it.
 */
public class SrsTests {
  private static final String SECRET = "a-secret-long-enough-to-be-a-secret";
  private static final String OURS = "ranch.example.org";

  @Test
  public void anAddressWeWroteComesBackAsWhatWentIn() {
    String wrapped = Srs.forward("someone@gmail.com", OURS, SECRET);
    assertTrue(wrapped, wrapped.startsWith("SRS0="));
    assertTrue(wrapped, wrapped.endsWith("@" + OURS));
    assertEquals("someone@gmail.com", Srs.reverse(wrapped, OURS, SECRET));
  }

  @Test
  public void theRewrittenSenderIsAtOurDomainWhichIsTheWholePoint() {
    // SPF is evaluated against the domain in MAIL FROM. Unless that is a domain listing this
    // machine, every forwarded message fails at the far end -- and for a sender publishing
    // p=reject, fails means deleted.
    String wrapped = Srs.forward("someone@gmail.com", OURS, SECRET);
    assertEquals(OURS, SmtpRouting.domainOf(wrapped));
    assertNotEquals("gmail.com", SmtpRouting.domainOf(wrapped));
  }

  @Test
  public void anAddressWeDidNotWriteReversesToNothing() {
    // hand-built to look exactly like ours: right prefix, right shape, right domain, wrong MAC
    String forged = "SRS0=AAAA=" + Srs.today() + "=stranger.example=victim@" + OURS;
    assertNull("relaying this would make us the delivery mechanism for whoever built it",
        Srs.reverse(forged, OURS, SECRET));
  }

  @Test
  public void adifferentSecretDoesNotReverseOurAddresses() {
    String wrapped = Srs.forward("someone@gmail.com", OURS, SECRET);
    assertNull(Srs.reverse(wrapped, OURS, "some-other-secret-entirely-here"));
  }

  @Test
  public void anAddressAtSomebodyElsesDomainIsNotOurs() {
    String wrapped = Srs.forward("someone@gmail.com", OURS, SECRET);
    String elsewhere = wrapped.substring(0, wrapped.lastIndexOf('@')) + "@not-us.example";
    assertNull(Srs.reverse(elsewhere, OURS, SECRET));
    assertFalse(Srs.looksLikeOurs(elsewhere, OURS));
  }

  @Test
  public void anOrdinaryAddressReversesToNothingRatherThanToItself() {
    assertNull(Srs.reverse("jeff@" + OURS, OURS, SECRET));
  }

  /**
   * An expired stamp stops working, because a return path that lives forever is a forwarding
   * address somebody harvests once and uses for years.
   */
  @Test
  public void aStampFromLongAgoIsRefused() {
    long today = System.currentTimeMillis() / 86_400_000L;
    assertTrue(Srs.stampIsRecent(Srs.stampOf(today)));
    assertTrue(Srs.stampIsRecent(Srs.stampOf(today - Srs.VALID_DAYS)));
    assertFalse("a month-old return path is not one anybody is still bouncing to",
        Srs.stampIsRecent(Srs.stampOf(today - Srs.VALID_DAYS - 1)));
  }

  /**
   * The null sender stays null.
   *
   * `MAIL FROM:&lt;&gt;` is what a bounce uses. Rewriting it would produce a bounce that can itself
   * bounce, which is the loop the empty sender exists to prevent.
   */
  @Test
  public void aBouncesEmptySenderIsNotRewritten() {
    assertEquals("", Srs.forward("", OURS, SECRET));
    assertEquals("", Srs.forward(null, OURS, SECRET));
  }

  /**
   * Mail that comes back round does not grow an envelope sender per lap.
   *
   * Without this a message that passes through twice gets a longer address each time, until it
   * crosses the 320-character limit and delivery fails for a reason nobody can read off the wire.
   */
  @Test
  public void ourOwnAddressIsRewoundRatherThanWrappedAgain() {
    String once = Srs.forward("someone@gmail.com", OURS, SECRET);
    String twice = Srs.forward(once, OURS, SECRET);
    assertEquals("the second pass is the same length as the first", once.length(), twice.length());
    assertEquals("someone@gmail.com", Srs.reverse(twice, OURS, SECRET));
  }

  /**
   * Somebody else's SRS0 becomes our SRS1, and unwraps back to theirs.
   *
   * This is the case a mailing list produces. Wrapping their SRS0 in another SRS0 would make an
   * address only we could reverse, and the bounce would stop one hop short of the sender.
   */
  @Test
  public void anotherForwardersAddressBecomesAnSrs1() {
    String theirs = Srs.forward("someone@gmail.com", "list.example.net", "their-own-long-secret");
    String ours = Srs.forward(theirs, OURS, SECRET);
    assertTrue(ours, ours.startsWith("SRS1="));
    assertEquals(OURS, SmtpRouting.domainOf(ours));
    assertEquals("it hands back the address the previous hop can reverse", theirs,
        Srs.reverse(ours, OURS, SECRET));
  }

  @Test
  public void aTruncatedAddressIsRefusedRatherThanCrashing() {
    for (String broken : new String[]{"SRS0=@" + OURS, "SRS0=AAAA@" + OURS,
        "SRS0=AAAA=BB@" + OURS, "SRS0=AAAA=BB=only-three@" + OURS, "SRS1=AAAA@" + OURS}) {
      assertNull(broken, Srs.reverse(broken, OURS, SECRET));
    }
  }
}
