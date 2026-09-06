package io.hearth.smtp;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

/**
 * The `smtp.forwarding` block: whether this server sends mail on, and with what credentials.
 *
 * <b>Everything here is the operator's rather than the community's</b>, which is invariant 119
 * doing its job. A signing key, a MAC secret and "may this machine make outbound connections on
 * port 25" are not decisions to expose in a browser; which addresses exist and where their mail
 * goes are, and those live in the database and are edited at `/admin/mail`.
 *
 * <b>Off by default, like the listener above it.</b> Turning on inbound mail and turning on
 * forwarding are two decisions: the first says this machine receives, the second says it acts on
 * what it receives by making connections to other people's servers in your name.
 *
 * <b>The two secrets are required and are not generated silently.</b> A default SRS secret would be
 * the same on every installation, which makes the MAC that stops this being an open relay a MAC
 * anybody can compute. The setup walkthrough writes a random one into the file, so the operator has
 * a value they can see, back up, and copy to a second box.
 */
public class ForwardConfig {
  /** where the signing key lives, under the root; the default is what the walkthrough writes */
  public static final String DEFAULT_KEY_FILE = "mail/dkim.key";
  /** the selector that appears in DNS and in every signature */
  public static final String DEFAULT_SELECTOR = "hearth";
  /** shorter than this and the MAC is guessable, which is the whole thing keeping this shut */
  private static final int MIN_SECRET = 24;

  public final boolean enabled;
  /**
   * Refuse to deliver over an unencrypted connection.
   *
   * On by default, which is stricter than the internet's own default and right for what this is
   * for: the destination is a large provider that has supported STARTTLS for a decade, and a
   * silent downgrade means somebody's mail crossing the internet in the clear because a receiver
   * had a bad afternoon. An operator forwarding to something older turns it off deliberately.
   */
  public final boolean requireTls;
  /** the name this server puts in Received and in an authentication result */
  public final String authserv;
  public final String dkimSelector;
  public final String dkimKeyFile;
  private final String srsSecret;

  public static ForwardConfig off() {
    return new ForwardConfig();
  }

  private ForwardConfig() {
    this.enabled = false;
    this.requireTls = true;
    this.authserv = "";
    this.dkimSelector = DEFAULT_SELECTOR;
    this.dkimKeyFile = DEFAULT_KEY_FILE;
    this.srsSecret = "";
  }

  public ForwardConfig(ConfigObject config) throws ConfigException {
    this.enabled = config.boolOf("enabled", false);
    this.requireTls = config.boolOf("require-tls", true);
    this.authserv = config.strOf("authserv-id", "");
    this.dkimSelector = config.strOf("dkim-selector", DEFAULT_SELECTOR);
    this.dkimKeyFile = config.strOf("dkim-key-file", DEFAULT_KEY_FILE);
    this.srsSecret = config.strOf("srs-secret", "");
    config.assertKnownKeys();
    if (!enabled) {
      return;
    }
    // Fatal at boot, because the alternative is a server that starts, accepts mail, and rewrites
    // return paths with a MAC anybody can forge -- which is an open relay with extra steps and
    // would be discovered by somebody else rather than by the operator.
    if (srsSecret.length() < MIN_SECRET) {
      throw new ConfigException("smtp.forwarding.srs-secret must be at least " + MIN_SECRET
          + " characters; run --setup-mail to have one generated");
    }
    if (dkimSelector.isBlank() || !dkimSelector.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?")) {
      throw new ConfigException("smtp.forwarding.dkim-selector is a DNS label:"
          + " lowercase letters, numbers and hyphens");
    }
    if (dkimKeyFile.isBlank() || dkimKeyFile.startsWith("/") || dkimKeyFile.contains("..")) {
      throw new ConfigException("smtp.forwarding.dkim-key-file is a path under the root,"
          + " like " + DEFAULT_KEY_FILE);
    }
  }

  /**
   * The MAC key behind every SRS address.
   *
   * A method rather than a public field, so this reads as a credential at every call site. It is
   * never printed, never exported and never reaches a template.
   */
  public String srsSecret() {
    return srsSecret;
  }

  /** the name in Received and in Authentication-Results; the banner when nothing else was said */
  public String authserv() {
    return authserv == null || authserv.isBlank() ? "hearth" : authserv.trim();
  }

  /** what a boot line and the admin screen say about this */
  public String describe() {
    if (!enabled) {
      return "off";
    }
    return "on, signing as " + dkimSelector + ", "
        + (requireTls ? "TLS required" : "TLS optional");
  }
}
