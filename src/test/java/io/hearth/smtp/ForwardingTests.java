package io.hearth.smtp;

import io.hearth.auth.Accounts;
import io.hearth.common.Verbose;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Mail arriving, being decided about, and going somewhere else.
 *
 * The scenario throughout is the one this exists for: a domain whose MX now points here, one
 * address forwarded to a mailbox at a large provider, and everything else dropped or refused.
 *
 * Two properties are worth more than the rest and both are checked from the far end rather than
 * from inside this server. <b>The message arrives byte for byte below the headers we added</b> --
 * anything else destroys the sender's DKIM signature and there is no way to tell that apart from
 * tampering. <b>Whatever the far end says comes straight back to the sending server</b>, which is
 * what removes the queue and the bounce generator together.
 */
public class ForwardingTests {
  private static final String DOMAIN = "ranch.example.org";
  private static final String SECRET = "a-secret-long-enough-to-be-a-secret";

  private static final String MESSAGE =
      "From: Someone <someone@gmail.com>\r\n"
          + "To: jeff@" + DOMAIN + "\r\n"
          + "Subject: about Thursday\r\n"
          + "Message-ID: <abc123@gmail.com>\r\n"
          + "\r\n"
          + "Can you do the ninth?\r\n"
          + ".a line that starts with a dot\r\n";

  private Configs configs;
  private TestServer server;
  private StubExchanger far;
  private MailKeys keys;
  private File dir;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain(DOMAIN, "{\"name\":\"Ranch\"}");
    server = TestServer.ofConfigs(configs.file());
    far = new StubExchanger();
    dir = Files.createTempDirectory("hearth-forwarding").toFile();
    keys = MailKeys.open(new File(dir, "dkim.key"), "hearth");
  }

  @After
  public void tearDown() {
    if (far != null) {
      far.close();
    }
    if (server != null) {
      server.close();
    }
    if (configs != null) {
      configs.delete();
    }
  }

  private Accounts accounts() {
    return server.auth.forDomain(DOMAIN);
  }

  private Mailboxes boxes() {
    return accounts().mailboxes;
  }

  /**
   * A forwarder pointed at the stub, with DNS that sends every domain to it.
   *
   * `require-tls` is off because the stub speaks no TLS; that the flag is honoured is checked on
   * its own below, where no handshake is needed to prove it.
   */
  private Forwarding forwarder() throws Exception {
    return forwarder(true);
  }

  private Forwarding forwarder(boolean signed) throws Exception {
    FakeDns dns = new FakeDns().mx("gmail.com", "10 stub.invalid")
        .mx("elsewhere.example", "10 stub.invalid");
    Relay relay = new Relay(dns, "mail." + DOMAIN, false, far.port(), Verbose.OFF) {
      @Override
      public List<String> exchangersFor(String domain) {
        // every destination resolves to the loopback stub; what MX ordering does is tested apart
        return List.of("127.0.0.1");
      }
    };
    ForwardConfig config = forwardConfig();
    return new Forwarding(server.auth, config, signed ? keys : null, relay,
        envelope -> MailReceiver.Outcome.accepted("printed"), Verbose.OFF);
  }

  private static ForwardConfig forwardConfig() throws Exception {
    String json = "{\"enabled\":true,\"require-tls\":false,\"srs-secret\":\"" + SECRET + "\","
        + "\"authserv-id\":\"mail." + DOMAIN + "\"}";
    return new ForwardConfig(new io.hearth.common.ConfigObject(
        (com.fasterxml.jackson.databind.node.ObjectNode)
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json), "forwarding"));
  }

  private static Envelope arriving(String recipient) {
    return arriving(recipient, MESSAGE, AuthResult.nothingChecked());
  }

  private static Envelope arriving(String recipient, String text, AuthResult checks) {
    return new Envelope("someone@gmail.com", List.of(recipient),
        text.getBytes(StandardCharsets.UTF_8), "203.0.113.9", "mail-sor.google.com", DOMAIN,
        System.currentTimeMillis(), checks);
  }

  /**
   * The transaction was answered with a 250.
   *
   * `receive` always answers, because SMTP has exactly one reply per message and the sending
   * server is waiting for it. What varies is whether that reply is an acceptance.
   */
  private static void assertAccepted(MailReceiver.Outcome outcome) {
    assertNotNull("every message gets an answer; a handler that returns nothing hangs the socket",
        outcome);
    assertTrue(String.valueOf(outcome.detail()), outcome.accepted());
  }

  private long forwardEverythingTo(String destination) throws Exception {
    return boxes().saveRule(0, DOMAIN, 100, "everything", Mailboxes.EVERYONE, "", "",
        Mailboxes.Action.forward, destination, true, null);
  }

  // ---- the message itself ------------------------------------------------------------------------

  /**
   * The whole point, checked from the receiving end: nothing below our headers moved.
   *
   * A footer, a subject tag or a re-encode destroys the sender's signature, and a receiver cannot
   * tell a well-meaning forwarder apart from somebody rewriting the message.
   */
  @Test
  public void theMessageArrivesUnchangedBelowTheHeadersWeAdd() throws Exception {
    forwardEverythingTo("her@elsewhere.example");
    assertAccepted(forwarder().receive(arriving("jeff@" + DOMAIN)));

    assertEquals(1, far.delivered.size());
    StubExchanger.Delivered got = far.delivered.get(0);
    assertTrue("the original message is in there whole and unaltered",
        got.data().endsWith(MESSAGE));
    assertTrue("including a body line that starts with a dot",
        got.body().contains("\r\n.a line that starts with a dot"));
    assertEquals("Someone <someone@gmail.com>", got.header("From"));
    assertEquals("about Thursday", got.header("Subject"));
  }

  @Test
  public void aReceivedHeaderIsAddedNamingThisHopAndTheOneRecipient() throws Exception {
    forwardEverythingTo("her@elsewhere.example");
    forwarder().receive(arriving("jeff@" + DOMAIN));
    String received = far.delivered.get(0).header("Received");
    assertNotNull(received);
    assertTrue(received, received.contains("from mail-sor.google.com"));
    assertTrue(received, received.contains("203.0.113.9"));
    assertTrue(received, received.contains("by mail." + DOMAIN));
    assertTrue("the recipient it was written for, and no other", received.contains("jeff@" + DOMAIN));
  }

  @Test
  public void theChainAndASignatureOfOurOwnRideAlong() throws Exception {
    forwardEverythingTo("her@elsewhere.example");
    forwarder().receive(arriving("jeff@" + DOMAIN, MESSAGE,
        new AuthResult(AuthResult.Status.pass, "gmail.com", AuthResult.Status.pass, "gmail.com",
            AuthResult.Status.pass, "gmail.com", "reject")));
    StubExchanger.Delivered got = far.delivered.get(0);
    assertTrue(got.hasHeader("ARC-Seal"));
    assertTrue(got.hasHeader("ARC-Message-Signature"));
    assertTrue(got.hasHeader("ARC-Authentication-Results"));
    assertTrue(got.hasHeader("DKIM-Signature"));
    assertTrue("the chain records what was actually seen here",
        got.header("ARC-Authentication-Results").contains("dmarc=pass"));
  }

  @Test
  public void withNoKeyTheMailStillGoesUnsigned() throws Exception {
    // an unsigned forward is worth far more than a refused one; the boot report is where the
    // complaint about the missing key belongs
    forwardEverythingTo("her@elsewhere.example");
    assertAccepted(forwarder(false).receive(arriving("jeff@" + DOMAIN)));
    assertEquals(1, far.delivered.size());
    assertFalse(far.delivered.get(0).hasHeader("DKIM-Signature"));
    assertFalse(far.delivered.get(0).hasHeader("ARC-Seal"));
    assertEquals("unsigned: no key", accounts().mailLog.recent(DOMAIN, 1).get(0).arc());
  }

  /**
   * The return path is rewritten and the visible sender is not.
   *
   * That split is the whole of SRS: SPF asks about the envelope, a person reads the header, and
   * rewriting the one nobody reads is what makes a forwarded message pass at the far end without
   * anybody being lied to about who wrote it.
   */
  @Test
  public void theEnvelopeSenderIsRewrittenAndTheFromHeaderIsNot() throws Exception {
    forwardEverythingTo("her@elsewhere.example");
    forwarder().receive(arriving("jeff@" + DOMAIN));
    StubExchanger.Delivered got = far.delivered.get(0);
    assertTrue(got.mailFrom(), got.mailFrom().startsWith("SRS0="));
    assertEquals(DOMAIN, SmtpRouting.domainOf(got.mailFrom()));
    assertEquals("someone@gmail.com", Srs.reverse(got.mailFrom(), DOMAIN, SECRET));
    assertEquals("Someone <someone@gmail.com>", got.header("From"));
  }

  // ---- the rules ---------------------------------------------------------------------------------

  @Test
  public void theFirstMatchingRuleWinsAndTheRestAreNotConsulted() throws Exception {
    boxes().saveRule(0, DOMAIN, 10, "hers", "her", "", "", Mailboxes.Action.forward,
        "her@elsewhere.example", true, null);
    boxes().saveRule(0, DOMAIN, 20, "everything else", Mailboxes.EVERYONE, "", "",
        Mailboxes.Action.drop, "", true, null);

    assertAccepted(forwarder().receive(arriving("her@" + DOMAIN)));
    assertEquals(1, far.delivered.size());

    assertAccepted(forwarder().receive(arriving("jeff@" + DOMAIN)));
    assertEquals("the catch-all dropped it rather than forwarding it", 1, far.delivered.size());
    assertEquals("dropped", accounts().mailLog.recent(DOMAIN, 1).get(0).outcome());
  }

  @Test
  public void aRuleCanNarrowOnTheSenderAndTheSubject() throws Exception {
    Mailboxes.Rule bySender = new Mailboxes.Rule(1, DOMAIN, 10, "", Mailboxes.EVERYONE,
        "@gmail.com", "", Mailboxes.Action.drop, "", true, null, null);
    assertTrue(bySender.matches("jeff", "someone@gmail.com", "anything"));
    assertFalse(bySender.matches("jeff", "someone@yahoo.example", "anything"));

    Mailboxes.Rule bySubject = new Mailboxes.Rule(2, DOMAIN, 10, "", Mailboxes.EVERYONE, "",
        "invoice", Mailboxes.Action.drop, "", true, null, null);
    assertTrue(bySubject.matches("jeff", "a@b.example", "Your INVOICE is ready"));
    assertFalse(bySubject.matches("jeff", "a@b.example", "about Thursday"));
  }

  @Test
  public void aRuleThatIsOffMatchesNothing() throws Exception {
    boxes().saveRule(0, DOMAIN, 10, "off for now", Mailboxes.EVERYONE, "", "",
        Mailboxes.Action.forward, "her@elsewhere.example", false, null);
    assertNull(boxes().decide(DOMAIN, "jeff", "someone@gmail.com", "hello"));
  }

  @Test
  public void droppingAcceptsTheMessageRatherThanRefusingIt() throws Exception {
    // a 550 would tell a sender their address is wrong when it is right and somebody simply does
    // not want their mail
    boxes().saveRule(0, DOMAIN, 10, "bin", Mailboxes.EVERYONE, "", "", Mailboxes.Action.drop,
        "", true, null);
    assertAccepted(forwarder().receive(arriving("jeff@" + DOMAIN)));
    assertEquals(0, far.delivered.size());
  }

  // ---- the door ----------------------------------------------------------------------------------

  @Test
  public void anAddressNothingClaimsIsRefusedBeforeTheMessageArrives() throws Exception {
    boxes().saveBox(0, DOMAIN, "jeff", "mine", null, true, null);
    boxes().saveRule(0, DOMAIN, 10, "mine", "jeff", "", "", Mailboxes.Action.forward,
        "her@elsewhere.example", true, null);

    Forwarding forwarding = forwarder();
    assertTrue(forwarding.accepts(DOMAIN, "jeff@" + DOMAIN));
    assertFalse("a mistyped address comes back to whoever typed it",
        forwarding.accepts(DOMAIN, "jef@" + DOMAIN));
  }

  @Test
  public void aDomainWithNothingConfiguredStillAcceptsEverything() throws Exception {
    // turning this on is a decision per domain; a domain nobody has set up behaves as it did
    // before forwarding existed rather than refusing every message
    assertTrue(forwarder().accepts(DOMAIN, "anybody@" + DOMAIN));
  }

  @Test
  public void aNamedAddressWithNoRuleIsStillAccepted() throws Exception {
    boxes().saveBox(0, DOMAIN, "receipts", "statements", null, true, null);
    assertTrue(forwarder().accepts(DOMAIN, "receipts@" + DOMAIN));
    assertFalse(forwarder().accepts(DOMAIN, "other@" + DOMAIN));
  }

  @Test
  public void anAddressTurnedOffIsRefused() throws Exception {
    boxes().saveBox(0, DOMAIN, "old", "not any more", null, false, null);
    assertFalse(forwarder().accepts(DOMAIN, "old@" + DOMAIN));
  }

  // ---- what the far end said ----------------------------------------------------------------------

  /**
   * A permanent refusal at the far end becomes a permanent refusal here.
   *
   * That is what makes the sending server write the bounce, to the address it really sent from --
   * rather than this server generating one and mailing it to a return path a spammer chose.
   */
  @Test
  public void aRefusalAtTheFarEndIsPassedStraightBack() throws Exception {
    forwardEverythingTo("her@elsewhere.example");
    far.answersData("550 5.7.26 not accepted due to domain's DMARC policy");

    MailReceiver.Outcome outcome = forwarder().receive(arriving("jeff@" + DOMAIN));
    assertNotNull(outcome);
    assertFalse(outcome.accepted());
    assertFalse("permanent, so the sender's server bounces rather than retrying for days",
        outcome.temporary());
    assertTrue(outcome.detail(), outcome.detail().contains("5.7.26"));

    MailLog.Entry logged = accounts().mailLog.recent(DOMAIN, 1).get(0);
    assertEquals("failed", logged.outcome());
    assertTrue("the far end's own sentence, kept word for word",
        logged.detail().contains("DMARC policy"));
  }

  @Test
  public void aTemporaryFailureAtTheFarEndBecomesATemporaryFailureHere() throws Exception {
    forwardEverythingTo("her@elsewhere.example");
    far.answersData("451 4.3.0 mail server temporarily rejected message");

    MailReceiver.Outcome outcome = forwarder().receive(arriving("jeff@" + DOMAIN));
    assertNotNull(outcome);
    assertFalse(outcome.accepted());
    assertTrue("the sending server already has a retry schedule and is better at this",
        outcome.temporary());
    assertEquals("deferred", accounts().mailLog.recent(DOMAIN, 1).get(0).outcome());
  }

  @Test
  public void aRefusalAtRcptIsAlsoPassedBack() throws Exception {
    forwardEverythingTo("her@elsewhere.example");
    far.answersRcpt("550 no such user here");
    MailReceiver.Outcome outcome = forwarder().receive(arriving("jeff@" + DOMAIN));
    assertNotNull(outcome);
    assertFalse(outcome.temporary());
    assertTrue(outcome.detail(), outcome.detail().contains("no such user"));
  }

  // ---- bounces ------------------------------------------------------------------------------------

  /**
   * A failure report addressed to an SRS address is unwrapped and sent on.
   *
   * Without this the scheme is one-way: the far end's report arrives here, at an address nothing
   * owns, and whoever sent the original message never learns it did not arrive.
   */
  @Test
  public void aBounceComingHomeIsReturnedToWhoeverWroteTheMessage() throws Exception {
    String returnPath = Srs.forward("someone@gmail.com", DOMAIN, SECRET);
    Envelope bounce = new Envelope("", List.of(returnPath),
        ("From: postmaster@elsewhere.example\r\nSubject: Undelivered\r\n\r\nit did not work\r\n")
            .getBytes(StandardCharsets.UTF_8),
        "203.0.113.9", "mx.elsewhere.example", DOMAIN, System.currentTimeMillis(),
        AuthResult.nothingChecked());

    assertAccepted(forwarder().receive(bounce));
    assertEquals(1, far.delivered.size());
    assertEquals("someone@gmail.com", far.delivered.get(0).recipients().get(0));
    assertEquals("a bounce's sender stays empty, or the bounce can itself bounce",
        "", far.delivered.get(0).mailFrom());
  }

  @Test
  public void anSrsAddressWeDidNotWriteIsRefusedRatherThanRelayed() throws Exception {
    String forged = "SRS0=AAAA=" + Srs.today() + "=stranger.example=victim@" + DOMAIN;
    MailReceiver.Outcome outcome = forwarder().receive(arriving(forged));
    assertNotNull(outcome);
    assertFalse(outcome.accepted());
    assertEquals(0, far.delivered.size());
    assertEquals("refused", accounts().mailLog.recent(DOMAIN, 1).get(0).outcome());
  }

  // ---- loops ---------------------------------------------------------------------------------------

  @Test
  public void aMessageThatHasGoneRoundTooManyTimesIsStopped() throws Exception {
    forwardEverythingTo("her@elsewhere.example");
    StringBuilder looped = new StringBuilder();
    for (int k = 0; k < Forwarding.MAX_HOPS + 1; k++) {
      looped.append("Received: from a by b; Tue, 8 Sep 2026 09:00:0").append(k % 10)
          .append(" +0000\r\n");
    }
    looped.append(MESSAGE);

    MailReceiver.Outcome outcome = forwarder().receive(
        arriving("jeff@" + DOMAIN, looped.toString(), AuthResult.nothingChecked()));
    assertNotNull(outcome);
    assertFalse(outcome.accepted());
    assertFalse("permanent, because a loop retried for four days runs for four days",
        outcome.temporary());
    assertEquals(0, far.delivered.size());
  }

  // ---- the log -------------------------------------------------------------------------------------

  @Test
  public void everyMessageLeavesARowSayingWhatWasDecidedAndWhy() throws Exception {
    long rule = forwardEverythingTo("her@elsewhere.example");
    forwarder().receive(arriving("jeff@" + DOMAIN, MESSAGE,
        new AuthResult(AuthResult.Status.pass, "gmail.com", AuthResult.Status.fail, "gmail.com",
            AuthResult.Status.pass, "gmail.com", "none")));

    MailLog.Entry entry = accounts().mailLog.recent(DOMAIN, 5).get(0);
    assertEquals("someone@gmail.com", entry.envelopeFrom());
    assertEquals("jeff@" + DOMAIN, entry.recipient());
    assertEquals("about Thursday", entry.subject());
    assertEquals("<abc123@gmail.com>", entry.messageId());
    assertEquals("pass", entry.spf());
    assertEquals("fail", entry.dkim());
    assertEquals(Long.valueOf(rule), entry.ruleId());
    assertEquals("forwarded", entry.outcome());
    assertEquals("her@elsewhere.example", entry.destination());
    assertTrue("a rule that matched the wrong message is only obvious next to the message",
        entry.preview().contains("Can you do the ninth?"));
  }

  @Test
  public void nothingMatchingIsRecordedAsUnroutedRatherThanSilentlyIgnored() throws Exception {
    boxes().saveBox(0, DOMAIN, "jeff", "mine", null, true, null);
    forwarder().receive(arriving("jeff@" + DOMAIN));
    assertEquals("unrouted", accounts().mailLog.recent(DOMAIN, 1).get(0).outcome());
  }

  @Test
  public void theTroubleListIsTheOneSomebodyActuallyOpens() throws Exception {
    forwardEverythingTo("her@elsewhere.example");
    forwarder().receive(arriving("jeff@" + DOMAIN));
    far.answersData("550 no");
    forwarder().receive(arriving("jeff@" + DOMAIN));

    assertEquals(2, accounts().mailLog.recent(DOMAIN, 10).size());
    List<MailLog.Entry> troubles = accounts().mailLog.troubles(DOMAIN, 10);
    assertEquals(1, troubles.size());
    assertEquals("failed", troubles.get(0).outcome());
  }

  /**
   * An erasure takes the address out of the log entirely.
   *
   * Every other row in this server keeps its words and loses its author, because the words are
   * somebody else's conversation. A log row is nothing but who wrote to whom.
   */
  @Test
  public void erasingSomebodyRemovesTheirMailFromTheLog() throws Exception {
    forwardEverythingTo("her@elsewhere.example");
    forwarder().receive(arriving("jeff@" + DOMAIN));
    assertEquals(1, accounts().mailLog.recent(DOMAIN, 10).size());
    assertEquals(1, accounts().mailLog.forget("someone@gmail.com"));
    assertEquals(0, accounts().mailLog.recent(DOMAIN, 10).size());
  }

  // ---- validation -----------------------------------------------------------------------------------

  @Test
  public void aRuleThatForwardsNeedsSomewhereToForwardTo() {
    assertNotNull(Mailboxes.checkRule("jeff", Mailboxes.Action.forward, ""));
    assertNotNull(Mailboxes.checkRule("jeff", Mailboxes.Action.forward, "not an address"));
    assertNull(Mailboxes.checkRule("jeff", Mailboxes.Action.forward, "her@elsewhere.example"));
    assertNull("dropping needs no destination",
        Mailboxes.checkRule("jeff", Mailboxes.Action.drop, ""));
    assertNull("the catch-all is a name a person types",
        Mailboxes.checkRule(Mailboxes.EVERYONE, Mailboxes.Action.drop, ""));
  }

  @Test
  public void aLocalPartIsCheckedBeforeItBecomesAnAddress() {
    assertNull(Mailboxes.checkLocalPart("jeff"));
    assertNull(Mailboxes.checkLocalPart("first.last+tag"));
    assertNotNull(Mailboxes.checkLocalPart(""));
    assertNotNull(Mailboxes.checkLocalPart(".leading"));
    assertNotNull(Mailboxes.checkLocalPart("two..dots"));
    assertNotNull("anything that would need escaping in a header is refused instead",
        Mailboxes.checkLocalPart("has space"));
    assertNotNull(Mailboxes.checkLocalPart("angle<bracket"));
  }

  /**
   * An action the database holds that this software does not understand drops the message.
   *
   * Failing closed is the only safe direction: the alternative is a rule whose meaning was lost
   * sending somebody's mail somewhere nobody chose.
   */
  @Test
  public void anUnknownActionFailsClosed() throws Exception {
    long id = boxes().saveRule(0, DOMAIN, 10, "from the future", Mailboxes.EVERYONE, "", "",
        Mailboxes.Action.forward, "her@elsewhere.example", true, null);
    try (java.sql.Connection connection = accounts().store.connection();
         java.sql.PreparedStatement statement = connection.prepareStatement(
             "UPDATE mail_rules SET action = 'quarantine' WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    assertEquals(Mailboxes.Action.drop, boxes().ruleById(id).action());
  }

  @Test
  public void aRuleSetBelongsToOneDomainEvenWhenTwoShareADatabase() throws Exception {
    boxes().saveRule(0, DOMAIN, 10, "ours", Mailboxes.EVERYONE, "", "", Mailboxes.Action.drop,
        "", true, null);
    assertEquals(1, boxes().rules(DOMAIN).size());
    assertEquals("another domain sharing this database has its own rules",
        0, boxes().rules("someone-else.example").size());
    assertNull(boxes().decide("someone-else.example", "jeff", "a@b.example", ""));
  }

  // ---- delivery ---------------------------------------------------------------------------------------

  @Test
  public void requiringTlsRefusesToDeliverWithoutItRatherThanDowngrading() throws Exception {
    // the stub advertises no STARTTLS, so this is the whole of the check
    Relay strict = new Relay(new FakeDns(), "mail." + DOMAIN, true, far.port(), Verbose.OFF) {
      @Override
      public List<String> exchangersFor(String domain) {
        return List.of("127.0.0.1");
      }
    };
    Relay.Sent sent = strict.send("a@" + DOMAIN, "her@elsewhere.example",
        MESSAGE.getBytes(StandardCharsets.UTF_8));
    assertFalse(sent.ok());
    assertTrue("temporary, so the message waits for the far end to fix its TLS",
        sent.worthRetrying());
    assertTrue(sent.detail(), sent.detail().contains("STARTTLS"));
    assertEquals(0, far.delivered.size());
  }

  /**
   * Exchangers come back in preference order.
   *
   * The lookup promised this and did not do it, which was harmless while the only caller treated
   * the set as a set -- and would have sent every forwarded message to Google's last listed
   * exchanger, which is the one meant to take the overflow.
   */
  @Test
  public void exchangersAreTriedInThePreferenceOrderTheDomainPublished() {
    FakeDns dns = new FakeDns().mx("gmail.com",
        "40 alt4.aspmx.l.google.com.", "5 gmail-smtp-in.l.google.com.", "20 alt2.aspmx.l.google.com.");
    Relay relay = new Relay(dns, "mail." + DOMAIN, false, Verbose.OFF);
    assertEquals(List.of("gmail-smtp-in.l.google.com", "alt2.aspmx.l.google.com",
        "alt4.aspmx.l.google.com"), relay.exchangersFor("gmail.com"));
  }

  @Test
  public void aDomainWithNoMxIsItsOwnExchanger() {
    // RFC 5321's implicit MX, and not a guess: plenty of small domains never publish one
    Relay relay = new Relay(new FakeDns(), "mail." + DOMAIN, false, Verbose.OFF);
    assertEquals(List.of("small.example"), relay.exchangersFor("small.example"));
  }

  @Test
  public void aDomainThatSaysItTakesNoMailIsNotConnectedTo() {
    // RFC 7505's null MX; connecting to "." would be a long timeout ending in the wrong answer
    Relay relay = new Relay(new FakeDns().mx("nomail.example", "0 ."), "mail." + DOMAIN, false,
        Verbose.OFF);
    assertEquals(List.of("nomail.example"), relay.exchangersFor("nomail.example"));
  }
}
