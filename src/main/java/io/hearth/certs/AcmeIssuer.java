package io.hearth.certs;

import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.security.KeyPair;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * The real certificate authority, over ACME.
 *
 * Adapted from goatbot's agent, with three things taken out and one put in.
 *
 * Out: the S3 bucket the HTTP-01 answer used to be uploaded to, the Route53 client for DNS-01, and
 * the shell-out to `openssl` for the PKCS#8 conversion. The first two were only there because the
 * thing ordering the certificate was not the thing serving the domain; here it is, so the answer
 * goes in a map and this server hands it over itself. The third is four lines of Java.
 *
 * In: a strict timeout on every wait loop. goatbot's polled `while (status != READY)` with no
 * bound, which is fine for an agent whose whole job is certificates and is very much not fine on a
 * boot path -- an unreachable domain would hang the renewal thread forever and nothing would ever
 * say why.
 *
 * **HTTP-01 only, which means no wildcards.** A wildcard certificate requires DNS-01, which requires
 * credentials for whoever runs the DNS, which is exactly the dependency this is removing. A domain
 * gets a certificate for its own name; subdomains that a config serves by wildcard need their own
 * config file and their own certificate.
 */
public class AcmeIssuer implements Acme {
  /** how long to wait for a status to move before giving up on the order */
  private static final long ORDER_TIMEOUT_MILLIS = 120_000L;
  private static final long POLL_MILLIS = 2_000L;

  private final Consumer<String> log;

  public AcmeIssuer(Consumer<String> log) {
    this.log = log;
  }

  @Override
  public String termsOfService(String directory) throws Exception {
    Session session = new Session(directory);
    URI terms = session.getMetadata().getTermsOfService();
    return terms == null ? null : terms.toString();
  }

  @Override
  public String registerAccount(String directory, String accountKeyPem, String contactEmail,
                                boolean agreeToTerms) throws Exception {
    if (!agreeToTerms) {
      throw new IllegalStateException("the certificate authority's terms have to be agreed to");
    }
    Session session = new Session(directory);
    KeyPair keyPair = readKeyPair(accountKeyPem);
    AccountBuilder builder = new AccountBuilder().agreeToTermsOfService().useKeyPair(keyPair);
    if (contactEmail != null && !contactEmail.isBlank()) {
      builder.addEmail(contactEmail.trim());
    }
    // fully qualified: the Account this class implements against is Acme.Account, and letting the
    // two share a simple name here is exactly the confusion the compiler already caught once
    org.shredzone.acme4j.Account account = builder.create(session);
    return account.getLocation().toString();
  }

  @Override
  public Issued issue(Acme.Account account, String domain, String domainKeyPem, Publisher publisher)
      throws Exception {
    if (domain.startsWith("*")) {
      throw new IllegalArgumentException("a wildcard certificate needs DNS-01, which this does not do;"
          + " give " + domain + " its own config and its own certificate");
    }
    Session session = new Session(account.directory());
    Login login = session.login(new URL(account.accountUrl()), readKeyPair(account.accountKeyPem()));
    KeyPair domainKey = readKeyPair(domainKeyPem);

    log.accept("ordering a certificate for " + domain);
    Order order = login.getAccount().newOrder().domains(domain).create();

    String publishedToken = null;
    try {
      for (Authorization authorization : order.getAuthorizations()) {
        if (authorization.getStatus() != Status.PENDING) {
          continue;
        }
        Http01Challenge challenge = authorization.findChallenge(Http01Challenge.class);
        if (challenge == null) {
          throw new IllegalStateException("the CA did not offer an HTTP-01 challenge for " + domain
              + "; this server cannot answer any other kind");
        }
        publishedToken = challenge.getToken();
        publisher.publish(challenge.getToken(), challenge.getAuthorization());
        log.accept("answering " + Challenges.PREFIX + challenge.getToken() + " for " + domain);
        challenge.trigger();
        awaitAuthorization(authorization, domain);
      }

      awaitStatus(order, Status.READY, domain, "authorization");

      CSRBuilder csr = new CSRBuilder();
      csr.addDomain(domain);
      csr.sign(domainKey);
      order.execute(csr.getEncoded());
      awaitStatus(order, Status.VALID, domain, "signing");

      Certificate certificate = order.getCertificate();
      if (certificate == null) {
        throw new IllegalStateException("the CA reported success but returned no certificate for " + domain);
      }
      StringWriter chain = new StringWriter();
      certificate.writeCertificate(chain);
      log.accept("issued a certificate for " + domain);
      return new Issued(chain.toString(), toPkcs8(domainKey));
    } finally {
      // always, including the failure paths: a stale answer left in the map is one this server
      // would keep serving for a token nobody is going to ask about again
      if (publishedToken != null) {
        publisher.withdraw(publishedToken);
      }
    }
  }

  private void awaitAuthorization(Authorization authorization, String domain) throws Exception {
    long deadline = System.currentTimeMillis() + ORDER_TIMEOUT_MILLIS;
    while (authorization.getStatus() != Status.VALID) {
      if (authorization.getStatus() == Status.INVALID) {
        throw new IllegalStateException("the CA could not reach " + domain + " to verify it."
            + " Check that " + domain + " resolves to this machine and that port 80 is open to the"
            + " internet: " + describe(authorization));
      }
      if (System.currentTimeMillis() > deadline) {
        throw new IllegalStateException("gave up waiting for the CA to verify " + domain);
      }
      Thread.sleep(POLL_MILLIS);
      authorization.update();
    }
  }

  private void awaitStatus(Order order, Status wanted, String domain, String phase) throws Exception {
    long deadline = System.currentTimeMillis() + ORDER_TIMEOUT_MILLIS;
    while (order.getStatus() != wanted) {
      if (order.getStatus() == Status.INVALID) {
        throw new IllegalStateException("the order for " + domain + " failed during " + phase);
      }
      if (System.currentTimeMillis() > deadline) {
        throw new IllegalStateException("gave up waiting for the order for " + domain
            + " to finish " + phase);
      }
      Thread.sleep(POLL_MILLIS);
      order.update();
    }
  }

  private static String describe(Authorization authorization) {
    try {
      Http01Challenge challenge = authorization.findChallenge(Http01Challenge.class);
      if (challenge != null && challenge.getError() != null) {
        return String.valueOf(challenge.getError());
      }
    } catch (Exception ex) {
      // the error detail is a nicety; not having it must not replace the real message
    }
    return "no further detail from the CA";
  }

  // ---- keys ---------------------------------------------------------------------------------------

  public static String newKeyPairPem() throws Exception {
    KeyPair keyPair = KeyPairUtils.createKeyPair(2048);
    StringWriter out = new StringWriter();
    KeyPairUtils.writeKeyPair(keyPair, out);
    return out.toString();
  }

  static KeyPair readKeyPair(String pem) throws Exception {
    try (StringReader reader = new StringReader(pem)) {
      return KeyPairUtils.readKeyPair(reader);
    }
  }

  /**
   * PKCS#8 PEM, which is what a Java TLS listener wants to read.
   *
   * goatbot shelled out to `openssl pkcs8 -topk8` for this. A private key's encoded form already is
   * PKCS#8 on this platform, so it is a base64 and a header -- and one fewer thing that has to be
   * installed on the box for the server to work.
   */
  static String toPkcs8(KeyPair keyPair) {
    String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'})
        .encodeToString(keyPair.getPrivate().getEncoded());
    return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
  }
}
