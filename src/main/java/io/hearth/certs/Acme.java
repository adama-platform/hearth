package io.hearth.certs;

/**
 * Talking to a certificate authority.
 *
 * An interface for the same reason {@link io.hearth.store.Database} is one: everything interesting
 * about certificate management -- when to renew, what to do when issuance fails, how to not order
 * the same thing twice -- is logic that has nothing to do with ACME, and testing it against a real
 * CA is not possible. {@link AcmeIssuer} is the implementation; the tests use a fake that mints a
 * self-signed certificate, and exercise the manager for real.
 *
 * It is also the honest shape. There is one ACME implementation here and there may only ever be
 * one, but the seam is what makes the rest of this package something you can be confident in without
 * a network.
 */
public interface Acme {
  String PRODUCTION = "acme://letsencrypt.org";
  String STAGING = "acme://letsencrypt.org/staging";

  /**
   * Register a new account with the CA.
   *
   * Returns the account URL, which along with the key is everything needed to log back in later.
   * Agreeing to the terms of service is a decision a person makes, which is why it is a parameter
   * rather than something this hardcodes to true.
   */
  String registerAccount(String directory, String accountKeyPem, String contactEmail,
                         boolean agreeToTerms) throws Exception;

  /** the terms of service a person is being asked to agree to, so the walkthrough can print it */
  String termsOfService(String directory) throws Exception;

  /**
   * Order a certificate for one domain and see the challenge through.
   *
   * The publisher is how the HTTP-01 answer reaches the running web server: this hands it a token
   * and the string to answer with, and takes it back when the order is finished either way.
   */
  Issued issue(Account account, String domain, String domainKeyPem, Publisher publisher) throws Exception;

  /** what the CA needs to log back into an existing account */
  record Account(String directory, String accountKeyPem, String accountUrl) {
  }

  /** a certificate, as the CA handed it over */
  record Issued(String chainPem, String pkcs8Key) {
  }

  /** where an HTTP-01 answer goes while the CA is checking */
  interface Publisher {
    void publish(String token, String keyAuthorization);

    void withdraw(String token);
  }
}
