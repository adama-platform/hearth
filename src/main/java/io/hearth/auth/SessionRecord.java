package io.hearth.auth;

/**
 * One live login, as both the cache and the sessions table see it.
 *
 * Times are epoch millis rather than Timestamp because this record is read on every single request
 * and compared against the clock; boxing a Timestamp to answer "is this still good" is work that
 * shows up when a thousand people are online.
 *
 * There is no token here, only its hash. Nothing that could be replayed sits in the cache.
 */
public record SessionRecord(long id, String tokenHash, long userId, long createdAt, long lastSeenAt,
                            long expiresAt, long revokedAt, boolean robot, String agent) {
  /** a human session: somebody at a keyboard */
  public static SessionRecord person(long id, String tokenHash, long userId, long createdAt,
                                     long lastSeenAt, long expiresAt, long revokedAt) {
    return new SessionRecord(id, tokenHash, userId, createdAt, lastSeenAt, expiresAt, revokedAt, false, null);
  }

  /** sentinel for "no expiry" and "not revoked"; keeps the hot comparison to a long */
  public static final long NEVER = 0L;

  public boolean isRevoked() {
    return revokedAt != NEVER;
  }

  public boolean isExpired(long nowMillis) {
    return expiresAt != NEVER && expiresAt <= nowMillis;
  }

  /** dead by idleness: nothing has touched it inside the window */
  public boolean isIdle(long nowMillis, long idleSeconds) {
    return idleSeconds > 0 && lastSeenAt + idleSeconds * 1000L <= nowMillis;
  }

  public boolean isLive(long nowMillis, long idleSeconds) {
    return !isRevoked() && !isExpired(nowMillis) && !isIdle(nowMillis, idleSeconds);
  }

  /** what to call this session in a log a person is reading */
  public String describe() {
    return robot ? (agent == null || agent.isEmpty() ? "an agent" : agent) : "a person";
  }

  public SessionRecord touched(long nowMillis) {
    return new SessionRecord(id, tokenHash, userId, createdAt, nowMillis, expiresAt, revokedAt, robot, agent);
  }

  public SessionRecord revoked(long nowMillis) {
    return new SessionRecord(id, tokenHash, userId, createdAt, lastSeenAt, expiresAt, nowMillis, robot, agent);
  }
}
