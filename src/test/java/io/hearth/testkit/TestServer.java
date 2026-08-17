package io.hearth.testkit;

import io.hearth.analytics.AccessLog;
import io.hearth.auth.AuthSystem;
import io.hearth.common.ConfigException;
import io.hearth.common.Verbose;
import io.hearth.events.EventBus;
import io.hearth.events.LocalEventBus;
import io.hearth.mail.Mailer;
import io.hearth.store.Stores;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainScanner;
import io.hearth.vhost.DomainTree;
import io.hearth.web.AccountRoutes;
import io.hearth.web.AdminRoutes;
import io.hearth.web.Pages;
import io.hearth.web.SelfRoutes;
import io.hearth.web.WebConfig;
import io.hearth.web.WebServer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * A real server, on a real socket, for the duration of a test.
 *
 * Binds 127.0.0.1 on port 0 so the OS picks a free port and tests can run in parallel with each
 * other and with a developer's own server. This is the whole boot path -- scan, compile templates,
 * open and upgrade databases, bind -- so a test that talks to it is testing what an operator would
 * run rather than a handler in isolation.
 *
 * When a test supplies a configs directory but no stores path, one is made in a temp directory, so
 * a test that only cares about virtual hosting still gets real databases behind it.
 */
public class TestServer implements AutoCloseable {
  public final int port;
  /** the TLS port, or -1 when this server has no TLS listener */
  private int httpsPort = -1;
  /** the redirect-only port, or -1 when there is none */
  private int bouncePort = -1;
  public final DomainTree tree;
  public final AuthSystem auth;
  public final Stores stores;
  /** the mailer the routes used; a CapturingMailer when the test wants to read codes back */
  public final Mailer mailer;
  public final EventBus events;
  public final AccessLog accessLog;
  /** what the agents did, for the tests that assert on the audit trail */
  public final io.hearth.mcp.AiLog aiLog;
  /** the ACME challenges this server would answer, for the certificate tests */
  public final io.hearth.certs.Challenges challenges;
  /** the live channel, so a test can watch what a browser would be told */
  public final io.hearth.live.Live live;
  /** where uploads land in a test: a directory thrown away with everything else */
  public final io.hearth.attach.AttachmentStore attachmentFiles;
  /** the serving route, so a test can look at the cache */
  public final io.hearth.attach.AttachmentRoutes attachments;
  /**
   * The availability grids, with a fetcher that answers nothing.
   *
   * No test may reach the network, so the seam is filled with a refusal by default; a test about
   * calendars replaces it with {@link #fetching}, which is exactly the point of the seam.
   */
  public final io.hearth.availability.Availabilities availabilities;
  /** what a fetch answers in this server; a test sets it before adding a link */
  public static final java.util.concurrent.atomic.AtomicReference<
      io.hearth.availability.CalendarFetch.Fetcher> fetching =
          new java.util.concurrent.atomic.AtomicReference<>(
              io.hearth.availability.CalendarFetch.NONE);
  /**
   * The geocoder, which answers nothing unless a test says otherwise.
   *
   * The same shape as {@link #fetching} and for the same reason: no test may reach the network, and
   * a seam filled with a refusal is what makes that true by construction rather than by everybody
   * remembering.
   */
  public static final java.util.concurrent.atomic.AtomicReference<io.hearth.places.Geocoder>
      geocoding = new java.util.concurrent.atomic.AtomicReference<>(
          io.hearth.places.Geocoder.NONE);
  /** the work queue, so a test can wait for it instead of sleeping and hoping */
  public final io.hearth.async.AsyncQueue async;
  private final WebServer server;
  private final Thread thread;
  private final File ownedStores;

  private TestServer(WebConfig config, DomainTree tree, Stores stores, AuthSystem auth, Templates templates,
                     Mailer mailer, EventBus events, AccessLog accessLog, Verbose verbose,
                     File ownedStores, io.hearth.certs.TlsContexts tls) throws Exception {
    this.tree = tree;
    this.stores = stores;
    this.auth = auth;
    this.mailer = mailer;
    this.events = events;
    this.accessLog = accessLog;
    this.ownedStores = ownedStores;
    this.aiLog = new io.hearth.mcp.AiLog();
    this.challenges = new io.hearth.certs.Challenges();
    io.hearth.mcp.AiLog aiLog = this.aiLog;
    this.live = io.hearth.live.Live.of(tree.all().keySet(), events, verbose);
    io.hearth.live.Live live = this.live;
    this.availabilities = io.hearth.availability.Availabilities.of(tree, auth,
        (url, timeout) -> fetching.get().get(url, timeout), events, verbose);
    io.hearth.availability.Availabilities availabilities = this.availabilities;
    AdminRoutes adminRoutes = new AdminRoutes(templates, events, accessLog, aiLog, mailer, live,
        true, geocoding.get(), io.hearth.common.ServerConfig.defaults(), verbose);
    adminRoutes.knowsAbout(availabilities);
    // Paced far faster than the real thing, and backing off in milliseconds rather than minutes:
    // both constants are promises made to somebody else's service, and this is not talking to one.
    // The behaviour under test is the shape -- that a failure waits and widens -- not the numbers.
    this.async = new io.hearth.async.AsyncQueue(verbose, 5, 20);
    io.hearth.places.Geocodes geocodes =
        new io.hearth.places.Geocodes(async, geocoding.get(), auth, verbose);
    adminRoutes.knowsAbout(geocodes);
    SelfRoutes selfRoutes = new SelfRoutes(templates, mailer, accessLog, verbose);
    selfRoutes.knowsAbout(geocodes);
    // uploads land in a directory of this test's own, thrown away with the databases
    this.attachmentFiles = new io.hearth.attach.DiskAttachments(
        new File(ownedStores != null ? ownedStores : new File(System.getProperty("java.io.tmpdir")),
            "attachments-" + System.nanoTime()));
    io.hearth.attach.AttachmentRoutes attachmentRoutes = new io.hearth.attach.AttachmentRoutes(
        attachmentFiles, new io.hearth.attach.BlobCache(8 * 1024 * 1024), verbose);
    attachmentRoutes.sharesFlashWith(adminRoutes.flash());
    adminRoutes.knowsAbout(attachmentRoutes);
    this.attachments = attachmentRoutes;
    this.server = new WebServer(config, tree, auth, new Pages(templates),
        new AccountRoutes(templates, mailer, verbose),
        // tests drive the inbound path directly, so invitations behave as they do on a box
        // that is receiving mail
        adminRoutes,
        selfRoutes,
        new io.hearth.mcp.McpRoutes(templates, aiLog, verbose),
        new io.hearth.api.ApiRoutes(templates, new io.hearth.web.Flash(), verbose),
        new io.hearth.availability.AvailabilityRoutes(templates, availabilities, verbose),
        attachmentRoutes,
        new io.hearth.board.BoardRoutes(templates, verbose),
        new io.hearth.calendar.CalendarRoutes(templates, verbose),
        new io.hearth.web.PwaRoutes(templates, verbose),
        new io.hearth.places.PlaceRoutes(templates, verbose),
        new io.hearth.legal.LegalRoutes(templates, verbose),
        new io.hearth.people.MemberRoutes(templates, verbose),
        new io.hearth.people.SurveyRoutes(templates, verbose),
        new io.hearth.tasks.TaskRoutes(templates, verbose),
        new io.hearth.web.HomeRoutes(templates, verbose),
        new io.hearth.people.OrientationRoutes(templates, verbose),
        new io.hearth.live.LiveRoutes(live, verbose),
        challenges, tls, accessLog, verbose);
    auth.start();
    availabilities.start();
    async.start();
    this.thread = new Thread(server, "test-web-server");
    this.thread.setDaemon(true);
    this.thread.start();
    if (!server.waitForReady(10000)) {
      throw new IllegalStateException("test server did not bind within 10 seconds");
    }
    if (!server.isAccepting()) {
      throw new IllegalStateException("test server failed to bind");
    }
    this.port = server.boundPort();
    this.httpsPort = server.boundHttpsPort();
    this.bouncePort = server.boundBouncePort();
  }

  public static Builder builder(File configs) {
    return new Builder(configs);
  }

  /** boot against a configs directory on disk, exactly as the command line would */
  public static TestServer ofConfigs(File configs) throws Exception {
    return builder(configs).build();
  }

  public static TestServer ofConfigs(File configs, Verbose verbose) throws Exception {
    return builder(configs).verbose(verbose).build();
  }

  /** boot with an empty domain tree and no databases at all */
  public int httpsPort() {
    return httpsPort;
  }

  public int bouncePort() {
    return bouncePort;
  }

  public static TestServer empty() throws Exception {
    Verbose verbose = Verbose.OFF;
    return new TestServer(new WebConfig("127.0.0.1", 0), DomainTree.EMPTY, Stores.none(),
        AuthSystem.EMPTY, Templates.compile(verbose), new CapturingMailer(),
        new LocalEventBus(verbose), new AccessLog(), verbose, null, null);
  }

  /** boot with every knob set, for tests that need to hit a limit */
  public static TestServer of(File configs, WebConfig config) throws Exception {
    return builder(configs).webConfig(config).build();
  }

  public static WebConfig config(int maxContentLength, int idleReadSeconds) {
    return new WebConfig("127.0.0.1", 0, maxContentLength, 1, 2, idleReadSeconds);
  }

  /** convenience for building a tree from a configs directory, without starting anything */
  public static DomainTree treeOf(File configs) throws ConfigException {
    return DomainScanner.scan(configs, Verbose.OFF).tree;
  }

  /** the mailer as a CapturingMailer, for tests that read codes out of it */
  public CapturingMailer mail() {
    return (CapturingMailer) mailer;
  }

  public boolean isAccepting() {
    return server.isAccepting();
  }

  @Override
  public void close() {
    server.shutdown();
    try {
      thread.join(5000);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    availabilities.shutdown();
    async.close();
    auth.close();
    stores.close();
    if (ownedStores != null) {
      deleteTree(ownedStores);
    }
  }

  private static void deleteTree(File root) {
    File[] children = root.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteTree(child);
      }
    }
    root.delete();
  }

  /** assembles a server; every part has a default so a test only names what it cares about */
  public static class Builder {
    private final File configs;
    private File stores;
    private Verbose verbose = Verbose.OFF;
    private Mailer mailer = new CapturingMailer();
    private WebConfig webConfig = new WebConfig("127.0.0.1", 0);
    private io.hearth.certs.TlsContexts tls;

    Builder(File configs) {
      this.configs = configs;
    }

    public Builder stores(File stores) {
      this.stores = stores;
      return this;
    }

    public Builder verbose(Verbose verbose) {
      this.verbose = verbose;
      return this;
    }

    public Builder mailer(Mailer mailer) {
      this.mailer = mailer;
      return this;
    }

    public Builder webConfig(WebConfig webConfig) {
      this.webConfig = webConfig;
      return this;
    }

    /** add a TLS listener on an ephemeral port, presenting whatever this holds */
    public Builder withTls(io.hearth.certs.TlsContexts tls) {
      this.tls = tls;
      this.webConfig = new WebConfig(webConfig.bind, webConfig.port, 0, webConfig.bouncePort);
      return this;
    }

    /** add the redirect-only listener on an ephemeral port */
    public Builder withBounce() {
      this.webConfig = new WebConfig(webConfig.bind, webConfig.port, webConfig.httpsPort, 0);
      return this;
    }

    public TestServer build() throws Exception {
      DomainTree tree = DomainScanner.scan(configs, verbose).tree;
      File owned = null;
      File storesRoot = stores;
      if (storesRoot == null) {
        owned = temp();
        storesRoot = owned;
      }
      LocalEventBus bus = new LocalEventBus(verbose);
      AccessLog log = new AccessLog();
      Stores opened = Stores.open(storesRoot, tree, bus, verbose);
      AuthSystem auth = AuthSystem.of(opened, tree, bus, verbose);
      Templates templates = Templates.compile(verbose);
      return new TestServer(webConfig, tree, opened, auth, templates, mailer, bus, log, verbose, owned, tls);
    }

    private static File temp() throws IOException {
      File dir = Files.createTempDirectory("hearth-test-stores").toFile();
      dir.deleteOnExit();
      return dir;
    }
  }
}
