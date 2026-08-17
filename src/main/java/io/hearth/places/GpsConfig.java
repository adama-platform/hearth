package io.hearth.places;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;
import io.hearth.common.Verbose;

/**
 * The `gps` block: which geocoding service turns an address into a point.
 *
 * <b>On by default, on Nominatim, which is a reversal.</b> This used to be the one thing in this
 * server that was off until an operator asked for it, on two arguments: it sends an address to
 * somebody else's company, and it is the only feature here that can cost money. The second argument
 * does not apply to the default -- Nominatim is OpenStreetMap's own service, free, with no account,
 * no key and no card. The first one is real, and the answer to it is that the addresses are now the
 * thing the feature is for: a member says where they are so that whoever plans an event can see how
 * far people would have to come. A community that had to find a config key first would never get
 * that, and every one of them would plan around the loudest three members instead.
 *
 * What has not changed is that the terms are enforced rather than hoped for. What has changed is
 * where: the identifying User-Agent is now unconditional, and `gps.contact` -- which makes that
 * identification useful rather than merely present -- is asked for loudly instead of fatally,
 * because a default that refuses to boot is not a default.
 *
 * <b>An operator who writes the block still gets the strict check.</b> Naming nominatim in a config
 * file with no contact address is a mistake somebody made on purpose, and it is fatal exactly as it
 * always was. The difference is only between a decision and a default.
 *
 * `--setup-gps` remains the walkthrough, and it exists because the interesting part is not the key:
 * it is that most geocoding services forbid keeping the answer, and keeping the answer is the whole
 * point of writing a coordinate onto a row.
 */
public class GpsConfig {
  /**
   * What is sent when nobody has said how to reach them.
   *
   * Nominatim's policy asks for a User-Agent that identifies the application, and a way to get in
   * touch. This is the first half honoured unconditionally, so the default install is a client that
   * says what it is rather than an anonymous one -- and every screen that can nag for the second
   * half does.
   */
  public static final String UNIDENTIFIED = "https://github.com/adama-platform/hearth";

  public final boolean enabled;
  public final WebGeocoder.Service service;
  public final String key;
  /**
   * How to reach whoever runs this server.
   *
   * Nominatim's acceptable use policy requires a User-Agent that identifies the application *and*
   * gives a way to get in touch, and a client that does not provide one can be blocked without
   * warning. It is sent to the others too: a server that identifies itself everywhere is easier to
   * keep on the right side of than one that remembers which service cares.
   */
  public final String contact;
  /** did somebody write a gps block, or is this what the software chose? */
  public final boolean configured;

  public static GpsConfig off() {
    return new GpsConfig();
  }

  private GpsConfig() {
    this.enabled = false;
    this.service = null;
    this.key = "";
    this.contact = "";
    this.configured = false;
  }

  public GpsConfig(ConfigObject config) throws ConfigException {
    this.configured = !config.node.isEmpty();
    this.enabled = config.boolOf("enabled", true);
    String name = config.strOf("service", "nominatim");
    this.service = WebGeocoder.Service.of(name);
    this.key = config.strOf("key", "");
    this.contact = config.strOf("contact", "");
    config.assertKnownKeys();
    if (!enabled) {
      return;
    }
    if (service == null) {
      throw new ConfigException("gps.service is '" + name + "', which is not a service this"
          + " server knows. It knows nominatim, opencage and geoapify -- run --setup-gps.");
    }
    if (service.needsKey() && key.isBlank()) {
      throw new ConfigException("gps.service is " + service + ", which needs gps.key");
    }
    if (configured && service == WebGeocoder.Service.nominatim && contact.isBlank()) {
      // Still fatal for somebody who wrote the block: naming a service and not saying who you are
      // is a decision, and the policy's answer to it is a block days later that looks like
      // geocoding quietly stopping. Not fatal when nobody wrote anything, because refusing to boot
      // an install that has never mentioned gps would be a default that breaks every upgrade.
      throw new ConfigException("gps.contact is required for nominatim: their acceptable use"
          + " policy asks for a way to reach whoever runs this server, and a client that does not"
          + " give one can be blocked without warning. An email address is enough.");
    }
  }

  /** is this the on-by-default case that has never been told who to say it is? */
  public boolean wantsContact() {
    return enabled && service == WebGeocoder.Service.nominatim && contact.isBlank();
  }

  /** what to identify as: the contact when there is one, and the project when there is not */
  public String identity() {
    return contact == null || contact.isBlank() ? UNIDENTIFIED : contact;
  }

  /** the geocoder this describes, or the one that does nothing */
  public Geocoder build(Verbose verbose) {
    if (!enabled || service == null) {
      return Geocoder.NONE;
    }
    return new WebGeocoder(service, key, identity(), verbose);
  }

  public String describe() {
    if (!enabled) {
      return "off; addresses are stored as typed";
    }
    return service + (service.needsKey() ? ", key configured" : ", no key needed")
        + (wantsContact() ? " -- set gps.contact so they can reach you" : "");
  }
}
