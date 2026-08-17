package io.hearth.places;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Reading three services' answers, and refusing to start when the terms have not been met.
 *
 * The parsing is unit-tested against captured shapes rather than the network, because the thing most
 * likely to be wrong is not "can we reach them" -- it is that GeoJSON puts longitude first and every
 * other service puts latitude first, which is silent, plausible, and puts every event in the wrong
 * hemisphere.
 */
public class GeocodingTests {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static Geocoder.Point read(WebGeocoder.Service service, String body) throws Exception {
    return WebGeocoder.read(service, JSON.readTree(body));
  }

  @Test
  public void nominatimPutsTheNumbersWhereItPutsThem() throws Exception {
    Geocoder.Point point = read(WebGeocoder.Service.nominatim,
        "[{\"lat\":\"51.1465\",\"lon\":\"0.8735\",\"display_name\":\"The Oak, Ashford\"}]");
    assertEquals(51.1465, point.latitude(), 0.0001);
    assertEquals(0.8735, point.longitude(), 0.0001);
    assertEquals("The Oak, Ashford", point.label());
    assertNull("nothing found is nothing, not an exception",
        read(WebGeocoder.Service.nominatim, "[]"));
  }

  @Test
  public void opencageNestsThemUnderGeometry() throws Exception {
    Geocoder.Point point = read(WebGeocoder.Service.opencage,
        "{\"results\":[{\"geometry\":{\"lat\":51.1465,\"lng\":0.8735},"
            + "\"formatted\":\"The Oak, Ashford, UK\"}]}");
    assertEquals(51.1465, point.latitude(), 0.0001);
    assertEquals(0.8735, point.longitude(), 0.0001);
    assertNull(read(WebGeocoder.Service.opencage, "{\"results\":[]}"));
  }

  @Test
  public void geoapifyIsGeoJsonAndThereforeLongitudeFirst() throws Exception {
    // the single easiest thing in this whole feature to get backwards, and it fails silently:
    // 0.87 north and 51 east is in Somalia
    Geocoder.Point point = read(WebGeocoder.Service.geoapify,
        "{\"features\":[{\"geometry\":{\"coordinates\":[0.8735,51.1465]},"
            + "\"properties\":{\"formatted\":\"The Oak\"}}]}");
    assertEquals("latitude is the second number in GeoJSON", 51.1465, point.latitude(), 0.0001);
    assertEquals(0.8735, point.longitude(), 0.0001);
    assertNull(read(WebGeocoder.Service.geoapify, "{\"features\":[]}"));
  }

  @Test
  public void anAnswerThatIsNotOnEarthIsNotAnAnswer() throws Exception {
    assertNull(read(WebGeocoder.Service.nominatim, "[{\"lat\":\"991\",\"lon\":\"0\"}]"));
    assertNull(read(WebGeocoder.Service.nominatim, "[{\"lat\":\"51\",\"lon\":\"999\"}]"));
    assertNull(read(WebGeocoder.Service.nominatim, "[{\"lat\":\"not a number\",\"lon\":\"0\"}]"));
    assertNull(read(WebGeocoder.Service.nominatim, "[{}]"));
  }

  // ---- the terms, enforced at boot -------------------------------------------------------------

  private static GpsConfig config(String json) throws Exception {
    return new GpsConfig(new ConfigObject(
        (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(json), "gps"));
  }

  @Test
  public void onIsTheDefaultAndNeedsNothingWrittenDown() throws Exception {
    // A reversal, and a considered one. This was the single thing here that was off until asked
    // for, on two arguments: it sends an address to another company, and it can cost money. The
    // default service costs nothing and has no account -- and the addresses are now the point of
    // the feature, so a community that had to find a config key first would never have it.
    GpsConfig fresh = config("{}");
    assertTrue(fresh.enabled);
    assertEquals(WebGeocoder.Service.nominatim, fresh.service);
    assertFalse("nobody wrote a gps block", fresh.configured);
    assertNotEquals(Geocoder.NONE, fresh.build(io.hearth.common.Verbose.OFF));

    // and off is still one word
    assertTrue(config("{\"enabled\":false}").describe().contains("off"));
    assertEquals(Geocoder.NONE,
        config("{\"enabled\":false}").build(io.hearth.common.Verbose.OFF));
  }

  @Test
  public void anUnidentifiedClientStillSaysWhatItIs() throws Exception {
    // Nominatim's policy wants a User-Agent naming the application *and* a way to get in touch.
    // The default install can honour the first half unconditionally, and every screen that can
    // asks for the second.
    GpsConfig fresh = config("{}");
    assertTrue(fresh.wantsContact());
    assertEquals(GpsConfig.UNIDENTIFIED, fresh.identity());
    assertTrue(fresh.describe().contains("gps.contact"));

    GpsConfig told = config("{\"contact\":\"boss@example.org\"}");
    assertFalse(told.wantsContact());
    assertEquals("boss@example.org", told.identity());
  }

  @Test
  public void nominatimWrittenDownWithoutAContactAddressRefusesToStart() {
    // Still fatal for somebody who wrote the block. Naming a service and not saying who you are is
    // a decision, and their answer to it is a block days later that looks exactly like geocoding
    // quietly stopping. It is only the *default* that is allowed to proceed unidentified, because
    // a default that refuses to boot is not a default.
    ConfigException thrown = null;
    try {
      config("{\"enabled\":true,\"service\":\"nominatim\"}");
    } catch (Exception ex) {
      thrown = (ConfigException) ex;
    }
    assertTrue(String.valueOf(thrown), thrown != null
        && thrown.getMessage().contains("gps.contact is required"));
  }

  @Test
  public void aServiceThatNeedsAKeyRefusesWithoutOne() {
    ConfigException thrown = null;
    try {
      config("{\"enabled\":true,\"service\":\"opencage\",\"contact\":\"a@b.org\"}");
    } catch (Exception ex) {
      thrown = (ConfigException) ex;
    }
    assertTrue(String.valueOf(thrown), thrown != null
        && thrown.getMessage().contains("needs gps.key"));
  }

  @Test
  public void aServiceThisServerHasNeverHeardOfIsFatalAndSaysWhatItKnows() {
    ConfigException thrown = null;
    try {
      config("{\"enabled\":true,\"service\":\"google\",\"key\":\"x\",\"contact\":\"a@b.org\"}");
    } catch (Exception ex) {
      thrown = (ConfigException) ex;
    }
    assertTrue(String.valueOf(thrown), thrown != null
        && thrown.getMessage().contains("nominatim, opencage and geoapify"));
  }

  @Test
  public void everyServiceSaysWhatItAsksOfYouAndWhereToGetIt() {
    // the walkthrough prints these, and a service added without them would print a blank line
    // where the terms should be -- which is the one thing that screen exists to say
    for (WebGeocoder.Service service : WebGeocoder.Service.values()) {
      assertTrue(service + " has no terms", service.terms().length() > 40);
      assertTrue(service + " has nowhere to go", service.where().startsWith("https://"));
    }
    assertTrue("nominatim is the one with no key", !WebGeocoder.Service.nominatim.needsKey());
    assertTrue(WebGeocoder.Service.opencage.needsKey());
    assertTrue(WebGeocoder.Service.geoapify.needsKey());
    assertNull(WebGeocoder.Service.of("google"));
    assertNull(WebGeocoder.Service.of(null));
    assertNull(WebGeocoder.Service.of(""));
  }

  @Test
  public void aConfiguredServiceBuildsSomethingThatSaysWhatItIs() throws Exception {
    GpsConfig config = config("{\"enabled\":true,\"service\":\"opencage\","
        + "\"key\":\"abc\",\"contact\":\"a@b.org\"}");
    Geocoder built = config.build(io.hearth.common.Verbose.OFF);
    assertTrue(built.describe().contains("opencage"));
    assertTrue("the rate limit is not a tuning knob, it is their policy",
        built.describe().contains("one lookup a second"));
    assertTrue(config.describe().contains("key configured"));
  }

  @Test
  public void theAddressHandedToAServiceIsTheOneLineAPersonWouldSay() {
    assertEquals("High Street, Ashford, Kent, TN23, UK",
        Places.addressLine("High Street", "Ashford", "Kent", "TN23", "UK", "The Oak"));
    assertEquals("a pub with no address is still findable by its name",
        "The Oak", Places.addressLine("", "", "", "", "", "The Oak"));
    assertEquals("", Places.addressLine("", "", "", "", "", ""));
  }

  // ---- matching what is already written down ----------------------------------------------------

  @Test
  public void twoSpellingsOfOnePubAreOnePub() {
    assertEquals(Places.normalize("The Oak"), Places.normalize("the oak "));
    assertEquals(Places.normalize("The Oak"), Places.normalize("Oak"));
    assertEquals(Places.normalize("St. Mary's Hall"), Places.normalize("St Marys Hall"));
    assertTrue("but two different pubs stay two",
        !Places.normalize("The Oak").equals(Places.normalize("The Oaks")));
  }

  @Test
  public void distanceIsGreatCircleRatherThanFlat() {
    // a degree of longitude at this latitude is about 70km, not 111km, and a flat approximation
    // gets it wrong by enough to put the wrong pub in the answer
    double km = Places.distanceKm(51.1465, 0.8735, 51.1465, 1.8735);
    assertTrue("expected about 70km, got " + km, km > 65 && km < 75);
    assertEquals(0, Places.distanceKm(51.1465, 0.8735, 51.1465, 0.8735), 0.0001);
    assertTrue("and three hundred metres is three hundred metres",
        Places.distanceKm(51.1465, 0.8735, 51.1492, 0.8735) < 0.4);
  }
}
