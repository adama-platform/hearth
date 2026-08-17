package io.hearth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.common.Verbose;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * The progressive web app: a shell, a manifest, a service worker, and push.
 *
 * <pre>
 *   /~app                     the shell; the installed app's start_url
 *   /manifest.webmanifest     what the browser installs
 *   /sw.js                    the service worker, at the root so its scope is the whole site
 *   POST /~app/push           subscribe this session, or say it is gone
 * </pre>
 *
 * <h2>Why a shell around an iframe</h2>
 *
 * The shell is a thin page that holds the site in an iframe. That is an old trick and it is here
 * for one modern reason: the shell stays put while the frame navigates, so the service worker, the
 * push permission and the notification state all live in a document that is never torn down. A
 * plain multi-page app re-runs its registration on every navigation and loses any state that is not
 * in storage -- workable, but it makes "did this browser subscribe" a question with a different
 * answer on every page.
 *
 * It also keeps the ordinary site ordinary. Every page still works on its own URL, with no
 * JavaScript, exactly as before; the shell is an additional way in rather than the way in. The
 * frame is same-origin only -- a shell that would frame anything else is an open redirect with
 * extra steps.
 *
 * <h2>Why the tilde</h2>
 *
 * `/~app` cannot collide with a page somebody writes, because content uris are checked and a
 * leading tilde is not something anybody types by accident. The alternative -- reserving `/app` --
 * takes a word a community might want.
 */
public class PwaRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(PwaRoutes.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  public static final String SHELL = "/~app";
  public static final String MANIFEST = "/manifest.webmanifest";
  public static final String WORKER = "/sw.js";
  /** where the installable icons live; same-origin, because a data: URI is not installable */
  public static final String ICON = SHELL + "/icon";
  /** the how-to: installing it, turning notifications on, and proving they work */
  public static final String HELP = SHELL + "/help";
  /** the self-test: send one to this browser, right now */
  public static final String SELF_TEST = SHELL + "/selftest";

  private final Templates templates;
  private final Verbose verbose;

  public PwaRoutes(Templates templates, Verbose verbose) {
    this.templates = templates;
    this.verbose = verbose;
  }

  public static boolean owns(String path) {
    return path.equals(SHELL) || path.startsWith(SHELL + "/") || path.equals(MANIFEST)
        || path.equals(WORKER);
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    String path = Forms.path(req.uri());
    try {
      if (path.equals(MANIFEST)) {
        manifest(config, accounts, ctx, req, recorder);
        return;
      }
      if (path.equals(WORKER)) {
        worker(config, ctx, req, recorder);
        return;
      }
      if (path.equals(SHELL + "/push")) {
        push(config, accounts, ctx, req, recorder);
        return;
      }
      if (path.startsWith(ICON)) {
        icon(config, accounts, ctx, req, path, recorder);
        return;
      }
      if (path.equals(HELP)) {
        help(config, accounts, ctx, req, recorder);
        return;
      }
      if (path.equals(SELF_TEST)) {
        selfTest(config, accounts, ctx, req, recorder);
        return;
      }
      shell(config, accounts, ctx, req, recorder);
    } catch (SQLException ex) {
      LOG.error("pwa-route-failed", ex);
      recorder.status(500);
      Responses.send(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, null, Responses.EMPTY);
    }
  }

  // ---- the shell --------------------------------------------------------------------------------

  private void shell(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) throws SQLException {
    SessionRecord session = AccountRoutes.currentSession(accounts, req);
    String csrf = Cookies.stableToken(req);
    Map<String, Object> model = new HashMap<>();
    io.hearth.web.Chrome.site(model, accounts);
    model.put("community", config.name);
    model.put("title", config.name);
    model.put("csrf", csrf);
    model.put("nonce", Cookies.newCsrfToken());
    model.put("themeColor", themeColor(accounts));

    // where the frame starts. `?to=` is honoured only when it is a path on this site, through the
    // same check that stops `?next=` being an open redirect -- a shell that frames anywhere is a
    // phishing kit with a manifest.
    String wanted = Landing.safe(Forms.query(req.uri(), "to"));
    model.put("start", wanted == null || wanted.isEmpty() ? config.urls.afterLogin : wanted);
    // Somebody arriving with a destination in the address came from a notification: that is what
    // the service worker puts there when one is tapped. It is the only honest signal this server
    // gets that a push worked, and it is what the delay histogram is built from.
    if (session != null && wanted != null && !wanted.isEmpty()) {
      accounts.pushLedger.acted(session.userId(), System.currentTimeMillis());
    }
    model.put("signedIn", session != null);
    model.put("pushUrl", SHELL + "/push");
    // the key is minted per session, so an anonymous shell has nothing to offer yet
    model.put("vapidKey", session == null ? null
        : accounts.pushSubs.publicKeyFor(session.id(), session.userId()));

    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
        templates.render("app", model),
        new String[]{HttpHeaderNames.SET_COOKIE.toString(), Cookies.csrf(accounts.security, csrf)},
        (String) model.get("nonce"));
  }

  // ---- the manifest -----------------------------------------------------------------------------

  private void manifest(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                        FullHttpRequest req, WebHandler.Recorder recorder) {
    ObjectNode manifest = JSON.createObjectNode();
    manifest.put("name", config.name);
    manifest.put("short_name", shortName(config.name));
    manifest.put("description", config.name + " -- a place to see what is on and who is coming.");
    manifest.put("start_url", SHELL);
    manifest.put("scope", "/");
    manifest.put("display", "standalone");
    manifest.put("orientation", "any");
    manifest.put("background_color", "#fbfbfa");
    manifest.put("theme_color", themeColor(accounts));
    manifest.put("lang", "en");

    // an id, so a browser keeps treating this as the same app when the start url moves
    manifest.put("id", SHELL);

    // Real PNGs at real addresses, drawn on the way out.
    //
    // These used to be the inline SVG the favicon uses, as data: URIs -- correct by the
    // specification and refused in practice. Chrome downloads manifest icons and will not offer to
    // install an app whose icons are data URIs; iOS wants a PNG before it puts anything on a home
    // screen. The app had a manifest, a worker and no install button, and the reason was here.
    var icons = manifest.putArray("icons");
    icon(icons, ICON + "-" + AppIcon.SMALL + ".png", AppIcon.SMALL + "x" + AppIcon.SMALL, "any");
    icon(icons, ICON + "-" + AppIcon.LARGE + ".png", AppIcon.LARGE + "x" + AppIcon.LARGE, "any");
    // maskable is drawn smaller inside its square so a phone can crop it into its own shape
    // without cutting the mark in half
    icon(icons, ICON + "-maskable-" + AppIcon.LARGE + ".png",
        AppIcon.LARGE + "x" + AppIcon.LARGE, "maskable");
    // and the vector as well, for anything that would rather scale than resample
    ObjectNode vector = icons.addObject();
    vector.put("src", Icons.FAVICON_DATA_URI);
    vector.put("sizes", "any");
    vector.put("type", "image/svg+xml");
    vector.put("purpose", "any");

    var shortcuts = manifest.putArray("shortcuts");
    if (config.has(io.hearth.vhost.Surface.board)) {
      ObjectNode board = shortcuts.addObject();
      board.put("name", "Discussion");
      board.put("url", SHELL + "?to=" + config.urls.board);
    }
    if (config.has(io.hearth.vhost.Surface.calendar)) {
      ObjectNode what = shortcuts.addObject();
      what.put("name", "Events");
      what.put("url", SHELL + "?to=" + config.urls.calendar);
    }

    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "application/manifest+json; charset=utf-8",
        manifest.toString().getBytes(StandardCharsets.UTF_8),
        new String[]{HttpHeaderNames.CACHE_CONTROL.toString(), "public, max-age=3600"});
  }

  private static void icon(com.fasterxml.jackson.databind.node.ArrayNode icons, String src,
                           String sizes, String purpose) {
    ObjectNode node = icons.addObject();
    node.put("src", src);
    node.put("sizes", sizes);
    node.put("type", "image/png");
    node.put("purpose", purpose);
  }

  /**
   * One icon, drawn in the community's own colours.
   *
   * Cached hard: the bytes for a given size and colour never change, and a browser fetching four of
   * these during an install should not cost four renders.
   */
  private void icon(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, String path, WebHandler.Recorder recorder) {
    String rest = path.substring(ICON.length());
    boolean maskable = rest.startsWith("-maskable");
    int size = rest.contains(Integer.toString(AppIcon.LARGE)) ? AppIcon.LARGE : AppIcon.SMALL;
    byte[] png = AppIcon.png(size, accentOf(accounts), maskable);
    if (png == null) {
      // a runtime without image encoding: no icon rather than a broken one, and the site keeps its
      // inline favicon either way
      recorder.status(404);
      Responses.send(ctx, req, HttpResponseStatus.NOT_FOUND, null, Responses.EMPTY);
      return;
    }
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "image/png", png,
        new String[]{HttpHeaderNames.CACHE_CONTROL.toString(), "public, max-age=86400"});
  }

  private static String accentOf(Accounts accounts) {
    try {
      return accounts == null ? null : accounts.themes.of(io.hearth.theme.Theme.Scope.site)
          .light.accent();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  // ---- the service worker -----------------------------------------------------------------------

  /**
   * Served from the root so its scope is the whole site.
   *
   * A worker under /~app/ could only ever control /~app/, which would make it useless for a push
   * arriving while nothing is open. It deliberately caches nothing: an offline cache that serves a
   * stale members list is worse than a page that says it cannot reach the server, and every page
   * here is one request against a database that is already local.
   */
  private void worker(DomainConfig config, ChannelHandlerContext ctx, FullHttpRequest req,
                      WebHandler.Recorder recorder) {
    Map<String, Object> model = new HashMap<>();
    model.put("shell", SHELL);
    model.put("community", config.name);
    byte[] script = templates.render("sw", model);
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/javascript; charset=utf-8", script,
        new String[]{HttpHeaderNames.CACHE_CONTROL.toString(), "no-cache",
            "Service-Worker-Allowed", "/"});
  }

  // ---- subscribing ------------------------------------------------------------------------------

  /**
   * How to install it, how to turn notifications on, and how to prove they work.
   *
   * <b>The self-test is the point of the page.</b> Everything else about push is somebody following
   * instructions and hoping: three permission prompts, an operating system that can silence a whole
   * app, and a phone with a focus mode on. Nobody finds out any of that has gone wrong until the
   * evening they needed to be told something and were not. A button that sends one right now, and a
   * page that says whether it arrived, is the difference between a feature people trust and a
   * feature people assume is broken.
   */
  private void help(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, WebHandler.Recorder recorder) throws SQLException {
    SessionRecord session = AccountRoutes.currentSession(accounts, req);
    String csrf = Cookies.stableToken(req);
    Map<String, Object> model = new HashMap<>();
    Chrome.site(model, config, accounts, req);
    model.put("title", "The app, and notifications");
    model.put("community", config.name);
    model.put("nav", Navigation.forRequest(config, accounts, req));
    model.put("csrf", csrf);
    model.put("nonce", Cookies.newCsrfToken());
    model.put("shell", SHELL);
    model.put("testUrl", SELF_TEST);
    model.put("pushUrl", SHELL + "/push");
    model.put("signedIn", session != null);
    model.put("loginUrl", Landing.carry(config.urls.login, HELP));
    int devices = session == null ? 0 : accounts.pushSubs.forUser(session.userId()).size();
    model.put("devices", devices);
    model.put("subscribed", devices > 0);
    // the key is minted per session, so there is nothing to offer somebody who is not signed in
    model.put("vapidKey", session == null ? ""
        : accounts.pushSubs.publicKeyFor(session.id(), session.userId()));
    // Said out loud rather than left to be discovered. Neither installing nor notifications work
    // over plain http -- browsers refuse both -- and a page of instructions that cannot possibly
    // work is worse than no page.
    model.put("secure", "https".equals(Canonical.scheme(ctx, req)));

    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
        templates.render("install", model),
        new String[]{HttpHeaderNames.SET_COOKIE.toString(), Cookies.csrf(accounts.security, csrf)},
        (String) model.get("nonce"));
  }

  /**
   * Send one to this browser, now.
   *
   * Answered as JSON so the page can say what happened without a reload -- and the page then waits
   * for the *worker* to tell it the message arrived, which is the only evidence that means
   * anything. "The server accepted it" and "the phone showed it" are different facts, and every
   * push problem lives in the gap between them.
   */
  private void selfTest(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                        FullHttpRequest req, WebHandler.Recorder recorder) throws SQLException {
    if (!HttpMethod.POST.equals(req.method())) {
      recorder.status(405);
      Responses.send(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, null, Responses.EMPTY);
      return;
    }
    SessionRecord session = AccountRoutes.currentSession(accounts, req);
    Forms form = Forms.of(req, Forms.MAX_CONTENT_BYTES);
    if (session == null || !Cookies.csrfMatches(form.get(Cookies.CSRF_FIELD),
        Forms.cookie(req, Cookies.CSRF_COOKIE))) {
      recorder.status(403);
      sendJson(ctx, req, recorder, 403, "{\"ok\":false,\"detail\":\"sign in first\"}");
      return;
    }
    java.util.List<io.hearth.push.PushSubs.Sub> subs = accounts.pushSubs.forUser(session.userId());
    if (subs.isEmpty()) {
      sendJson(ctx, req, recorder, 200, "{\"ok\":false,\"detail\":\"This browser has not"
          + " subscribed yet. Press the button above first, and say yes when it asks.\"}");
      return;
    }
    int sent = 0;
    String problem = null;
    for (io.hearth.push.PushSubs.Sub sub : subs) {
      io.hearth.push.WebPush.Outcome outcome = new io.hearth.push.WebPush(verbose).send(sub,
          new io.hearth.push.WebPush.Message(config.name,
              "Notifications are working. Nothing is wrong.", HELP, "selftest",
              session.userId()),
          "mailto:no-reply@" + config.domain);
      if (outcome.delivered()) {
        accounts.pushSubs.recordSuccess(sub.id());
        sent++;
      } else {
        problem = outcome.detail();
        accounts.pushSubs.recordFailure(sub.id(), outcome.gone(), outcome.detail());
      }
    }
    verbose.detail(() -> "push: self-test for " + session.userId());
    if (sent > 0) {
      sendJson(ctx, req, recorder, 200, "{\"ok\":true,\"devices\":" + sent + "}");
      return;
    }
    // the push service refused it, which the person cannot fix and the operator can: say what it
    // said rather than "something went wrong", because the answer is usually in that sentence
    ObjectNode failed = JSON.createObjectNode();
    failed.put("ok", false);
    failed.put("detail", "The push service would not take it: "
        + (problem == null ? "no reason given" : problem)
        + ". Turning notifications off and on again in this browser usually fixes it.");
    sendJson(ctx, req, recorder, 200, failed.toString());
  }

  private static void sendJson(ChannelHandlerContext ctx, FullHttpRequest req,
                               WebHandler.Recorder recorder, int status, String body) {
    recorder.status(status);
    Responses.send(ctx, req, HttpResponseStatus.valueOf(status),
        "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8),
        new String[]{HttpHeaderNames.CACHE_CONTROL.toString(), "no-store"});
  }

  private void push(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, WebHandler.Recorder recorder) throws SQLException {
    if (!HttpMethod.POST.equals(req.method())) {
      recorder.status(405);
      Responses.send(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, null, Responses.EMPTY);
      return;
    }
    SessionRecord session = AccountRoutes.currentSession(accounts, req);
    Forms form = Forms.of(req, Forms.MAX_CONTENT_BYTES);
    if (session == null
        || !Cookies.csrfMatches(form.get(Cookies.CSRF_FIELD), Forms.cookie(req, Cookies.CSRF_COOKIE))) {
      // no session means nothing to bind a subscription to, which is the whole design
      recorder.status(403);
      Responses.send(ctx, req, HttpResponseStatus.FORBIDDEN, null, Responses.EMPTY);
      return;
    }

    if ("off".equals(form.get("action"))) {
      int gone = accounts.pushSubs.forgetSession(session.id());
      verbose.detail(() -> "push: " + session.userId() + " unsubscribed (" + gone + ")");
      ok(ctx, req, recorder);
      return;
    }

    String endpoint = form.text("endpoint");
    String p256dh = form.get("p256dh");
    String auth = form.get("auth");
    if (endpoint == null || p256dh == null || auth == null || form.tooLong() != null
        || !endpoint.startsWith("https://")) {
      recorder.status(400);
      Responses.send(ctx, req, HttpResponseStatus.BAD_REQUEST, null, Responses.EMPTY);
      return;
    }
    accounts.pushSubs.subscribe(session.id(), session.userId(), endpoint.trim(), p256dh, auth);
    verbose.detail(() -> "push: session " + session.id() + " subscribed");
    ok(ctx, req, recorder);
  }

  private void ok(ChannelHandlerContext ctx, FullHttpRequest req, WebHandler.Recorder recorder) {
    recorder.status(204);
    Responses.send(ctx, req, HttpResponseStatus.NO_CONTENT, null, Responses.EMPTY);
  }

  private static String themeColor(Accounts accounts) {
    try {
      return accounts == null ? io.hearth.theme.Theme.SITE_LIGHT.bg()
          : accounts.themes.of(io.hearth.theme.Theme.Scope.site).light.bg();
    } catch (RuntimeException ex) {
      return io.hearth.theme.Theme.SITE_LIGHT.bg();
    }
  }

  /** twelve characters is what a phone shows under an icon before it gives up */
  public static String shortName(String name) {
    if (name == null || name.isBlank()) {
      return "Hearth";
    }
    String clean = name.trim();
    return clean.length() <= 12 ? clean : clean.substring(0, 12).trim();
  }
}
