package io.hearth.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one-line "that worked" a page shows after a redirect.
 *
 * In memory, keyed by session, read once and gone. The obvious alternative is a query parameter on
 * the redirect target, which is what this replaces: every admin action left
 * `?done=example.org+is+approved.` in the URL, in the browser history, and in the access log, where
 * it is pure noise on top of the path somebody actually wanted to see. A message meant for one
 * person, once, does not belong in a URL.
 *
 * Losing one on a restart is fine. Nothing depends on a flash arriving.
 */
public class Flash {
  private static final int MAX_PENDING = 10_000;
  private final ConcurrentHashMap<String, Message> pending = new ConcurrentHashMap<>();

  /** leave a message for whoever holds this session, to be shown on their next page */
  public void set(String sessionKey, String message, boolean bad) {
    set(sessionKey, message, bad, null);
  }

  /**
   * The same, carrying something that must be shown exactly once and never appear in a URL.
   *
   * There is one caller: the screen that mints an API token, which has to put the token in front of
   * a person to copy and must not put it in a redirect, in the history or in the access log. A
   * secret handed back on the POST's own response would be re-minted by a refresh; this is the same
   * one-shot the rest of the redirect flow already uses.
   */
  public void set(String sessionKey, String message, boolean bad, String secret) {
    if (sessionKey == null || message == null) {
      return;
    }
    if (pending.size() > MAX_PENDING) {
      pending.clear();
    }
    pending.put(sessionKey, new Message(message, bad, System.currentTimeMillis(), secret));
  }

  /**
   * Read and clear.
   *
   * A flash shown twice looks like the action ran twice, so it comes out exactly once. The returned
   * map goes straight into the page model, where the template picks the green box or the red one --
   * a refusal rendered as a confirmation is worse than no message at all.
   */
  public Map<String, Object> take(String sessionKey) {
    if (sessionKey == null) {
      return null;
    }
    Message message = pending.remove(sessionKey);
    if (message == null) {
      return null;
    }
    // a message from an hour ago belongs to something the person has long forgotten
    if (System.currentTimeMillis() - message.at() > 60_000) {
      return null;
    }
    return message.secret() == null
        ? Map.of("text", message.text(), "bad", message.bad(), "good", !message.bad())
        : Map.of("text", message.text(), "bad", message.bad(), "good", !message.bad(),
            "secret", message.secret());
  }

  public int size() {
    return pending.size();
  }

  private record Message(String text, boolean bad, long at, String secret) {
  }

  /** what to key by: the session, so two people on one browser profile do not share messages */
  public static String keyFor(io.hearth.auth.SessionRecord session) {
    return session == null ? null : session.tokenHash();
  }

  /** for the admin overview */
  public Map<String, Integer> stats() {
    return Map.of("pending", pending.size());
  }
}
