package io.hearth.auth;

import java.sql.SQLException;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who is an admin, and who is allowed in at all.
 *
 * Two rules, and the second exists because of the first.
 *
 * Every new account starts unapproved. That is the right default for a community that wants to know
 * who is in the room, and it creates an obvious hole: the first person to sign up has nobody to
 * approve them, and if the last admin's account is lost there is no way back in. A permissions
 * system whose failure mode is "nobody can ever administer this server again" is not a permissions
 * system, it is a trap.
 *
 * So the config carries a list of addresses that are admins by fiat. Membership in that list beats
 * the roles table, cannot be revoked from inside the running system, and carries approval with it.
 * Changing who is on it means editing a file on the box and restarting -- which is exactly the level
 * of access somebody should need to appoint themselves an administrator.
 */
public class Access {
  private final Roles roles;
  private final RoleDefs defs;
  /** addresses that are admins because the config says so, normalized and lowercased */
  private final Set<String> bootstrapAdmins;

  public Access(Roles roles, RoleDefs defs, Set<String> bootstrapAdmins) {
    this.roles = roles;
    this.defs = defs;
    TreeSet<String> normalized = new TreeSet<>();
    for (String email : bootstrapAdmins) {
      String clean = Tokens.normalizeEmail(email);
      if (clean != null && !clean.isEmpty()) {
        normalized.add(clean);
      }
    }
    this.bootstrapAdmins = Collections.unmodifiableSet(normalized);
  }

  public Set<String> bootstrapAdmins() {
    return bootstrapAdmins;
  }

  /** is this address an admin no matter what the database says? */
  public boolean isBootstrapAdmin(String normalizedEmail) {
    return normalizedEmail != null && bootstrapAdmins.contains(normalizedEmail);
  }

  /** admin by config or by grant; the config list is checked first and never overridden */
  public boolean isAdmin(UserRecord user) throws SQLException {
    if (user == null) {
      return false;
    }
    if (isBootstrapAdmin(user.email())) {
      return true;
    }
    return roles.has(user.id(), Roles.ADMIN);
  }

  /**
   * May this person do this thing?
   *
   * The single question every gate asks, so there is one place that knows how a config admin, a
   * granted role and a permission add up. A bootstrap admin short-circuits to yes -- that is the
   * escape hatch, and an escape hatch that consults the database it exists to rescue you from is
   * not one.
   */
  public boolean can(UserRecord user, Permission permission) throws SQLException {
    if (user == null || permission == null) {
      return false;
    }
    if (isBootstrapAdmin(user.email())) {
      return true;
    }
    // Reading, writing and voting on the board are things being let in is enough for. See
    // Permission.MEMBER_BASELINE for why that is a baseline rather than a role nobody would ever
    // decline to grant -- the short version is that the alternative is a board only administrators
    // can read on the morning after an upgrade.
    if (permission.isMemberBaseline() && isApproved(user)) {
      return true;
    }
    Set<Permission> allowed = defs.permissionsFor(roles.of(user.id()));
    return allowed.contains(Permission.everything) || allowed.contains(permission);
  }

  /** everything somebody can do, for the screen that shows them */
  public Set<Permission> permissionsOf(UserRecord user) throws SQLException {
    if (user == null) {
      return java.util.EnumSet.noneOf(Permission.class);
    }
    if (isBootstrapAdmin(user.email())) {
      return java.util.EnumSet.of(Permission.everything);
    }
    java.util.EnumSet<Permission> held =
        java.util.EnumSet.copyOf(defs.permissionsFor(roles.of(user.id())));
    if (isApproved(user)) {
      held.addAll(Permission.MEMBER_BASELINE);
    }
    return held;
  }

  /** whether the admin section should exist for this person at all */
  public boolean canEnterAdmin(UserRecord user) throws SQLException {
    return can(user, Permission.admin_enter);
  }

  /**
   * Approved, and therefore allowed to hold a session.
   *
   * A bootstrap admin is approved by definition -- otherwise the escape hatch would itself be locked
   * behind the thing it exists to unlock.
   */
  public boolean isApproved(UserRecord user) {
    if (user == null) {
      return false;
    }
    return user.isApproved() || isBootstrapAdmin(user.email());
  }

  /** everything that has to be true before somebody gets a session */
  public boolean canSignIn(UserRecord user, long nowMillis) {
    return user != null && user.canSignIn(nowMillis) && isApproved(user);
  }

  /** why they can't, in words a person can act on; null when they can */
  public String refusalFor(UserRecord user, long nowMillis) {
    if (user == null) {
      return "That account cannot sign in right now.";
    }
    if (user.disabled()) {
      return "That account has been turned off. An admin can turn it back on.";
    }
    if (user.isLocked(nowMillis)) {
      return "That account is locked for a little while.";
    }
    if (!isApproved(user)) {
      return "That account is waiting for an admin to approve it.";
    }
    return null;
  }

  /**
   * Bring a config-listed admin's database row into line: approved, and holding the admin role.
   *
   * Called when they sign in, so the roles table eventually reflects reality and an admin listing
   * shows them. The config list is still what actually grants the power; this is bookkeeping.
   */
  public void reconcileBootstrapAdmin(Users users, UserRecord user) throws SQLException {
    if (!isBootstrapAdmin(user.email())) {
      return;
    }
    if (!user.isApproved()) {
      users.approve(user.id(), null);
    }
    if (!roles.has(user.id(), Roles.ADMIN)) {
      roles.grant(user.id(), Roles.ADMIN, null);
    }
  }
}
