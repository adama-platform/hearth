package io.hearth.auth;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

import java.util.Locale;

/**
 * Every knob that decides how hard it is to get into a community, read once at boot.
 *
 * The defaults are tuned for a high trust community: a few hundred people who mostly know each
 * other, where the realistic threat is a stranger finding the site, not a targeted attack. That
 * means passwordless, because a password nobody can steal is better than a password everybody
 * reuses, and sessions that don't expire on their own, because logging your neighbours out every
 * week to defend against nothing is how you get them to stop coming.
 *
 * Every one of those defaults tightens with a line of JSON. A business that needs real isolation
 * sets mode to "password", turns on a session lifetime, and caps concurrent sessions; nothing about
 * the code path changes.
 *
 * Read once, at startup, and then immutable. There is no reload and no per-request lookup, so a
 * policy cannot change out from under a request that is halfway through evaluating it.
 */
public class LoginSecurity {
  /** how somebody proves they are who they say they are */
  public enum Mode {
    /** an emailed code and nothing else -- the default, and the one with no password to leak */
    passwordless,
    /** a password, with an emailed code still available for recovery */
    password,
    /** a password plus a second factor */
    password_and_code
  }

  public final Mode mode;
  /** seconds a session may live, counted from creation; 0 means it does not expire on its own */
  public final long sessionLifetimeSeconds;
  /** seconds of inactivity after which a session is dead; 0 means never */
  public final long sessionIdleSeconds;
  /** how many sessions the in-memory cache holds before it starts evicting the coldest */
  public final int cacheMaxSessions;
  /** seconds a cache entry survives without being touched; the disk row outlives it */
  public final long cacheTtlSeconds;
  /** ceiling on concurrent sessions per person; 0 means no ceiling */
  public final int maxActiveSessions;
  /**
   * Sessions younger than this are never reaped for being over the ceiling.
   *
   * This is what makes a ceiling usable. With max 4 and a grace of 30 minutes, a fifth login does
   * not knock somebody out mid-task; it waits until that session has been around long enough to
   * count, and then the oldest goes. "Infinite sessions but only four that stick" is a policy you
   * can state in one sentence, which is the point.
   */
  public final long sessionCapGraceSeconds;
  /** how often the reaper sweeps expired and over-cap sessions out of the database */
  public final long reaperIntervalSeconds;
  /**
   * How long the IP address a sign-up came from is kept.
   *
   * It answers "where did these forty accounts come from" in the days after a burst, and nothing
   * after that. Ninety days is long enough for the question and short enough that this is not a
   * server quietly holding a location for everybody who ever joined. `0` keeps it forever, for an
   * operator who has decided that on purpose.
   */
  public final long signupIpDays;
  /** digits in an emailed code */
  public final int codeLength;
  /** seconds an emailed code is good for */
  public final long codeLifetimeSeconds;
  /** wrong guesses before a code is burned */
  public final int codeMaxAttempts;
  /** failed sign-ins before an account is locked */
  public final int lockoutThreshold;
  /** seconds an account stays locked */
  public final long lockoutSeconds;
  /** how many codes one address may request per window */
  public final int codeRequestsPerHour;
  public final String cookieName;
  /**
   * Secure flag on the session cookie.
   *
   * Off by default because the developer HTTP mode has no TLS, and a Secure cookie over plaintext
   * is a cookie the browser silently drops -- a login that appears to work and doesn't. This turns
   * on with the TLS listener.
   */
  public final boolean cookieSecure;
  public final String cookieSameSite;
  /** shortest password accepted when passwords are in play */
  public final int passwordMinLength;

  public LoginSecurity(ConfigObject config) throws ConfigException {
    String rawMode = config.strOf("mode", Mode.passwordless.name());
    try {
      this.mode = Mode.valueOf(rawMode.toLowerCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new ConfigException("login_security.mode must be one of passwordless, password,"
          + " password_and_code; got '" + rawMode + "'");
    }
    this.sessionLifetimeSeconds = nonNegative(config, "session-lifetime-seconds", 0);
    this.sessionIdleSeconds = nonNegative(config, "session-idle-seconds", 0);
    this.cacheMaxSessions = positive(config, "session-cache-max", 1000);
    this.cacheTtlSeconds = positive(config, "session-cache-ttl-seconds", 3600);
    this.maxActiveSessions = nonNegativeInt(config, "max-active-sessions", 0);
    this.sessionCapGraceSeconds = nonNegative(config, "max-active-sessions-grace-seconds", 1800);
    this.reaperIntervalSeconds = positive(config, "reaper-interval-seconds", 300);
    this.signupIpDays = nonNegative(config, "signup-ip-days", 90);
    this.codeLength = between(config, "code-length", 6, 4, 12);
    this.codeLifetimeSeconds = positive(config, "code-lifetime-seconds", 600);
    this.codeMaxAttempts = positive(config, "code-max-attempts", 5);
    this.lockoutThreshold = nonNegativeInt(config, "lockout-threshold", 10);
    this.lockoutSeconds = nonNegative(config, "lockout-seconds", 900);
    this.codeRequestsPerHour = positive(config, "code-requests-per-hour", 10);
    this.cookieName = config.strOf("cookie-name", "hearth_session");
    this.cookieSecure = config.boolOf("cookie-secure", false);
    this.cookieSameSite = config.strOf("cookie-same-site", "Lax");
    this.passwordMinLength = between(config, "password-min-length", 12, 8, 256);
    config.assertKnownKeys();
    validate();
  }

  private void validate() throws ConfigException {
    if (!cookieName.matches("[A-Za-z0-9_-]{1,64}")) {
      throw new ConfigException("login_security.cookie-name must be 1 to 64 characters of letters,"
          + " digits, underscore or hyphen");
    }
    if (!cookieSameSite.equals("Lax") && !cookieSameSite.equals("Strict") && !cookieSameSite.equals("None")) {
      throw new ConfigException("login_security.cookie-same-site must be Lax, Strict or None");
    }
    if (cookieSameSite.equals("None") && !cookieSecure) {
      throw new ConfigException("login_security.cookie-same-site None requires cookie-secure true;"
          + " browsers reject the combination and the login would silently fail");
    }
  }

  /** does this configuration expect a password at all? */
  public boolean usesPasswords() {
    return mode != Mode.passwordless;
  }

  /** does a successful password still need a second factor? */
  public boolean requiresSecondFactor() {
    return mode == Mode.password_and_code;
  }

  /** a human-readable one-liner for the boot report */
  public String describe() {
    StringBuilder sb = new StringBuilder(mode.name());
    sb.append(", sessions ").append(sessionLifetimeSeconds == 0 ? "never expire" : sessionLifetimeSeconds + "s");
    if (maxActiveSessions > 0) {
      sb.append(", max ").append(maxActiveSessions).append(" older than ").append(sessionCapGraceSeconds).append("s");
    }
    if (sessionIdleSeconds > 0) {
      sb.append(", idle ").append(sessionIdleSeconds).append("s");
    }
    return sb.toString();
  }

  private static long nonNegative(ConfigObject config, String key, int fallback) throws ConfigException {
    int value = config.intOf(key, fallback);
    if (value < 0) {
      throw new ConfigException("login_security." + key + " must be zero or more");
    }
    return value;
  }

  private static int nonNegativeInt(ConfigObject config, String key, int fallback) throws ConfigException {
    return (int) nonNegative(config, key, fallback);
  }

  private static int positive(ConfigObject config, String key, int fallback) throws ConfigException {
    int value = config.intOf(key, fallback);
    if (value <= 0) {
      throw new ConfigException("login_security." + key + " must be greater than zero");
    }
    return value;
  }

  private static int between(ConfigObject config, String key, int fallback, int min, int max) throws ConfigException {
    int value = config.intOf(key, fallback);
    if (value < min || value > max) {
      throw new ConfigException("login_security." + key + " must be between " + min + " and " + max);
    }
    return value;
  }
}
