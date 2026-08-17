package io.hearth.places;

/**
 * Turning an address into a point on the earth, from somebody else's service.
 *
 * <b>An interface first, because the choice of service is an operator's and a licensing decision
 * rather than a technical one.</b> Geocoding terms are unusually restrictive and unusually varied,
 * and the thing this product does with a result -- writes the coordinates onto a row and keeps them
 * -- is exactly the thing several of the big services forbid. So the seam exists so that the
 * decision can be made per install, tested without a network, and written down in the walkthrough
 * that asks for the key.
 *
 * <b>What the research turned up, and why the obvious answers are not here.</b>
 *
 * <ul>
 *   <li><b>Google</b> and <b>Mapbox</b>: results may not be stored permanently on ordinary terms --
 *       Mapbox splits temporary from permanent geocoding and charges differently for it, and Google
 *       requires results to be used with a Google map. Storing a coordinate on a place row is the
 *       whole feature here, so neither is offered.</li>
 *   <li><b>HERE</b>: the largest free tier of any of them, and caps caching at 30 days on standard
 *       plans. Same problem, and worse for being tempting.</li>
 * </ul>
 *
 * What is offered, all three of which permit what this actually does:
 *
 * <ol>
 *   <li><b>Nominatim</b>, the OpenStreetMap service. Free, no key, and the right answer at this
 *       scale -- a community geocodes a few dozen places once each. Its acceptable use policy is
 *       the constraint: one request a second, a real User-Agent naming you, and results must be
 *       cached rather than re-asked. Writing the coordinate onto the row *is* that cache.</li>
 *   <li><b>OpenCage</b>. A key, a free tier for testing and paid plans from there -- and the only
 *       one whose terms say plainly that results may be kept permanently, even after you stop being
 *       a customer. The answer for a community that wants this to be somebody's job.</li>
 *   <li><b>Geoapify</b>. A key, a larger free allowance than the others, attribution required on
 *       the free plan.</li>
 * </ol>
 *
 * <b>Nothing here is ever called on the request path.</b> A geocode is a network call to somebody
 * else's server with somebody else's latency, and a page that waits on one is a page that hangs
 * when they have a bad afternoon. Places are geocoded when they are saved, from the admin's own
 * request, and an event arriving by email is matched against what is already stored first.
 */
public interface Geocoder {
  /** what came back, or null when the service had nothing */
  record Point(double latitude, double longitude, String label) {
  }

  /**
   * The service could not be asked: down, refusing, over its limit, or unreachable.
   *
   * <b>Distinct from "no such place", and that distinction is what makes backoff possible.</b> This
   * used to be swallowed and answered as null, which was right when a geocode happened inside a
   * save -- a place without a coordinate is a place, and a save that failed because somebody else's
   * server was slow is a bug in this one. It is wrong now that the work is queued: a queue that
   * cannot tell "there is no such street" from "they are not answering" either retries a typo
   * forever or hammers a service that is having a bad afternoon. One of those two is how a client
   * gets blocked.
   */
  class Unavailable extends Exception {
    public Unavailable(String message) {
      super(message);
    }

    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Look one address up.
   *
   * @return the point, or null when the service answered and had nothing -- which is a complete and
   *     correct answer, and must never be confused with the service being unreachable.
   * @throws Unavailable when the request itself did not work.
   */
  Point find(String query) throws Unavailable;

  /**
   * The same, for a caller that has nowhere to put a failure.
   *
   * Inbound mail is the one: an invitation arriving by email is matched against places that are
   * already known first, and if the geocoder is also down then the event lands without a location
   * rather than not landing. Anything with a queue behind it should use {@link #find} and let the
   * queue decide what a failure costs.
   */
  default Point findQuietly(String query) {
    try {
      return find(query);
    } catch (Unavailable ex) {
      return null;
    }
  }

  /** what this is, for the boot report and the admin screen */
  String describe();

  /**
   * A short, stable name for whoever is answering.
   *
   * Stored beside every answer, and the reason is the one failure that cannot fix itself: a service
   * that has never heard of an address will never hear of it, so nothing asks again -- unless the
   * service has changed, which is exactly the case somebody switches geocoder in order to fix.
   * Comparing this against what is recorded on the row is what re-opens them all, with nobody
   * having to remember anything.
   */
  default String name() {
    return getClass().getSimpleName();
  }

  /** the one that does nothing, for a community that has not set one up */
  Geocoder NONE = new Geocoder() {
    @Override
    public String name() {
      return "none";
    }

    @Override
    public Point find(String query) {
      return null;
    }

    @Override
    public String describe() {
      return "off; addresses are stored as typed and coordinates entered by hand";
    }
  };
}
