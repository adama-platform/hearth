package io.hearth.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.web.WebConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * `config.cfg`: the server itself, as opposed to any one community.
 *
 * Everything here has a built-in default that works, so the file is optional and an empty one is
 * valid. That is the point of moving these out of the command line: the command becomes
 * `--root /var/hearth` and stays that way forever, while the things that actually differ between
 * installations live in a file you can read, diff and put in version control.
 *
 * Parsed with the same strict reader as a domain config, so a typo is a refusal at boot rather than
 * a setting that silently did not apply. That matters most for the ports: a server that ignored
 * `htp-port` and listened on 80 anyway would look like it worked.
 */
public class ServerConfig {
  private static final ObjectMapper JSON = new ObjectMapper();

  public final String bind;
  /**
   * Which clock this box keeps.
   *
   * <b>An IANA zone id, and every community here is in one.</b> This is a program for people who
   * meet in a room, so "Tuesday evening" is a fact about a place rather than about UTC -- and the
   * moment a community is not in the same zone as the machine it runs on, every "today", every
   * all-day entry read out of somebody's calendar, and every hour on the availability grid is
   * quietly wrong by a few hours. It defaults to whatever the machine is set to, which is right on
   * a box somebody set up for their own community and wrong on a rented one in another continent.
   */
  public final java.time.ZoneId zone;
  /**
   * Which geocoding service, if any, turns an address into a point.
   *
   * A property of the box rather than of a community: it is one account with one key, and two
   * communities on one machine sharing it is right -- the alternative is asking somebody to open
   * two accounts to run two supper clubs.
   */
  public final io.hearth.places.GpsConfig gps;
  public final int httpPort;
  public final int httpsPort;
  public final boolean httpsEnabled;
  /** the redirect-only listener, or {@link WebConfig#NO_PORT} */
  public final int bouncePort;
  /** whether this server also receives email, and on what terms */
  public final io.hearth.smtp.SmtpConfig smtp;
  /** negotiate HTTP/2 over TLS; HTTP/1.1 remains the fallback for anything that cannot */
  public final boolean http2;
  /** take the template's own whitespace out of every page on the way to the browser */
  public final boolean compactHtml;
  public final int maxRequestBytes;
  public final int idleSeconds;
  /** narrate every decision; the flag still works and wins when it is given */
  public final boolean verbose;

  public static ServerConfig defaults() {
    return new ServerConfig();
  }

  private ServerConfig() {
    this.bind = WebConfig.DEFAULT_BIND;
    this.zone = java.time.ZoneId.systemDefault();
    this.httpPort = WebConfig.DEFAULT_HTTP_PORT;
    this.httpsPort = WebConfig.DEFAULT_HTTPS_PORT;
    this.httpsEnabled = false;
    this.bouncePort = WebConfig.NO_PORT;
    this.smtp = io.hearth.smtp.SmtpConfig.off();
    this.gps = io.hearth.places.GpsConfig.off();
    this.http2 = true;
    this.compactHtml = true;
    this.maxRequestBytes = WebConfig.DEFAULT_MAX_CONTENT_LENGTH;
    this.idleSeconds = WebConfig.DEFAULT_IDLE_READ_SECONDS;
    this.verbose = false;
  }

  /** read the file if it is there; an absent file is every default, which is a normal state */
  public static ServerConfig read(File file) throws ConfigException {
    if (file == null || !file.isFile()) {
      return defaults();
    }
    try {
      var parsed = JSON.readTree(file);
      if (!parsed.isObject()) {
        throw new ConfigException(file + ": the top level of config.cfg must be a JSON object");
      }
      return new ServerConfig(new ConfigObject((ObjectNode) parsed, file.getName()));
    } catch (IOException ex) {
      throw new ConfigException(file + ": could not be read as JSON -- " + ex.getMessage());
    }
  }

  public ServerConfig(ConfigObject config) throws ConfigException {
    this.bind = config.strOf("bind", WebConfig.DEFAULT_BIND);
    this.zone = zoneOf(config.strOf("timezone", java.time.ZoneId.systemDefault().getId()),
        "config.cfg: timezone");
    this.httpPort = port(config, "http-port", WebConfig.DEFAULT_HTTP_PORT);
    this.httpsPort = port(config, "https-port", WebConfig.DEFAULT_HTTPS_PORT);
    this.httpsEnabled = config.boolOf("enable-https", false);
    boolean bounce = config.boolOf("enable-http-bounce", false);
    int bouncePort = port(config, "http-bounce-port", WebConfig.DEFAULT_BOUNCE_PORT);
    this.bouncePort = bounce ? bouncePort : WebConfig.NO_PORT;
    this.smtp = new io.hearth.smtp.SmtpConfig(config.child("smtp"));
    this.gps = new io.hearth.places.GpsConfig(config.child("gps"));
    this.http2 = config.boolOf("http2", true);
    this.compactHtml = config.boolOf("compact-html", true);
    this.maxRequestBytes = positive(config, "max-request-bytes", WebConfig.DEFAULT_MAX_CONTENT_LENGTH);
    this.idleSeconds = positive(config, "idle-seconds", WebConfig.DEFAULT_IDLE_READ_SECONDS);
    this.verbose = config.boolOf("verbose", false);
    config.assertKnownKeys();

    if (httpsEnabled && httpsPort == httpPort) {
      throw new ConfigException("config.cfg: http-port and https-port are both " + httpPort);
    }
    if (this.bouncePort != WebConfig.NO_PORT
        && (this.bouncePort == httpPort || (httpsEnabled && this.bouncePort == httpsPort))) {
      throw new ConfigException("config.cfg: http-bounce-port " + this.bouncePort
          + " is already in use by another listener");
    }
  }

  /** the listener configuration this implies */
  public WebConfig web() {
    return new WebConfig(bind, httpPort, httpsEnabled ? httpsPort : WebConfig.NO_PORT, bouncePort,
        maxRequestBytes, 1, Math.max(2, Runtime.getRuntime().availableProcessors()), idleSeconds,
        http2);
  }

  /** what an empty install should have written for it, with every default spelled out */
  public static String template() {
    ObjectNode node = JSON.createObjectNode();
    node.put("bind", WebConfig.DEFAULT_BIND);
    node.put("http-port", WebConfig.DEFAULT_HTTP_PORT);
    node.put("enable-https", false);
    node.put("https-port", WebConfig.DEFAULT_HTTPS_PORT);
    node.put("enable-http-bounce", false);
    node.put("http-bounce-port", WebConfig.DEFAULT_BOUNCE_PORT);
    node.put("http2", true);
    node.put("max-request-bytes", WebConfig.DEFAULT_MAX_CONTENT_LENGTH);
    node.put("idle-seconds", WebConfig.DEFAULT_IDLE_READ_SECONDS);
    node.put("verbose", false);
    return node.toPrettyString() + "\n";
  }

  /**
   * A zone id, or a refusal that says what one looks like.
   *
   * Fatal at boot like every other config problem: a server running on a zone somebody mistyped
   * would put every event on the wrong evening, and it would look like a bug in the calendar rather
   * than a typo in a file.
   */
  public static java.time.ZoneId zoneOf(String raw, String where) throws ConfigException {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) {
      return java.time.ZoneId.systemDefault();
    }
    try {
      return java.time.ZoneId.of(value);
    } catch (java.time.DateTimeException ex) {
      throw new ConfigException(where + " '" + value + "' is not a time zone. It wants an IANA"
          + " zone id -- Europe/London, America/New_York, Australia/Sydney, or UTC -- rather than an"
          + " abbreviation or an offset.");
    }
  }

  public String describe() {
    StringBuilder said = new StringBuilder("http on " + httpPort);
    if (httpsEnabled) {
      said.append(", https on ").append(httpsPort).append(http2 ? " with http/2" : ", http/1.1 only");
    } else {
      said.append(", no TLS");
    }
    if (bouncePort != WebConfig.NO_PORT) {
      said.append(", bounce on ").append(bouncePort);
    }
    return said.toString();
  }

  private static int port(ConfigObject config, String key, int fallback) throws ConfigException {
    int value = config.intOf(key, fallback);
    if (value < 0 || value > 65535) {
      throw new ConfigException("config.cfg: " + key + " must be between 0 and 65535");
    }
    return value;
  }

  private static int positive(ConfigObject config, String key, int fallback) throws ConfigException {
    int value = config.intOf(key, fallback);
    if (value <= 0) {
      throw new ConfigException("config.cfg: " + key + " must be greater than zero");
    }
    return value;
  }

  /** write a config file, used by the setup walkthrough */
  public static void write(File file, String contents) throws IOException {
    Files.writeString(file.toPath(), contents);
  }
}
