package io.hearth.web;

import io.hearth.analytics.AccessLog;
import io.hearth.auth.Accounts;
import io.hearth.auth.AuthSystem;
import io.hearth.auth.SessionRecord;
import io.hearth.common.Verbose;
import io.hearth.content.Site;
import io.hearth.vhost.DomainConfig;
import io.hearth.vhost.DomainTree;
import io.hearth.vhost.Hosts;
import io.hearth.vhost.SiteUrls;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * The HTTP request path.
 *
 * The order is the point:
 *
 *   1. decode  -- a request the codec couldn't parse never reaches any logic
 *   2. version -- only wire versions we speak, so we never echo back a token the client invented
 *   3. shield  -- drop scanner noise before anything else runs
 *   4. method  -- only the verbs this server implements get past here
 *   5. host    -- canonicalize the Host header or refuse the request
 *   6. resolve -- descend the immutable in-memory label tree to the most specific config
 *   7. route   -- an account or admin page if the domain's config named this path
 *   8. content -- a page from the content table, rendered and cached
 *   9. serve   -- the built-in home page
 *
 * A domain with no config is not a 404 for a missing page; it is this server declining to know
 * anything about that name. There is no fallback host and no default site on purpose.
 *
 * Every response is recorded in the access log on the way out, including the user id behind the
 * session. That id is the reason session resolution happens here rather than deeper in: "which
 * member is doing this" is a question the analytics page cannot answer unless the request path
 * writes the answer down.
 */
public class WebHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
  private static final Logger LOG = LoggerFactory.getLogger(WebHandler.class);
  private static final String READ_METHODS = "GET, HEAD";
  private final DomainTree domains;
  private final AuthSystem auth;
  private final Pages pages;
  private final AccountRoutes accounts;
  private final AdminRoutes admin;
  private final SelfRoutes self;
  private final io.hearth.mcp.McpRoutes mcp;
  private final io.hearth.attach.AttachmentRoutes attachments;
  private final PwaRoutes pwa;
  private final io.hearth.legal.LegalRoutes legal;
  private final ThirdParty thirdParty;
  private final ThemeRoutes theme;
  private final io.hearth.certs.Challenges challenges;
  private final AccessLog accessLog;
  private final Verbose verbose;

  public WebHandler(DomainTree domains, AuthSystem auth, Pages pages, AccountRoutes accounts,
                    AdminRoutes admin, SelfRoutes self, io.hearth.mcp.McpRoutes mcp,
                    io.hearth.attach.AttachmentRoutes attachments,
                    PwaRoutes pwa,
                    io.hearth.legal.LegalRoutes legal,
                    io.hearth.certs.Challenges challenges,
                    AccessLog accessLog, Verbose verbose) {
    this.domains = domains;
    this.auth = auth;
    this.pages = pages;
    this.accounts = accounts;
    this.admin = admin;
    this.self = self;
    this.mcp = mcp;
    this.attachments = attachments;
    this.pwa = pwa;
    this.legal = legal;
    this.thirdParty = new ThirdParty(verbose);
    this.theme = new ThemeRoutes(verbose);
    this.challenges = challenges;
    this.accessLog = accessLog;
    this.verbose = verbose;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
    Recorder recorder = new Recorder(ctx, req, System.nanoTime());
    try {
      route(ctx, req, recorder);
    } finally {
      recorder.write();
    }
  }

  private void route(ChannelHandlerContext ctx, FullHttpRequest req, Recorder recorder) {
    String uri = req.uri();
    String rawHost = req.headers().get(HttpHeaderNames.HOST);
    verbose.say(() -> req.method().name() + " " + uri + " host=" + (rawHost == null ? "<none>" : rawHost) + " from " + ctx.channel().remoteAddress());

    // The domain is recorded before anything can refuse the request, because the refusals are the
    // interesting traffic. A scanner probing /wp-login.php is exactly what an operator wants to see
    // on their analytics page, and it would be invisible if the shield answered before the log knew
    // which site was being probed.
    String host = Hosts.normalize(rawHost);
    recorder.domain(host == null ? "" : host);

    if (req.decoderResult().isFailure()) {
      // a malformed request line or header block; the parse already failed, so nothing here is
      // trustworthy enough to route on
      verbose.detail("decoder failure: " + req.decoderResult() + " -> 400");
      recorder.status(400);
      Responses.send(ctx, req, HttpResponseStatus.BAD_REQUEST, null, Responses.EMPTY);
      return;
    }

    if (!Responses.isSupported(req.protocolVersion())) {
      verbose.detail("unsupported protocol version " + req.protocolVersion() + " -> 505");
      recorder.status(505);
      Responses.send(ctx, req, HttpResponseStatus.HTTP_VERSION_NOT_SUPPORTED, null, Responses.EMPTY);
      return;
    }

    // Before everything: the certificate authority's HTTP-01 check.
    //
    // It has to come first because every step below can refuse it for a reason that has nothing to
    // do with certificates -- an unconfigured Host, a domain marked disabled, the scanner shield
    // deciding a long random path looks like probing. A failed validation here is not a 404 an
    // operator can debug; it is an opaque CA error an hour later, and possibly a rate limit.
    //
    // It is safe this early because the token is the CA's own unguessable string and only ever
    // matches an order this server placed. Serving it to anybody who asks costs nothing.
    if (io.hearth.certs.Challenges.isChallenge(Forms.path(uri))) {
      String answer = challenges.answerFor(Forms.path(uri));
      recorder.status(answer == null ? 404 : 200);
      if (answer == null) {
        verbose.detail("acme challenge " + uri + " is not one we are expecting -> 404");
        Responses.send(ctx, req, HttpResponseStatus.NOT_FOUND, "text/plain", Responses.EMPTY);
      } else {
        verbose.say("certificates: answered " + uri);
        Responses.send(ctx, req, HttpResponseStatus.OK, "text/plain",
            answer.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      return;
    }

    if (WebRequestShield.block(uri)) {
      verbose.detail("shield blocked " + uri + " -> 410");
      recorder.status(410);
      Responses.send(ctx, req, HttpResponseStatus.GONE, null, Responses.EMPTY);
      return;
    }

    // Somebody else's JavaScript, from inside the jar. Answered before the host is resolved,
    // because these are the same public bytes for every community and depend on nothing about who
    // is asking -- putting it behind domain resolution and the approval gate meant an admin who was
    // not yet approved got the waiting page instead of the editor they were entitled to. After the
    // shield, though: unlike an ACME token there is nothing here worth an exception.
    if (ThirdParty.owns(Forms.path(uri))) {
      thirdParty.handle(ctx, req, recorder);
      return;
    }

    // ...and our own three lines that decide light or dark, for the same reason: the same bytes for
    // everybody, needed by the sign-in page and the terms as much as by the board
    if (ThemeRoutes.owns(Forms.path(uri))) {
      theme.handle(ctx, req, recorder);
      return;
    }

    if (Forms.path(uri).equals("/favicon.ico")) {
      // the page declares its icon as a data URI, so nothing should be asking for this. Browsers
      // ask anyway; answering 204 stops them rather than handing back a whole home page, which is
      // what used to happen and is not what a request budget of one looks like.
      verbose.detail("favicon -> 204");
      recorder.status(204);
      Responses.send(ctx, req, HttpResponseStatus.NO_CONTENT, null, Responses.EMPTY);
      return;
    }

    boolean readMethod = HttpMethod.GET.equals(req.method()) || HttpMethod.HEAD.equals(req.method());
    boolean post = HttpMethod.POST.equals(req.method());
    if (!readMethod && !post) {
      verbose.detail(req.method().name() + " is not implemented -> 405");
      recorder.status(405);
      Responses.send(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, null, Responses.EMPTY,
          new String[]{HttpHeaderNames.ALLOW.toString(), READ_METHODS});
      return;
    }

    if (host == null) {
      verbose.detail("host header is not a usable domain -> 400");
      recorder.status(400);
      Responses.sendHtml(ctx, req, HttpResponseStatus.BAD_REQUEST, pages.badHost());
      return;
    }
    if (verbose.on && !host.equals(rawHost)) {
      verbose.detail("host normalized to " + host);
    }

    DomainConfig config = domains.resolve(host);
    if (verbose.on) {
      for (String line : domains.explain(host)) {
        verbose.detail(line);
      }
    }

    if (config == null || !config.enabled) {
      verbose.detail((config == null ? "no configuration for " + host : config.domain + " is disabled") + " -> 404");
      recorder.status(404);
      Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, pages.notFound());
      return;
    }
    recorder.domain(config.domain);

    // One community, one address. Everything that resolved here under another name -- a listed
    // subdomain, a wildcard sweeping up www -- is sent to the domain the config is named for,
    // keeping the scheme, the port, the path and the query. See Canonical for why each of those
    // matters, and note that the ACME challenge, the pixel and /3rd have already been answered
    // above: an authority validating www fetches its token from www, and a redirect is not an
    // answer to that.
    if (!host.equals(config.domain)) {
      String target = Canonical.location(Canonical.scheme(ctx, req), config.domain, rawHost, uri);
      if (target == null) {
        verbose.detail("cannot canonicalize " + uri + " from " + host + " -> 400");
        recorder.status(400);
        Responses.sendHtml(ctx, req, HttpResponseStatus.BAD_REQUEST, pages.badHost());
        return;
      }
      verbose.detail(() -> host + " is not " + config.domain + " -> 308 " + target);
      recorder.status(308);
      Responses.send(ctx, req, HttpResponseStatus.PERMANENT_REDIRECT, null, Responses.EMPTY,
          new String[]{HttpHeaderNames.LOCATION.toString(), target});
      return;
    }

    Accounts accountsForDomain = auth.forDomain(config.domain);
    // resolved once and reused: the nav needs it, the admin section needs it, and the access log
    // needs the user id behind it
    SessionRecord session = AccountRoutes.currentSession(accountsForDomain, req);
    recorder.user(session == null ? null : session.userId());

    String path = Forms.path(uri);
    // A session whose account is gone -- rejected, deleted -- is signed out, and is told so.
    //
    // It used to fall into the approval gate below, which asks "is this person approved", gets a
    // no for somebody who no longer exists, and shows the waiting page: a person waiting forever
    // for a decision about an account that is not there. The cookie is cleared on the way past so
    // the next request is honestly anonymous, and the destination rides along like any other
    // refusal, because from where they are standing this is being signed out.
    if (session != null && accountsForDomain != null && missingAccount(accountsForDomain, session)) {
      verbose.detail("session " + session.userId() + " has no account -> signed out");
      recorder.status(303);
      Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY, new String[]{
          HttpHeaderNames.LOCATION.toString(),
          Landing.carry(config.urls.login, Landing.here(req)),
          HttpHeaderNames.SET_COOKIE.toString(),
          Cookies.clearSession(accountsForDomain.security)});
      return;
    }
    // Uploaded files, before the approval gate on purpose.
    //
    // A public attachment is public -- an anonymous browser asking for one is the ordinary case,
    // and a poster on the front page must not stop rendering for somebody whose account has not
    // been approved yet. The route does its own check, which is stricter than this one: private
    // means an approved member, and it answers 404 rather than a waiting page to everybody else.
    if (accountsForDomain != null && config.has(io.hearth.vhost.Surface.attachments)
        && io.hearth.attach.AttachmentRoutes.owns(path)) {
      verbose.detail("attachment " + path + " on " + config.domain);
      attachments.handle(config, accountsForDomain, ctx, req, recorder);
      return;
    }
    if (session != null && accountsForDomain != null && !isAlwaysReachable(config, path)
        && !approved(accountsForDomain, session)) {
      // signed in but not approved: they can write a profile and answer questions, and that is all.
      // The community itself is what approval gates.
      verbose.detail("session " + session.userId() + " is not approved -> waiting page");
      recorder.status(200);
      Responses.sendHtml(ctx, req, HttpResponseStatus.OK, pages.waiting(config, accountsForDomain, config.urls.self));
      return;
    }
    // The terms and the privacy policy, open to anybody. Every email this server sends links to
    // them and most of those go to somebody with no account yet, so putting them behind the sign-in
    // would make "these are the terms you are accepting" a link to a login form.
    if (io.hearth.legal.LegalRoutes.owns(path) && !post) {
      verbose.detail("legal " + path + " on " + config.domain);
      legal.handle(config, accountsForDomain, ctx, req, recorder);
      return;
    }
    if (accountsForDomain != null && io.hearth.mcp.McpRoutes.owns(config, path)) {
      verbose.detail("mcp " + path + " on " + config.domain);
      mcp.handle(config, accountsForDomain, ctx, req, recorder);
      return;
    }
    if (accountsForDomain != null && isAdminPath(config, path)) {
      verbose.detail("admin " + path + " on " + config.domain);
      admin.handle(config, accountsForDomain, ctx, req, recorder);
      return;
    }





    // The app shell, the manifest and the service worker. After the approval gate on purpose: an
    // unapproved person gets the waiting page in the shell exactly as they do on the site, rather
    // than an installed app that looks like it works.
    if (accountsForDomain != null && config.has(io.hearth.vhost.Surface.app)
        && PwaRoutes.owns(path)) {
      verbose.detail("pwa " + path + " on " + config.domain);
      pwa.handle(config, accountsForDomain, ctx, req, recorder);
      return;
    }

    SiteUrls.Route route = config.routes.get(path);
    if (route == SiteUrls.Route.self && accountsForDomain != null) {
      self.handle(config, accountsForDomain, ctx, req, recorder);
      return;
    }
    // only the forms this class knows how to render. A section whose surface is switched off is
    // still in the table -- that is what stops two of them sharing an address -- and used to fall
    // in here, where nothing answered and the connection was held until the browser gave up.
    if (route != null && route.isAccountPage() && accountsForDomain != null) {
      verbose.detail("account route " + route + " on " + config.domain);
      if (route == SiteUrls.Route.logout && !post) {
        // signing out changes state, so it needs a POST with a token rather than a link somebody
        // else's page can point a browser at
        recorder.status(405);
        Responses.send(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, null, Responses.EMPTY,
            new String[]{HttpHeaderNames.ALLOW.toString(), "POST"});
        return;
      }
      accounts.handle(route, config, accountsForDomain, ctx, req, recorder);
      return;
    }

    // a mutation, if this community has declared one at this address
    if (post && accountsForDomain != null
        && runMutation(ctx, req, recorder, config, accountsForDomain, path)) {
      return;
    }

    if (post) {
      verbose.detail("POST to " + uri + " which is not a form endpoint -> 405");
      recorder.status(405);
      Responses.send(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, null, Responses.EMPTY,
          new String[]{HttpHeaderNames.ALLOW.toString(), READ_METHODS});
      return;
    }

    // a page from the content table, if one answers this path
    if (accountsForDomain != null) {
      // Minted before the render, because the page may put it in a form.
      //
      // Stable: an existing well-formed cookie is reused, so a member with several tabs open does
      // not have each one invalidating the others' forms. The cookie goes back on every content
      // response rather than only when a form was rendered -- the alternative is knowing what the
      // page produced before deciding a header, which means parsing our own output.
      String pageCsrf = Cookies.stableToken(req);
      Site.Rendered page =
          accountsForDomain.site.page(path, pageContext(accountsForDomain, req, pageCsrf));
      if (page != null) {
        verbose.detail(() -> "content " + path + " -> 200 (" + page.html().length + " bytes)");
        recorder.status(200);
        Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8", page.html(),
            new String[]{HttpHeaderNames.SET_COOKIE.toString(),
                Cookies.csrf(accountsForDomain.security, pageCsrf)});
        return;
      }
      // and then a listing, if a template publishes one at this address. After the page lookup on
      // purpose: a real page at /blog wins over a listing that wanted to be there, because the
      // page is the thing somebody wrote and the listing is a property of a setting.
      Site.Rendered listing = accountsForDomain.site.directory(path, Forms.queryString(uri));
      if (listing != null) {
        verbose.detail(() -> "directory " + path + " -> 200 (" + listing.html().length + " bytes)");
        recorder.status(200);
        Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
            listing.html());
        return;
      }
    }

    // Nothing answered, and only one address is allowed to fall through to the placeholder.
    //
    // Every other path used to be served the community's front page with a 200, which is a lie to
    // a person (the link they followed appears to work and shows something else), a lie to a search
    // engine (every typo is a page) and a lie to anything automated (a 404 is how a client learns
    // an address is wrong). A community that has written a front page has one at "/" like any other
    // page; this is what is there before they have.
    if (!path.equals("/")) {
      verbose.detail(() -> "nothing answers " + path + " on " + config.domain + " -> 404");
      recorder.status(404);
      Responses.send(ctx, req, HttpResponseStatus.NOT_FOUND, "text/html; charset=utf-8",
          accountsForDomain == null ? pages.notFound()
              : pages.missing(config, accountsForDomain, req));
      return;
    }
    verbose.detail("serving " + config.domain + " (" + config.name + ") -> 200");
    recorder.status(200);
    if (accountsForDomain == null) {
      Responses.sendHtml(ctx, req, HttpResponseStatus.OK,
          pages.hello(config, null, req, verbose.on, null));
      return;
    }
    // the nav can carry a sign-out form, so every page hands out a token and the cookie to match.
    // The browser's existing token is reused: a fresh one here would overwrite the cookie that a
    // form open in another tab is relying on.
    String csrf = Cookies.stableToken(req);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
        pages.hello(config, accountsForDomain, req, verbose.on, csrf),
        new String[]{HttpHeaderNames.SET_COOKIE.toString(),
            Cookies.csrf(accountsForDomain.security, csrf)});
  }

  /**
   * Paths an unapproved person may still use: their own page, and anything to do with their account.
   *
   * The admin section is on the list because it does its own, stricter check -- an unapproved person
   * gets a 404 from it, which is the same answer everybody who is not an admin gets.
   */
  /**
   * What a dynamic page on this domain may reach, for this one request.
   *
   * Built per request because both halves are per request: the query string is, and the set of
   * tables is whatever the community has right now. A domain whose data file would not open gets
   * the empty context -- its pages still render, they just have no tables, which is a far better
   * outcome than the site being down because somebody's table would not load.
   */
  /**
   * Run a mutation, if one is declared at this address and this request may run it.
   *
   * <b>Three gates, and each is a different question.</b> A signed-in, approved member, because a
   * public POST that writes is a queue somebody else fills. The CSRF token, because otherwise
   * another site's form can post here with a member's cookies. And `enabled`, because a mutation
   * halfway through being written should not be answering anything.
   *
   * <b>Returns false when there is no mutation here</b>, so the 405 below still happens for an
   * address that is a page -- a POST to a page is a mistake and should say so, rather than becoming
   * a 404 that reads like the page is gone.
   */
  private boolean runMutation(ChannelHandlerContext ctx, FullHttpRequest req, Recorder recorder,
                              DomainConfig config, io.hearth.auth.Accounts accounts, String path) {
    io.hearth.content.Mutations.Record mutation;
    io.hearth.auth.UserRecord me;
    try {
      mutation = accounts.mutations.byUri(path);
      if (mutation == null) {
        return false;
      }
      SessionRecord session = AccountRoutes.currentSession(accounts, req);
      me = session == null ? null : accounts.users.byId(session.userId());
    } catch (java.sql.SQLException ex) {
      LOG.error("mutation-lookup-failed", ex);
      return false;
    }
    if (!mutation.enabled()) {
      // it exists and is switched off. A 404 rather than a 403: whether a draft mutation exists at
      // this address is not something an anonymous POST should be able to discover.
      verbose.detail(() -> "mutation " + path + " is not enabled -> 404");
      recorder.status(404);
      Responses.send(ctx, req, HttpResponseStatus.NOT_FOUND, "text/html; charset=utf-8",
          pages.missing(config, accounts, req));
      return true;
    }
    if (me == null || !me.isApproved()) {
      verbose.detail(() -> "mutation " + path + " refused: not an approved member");
      recorder.status(403);
      sendJson(ctx, req, HttpResponseStatus.FORBIDDEN,
          "{\"success\":false,\"reasons\":[\"you have to be a signed-in member to do that\"]}");
      return true;
    }
    Forms form = Forms.of(req, Forms.MAX_CONTENT_BYTES);
    if (!Cookies.csrfMatches(form.get(Cookies.CSRF_FIELD),
        Forms.cookie(req, Cookies.CSRF_COOKIE))) {
      recorder.status(403);
      sendJson(ctx, req, HttpResponseStatus.FORBIDDEN,
          "{\"success\":false,\"reasons\":[\"that form expired; please try again\"]}");
      return true;
    }
    io.hearth.tables.TableBindings bindings = accounts.tables == null ? null
        : new io.hearth.tables.TableBindings(accounts.tables, true, me.id());
    String prologue = bindings == null ? "" : bindings.prologue(Forms.queryParameters(req.uri()))
        + bindings.formPrologue(form.all());
    io.hearth.js.JavaScript.Run run = io.hearth.js.JavaScript.shared().run(mutation.body(),
        new io.hearth.js.JavaScript.Page(prologue,
            bindings == null ? request -> "null" : bindings));
    verbose.detail(() -> "mutation " + path + " by " + me.email()
        + (run.failed() ? " failed -- " + run.error() : " ok in " + run.millis() + "ms"));
    if (run.failed()) {
      recorder.status(500);
      sendJson(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          io.hearth.tables.UserTables.toJson(java.util.Map.of("success", false,
              "reasons", java.util.List.of(run.error()))));
      return true;
    }
    // Where to go afterwards is the mutation's decision, and both answers are useful.
    //
    // meta('redirect', '/thanks') is what a form wants: a 303 so a refresh cannot repeat the write,
    // which is the same rule every admin POST follows. Rendering something instead is what a script
    // wants. Neither is the default, because guessing wrong is either a lost answer or a lost page.
    String redirect = run.meta().get("redirect");
    if (redirect != null && !redirect.isBlank()) {
      // through Landing.safe, because "we generated it" is exactly the assumption that turns a
      // redirect into a header injection -- and this one was written by whoever wrote the mutation
      String where = Landing.safe(redirect);
      if (where == null) {
        where = "/";
      }
      recorder.status(303);
      Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
          new String[]{HttpHeaderNames.LOCATION.toString(), where});
      return true;
    }
    recorder.status(200);
    if (run.body() != null && !run.body().isEmpty()) {
      Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
          run.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } else {
      sendJson(ctx, req, HttpResponseStatus.OK, "{\"success\":true}");
    }
    return true;
  }

  /** a mutation answers JSON when it does not render or redirect */
  private static void sendJson(ChannelHandlerContext ctx, FullHttpRequest req,
                               HttpResponseStatus status, String json) {
    Responses.send(ctx, req, status, "application/json; charset=utf-8",
        json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static Site.PageContext pageContext(io.hearth.auth.Accounts accounts,
                                              FullHttpRequest req, String csrf) {
    if (accounts.tables == null) {
      return Site.PageContext.NONE;
    }
    io.hearth.tables.TableBindings bindings =
        new io.hearth.tables.TableBindings(accounts.tables);
    // csrf() so a page can render a form that a mutation will accept. A page that never renders one
    // simply never calls it; handing it over is not a decision, because the token is already in the
    // reader's own cookie jar and proves nothing about anybody else.
    String prologue = bindings.prologue(Forms.queryParameters(req.uri()))
        + "function csrf(){return " + io.hearth.tables.UserTables.toJson(csrf) + ";}";
    return new Site.PageContext() {
      @Override
      public String prologue() {
        return prologue;
      }

      @Override
      public io.hearth.js.JavaScript.Host host() {
        return bindings;
      }
    };
  }

  private static boolean isAlwaysReachable(DomainConfig config, String path) {
    if (isAdminPath(config, path) || io.hearth.mcp.McpRoutes.owns(config, path)) {
      return true;
    }
    if (io.hearth.legal.LegalRoutes.owns(path)) {
      // what somebody is agreeing to cannot be behind the approval they are waiting for
      return true;
    }
    if (PwaRoutes.owns(path)) {
      // the shell, the manifest and the worker answer for anybody; the shell frames whatever the
      // person is actually allowed to see, so the gate belongs on the page inside it
      return true;
    }
    SiteUrls.Route route = config.routes.get(path);
    return route != null && route.isReachableUnapproved();
  }

  /**
   * Did the account behind this session go away?
   *
   * A database problem answers no rather than yes: a lookup that failed is not evidence that
   * somebody was deleted, and signing the whole community out because H2 hiccupped would be a
   * worse failure than the one this is guarding against.
   */
  private boolean missingAccount(Accounts accounts, SessionRecord session) {
    try {
      return accounts.users.byId(session.userId()) == null;
    } catch (java.sql.SQLException ex) {
      LOG.error("account-check-failed", ex);
      return false;
    }
  }

  /** one indexed lookup per request for a signed-in person; the row is what says yes or no */
  private boolean approved(Accounts accounts, SessionRecord session) {
    try {
      return accounts.access.isApproved(accounts.users.byId(session.userId()));
    } catch (java.sql.SQLException ex) {
      LOG.error("approval-check-failed", ex);
      return false;
    }
  }

  /** the admin section owns its path and everything under it, so it can have sub-pages */
  static boolean isAdminPath(DomainConfig config, String path) {
    String admin = config.urls.admin;
    return path.equals(admin) || path.startsWith(admin + "/");
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    // never echo an exception to a client; it goes to the log and the socket goes away
    LOG.error("web-handler-exception", cause);
    verbose.detail("exception on " + ctx.channel().remoteAddress() + ": " + cause.getMessage());
    ctx.close();
  }

  /**
   * Collects what the access log wants, and writes exactly one entry per request.
   *
   * Handlers call {@link #status} as they answer. The alternative -- reading the code back off the
   * response object -- would mean threading it through every send, and forgetting one would silently
   * drop a request from the log rather than showing it with the wrong code.
   */
  public class Recorder {
    private final ChannelHandlerContext ctx;
    private final FullHttpRequest req;
    private final long startedAt;
    private String domain = "";
    private Long userId;
    private int status;
    private boolean written;

    Recorder(ChannelHandlerContext ctx, FullHttpRequest req, long startedAt) {
      this.ctx = ctx;
      this.req = req;
      this.startedAt = startedAt;
      this.status = 0;
      this.written = false;
    }

    public void status(int status) {
      this.status = status;
    }

    public void domain(String domain) {
      this.domain = domain;
    }

    public void user(Long userId) {
      this.userId = userId;
    }

    public Long user() {
      return userId;
    }

    void write() {
      if (written || accessLog == null) {
        return;
      }
      written = true;
      long micros = (System.nanoTime() - startedAt) / 1000;
      // a handler that answered without saying what it answered with is a bug, and 500 is the
      // reading that makes it visible rather than the one that hides it
      accessLog.record(System.currentTimeMillis(), domain, req.method().name(), req.uri(),
          status == 0 ? 500 : status, micros, ip(), userId,
          req.headers().get(HttpHeaderNames.USER_AGENT),
          req.headers().get(HttpHeaderNames.REFERER));
    }

    private String ip() {
      if (ctx.channel().remoteAddress() instanceof InetSocketAddress address && address.getAddress() != null) {
        return address.getAddress().getHostAddress();
      }
      return null;
    }
  }
}
