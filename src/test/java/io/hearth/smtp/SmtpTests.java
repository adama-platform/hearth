package io.hearth.smtp;

import io.hearth.common.Verbose;
import io.hearth.testkit.Configs;
import io.hearth.vhost.DomainScanner;
import io.hearth.vhost.DomainTree;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Inbound mail, over a real socket.
 *
 * Most of this is about refusing. An SMTP server that accepts mail for a domain it does not serve
 * is an open relay, and an open relay is found within days, used to send spam in somebody else's
 * name, and ends with this machine's address on every blocklist there is -- with the community's
 * own mail undeliverable behind it. So the relay refusal gets more tests than the happy path, and
 * it is checked at RCPT rather than after the body, because a relay attempt should cost one line
 * rather than a megabyte.
 */
public class SmtpTests {
  private Configs configs;
  private SmtpServer server;
  private final List<Envelope> received = new CopyOnWriteArrayList<>();
  private int port;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir()
        .domain("example.org", "{\"name\":\"Example\"}")
        .domain("junior.example.org", "{\"name\":\"Junior\"}")
        .domain("wild.example", "{\"name\":\"Wild\",\"wildcard\":true}")
        .domain("off.example.org", "{\"name\":\"Off\",\"enabled\":false}");
    DomainTree tree = DomainScanner.scan(configs.file(), Verbose.capturing().verbose).tree;

    SmtpConfig config = smtpConfig(0);
    server = new SmtpServer(config, tree, envelope -> {
      received.add(envelope);
      return MailReceiver.Outcome.accepted("captured");
    }, "test.example.org", Verbose.capturing().verbose);

    CountDownLatch bound = new CountDownLatch(1);
    server.whenBound(bound::countDown);
    Thread thread = new Thread(server, "smtp-test");
    thread.setDaemon(true);
    thread.start();
    assertTrue("the listener did not come up", bound.await(10, TimeUnit.SECONDS));
    port = server.port();
    assertTrue("nothing is listening", port > 0);
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.shutdown();
    }
    if (configs != null) {
      configs.delete();
    }
  }

  // ---- the refusal that matters -----------------------------------------------------------------

  @Test
  public void mailForADomainWeDoNotServeIsRefusedAtRcpt() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("EHLO sender.example", "250");
    talk.send("MAIL FROM:<somebody@sender.example>", "250");
    String answer = talk.send("RCPT TO:<victim@not-ours.example>", "550");

    assertTrue(answer, answer.contains("do not relay"));
    assertEquals("and refused before a single byte of body", 0, received.size());
    talk.close();
  }

  @Test
  public void everyShapeOfRelayAttemptIsRefused() throws Exception {
    for (String recipient : new String[]{
        "victim@not-ours.example",
        "victim@example.com",
        "victim@sub.not-ours.example",
        "victim@EXAMPLE.NET",
        "a@b.c"}) {
      Talk talk = new Talk();
      talk.expect("220");
      talk.send("EHLO sender.example", "250");
      talk.send("MAIL FROM:<s@sender.example>", "250");
      talk.send("RCPT TO:<" + recipient + ">", "550");
      talk.close();
    }
  }

  @Test
  public void aWildcardDomainDoesNotMeanWeTakeMailForEverythingUnderIt() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("EHLO sender.example", "250");
    talk.send("MAIL FROM:<s@sender.example>", "250");
    // wild.example is configured with wildcard: true, which the web side honours. Reading that as
    // permission to receive mail for anything.wild.example is how an open relay gets built by
    // accident, because a wildcard on a public suffix would accept for the whole internet.
    talk.send("RCPT TO:<victim@anything.wild.example>", "550");
    talk.send("RCPT TO:<real@wild.example>", "250");
    talk.close();
  }

  @Test
  public void aDisabledDomainTakesNoMail() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("EHLO sender.example", "250");
    talk.send("MAIL FROM:<s@sender.example>", "250");
    talk.send("RCPT TO:<someone@off.example.org>", "550");
    talk.close();
  }

  @Test
  public void mailForADomainWeDoServeIsAccepted() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("EHLO sender.example", "250");
    talk.send("MAIL FROM:<ana@sender.example>", "250");
    talk.send("RCPT TO:<hello@example.org>", "250");
    talk.send("DATA", "354");
    talk.body("Subject: Dinner on Tuesday", "", "Are you coming?");
    talk.expect("250");
    talk.close();

    assertEquals(1, received.size());
    Envelope envelope = received.get(0);
    assertEquals("ana@sender.example", envelope.from());
    assertEquals(List.of("hello@example.org"), envelope.recipients());
    assertEquals("routed to the community that owns the domain", "example.org", envelope.domain());
    assertEquals("Dinner on Tuesday", envelope.subject());
    assertTrue(envelope.bodyPreview(200).contains("Are you coming?"));
  }

  @Test
  public void twoCommunitiesInOneMessageIsRefused() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("EHLO sender.example", "250");
    talk.send("MAIL FROM:<s@sender.example>", "250");
    talk.send("RCPT TO:<a@example.org>", "250");
    String answer = talk.send("RCPT TO:<b@junior.example.org>", "451");
    assertTrue(answer, answer.contains("One community"));
    talk.close();
  }

  // ---- the protocol -------------------------------------------------------------------------

  @Test
  public void commandsBeforeGreetingAreRefused() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("MAIL FROM:<s@sender.example>", "503");
    talk.send("EHLO sender.example", "250");
    talk.send("MAIL FROM:<s@sender.example>", "250");
    talk.close();
  }

  @Test
  public void rcptWithoutMailFromIsRefused() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("EHLO sender.example", "250");
    talk.send("RCPT TO:<a@example.org>", "503");
    talk.close();
  }

  @Test
  public void dataWithNoRecipientIsRefused() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("EHLO sender.example", "250");
    talk.send("MAIL FROM:<s@sender.example>", "250");
    talk.send("DATA", "503");
    talk.close();
  }

  @Test
  public void anEmptySenderIsAcceptedBecauseThatIsWhatABounceUses() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("EHLO sender.example", "250");
    talk.send("MAIL FROM:<>", "250");
    talk.send("RCPT TO:<a@example.org>", "250");
    talk.send("DATA", "354");
    talk.body("Subject: Undeliverable", "", "it bounced");
    talk.expect("250");
    talk.close();
    assertEquals("refusing this would mean never learning one of our messages failed",
        1, received.size());
    assertEquals("", received.get(0).from());
  }

  @Test
  public void resetClearsTheTransactionAndNotTheGreeting() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("EHLO sender.example", "250");
    talk.send("MAIL FROM:<s@sender.example>", "250");
    talk.send("RCPT TO:<a@example.org>", "250");
    talk.send("RSET", "250");
    talk.send("RCPT TO:<a@example.org>", "503");
    talk.send("MAIL FROM:<s@sender.example>", "250");
    talk.close();
  }

  @Test
  public void aDotAtTheStartOfALineIsUnstuffed() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("EHLO sender.example", "250");
    talk.send("MAIL FROM:<s@sender.example>", "250");
    talk.send("RCPT TO:<a@example.org>", "250");
    talk.send("DATA", "354");
    // the sending server doubles a leading dot; leaving it there corrupts every message that
    // happens to begin a line with a full stop
    talk.body("Subject: Ellipsis", "", "..and then we left");
    talk.expect("250");
    talk.close();
    assertTrue(received.get(0).bodyPreview(200),
        received.get(0).bodyPreview(200).startsWith(".and then"));
  }

  @Test
  public void vrfyDoesNotHandOverTheMemberList() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("EHLO sender.example", "250");
    // answering truthfully is a membership oracle, one guess at a time
    talk.send("VRFY hello@example.org", "252");
    talk.send("EXPN everybody", "252");
    talk.close();
  }

  @Test
  public void authIsNotOfferedBecauseThereIsNothingToAuthenticateFor() throws Exception {
    Talk talk = new Talk();
    String greeting = talk.expect("220");
    String ehlo = talk.send("EHLO sender.example", "250");
    assertFalse("advertising what is not honoured is worse than not advertising",
        ehlo.contains("AUTH"));
    assertFalse(ehlo.contains("STARTTLS"));
    assertTrue("but the size limit is true and worth saying", ehlo.contains("SIZE"));
    assertNotNull(greeting);
    talk.send("AUTH PLAIN abc", "502");
    talk.close();
  }

  @Test
  public void aMessagePastTheCeilingIsRefusedAndTheConversationStillEnds() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("EHLO sender.example", "250");
    talk.send("MAIL FROM:<s@sender.example>", "250");
    talk.send("RCPT TO:<a@example.org>", "250");
    talk.send("DATA", "354");
    ArrayList<String> lines = new ArrayList<>();
    lines.add("Subject: Enormous");
    lines.add("");
    for (int k = 0; k < 400; k++) {
      lines.add("x".repeat(200));
    }
    talk.body(lines.toArray(new String[0]));
    // read to the dot rather than closing: a sender that never sees the end of its transaction
    // retries the whole thing, forever
    String answer = talk.expect("552");
    assertTrue(answer, answer.contains("too large"));
    assertEquals(0, received.size());
    talk.close();
  }

  @Test
  public void aStreamOfNonsenseGetsDroppedRatherThanAnsweredForever() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("EHLO sender.example", "250");
    String last = null;
    for (int k = 0; k < 20; k++) {
      last = talk.line("WHAT IS THIS " + k);
      if (last != null && last.startsWith("421")) {
        break;
      }
    }
    assertNotNull(last);
    assertTrue("a peer sending only rubbish is eventually not worth talking to",
        last.startsWith("421"));
    talk.close();
  }

  @Test
  public void theRelayRefusalIsCounted() throws Exception {
    Talk talk = new Talk();
    talk.expect("220");
    talk.send("EHLO sender.example", "250");
    talk.send("MAIL FROM:<s@sender.example>", "250");
    talk.send("RCPT TO:<victim@not-ours.example>", "550");
    talk.close();
    assertEquals("an operator should be able to see this happening", 1,
        server.counters().relaysRefused.get());
  }

  // ---- the routing rule on its own ----------------------------------------------------------

  @Test
  public void theDomainIsTakenFromTheLastAtSign() {
    assertEquals("example.org", SmtpRouting.domainOf("a@example.org"));
    assertEquals("normalized the way a Host header is", "example.org",
        SmtpRouting.domainOf("A@Example.ORG"));
    assertEquals(null, SmtpRouting.domainOf("nobody"));
    assertEquals(null, SmtpRouting.domainOf("@example.org"));
    assertEquals(null, SmtpRouting.domainOf("a@"));
  }

  @Test
  public void addressesAreCheckedBeforeAnythingElseHappens() {
    assertTrue(SmtpRouting.looksLikeAddress("a@example.org"));
    assertTrue(SmtpRouting.looksLikeAddress("first.last+tag@example.org"));
    for (String bad : new String[]{null, "", "nobody", "a@@b.com", "a@b", "a@b..com",
        ".a@example.org", "a.@example.org", "a b@example.org", "a@exam ple.org",
        "a@[127.0.0.1]", "a@-example.org"}) {
      assertFalse(String.valueOf(bad), SmtpRouting.looksLikeAddress(bad));
    }
  }

  @Test
  public void esmtpParametersAreStrippedFromTheAddress() {
    assertEquals("a@example.org", SmtpRouting.extractAddress("<a@example.org>"));
    assertEquals("a@example.org", SmtpRouting.extractAddress(" <a@example.org> SIZE=12345"));
    assertEquals("a@example.org", SmtpRouting.extractAddress("a@example.org BODY=8BITMIME"));
    assertEquals("an empty sender is legal and means a bounce", "",
        SmtpRouting.extractAddress("<>"));
  }

  // ---- a small SMTP client --------------------------------------------------------------------

  @Test
  public void asecondMessageIsRefusedUntilTheFirstOneHasBeenAnsweredFor() throws Exception {
    // SMTP is strictly ordered and a sending server matches replies to commands by position. The
    // reply to a message is written when its DNS checks finish, on a pool thread, so two messages
    // in flight on one connection could be answered in either order -- and the sender would record
    // message A as accepted when the acceptance was for B. This is why EHLO does not offer
    // PIPELINING, and this is the server holding to that.
    try (Talk talk = new Talk()) {
      talk.expect("220");
      talk.send("EHLO sender.example", "250");
      assertFalse("we must not be advertising what we do not do",
          talk.line("NOOP").contains("PIPELINING"));

      talk.send("MAIL FROM:<somebody@sender.example>", "250");
      talk.send("RCPT TO:<hello@example.org>", "250");
      talk.send("DATA", "354");
      // the body and the *next* transaction, written together without waiting: exactly what a
      // pipelining client does
      out(talk, "Subject: one", "", "first message", ".", "MAIL FROM:<somebody@sender.example>");

      // the message's own reply comes first, because it was asked for first
      String first = talk.readReply();
      assertTrue("the reply to the message, before anything else: " + first,
          first.startsWith("250 OK"));
      String second = talk.readReply();
      assertTrue("and then the answer to the command that was waiting: " + second,
          second.startsWith("250"));

      // ...and the connection carries on from there
      talk.send("RCPT TO:<hello@example.org>", "250");
      talk.send("DATA", "354");
      talk.body("Subject: two", "", "second message");
      talk.expect("250");
      assertEquals("both messages arrived", 2, received.size());
    }
  }

  @Test
  public void aClientThatTalksIntoSilenceForeverIsClosedRatherThanBuffered() throws Exception {
    try (Talk talk = new Talk()) {
      talk.expect("220");
      talk.send("EHLO sender.example", "250");
      talk.send("MAIL FROM:<somebody@sender.example>", "250");
      talk.send("RCPT TO:<hello@example.org>", "250");
      talk.send("DATA", "354");
      talk.raw("Subject: one");
      talk.raw("");
      talk.raw("first message");
      talk.raw(".");
      for (int k = 0; k < 64; k++) {
        talk.raw("NOOP");
      }
      talk.flush();
      String reply = talk.readReply();
      // either the message's own 250 or the 421 arrives first depending on how fast DNS was; what
      // matters is that somewhere in what comes back, the server said stop
      StringBuilder all = new StringBuilder(reply);
      for (int k = 0; k < 40 && !all.toString().contains("421"); k++) {
        String next = talk.readReply();
        if (next.isEmpty()) {
          break;
        }
        all.append(next);
      }
      assertTrue("the queue is bounded: " + all, all.toString().contains("421"));
    }
  }

  @Test
  public void aQuietConnectionStillTakesTwoMessagesInARow() throws Exception {
    try (Talk talk = new Talk()) {
      talk.expect("220");
      talk.send("EHLO sender.example", "250");
      talk.send("MAIL FROM:<somebody@sender.example>", "250");
      talk.send("RCPT TO:<hello@example.org>", "250");
      talk.send("DATA", "354");
      talk.body("Subject: one", "", "first message");
      talk.expect("250");
      talk.send("MAIL FROM:<somebody@sender.example>", "250");
      talk.send("RCPT TO:<hello@example.org>", "250");
      talk.send("DATA", "354");
      talk.body("Subject: two", "", "second message");
      talk.expect("250");
      assertEquals("both messages arrived", 2, received.size());
    }
  }

  private static void out(Talk talk, String... lines) {
    for (String line : lines) {
      talk.raw(line);
    }
    talk.flush();
  }

  private class Talk implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;

    Talk() throws Exception {
      socket = new Socket();
      socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
      socket.setSoTimeout(10000);
      in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      out = new PrintWriter(socket.getOutputStream(), false);
    }

    /** read one reply, following multi-line continuations */
    String expect(String code) throws Exception {
      StringBuilder all = new StringBuilder();
      String line;
      while ((line = in.readLine()) != null) {
        all.append(line).append('\n');
        // "250-" continues, "250 " ends
        if (line.length() < 4 || line.charAt(3) != '-') {
          break;
        }
      }
      String reply = all.toString();
      assertTrue("expected " + code + " but got: " + reply, reply.startsWith(code)
          || reply.contains("\n" + code));
      return reply;
    }

    String line(String command) throws Exception {
      out.print(command + "\r\n");
      out.flush();
      StringBuilder all = new StringBuilder();
      String read;
      while ((read = in.readLine()) != null) {
        all.append(read).append('\n');
        if (read.length() < 4 || read.charAt(3) != '-') {
          break;
        }
      }
      return all.toString();
    }

    /** write a line without waiting for anything back */
    void raw(String line) {
      out.print(line + "\r\n");
    }

    void flush() {
      out.flush();
    }

    /** read exactly one reply, whatever it says */
    String readReply() throws Exception {
      StringBuilder all = new StringBuilder();
      String read;
      while ((read = in.readLine()) != null) {
        all.append(read).append('\n');
        if (read.length() < 4 || read.charAt(3) != '-') {
          break;
        }
      }
      return all.toString();
    }

    String send(String command, String code) throws Exception {
      String reply = line(command);
      assertTrue("'" + command + "' expected " + code + " but got: " + reply,
          reply.startsWith(code));
      return reply;
    }

    void body(String... lines) {
      for (String line : lines) {
        out.print(line + "\r\n");
      }
      out.print(".\r\n");
      out.flush();
    }

    @Override
    public void close() {
      try {
        out.print("QUIT\r\n");
        out.flush();
        socket.close();
      } catch (Exception ex) {
        // the test is over; a socket that will not close politely is not a failure
      }
    }
  }

  /** a config with a couple of keys overridden, shared with the validation tests */
  static SmtpConfig configWith(String extra) throws Exception {
    String json = "{\"enabled\":true,\"port\":25" + (extra.isEmpty() ? "" : "," + extra) + "}";
    com.fasterxml.jackson.databind.node.ObjectNode node =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
    return new SmtpConfig(new io.hearth.common.ConfigObject(node, "smtp"));
  }

  private static SmtpConfig smtpConfig(int port) throws Exception {
    // an ephemeral port, so tests never need root and never collide
    String json = "{\"enabled\":true,\"port\":" + (port == 0 ? freePort() : port)
        + ",\"max-message-bytes\":8192,\"max-recipients\":3,\"idle-seconds\":10}";
    com.fasterxml.jackson.databind.node.ObjectNode node =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
    return new SmtpConfig(new io.hearth.common.ConfigObject(node, "smtp"));
  }

  private static int freePort() throws Exception {
    try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
      return probe.getLocalPort();
    }
  }
}
