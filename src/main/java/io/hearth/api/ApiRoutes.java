package io.hearth.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.auth.Accounts;
import io.hearth.auth.Permission;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.content.Bundle;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.hearth.web.AccountRoutes;
import io.hearth.web.Chrome;
import io.hearth.web.Cookies;
import io.hearth.web.Flash;
import io.hearth.web.Forms;
import io.hearth.web.Landing;
import io.hearth.web.Navigation;
import io.hearth.web.Responses;
import io.hearth.web.WebHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The door a program comes in through, and the screen a person makes its key on.
 *
 * Two halves that must not be confused, and the distinction is the security property:
 *
 * <ul>
 *   <li><b>`/api`</b> is a page for a person, authenticated by the session cookie like every other
 *       page, where a token is minted and revoked.</li>
 *   <li><b>`/api/v1/...`</b> is for a program, authenticated by `Authorization: Bearer` and
 *       <i>never</i> by a cookie. That is not a detail: a JSON endpoint that accepted the browser's
 *       cookie would be a cross-site request forgery hole with no form and no token to protect it,
 *       reachable from any page on the internet a member happens to have open.</li>
 * </ul>
 *
 * <b>The token is copied by hand, on purpose.</b> A CLI prints an address, somebody opens it, reads
 * what is being asked for, presses a button and copies a string back. No callback, no local
 * listener, no redirect: the whole flow works from a machine with no browser on it, over SSH, and
 * from a phone -- and there is nothing to get wrong about which program received what, because a
 * person moved the string themselves.
 *
 * <b>An API token can never do more than the person holding it.</b> Every endpoint asks the same
 * `Access.can` the admin screen asks, of the same account. A token is a different keyboard, not a
 * different identity.
 */
public class ApiRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(ApiRoutes.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  /** the version in the path, so a CLI written today keeps working when a second one appears */
  public static final String V1 = ApiConfig.PATH + "/v1";

  private final Templates templates;
  private final Flash flash;
  private final Verbose verbose;

  public ApiRoutes(Templates templates, Flash flash, Verbose verbose) {
    this.templates = templates;
    this.flash = flash;
    this.verbose = verbose;
  }

  /** everything under /api belongs here, so the request path routes without guessing */
  public static boolean owns(String path) {
    return path.equals(ApiConfig.PATH) || path.startsWith(ApiConfig.PATH + "/");
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    String path = Forms.path(req.uri());
    try {
      if (path.equals(V1) || path.startsWith(V1 + "/")) {
        program(config, accounts, ctx, req, path, recorder);
        return;
      }
      person(config, accounts, ctx, req, recorder);
    } catch (SQLException ex) {
      LOG.error("api-failed path={}", path, ex);
      recorder.status(500);
      sendJson(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          problem("server_error", "that did not work, and it is our fault rather than yours"));
    }
  }

  // ---- the half a person uses ------------------------------------------------------------------

  private void person(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                      FullHttpRequest req, WebHandler.Recorder recorder) throws SQLException {
    SessionRecord session = AccountRoutes.currentSession(accounts, req);
    UserRecord me = session == null ? null : accounts.users.byId(session.userId());
    if (me == null) {
      // the errand rides along: somebody following a URL their CLI printed is mid-task, and
      // landing them on a home page means going back to the terminal to copy it again
      recorder.status(303);
      Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
          new String[]{HttpHeaderNames.LOCATION.toString(),
              Landing.carry(config.urls.login, Landing.here(req))});
      return;
    }
    String csrf = Cookies.stableToken(req);
    if (HttpMethod.POST.equals(req.method())) {
      act(config, accounts, ctx, req, me, session, recorder);
      return;
    }

    Map<String, Object> model = new HashMap<>();
    Chrome.site(model, config, accounts, req);
    model.put("title", "Programs");
    model.put("community", config.name);
    model.put("nav", Navigation.forRequest(config, accounts, req));
    model.put("csrf", csrf);
    model.put("action", ApiConfig.PATH);
    model.put("selfUrl", config.urls.self);
    model.put("base", "https://" + config.domain + V1);
    model.put("tokenDays", config.api.tokenDays);
    model.put("expires", config.api.tokenDays > 0);
    model.put("maxTokens", config.api.maxTokens);

    // what a CLI asked to be called, if this is the address one printed
    String asking = Forms.query(req.uri(), "name");
    model.put("asking", asking == null ? "" : ApiTokens.label(asking));
    model.put("wasAsked", asking != null && !asking.isBlank());

    List<ApiTokens.Token> tokens = ApiTokens.of(accounts, me.id());
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (ApiTokens.Token token : tokens) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", token.id());
      row.put("label", token.label());
      row.put("made", when(token.createdAt()));
      row.put("used", token.lastSeenAt() == token.createdAt() ? "" : when(token.lastSeenAt()));
      row.put("expires", token.expires() ? when(token.expiresAt()) : "");
      row.put("forever", !token.expires());
      rows.add(row);
    }
    model.put("tokens", rows);
    model.put("anyTokens", !rows.isEmpty());
    model.put("atLimit", tokens.size() >= config.api.maxTokens);
    model.put("canWriteContent", accounts.access.can(me, Permission.content_write));

    Map<String, Object> message = flash.take(session.tokenHash());
    if (message != null) {
      model.put("flash", message);
      // shown exactly once and never in a URL: this is the only moment the token exists anywhere a
      // person can read it, because what is stored is a hash
      model.put("newToken", message.get("secret"));
    }
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
        templates.render("api", model),
        new String[]{HttpHeaderNames.SET_COOKIE.toString(), Cookies.csrf(accounts.security, csrf)});
  }

  private void act(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, UserRecord me, SessionRecord session,
                   WebHandler.Recorder recorder) throws SQLException {
    Forms form = Forms.of(req);
    if (form.bodyTooLarge() || !Cookies.csrfMatches(form.get(Cookies.CSRF_FIELD),
        Forms.cookie(req, Cookies.CSRF_COOKIE))) {
      redirect(ctx, req, recorder, ApiConfig.PATH);
      return;
    }
    String action = String.valueOf(form.get("action"));
    if (action.equals("revoke")) {
      Long id = longOf(form.get("token"));
      boolean gone = id != null && ApiTokens.revoke(accounts, me.id(), id);
      flash.set(session.tokenHash(), gone
          ? "That token is gone. Anything using it will stop at its next request."
          : "That token could not be found.", !gone);
      redirect(ctx, req, recorder, ApiConfig.PATH);
      return;
    }
    if (!action.equals("authorize")) {
      flash.set(session.tokenHash(), "That is not something this page can do.", true);
      redirect(ctx, req, recorder, ApiConfig.PATH);
      return;
    }
    ApiTokens.Minted minted = ApiTokens.mint(accounts, config.api, me.id(), form.get("name"));
    if (!minted.ok()) {
      flash.set(session.tokenHash(), minted.problem(), true);
      redirect(ctx, req, recorder, ApiConfig.PATH);
      return;
    }
    verbose.say("api: " + me.email() + " authorized '" + minted.record().label() + "'");
    flash.set(session.tokenHash(),
        "Here is the token for " + minted.record().label() + ". Copy it now -- this is the only"
            + " time it is shown, because what is stored is a hash of it.",
        false, minted.token());
    redirect(ctx, req, recorder, ApiConfig.PATH);
  }

  // ---- the half a program uses -----------------------------------------------------------------

  /**
   * Everything here is bearer-only, and the cookie is deliberately not consulted.
   *
   * A JSON endpoint that accepted the browser's session would be a forgery hole with no form to put
   * a token in: any page anywhere could POST a bundle to a community a member happens to be signed
   * in to. So the credential is the header, and a request without one is a 401 that says how to get
   * one -- which is also what a CLI needs to print.
   */
  private void program(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                       FullHttpRequest req, String path, WebHandler.Recorder recorder)
      throws SQLException {
    String bearer = bearerOf(req);
    SessionRecord session = bearer == null ? null : accounts.sessions.resolve(bearer);
    UserRecord me = session == null ? null : accounts.users.byId(session.userId());
    boolean isApiToken = session != null && session.robot() && session.agent() != null
        && session.agent().startsWith(ApiConfig.AGENT_PREFIX);
    if (!isApiToken || me == null || me.disabled() || !accounts.access.isApproved(me)) {
      recorder.status(401);
      sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED,
          problem("unauthorized", "this needs an API token: open https://" + config.domain
              + ApiConfig.PATH + " to make one"),
          new String[]{HttpHeaderNames.WWW_AUTHENTICATE.toString(), "Bearer"});
      return;
    }
    String rest = path.equals(V1) ? "" : path.substring(V1.length() + 1);
    switch (rest) {
      case "", "whoami" -> whoami(config, accounts, ctx, req, me, session, recorder);
      case "content" -> content(config, accounts, ctx, req, me, recorder);
      default -> {
        recorder.status(404);
        sendJson(ctx, req, HttpResponseStatus.NOT_FOUND,
            problem("no_such_endpoint", "this server has nothing at " + path));
      }
    }
  }

  /**
   * Who this token is, and what it may do.
   *
   * The first thing any CLI needs and the last thing anybody thinks to build: "am I authenticated,
   * as whom, until when, and what will you let me do" answered in one request, so a tool can say
   * that rather than making somebody find out from a 403 halfway through a push.
   */
  private void whoami(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                      FullHttpRequest req, UserRecord me, SessionRecord session,
                      WebHandler.Recorder recorder) throws SQLException {
    if (!HttpMethod.GET.equals(req.method()) && !HttpMethod.HEAD.equals(req.method())) {
      methodNotAllowed(ctx, req, recorder, "GET");
      return;
    }
    ObjectNode out = JSON.createObjectNode();
    out.put("community", config.name);
    out.put("domain", config.domain);
    out.put("email", me.email());
    out.put("token", session.agent().substring(ApiConfig.AGENT_PREFIX.length()));
    out.put("expires_at", session.expiresAt() == SessionRecord.NEVER ? null
        : java.time.Instant.ofEpochMilli(session.expiresAt()).toString());
    ArrayNode can = out.putArray("can");
    for (Permission permission : Permission.values()) {
      if (accounts.access.can(me, permission)) {
        can.add(permission.name());
      }
    }
    ArrayNode endpoints = out.putArray("endpoints");
    endpoints.add("GET " + V1 + "/whoami");
    endpoints.add("GET " + V1 + "/content");
    endpoints.add("POST " + V1 + "/content");
    recorder.status(200);
    sendJson(ctx, req, HttpResponseStatus.OK, out);
  }

  /**
   * The content bundle, out and in.
   *
   * A push is a *merge that only writes what differs* and says what it did, which is the whole
   * reason this exists rather than a form: a tool that pushes a repository every time it changes
   * would otherwise fill the version history with edits nobody made, and a diff nobody can see
   * before it lands is a diff nobody reviews. `?dry=1` answers the same JSON and writes nothing.
   */
  private void content(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                       FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder)
      throws SQLException {
    if (HttpMethod.GET.equals(req.method()) || HttpMethod.HEAD.equals(req.method())) {
      if (!accounts.access.can(me, Permission.content_write)) {
        forbid(ctx, req, recorder, "reading the whole site needs 'write pages': it is every page,"
            + " including the drafts and the ones locked away from AI");
        return;
      }
      recorder.status(200);
      Responses.send(ctx, req, HttpResponseStatus.OK, "application/json; charset=utf-8",
          Bundle.of(accounts.site.store(), config.name, config.domain,
              java.time.Instant.now().toString(), null));
      return;
    }
    if (!HttpMethod.POST.equals(req.method())) {
      methodNotAllowed(ctx, req, recorder, "GET, POST");
      return;
    }
    if (!accounts.access.can(me, Permission.content_write)
        || !accounts.access.can(me, Permission.content_publish)) {
      forbid(ctx, req, recorder, "pushing content writes pages and puts them live, so it needs"
          + " both 'write pages' and 'publish pages'");
      return;
    }
    String body = req.content().toString(StandardCharsets.UTF_8);
    if (body.isBlank()) {
      recorder.status(400);
      sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST,
          problem("empty", "the body is the bundle: {\"content\": [ ... ]}"));
      return;
    }
    boolean dry = "1".equals(Forms.query(req.uri(), "dry"))
        || "true".equals(Forms.query(req.uri(), "dry"));
    Bundle.Report report = Bundle.apply(accounts.site.store(), body, me.id(), me.email(), dry);
    if (report.total() == 0 && !report.problems().isEmpty() && report.pages().isEmpty()) {
      recorder.status(400);
      sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST,
          problem("bad_bundle", String.join("; ", report.problems())));
      return;
    }
    verbose.say("api: " + me.email() + (dry ? " asked what would change -- " : " pushed content -- ")
        + report.describe());
    recorder.status(200);
    sendJson(ctx, req, HttpResponseStatus.OK, asJson(report));
  }

  /** what changed, row by row, in the shape a script wants to read */
  static ObjectNode asJson(Bundle.Report report) {
    ObjectNode out = JSON.createObjectNode();
    out.put("ok", true);
    out.put("dry_run", report.dryRun());
    out.set("content", changes(report.pages()));
    out.set("templates", changes(report.templates()));
    ObjectNode summary = out.putObject("summary");
    summary.put("created", report.pagesAdded());
    summary.put("updated", report.pagesUpdated());
    summary.put("unchanged", report.pagesUnchanged());
    summary.put("templates_created", report.templatesAdded());
    summary.put("templates_updated", report.templatesUpdated());
    summary.put("templates_unchanged", report.templatesUnchanged());
    ArrayNode notes = out.putArray("notes");
    for (String problem : report.problems()) {
      notes.add(problem);
    }
    return out;
  }

  private static ArrayNode changes(List<Bundle.Change> rows) {
    ArrayNode out = JSON.createArrayNode();
    for (Bundle.Change change : rows) {
      ObjectNode row = out.addObject();
      row.put("uuid", change.uuid());
      row.put("name", change.name());
      row.put("status", change.status());
      ArrayNode changed = row.putArray("changed");
      for (String field : change.changed()) {
        changed.add(field);
      }
    }
    return out;
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private static String bearerOf(FullHttpRequest req) {
    String header = req.headers().get(HttpHeaderNames.AUTHORIZATION);
    if (header == null || header.length() > 4096) {
      return null;
    }
    String trimmed = header.trim();
    if (trimmed.length() < 8 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return null;
    }
    String token = trimmed.substring(7).trim();
    return token.isEmpty() ? null : token;
  }

  private void forbid(ChannelHandlerContext ctx, FullHttpRequest req, WebHandler.Recorder recorder,
                      String why) {
    recorder.status(403);
    sendJson(ctx, req, HttpResponseStatus.FORBIDDEN, problem("not_allowed", why));
  }

  private void methodNotAllowed(ChannelHandlerContext ctx, FullHttpRequest req,
                                WebHandler.Recorder recorder, String allowed) {
    recorder.status(405);
    Responses.send(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, "application/json",
        problem("wrong_method", "this endpoint answers " + allowed).toString()
            .getBytes(StandardCharsets.UTF_8),
        new String[]{HttpHeaderNames.ALLOW.toString(), allowed});
  }

  private static ObjectNode problem(String code, String detail) {
    ObjectNode out = JSON.createObjectNode();
    out.put("ok", false);
    out.put("error", code);
    out.put("detail", detail);
    return out;
  }

  private static void sendJson(ChannelHandlerContext ctx, FullHttpRequest req,
                               HttpResponseStatus status, JsonNode body) {
    sendJson(ctx, req, status, body, null);
  }

  private static void sendJson(ChannelHandlerContext ctx, FullHttpRequest req,
                               HttpResponseStatus status, JsonNode body, String[] headers) {
    byte[] bytes = body.toPrettyString().getBytes(StandardCharsets.UTF_8);
    if (headers == null) {
      Responses.send(ctx, req, status, "application/json; charset=utf-8", bytes);
    } else {
      Responses.send(ctx, req, status, "application/json; charset=utf-8", bytes, headers);
    }
  }

  private static void redirect(ChannelHandlerContext ctx, FullHttpRequest req,
                               WebHandler.Recorder recorder, String where) {
    recorder.status(303);
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
        new String[]{HttpHeaderNames.LOCATION.toString(), where});
  }

  private static Long longOf(String raw) {
    try {
      return raw == null ? null : Long.parseLong(raw.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static String when(long millis) {
    return millis <= 0 ? ""
        : java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().toString();
  }
}
