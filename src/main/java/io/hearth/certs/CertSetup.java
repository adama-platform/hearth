package io.hearth.certs;

import io.hearth.common.Boot;
import io.hearth.vhost.DomainConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * `--do-cert-setup`: the walkthrough that turns an empty `--certs` directory into a working one.
 *
 * This exists because the failure mode it prevents is expensive. Certificate authorities rate limit
 * hard -- Let's Encrypt allows a handful of failures per hour per account -- so somebody who starts
 * a server with the DNS not yet pointed at it can lock themselves out for an afternoon by simply
 * restarting a few times. The cheapest place to catch that is before the account exists, by asking
 * out loud whether the two things that must be true actually are.
 *
 * So this is a conversation rather than a flag: it says what is required, checks what it can check
 * from here, offers staging first, prints the terms of service with its URL, registers the account,
 * and then tells you exactly what happens on the next boot. Nothing about it is clever. It is a
 * checklist that refuses to be skipped, and the reason it is worth the code is that the alternative
 * is somebody reading an ACME error at midnight.
 */
public class CertSetup {
  private final CertStore store;
  private final Acme acme;
  private final BufferedReader in;
  private final PrintStream out;

  public CertSetup(CertStore store, Acme acme) {
    this(store, acme, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
  }

  public CertSetup(CertStore store, Acme acme, BufferedReader in, PrintStream out) {
    this.store = store;
    this.acme = acme;
    this.in = in;
    this.out = out;
  }

  /** the walkthrough; returns true when the directory is ready to issue certificates */
  public boolean run(Map<String, DomainConfig> domains, int port) throws IOException {
    out.println();
    out.println(Boot.sectionLine("certificate setup"));
    out.println("  This will register an account with a certificate authority and set this");
    out.println("  directory up as the certificate cache:");
    out.println();
    out.println("      " + store.root().getAbsolutePath());
    out.println();

    List<String> managed = managedDomains(domains);
    if (managed.isEmpty()) {
      warn("no domains are configured, so there is nothing to get certificates for");
      out.println("  Add a <domain>.cfg to your configs directory first.");
      return false;
    }

    if (!explainRequirements(managed, port)) {
      return false;
    }
    if (store.hasAccount() && !confirmReplacingAccount()) {
      return false;
    }
    return register();
  }

  /**
   * The two things that have to be true, said plainly, and checked where checking is possible.
   *
   * The DNS check is advisory: this machine's idea of its own address is often wrong behind NAT or
   * a load balancer, so a mismatch is a warning rather than a refusal. Saying "this looks wrong,
   * are you sure" is the useful behaviour; refusing on it would be wrong about half the time.
   */
  private boolean explainRequirements(List<String> managed, int port) throws IOException {
    out.println("  Two things have to be true for this to work, and neither is something this");
    out.println("  server can arrange for you:");
    out.println();
    out.println("    1. Each domain's DNS points at this machine.");
    out.println("    2. Port 80 on this machine is reachable from the internet.");
    out.println();
    out.println("  The authority proves you control a domain by asking this server for a file over");
    out.println("  plain HTTP on port 80. There is no DNS record to add and nothing to upload --");
    out.println("  this server answers the request itself, at " + Challenges.PREFIX + "...");
    out.println();
    if (port != 80) {
      warn("this server is set to listen on port " + port + ", not 80");
      out.println("  The authority will connect to port 80 regardless. Either run with --port 80,");
      out.println("  or forward port 80 to " + port + " before continuing.");
      out.println();
    }

    out.println("  Certificates will be managed for:");
    for (String domain : managed) {
      String note = resolves(domain);
      out.println("      " + domain + (note == null ? "" : "   " + note));
    }
    out.println();
    out.println("  A domain that is served by wildcard gets a certificate for its own name only.");
    out.println("  A wildcard certificate needs a DNS challenge, which this deliberately does not do.");
    out.println();
    return ask("Are those domains pointed here, with port 80 open? [y/N] ");
  }

  /** what this machine can tell about a domain, phrased as a hint rather than a verdict */
  private String resolves(String domain) {
    try {
      InetAddress[] addresses = InetAddress.getAllByName(domain);
      ArrayList<String> found = new ArrayList<>();
      for (InetAddress address : addresses) {
        found.add(address.getHostAddress());
      }
      return "-> " + String.join(", ", found);
    } catch (Exception ex) {
      return "-> does not resolve from here (this may still be fine, but check it)";
    }
  }

  private boolean confirmReplacingAccount() throws IOException {
    out.println();
    try {
      warn("there is already an account in this directory: " + store.readAccount().describe());
    } catch (IOException ex) {
      warn("there is already an account file in this directory, and it did not parse");
    }
    out.println("  Registering again makes a new account. The old one is not deleted at the");
    out.println("  authority, but this server will stop using it, and certificates already issued");
    out.println("  stay valid until they expire.");
    out.println();
    return ask("Replace it? [y/N] ");
  }

  private boolean register() throws IOException {
    out.println();
    out.println("  Staging first is the recommendation. The staging authority issues certificates");
    out.println("  browsers will not trust, but its rate limits are generous -- so if the DNS or the");
    out.println("  firewall is wrong, you find out without burning a production quota.");
    out.println();
    boolean staging = ask("Use the staging authority? [Y/n] ", true);
    String directory = staging ? Acme.STAGING : Acme.PRODUCTION;

    out.println();
    out.println("  An email address goes on the account. The authority uses it to warn you if a");
    out.println("  certificate is about to expire and nothing has renewed it.");
    String email = prompt("  Contact email: ");
    if (email.isEmpty() || email.indexOf('@') <= 0) {
      fail("that does not look like an email address");
      return false;
    }

    String terms;
    try {
      terms = acme.termsOfService(directory);
    } catch (Exception ex) {
      fail("could not reach the certificate authority: " + ex.getMessage());
      out.println("  Check this machine's outbound internet access and try again.");
      return false;
    }
    out.println();
    if (terms != null) {
      out.println("  The authority's terms of service:");
      out.println("      " + terms);
      out.println();
    }
    if (!ask("Do you agree to them? [y/N] ")) {
      out.println("  Nothing was registered.");
      return false;
    }

    out.println();
    step("generating an account key");
    String accountKey;
    try {
      accountKey = AcmeIssuer.newKeyPairPem();
      store.writeAccountKey(accountKey);
    } catch (Exception ex) {
      fail("could not write " + store.accountKeyFile() + ": " + ex.getMessage());
      return false;
    }
    ok("wrote " + store.accountKeyFile().getName() + " (keep this; losing it means starting over)");

    step("registering with the authority");
    String url;
    try {
      url = acme.registerAccount(directory, accountKey, email, true);
    } catch (Exception ex) {
      fail("registration failed: " + ex.getMessage());
      // the key on disk without an account is worse than nothing: the next boot would look
      // half-configured, so take it back out
      store.accountKeyFile().delete();
      return false;
    }
    store.writeAccount(new CertStore.AccountRecord(url, email, directory, staging));
    ok("registered " + (staging ? "a staging" : "a production") + " account for " + email);

    out.println();
    out.println("  Done. This directory now holds:");
    out.println("      account.key    the account key");
    out.println("      account.json   the account URL and what it was registered against");
    out.println();
    out.println("  Start the server with --certs " + store.root().getAbsolutePath() + " and it will");
    out.println("  get a certificate for each domain a few seconds after it starts listening, then");
    out.println("  renew each one 20 days before it expires.");
    if (staging) {
      out.println();
      warn("these will be staging certificates, which browsers do not trust");
      out.println("  Once a full round works, run --do-cert-setup again and answer 'n' to staging.");
    }
    return true;
  }

  /** every domain that can actually have a certificate, in a stable order */
  /**
   * Everything a certificate is needed for, including named subdomains.
   *
   * A domain that lists `subdomains: ["www"]` is answering on www by name, so a browser arriving
   * there over TLS needs a certificate naming it -- and an authority will issue for a name, which
   * is precisely why a list is certifiable where `wildcard: true` is not.
   */
  public static List<String> managedDomains(io.hearth.vhost.DomainTree tree) {
    ArrayList<String> managed = new ArrayList<>(managedDomains(tree.all()));
    for (String host : tree.hostnames()) {
      if (!managed.contains(host) && issuable(host)) {
        managed.add(host);
      }
    }
    java.util.Collections.sort(managed);
    return managed;
  }

  /** a name an authority could actually issue for */
  private static boolean issuable(String host) {
    return host.contains(".") && !host.equals("localhost") && !host.endsWith(".localhost");
  }

  public static List<String> managedDomains(Map<String, DomainConfig> domains) {
    ArrayList<String> managed = new ArrayList<>();
    for (Map.Entry<String, DomainConfig> entry : domains.entrySet()) {
      DomainConfig config = entry.getValue();
      if (!config.enabled) {
        continue;
      }
      // a junction label like "org" is in the tree because something hangs off it, and is not a
      // hostname anybody could point at this machine
      if (!entry.getKey().contains(".") && !entry.getKey().equals("localhost")) {
        continue;
      }
      if (entry.getKey().equals("localhost") || entry.getKey().endsWith(".localhost")) {
        // no authority will ever issue for these, and asking is how you meet a rate limit
        continue;
      }
      managed.add(entry.getKey());
    }
    return managed;
  }

  // everything this walkthrough says goes through its own stream, so a test can drive it with
  // canned answers and read back exactly what a person would have seen
  private void warn(String message) {
    out.println(Boot.warnLine(message));
  }

  private void fail(String message) {
    out.println(Boot.failLine(message));
  }

  private void step(String message) {
    out.println(Boot.stepLine(message));
  }

  private void ok(String message) {
    out.println(Boot.okLine(message));
  }

  private boolean ask(String question) throws IOException {
    return ask(question, false);
  }

  private boolean ask(String question, boolean defaultYes) throws IOException {
    out.print("  " + question);
    out.flush();
    String answer = in.readLine();
    if (answer == null) {
      // no terminal: refuse rather than assume. This walkthrough exists to make somebody think,
      // and a pipe cannot think.
      out.println();
      fail("--do-cert-setup needs a terminal to ask questions on");
      return false;
    }
    String trimmed = answer.trim().toLowerCase(Locale.ROOT);
    if (trimmed.isEmpty()) {
      return defaultYes;
    }
    return trimmed.equals("y") || trimmed.equals("yes");
  }

  private String prompt(String question) throws IOException {
    out.print(question);
    out.flush();
    String answer = in.readLine();
    return answer == null ? "" : answer.trim();
  }

  /** for the boot report: is this directory ready to issue anything? */
  public static String describe(File certs, CertStore store) {
    if (!store.hasAccount()) {
      return "no account yet -- run --do-cert-setup";
    }
    try {
      return store.readAccount().describe() + ", " + store.all().size() + " certificate(s) cached";
    } catch (IOException ex) {
      return "the account file did not parse";
    }
  }
}
