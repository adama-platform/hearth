package io.hearth.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Where to send somebody after they sign in.
 *
 * A `?next=` parameter is the classic open redirect. Somebody mails a member a link to
 * `https://yourcommunity.example/login?next=https://not-your-community.example/login`, they sign in
 * on a page they trust, and land on a copy of it asking for the password again. The whole defence is
 * refusing anything that is not a path on this site, and refusing is the only correct answer --
 * "sanitizing" a redirect target means guessing what somebody meant by a URL that is already wrong.
 *
 * So this accepts an absolute path and nothing else. Not a scheme, not a host, not a
 * protocol-relative `//host` (which a browser reads as another site), and not a backslash (which
 * some browsers normalize into a slash, making `/\evil.example` an external redirect on exactly the
 * browsers you would not want it to be).
 *
 * The reason it exists at all is the OAuth flow. A connector sends an admin to `/mcp/authorize`,
 * they are not signed in, and they have to come back to that exact URL afterwards -- with its client
 * id, its PKCE challenge and its state intact -- or the popup they are sitting in never completes
 * and they are dropped into the site wondering what happened.
 */
public final class Landing {
  /** long enough for an OAuth authorize URL, short enough not to be a place to stash things */
  private static final int MAX = 1024;

  private Landing() {
  }

  /**
   * The path to land on, or null when the value cannot be trusted.
   *
   * Null means "use the normal landing page". A caller must never fall back to the raw value.
   */
  public static String safe(String raw) {
    if (raw == null || raw.isEmpty() || raw.length() > MAX) {
      return null;
    }
    // must be an absolute path on this site
    if (raw.charAt(0) != '/') {
      return null;
    }
    // "//host" and "/\host" are both read as another origin by browsers
    if (raw.startsWith("//") || raw.startsWith("/\\")) {
      return null;
    }
    for (int k = 0; k < raw.length(); k++) {
      char ch = raw.charAt(k);
      if (ch < 0x20 || ch == 0x7f) {
        // control characters, including the CR and LF that would split a header
        return null;
      }
      if (ch == '\\' || ch == '<' || ch == '>' || ch == '"' || ch == '\'' || ch == '`') {
        // backslash for the browser normalization above; the rest because this value is echoed
        // into a page as well as into a Location header
        return null;
      }
      if (ch > 0x7e) {
        // anything non-ASCII should have arrived percent-encoded; a raw one is somebody probing
        return null;
      }
    }
    return raw;
  }

  /**
   * Where this request was going, as something safe to hand to `next`.
   *
   * <b>Path and query, not path.</b> Every refusal used to carry `Forms.path(uri)`, so somebody
   * sent to sign in from `/survey?all=1` or `/board?q=chairs` came back to a page that was nearly
   * where they were -- which is the kind of small wrongness nobody reports and everybody notices.
   * The query is part of the address.
   *
   * It still goes through {@link #safe}, even though it is this server's own request line: it is
   * about to be echoed into a `Location` header and then into a page, and "we generated it" is
   * exactly the assumption that turns a URL somebody else typed into a header injection.
   */
  public static String here(io.netty.handler.codec.http.FullHttpRequest req) {
    if (req == null) {
      return null;
    }
    String uri = req.uri();
    String query = Forms.queryString(uri);
    return safe(query == null || query.isEmpty()
        ? Forms.path(uri) : Forms.path(uri) + "?" + query);
  }

  /** did this request carry a usable `next`? */
  public static String from(String uri) {
    return safe(Forms.query(uri, "next"));
  }

  /**
   * Add `next` to a URL, keeping whatever query it already has.
   *
   * Used to thread the destination through a multi-step flow: the login form posts to an action
   * that still carries it, and so does the code page after that.
   */
  public static String carry(String url, String next) {
    if (next == null) {
      return url;
    }
    String separator = url.indexOf('?') < 0 ? "?" : "&";
    return url + separator + "next=" + URLEncoder.encode(next, StandardCharsets.UTF_8);
  }
}
