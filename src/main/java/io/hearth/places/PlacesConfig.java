package io.hearth.places;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

/**
 * The `places` block: whether a domain keeps an address book.
 *
 * On by default and empty until somebody invents a kind of place, which costs one query and a
 * heading saying nothing is here yet. A community that never uses it never notices.
 */
public class PlacesConfig {
  public final boolean enabled;
  /** what to call it in the navigation; "Places" is right for most and wrong for some */
  public final String label;

  public static PlacesConfig defaults() {
    return new PlacesConfig();
  }

  private PlacesConfig() {
    this.enabled = true;
    this.label = "Places";
  }

  public PlacesConfig(ConfigObject config) throws ConfigException {
    this.enabled = config.boolOf("enabled", true);
    this.label = config.strOf("label", "Places");
    config.assertKnownKeys();
  }

  public String describe() {
    return enabled ? "on, as \"" + label + "\"" : "off";
  }
}
