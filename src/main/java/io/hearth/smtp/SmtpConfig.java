package io.hearth.smtp;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

/**
 * The `smtp` block in config.cfg: whether this server also receives email.
 *
 * <b>Off by default, and that is not timidity.</b> Port 25 needs root, an unconfigured listener on
 * it is a machine that will be found by every scanner on the internet within the hour, and a
 * community that has not decided it wants inbound mail should not be running an SMTP server by
 * accident. Turning it on is one line, and the operator who writes that line has thought about it.
 *
 * The limits are all ceilings rather than tunables. There is no correct message size for a
 * community of two hundred people; there is only a number past which somebody is doing something
 * else, and these are chosen so that the memory a hostile connection can hold is bounded by
 * arithmetic rather than by hope.
 */
public class SmtpConfig {
  /** the port the world expects, and the reason this is off unless somebody asks for it */
  public static final int DEFAULT_PORT = 25;

  public final boolean enabled;
  public final int port;
  /** the name this server gives in its banner; a lie here confuses everybody's logs, including ours */
  public final String hostname;
  /** how much of one message we will hold */
  public final int maxMessageBytes;
  /** how many people one message may be for */
  public final int maxRecipients;
  /** how long a connection may sit saying nothing */
  public final int idleSeconds;
  /** how many connections at once, in total */
  public final int maxConnections;
  /** run SPF, DKIM and DMARC on every message */
  public final boolean checkSenders;
  /**
   * Refuse a message whose From domain published DMARC `p=reject` and which failed it.
   *
   * <b>Off by default, and that is a statement about this code rather than about DMARC.</b> The
   * three validators are tested hard against the RFCs with a fake resolver, and have never seen a
   * real Gmail signature or a message that went through a mailing list -- which is exactly where
   * canonicalization bugs live. Enforcing on day one means a bug here silently refuses real mail
   * from the providers most likely to publish `p=reject`, and the operator finds out when somebody
   * says "I emailed you last week".
   *
   * The checks still run and still stamp `Authentication-Results` on every message. Read those on
   * mail you know is genuine for a few weeks, and then turn this on.
   */
  public final boolean enforceDmarc;
  /** how long to wait on one DNS answer */
  public final int dnsTimeoutMillis;

  public static SmtpConfig off() {
    return new SmtpConfig();
  }

  private SmtpConfig() {
    this.enabled = false;
    this.port = DEFAULT_PORT;
    this.hostname = "";
    this.maxMessageBytes = 10 * 1024 * 1024;
    this.maxRecipients = 25;
    this.idleSeconds = 60;
    this.maxConnections = 64;
    this.checkSenders = true;
    this.enforceDmarc = true;
    this.dnsTimeoutMillis = 3000;
  }

  public SmtpConfig(ConfigObject config) throws ConfigException {
    this.enabled = config.boolOf("enabled", false);
    this.port = config.intOf("port", DEFAULT_PORT);
    this.hostname = config.strOf("hostname", "");
    this.maxMessageBytes = atLeast(config, "max-message-bytes", 10 * 1024 * 1024, 1024);
    this.maxRecipients = atLeast(config, "max-recipients", 25, 1);
    this.idleSeconds = atLeast(config, "idle-seconds", 60, 5);
    this.maxConnections = atLeast(config, "max-connections", 64, 1);
    this.checkSenders = config.boolOf("check-senders", true);
    this.enforceDmarc = config.boolOf("enforce-dmarc", false);
    this.dnsTimeoutMillis = atLeast(config, "dns-timeout-millis", 3000, 250);
    config.assertKnownKeys();
    if (port < 1 || port > 65535) {
      throw new ConfigException("smtp.port must be a real port");
    }
  }

  /** what the banner says; falls back to something honest rather than to "localhost" */
  public String hostnameOr(String fallback) {
    if (hostname != null && !hostname.isBlank()) {
      return hostname.trim();
    }
    return fallback == null || fallback.isBlank() ? "hearth" : fallback;
  }

  public String describe() {
    if (!enabled) {
      return "off";
    }
    return "port " + port + ", at most " + (maxMessageBytes / 1024) + "KB per message and "
        + maxRecipients + " recipient(s); "
        + (checkSenders
            ? "spf, dkim and dmarc checked" + (enforceDmarc ? ", p=reject honoured" : ", nothing refused")
            : "no sender checks");
  }

  private static int atLeast(ConfigObject config, String key, int fallback, int floor)
      throws ConfigException {
    int value = config.intOf(key, fallback);
    if (value < floor) {
      throw new ConfigException("smtp." + key + " must be at least " + floor);
    }
    return value;
  }
}
