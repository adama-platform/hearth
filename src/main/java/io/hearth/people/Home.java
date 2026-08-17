package io.hearth.people;

import io.hearth.places.Placement;

import java.sql.Timestamp;

/**
 * Where somebody actually lives, and the point it resolved to.
 *
 * <b>A separate type from {@link ProfileRecord}, and that is the whole security design.</b> A
 * profile is what a member wrote for other members to read: it goes to the directory, to somebody's
 * page, to the admin review screen, into an export, and past a model. If the address were a field on
 * it, keeping it private would mean remembering to leave one field out at seven call sites, and the
 * eighth would ship. It is a different record read by a different method, and the query that builds
 * a profile does not name the column -- so there is no version of "forgot to hide it" available.
 *
 * <b>What leaves this record is a distance and nothing else.</b> Not the address, not the point, not
 * a map pin, not a "nearest member". One number per person, counted into a bucket with everybody
 * else's, so a planner can see that a hall across town would put half the community on an hour's
 * journey. The person themselves can read and change their own; everybody else, administrators
 * included, gets the histogram.
 *
 * <b>City or precise, said out loud.</b> Somebody who will not give a street address can give a town
 * and still be counted, and a histogram built from a mix has to say so -- a distance from a town
 * centre is a claim about a town, not about a doorstep, and a screen that presented the two as the
 * same number would be quietly wrong in whichever direction flattered the venue.
 *
 * <b>Why the {@link Placement} is a record of its own.</b> Because "we have no point for this
 * person" is three different situations -- never asked, asked and there is no such address, asked
 * and the service was down -- and only the last is worth retrying. Keeping that in one shared type
 * is what stops the retry policy being written twice, once here and once for the address book.
 */
public record Home(long userId, String address, Double latitude, Double longitude,
                   String precision, Placement placement) {

  /** it came from the private address: a doorstep */
  public static final String PRECISE = "precise";
  /** it came from the public location line: a town, near enough */
  public static final String CITY = "city";

  public static Home blank(long userId) {
    return new Home(userId, "", null, null, "", Placement.blank());
  }

  public boolean hasAddress() {
    return address != null && !address.isBlank();
  }

  public boolean hasPoint() {
    return latitude != null && longitude != null;
  }

  public boolean isPrecise() {
    return PRECISE.equals(precision);
  }

  /** what went wrong last time, in this person's own words rather than a state name */
  public String note() {
    return placement.note();
  }

  /** when it was last asked about -- successfully or not */
  public Timestamp triedAt() {
    return placement.triedAt();
  }

  /** the service could not be reached, so this is not about the address and will be tried again */
  public boolean isRetrying() {
    return placement.isUnreachable();
  }

  /** the service answered and had never heard of it; nothing will happen until something changes */
  public boolean isUnfindable() {
    return placement.isNotFound();
  }

  /** what to tell its owner, and only its owner */
  public String status() {
    if (hasPoint() && isPrecise()) {
      return "Your address has been placed on the map. Only distances are ever shown to anybody.";
    }
    if (hasPoint()) {
      return "Placed roughly, from the location on your profile.";
    }
    if (!hasAddress() && placement.isUntried()) {
      return "Nothing given, so you are not counted when somebody works out how far people would"
          + " have to travel.";
    }
    if (placement.isUnreachable()) {
      // Deliberately not the note here: the note is what the other end said, which is a sentence
      // about DNS or an HTTP status. Nothing is wrong with the address, nothing is for this person
      // to fix, and the only useful thing to tell them is that it keeps trying by itself.
      return placement.describe();
    }
    if (!placement.note().isBlank()) {
      return placement.note();
    }
    return placement.describe();
  }
}
