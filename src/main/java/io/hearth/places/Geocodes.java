package io.hearth.places;

import io.hearth.async.AsyncQueue;
import io.hearth.auth.Accounts;
import io.hearth.auth.AuthSystem;
import io.hearth.common.Verbose;
import io.hearth.people.Home;
import io.hearth.people.ProfileRecord;

/**
 * Every address this server wants placed on the earth, asked for slowly and written down later.
 *
 * <b>The one door to the geocoder.</b> Handlers used to call it directly, inside the save -- which
 * meant an admin adding a place waited on somebody else's server, and forty places added in an
 * afternoon meant forty requests as fast as they could be typed. Now the save writes the row and
 * puts an ask on the queue, and the coordinate arrives a few seconds later. That is the only shape
 * in which one request every {@value io.hearth.async.AsyncQueue#GAP_MILLIS} milliseconds can be
 * honoured across a whole box, and it is what invariant 174 always meant.
 *
 * <b>Two kinds of subject and they are not symmetric.</b> A place is a public thing with a public
 * address, and its coordinate is shown on its page. A member is a private thing: what goes in is
 * either the street address only they can see, or -- failing that -- the town on their profile, and
 * what comes out is one point that nothing ever renders. The job is written so that it reads the
 * current values when it runs rather than carrying them, because between queuing and running
 * somebody may have changed their mind, and the address on the queue would then be the old one.
 */
public class Geocodes {
  private final AsyncQueue queue;
  private final Geocoder geocoder;
  private final AuthSystem auth;
  private final Verbose verbose;

  public Geocodes(AsyncQueue queue, Geocoder geocoder, AuthSystem auth, Verbose verbose) {
    this.queue = queue;
    this.geocoder = geocoder;
    this.auth = auth;
    this.verbose = verbose == null ? Verbose.OFF : verbose;
  }

  public boolean on() {
    return geocoder != Geocoder.NONE;
  }

  public AsyncQueue queue() {
    return queue;
  }

  public Geocoder geocoder() {
    return geocoder;
  }

  /** what this server is currently asking, recorded beside every answer */
  public String service() {
    return geocoder == Geocoder.NONE ? "" : geocoder.name();
  }

  /**
   * Place one address in the address book.
   *
   * Skipped entirely when somebody typed the numbers in by hand: they were standing in the field,
   * and a geocoder was reading a string.
   */
  public void forPlace(String domain, long placeId, String query) {
    if (!on() || query == null || query.isBlank()) {
      return;
    }
    // Whether this episode has already been written down.
    //
    // The queue retries a failed job several times seconds apart, which is the right answer to a
    // service having a bad minute. The row's own counter is the answer to it having a bad
    // afternoon, and bumping it five times per sweep would send a place straight to the day-long
    // wait the first time somebody's DNS hiccuped.
    boolean[] recorded = {false};
    queue.submit(domain, "place " + placeId, () -> {
      Accounts accounts = auth.forDomain(domain);
      if (accounts == null) {
        return false;
      }
      Places.Place place = accounts.places.byId(placeId);
      if (place == null || place.latitude() != null) {
        // deleted, or somebody filled it in while this waited its turn
        return false;
      }
      try {
        Geocoder.Point point = geocoder.find(query);
        if (point == null) {
          accounts.places.notFound(placeId, service(),
              "No such address, according to " + service() + ".");
          return false;
        }
        accounts.places.placed(placeId, point.latitude(), point.longitude(), service());
        verbose.detail(() -> "geocode: place " + placeId + " -> " + point.label());
        return true;
      } catch (Geocoder.Unavailable ex) {
        if (!recorded[0]) {
          recorded[0] = true;
          accounts.places.unreachable(placeId, service(), ex.getMessage(),
              System.currentTimeMillis());
        }
        throw ex;
      }
    });
  }

  /**
   * Place one member.
   *
   * The private address when they gave one, the public location line when they did not. Which of
   * those it was is recorded beside the point, because a distance from a town centre and a distance
   * from a doorstep are different claims and a histogram made of both has to say so.
   */
  public void forMember(String domain, long userId) {
    if (!on()) {
      return;
    }
    boolean[] recorded = {false};
    // no address in the label: the queue's contents end up on an admin screen, and where somebody
    // lives is not something to put there on the way past
    queue.submit(domain, "member " + userId, () -> {
      Accounts accounts = auth.forDomain(domain);
      if (accounts == null) {
        return false;
      }
      io.hearth.people.Home home = accounts.people.homeOf(userId);
      ProfileRecord profile = accounts.people.profileOf(userId);
      boolean precise = home.hasAddress();
      String query = precise ? home.address() : profile.location();
      if (query == null || query.isBlank()) {
        return false;
      }
      try {
        Geocoder.Point point = geocoder.find(query);
        if (point == null) {
          // A closed answer, not a missing one. Nothing will ask again until the address changes
          // or the service does -- asking the same service the same question tomorrow spends a
          // slot to get the same answer, every day, forever.
          accounts.people.notFound(userId, service(),
              "We could not find that. A town and a postcode is usually enough.");
          return false;
        }
        accounts.people.placed(userId, point.latitude(), point.longitude(),
            precise ? io.hearth.people.Home.PRECISE : io.hearth.people.Home.CITY, service());
        verbose.detail(() -> "geocode: member " + userId + " placed"
            + (precise ? " precisely" : " roughly"));
        return true;
      } catch (Geocoder.Unavailable ex) {
        // This says nothing about the address, so the point (if any) stays and a time is written
        // down for when to ask again. Then it is rethrown, because the *queue* still needs to know
        // the service is unwell and slow down for everybody.
        if (!recorded[0]) {
          recorded[0] = true;
          accounts.people.unreachable(userId, service(), ex.getMessage(),
              System.currentTimeMillis());
        }
        throw ex;
      }
    });
  }

  /**
   * Everything due right now, members and places both.
   *
   * Runs from the notifier's pass rather than a thread of its own, and takes a bounded slice: a
   * community importing three hundred members has three hundred asks to make, and doing them over
   * the following few hours is exactly right for a number nobody is watching.
   *
   * "Due" is a query rather than a memory -- the same rule the notifier's own queue follows. A row
   * is due if it has never been asked about, if the service could not be reached and its wait has
   * passed, or if it was not found by a service that is no longer the one configured. That last
   * clause is what makes switching geocoder re-open every address the old one could not place,
   * with nobody having to remember to do anything.
   */
  public int sweep(String domain, int limit) {
    if (!on()) {
      return 0;
    }
    Accounts accounts = auth.forDomain(domain);
    if (accounts == null) {
      return 0;
    }
    long now = System.currentTimeMillis();
    int queued = 0;
    try {
      for (long userId : accounts.people.dueForPlacement(service(), now, limit)) {
        forMember(domain, userId);
        queued++;
      }
      for (long placeId : accounts.places.dueForPlacement(service(), now, limit)) {
        forPlace(domain, placeId, addressOf(accounts, placeId));
        queued++;
      }
    } catch (java.sql.SQLException ex) {
      verbose.detail(() -> "geocode sweep failed on " + domain + ": " + ex.getMessage());
    }
    return queued;
  }

  /**
   * Forget every failure so that everything is due again.
   *
   * The button for after a service was down all day, or after somebody fixed whatever was wrong at
   * the other end. It never touches a row that already has a point: re-asking about an address we
   * hold the answer to spends somebody else's rate limit to learn nothing.
   */
  public int reopen(String domain) {
    Accounts accounts = auth.forDomain(domain);
    if (accounts == null) {
      return 0;
    }
    try {
      return accounts.people.reopenPlacements() + accounts.places.reopenPlacements();
    } catch (java.sql.SQLException ex) {
      verbose.detail(() -> "geocode reopen failed on " + domain + ": " + ex.getMessage());
      return 0;
    }
  }

  private static String addressOf(Accounts accounts, long placeId) throws java.sql.SQLException {
    Places.Place place = accounts.places.byId(placeId);
    return place == null ? "" : Places.addressLine(place.address(), place.locality(),
        place.region(), place.postcode(), place.country(), place.name());
  }
}
