package io.hearth.board;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

/**
 * The `board` block: whether a domain has a discussion board, and how long a thread lives.
 *
 * Expiry is the only setting with an opinion in it. The default is that posts age out, because a
 * board that keeps everything becomes an archive nobody reads -- but a community that wants a
 * permanent record sets `expiry-days` to 0 and gets one.
 */
public class BoardConfig {
  public final boolean enabled;
  /** how many days a new post lives; 0 means forever */
  public final int expiryDays;
  /** how long a notification about a thread outlives being written */
  public final int notificationDays;

  public static BoardConfig defaults() {
    return new BoardConfig();
  }

  private BoardConfig() {
    this.enabled = true;
    this.expiryDays = 60;
    this.notificationDays = 30;
  }

  public BoardConfig(ConfigObject config) throws ConfigException {
    this.enabled = config.boolOf("enabled", true);
    this.expiryDays = nonNegative(config, "expiry-days", 60);
    this.notificationDays = nonNegative(config, "notification-days", 30);
    config.assertKnownKeys();
  }

  public String describe() {
    if (!enabled) {
      return "off";
    }
    return expiryDays == 0 ? "posts kept forever" : "posts expire after " + expiryDays + " days";
  }

  private static int nonNegative(ConfigObject config, String key, int fallback)
      throws ConfigException {
    int value = config.intOf(key, fallback);
    if (value < 0) {
      throw new ConfigException("board." + key + " must be zero or more");
    }
    return value;
  }
}
