package io.hearth.certs;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A certificate authority that is not one.
 *
 * Everything worth testing about certificate management is a failure case -- a domain that will not
 * validate, an account that is not there, a certificate that is nearly expired -- and none of them
 * can be produced on demand by pointing at Let's Encrypt. So the real authority sits behind
 * {@link Acme} and this stands in, minting a self-signed certificate with whatever expiry the test
 * asks for.
 *
 * It does exercise the challenge handshake for real: it calls the publisher, checks that the running
 * web server actually answers on the token, and refuses if it does not. That is the part of the flow
 * this codebase owns, so it is the part worth simulating faithfully.
 */
public class FakeAuthority implements Acme {
  private final AtomicInteger issued = new AtomicInteger();
  private final List<String> ordered = new ArrayList<>();
  private final List<String> tokensSeen = new ArrayList<>();

  /** how long the certificates it mints are good for; the point of the whole fake */
  private long validForMillis = 90L * 86_400_000L;
  private String failWith;
  private java.util.function.Function<String, String> challengeCheck;

  public FakeAuthority validFor(long millis) {
    this.validForMillis = millis;
    return this;
  }

  /** make the next issuance fail, the way an unreachable domain does */
  public FakeAuthority failing(String message) {
    this.failWith = message;
    return this;
  }

  /**
   * Check the challenge answer the way the real authority would: by fetching it.
   *
   * The function is handed the token and returns whatever the server served, or null.
   */
  public FakeAuthority verifyingWith(java.util.function.Function<String, String> check) {
    this.challengeCheck = check;
    return this;
  }

  public int issuedCount() {
    return issued.get();
  }

  public List<String> ordered() {
    return ordered;
  }

  public List<String> tokensSeen() {
    return tokensSeen;
  }

  @Override
  public String termsOfService(String directory) {
    return "https://example.org/terms";
  }

  @Override
  public String registerAccount(String directory, String accountKeyPem, String contactEmail,
                                boolean agreeToTerms) {
    if (!agreeToTerms) {
      throw new IllegalStateException("terms not agreed");
    }
    return directory + "/acct/" + Math.abs(contactEmail.hashCode());
  }

  @Override
  public Issued issue(Account account, String domain, String domainKeyPem, Publisher publisher)
      throws Exception {
    ordered.add(domain);
    if (domain.startsWith("*")) {
      throw new IllegalArgumentException("a wildcard certificate needs DNS-01, which this does not do");
    }
    String token = "tok-" + domain.replace('.', '-') + "-" + issued.get();
    String keyAuthorization = token + ".fake-thumbprint";
    tokensSeen.add(token);
    publisher.publish(token, keyAuthorization);
    try {
      if (challengeCheck != null) {
        String served = challengeCheck.apply(token);
        if (!keyAuthorization.equals(served)) {
          throw new IllegalStateException("the CA could not reach " + domain
              + " to verify it; served '" + served + "'");
        }
      }
      if (failWith != null) {
        String message = failWith;
        failWith = null;
        throw new IllegalStateException(message);
      }
      issued.incrementAndGet();
      // a real matched pair, so a TLS test can actually complete a handshake against it
      Pair pair = selfSignedPair(domain, validForMillis);
      return new Issued(pair.chainPem(), pair.keyPem());
    } finally {
      publisher.withdraw(token);
    }
  }

  /** a certificate and the key that goes with it */
  public record Pair(String chainPem, String keyPem) {
  }

  /** just the certificate, for the tests that only care about expiry */
  public static String selfSigned(String domain, long validForMillis) throws Exception {
    return selfSignedPair(domain, validForMillis).chainPem();
  }

  /**
   * A real X.509 certificate, self signed, with a chosen expiry, and its private key.
   *
   * Real rather than a stub string for two reasons: {@link CertStore} parses what it stores to
   * decide about renewal, and the TLS tests present it to a real client. A fake string would prove
   * nothing about either.
   */
  public static Pair selfSignedPair(String domain, long validForMillis) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048, new SecureRandom());
    KeyPair keyPair = generator.generateKeyPair();

    long now = System.currentTimeMillis();
    org.bouncycastle.cert.X509v3CertificateBuilder builder =
        new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            new javax.security.auth.x500.X500Principal("CN=" + domain),
            BigInteger.valueOf(now),
            new Date(now - 60_000L),
            new Date(now + validForMillis),
            new javax.security.auth.x500.X500Principal("CN=" + domain),
            keyPair.getPublic());
    org.bouncycastle.operator.ContentSigner signer =
        new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
            .build(keyPair.getPrivate());
    X509Certificate certificate = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
        .getCertificate(builder.build(signer));

    String certBase64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certificate.getEncoded());
    String keyBase64 = Base64.getMimeEncoder(64, new byte[]{'\n'})
        .encodeToString(keyPair.getPrivate().getEncoded());
    return new Pair(
        "-----BEGIN CERTIFICATE-----\n" + certBase64 + "\n-----END CERTIFICATE-----\n",
        "-----BEGIN PRIVATE KEY-----\n" + keyBase64 + "\n-----END PRIVATE KEY-----\n");
  }

  /** parse back what selfSigned produced, for tests that assert on the expiry */
  public static X509Certificate parse(String pem) throws Exception {
    return (X509Certificate) java.security.cert.CertificateFactory.getInstance("X509")
        .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
  }
}
