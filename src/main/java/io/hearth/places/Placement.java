package io.hearth.places;

import java.sql.Timestamp;

/**
 * How the last attempt to place an address ended, and when it may be tried again.
 *
 * <b>Two failures, and only one of them is worth retrying.</b> The first version of this had one:
 * an address that did not turn into a point was simply a row with no point, and the sweep picked it
 * up again on the next pass. That is wrong in both directions at once. A street name with a typo in
 * it was asked about every minute forever, at the cost of one of the queue's slots each time; and a
 * service that was down for an hour left every member's address in the same indistinguishable
 * state, so nothing could tell the operator that anything had gone wrong or that anything was worth
 * asking again.
 *
 * <ul>
 *   <li><b>not found</b> -- the service answered and has never heard of that address. Asking it
 *       again tomorrow gets the same answer, so nothing does. It re-opens when the address changes,
 *       when the operator switches service, or when somebody presses the button.</li>
 *   <li><b>unreachable</b> -- the service did not answer: down, refusing, over its limit. This says
 *       nothing about the address, so it is retried on a widening schedule until it works.</li>
 * </ul>
 *
 * <b>Which service answered is stored with the answer.</b> Without it, switching to a better
 * geocoder leaves every previous "not found" sitting there permanently -- and fixing exactly those
 * is the usual reason somebody switches. Comparing the recorded service against the configured one
 * is what re-opens them, with nobody having to remember to press anything.
 *
 * <b>The schedule is coarse on purpose.</b> The queue already retries within a run, seconds apart,
 * which is the right response to a service having a bad minute. This is the response to a service
 * having a bad afternoon, and the useful spacings there are quarter-hours and hours. It never
 * reaches "never": a day is the ceiling, so a service that comes back a week later is picked up
 * within a day of coming back, and an operator who is watching can have all of it now.
 */
public record Placement(String state, String service, int tries, Timestamp triedAt,
                        Timestamp nextAt, String note) {

  /** never asked about */
  public static final String UNKNOWN = "";
  /** it has a point */
  public static final String PLACED = "placed";
  /** the service answered, and has no such address */
  public static final String NOT_FOUND = "not_found";
  /** the service could not be asked */
  public static final String UNREACHABLE = "unreachable";

  /** what each failure costs before the next attempt, in minutes; the last one repeats forever */
  private static final int[] MINUTES = {15, 60, 240, 1440};

  public static Placement blank() {
    return new Placement(UNKNOWN, "", 0, null, null, "");
  }

  public boolean isPlaced() {
    return PLACED.equals(state);
  }

  public boolean isNotFound() {
    return NOT_FOUND.equals(state);
  }

  public boolean isUnreachable() {
    return UNREACHABLE.equals(state);
  }

  /** never asked, so it goes on the queue as soon as anything is looking */
  public boolean isUntried() {
    return state == null || state.isBlank();
  }

  /** when this may be asked about again, given how many times it has failed */
  public static Timestamp scheduleAfter(int tries, long now) {
    int minutes = MINUTES[Math.min(Math.max(tries, 1), MINUTES.length) - 1];
    return new Timestamp(now + minutes * 60_000L);
  }

  /** what to say about it, to whoever is allowed to be told */
  public String describe() {
    return switch (state == null ? "" : state) {
      case PLACED -> "Placed.";
      case NOT_FOUND -> "That address could not be found"
          + (service.isBlank() ? "" : " by " + service)
          + ". A town and a postcode is usually enough. It will be tried again if the address"
          + " changes, or if this server switches to another lookup service.";
      case UNREACHABLE -> "The lookup service could not be reached"
          + (tries > 1 ? " (" + tries + " attempts)" : "")
          + ". Nothing is wrong with the address; this keeps trying on its own.";
      default -> "Waiting to be looked up. This happens in the background and takes a minute or"
          + " two.";
    };
  }

  /** a short word for a table */
  public String word() {
    return switch (state == null ? "" : state) {
      case PLACED -> "placed";
      case NOT_FOUND -> "not found";
      case UNREACHABLE -> "cannot reach the service";
      default -> "waiting";
    };
  }
}
