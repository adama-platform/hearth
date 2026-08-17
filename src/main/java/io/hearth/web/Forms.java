package io.hearth.web;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reading what a browser sent: form fields, query parameters, and cookies.
 *
 * Everything here is attacker-controlled, so every accessor has a length ceiling and returns null
 * rather than an empty string for "not there". A handler that forgets to check gets a
 * NullPointerException in a test rather than a silently empty credential in production.
 *
 * **There are two kinds of field and they need two ceilings.** An email address, a code, a uri, a
 * template name -- none of those is ever long, and a 512 character one is somebody probing. A page
 * body is a different thing entirely: the column holds a megabyte because people write.
 *
 * Getting that wrong was a real bug and a bad one. Every long field went through {@link #raw},
 * which returned null past 512 characters, and every caller turned null into the empty string --
 * so writing more than a paragraph and pressing save silently stored nothing. The lesson is in
 * {@link #tooLong()}: a value that exceeds its ceiling is now *recorded*, so a handler can refuse
 * out loud. Silently substituting the empty string for "too long" is how data disappears.
 */
public class Forms {
  /** the default body ceiling; an account form is small and anything larger is not one */
  public static final int MAX_FORM_BYTES = 16 * 1024;
  /**
   * The ceiling for a form that carries written content.
   *
   * Matched to the request aggregator, so the refusal for something genuinely enormous is Netty's
   * 413 -- a visible answer -- rather than this quietly handing back an empty form.
   */
  public static final int MAX_CONTENT_BYTES = WebConfig.DEFAULT_MAX_CONTENT_LENGTH;
  /** no *short* field this server reads is longer than this */
  public static final int MAX_FIELD_LENGTH = 512;
  /** what a person can write into one box; the content column is the same size */
  public static final int MAX_TEXT_LENGTH = 1024 * 1024;

  private final Map<String, String> fields;
  private final TreeMap<String, Integer> oversize = new TreeMap<>();
  private final boolean bodyTooLarge;

  private Forms(Map<String, String> fields) {
    this(fields, false);
  }

  private Forms(Map<String, String> fields, boolean bodyTooLarge) {
    this.fields = fields;
    this.bodyTooLarge = bodyTooLarge;
  }

  /** parse an application/x-www-form-urlencoded body; an unparseable body yields no fields */
  public static Forms of(FullHttpRequest req) {
    return of(req, MAX_FORM_BYTES);
  }

  /**
   * The same, for a form that legitimately carries a lot of text.
   *
   * The admin content and template editors, and the pages where a member writes about themselves.
   * A body over the ceiling yields no fields at all, which then fails the CSRF check and reads as
   * "that form expired" -- confusing, and the reason this is a parameter rather than a constant.
   */
  public static Forms of(FullHttpRequest req, int maxBytes) {
    TreeMap<String, String> fields = new TreeMap<>();
    String contentType = req.headers().get(HttpHeaderNames.CONTENT_TYPE);
    if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
      return new Forms(fields);
    }
    int readable = req.content().readableBytes();
    if (readable > maxBytes) {
      // No fields at all, which the CSRF check then reads as a forged submission and reports as
      // "that form expired" -- a message that sends somebody looking in entirely the wrong place.
      // Recording it means the handler can say what actually happened.
      return new Forms(fields, true);
    }
    if (readable <= 0) {
      return new Forms(fields);
    }
    String body = req.content().toString(StandardCharsets.UTF_8);
    try {
      QueryStringDecoder decoder = new QueryStringDecoder(body, StandardCharsets.UTF_8, false);
      for (Map.Entry<String, List<String>> entry : decoder.parameters().entrySet()) {
        List<String> values = entry.getValue();
        if (!values.isEmpty()) {
          // first value wins; a repeated field is a client doing something odd, not a list
          fields.put(entry.getKey(), values.get(0));
        }
      }
    } catch (IllegalArgumentException ex) {
      return new Forms(fields); // bad percent-encoding; treat as an empty form
    }
    return new Forms(fields);
  }

  /** a short field, trimmed, or null when absent, empty, or implausibly long */
  public String get(String name) {
    String value = fields.get(name);
    if (value == null) {
      return null;
    }
    if (tooLong(name, value, MAX_FIELD_LENGTH)) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** a short field kept exactly as typed; passwords must not be trimmed or normalized */
  public String raw(String name) {
    String value = fields.get(name);
    if (value == null || value.isEmpty() || tooLong(name, value, MAX_FIELD_LENGTH)) {
      return null;
    }
    return value;
  }

  /**
   * A field somebody wrote prose into: a page body, a template, a paragraph about themselves.
   *
   * Kept exactly as typed, up to a megabyte. Anything longer is recorded rather than dropped, so
   * the handler refuses and says so instead of storing an empty string over what was there.
   */
  public String text(String name) {
    String value = fields.get(name);
    if (value == null || value.isEmpty() || tooLong(name, value, MAX_TEXT_LENGTH)) {
      return null;
    }
    return value;
  }

  private boolean tooLong(String name, String value, int ceiling) {
    if (value.length() <= ceiling) {
      return false;
    }
    oversize.put(name, value.length());
    return true;
  }

  /**
   * Was the whole submission too large to parse?
   *
   * Check this before the CSRF check, or an oversized form is reported as an expired one.
   */
  public boolean bodyTooLarge() {
    return bodyTooLarge;
  }

  /**
   * The first field that was too long to accept, or null.
   *
   * A handler that writes without checking this will store an empty string over somebody's work,
   * which is exactly the bug that put this method here.
   */
  public String tooLong() {
    return oversize.isEmpty() ? null : oversize.firstKey();
  }

  /** how long the offending field was, for a message somebody can act on */
  public int tooLongBy(String name) {
    return oversize.getOrDefault(name, 0);
  }

  public boolean isEmpty() {
    return fields.isEmpty();
  }

  /** every field exactly as posted; the minted-form path translates these names itself */
  public Map<String, String> all() {
    return fields;
  }

  /** a query string parameter from the request line */
  /** everything after the '?', or null; what a directory listing matches its pattern against */
  public static String queryString(String uri) {
    if (uri == null) {
      return null;
    }
    int mark = uri.indexOf('?');
    return mark < 0 || mark == uri.length() - 1 ? null : uri.substring(mark + 1);
  }

  public static String query(String uri, String name) {
    try {
      QueryStringDecoder decoder = new QueryStringDecoder(uri, StandardCharsets.UTF_8);
      List<String> values = decoder.parameters().get(name);
      if (values == null || values.isEmpty()) {
        return null;
      }
      String value = values.get(0);
      return value.length() > MAX_FIELD_LENGTH ? null : value;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /** the path with any query string removed */
  public static String path(String uri) {
    String target = uri;
    // Absolute-form: `GET http://example.org/about HTTP/1.1`, which RFC 9112 requires a server to
    // accept and a proxy is entitled to send. The authority in it is *not* a way to pick a virtual
    // host -- the Host header decides that, and `Hosts` is where -- so it is dropped here and only
    // the path survives. This used to matter to nothing because every path was answered the same
    // way; the moment an address that answers nothing became a 404, it decided whether a proxied
    // request found the page at all.
    int scheme = target.indexOf("://");
    if (scheme > 0 && scheme < 8) {
      int slash = target.indexOf('/', scheme + 3);
      target = slash < 0 ? "/" : target.substring(slash);
    }
    int question = target.indexOf('?');
    return question < 0 ? target : target.substring(0, question);
  }

  /** one cookie value, or null; cookie headers above 8k are ignored outright */
  public static String cookie(FullHttpRequest req, String name) {
    String header = req.headers().get(HttpHeaderNames.COOKIE);
    if (header == null || header.length() > 8192) {
      return null;
    }
    for (Cookie cookie : ServerCookieDecoder.STRICT.decode(header)) {
      if (cookie.name().equals(name)) {
        String value = cookie.value();
        return value == null || value.isEmpty() || value.length() > 1024 ? null : value;
      }
    }
    return null;
  }
}
