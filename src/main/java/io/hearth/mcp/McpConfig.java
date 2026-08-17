package io.hearth.mcp;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Whether this domain talks to models at all, and on what terms.
 *
 * **Off by default.** Every other default in this server is tuned for a high trust community; this
 * one is not, because the thing being handed out is the ability to rewrite the site. An operator
 * turning it on is making a decision, and it should look like one.
 *
 * The knobs that matter are the two that bound the blast radius: which vendors may connect, and
 * where their authorization codes are allowed to land. Everything else is convenience.
 */
public class McpConfig {
  /** whether this domain answers on the MCP path at all */
  public final boolean enabled;
  /** where the endpoint lives; simple by default, overridable because a domain may have plans */
  public final String path;
  /** vendors allowed to register and connect */
  public final List<Vendor> vendors;
  /** redirect prefixes beyond what the vendor profiles ship, for when a connector moves */
  public final List<String> extraRedirectPrefixes;
  /** may a connector register itself, or must an admin add it first? */
  public final boolean dynamicRegistration;
  /** how long an agent token is good for; 0 follows the domain's session policy */
  public final int tokenLifetimeSeconds;
  /** may agents write, or only read? */
  public final boolean readOnly;
  /** how long an authorization code is good for; short, because it only has to survive a redirect */
  public final int codeLifetimeSeconds;

  public static McpConfig disabled() {
    return new McpConfig();
  }

  private McpConfig() {
    this.enabled = false;
    this.path = "/mcp";
    this.vendors = List.of();
    this.extraRedirectPrefixes = List.of();
    this.dynamicRegistration = false;
    this.tokenLifetimeSeconds = 0;
    this.readOnly = false;
    this.codeLifetimeSeconds = 120;
  }

  public McpConfig(ConfigObject config) throws ConfigException {
    this.enabled = config.boolOf("enabled", false);
    this.path = path(config.strOf("path", "/mcp"));
    String[] rawVendors = config.stringsOf("vendors", new String[]{"grok"});
    ArrayList<Vendor> allowed = new ArrayList<>();
    for (String raw : rawVendors) {
      Vendor vendor = Vendor.of(raw);
      if (vendor == Vendor.custom && !"custom".equalsIgnoreCase(raw.trim())) {
        throw new ConfigException("mcp.vendors has '" + raw + "', which is not a vendor this server"
            + " knows; use one of grok, claude, chatgpt, custom");
      }
      if (!allowed.contains(vendor)) {
        allowed.add(vendor);
      }
    }
    this.vendors = List.copyOf(allowed);

    ArrayList<String> extra = new ArrayList<>();
    for (String prefix : config.stringsOf("extra-redirect-prefixes", new String[0])) {
      String trimmed = prefix.trim();
      if (!trimmed.startsWith("https://")) {
        // http would put an authorization code on the wire in clear text, and localhost is not a
        // thing a hosted connector redirects to
        throw new ConfigException("mcp.extra-redirect-prefixes must all start with 'https://';"
            + " got '" + prefix + "'");
      }
      extra.add(trimmed);
    }
    this.extraRedirectPrefixes = List.copyOf(extra);

    this.dynamicRegistration = config.boolOf("dynamic-registration", true);
    this.tokenLifetimeSeconds = nonNegative(config, "token-lifetime-seconds", 0);
    this.readOnly = config.boolOf("read-only", false);
    this.codeLifetimeSeconds = positive(config, "code-lifetime-seconds", 120);
    config.assertKnownKeys();

    if (enabled && vendors.isEmpty() && extraRedirectPrefixes.isEmpty()) {
      throw new ConfigException("mcp is enabled but no vendor and no redirect prefix is allowed,"
          + " so nothing could ever connect; list a vendor or a prefix");
    }
  }

  /** every redirect prefix this domain will accept, vendor profiles plus operator additions */
  public List<String> allowedRedirectPrefixes() {
    LinkedHashSet<String> all = new LinkedHashSet<>();
    for (Vendor vendor : vendors) {
      all.addAll(vendor.redirectPrefixes());
    }
    all.addAll(extraRedirectPrefixes);
    return List.copyOf(all);
  }

  /**
   * Is this redirect one we are willing to send a code to?
   *
   * Prefix match against an explicit list, and nothing else. No wildcards, no host-suffix matching,
   * no "well it's the same registrable domain" -- every one of those has been the subject of a real
   * OAuth advisory, and the list is short enough that exactness costs nothing.
   */
  public boolean allowsRedirect(String redirectUri) {
    if (redirectUri == null || !redirectUri.startsWith("https://") || redirectUri.contains("..")) {
      return false;
    }
    for (String prefix : allowedRedirectPrefixes()) {
      if (redirectUri.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  public boolean allows(Vendor vendor) {
    return vendors.contains(vendor);
  }

  /** the one-line summary the boot report prints */
  public String describe() {
    if (!enabled) {
      return "off";
    }
    ArrayList<String> names = new ArrayList<>();
    for (Vendor vendor : vendors) {
      names.add(vendor.name());
    }
    return path + ", " + (names.isEmpty() ? "no vendors" : String.join("+", names))
        + (readOnly ? ", read only" : ", read and write")
        + (dynamicRegistration ? ", self registration" : ", admin registration only");
  }

  private static String path(String value) throws ConfigException {
    if (value.isEmpty() || value.charAt(0) != '/' || value.contains("//") || value.contains("..")
        || value.contains("?") || value.contains("#") || (value.length() > 1 && value.endsWith("/"))) {
      throw new ConfigException("mcp.path must be a plain absolute path like '/mcp'");
    }
    for (int k = 0; k < value.length(); k++) {
      char ch = value.charAt(k);
      boolean allowed = ch == '/' || ch == '-' || ch == '_'
          || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
      if (!allowed) {
        throw new ConfigException("mcp.path may only contain lowercase letters, digits, '/', '-' and '_'");
      }
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private static int nonNegative(ConfigObject config, String key, int fallback) throws ConfigException {
    int value = config.intOf(key, fallback);
    if (value < 0) {
      throw new ConfigException("mcp." + key + " must be zero or more");
    }
    return value;
  }

  private static int positive(ConfigObject config, String key, int fallback) throws ConfigException {
    int value = config.intOf(key, fallback);
    if (value <= 0) {
      throw new ConfigException("mcp." + key + " must be greater than zero");
    }
    return value;
  }
}
