package io.hearth.web;

import io.hearth.auth.LoginSecurity;
import io.hearth.auth.Tokens;
import io.netty.handler.codec.http.FullHttpRequest;

/**
 * Building Set-Cookie headers, and the double-submit token that protects the forms.
 *
 * Session cookies are always HttpOnly: a session token that JavaScript can read is a session token
 * that one cross-site scripting bug hands over wholesale. The CSRF cookie is deliberately NOT
 * HttpOnly-critical in the same way -- it is not a credential, only a value that must match the one
 * in the form -- but it costs nothing to lock down, so it is.
 */
public class Cookies {
  public static final String CSRF_COOKIE = "hearth_csrf";
  public static final String CSRF_FIELD = "csrf";

  private Cookies() {
  }

  /** set the session cookie for the life of the session */
  public static String session(LoginSecurity security, String token) {
    return build(security.cookieName, token, security, maxAge(security));
  }

  /** clear the session cookie; same attributes, empty value, expired */
  public static String clearSession(LoginSecurity security) {
    return build(security.cookieName, "", security, 0);
  }

  public static String csrf(LoginSecurity security, String token) {
    return build(CSRF_COOKIE, token, security, 3600);
  }

  /**
   * The CSRF token for this browser: the one it already has, or a fresh one.
   *
   * Stable per browser, NOT per page, and that distinction is the whole of this method. Minting a
   * new token on every response looks harmless until you remember a browser does not load one page
   * -- it loads a page, then asks for the favicon, then maybe prefetches a link, and any one of
   * those responses would overwrite the cookie the form in front of the person is carrying. The
   * symptom is a registration form that says it expired the instant you submit it, which is exactly
   * what it did.
   *
   * A second tab, an image, a background fetch: all of them are fine now, because they all see the
   * same token and hand it straight back.
   */
  public static String stableToken(FullHttpRequest req) {
    String existing = Forms.cookie(req, CSRF_COOKIE);
    return isWellFormed(existing) ? existing : newCsrfToken();
  }

  /** a fresh random token; used for the CSRF cookie when there isn't one, and for script nonces */
  public static String newCsrfToken() {
    return Tokens.newHandle();
  }

  /** does this look like a token we issued, rather than something a client made up? */
  static boolean isWellFormed(String token) {
    if (token == null || token.length() < 16 || token.length() > 64) {
      return false;
    }
    for (int k = 0; k < token.length(); k++) {
      char ch = token.charAt(k);
      boolean allowed = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
          || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_';
      if (!allowed) {
        return false;
      }
    }
    return true;
  }

  /**
   * Do the value in the form and the value in the cookie agree?
   *
   * Double-submit: an attacker's page can make a browser POST here, but it cannot read our cookie,
   * so it cannot put the matching value in the form. Compared in constant time out of habit -- the
   * token is not secret enough for timing to matter, but the habit is what keeps the one that does
   * matter from being written the other way.
   */
  public static boolean csrfMatches(String fromForm, String fromCookie) {
    if (fromForm == null || fromCookie == null || fromCookie.length() < 16) {
      return false;
    }
    return Tokens.constantTimeEquals(fromForm, fromCookie);
  }

  private static long maxAge(LoginSecurity security) {
    // a session with no server-side lifetime still gets a bounded cookie; a year is long enough to
    // feel permanent and short enough that an abandoned laptop does not stay signed in forever
    return security.sessionLifetimeSeconds == 0 ? 31_536_000L : security.sessionLifetimeSeconds;
  }

  private static String build(String name, String value, LoginSecurity security, long maxAgeSeconds) {
    StringBuilder sb = new StringBuilder();
    sb.append(name).append('=').append(value);
    sb.append("; Path=/");
    sb.append("; Max-Age=").append(maxAgeSeconds);
    sb.append("; HttpOnly");
    sb.append("; SameSite=").append(security.cookieSameSite);
    if (security.cookieSecure) {
      sb.append("; Secure");
    }
    return sb.toString();
  }
}
