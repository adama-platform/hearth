package io.hearth.mail;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

import java.util.Locale;

/**
 * The `ses` block in a domain's config: how this community sends real email.
 *
 * Per domain rather than per server, because the sending address has to belong to the community
 * whose name is on the message -- a code from "Example Community" arriving from
 * `no-reply@some-other-place` is the shape a phishing mail has, and a member who has been told to
 * be careful will treat it as one.
 *
 * **The credentials are in this file, in the clear.** There is nowhere else for them to be in a
 * single-jar server with no secret store, so the honest thing is to say so and make the file
 * matter: `--setup-email` writes it 0600, and the boot report names the domains that have keys in
 * them. An IAM user for this should be able to do exactly one thing, `ses:SendEmail`, which turns a
 * leaked key from a disaster into a nuisance.
 */
public class SesConfig {
  public final boolean enabled;
  public final String accessKeyId;
  public final String secretAccessKey;
  public final String region;
  /** the verified address SES sends from */
  public final String from;
  /** what to show as the sender's name; the community's name by default */
  public final String fromName;
  /** where replies go; the from address unless somebody wants them elsewhere */
  public final String replyTo;

  public static SesConfig off() {
    return new SesConfig();
  }

  private SesConfig() {
    this.enabled = false;
    this.accessKeyId = null;
    this.secretAccessKey = null;
    this.region = "us-east-1";
    this.from = null;
    this.fromName = null;
    this.replyTo = null;
  }

  public SesConfig(ConfigObject config) throws ConfigException {
    this.enabled = config.boolOf("enabled", false);
    this.accessKeyId = config.strOf("access-key-id", null);
    this.secretAccessKey = config.strOf("secret-access-key", null);
    this.region = config.strOf("region", "us-east-1");
    this.from = config.strOf("from", null);
    this.fromName = config.strOf("from-name", null);
    this.replyTo = config.strOf("reply-to", null);
    config.assertKnownKeys();

    if (!enabled) {
      return;
    }
    // Everything a send needs, checked at boot rather than at the moment somebody is waiting for a
    // sign-in code. A missing key here is a config error; discovered later it is a person locked
    // out of a community with no idea why.
    require(accessKeyId, "access-key-id");
    require(secretAccessKey, "secret-access-key");
    require(from, "from");
    if (from.indexOf('@') <= 0) {
      throw new ConfigException("ses.from must be an email address SES has verified for you");
    }
    if (!region.matches("[a-z]{2}(-[a-z]+)+-[0-9]")) {
      throw new ConfigException("ses.region '" + region + "' does not look like an AWS region");
    }
  }

  private static void require(String value, String key) throws ConfigException {
    if (value == null || value.isBlank()) {
      throw new ConfigException("ses is enabled, so ses." + key + " is required");
    }
  }

  public String host() {
    return "email." + region.toLowerCase(Locale.ROOT) + ".amazonaws.com";
  }

  public String replyToOr() {
    return replyTo == null || replyTo.isBlank() ? from : replyTo;
  }

  public String describe() {
    return enabled ? "SES from " + from + " in " + region : "terminal (no provider configured)";
  }
}
