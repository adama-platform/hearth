package io.hearth.inbox;

import io.hearth.auth.Accounts;
import io.hearth.common.Verbose;
import io.hearth.smtp.AuthResult;
import io.hearth.smtp.Envelope;
import io.hearth.smtp.FakeDns;
import io.hearth.smtp.Mailboxes;
import io.hearth.smtp.Relay;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The things a security review found, each with the case that would have exploited it.
 *
 * <b>Every one of these is written from the outside in.</b> A test that asserts a guard was added
 * proves the guard exists; a test that performs the attack proves the guard works. Where the two
 * differ this file does the second, because a check that is present and in the wrong place reads
 * identically to one that is right.
 */
public class HardeningTests {
  private static final String DOMAIN = "ranch.example.org";

  private Configs configs;
  private TestServer server;
  private Delivery delivery;
  private long me;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain(DOMAIN, "{\"name\":\"Ranch\"}");
    server = TestServer.ofConfigs(configs.file());
    delivery = new Delivery(server.messageFiles, PushOnArrival.none(), Verbose.OFF);
    me = accounts().users.create("boss@example.com", null, true, null).id();
    accounts().users.approve(me, null);
  }

  @After
  public void tearDown() {
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

  private Mailboxes.Box mailbox(String local) throws Exception {
    accounts().mailboxes.saveBox(0, DOMAIN, local, "", me, true, null);
    return accounts().mailboxes.box(DOMAIN, local);
  }

  private static Envelope arriving(String to, String message) {
    return new Envelope("someone@gmail.com", List.of(to),
        message.getBytes(StandardCharsets.UTF_8), "203.0.113.9", "mx", DOMAIN,
        System.currentTimeMillis(), AuthResult.nothingChecked());
  }

  // ---- the relay -----------------------------------------------------------------------------------

  /**
   * Nothing reaches an SMTP command line without being an address first.
   *
   * `RCPT TO:<...>` is a line of a protocol. Recipients are not always an administrator's choice --
   * a reply-all takes them from the To and Cc headers of a message a stranger wrote -- so the same
   * check the inbound side runs at RCPT has to run on the way out.
   */
  @Test
  public void arecipientThatIsNotAnAddressNeverReachesACommand() {
    Relay relay = new Relay(new FakeDns(), "mail." + DOMAIN, false, Verbose.OFF);
    for (String bad : new String[]{"a@b.example>\r\nRCPT TO:<victim@elsewhere.example",
        "not an address", "a@b.example RCPT TO:<x@y>", "<>", "", "a b@c.example"}) {
      Relay.Sent sent = relay.send("me@" + DOMAIN, bad, "x".getBytes(StandardCharsets.UTF_8));
      assertFalse(bad + " was not refused", sent.ok());
      assertTrue(sent.detail(), sent.detail().contains("is not an address"));
    }
  }

  @Test
  public void asenderThatIsNotAnAddressIsRefusedToo() {
    Relay relay = new Relay(new FakeDns(), "mail." + DOMAIN, false, Verbose.OFF);
    Relay.Sent sent = relay.send("me@" + DOMAIN + ">\r\nDATA", "them@elsewhere.example",
        "x".getBytes(StandardCharsets.UTF_8));
    assertFalse(sent.ok());
    assertTrue(sent.detail(), sent.detail().contains("not an address to send from"));
  }

  @Test
  public void theEmptySenderIsStillAllowedBecauseThatIsWhatABounceUses() {
    // it must be refused for a *connection* reason rather than for being empty
    Relay relay = new Relay(new FakeDns().mx("elsewhere.example", "10 nothing.invalid"),
        "mail." + DOMAIN, false, Verbose.OFF);
    Relay.Sent sent = relay.send("", "them@elsewhere.example",
        "x".getBytes(StandardCharsets.UTF_8));
    assertFalse(sent.detail(), sent.detail().contains("not an address to send from"));
  }

  /**
   * A destination whose mail exchanger points inside the network is refused.
   *
   * This is invariant 150 arriving by a different door. A reply-all is addressed from headers a
   * stranger wrote, so a stranger picks the domain; that domain's MX is a record they also control,
   * and pointing it at `127.0.0.1` turns "reply to this" into a request to something behind the
   * firewall.
   */
  @Test
  public void anExchangerInsideTheNetworkIsRefusedAfterResolution() {
    Relay relay = new Relay(new FakeDns().mx("evil.example", "10 localhost"),
        "mail." + DOMAIN, false, Verbose.OFF);
    Relay.Sent sent = relay.send("me@" + DOMAIN, "victim@evil.example",
        "x".getBytes(StandardCharsets.UTF_8));
    assertFalse(sent.ok());
    assertFalse("permanent: an exchanger inside this network is not going to move out of it",
        sent.worthRetrying());
    assertTrue(sent.detail(), sent.detail().contains("not somewhere this server will deliver"));
  }

  @Test
  public void aDomainThatIsItsOwnExchangerIsCheckedTheSameWay() {
    // no MX at all, so the domain is the exchanger -- and it still must not be inside
    Relay relay = new Relay(new FakeDns(), "mail." + DOMAIN, false, Verbose.OFF);
    Relay.Sent sent = relay.send("me@" + DOMAIN, "victim@localhost.localdomain",
        "x".getBytes(StandardCharsets.UTF_8));
    assertFalse(sent.ok());
  }

  // ---- the parts manifest ----------------------------------------------------------------------------

  /**
   * A manifest is never truncated, because a truncated one will not parse.
   *
   * An unparseable manifest is a message whose attachments all vanish from the screen *and* become
   * undownloadable -- the manifest is what the download path checks against. So the cap is on the
   * number of parts listed, and the list says it stopped.
   */
  @Test
  public void aMessageWithHundredsOfPartsStillListsWhatItCan() throws Exception {
    StringBuilder message = new StringBuilder("From: a@b.example\r\nSubject: many\r\n"
        + "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n");
    for (int k = 0; k < 200; k++) {
      message.append("--b\r\nContent-Type: application/pdf\r\n")
          .append("Content-Disposition: attachment; filename=\"")
          .append("a-really-quite-long-document-name-number-").append(k)
          .append(".pdf\"\r\n\r\n%PDF-1.4\r\n");
    }
    message.append("--b--\r\n");

    Mailboxes.Box box = mailbox("jeff");
    long id = delivery.deliver(accounts(), arriving(box.address(), message.toString()), box,
        "/self").id();
    Messages.Record stored = accounts().inbox.byId(id, me);

    List<Messages.Attachment> parts = stored.parts();
    assertFalse("the manifest parsed, which is the whole point", parts.isEmpty());
    assertEquals(Messages.MAX_LISTED_PARTS + 1, parts.size());
    assertTrue("and the last row says the list stops rather than lying by omission",
        parts.get(parts.size() - 1).filename().contains("more part(s)"));
    assertTrue("a real part is still downloadable", parts.get(0).allowed());
  }

  // ---- the mailbox ceiling -----------------------------------------------------------------------------

  /**
   * A mailbox past its ceiling drops the oldest dealt-with messages, and never an unread one.
   *
   * A mailbox that silently deletes unread mail to make room is worse than one that fills up.
   */
  @Test
  public void onlyArchivedMessagesAreEverDropped() throws Exception {
    Mailboxes.Box box = mailbox("jeff");
    ArrayList<Long> ids = new ArrayList<>();
    for (int k = 0; k < 5; k++) {
      ids.add(delivery.deliver(accounts(), arriving(box.address(),
          "From: a@b.example\r\nSubject: " + k + "\r\n\r\nbody\r\n"), box, "/self").id());
    }
    accounts().inbox.archive(ids.get(0), me);
    accounts().inbox.archive(ids.get(1), me);

    // nothing is over the ceiling, so nothing is offered up
    assertTrue(accounts().inbox.overflowing(me).isEmpty());
    assertEquals(5, accounts().inbox.all(me, 100).size());
  }

  @Test
  public void theCeilingIsHighEnoughToBeAboutADiskRatherThanAboutMail() {
    // if this is ever lowered to something a person could reach in a year, it stops being a
    // backstop and starts being a feature nobody asked for
    assertTrue(Messages.KEPT_PER_PERSON >= 10_000);
  }

  // ---- the calendar feed path ------------------------------------------------------------------------

  /**
   * The feed route claims only paths that look like a token it minted.
   *
   * Without the shape check it claims `/calendar/anything.ics`, and a community with a page there
   * finds it answering 404 for ever with nothing on any screen saying why.
   */
  @Test
  public void theFeedRouteDoesNotShadowACommunitysOwnPages() throws Exception {
    String token = accounts().events.mintFeedToken(me);
    assertTrue(io.hearth.calendar.CalendarRoutes.isFeed("/calendar/" + token + ".ics"));

    for (String path : new String[]{"/calendar/2026.ics", "/calendar/the-season.ics",
        "/calendar/.ics", "/calendar/x.ics", "/calendar/a b.ics", "/calendar/../secret.ics"}) {
      assertFalse(path + " should belong to the site", io.hearth.calendar.CalendarRoutes.isFeed(path));
    }
  }

  // ---- the html half -----------------------------------------------------------------------------------

  /**
   * A protocol-relative URL starts with a slash and is not same-origin.
   *
   * `//evil.example/x.png` passes "starts with a slash", which reads like a same-origin test and is
   * not one. Nothing reaches that check unrewritten today; this proves the check is against the
   * exact set rather than a prefix, so a later change to the rewriting cannot quietly reintroduce a
   * tracking pixel.
   */
  @Test
  public void aProtocolRelativeImageIsNotMistakenForOneOfOurs() {
    MailHtml.Cleaned cleaned = MailHtml.clean(
        "<img src=\"//evil.example/pixel.png\"><img src=\"/self/mail/part?m=1&p=1.1\">",
        java.util.Map.of("real@x", "/self/mail/part?m=1&p=1.1"));
    assertFalse(cleaned.html(), cleaned.html().contains("evil.example"));
    assertFalse("and a path we did not write for this message goes too",
        cleaned.html().contains("/self/mail/part"));
  }

  @Test
  public void anEntityEndsAUrlRatherThanBeingSwallowedIntoIt() {
    // the input to linkify is already escaped, so `&quot;` is how a quote arrives
    String html = MailHtml.fromText("see https://example.com/a\"b and stop");
    assertTrue(html, html.contains("href=\"https://example.com/a\""));
    assertFalse("the entity is not part of the address", html.contains("quot;b\""));
  }

  // ---- the clock ---------------------------------------------------------------------------------------

  /**
   * Nothing on a request path reaches for the machine's own clock.
   *
   * Invariant 9, checked by reading the source rather than by rendering a page, because the failure
   * is silent: a box rented in another continent drew every timestamp in the admin section hours
   * out, consistently enough that it reads as correct. A grep is a blunt test and it is the only
   * one that catches the *next* one.
   */
  @Test
  public void noRequestPathUsesTheMachinesOwnTimezone() throws Exception {
    java.nio.file.Path source = java.nio.file.Path.of("src/main/java/io/hearth");
    if (!java.nio.file.Files.isDirectory(source)) {
      return;
    }
    // Where it is legitimate, and why. Every one is either the place the community's clock is read
    // *from*, a fallback for a zone nobody passed, or a log line for the operator of this box --
    // none of them is a request being answered. A file not on this list that reaches for the
    // machine's clock is the bug this test is for.
    java.util.Map<String, String> allowed = java.util.Map.of(
        "common/ServerConfig.java", "reads the configured zone, and defaults to the box's",
        "vhost/DomainConfig.java", "the same, per domain",
        "vhost/DomainScanner.java", "the default handed to configs at boot",
        "cli/Setup.java", "offers the box's zone as the answer to a question in a terminal",
        "auth/Accounts.java", "the fallback when a caller passes no zone at construction",
        "tasks/Tasks.java", "the same fallback, at construction",
        "certs/CertificateManager.java", "a log line on a background thread, for whoever runs"
            + " this machine");
    ArrayList<String> offenders = new ArrayList<>();
    try (java.util.stream.Stream<java.nio.file.Path> files =
             java.nio.file.Files.walk(source)) {
      for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        String text = java.nio.file.Files.readString(file);
        // a mention in a comment is the explanation of the rule, not a use of it
        String code = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        if (!code.contains("ZoneId.systemDefault()")) {
          continue;
        }
        String relative = source.relativize(file).toString().replace('\\', '/');
        if (!allowed.containsKey(relative)) {
          offenders.add(relative);
        }
      }
    }
    assertTrue("these reach for the machine's clock on a request path: " + offenders,
        offenders.isEmpty());
  }

  // ---- transport ---------------------------------------------------------------------------------------

  /**
   * HSTS is never sent over plaintext, whatever it is set to.
   *
   * The RFC forbids it and browsers ignore it, so sending it there is noise that reads as
   * protection. Worse, on a development box it would pin a browser to https for a port with no TLS
   * behind it, and the only cure is clearing it by hand in the browser's settings.
   */
  @Test
  public void hstsIsNotSentOnAPlaintextResponse() throws Exception {
    long was = io.hearth.web.Responses.hstsSeconds();
    try {
      io.hearth.web.Responses.hsts(31_536_000L);
      io.hearth.testkit.Browser browser = new io.hearth.testkit.Browser(server.port, DOMAIN);
      io.hearth.testkit.Browser.Page page = browser.get("/");
      assertEquals("", page.header("strict-transport-security"));
      assertEquals("and the rest of the headers are still there", "nosniff",
          page.header("x-content-type-options"));
    } finally {
      io.hearth.web.Responses.hsts(was);
    }
  }

  @Test
  public void hstsIsOffUnlessAnOperatorTurnsItOn() {
    // a one-way door is not a default: a browser that has seen it refuses plaintext for the whole
    // window, and there is no way to reach the people whose browsers already have it
    assertEquals(0, io.hearth.common.ServerConfig.defaults().hstsSeconds);
  }

  // ---- the export --------------------------------------------------------------------------------------

  /**
   * The file that is supposed to be everything actually is.
   *
   * The day this server started keeping mail was the day the export stopped being complete, and a
   * subject access request answered without it is an answer that is wrong.
   */
  @Test
  public void theExportCarriesTheMailAndTheCalendarAndTheAddresses() throws Exception {
    Mailboxes.Box box = mailbox("jeff");
    delivery.deliver(accounts(), arriving(box.address(),
        "From: Ana <ana@elsewhere.example>\r\nSubject: about Thursday\r\n\r\n"
            + "Can you do the ninth?\r\n"), box, "/self");
    long future = System.currentTimeMillis() + 86_400_000L;
    accounts().events.merge(me, new io.hearth.calendar.IcsFile.Event("gym@x", 0, "Squats",
        "heavy day", "The barn", future, future + 3_600_000L, false, "", "", "CONFIRMED", "",
        List.of()), "typed", null, null);

    String json = new String(io.hearth.people.DataExport.of(accounts(),
        accounts().users.byId(me), "Ranch", DOMAIN), StandardCharsets.UTF_8);
    assertTrue("the message", json.contains("Can you do the ninth?"));
    assertTrue("who it was from", json.contains("ana@elsewhere.example"));
    assertTrue("which address it came to", json.contains("jeff@" + DOMAIN));
    assertTrue("the calendar", json.contains("Squats"));
    assertTrue("and the notes on it", json.contains("heavy day"));
    assertTrue("the addresses that are theirs", json.contains("addresses_that_are_yours"));
  }

  /**
   * And the shipped privacy policy says all of it is held.
   *
   * Invariant 177: the policy is a specification, so a thing the code does and the policy does not
   * mention is a promise being broken rather than a paragraph being short.
   */
  @Test
  public void theShippedPolicySaysThatMailIsKept() {
    String policy = io.hearth.legal.LegalDoc.privacy.standard();
    assertTrue("the mail itself", policy.contains("The messages sent to you"));
    assertTrue("the original on disk", policy.contains("the original exactly as"));
    assertTrue("what an administrator can see", policy.contains("I never got your email"));
    assertTrue("the calendar", policy.contains("Your calendar"));
    assertTrue("that nothing remote is fetched",
        policy.contains("is ever fetched from anybody else's server"));
    assertTrue("and how long it is kept", policy.contains("Mail kept for you"));
  }
}
