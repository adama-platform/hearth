package io.hearth.smtp;

import io.hearth.auth.Accounts;
import io.hearth.auth.AuthSystem;
import io.hearth.common.Verbose;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The receiver that reads the rules and sends mail on, and the one place that decides what a
 * message is for.
 *
 * <b>It forwards before it answers, and everything else here follows from that.</b> The obvious
 * design is to say 250, put the message in a queue and deliver it later; this one holds the
 * conversation open, delivers, and hands the far end's own verdict back to the sending server. It
 * costs a few seconds of a socket and it buys the three properties a forwarder is otherwise unable
 * to have:
 *
 * <ul>
 *   <li><b>No backscatter, ever.</b> Once this server has said 250 it has taken responsibility, and
 *       a message it then cannot deliver has to go somewhere -- which means mailing a failure report
 *       to a return path a spammer chose. Passing Google's 550 straight back means the *sender's*
 *       server writes the bounce, to the address it actually sent from.</li>
 *   <li><b>No queue.</b> A temporary failure becomes a 451 to the sending server, which already has
 *       a retry schedule, already holds the message, and is already better at this than anything
 *       written here would be. There is no spool on disk, nothing to supervise and nothing to lose
 *       in a restart.</li>
 *   <li><b>No lost mail.</b> A message is either delivered onward or never accepted. There is no
 *       third state in which this server is the only thing holding it.</li>
 * </ul>
 *
 * <b>The message is not modified.</b> Headers are prepended -- Received, the ARC set, a DKIM
 * signature of our own -- and not one byte below them changes. The original signature is the
 * strongest thing a forwarded message carries; a footer, a subject tag or a re-encode destroys it
 * and leaves a message failing both SPF and DKIM at the far end, which is how a forwarder gets its
 * mail filed as spam and never finds out why.
 *
 * <b>An address that does not exist is refused at RCPT.</b> Before any data arrives, so a directory
 * harvester costs one line, and so this server never accepts mail it has nowhere to put.
 */
public class Forwarding implements MailReceiver {
  /**
   * How many hops a message may already have taken.
   *
   * A forwarding loop -- this box to Google, a Google rule back to this domain, round again -- is
   * the classic way to turn two mail systems into an amplifier. RFC 5321 suggests 100; a message
   * that has legitimately been through thirty relays does not exist in this setting, and the lower
   * number means a loop is broken in minutes rather than hours.
   */
  public static final int MAX_HOPS = 30;
  private static final DateTimeFormatter RFC5322 =
      DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US);

  private final AuthSystem auth;
  private final ForwardConfig config;
  private final MailKeys keys;
  private final Relay relay;
  private final MailReceiver fallback;
  private final Verbose verbose;

  /**
   * @param keys     the signing key, or null when none could be opened -- mail is still forwarded,
   *                 unsigned and unsealed, because an unsigned forward is worth far more than a
   *                 refused one and the boot report has already complained about it.
   * @param fallback what handles a message no rule forwards; the terminal receiver in development,
   *                 so a domain with no rules behaves exactly as it did before this existed.
   */
  public Forwarding(AuthSystem auth, ForwardConfig config, MailKeys keys, Relay relay,
                    MailReceiver fallback, Verbose verbose) {
    this.auth = auth;
    this.config = config;
    this.keys = keys;
    this.relay = relay;
    this.fallback = fallback;
    this.verbose = verbose;
  }

  /**
   * Is there anywhere for this to go?
   *
   * <b>Consulted at RCPT, before a byte of the message arrives.</b> A 550 here is what tells a
   * sending server the address does not exist, which is what stops a mistyped address disappearing
   * silently -- and it is what keeps this server from ever accepting mail it has no rule for.
   *
   * The one deliberate softness: when a domain has no rules and no mailboxes at all, everything is
   * accepted. A domain that has not been configured for mail yet behaves as it did before this
   * feature existed, rather than refusing every message with an error its owner has no way to
   * interpret.
   */
  @Override
  public boolean accepts(String domain, String recipient) {
    Accounts accounts = accountsFor(domain);
    if (accounts == null || accounts.mailboxes == null) {
      return true;
    }
    try {
      String local = localPartOf(recipient);
      if (Srs.looksLikeOurs(recipient, domain)) {
        // a bounce coming home to an address this server wrote; it is checked properly on the way
        // through, and refusing it here would mean never learning that a forward failed
        return true;
      }
      boolean anythingConfigured = accounts.mailboxes.countRules(domain) > 0
          || !accounts.mailboxes.boxes(domain).isEmpty();
      if (!anythingConfigured) {
        return true;
      }
      if (accounts.mailboxes.decide(domain, local, "", "") != null) {
        return true;
      }
      Mailboxes.Box box = accounts.mailboxes.box(domain, local);
      return box != null && box.enabled();
    } catch (java.sql.SQLException ex) {
      // a database that will not answer is our problem, so accept and let the message be judged
      // properly a moment later, where a failure becomes a 451 rather than a permanent refusal
      verbose.detail(() -> "mail: could not check " + recipient + " -- " + ex.getMessage());
      return true;
    }
  }

  @Override
  public Outcome receive(Envelope envelope) {
    Accounts accounts = accountsFor(envelope.domain());
    if (accounts == null || accounts.mailboxes == null) {
      return fallback.receive(envelope);
    }
    if (hops(envelope.data()) > MAX_HOPS) {
      // permanent on purpose: a loop retried for four days is a loop that runs for four days
      return Outcome.refused("this message has been forwarded too many times; there is a loop");
    }

    List<String> handled = new ArrayList<>();
    Outcome worst = null;
    for (String recipient : envelope.recipients()) {
      Outcome one = deliverOne(accounts, envelope, recipient);
      if (one == null) {
        handled.add(recipient);
        continue;
      }
      // A refusal is the whole transaction's answer.
      //
      // SMTP has one reply for the message however many recipients it carried, so a message for two
      // addresses where one fails cannot be half accepted. Refusing the lot is the only answer that
      // does not lose mail: the sending server retries or bounces for everybody, and nobody is
      // quietly dropped. This is also why one message per recipient is the shape worth having, and
      // why the log has a row per recipient rather than per message.
      worst = one;
    }
    if (worst != null) {
      return worst;
    }
    if (handled.isEmpty()) {
      return fallback.receive(envelope);
    }
    return Outcome.accepted(handled.size() == 1 ? "forwarded" : "forwarded to " + handled.size());
  }

  /**
   * One recipient.
   *
   * Returns null when the message was dealt with, and an {@link Outcome} when the transaction as a
   * whole has to be refused -- which reads backwards until you notice that the common case is
   * "nothing to say" and the exceptional one is the answer to the sending server.
   */
  private Outcome deliverOne(Accounts accounts, Envelope envelope, String recipient) {
    String domain = envelope.domain();
    String local = localPartOf(recipient);
    MailLog.Draft draft = new MailLog.Draft()
        .domain(domain)
        .envelope(envelope.from(), recipient)
        .message(envelope.header("from"), envelope.subject(), envelope.header("message-id"),
            envelope.size())
        .from(envelope.remoteAddress())
        .checks(envelope.checks())
        .preview(envelope.bodyPreview(MailLog.PREVIEW_CHARS));

    try {
      // A bounce coming back to an address this server wrote is unwrapped and sent on to whoever
      // the message was originally from. Without this the whole scheme is one-way: the far end's
      // failure report arrives here, at an address nothing owns, and the person who sent the
      // message never learns it did not arrive.
      String bounceFor = Srs.reverse(recipient, domain, config.srsSecret());
      if (bounceFor != null) {
        return relayBounce(accounts, envelope, draft, bounceFor);
      }
      if (Srs.looksLikeOurs(recipient, domain)) {
        record(accounts, draft.outcome(MailLog.Outcome.refused,
            "an SRS address this server did not write, or one that has expired"));
        return Outcome.refused("that return path is not one this server issued");
      }

      Mailboxes.Rule rule = accounts.mailboxes.decide(domain, local, envelope.from(),
          envelope.subject());
      draft.rule(rule);
      if (rule == null) {
        record(accounts, draft.outcome(MailLog.Outcome.unrouted,
            "no rule matched and no mailbox claimed it"));
        return null;
      }
      if (rule.action() == Mailboxes.Action.drop) {
        // Accepted and let go, and the sender is told it arrived.
        //
        // That is the honest thing for a rule the *recipient* wrote: a 550 would tell a sender
        // their address is wrong when it is right and somebody simply does not want their mail.
        record(accounts, draft.outcome(MailLog.Outcome.dropped, "a rule said to drop it"));
        verbose.detail(() -> "mail: dropped " + recipient + " by rule " + rule.id());
        return null;
      }

      byte[] outgoing = prepare(envelope, domain, recipient, rule.forwardTo(), draft);
      String returnPath = Srs.forward(envelope.from(), domain, config.srsSecret());
      Relay.Sent sent = relay.send(returnPath, rule.forwardTo(), outgoing);
      draft.delivery(sent).attempts(1);

      if (sent.ok()) {
        record(accounts, draft.outcome(MailLog.Outcome.forwarded, null));
        verbose.detail(() -> "mail: " + recipient + " -> " + rule.forwardTo() + " ("
            + sent.tls() + ")");
        return null;
      }
      // The far end's verdict is this server's verdict. See the class note: passing it straight
      // back is what removes the queue and the bounce generator in one stroke.
      if (sent.worthRetrying()) {
        record(accounts, draft.outcome(MailLog.Outcome.deferred, null));
        return Outcome.tryLater("the destination for " + recipient + " said: " + sent.detail());
      }
      record(accounts, draft.outcome(MailLog.Outcome.failed, null));
      return Outcome.refused("the destination for " + recipient + " said: " + sent.detail());
    } catch (java.sql.SQLException ex) {
      verbose.detail(() -> "mail: " + recipient + " could not be routed -- " + ex.getMessage());
      return Outcome.tryLater("this server could not read its own routing rules");
    }
  }

  /**
   * A failure report coming home, unwrapped and sent on.
   *
   * The envelope sender of a bounce is empty and stays empty: rewriting it would produce a bounce
   * that can itself bounce, which is the loop the null sender exists to prevent.
   */
  private Outcome relayBounce(Accounts accounts, Envelope envelope, MailLog.Draft draft,
                              String originalSender) throws java.sql.SQLException {
    draft.outcome(MailLog.Outcome.forwarded, "a bounce, returned to " + originalSender);
    byte[] outgoing = withHeaders(envelope, List.of(received(envelope, originalSender)));
    Relay.Sent sent = relay.send("", originalSender, outgoing);
    draft.delivery(sent).attempts(1);
    if (sent.ok()) {
      record(accounts, draft);
      return null;
    }
    if (sent.worthRetrying()) {
      record(accounts, draft.outcome(MailLog.Outcome.deferred, null));
      return Outcome.tryLater("the bounce could not be returned: " + sent.detail());
    }
    // A bounce that cannot be delivered is where mail stops, and it stops here rather than
    // generating a second failure report about the first one.
    record(accounts, draft.outcome(MailLog.Outcome.failed, null));
    return null;
  }

  /**
   * The message with our headers on the front, and nothing else touched.
   *
   * The order is the one a reader expects to find: the most recent hop's Received at the top, then
   * what this server is asserting about the message underneath it.
   */
  private byte[] prepare(Envelope envelope, String domain, String recipient, String destination,
                         MailLog.Draft draft) {
    ArrayList<String> headers = new ArrayList<>();
    headers.add(received(envelope, recipient));

    if (keys == null) {
      draft.arc("unsigned: no key");
      verbose.detail("mail: forwarding unsigned because no signing key is available");
      return withHeaders(envelope, headers);
    }
    Arc.Sealed sealed = Arc.seal(envelope.data(), envelope.checks(), config.authserv(), domain,
        keys);
    headers.addAll(sealed.headers());
    draft.arc(sealed.any() ? "i=1" : Arc.describeChain(envelope.data()).isEmpty() ? "none"
        : "chain left alone");
    if (!sealed.any()) {
      verbose.detail(() -> "mail: " + destination + " -- " + sealed.state());
    }
    String signature = DkimSigner.sign(envelope.data(), domain, keys);
    if (signature != null) {
      headers.add(DkimSigner.fold(signature));
    }
    return withHeaders(envelope, headers);
  }

  private static byte[] withHeaders(Envelope envelope, List<String> headers) {
    StringBuilder front = new StringBuilder();
    for (String header : headers) {
      front.append(header).append("\r\n");
    }
    byte[] prefix = front.toString().getBytes(StandardCharsets.UTF_8);
    byte[] body = envelope.data();
    byte[] out = new byte[prefix.length + body.length];
    System.arraycopy(prefix, 0, out, 0, prefix.length);
    System.arraycopy(body, 0, out, prefix.length, body.length);
    return out;
  }

  /**
   * The Received header, in the shape RFC 5321 section 4.4 defines.
   *
   * Every hop adds one and a message without them looks manufactured. The `for` clause names one
   * recipient, which is why a message for two addresses is two deliveries with two different
   * headers rather than one with both names on it -- the second recipient has no business appearing
   * in the first one's copy.
   */
  private String received(Envelope envelope, String recipient) {
    String peer = envelope.remoteAddress() == null ? "unknown" : envelope.remoteAddress();
    return DkimSigner.fold("Received: from " + safe(envelope.helo()) + " (" + safe(peer) + ")"
        + " by " + config.authserv() + " with ESMTP"
        + " for <" + safe(recipient) + ">; "
        + ZonedDateTime.now().format(RFC5322));
  }

  /** nothing from the wire goes into a header without the characters that would break one removed */
  private static String safe(String value) {
    if (value == null) {
      return "unknown";
    }
    String clean = value.replaceAll("[\\r\\n\\x00]", " ").trim();
    return clean.isEmpty() ? "unknown" : clean.length() > 200 ? clean.substring(0, 200) : clean;
  }

  /** how many mail systems have already handled this */
  static int hops(byte[] message) {
    int seen = 0;
    for (String[] header : Dkim.parseHeaders(DkimSigner.split(message).headers())) {
      if (header[0].trim().equalsIgnoreCase("received")) {
        seen++;
      }
    }
    return seen;
  }

  static String localPartOf(String address) {
    if (address == null) {
      return "";
    }
    int at = address.lastIndexOf('@');
    return (at <= 0 ? address : address.substring(0, at)).trim().toLowerCase(Locale.ROOT);
  }

  private void record(Accounts accounts, MailLog.Draft draft) {
    try {
      accounts.mailLog.record(draft);
    } catch (java.sql.SQLException ex) {
      // A log that will not write must never fail a delivery. Losing a row is a bad day; refusing
      // somebody's mail because the audit table had a problem is a worse one.
      verbose.detail(() -> "mail: could not record what happened -- " + ex.getMessage());
    }
  }

  private Accounts accountsFor(String domain) {
    return auth == null ? null : auth.forDomain(domain);
  }
}
