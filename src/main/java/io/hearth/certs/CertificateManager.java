package io.hearth.certs;

import io.hearth.common.Verbose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeping certificates current, on one background thread.
 *
 * The whole of this class is "what to order and when", which is the part with judgement in it. The
 * ACME conversation is behind {@link Acme} so that this can be tested against a fake authority --
 * because the interesting cases are all failure cases, and none of them are reachable by pointing a
 * test at Let's Encrypt.
 *
 * **Nothing here can run before the socket is open.** HTTP-01 works by the certificate authority
 * fetching a path from this very server on port 80, so an order placed during boot would be waiting
 * on a listener that is waiting on the order. goatbot did not have this problem because it uploaded
 * the answer to a bucket something else served; the cost of removing that bucket is that ordering
 * has to happen after bind, which is why {@link #start} is called from the ready callback and does
 * its first sweep on a delay.
 *
 * Failure is expected and is not fatal. A domain that does not resolve yet, a port 80 that is
 * firewalled, a CA having a bad afternoon -- each of those means one domain has no certificate, is
 * written down loudly, and is retried on the next sweep. The server serves plain HTTP throughout.
 */
public class CertificateManager {
  private static final Logger LOG = LoggerFactory.getLogger(CertificateManager.class);
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
  private static final int LOG_MAX = 500;
  /** the first sweep waits this long, so the listener is certainly accepting before the CA calls */
  private static final long FIRST_SWEEP_SECONDS = 5;
  /** and then every twelve hours, which is far more often than a 90 day certificate needs */
  private static final long SWEEP_SECONDS = 12 * 3600;

  private final CertStore store;
  private final Acme acme;
  private final Challenges challenges;
  private final Verbose verbose;
  private final LinkedList<String> journal = new LinkedList<>();
  private final AtomicInteger issued = new AtomicInteger();
  private final AtomicInteger failed = new AtomicInteger();
  private volatile ScheduledExecutorService scheduler;
  private volatile List<String> domains = List.of();
  private volatile java.util.function.BiConsumer<String, String> onIssued = (domain, detail) -> {
  };
  private volatile java.util.function.BiConsumer<String, String> onFailed = (domain, why) -> {
  };

  /**
   * Called when a certificate actually lands, with the domain and a human sentence about it.
   *
   * This exists because "first check in a few seconds" is a promise, not a report. Whether the
   * certificate process is working is the single thing an operator wants to know, and it cannot be
   * known at boot -- the order has not happened yet. So the answer arrives when it arrives, on the
   * certificate thread, and the boot output says so as it happens.
   */
  public CertificateManager onIssued(java.util.function.BiConsumer<String, String> listener) {
    this.onIssued = listener;
    return this;
  }

  public CertificateManager onFailed(java.util.function.BiConsumer<String, String> listener) {
    this.onFailed = listener;
    return this;
  }

  public CertificateManager(CertStore store, Acme acme, Challenges challenges, Verbose verbose) {
    this.store = store;
    this.acme = acme;
    this.challenges = challenges;
    this.verbose = verbose;
  }

  public Challenges challenges() {
    return challenges;
  }

  public CertStore store() {
    return store;
  }

  /**
   * Begin managing these domains.
   *
   * Call this once the server is accepting connections, not before -- see the note above about why
   * ordering a certificate requires a working listener.
   */
  public synchronized void start(Collection<String> managed) {
    this.domains = List.copyOf(managed);
    if (scheduler != null) {
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "certificates");
      thread.setDaemon(true);
      return thread;
    });
    note("managing " + domains.size() + " domain(s): " + String.join(", ", domains));
    scheduler.scheduleAtFixedRate(this::sweepQuietly, FIRST_SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS);
  }

  public synchronized void shutdown() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
    challenges.clear();
  }

  private void sweepQuietly() {
    try {
      sweep();
    } catch (Exception ex) {
      LOG.error("certificate-sweep-failed", ex);
      note("the sweep failed: " + ex.getMessage());
    }
  }

  /**
   * Look at every managed domain and order what is missing or nearly expired.
   *
   * Returns how many certificates it obtained. One domain failing does not stop the others: a
   * community with four domains and one bad DNS record should end up with three certificates and
   * one clear complaint, not zero certificates.
   */
  public int sweep() {
    if (!store.hasAccount()) {
      note("no ACME account in " + store.root() + "; run --do-cert-setup");
      return 0;
    }
    Acme.Account account;
    try {
      CertStore.AccountRecord record = store.readAccount();
      account = new Acme.Account(record.directory(), store.readAccountKey(), record.url());
    } catch (Exception ex) {
      note("could not read the ACME account: " + ex.getMessage());
      return 0;
    }

    long now = System.currentTimeMillis();
    int obtained = 0;
    for (String domain : domains) {
      try {
        if (!needs(domain, now)) {
          continue;
        }
        obtain(account, domain);
        obtained++;
      } catch (Exception ex) {
        failed.incrementAndGet();
        // the message is the product here: whoever reads this is trying to work out what is wrong
        // with their DNS or their firewall, and "failed" tells them nothing
        note("could not get a certificate for " + domain + ": " + ex.getMessage());
        LOG.warn("certificate-failed for {}", domain, ex);
        safely(() -> onFailed.accept(domain, ex.getMessage()));
      }
    }
    return obtained;
  }

  /** does this domain want work, and say why in the journal when it does */
  private boolean needs(String domain, long now) {
    CertStore.Held held = store.held(domain);
    if (held == null) {
      note(domain + " has no certificate yet");
      return true;
    }
    if (held.needsRenewal(now)) {
      note(domain + " expires " + STAMP.format(Instant.ofEpochMilli(held.notAfter().getTime()))
          + " (" + held.daysLeft(now) + " day(s) left), renewing");
      return true;
    }
    verbose.detail(() -> domain + " has " + held.daysLeft(now) + " day(s) of certificate left");
    return false;
  }

  private void obtain(Acme.Account account, String domain) throws Exception {
    String domainKey = store.has(domain) && store.keyFile(domain).isFile()
        ? store.readKey(domain)
        : AcmeIssuer.newKeyPairPem();
    if (!store.keyFile(domain).isFile()) {
      store.writeKey(domain, domainKey);
    }
    Acme.Issued result = acme.issue(account, domain, domainKey, new Acme.Publisher() {
      @Override
      public void publish(String token, String keyAuthorization) {
        challenges.publish(token, keyAuthorization);
      }

      @Override
      public void withdraw(String token) {
        challenges.withdraw(token);
      }
    });
    store.writeCertificate(domain, result.chainPem(), result.pkcs8Key());
    issued.incrementAndGet();
    CertStore.Held held = store.held(domain);
    String detail = held == null
        ? "issued"
        : "good until " + STAMP.format(Instant.ofEpochMilli(held.notAfter().getTime()))
            + " (" + held.daysLeft(System.currentTimeMillis()) + " days)";
    note("got a certificate for " + domain + ", " + detail);
    // the listener is what puts the new certificate into the TLS layer and tells the operator, so
    // a listener that throws must not look like a failed order
    safely(() -> onIssued.accept(domain, detail));
  }

  // ---- what an operator reads --------------------------------------------------------------------

  /** the last few hundred lines of what the certificate thread has been doing */
  public List<String> journal() {
    synchronized (journal) {
      return new ArrayList<>(journal);
    }
  }

  public int issuedCount() {
    return issued.get();
  }

  public int failedCount() {
    return failed.get();
  }

  public List<String> managedDomains() {
    return domains;
  }

  private void safely(Runnable action) {
    try {
      action.run();
    } catch (Exception ex) {
      LOG.warn("certificate-listener-failed", ex);
    }
  }

  private void note(String message) {
    String line = STAMP.format(Instant.now()) + "  " + message;
    synchronized (journal) {
      journal.addLast(line);
      while (journal.size() > LOG_MAX) {
        journal.removeFirst();
      }
    }
    verbose.say("certificates: " + message);
  }
}
