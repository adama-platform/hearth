package io.hearth.web;

import io.hearth.analytics.AccessLog;
import io.hearth.auth.AuthSystem;
import io.hearth.common.Boot;
import io.hearth.common.Verbose;
import io.hearth.vhost.DomainTree;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Netty bootstrap and lifecycle, following Adama's ServiceRunnable but without the cluster parts.
 *
 * The domain tree handed in here is already fully loaded and immutable. Nothing in this class or
 * below it can add a domain at runtime.
 */
public class WebServer implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(WebServer.class);
  private final WebConfig webConfig;
  private final DomainTree domains;
  private final AuthSystem auth;
  private final Pages pages;
  private final AccountRoutes accounts;
  private final AdminRoutes admin;
  private final SelfRoutes self;
  private final io.hearth.mcp.McpRoutes mcp;
  private final io.hearth.attach.AttachmentRoutes attachments;
  private final io.hearth.certs.Challenges challenges;
  private final AccessLog accessLog;
  private final Verbose verbose;
  private final PwaRoutes pwa;
  private final io.hearth.legal.LegalRoutes legal;
  private final io.hearth.certs.TlsContexts tls;
  private final AtomicBoolean started;
  private final CountDownLatch ready;
  private volatile Channel httpsChannel;
  private volatile Channel bounceChannel;
  private Channel channel;
  private boolean stopped;

  public WebServer(WebConfig webConfig, DomainTree domains, AuthSystem auth, Pages pages,
                   AccountRoutes accounts, AdminRoutes admin, SelfRoutes self,
                   io.hearth.mcp.McpRoutes mcp,
                   io.hearth.attach.AttachmentRoutes attachments,
                   PwaRoutes pwa,
                   io.hearth.legal.LegalRoutes legal,
                   io.hearth.certs.Challenges challenges,
                   io.hearth.certs.TlsContexts tls,
                   AccessLog accessLog, Verbose verbose) {
    this.webConfig = webConfig;
    this.domains = domains;
    this.auth = auth;
    this.pages = pages;
    this.accounts = accounts;
    this.admin = admin;
    this.self = self;
    this.mcp = mcp;
    this.attachments = attachments;
    this.pwa = pwa;
    this.legal = legal;
    this.challenges = challenges;
    this.tls = tls;
    this.accessLog = accessLog;
    this.verbose = verbose;
    this.started = new AtomicBoolean(false);
    this.ready = new CountDownLatch(1);
    this.channel = null;
    this.stopped = false;
  }

  /** blocks until the listening channel closes */
  @Override
  public void run() {
    if (started.getAndSet(true)) {
      return;
    }
    EventLoopGroup bossGroup = new NioEventLoopGroup(webConfig.bossThreads);
    EventLoopGroup workerGroup = new NioEventLoopGroup(webConfig.workerThreads);
    try {
      ServerBootstrap bootstrap = new ServerBootstrap();
      bootstrap.group(bossGroup, workerGroup)
          .channel(NioServerSocketChannel.class)
          .option(ChannelOption.SO_REUSEADDR, true)
          .option(ChannelOption.SO_BACKLOG, 128)
          .childOption(ChannelOption.TCP_NODELAY, true)
          .childOption(ChannelOption.SO_KEEPALIVE, true)
          .childHandler(new Initializer(webConfig, domains, auth, pages, accounts, admin, self, mcp, attachments,
              pwa, legal, challenges, accessLog, verbose, null));
      verbose.say("binding " + webConfig.bind + ":" + webConfig.port + " with " + webConfig.workerThreads + " worker thread(s)");
      Channel bound = bootstrap.bind(new InetSocketAddress(webConfig.bind, webConfig.port)).sync().channel();

      // The TLS listener runs the same pipeline behind an SNI handler. Same routes, same handlers,
      // same everything -- a request that arrived encrypted is not a different kind of request, and
      // giving it a second path would be two places to fix every bug.
      if (webConfig.httpsEnabled() && tls != null) {
        ServerBootstrap secure = new ServerBootstrap();
        secure.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_REUSEADDR, true)
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childHandler(new Initializer(webConfig, domains, auth, pages, accounts, admin, self,
                mcp, attachments, pwa, legal, challenges, accessLog, verbose, tls));
        verbose.say("binding " + webConfig.bind + ":" + webConfig.httpsPort + " for https");
        httpsChannel = secure.bind(new InetSocketAddress(webConfig.bind, webConfig.httpsPort)).sync().channel();
      }

      if (webConfig.bounceEnabled()) {
        ServerBootstrap bounce = new ServerBootstrap();
        bounce.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_REUSEADDR, true)
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(new BounceInitializer(
                webConfig.httpsEnabled() ? webConfig.httpsPort : WebConfig.DEFAULT_HTTPS_PORT, verbose));
        verbose.say("binding " + webConfig.bind + ":" + webConfig.bouncePort + " to redirect to https");
        bounceChannel = bounce.bind(new InetSocketAddress(webConfig.bind, webConfig.bouncePort)).sync().channel();
      }

      // Only once every listener is bound. "Ready" that means "one of three ports is up" is a race
      // anything checking readiness will eventually lose -- including the certificate manager,
      // whose whole correctness rests on the socket being open before it orders anything.
      registered(bound);

      bound.closeFuture().sync();
      LOG.info("listener-closed");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      LOG.error("server-interrupted", ex);
    } catch (Exception ex) {
      LOG.error("server-failed", ex);
      Boot.fail("server failed: " + ex.getMessage());
    } finally {
      ready.countDown();
      bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
      workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }

  private synchronized void registered(Channel bound) {
    this.channel = bound;
    if (stopped) {
      bound.close();
    }
    ready.countDown();
  }

  /** waits for the listener to be bound (or for the run to have given up) */
  public boolean waitForReady(int ms) throws InterruptedException {
    return ready.await(ms, TimeUnit.MILLISECONDS);
  }

  public synchronized boolean isAccepting() {
    return channel != null && channel.isActive();
  }

  /** the port actually bound, which matters when port 0 was requested (tests) */
  public synchronized int boundPort() {
    if (channel != null && channel.localAddress() instanceof InetSocketAddress address) {
      return address.getPort();
    }
    return -1;
  }

  /** the https port actually bound, or -1 when there is no TLS listener */
  public int boundHttpsPort() {
    Channel bound = httpsChannel;
    if (bound != null && bound.localAddress() instanceof InetSocketAddress address) {
      return address.getPort();
    }
    return -1;
  }

  /** the bounce port actually bound, or -1 when there is no bounce listener */
  public int boundBouncePort() {
    Channel bound = bounceChannel;
    if (bound != null && bound.localAddress() instanceof InetSocketAddress address) {
      return address.getPort();
    }
    return -1;
  }

  public synchronized void shutdown() {
    stopped = true;
    if (httpsChannel != null) {
      httpsChannel.close();
    }
    if (bounceChannel != null) {
      bounceChannel.close();
    }
    if (channel != null) {
      channel.close();
    }
  }
}
