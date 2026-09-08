package io.hearth.inbox;

import io.hearth.common.Verbose;
import io.hearth.smtp.DkimSigner;
import io.hearth.smtp.MailKeys;
import io.hearth.smtp.Relay;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sending a message this server wrote, on behalf of the person who wrote it.
 *
 * <b>This is the one path where mail leaves with our name on it rather than somebody else's.</b>
 * Forwarding passes a message through and signs the hop; this composes a message, signs it as the
 * domain in the From address, and sends it with an envelope sender at that same domain -- so SPF,
 * DKIM and DMARC all align on the address a person reads. A reply from `jeff@example.org` is, by
 * every check a receiver runs, from `jeff@example.org`.
 *
 * <b>No SRS here, and that is the difference worth naming.</b> A forwarded message keeps somebody
 * else's From header, so its return path has to be rewritten to a domain that lists this machine.
 * A reply's From header is already ours, so the envelope sender is simply the same address -- and a
 * bounce comes back to a real mailbox that a person reads, rather than to a rewritten address that
 * has to be decoded first.
 *
 * <b>One delivery per recipient.</b> A receiver that accepts for one address and refuses another
 * answers once per RCPT, and a single result for the whole send would be true of some of it. What
 * comes back is a line per recipient, which is what the screen shows.
 */
public class Postman {
  private final Relay relay;
  private final MailKeys keys;
  private final Verbose verbose;

  public Postman(Relay relay, MailKeys keys, Verbose verbose) {
    this.relay = relay;
    this.keys = keys;
    this.verbose = verbose;
  }

  /** what happened to one recipient */
  public record Attempt(String recipient, boolean delivered, boolean temporary, String detail) {
  }

  /** what happened to all of them */
  public record Sent(List<Attempt> attempts) {
    public boolean allDelivered() {
      if (attempts.isEmpty()) {
        return false;
      }
      for (Attempt attempt : attempts) {
        if (!attempt.delivered()) {
          return false;
        }
      }
      return true;
    }

    public boolean anyDelivered() {
      for (Attempt attempt : attempts) {
        if (attempt.delivered()) {
          return true;
        }
      }
      return false;
    }

    /** one sentence for a person, naming what failed rather than how many did */
    public String describe() {
      if (allDelivered()) {
        return attempts.size() == 1 ? "Sent."
            : "Sent to all " + attempts.size() + " of them.";
      }
      ArrayList<String> trouble = new ArrayList<>();
      for (Attempt attempt : attempts) {
        if (!attempt.delivered()) {
          trouble.add(attempt.recipient() + " — " + attempt.detail());
        }
      }
      String failures = String.join("; ", trouble);
      return anyDelivered()
          ? "Some of it went. " + failures
          : "It did not go. " + failures;
    }
  }

  /**
   * Sign it and send it.
   *
   * The signature is added first and covers the headers a receiver checks; the relay then prepends
   * nothing at all, because this message did not pass through -- it started here. Signing failing
   * is not a reason not to send: an unsigned reply is a worse reply and is still the reply somebody
   * asked for, and the boot report has already said the key is missing.
   */
  public Sent send(String from, Outgoing.Written written) {
    byte[] bytes = written.bytes();
    String domain = from.indexOf('@') > 0
        ? from.substring(from.indexOf('@') + 1).toLowerCase(Locale.ROOT) : "";
    if (keys != null && !domain.isEmpty()) {
      String signature = DkimSigner.sign(bytes, domain, keys);
      if (signature != null) {
        bytes = prepend(DkimSigner.fold(signature), bytes);
      } else {
        verbose.detail(() -> "outgoing: " + written.messageId() + " could not be signed");
      }
    }
    ArrayList<Attempt> attempts = new ArrayList<>();
    for (String recipient : written.recipients()) {
      Relay.Sent sent = relay.send(from, recipient, bytes);
      attempts.add(new Attempt(recipient, sent.ok(), sent.worthRetrying(), sent.detail()));
      verbose.detail(() -> "outgoing: " + recipient + " " + sent.status() + " " + sent.detail());
    }
    return new Sent(List.copyOf(attempts));
  }

  private static byte[] prepend(String header, byte[] message) {
    byte[] front = (header + "\r\n").getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[front.length + message.length];
    System.arraycopy(front, 0, out, 0, front.length);
    System.arraycopy(message, 0, out, front.length, message.length);
    return out;
  }

  /**
   * Is this address one this server can honestly send as?
   *
   * A From header naming a domain this machine has no key for and no SPF record on is a message
   * that will be refused or filed as spam wherever it lands, and sending it anyway wastes the
   * reputation of every domain that *is* set up here. The check is the same one the mail rules use:
   * the domain has to be one this server serves.
   */
  public static boolean canSendAs(String address, java.util.Set<String> ourDomains) {
    String domain = io.hearth.smtp.SmtpRouting.domainOf(address);
    return domain != null && ourDomains.contains(domain);
  }
}
