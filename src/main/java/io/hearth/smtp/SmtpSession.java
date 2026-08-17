package io.hearth.smtp;

import io.hearth.common.Verbose;
import io.hearth.vhost.DomainConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One SMTP conversation: the state machine from RFC 5321, minus everything nothing needs yet.
 *
 * Adapted from adama's `SmtpHandler`, with the same overall shape -- a line-delimited command loop
 * that flips into a data-collecting mode after DATA -- and a good deal less in it. Gone are STARTTLS
 * (nothing here has a certificate to present on port 25 yet), AUTH (this server never relays, so
 * there is nothing to authenticate *for*), SIZE negotiation and pipelining. What is kept is the
 * protocol, the limits, and the refusals.
 *
 * <pre>
 *   greeting -> EHLO -> MAIL FROM -> RCPT TO (one or more) -> DATA -> body -> "." -> accepted
 * </pre>
 *
 * The rules that matter:
 *
 * <ul>
 *   <li><b>Never relay.</b> RCPT is refused unless the domain is one this server has a config for.
 *       Refused at RCPT rather than after DATA, so a relay attempt costs one line instead of a
 *       megabyte.</li>
 *   <li><b>One message, one community.</b> Recipients on two domains are two deliveries, not one
 *       message that belongs to both.</li>
 *   <li><b>Bounded by arithmetic.</b> The message buffer stops at a byte ceiling, recipients at a
 *       count, the connection at an idle timeout. A hostile peer cannot make this hold more than
 *       those numbers multiply out to.</li>
 *   <li><b>Refusals are permanent unless they are ours.</b> A relay attempt gets 550 and should
 *       never come back; a receiver having a bad day gets 451 and should.</li>
 * </ul>
 */
public class SmtpSession extends SimpleChannelInboundHandler<String> {
  private static final Logger LOG = LoggerFactory.getLogger(SmtpSession.class);
  /** how many junk commands before we stop being polite */
  private static final int MAX_ERRORS = 12;
  private static final String CRLF = "\r\n";

  private final SmtpConfig config;
  private final SmtpRouting routing;
  private final MailReceiver receiver;
  private final Verbose verbose;
  private final AtomicInteger connections;
  private final SmtpServer.Counters counters;
  private final SenderCheck checks;
  private final java.util.concurrent.ExecutorService validators;
  private final java.util.function.Supplier<SmtpDns> dnsFactory;
  private final String banner;

  private boolean greeted;
  private String helo;
  private String from;
  private final List<String> recipients = new ArrayList<>();
  private DomainConfig domain;
  private boolean collecting;
  /**
   * A message has been handed to the checkers and its reply has not been written yet.
   *
   * Set on the event loop and cleared on the event loop -- the pool thread hands the reply back
   * through {@link #answer} rather than writing it itself -- so this and the queue below are only
   * ever touched by one thread, which is the whole reason the ordering can be relied on.
   */
  private boolean delivering;
  /** commands that arrived while a message was being decided about, in the order they arrived */
  private final java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
  /** a bound on the queue: a client that keeps talking into silence is closed rather than buffered */
  private static final int MAX_PENDING = 32;
  private ByteArrayOutputStream body;
  private boolean tooBig;
  private int errors;
  private boolean counted;

  public SmtpSession(SmtpConfig config, SmtpRouting routing, MailReceiver receiver, String banner,
                     AtomicInteger connections, SmtpServer.Counters counters,
                     java.util.concurrent.ExecutorService validators,
                     java.util.function.Supplier<SmtpDns> dnsFactory, Verbose verbose) {
    this.checks = new SenderCheck(config, verbose);
    this.validators = validators;
    this.dnsFactory = dnsFactory;
    this.config = config;
    this.routing = routing;
    this.receiver = receiver;
    this.banner = banner;
    this.connections = connections;
    this.counters = counters;
    this.verbose = verbose;
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    // The connection cap is checked here rather than in the initializer, because refusing with a
    // 421 and closing is a conversation a sending server understands; dropping the socket is not.
    if (connections.incrementAndGet() > config.maxConnections) {
      counters.refused.incrementAndGet();
      say(ctx, "421 " + banner + " too many connections, try later");
      ctx.close();
      return;
    }
    counted = true;
    counters.connections.incrementAndGet();
    verbose.detail(() -> "smtp: connection from " + remote(ctx));
    say(ctx, "220 " + banner + " Hearth ESMTP ready");
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    if (counted) {
      connections.decrementAndGet();
      counted = false;
    }
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, String line) {
    // A message is with the checkers and its reply has not been written yet.
    //
    // SMTP is strictly ordered: a sending server matches replies to commands by position, so
    // answering the next command before the previous message's 250 would have it record message A
    // as accepted when the acceptance was for B. Refusing the command instead does not help --
    // the refusal would still arrive first.
    //
    // So whatever arrives waits here and is handled, in order, the moment that reply goes out. A
    // client that pipelines gets correct answers in the right sequence; one that floods hits the
    // cap and is closed, because a queue with no bound is somewhere to put a megabyte.
    if (delivering) {
      if (pending.size() >= MAX_PENDING) {
        say(ctx, "421 " + banner + " too much at once");
        ctx.close();
        return;
      }
      pending.add(line == null ? "" : line);
      return;
    }
    handle(ctx, line);
  }

  private void handle(ChannelHandlerContext ctx, String line) {
    if (collecting) {
      collect(ctx, line);
      return;
    }
    command(ctx, line == null ? "" : line.trim());
  }

  /**
   * The reply to a delivery, and then whatever arrived while it was being decided.
   *
   * Always on the event loop, because everything else that touches the session state is, and
   * because that is what makes "in order" mean anything.
   */
  private void answer(ChannelHandlerContext ctx, String reply) {
    // The server can be stopping while a message is still with the checkers, in which case there is
    // nobody left to write to and no event loop to write on. Saying nothing is the whole of the
    // right behaviour: the sending server sees a dropped connection, does not record an acceptance,
    // and tries again -- which is exactly what should happen to a message this server never
    // finished deciding about.
    if (ctx.executor().isShuttingDown() || !ctx.channel().isActive()) {
      return;
    }
    try {
      ctx.executor().execute(() -> {
        say(ctx, reply);
        delivering = false;
        while (!pending.isEmpty() && !delivering) {
          handle(ctx, pending.poll());
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException ex) {
      // it went down between the check above and here; same answer
      verbose.detail("smtp: the connection went away before its reply could be written");
    }
  }

  // ---- commands ---------------------------------------------------------------------------------

  private void command(ChannelHandlerContext ctx, String line) {
    String upper = line.toUpperCase();
    if (upper.equals("QUIT")) {
      say(ctx, "221 " + banner + " closing");
      ctx.close();
      return;
    }
    if (upper.equals("NOOP")) {
      say(ctx, "250 OK");
      return;
    }
    if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
      greet(ctx, line, upper.startsWith("EHLO"));
      return;
    }
    if (!greeted) {
      say(ctx, "503 Send EHLO first");
      strike(ctx);
      return;
    }
    if (upper.equals("RSET")) {
      reset();
      say(ctx, "250 OK");
      return;
    }
    if (upper.startsWith("VRFY") || upper.startsWith("EXPN")) {
      // Answering these truthfully hands over a list of who is a member here, one guess at a time.
      // 252 is the RFC's own "I will not tell you, try sending it and find out".
      say(ctx, "252 Cannot verify");
      return;
    }
    if (upper.startsWith("AUTH")) {
      // there is nothing to authenticate for: this server never relays, so a credential would not
      // buy the caller anything it does not already have
      say(ctx, "502 Authentication is not offered here");
      return;
    }
    if (upper.startsWith("STARTTLS")) {
      say(ctx, "454 TLS is not available on this port");
      return;
    }
    if (upper.startsWith("MAIL FROM:")) {
      mailFrom(ctx, line.substring("MAIL FROM:".length()));
      return;
    }
    if (upper.startsWith("RCPT TO:")) {
      rcptTo(ctx, line.substring("RCPT TO:".length()));
      return;
    }
    if (upper.equals("DATA")) {
      data(ctx);
      return;
    }
    say(ctx, "500 Unrecognized command");
    strike(ctx);
  }

  private void greet(ChannelHandlerContext ctx, String line, boolean extended) {
    int space = line.indexOf(' ');
    String who = space < 0 ? "" : line.substring(space + 1).trim();
    if (who.isEmpty()) {
      say(ctx, "501 Syntax: EHLO hostname");
      strike(ctx);
      return;
    }
    helo = who;
    greeted = true;
    reset();
    if (!extended) {
      say(ctx, "250 " + banner);
      return;
    }
    // Only what is true. Advertising PIPELINING or STARTTLS and then not honouring it is worse than
    // not advertising, because a sending server will believe it.
    say(ctx, "250-" + banner + CRLF
        + "250-SIZE " + config.maxMessageBytes + CRLF
        + "250 8BITMIME");
  }

  private void mailFrom(ChannelHandlerContext ctx, String argument) {
    String address = SmtpRouting.extractAddress(argument);
    // <> is legal and meaningful: it is the sender a bounce uses, and refusing it would mean never
    // learning that one of our own messages failed
    if (address == null || (!address.isEmpty() && !SmtpRouting.looksLikeAddress(address))) {
      say(ctx, "501 Bad sender address");
      strike(ctx);
      return;
    }
    reset();
    from = address;
    say(ctx, "250 OK");
  }

  private void rcptTo(ChannelHandlerContext ctx, String argument) {
    if (from == null) {
      say(ctx, "503 Need MAIL FROM first");
      strike(ctx);
      return;
    }
    if (recipients.size() >= config.maxRecipients) {
      say(ctx, "452 Too many recipients");
      return;
    }
    String address = SmtpRouting.extractAddress(argument);
    if (address == null || !SmtpRouting.looksLikeAddress(address)) {
      say(ctx, "501 Bad recipient address");
      strike(ctx);
      return;
    }
    DomainConfig route = routing.routeFor(address);
    if (route == null) {
      // The one refusal this server must never get wrong. Permanent, and before any data arrives:
      // an open relay is found within days and ends with this machine's address on every blocklist
      // there is, with the community's own mail undeliverable behind it.
      counters.relaysRefused.incrementAndGet();
      verbose.detail(() -> "smtp: refused relay to " + address + " from " + remote(ctx));
      say(ctx, "550 We do not relay; no such domain here");
      strike(ctx);
      return;
    }
    if (domain != null && !domain.domain.equals(route.domain)) {
      // one message, one community. Two domains in one transaction is two deliveries, and pretending
      // otherwise means a handler cannot say which community it is acting for.
      say(ctx, "451 One community per message; send this one separately");
      return;
    }
    domain = route;
    recipients.add(address);
    say(ctx, "250 OK");
  }

  private void data(ChannelHandlerContext ctx) {
    if (from == null || recipients.isEmpty()) {
      say(ctx, "503 Need MAIL FROM and RCPT TO first");
      strike(ctx);
      return;
    }
    collecting = true;
    tooBig = false;
    body = new ByteArrayOutputStream(8192);
    say(ctx, "354 Go ahead; end with <CRLF>.<CRLF>");
  }

  // ---- the body ---------------------------------------------------------------------------------

  private void collect(ChannelHandlerContext ctx, String line) {
    String text = line == null ? "" : line;
    if (text.equals(".")) {
      finish(ctx);
      return;
    }
    // dot-stuffing: a body line that begins with a dot arrives with an extra one, and leaving it
    // there corrupts every message that happens to start a line with a full stop
    if (text.startsWith(".")) {
      text = text.substring(1);
    }
    byte[] bytes = (text + CRLF).getBytes(StandardCharsets.UTF_8);
    if (body.size() + bytes.length > config.maxMessageBytes) {
      // Keep reading to the terminating dot rather than closing: a sending server that never sees
      // the end of its own transaction will retry the whole thing, forever. Read it, drop it, and
      // say so once.
      tooBig = true;
      return;
    }
    body.write(bytes, 0, bytes.length);
  }

  private void finish(ChannelHandlerContext ctx) {
    collecting = false;
    if (tooBig) {
      counters.refused.incrementAndGet();
      say(ctx, "552 Message too large");
      reset();
      return;
    }
    byte[] message = body.toByteArray();
    String envelopeFrom = from;
    String sayHelo = helo;
    String forDomain = domain.domain;
    java.util.List<String> to = List.copyOf(recipients);
    java.net.InetAddress client = clientAddress(ctx);
    String peer = remote(ctx);
    reset();
    delivering = true;

    // SPF, DKIM and DMARC all need DNS, and DNS blocks. Doing that on the event loop would let one
    // slow nameserver stall every other conversation this server is having, so the checks and the
    // handler run on a small pool and the reply is written when they finish. The sending server is
    // simply waiting for its 250, which is exactly what it expects to be doing.
    validators.execute(() -> {
      MailReceiver.Outcome outcome;
      AuthResult result = AuthResult.nothingChecked();
      try {
        SenderCheck.Judgement judgement =
            checks.judge(client, envelopeFrom, sayHelo, message, dnsFactory.get());
        result = judgement.result();
        if (!judgement.deliver()) {
          counters.refused.incrementAndGet();
          counters.failedAuth.incrementAndGet();
          answer(ctx, (judgement.temporary() ? "451 " : "550 ") + judgement.reason());
          return;
        }
        // the findings ride on the front of the message, whatever they were: a handler should be
        // able to see what was known when it arrived rather than re-deriving it later
        Envelope envelope = new Envelope(envelopeFrom, to,
            SenderCheck.stamp(message, result, banner), peer, sayHelo, forDomain,
            System.currentTimeMillis());
        outcome = receiver.receive(envelope);
      } catch (Exception ex) {
        // A handler that throws is our problem, not the sender's, so it gets a 4xx and the message
        // comes back rather than bouncing to somebody who did nothing wrong.
        LOG.error("smtp-receiver-failed", ex);
        outcome = MailReceiver.Outcome.tryLater("the receiver failed");
      }
      if (outcome.accepted()) {
        counters.accepted.incrementAndGet();
        if (result.dmarc() == AuthResult.Status.pass) {
          counters.authenticated.incrementAndGet();
        }
        answer(ctx, "250 OK: " + outcome.detail());
        return;
      }
      counters.refused.incrementAndGet();
      answer(ctx, (outcome.temporary() ? "451 " : "550 ") + outcome.detail());
    });
  }

  private static java.net.InetAddress clientAddress(ChannelHandlerContext ctx) {
    return ctx.channel().remoteAddress() instanceof java.net.InetSocketAddress address
        ? address.getAddress() : null;
  }

  // ---- plumbing ---------------------------------------------------------------------------------

  /** clear the transaction, not the connection: EHLO and the greeting survive a RSET */
  private void reset() {
    from = null;
    recipients.clear();
    domain = null;
    collecting = false;
    tooBig = false;
    body = null;
  }

  private void strike(ChannelHandlerContext ctx) {
    if (++errors >= MAX_ERRORS) {
      verbose.detail(() -> "smtp: dropping " + remote(ctx) + " after " + errors + " bad commands");
      say(ctx, "421 Too many errors");
      ctx.close();
    }
  }

  private void say(ChannelHandlerContext ctx, String line) {
    ctx.writeAndFlush(line + CRLF);
  }

  private static String remote(ChannelHandlerContext ctx) {
    java.net.SocketAddress address = ctx.channel().remoteAddress();
    return address == null ? "unknown" : address.toString();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    LOG.warn("smtp-session-failed", cause);
    ctx.close();
  }
}
