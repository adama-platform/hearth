package io.hearth.people;

import io.hearth.auth.Accounts;

import java.sql.SQLException;
import java.util.Map;

/**
 * What to call somebody, on a page another member is looking at.
 *
 * <b>Never their email address.</b> That is what this exists to stop, and it was everywhere: the
 * board printed the author's address on every post and every comment, the guest list printed one per
 * guest, the dashboard printed one per thread, and a notification said "ana@example.com replied to
 * you". Meanwhile `/members` -- the one screen actually designed for looking at people -- carries no
 * addresses at all, on the grounds that a member list is the easiest thing in the world to
 * screenshot. Both rules cannot be right, and the directory's is the right one: a community of two
 * hundred people should not hand every one of them a machine-readable list of the other hundred and
 * ninety nine, and an address is a thing people get spam at rather than a thing they are called.
 *
 * The admin section is the deliberate exception and always was. Approving somebody is a decision
 * about an address, and an administrator who cannot see one cannot do the job.
 *
 * <b>Somebody with no name is "a member", not a fragment of their address.</b> A local part is still
 * most of an address and usually most of a real name. A name is required to save a profile now, so
 * this is only reached by an account that predates that or one that never finished the welcome, and
 * the honest answer there is that we do not know what to call them yet.
 */
public final class Names {
  public static final String UNKNOWN = "a member";

  private final Map<Long, ProfileRecord> profiles;

  private Names(Map<Long, ProfileRecord> profiles) {
    this.profiles = profiles;
  }

  /**
   * One read of the profile table, for a page that is about to name a lot of people.
   *
   * A query per row would be forty queries to draw a feed, and a cache would be a third thing to
   * invalidate when somebody changes their name. At a hundred to a thousand members this is one
   * indexed scan of a small table per page, which is the trade this whole program makes.
   */
  public static Names of(Accounts accounts) {
    try {
      return new Names(accounts.people.allProfiles());
    } catch (SQLException ex) {
      // a page that cannot reach the profiles still renders; it just calls everybody a member,
      // which is a worse page rather than a broken one -- and never an address by accident
      return new Names(Map.of());
    }
  }

  public String of(long userId) {
    ProfileRecord profile = profiles.get(userId);
    if (profile == null || profile.displayName() == null || profile.displayName().isBlank()) {
      return UNKNOWN;
    }
    return profile.displayName();
  }

  /** for the one-off caller that has a user id and no page to build */
  public static String nameOf(Accounts accounts, long userId) {
    try {
      ProfileRecord profile = accounts.people.profileOf(userId);
      return profile.displayName().isBlank() ? UNKNOWN : profile.displayName();
    } catch (SQLException ex) {
      return UNKNOWN;
    }
  }
}
