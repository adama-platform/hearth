package io.hearth.smtp;

import io.hearth.common.Verbose;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One message, out to somebody else's mail exchanger.
 *
 * <b>This is the half that makes forwarding a delivery rather than a hope</b>, and every rule in it
 * exists because a large receiver -- Google above all -- will quietly reclassify or refuse mail from
 * a forwarder that gets one of them wrong.
 *
 * <ul>
 *   <li><b>The message body is never touched.</b> Not a footer, not a subject tag, not a re-encode.
 *       The original DKIM signature is the strongest thing a forwarded message carries, and it
 *       covers the body and most of the headers; changing one byte of any of them destroys it and
 *       leaves a message that fails both SPF and DKIM at the far end. Headers are *prepended* and
 *       nothing else happens.</li>
 *   <li><b>TLS, and honestly reported.</b> Opportunistic STARTTLS with the certificate verified
 *       against the exchanger's hostname. When verification fails the connection is still
 *       encrypted, and the log says which of the two it was rather than recording "TLS" for both.
 *       With `require-tls` on -- the default, because the destination here is a large provider that
 *       has supported it for a decade -- an unencrypted delivery is a temporary failure rather than
 *       a quiet downgrade.</li>
 *   <li><b>A 4xx is a later, a 5xx is a no.</b> Retrying a permanent refusal for days is how a
 *       forwarder gets its address treated as a spam source; giving up on a temporary one loses
 *       mail. The distinction is the sending server's whole job.</li>
 *   <li><b>The far end's own sentence is kept verbatim.</b> "550 5.7.26 Unauthenticated email from
 *       gmail.com is not accepted due to domain's DMARC policy" is the entire diagnosis; a
 *       paraphrase of it is an afternoon.</li>
 * </ul>
 *
 * <b>No queue lives in here.</b> This connects, talks and returns what happened. What to do about a
 * `later` is {@link Forwarding}'s decision, because that is where the record of the message is.
 */
public class Relay {
  /** what a receiver may take to answer one command */
  private static final int READ_TIMEOUT_MILLIS = 60_000;
  private static final int CONNECT_TIMEOUT_MILLIS = 20_000;
  /** how many exchangers to try before calling it a day */
  private static final int MAX_HOSTS = 3;
  /**
   * The longest reply line this will hold.
   *
   * A receiver is somebody else's machine and a hostile one can answer with a gigabyte and no
   * newline in it. `readLine` on that is an out-of-memory error on the delivery thread, which takes
   * the whole mail path down -- so the reader stops at a length no real SMTP reply approaches.
   */
  private static final int MAX_REPLY_LINE = 4096;
  private static final String CRLF = "\r\n";

  private final SmtpDns dns;
  private final Verbose verbose;
  private final String helo;
  private final boolean requireTls;
  private final int port;
  /**
   * May this deliver to an address inside the network?
   *
   * <b>False everywhere except a test, and that is a real defence rather than tidiness.</b> Who a
   * message is delivered to is not always chosen by an administrator: a reply-all is addressed from
   * the To and Cc headers of a message a stranger sent, so a stranger picks a domain, and that
   * domain's MX record is a name they also control. Pointing it at `127.0.0.1` or `10.0.0.5` turns
   * "reply to this" into a request to something behind the firewall -- which is invariant 150's
   * argument arriving by a different door.
   *
   * A test needs the loopback, because the stub exchanger it talks to is bound on it.
   */
  private final boolean allowInside;

  public Relay(SmtpDns dns, String helo, boolean requireTls, Verbose verbose) {
    this(dns, helo, requireTls, 25, false, verbose);
  }

  /**
   * The seam a test uses: its own port, and permission to reach the loopback it bound on.
   *
   * Both halves are the test's, together, on purpose -- a production caller reaching for a custom
   * port would otherwise silently acquire the right to deliver inside the network as well.
   */
  public Relay(SmtpDns dns, String helo, boolean requireTls, int port, Verbose verbose) {
    this(dns, helo, requireTls, port, true, verbose);
  }

  private Relay(SmtpDns dns, String helo, boolean requireTls, int port, boolean allowInside,
                Verbose verbose) {
    this.dns = dns;
    this.helo = helo;
    this.requireTls = requireTls;
    this.port = port;
    this.allowInside = allowInside;
    this.verbose = verbose;
  }

  /** how far a delivery got, and what the other end said about it */
  public record Sent(Status status, String host, String tls, String detail) {
    public enum Status {
      /** the receiver took responsibility for it */
      delivered,
      /** not now: a queue full, a greylist, a nameserver down. Worth coming back for */
      later,
      /** no, and coming back will not help */
      refused
    }

    public boolean ok() {
      return status == Status.delivered;
    }

    public boolean worthRetrying() {
      return status == Status.later;
    }
  }

  /**
   * The exchangers for a domain, best first, falling back to the domain itself.
   *
   * <b>A domain with no MX is its own exchanger</b>, which is RFC 5321's implicit-MX rule and not a
   * guess -- plenty of small domains never publish one. What this must not do is treat "DNS did not
   * answer" as "no MX", because those look identical from here and the first is worth retrying
   * while the second is not.
   */
  public List<String> exchangersFor(String domain) {
    ArrayList<String> hosts = new ArrayList<>();
    for (String host : dns.mx(domain)) {
      // "." as the exchanger is a domain declaring it accepts no mail at all (RFC 7505). Trying to
      // connect to it would be a long timeout ending in the wrong answer.
      if (!host.isBlank() && !host.equals(".") && !hosts.contains(host)) {
        hosts.add(host);
      }
      if (hosts.size() >= MAX_HOSTS) {
        break;
      }
    }
    if (hosts.isEmpty()) {
      hosts.add(domain);
    }
    return hosts;
  }

  /**
   * Deliver one message to one recipient.
   *
   * One recipient per delivery even when a rule sends to two places, because a receiver that
   * accepts for one and refuses the other answers once per RCPT and the outcomes have to be
   * recorded separately. Batching them would mean a single row in the log saying something that was
   * true of half the message.
   */
  public Sent send(String envelopeFrom, String recipient, byte[] message) {
    // Nothing reaches a command line without being an address first.
    //
    // `RCPT TO:<...>` is a line of a protocol, and a recipient that is not an address is at best a
    // confusing refusal and at worst a second command. Recipients are not always an
    // administrator's: a reply-all takes them from headers a stranger wrote. The same check the
    // inbound side uses at RCPT, applied on the way out.
    if (!SmtpRouting.looksLikeAddress(recipient)) {
      return new Sent(Sent.Status.refused, "", "", "'" + recipient + "' is not an address");
    }
    if (envelopeFrom != null && !envelopeFrom.isEmpty()
        && !SmtpRouting.looksLikeAddress(envelopeFrom)) {
      // the empty sender is legal and is what a bounce uses; anything else has to be real
      return new Sent(Sent.Status.refused, "", "",
          "'" + envelopeFrom + "' is not an address to send from");
    }
    String domain = SmtpRouting.domainOf(recipient);
    if (domain == null) {
      return new Sent(Sent.Status.refused, "", "", "'" + recipient + "' is not an address");
    }
    List<String> hosts = exchangersFor(domain);
    Sent last = new Sent(Sent.Status.later, "", "", "no exchanger for " + domain + " answered");
    for (String host : hosts) {
      Sent attempt = sendTo(host, envelopeFrom, recipient, message);
      if (attempt.status() != Sent.Status.later) {
        // a definite answer from any exchanger is the answer; the rest are the same mail system
        return attempt;
      }
      last = attempt;
    }
    return last;
  }

  private Sent sendTo(String host, String envelopeFrom, String recipient, byte[] message) {
    verbose.detail(() -> "relay: " + recipient + " via " + host);
    String inside = allowInside ? null : io.hearth.common.PublicAddress.refuse(host);
    if (inside != null) {
      // Resolved and refused, not read: a name under somebody else's control can point anywhere,
      // and a check on the text would never notice. Permanent, because an exchanger inside this
      // network is not going to move out of it while the message waits.
      verbose.detail(() -> "relay: refused " + host + " -- " + inside);
      return new Sent(Sent.Status.refused, host, "none",
          host + " is not somewhere this server will deliver: " + inside);
    }
    String tls = "none";
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
      socket.setSoTimeout(READ_TIMEOUT_MILLIS);
      Conversation talk = new Conversation(socket);

      Reply greeting = talk.read();
      if (!greeting.positive()) {
        return new Sent(Sent.Status.later, host, tls, "greeting: " + greeting.text());
      }
      Reply ehlo = talk.say("EHLO " + helo);
      if (!ehlo.positive()) {
        return new Sent(Sent.Status.later, host, tls, "EHLO: " + ehlo.text());
      }

      if (ehlo.offers("STARTTLS")) {
        Reply ready = talk.say("STARTTLS");
        if (ready.positive()) {
          Upgraded upgraded = talk.upgrade(host);
          tls = upgraded.how();
          if (upgraded.failed()) {
            return new Sent(Sent.Status.later, host, "failed", upgraded.how());
          }
          // The RFC requires a second EHLO after the handshake: the extension list before TLS and
          // the one after are allowed to differ, and a receiver may only advertise what it will
          // honour once the conversation is private.
          ehlo = talk.say("EHLO " + helo);
          if (!ehlo.positive()) {
            return new Sent(Sent.Status.later, host, tls, "EHLO after TLS: " + ehlo.text());
          }
        }
      }
      if (requireTls && tls.equals("none")) {
        // A downgrade is a decision somebody should make on purpose. Temporary, so the message
        // waits for the far end to fix its TLS rather than bouncing to a sender who cannot.
        return new Sent(Sent.Status.later, host, tls,
            host + " offered no STARTTLS and this server is configured to require it");
      }

      Reply from = talk.say("MAIL FROM:<" + envelopeFrom + ">");
      if (!from.positive()) {
        return new Sent(from.permanent() ? Sent.Status.refused : Sent.Status.later, host, tls,
            "MAIL FROM: " + from.text());
      }
      Reply to = talk.say("RCPT TO:<" + recipient + ">");
      if (!to.positive()) {
        return new Sent(to.permanent() ? Sent.Status.refused : Sent.Status.later, host, tls,
            "RCPT TO: " + to.text());
      }
      Reply data = talk.say("DATA");
      if (!data.intermediate()) {
        return new Sent(data.permanent() ? Sent.Status.refused : Sent.Status.later, host, tls,
            "DATA: " + data.text());
      }
      talk.body(message);
      Reply done = talk.read();
      talk.quiet("QUIT");
      if (done.positive()) {
        return new Sent(Sent.Status.delivered, host, tls, done.text());
      }
      return new Sent(done.permanent() ? Sent.Status.refused : Sent.Status.later, host, tls,
          done.text());
    } catch (java.io.IOException ex) {
      // A socket that broke is this hop's problem and is worth coming back for. Calling it
      // permanent would bounce mail because somebody's exchanger restarted.
      return new Sent(Sent.Status.later, host, tls,
          host + " could not be reached: " + ex.getMessage());
    }
  }

  // ---- the conversation ---------------------------------------------------------------------------

  /** one reply, which may be several lines and is judged by its first digit */
  record Reply(int code, String text, List<String> lines) {
    boolean positive() {
      return code >= 200 && code < 300;
    }

    boolean intermediate() {
      return code >= 300 && code < 400;
    }

    boolean permanent() {
      return code >= 500;
    }

    /** does the EHLO response advertise this? */
    boolean offers(String extension) {
      for (String line : lines) {
        String cleaned = line.length() > 4 ? line.substring(4).trim() : "";
        if (cleaned.toUpperCase(Locale.ROOT).startsWith(extension)) {
          return true;
        }
      }
      return false;
    }
  }

  private record Upgraded(String how, boolean failed) {
  }

  /**
   * The socket, and the reading and writing that goes with it.
   *
   * Not static and not shared: an instance lives for one delivery, which is what makes the
   * mid-conversation swap to a TLS socket straightforward -- there is exactly one reader and one
   * writer and they are both replaced at once.
   */
  private final class Conversation {
    private Socket socket;
    private BufferedReader in;
    private Writer out;

    Conversation(Socket socket) throws java.io.IOException {
      bind(socket);
    }

    private void bind(Socket bound) throws java.io.IOException {
      this.socket = bound;
      this.in = new BufferedReader(
          new InputStreamReader(bound.getInputStream(), StandardCharsets.UTF_8));
      this.out = new java.io.OutputStreamWriter(bound.getOutputStream(), StandardCharsets.UTF_8);
    }

    Reply read() throws java.io.IOException {
      ArrayList<String> lines = new ArrayList<>();
      String line;
      int code = 0;
      while ((line = readBounded()) != null) {
        lines.add(line);
        if (line.length() >= 3) {
          try {
            code = Integer.parseInt(line.substring(0, 3));
          } catch (NumberFormatException ex) {
            return new Reply(0, "unreadable reply: " + line, lines);
          }
        }
        // "250-EXTENSION" continues; "250 EXTENSION" is the last line
        if (line.length() < 4 || line.charAt(3) != '-') {
          break;
        }
        if (lines.size() > 64) {
          return new Reply(0, "the far end would not stop talking", lines);
        }
      }
      if (lines.isEmpty()) {
        throw new java.io.IOException("the connection closed without a reply");
      }
      return new Reply(code, String.join(" ", lines).trim(), lines);
    }

    /**
     * One line, or as much of one as this will hold.
     *
     * `readLine` on a hostile receiver that answers with a gigabyte and no newline is an
     * out-of-memory error on the delivery thread. Stopping at a length no real reply approaches
     * turns that into a refusal.
     */
    private String readBounded() throws java.io.IOException {
      StringBuilder out = new StringBuilder(128);
      int ch;
      while ((ch = in.read()) >= 0) {
        if (ch == '\n') {
          break;
        }
        // past the ceiling the characters are dropped and the loop keeps going to the newline,
        // so the conversation stays in step rather than the next reply being read as this one
        if (ch != '\r' && out.length() < MAX_REPLY_LINE) {
          out.append((char) ch);
        }
      }
      return ch < 0 && out.length() == 0 ? null : out.toString();
    }

    Reply say(String command) throws java.io.IOException {
      out.write(command + CRLF);
      out.flush();
      return read();
    }

    /** for QUIT, where the answer changes nothing and a receiver may simply hang up */
    void quiet(String command) {
      try {
        out.write(command + CRLF);
        out.flush();
      } catch (java.io.IOException ex) {
        // the delivery already succeeded or failed; how the goodbye went is not interesting
      }
    }

    /**
     * The message, dot-stuffed, followed by the terminating dot.
     *
     * <b>Dot-stuffing is the one transformation allowed</b>, and it is not a change to the message:
     * a line beginning with a period has to be sent with an extra one, and the receiver takes it
     * off again. Skipping it means any message with a line starting `.` ends early, which is data
     * loss that looks like a truncated email.
     */
    void body(byte[] message) throws java.io.IOException {
      OutputStream raw = socket.getOutputStream();
      String text = new String(message, StandardCharsets.UTF_8);
      StringBuilder wire = new StringBuilder(text.length() + 64);
      for (String line : text.split("\r\n|\n", -1)) {
        if (line.startsWith(".")) {
          wire.append('.');
        }
        wire.append(line).append(CRLF);
      }
      // exactly one CRLF before the dot, whatever the message ended with
      while (wire.length() >= 4 && wire.lastIndexOf(CRLF + CRLF) == wire.length() - 4) {
        wire.setLength(wire.length() - 2);
      }
      wire.append(".").append(CRLF);
      raw.write(wire.toString().getBytes(StandardCharsets.UTF_8));
      raw.flush();
    }

    /**
     * Swap in TLS, verifying the certificate against the exchanger's name.
     *
     * <b>Verification is attempted and its failure is not fatal</b>, which is the standard for
     * SMTP between servers and is worth saying out loud because it looks like a hole. There is no
     * way to know in advance whether an arbitrary exchanger has a certificate that chains -- plenty
     * do not -- and refusing those would mean refusing to forward to them at all. So: verified when
     * it can be, encrypted when it cannot, and the difference is recorded rather than smoothed
     * over. For the destination this feature exists for, a large provider, it is always verified.
     */
    Upgraded upgrade(String host) {
      try {
        SSLSocketFactory factory = SSLContext.getDefault().getSocketFactory();
        SSLSocket secure = (SSLSocket) factory.createSocket(socket, host, port, false);
        secure.setUseClientMode(true);
        SSLParameters parameters = secure.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        secure.setSSLParameters(parameters);
        try {
          secure.startHandshake();
          bind(secure);
          return new Upgraded("verified", false);
        } catch (java.io.IOException ex) {
          verbose.detail(() -> "relay: " + host + " did not verify -- " + ex.getMessage());
        }
      } catch (Exception ex) {
        return new Upgraded("could not start TLS: " + ex.getMessage(), true);
      }
      // The verifying handshake consumed the socket, so a second attempt needs a new connection.
      // Reconnecting rather than reusing is what makes the fallback honest: the alternative is
      // sending on a socket whose state after a failed handshake nobody can describe.
      try {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
        plain.setSoTimeout(READ_TIMEOUT_MILLIS);
        bind(plain);
        read();
        say("EHLO " + helo);
        Reply ready = say("STARTTLS");
        if (!ready.positive()) {
          return new Upgraded("STARTTLS refused on the second attempt", true);
        }
        SSLSocketFactory factory = SSLContext.getDefault().getSocketFactory();
        SSLSocket secure = (SSLSocket) factory.createSocket(plain, host, port, false);
        secure.setUseClientMode(true);
        secure.startHandshake();
        bind(secure);
        return new Upgraded("encrypted", false);
      } catch (Exception ex) {
        return new Upgraded("TLS failed: " + ex.getMessage(), true);
      }
    }
  }
}
