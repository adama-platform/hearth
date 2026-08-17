package io.hearth.auth;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Emailed codes in flight, held in memory only.
 *
 * Never on disk, deliberately. A code is valid for ten minutes and is worth an account for those
 * ten minutes, so writing it down buys nothing (a restart every few weeks costs one person one
 * retry) and costs a row that is a credential. It also keeps the promise that the disk is for
 * startup and for durable state, not for scratch.
 *
 * A code is addressed by an opaque handle, not by the email address. The handle goes in the form
 * and the URL; the address stays server-side. That way the page that says "check your email" cannot
 * be edited into a page that redeems a code for somebody else's address.
 *
 * Attempts are counted and the code burns after a few wrong guesses -- six digits is 1 in a million
 * per try, which is plenty against five tries and nothing at all against unlimited ones.
 */
public class PendingCodes {
  /** what a code, once redeemed, entitles somebody to do */
  public enum Purpose {
    register,
    login,
    reset_password,
    two_factor
  }

  private final LoginSecurity security;
  private final ConcurrentHashMap<String, Pending> byHandle = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Window> byEmail = new ConcurrentHashMap<>();

  public PendingCodes(LoginSecurity security) {
    this.security = security;
  }

  /**
   * Start a flow: mint a code, remember it against a fresh handle, and hand both back. The caller
   * mails the code and puts the handle in the page.
   */
  public Issued issue(Purpose purpose, String normalizedEmail, Long userId) {
    sweep();
    String handle = Tokens.newHandle();
    String code = Tokens.newCode(security.codeLength);
    long expiresAt = System.currentTimeMillis() + security.codeLifetimeSeconds * 1000L;
    byHandle.put(handle, new Pending(purpose, normalizedEmail, userId, code, expiresAt));
    return new Issued(handle, code, expiresAt);
  }

  /**
   * Rate limit code requests per address, so that the form cannot be used to mail somebody a
   * hundred times. Returns true when the request is allowed.
   */
  public boolean allowRequest(String normalizedEmail) {
    long now = System.currentTimeMillis();
    Window window = byEmail.compute(normalizedEmail.toLowerCase(Locale.ROOT), (key, existing) -> {
      if (existing == null || now - existing.startedAt > 3_600_000L) {
        return new Window(now);
      }
      return existing;
    });
    return window.count.incrementAndGet() <= security.codeRequestsPerHour;
  }

  /**
   * Redeem a code. A correct code is consumed; a wrong one costs an attempt and, once they run out,
   * burns the handle so the flow has to start over.
   */
  public Redeemed redeem(String handle, Purpose purpose, String code) {
    if (handle == null || code == null) {
      return Redeemed.rejected("that code did not match");
    }
    Pending pending = byHandle.get(handle);
    if (pending == null) {
      return Redeemed.rejected("that request expired; start again");
    }
    if (pending.purpose != purpose) {
      // a handle minted for one flow must not be spendable in another
      byHandle.remove(handle);
      return Redeemed.rejected("that request expired; start again");
    }
    if (System.currentTimeMillis() > pending.expiresAt) {
      byHandle.remove(handle);
      return Redeemed.rejected("that code expired; start again");
    }
    if (!Tokens.constantTimeEquals(pending.code, code.trim())) {
      int used = pending.attempts.incrementAndGet();
      if (used >= security.codeMaxAttempts) {
        byHandle.remove(handle);
        return Redeemed.rejected("too many wrong codes; start again");
      }
      return Redeemed.rejected("that code did not match");
    }
    byHandle.remove(handle);
    return Redeemed.accepted(pending.email, pending.userId);
  }

  /** the address a handle belongs to, for redisplaying "we mailed name@example.com" */
  public String emailFor(String handle) {
    Pending pending = handle == null ? null : byHandle.get(handle);
    return pending == null ? null : pending.email;
  }

  public int size() {
    return byHandle.size();
  }

  /** drop everything expired; cheap, and called on every issue so nothing accumulates */
  public void sweep() {
    long now = System.currentTimeMillis();
    byHandle.values().removeIf(pending -> pending.expiresAt <= now);
    byEmail.values().removeIf(window -> now - window.startedAt > 3_600_000L);
  }

  private static final class Pending {
    final Purpose purpose;
    final String email;
    final Long userId;
    final String code;
    final long expiresAt;
    final AtomicInteger attempts = new AtomicInteger();

    Pending(Purpose purpose, String email, Long userId, String code, long expiresAt) {
      this.purpose = purpose;
      this.email = email;
      this.userId = userId;
      this.code = code;
      this.expiresAt = expiresAt;
    }
  }

  private static final class Window {
    final long startedAt;
    final AtomicInteger count = new AtomicInteger();

    Window(long startedAt) {
      this.startedAt = startedAt;
    }
  }

  /** the handle to put in the page and the code to put in the email */
  public record Issued(String handle, String code, long expiresAt) {
  }

  /** the result of a redemption; email and userId are only set when accepted */
  public record Redeemed(boolean accepted, String email, Long userId, String problem) {
    static Redeemed accepted(String email, Long userId) {
      return new Redeemed(true, email, userId, null);
    }

    static Redeemed rejected(String problem) {
      return new Redeemed(false, null, null, problem);
    }
  }
}
