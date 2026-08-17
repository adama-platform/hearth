package io.hearth;

import io.hearth.analytics.AccessLog;
import io.hearth.certs.AcmeIssuer;
import io.hearth.certs.CertSetup;
import io.hearth.certs.CertStore;
import io.hearth.certs.CertificateManager;
import io.hearth.certs.Challenges;
import io.hearth.certs.TlsContexts;
import io.hearth.auth.AuthSystem;
import io.hearth.board.Notifier;
import io.hearth.auth.LoginSecurity;
import io.hearth.cli.Args;
import io.hearth.cli.Ask;
import io.hearth.cli.Root;
import io.hearth.cli.Setup;
import io.hearth.common.ServerConfig;
import io.hearth.mail.Mailers;
import io.hearth.common.Boot;
import io.hearth.common.ConfigException;
import io.hearth.common.Verbose;
import io.hearth.events.EventBus;
import io.hearth.events.LocalEventBus;
import io.hearth.mail.DevBoxMailer;
import io.hearth.mail.Mailer;
import io.hearth.store.Store;
import io.hearth.store.Stores;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.hearth.vhost.DomainScanner;
import io.hearth.web.AccountRoutes;
import io.hearth.web.AdminRoutes;
import io.hearth.web.Pages;
import io.hearth.web.SelfRoutes;
import io.hearth.web.WebConfig;
import io.hearth.web.WebServer;

import java.util.List;
import java.util.Map;

/**
 * Entry point.
 *
 * The boot order is the security model: parse arguments, scan and load every configuration, compile
 * every template, open and audit every database, and only then open a socket. By the time the first
 * byte arrives, the set of domains this process will serve, the policy governing each of them, and
 * the shape of every table is fixed and in memory.
 *
 * That is also the whole of the "disk at startup only" rule. Configs, templates and schema are read
 * here and never again; after this the only thing that touches a file is the database doing what a
 * database does.
 */
public class Server {
  /**
   * What this binary is, taken from the jar rather than from a constant somebody forgot to bump.
   *
   * `just release` stamps the manifest, so a downloaded jar answers `--version` with the tag it was
   * cut from. Running from a build tree there is no manifest, and it says so -- a development build
   * claiming to be a release is how a bug report becomes unreproducible.
   */
  public static final String VERSION = versionOf();

  private static String versionOf() {
    String stamped = Server.class.getPackage() == null
        ? null : Server.class.getPackage().getImplementationVersion();
    return stamped == null || stamped.isBlank() ? "0.0.0-dev" : stamped;
  }

  public static void main(String[] args) {
    Args parsed;
    try {
      parsed = Args.parse(args);
    } catch (Args.ArgsException ex) {
      System.err.println("error: " + ex.getMessage());
      System.err.println();
      System.err.println(Args.usage());
      System.exit(2);
      return;
    }
    if (parsed.version) {
      System.out.println("Hearth " + VERSION);
      return;
    }
    if (parsed.help) {
      System.out.println(Args.usage());
      return;
    }
    if (parsed.install != null) {
      // before anything is opened: this writes files into a directory and stops, and it is the one
      // command somebody runs on a machine that has never run this server
      System.exit(install(parsed.install) ? 0 : 1);
      return;
    }
    try {
      serve(parsed);
    } catch (ConfigException ex) {
      Boot.fail(ex.getMessage());
      System.exit(1);
    } catch (Exception ex) {
      Boot.fail("unexpected failure: " + ex);
      ex.printStackTrace();
      System.exit(1);
    }
  }

  /**
   * Write a service into a directory and say what was written.
   *
   * Prints rather than returns, because the whole value of it is that an operator can read what
   * they are about to run as root before they run it.
   */
  private static boolean install(String where) {
    Boot.banner(VERSION);
    Boot.section("install");
    io.hearth.cli.Install.Report report =
        io.hearth.cli.Install.run(new java.io.File(where), io.hearth.cli.Install.currentJar());
    if (!report.ok()) {
      Boot.fail(report.problem());
      return false;
    }
    Boot.info("machine", io.hearth.cli.Install.distribution() + ", systemd");
    Boot.info("directory", report.home().getAbsolutePath());
    Boot.info("service", report.service() + ".service");
    Boot.info("java", io.hearth.cli.Install.javaBinary());
    for (String wrote : report.wrote()) {
      Boot.ok(wrote);
    }
    if (report.staged()) {
      Boot.warn("a jar was already installed here, so this one is staged rather than swapped in --"
          + " restarting the service is what picks it up");
    }
    Boot.section("next");
    for (String step : io.hearth.cli.Install.nextSteps(report)) {
      Boot.step(step);
    }
    return true;
  }

  /** the banner name: one of the domains this server actually answers for, rather than a guess */
  private static String firstDomainOf(io.hearth.vhost.DomainTree tree) {
    for (String domain : tree.all().keySet()) {
      return domain;
    }
    return "hearth";
  }

  private static void serve(Args args) throws Exception {
    Root root = Root.open(args.root);
    ServerConfig settings = ServerConfig.read(root.configFile());
    // the flag wins over the file: somebody adding -v to one run is asking about this run
    Verbose verbose = new Verbose(args.verbose || settings.verbose);
    Boot.banner(VERSION);

    // the walkthroughs need the root and nothing else, so they run before anything is opened
    if (args.setup || args.domainSetup != null || args.setupEmail != null || args.setupGps
        || args.testEmailDomain != null) {
      Setup setup = new Setup(root, new Ask());
      boolean done;
      try {
        if (args.setup) {
          done = setup.server();
        } else if (args.domainSetup != null) {
          done = setup.domain(args.domainSetup);
        } else if (args.setupEmail != null) {
          done = setup.email(args.setupEmail);
        } else if (args.setupGps) {
          done = setup.gps();
        } else {
          done = setup.testEmail(args.testEmailDomain, args.testEmailTo);
        }
      } catch (Ask.NoTerminal ex) {
        Boot.fail(ex.getMessage());
        System.exit(1);
        return;
      }
      if (!done) {
        System.exit(1);
      }
      return;
    }

    Boot.section("root");
    Boot.info("directory", root.describe());
    Boot.info("settings", root.hasConfig() ? Root.CONFIG_FILE : "every default (no config.cfg)");
    Boot.info("listeners", settings.describe());

    Boot.section("configs");
    Boot.step("scanning " + root.domains());
    DomainScanner.Result scan = DomainScanner.scan(root.domains(), settings.zone, verbose);
    for (String warning : scan.warnings) {
      Boot.warn(warning);
    }
    if (scan.tree.isEmpty()) {
      // an empty tree means every request gets the sad face, which is almost never intended
      Boot.warn("no " + DomainScanner.CONFIG_SUFFIX + " files in " + scan.root + "; every request will be refused");
    } else {
      Boot.ok("loaded " + scan.tree.size() + " domain(s)");
      for (Map.Entry<String, DomainConfig> entry : scan.tree.all().entrySet()) {
        DomainConfig config = entry.getValue();
        Boot.info(entry.getKey(), config.name
            + (config.enabled ? "" : " [disabled]")
            + (config.wildcard ? " [+subdomains]" : "")
            + (config.useDatabaseDomain == null ? "" : " [db: " + config.useDatabaseDomain + "]")
            // an operator who turned on the model endpoint should see it here, because what it
            // hands out is the ability to rewrite the site
            + (config.mcp.enabled ? " [ai: " + config.mcp.describe() + "]" : ""));
      }
    }

    if (args.check) {
      // --check exists so that `just check` can validate an operator's tree in CI or before a
      // restart. Reaching here at all means the scan passed, since any problem would have thrown.
      Boot.ok("configs are valid");
      return;
    }

    if (args.setupCerts) {
      // needs the scan (to know which domains it is about to promise certificates for) and nothing
      // else, so it runs before the databases are even opened
      CertStore certStore = CertStore.open(root.certs());
      boolean ready = new CertSetup(certStore, new AcmeIssuer(message -> Boot.info("acme", message)))
          .run(scan.tree.all(), settings.httpPort);
      if (!ready) {
        System.exit(1);
      }
      return;
    }

    Boot.section("templates");
    Templates templates = Templates.compile(verbose, settings.compactHtml);
    Boot.ok("compiled " + Templates.PAGES.size() + " template(s)");

    Boot.section("events");
    // built before the stores, because every store announces its writes on it
    LocalEventBus events = new LocalEventBus(verbose);
    AccessLog accessLog = new AccessLog();
    Boot.ok("event bus holding the last " + events.capacity() + " mutations");
    Boot.info("access log", "the last " + accessLog.capacity() + " requests");

    Boot.section("stores");
    Boot.step("opening databases in " + root.databases());
    Stores stores = Stores.open(root.databases(), scan.tree, events, verbose);
    for (Store.Audit audit : stores.audits()) {
      Boot.info(audit.databaseDomain(), audit.summary());
      for (String note : audit.notes()) {
        Boot.warn(audit.databaseDomain() + ": " + note);
      }
    }
    for (Map.Entry<String, List<String>> shared : stores.sharing().entrySet()) {
      if (shared.getValue().size() > 1) {
        Boot.info("shared", shared.getKey() + " <- " + String.join(", ", shared.getValue()));
      }
    }
    Boot.ok(stores.databaseCount() + " database(s) for " + stores.domainCount() + " domain(s)");

    Boot.section("accounts");
    AuthSystem auth = AuthSystem.of(stores, scan.tree, events, verbose);
    for (Map.Entry<String, LoginSecurity> policy : auth.policies().entrySet()) {
      Boot.info(policy.getKey(), policy.getValue().describe());
    }
    for (Map.Entry<String, DomainConfig> entry : scan.tree.all().entrySet()) {
      Boot.info(entry.getKey() + " cache", entry.getValue().caches.describe());
    }
    Boot.section("email");
    Mailers mailer = Mailers.of(scan.tree.all(), verbose);
    for (Map.Entry<String, String> line : mailer.describe(scan.tree.all()).entrySet()) {
      Boot.info(line.getKey(), line.getValue());
    }
    if (mailer.realCount() == 0) {
      Boot.warn("no email provider anywhere: codes and links print to this terminal");
    } else if (mailer.realCount() < scan.tree.all().size()) {
      Boot.warn("some domains have no provider; their codes print to this terminal");
    }
    // one thread for the whole box: delivery is never on the request path, because a reply in a
    // thread with forty watchers is forty signed requests to Amazon
    Notifier notifier = new Notifier(auth, scan.tree.all(), mailer, io.hearth.sms.NoSms.INSTANCE,
        verbose, 60, settings.smtp.enabled);

    Boot.section("mail in");
    // a calendar reply first, and whatever it was not, printed. The order is the point: an Accept
    // pressed in somebody's mail client has to become an RSVP without them opening the site.
    io.hearth.places.Geocoder geocoder = settings.gps.build(verbose);
    io.hearth.smtp.MailReceiver receiver = new io.hearth.smtp.CommunityMailReceiver(auth, scan.tree,
        new io.hearth.smtp.TerminalMailReceiver(), geocoder, verbose);
    io.hearth.smtp.SmtpServer smtp = new io.hearth.smtp.SmtpServer(settings.smtp, scan.tree,
        receiver, firstDomainOf(scan.tree), verbose);
    Boot.info("smtp", settings.smtp.describe());
    Boot.info("geocoding", settings.gps.describe());
    if (settings.smtp.enabled) {
      Boot.info("routing", "only domains with a config file; this server never relays");
      Boot.info("handling", "calendar replies become RSVPs, invitations become events;"
          + " everything else prints here");
    }

    Boot.section("http");
    WebConfig webConfig = settings.web();
    Pages pages = new Pages(templates);
    AccountRoutes accountRoutes = new AccountRoutes(templates, mailer, verbose);
    io.hearth.mcp.AiLog aiLog = new io.hearth.mcp.AiLog();
    // one hub per community, wired to the event bus before the socket opens so a write from
    // anywhere -- a browser, a model, the admin -- shows up live without that code knowing
    io.hearth.live.Live live = io.hearth.live.Live.of(scan.tree.all().keySet(), events, verbose);
    AdminRoutes adminRoutes =
        new AdminRoutes(templates, events, accessLog, aiLog, mailer, live,
            settings.smtp.enabled, geocoder, settings, verbose);
    SelfRoutes selfRoutes = new SelfRoutes(templates, mailer, accessLog, verbose);
    io.hearth.mcp.McpRoutes mcpRoutes = new io.hearth.mcp.McpRoutes(templates, aiLog, verbose);
    io.hearth.api.ApiRoutes apiRoutes =
        new io.hearth.api.ApiRoutes(templates, new io.hearth.web.Flash(), verbose);
    // one grid per community, wired to the event bus before the socket opens and pulling calendars
    // on its own thread: nothing about this may ever happen while somebody is waiting for a page
    io.hearth.availability.Availabilities availabilities = io.hearth.availability.Availabilities.of(
        scan.tree, auth, io.hearth.availability.CalendarFetch.overHttps(), events, verbose);
    io.hearth.availability.AvailabilityRoutes availabilityRoutes =
        new io.hearth.availability.AvailabilityRoutes(templates, availabilities, verbose);
    adminRoutes.knowsAbout(availabilities);
    // One queue for the whole box, because the rate limit is a property of this server as somebody
    // else's client rather than of a community. Everything that wants an address turned into a
    // point goes through it, slowly, out of the way of anybody waiting for a page.
    io.hearth.async.AsyncQueue async = new io.hearth.async.AsyncQueue(verbose);
    io.hearth.places.Geocodes geocodes =
        new io.hearth.places.Geocodes(async, geocoder, auth, verbose);
    adminRoutes.knowsAbout(geocodes);
    selfRoutes.knowsAbout(geocodes);
    // one sample a minute, on the pass that already wakes every minute: a second thread to read
    // two files out of /proc would be a second thread
    notifier.alsoEachPass(() -> adminRoutes.machine().sample());
    // Polls whose moment has come: counted, and -- when they were about a date -- put in the
    // calendar. On the pass rather than on a page load, because a vote that only closed when
    // somebody happened to look at it would close on Monday morning.
    io.hearth.board.PollClock pollClock =
        new io.hearth.board.PollClock(auth, scan.tree.all(), verbose);
    notifier.alsoEachPass(() -> pollClock.sweep(System.currentTimeMillis()));
    // and the same pass picks up anybody who said where they are and has not been placed yet. A
    // bounded slice, because a community importing three hundred members at once should take the
    // afternoon over it rather than the queue's whole capacity in one minute.
    notifier.alsoEachPass(() -> {
      for (String domain : scan.tree.all().keySet()) {
        geocodes.sweep(domain, 25);
      }
    });
    // uploads: one directory under the root, one cache in front of it, and a ceiling taken from
    // the most generous community on the box -- the pipeline needs one number and cannot ask a
    // domain, so the largest wins and UploadGate keeps it to the upload path
    io.hearth.attach.AttachmentStore attachmentFiles =
        new io.hearth.attach.DiskAttachments(root.attachments());
    int cacheBytes = 0;
    for (io.hearth.vhost.DomainConfig each : scan.tree.all().values()) {
      webConfig.allowUploadsOf(each.attachments.maxBytes);
      cacheBytes = Math.max(cacheBytes, each.attachments.cacheBytes);
    }
    io.hearth.attach.AttachmentRoutes attachmentRoutes = new io.hearth.attach.AttachmentRoutes(
        attachmentFiles, new io.hearth.attach.BlobCache(cacheBytes), verbose);
    attachmentRoutes.sharesFlashWith(adminRoutes.flash());
    adminRoutes.knowsAbout(attachmentRoutes);
    Boot.info("attachments", attachmentFiles.describe());
    io.hearth.board.BoardRoutes boardRoutes = new io.hearth.board.BoardRoutes(templates, verbose);
    io.hearth.calendar.CalendarRoutes calendarRoutes =
        new io.hearth.calendar.CalendarRoutes(templates, verbose);
    io.hearth.web.PwaRoutes pwaRoutes = new io.hearth.web.PwaRoutes(templates, verbose);
    io.hearth.places.PlaceRoutes placeRoutes =
        new io.hearth.places.PlaceRoutes(templates, verbose);
    io.hearth.legal.LegalRoutes legalRoutes =
        new io.hearth.legal.LegalRoutes(templates, verbose);
    io.hearth.live.LiveRoutes liveRoutes = new io.hearth.live.LiveRoutes(live, verbose);
    io.hearth.people.MemberRoutes memberRoutes =
        new io.hearth.people.MemberRoutes(templates, verbose);
    io.hearth.people.SurveyRoutes surveyRoutes =
        new io.hearth.people.SurveyRoutes(templates, verbose);
    io.hearth.tasks.TaskRoutes taskRoutes = new io.hearth.tasks.TaskRoutes(templates, verbose);
    io.hearth.web.HomeRoutes homeRoutes = new io.hearth.web.HomeRoutes(templates, verbose);
    io.hearth.people.OrientationRoutes orientationRoutes =
        new io.hearth.people.OrientationRoutes(templates, verbose);
    Challenges challenges = new Challenges();
    CertificateManager certificates = null;
    TlsContexts tls = null;
    {
      Boot.section("certificates");
      CertStore certStore = CertStore.open(root.certs());
      Boot.info("cache", certStore.root().getAbsolutePath());
      Boot.info("account", CertSetup.describe(root.certs(), certStore));
      for (CertStore.Held held : certStore.all()) {
        Boot.info(held.domain(), held.daysLeft(System.currentTimeMillis()) + " day(s) left");
      }
      if (webConfig.httpsEnabled()) {
        tls = new TlsContexts(certStore, verbose, webConfig.http2);
        int loaded = tls.reload();
        if (loaded == 0) {
          Boot.warn("no certificate is loadable yet, so https will present a self-signed one"
              + " until the first order lands");
        } else {
          Boot.ok("https will present " + tls.describe());
        }
      }
      if (certStore.hasAccount()) {
        certificates = new CertificateManager(certStore,
            new AcmeIssuer(message -> verbose.say("certificates: " + message)), challenges, verbose);
        if (settings.httpPort != 80) {
          Boot.warn("the authority verifies over port 80; this server is on " + settings.httpPort
              + ", so forward it or set http-port to 80 in config.cfg");
        }
      } else {
        Boot.warn("no account yet, so nothing will be issued; run --do-cert-setup");
      }
    }

    TlsContexts tlsContexts = tls;
    CertificateManager certManager = certificates;
    if (certManager != null) {
      // Report the moment a certificate actually exists, rather than promising one at boot. This is
      // the only honest answer to "is the certificate process working", because at boot it has not
      // run yet -- and it is also where a new certificate reaches the TLS layer, so a renewal months
      // from now is presented without a restart.
      certManager.onIssued((domain, detail) -> {
        if (tlsContexts != null && tlsContexts.reload(domain)) {
          Boot.ok("certificate for " + domain + ": " + detail + "; https is now serving it");
        } else {
          Boot.ok("certificate for " + domain + ": " + detail);
        }
      });
      certManager.onFailed((domain, why) ->
          Boot.warn("no certificate for " + domain + " yet: " + why));
    }

    WebServer server = new WebServer(webConfig, scan.tree, auth, pages, accountRoutes, adminRoutes,
        selfRoutes, mcpRoutes, apiRoutes, availabilityRoutes, attachmentRoutes, boardRoutes, calendarRoutes, pwaRoutes, placeRoutes, legalRoutes, memberRoutes, surveyRoutes, taskRoutes,
        homeRoutes, orientationRoutes, liveRoutes, challenges, tlsContexts, accessLog, verbose);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      Boot.step("shutting down");
      if (certManager != null) {
        certManager.shutdown();
      }
      notifier.shutdown();
      availabilities.shutdown();
      async.close();
      smtp.shutdown();
      server.shutdown();
      auth.close();
      stores.close();
    }, "shutdown"));

    auth.start();
    notifier.start();
    async.start();
    // after the databases are open, so the first build has something to read, and before the socket
    // is accepting, so nobody waits on it
    availabilities.start();
    Thread listener = new Thread(server, "web-server");
    listener.start();
    if (!server.waitForReady(10000)) {
      Boot.fail("server did not come up within 10 seconds");
      server.shutdown();
      System.exit(1);
    }
    if (!server.isAccepting()) {
      Boot.fail("server failed to bind " + settings.bind + ":" + settings.httpPort);
      System.exit(1);
    }
    Boot.ok("listening on " + settings.bind + ":" + server.boundPort() + " (http)");
    if (webConfig.httpsEnabled()) {
      if (server.boundHttpsPort() < 0) {
        Boot.fail("could not bind https on " + settings.bind + ":" + webConfig.httpsPort);
        server.shutdown();
        System.exit(1);
      }
      Boot.ok("listening on " + settings.bind + ":" + server.boundHttpsPort()
          + (webConfig.http2 ? " (https, http/2 offered)" : " (https, http/1.1)"));
    }
    if (webConfig.bounceEnabled()) {
      if (server.boundBouncePort() < 0) {
        Boot.fail("could not bind the bounce listener on " + settings.bind + ":" + webConfig.bouncePort);
        server.shutdown();
        System.exit(1);
      }
      Boot.ok("listening on " + settings.bind + ":" + server.boundBouncePort()
          + " (redirects to https and nothing else)");
    }
    // Inbound mail, after the web listeners, so a mail conversation can never delay a page load
    // coming up. Its own event loop for the same reason.
    if (settings.smtp.enabled) {
      java.util.concurrent.CountDownLatch mailReady = new java.util.concurrent.CountDownLatch(1);
      smtp.whenBound(mailReady::countDown);
      Thread mailListener = new Thread(smtp, "smtp-server");
      mailListener.setDaemon(true);
      mailListener.start();
      // Report what happened rather than what is about to: bind, then say so. Port 25 needs root
      // and the usual failure is somebody running this without it, which must not stop the site.
      if (!mailReady.await(10, java.util.concurrent.TimeUnit.SECONDS) || !smtp.isAccepting()) {
        Boot.warn("could not bind smtp on " + settings.smtp.port
            + " -- the site is unaffected, and no mail will arrive");
      } else {
        Boot.ok("listening on " + smtp.port() + " (smtp, inbound only, never relays)");
      }
    }

    if (certManager != null) {
      // Only now. HTTP-01 works by the authority fetching a path from this very server, so an
      // order placed before the socket was accepting would be waiting on a listener that is
      // waiting on the order.
      certManager.start(CertSetup.managedDomains(scan.tree));
      Boot.info("certificates", "checking " + String.join(", ", certManager.managedDomains())
          + " -- each will report below as it succeeds or fails");
    }
    Boot.info("mode", webConfig.httpsEnabled()
        ? (webConfig.http2 ? "http/2 and http/1.1 over TLS, certificate per domain"
            : "http/1.1 over TLS, certificate per domain")
        : "http/1.1, no TLS -- plain http only");
    Boot.info("verbose", args.verbose ? "on" : "off");
    Boot.ready((webConfig.httpsEnabled() ? "https://localhost:" + server.boundHttpsPort()
        : "http://localhost:" + server.boundPort()) + "/");
    listener.join();
  }
}
