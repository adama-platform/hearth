package io.hearth.mcp;

import java.util.List;
import java.util.Locale;

/**
 * The model vendors this server knows how to be connected to.
 *
 * A vendor is nothing but a name and the redirect URIs its connector is allowed to come back to.
 * That list is the whole security of the authorization step: an authorization code handed to the
 * wrong redirect is an agent token handed to whoever owns that host, and no amount of PKCE helps if
 * the code goes to the attacker in the first place. So redirects are matched against an explicit
 * prefix list rather than a pattern, and there is no wildcard that can be widened by accident.
 *
 * Grok is the one this was built against. The others are here because the shape generalizes -- a
 * vendor is a row in a table, not a code path -- and an operator can add any of them with a line of
 * config. Nothing about the flow is vendor-specific; if a connector does OAuth 2.1 with PKCE and
 * dynamic registration, it works.
 *
 * **The prefixes below are a starting point, not gospel.** A connector's callback URL is the
 * vendor's to change, and when it does, the operator adds the new one to
 * `mcp.extra_redirect_prefixes` rather than waiting for a release. `just check` prints what is
 * allowed so a mismatch is a boot-time answer instead of a mystery at authorize time.
 */
public enum Vendor {
  grok("Grok", "x.ai", List.of(
      "https://grok.com/",
      "https://www.grok.com/",
      "https://x.ai/",
      "https://api.x.ai/")),
  claude("Claude", "Anthropic", List.of(
      "https://claude.ai/",
      "https://claude.com/")),
  chatgpt("ChatGPT", "OpenAI", List.of(
      "https://chatgpt.com/",
      "https://platform.openai.com/")),
  /** anything an operator adds by hand, governed entirely by the configured prefixes */
  custom("Custom", "operator configured", List.of());

  public final String label;
  public final String who;
  private final List<String> prefixes;

  Vendor(String label, String who, List<String> prefixes) {
    this.label = label;
    this.who = who;
    this.prefixes = prefixes;
  }

  public List<String> redirectPrefixes() {
    return prefixes;
  }

  public static Vendor of(String raw) {
    if (raw == null) {
      return custom;
    }
    try {
      return valueOf(raw.trim().toLowerCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return custom;
    }
  }

  /** the vendor a redirect belongs to, or null when nothing claims it */
  public static Vendor claiming(String redirectUri) {
    if (redirectUri == null) {
      return null;
    }
    for (Vendor vendor : values()) {
      for (String prefix : vendor.prefixes) {
        if (redirectUri.startsWith(prefix)) {
          return vendor;
        }
      }
    }
    return null;
  }
}
