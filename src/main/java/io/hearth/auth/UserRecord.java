package io.hearth.auth;

import java.sql.Timestamp;

/** One row of the emails table. */
public record UserRecord(long id, String email, String passwordHash, Timestamp createdAt, Timestamp verifiedAt,
                         Timestamp lastLoginAt, Timestamp approvedAt, Long approvedBy, int signupEvents,
                         String signupSignals, String signupIp, Timestamp sessionsValidAfter,
                         int failedAttempts, Timestamp lockedUntil, boolean disabled) {

  public boolean hasPassword() {
    return passwordHash != null && !passwordHash.isEmpty();
  }

  public boolean isVerified() {
    return verifiedAt != null;
  }

  /** an admin has said yes; until then this account exists but cannot hold a session */
  public boolean isApproved() {
    return approvedAt != null;
  }

  /** locked out right now by too many failed attempts */
  public boolean isLocked(long nowMillis) {
    return lockedUntil != null && lockedUntil.getTime() > nowMillis;
  }

  /**
   * Can this account sign in at all, before we even look at what they typed?
   *
   * Approval is deliberately NOT part of this: whether an unapproved account is let in depends on
   * the bootstrap admin list, which lives in {@link Access}. Answering it here would mean two
   * places knowing the rule and eventually disagreeing.
   */
  public boolean canSignIn(long nowMillis) {
    return !disabled && !isLocked(nowMillis);
  }
}
