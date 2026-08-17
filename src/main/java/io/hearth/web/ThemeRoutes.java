package io.hearth.web;

import io.hearth.common.Verbose;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.IOException;
import java.io.InputStream;

/**
 * The three lines that decide whether the page is light or dark.
 *
 * <pre>
 *   GET /~theme.js
 * </pre>
 *
 * A file rather than an inline script, and that is the whole design decision. An inline script needs
 * a nonce (invariant 4 -- inline is nonce-only, never `'unsafe-inline'`), and this has to run on
 * every page including the ones rendered by handlers that have no nonce to give it. A same-origin
 * file is already allowed by `script-src 'self'`, is parsed once for the whole site, and can be
 * cached.
 *
 * Answered here, beside `/3rd` and before the host is resolved, because it is the same bytes for
 * every community and for everybody -- a stranger reading the terms gets the same choice a member
 * does, and putting it behind the approval gate would mean a page that is light for one person and
 * dark for another depending on whether an administrator had said yes yet.
 *
 * Cached for an hour rather than a year: it has no version in its path, so a long cache would mean
 * a fix to it taking a day to reach anybody. An hour is long enough that a person clicking around
 * fetches it once.
 */
public class ThemeRoutes {
  public static final String PATH = "/~theme.js";
  /**
   * The menu's manners, which are not the menu.
   *
   * Served beside the theme script and deferred, because it is not needed for first paint and the
   * menu works without it: a `<details>` opens and closes on its own. All this adds is closing when
   * somebody clicks away or presses escape.
   */
  public static final String MENU_PATH = "/~menu.js";
  /**
   * The rest timer's ticking, which is not the rest timer.
   *
   * The server renders how long it has been and what the target is; this only makes the number
   * move. A gym is the worst network anybody uses regularly, and a timer that exists only once a
   * script has loaded is a timer missing at the moment it is wanted.
   */
  public static final String REST_PATH = "/~rest.js";
  private static final String CACHE = "public, max-age=3600";

  private final Verbose verbose;
  private final byte[] script;
  private final byte[] menu;
  private final byte[] rest;

  public ThemeRoutes(Verbose verbose) {
    this.verbose = verbose;
    this.script = read();
    this.menu = read("/theme/menu.js");
    this.rest = read("/theme/rest.js");
  }

  public static boolean owns(String path) {
    return PATH.equals(path) || MENU_PATH.equals(path) || REST_PATH.equals(path);
  }

  public void handle(ChannelHandlerContext ctx, FullHttpRequest req, WebHandler.Recorder recorder) {
    String wanted = Forms.path(req.uri());
    byte[] script = MENU_PATH.equals(wanted) ? menu
        : REST_PATH.equals(wanted) ? rest : this.script;
    if (script == null) {
      // a jar built without its own resources; a 404 here is a site that is always light rather
      // than a site that does not load
      recorder.status(404);
      Responses.send(ctx, req, HttpResponseStatus.NOT_FOUND, null, Responses.EMPTY);
      return;
    }
    verbose.detail("theme script -> 200");
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/javascript; charset=utf-8", script,
        new String[]{HttpHeaderNames.CACHE_CONTROL.toString(), CACHE});
  }

  private static byte[] read() {
    return read("/theme/theme.js");
  }

  private static byte[] read(String resource) {
    try (InputStream stream = ThemeRoutes.class.getResourceAsStream(resource)) {
      return stream == null ? null : stream.readAllBytes();
    } catch (IOException ex) {
      return null;
    }
  }
}
