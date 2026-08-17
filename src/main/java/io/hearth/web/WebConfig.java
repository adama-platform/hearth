package io.hearth.web;

/**
 * Server knobs, fed from command line arguments.
 *
 * There are up to three listeners, and they exist for three different reasons:
 *
 * - **http** (80) always. It serves the site, and it answers the ACME challenge -- which is why it
 *   cannot simply become a redirect once TLS is on. A domain that has not got a certificate yet
 *   needs this port working in plain HTTP, or it never will.
 * - **https** (443) when `--enable-https`. Certificate per domain, chosen by SNI.
 * - **bounce** (9999) only when `--http-bounce-port` is given. A whole listener that does nothing
 *   but redirect to https, for load balancers that want somewhere to send plain traffic.
 */
public class WebConfig {
  public static final int DEFAULT_HTTP_PORT = 80;
  public static final int DEFAULT_HTTPS_PORT = 443;
  /** the suggested bounce port; high, because it is behind a balancer rather than in front of one */
  public static final int DEFAULT_BOUNCE_PORT = 9999;
  public static final String DEFAULT_BIND = "0.0.0.0";
  /**
   * A listener that is not running.
   *
   * Minus one rather than zero, because zero is a real and useful port number here: it means "bind
   * whatever is free", which is how every test gets an address without fighting over one. Using the
   * same value for "off" and "any" made a test's TLS listener silently not exist.
   */
  public static final int NO_PORT = -1;

  public final String bind;
  /** the plain HTTP listener; always bound */
  public final int port;
  /** the TLS listener, or {@link #NO_PORT} when https is not enabled */
  public final int httpsPort;
  /** the redirect-only listener, or {@link #NO_PORT} when it was not asked for */
  public final int bouncePort;
  /** cap on a single aggregated request; uploads will need their own path, not a bigger number */
  public final int maxContentLengthSize;
  /**
   * The largest body the aggregator will hold, which is the upload ceiling rather than the form
   * one.
   *
   * The two are different numbers on purpose -- a form is a few kilobytes and a photograph off a
   * phone is twelve megabytes -- and {@link UploadGate} is what stops the larger one applying
   * anywhere except the upload path.
   */
  private int uploadCeiling = 32 * 1024 * 1024;
  public final int bossThreads;
  public final int workerThreads;
  /** drop connections that go quiet, so idle sockets don't accumulate */
  public final int idleReadSeconds;

  /** the aggregator's ceiling: never smaller than the ordinary limit */
  public int uploadCeiling() {
    return Math.max(maxContentLengthSize, uploadCeiling);
  }

  /** told once at boot, from the largest thing any community here accepts */
  public void allowUploadsOf(int bytes) {
    this.uploadCeiling = Math.max(this.uploadCeiling, bytes);
  }
  /**
   * Negotiate HTTP/2 on the TLS listener.
   *
   * Only over TLS, because that is the only place browsers do it, and ALPN is how they ask. HTTP/1.1
   * stays the fallback for anything that cannot -- including the plain HTTP listener, which has to
   * keep answering the certificate challenge in the simplest possible way.
   */
  public final boolean http2;

  public static final int DEFAULT_MAX_CONTENT_LENGTH = 1024 * 1024;
  public static final int DEFAULT_IDLE_READ_SECONDS = 60;

  /** plain HTTP on one port and nothing else; what the tests and a dev box use */
  public WebConfig(String bind, int port) {
    this(bind, port, DEFAULT_MAX_CONTENT_LENGTH, 1,
        Math.max(2, Runtime.getRuntime().availableProcessors()), DEFAULT_IDLE_READ_SECONDS);
  }

  /** every knob explicit; the HTTP tests use this to drive real limits instead of faking them */
  public WebConfig(String bind, int port, int maxContentLengthSize, int bossThreads, int workerThreads, int idleReadSeconds) {
    this(bind, port, NO_PORT, NO_PORT, maxContentLengthSize, bossThreads, workerThreads, idleReadSeconds);
  }

  public WebConfig(String bind, int port, int httpsPort, int bouncePort) {
    this(bind, port, httpsPort, bouncePort, DEFAULT_MAX_CONTENT_LENGTH, 1,
        Math.max(2, Runtime.getRuntime().availableProcessors()), DEFAULT_IDLE_READ_SECONDS);
  }

  public WebConfig(String bind, int port, int httpsPort, int bouncePort, int maxContentLengthSize,
                   int bossThreads, int workerThreads, int idleReadSeconds) {
    this(bind, port, httpsPort, bouncePort, maxContentLengthSize, bossThreads, workerThreads,
        idleReadSeconds, true);
  }

  public WebConfig(String bind, int port, int httpsPort, int bouncePort, int maxContentLengthSize,
                   int bossThreads, int workerThreads, int idleReadSeconds, boolean http2) {
    this.bind = bind;
    this.port = port;
    this.httpsPort = httpsPort;
    this.bouncePort = bouncePort;
    this.maxContentLengthSize = maxContentLengthSize;
    this.bossThreads = bossThreads;
    this.workerThreads = workerThreads;
    this.idleReadSeconds = idleReadSeconds;
    this.http2 = http2;
  }

  public boolean httpsEnabled() {
    return httpsPort != NO_PORT;
  }

  public boolean bounceEnabled() {
    return bouncePort != NO_PORT;
  }
}
