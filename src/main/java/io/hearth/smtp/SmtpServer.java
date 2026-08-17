package io.hearth.smtp;

import io.hearth.common.Verbose;
import io.hearth.vhost.DomainTree;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The inbound mail listener.
 *
 * Adapted from adama's `SmtpServer`: the same Netty bootstrap and the same pipeline shape --
 * CRLF framing, an idle timeout, a string codec, the session -- on its own event loop rather than
 * sharing the web server's, because a slow mail conversation should never be able to hold up a page
 * load.
 *
 * <b>Off unless an operator asks for it.</b> Port 25 needs root and an unconfigured listener on it
 * is found by scanners within the hour. It also binds *after* the readiness latch counts it, for
 * the same reason the certificate manager waits for the socket: a server that reports ready while
 * one of its listeners is still coming up is a race that something eventually loses.
 *
 * Deliberately absent, and worth naming so nobody assumes otherwise: no TLS on this port, no AUTH,
 * no SPF, no DKIM, no DMARC, and no MIME parsing. This receives mail and hands it to a
 * {@link MailReceiver}. Anything that decides whether a message is *genuine* is a separate piece of
 * work, and a half-implemented one would be worse than none, because it would look like a check.
 */
public class SmtpServer implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(SmtpServer.class);
  /** the longest line any command may be; the RFC says 512, and generosity here buys nothing */
  private static final int MAX_LINE = 4096;

  private final SmtpConfig config;
  private final SmtpRouting routing;
  private final MailReceiver receiver;
  private final Verbose verbose;
  private final String banner;
  private final AtomicInteger connections = new AtomicInteger();
  private final Counters counters = new Counters();
  private volatile Channel channel;
  private volatile EventLoopGroup boss;
  private volatile EventLoopGroup workers;
  private volatile Runnable onBound;
  /** the checks and the handler run here, because DNS blocks and the event loop must not */
  private volatile java.util.concurrent.ExecutorService validators;
  private java.util.function.Supplier<SmtpDns> dnsFactory;

  /** what has happened since boot, for the admin overview and the boot report */
  public static class Counters {
    public final AtomicLong connections = new AtomicLong();
    public final AtomicLong accepted = new AtomicLong();
    public final AtomicLong refused = new AtomicLong();
    public final AtomicLong relaysRefused = new AtomicLong();
    /** messages whose From domain vouched for them, which is the number worth watching */
    public final AtomicLong authenticated = new AtomicLong();
    /** refused because the domain owner said to */
    public final AtomicLong failedAuth = new AtomicLong();
  }

  public SmtpServer(SmtpConfig config, DomainTree tree, MailReceiver receiver, String banner,
                    Verbose verbose) {
    this.config = config;
    this.routing = new SmtpRouting(tree);
    this.receiver = receiver;
    this.banner = config.hostnameOr(banner);
    this.verbose = verbose;
    // a fresh resolver per message, so its cache lives exactly as long as the evaluation it serves
    this.dnsFactory = () -> new SmtpDns.Jdk(config.dnsTimeoutMillis);
  }

  /** swap the resolver, which is how every test of these checks is written */
  public void resolveWith(java.util.function.Supplier<SmtpDns> factory) {
    this.dnsFactory = factory;
  }

  /** called once the socket is accepting, so the boot report says what happened rather than what will */
  public void whenBound(Runnable callback) {
    this.onBound = callback;
  }

  public Counters counters() {
    return counters;
  }

  public boolean isAccepting() {
    Channel bound = channel;
    return bound != null && bound.isActive();
  }

  public int port() {
    Channel bound = channel;
    return bound != null && bound.localAddress() instanceof InetSocketAddress address
        ? address.getPort() : config.port;
  }

  @Override
  public void run() {
    if (!config.enabled) {
      return;
    }
    // one thread accepting and two working: this is mail for a few hundred people, and a pool
    // sized for a mail provider would be memory spent on nothing
    boss = new NioEventLoopGroup(1);
    workers = new NioEventLoopGroup(2);
    validators = java.util.concurrent.Executors.newFixedThreadPool(4, runnable -> {
      Thread thread = new Thread(runnable, "smtp-check");
      thread.setDaemon(true);
      return thread;
    });
    try {
      ServerBootstrap bootstrap = new ServerBootstrap();
      bootstrap.group(boss, workers)
          .channel(NioServerSocketChannel.class)
          .option(ChannelOption.SO_REUSEADDR, true)
          .option(ChannelOption.SO_BACKLOG, 32)
          .childOption(ChannelOption.TCP_NODELAY, true)
          .childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
              ch.pipeline()
                  // CRLF framing, with a bare LF accepted too. Rejecting a bare newline is more
                  // correct and less useful: plenty of real senders emit them, and a message
                  // refused for that arrives as a bounce nobody can act on.
                  // stripDelimiter is true: the session compares a body line against "." and
                  // writes its own CRLF back, so a line arriving with the delimiter still attached
                  // would never terminate a message -- the sender would wait forever and retry.
                  .addLast(new DelimiterBasedFrameDecoder(MAX_LINE, true,
                      Unpooled.copiedBuffer("\r\n", StandardCharsets.US_ASCII),
                      Unpooled.copiedBuffer("\n", StandardCharsets.US_ASCII)))
                  .addLast(new ReadTimeoutHandler(config.idleSeconds, TimeUnit.SECONDS))
                  .addLast(new StringDecoder(StandardCharsets.UTF_8))
                  .addLast(new StringEncoder(StandardCharsets.UTF_8))
                  .addLast(new SmtpSession(config, routing, receiver, banner, connections,
                      counters, validators, dnsFactory, verbose));
            }
          });
      verbose.say("smtp: binding " + config.port);
      channel = bootstrap.bind(new InetSocketAddress(config.port)).sync().channel();
      Runnable callback = onBound;
      if (callback != null) {
        callback.run();
      }
      channel.closeFuture().sync();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } catch (Exception ex) {
      // A mail listener that cannot bind must not stop the web server: port 25 needs root, and the
      // usual cause is somebody running this without it. Say so loudly and carry on serving pages.
      LOG.error("smtp-bind-failed port={}", config.port, ex);
      verbose.say("smtp: could not bind " + config.port + " -- " + ex.getMessage());
      Runnable callback = onBound;
      if (callback != null) {
        callback.run();
      }
    } finally {
      shutdownGroups();
    }
  }

  public void shutdown() {
    Channel bound = channel;
    if (bound != null) {
      bound.close();
    }
    shutdownGroups();
  }

  private void shutdownGroups() {
    EventLoopGroup b = boss;
    EventLoopGroup w = workers;
    boss = null;
    workers = null;
    if (b != null) {
      b.shutdownGracefully(0, 2, TimeUnit.SECONDS);
    }
    if (w != null) {
      w.shutdownGracefully(0, 2, TimeUnit.SECONDS);
    }
    java.util.concurrent.ExecutorService pool = validators;
    validators = null;
    if (pool != null) {
      pool.shutdownNow();
    }
  }

  public String describe() {
    if (!config.enabled) {
      return "off";
    }
    return counters.accepted.get() + " accepted (" + counters.authenticated.get()
        + " dmarc pass), " + counters.refused.get() + " refused ("
        + counters.relaysRefused.get() + " relay attempts, " + counters.failedAuth.get()
        + " failed authentication)";
  }
}
