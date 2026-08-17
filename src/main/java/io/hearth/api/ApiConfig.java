package io.hearth.api;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

/**
 * Whether this community answers to a program, and on what terms.
 *
 * <b>On by default, unlike the model endpoint.</b> The two look similar and are not. An MCP
 * connection hands a model the ability to write on the community's behalf under a consent screen
 * somebody has to read; an API token is a person's own credentials in a different shape, doing
 * exactly what that person could already do from the admin screen. There is no new power here, only
 * a different keyboard -- which is why it follows the ordinary rule that everything is on until an
 * operator says otherwise.
 *
 * <b>Two tokens, thirty days.</b> Both are here to be changed and both defaults are opinions. Two
 * is a laptop and a build machine, which is the honest shape of "I have a CLI"; a third is usually
 * a token somebody lost track of, and a list of five nobody can tell apart is a list nobody revokes
 * anything from. Thirty days is short enough that a token pasted into a script somewhere stops
 * working while the person who pasted it still remembers doing so.
 */
public class ApiConfig {
  /** the label every API token carries, so it can be told from a model's connection */
  public static final String AGENT_PREFIX = "api:";
  /** where it answers; a constant rather than a setting, because a CLI has to know it in advance */
  public static final String PATH = "/api";

  public final boolean enabled;
  /** how long a token lives, in days; zero means it does not expire */
  public final int tokenDays;
  /** how many live tokens one person may hold */
  public final int maxTokens;

  public ApiConfig(ConfigObject config) throws ConfigException {
    this.enabled = config.boolOf("enabled", true);
    this.tokenDays = config.intOf("token-days", 30);
    this.maxTokens = config.intOf("max-tokens", 2);
    config.assertKnownKeys();
    if (tokenDays < 0 || tokenDays > 3650) {
      throw new ConfigException("api.token-days must be between 0 and 3650");
    }
    if (maxTokens < 1 || maxTokens > 20) {
      throw new ConfigException("api.max-tokens must be between 1 and 20");
    }
  }

  /** the lifetime a token gets, in seconds; zero means it never expires */
  public long lifetimeSeconds() {
    return tokenDays <= 0 ? 0L : tokenDays * 24L * 60L * 60L;
  }

  public String describe() {
    return enabled
        ? maxTokens + " token(s) each, " + (tokenDays <= 0 ? "no expiry" : tokenDays + " days")
        : "off";
  }
}
