package io.hearth.availability;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

/**
 * When people can actually come, and how hard this server works to find out.
 *
 * <b>The defaults are the interesting part.</b> Somebody who says nothing is assumed to be free on
 * weekday evenings and most of a weekend day -- which is wrong about individuals and right about
 * groups, and is the whole reason the screen is worth looking at on the first day rather than after
 * everybody has filled something in. What it produces is common sense, drawn on a grid, and it is
 * overridden entirely by anybody who cares enough to say otherwise. A tool that only worked once
 * everybody had used it would never be used by anybody.
 *
 * <b>The pull is nocturnal and the histogram is not.</b> Fetching somebody's calendar is a request
 * to another company's server, so it happens once a day, in the small hours, for everybody at once.
 * The grid itself is rebuilt whenever anything changes, because that is cheap and because a person
 * who has just typed their evenings in should see them.
 */
public class AvailabilityConfig {
  /** the hour a person's evening is assumed to start when they have not said */
  public static final int ASSUMED_EVENING_HOUR = 16;
  /** and when it is assumed to end, because nobody is planning a supper club for 3am */
  public static final int ASSUMED_NIGHT_HOUR = 22;
  /** the hour a weekend day is assumed to open up */
  public static final int ASSUMED_WEEKEND_HOUR = 9;

  public final boolean enabled;
  /**
   * The hour the daily pull runs, on a 24-hour clock in the server's own timezone.
   *
   * Midnight by default: everybody's calendar for tomorrow is settled by then, nobody is looking at
   * the screen, and whoever we are fetching from is at their quietest.
   */
  public final int refreshHour;
  /** how far ahead the busy blocks are read; the grid is a fold of this many days */
  public final int horizonDays;
  /** how many calendars one person may attach */
  public final int maxLinks;
  /** how long to wait on somebody else's server before giving up on it for the day */
  public final int fetchTimeoutSeconds;

  public static AvailabilityConfig defaults() {
    return new AvailabilityConfig();
  }

  private AvailabilityConfig() {
    this.enabled = true;
    this.refreshHour = 0;
    this.horizonDays = 28;
    this.maxLinks = 5;
    this.fetchTimeoutSeconds = 10;
  }

  public AvailabilityConfig(ConfigObject config) throws ConfigException {
    this.enabled = config.boolOf("enabled", true);
    this.refreshHour = config.intOf("refresh-hour", 0);
    this.horizonDays = config.intOf("horizon-days", 28);
    this.maxLinks = config.intOf("max-links", 5);
    this.fetchTimeoutSeconds = config.intOf("fetch-timeout-seconds", 10);
    config.assertKnownKeys();
    if (refreshHour < 0 || refreshHour > 23) {
      throw new ConfigException("availability.refresh-hour is an hour of the day, 0 to 23");
    }
    if (horizonDays < 7 || horizonDays > 180) {
      throw new ConfigException("availability.horizon-days must be between 7 and 180");
    }
    if (maxLinks < 1 || maxLinks > 20) {
      throw new ConfigException("availability.max-links must be between 1 and 20");
    }
    if (fetchTimeoutSeconds < 1 || fetchTimeoutSeconds > 120) {
      throw new ConfigException("availability.fetch-timeout-seconds must be between 1 and 120");
    }
  }

  public String describe() {
    return enabled
        ? "calendars pulled at " + String.format("%02d:00", refreshHour) + ", "
            + horizonDays + " days ahead"
        : "off";
  }
}
