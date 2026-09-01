package io.hearth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.auth.Accounts;
import io.hearth.auth.LoginSecurity;
import io.hearth.auth.PendingCodes;
import io.hearth.auth.Passwords;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.Sessions;
import io.hearth.auth.Tokens;
import io.hearth.auth.UserRecord;
import io.hearth.auth.Users;
import io.hearth.common.Verbose;
import io.hearth.mail.Mailer;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.hearth.vhost.SiteUrls;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * The account pages: register, sign in, sign out, forgot and reset password.
 *
 * The shape of every flow is the same, because there is really only one flow: prove you can read an
 * email address, then get a session. Registering and signing in passwordlessly are the same six
 * steps with different wording, which is why passwordless is the default -- it is not a reduced
 * version of the password flow, it is the whole thing with one fewer secret to lose.
 *
 * Rules that run through all of it:
 *
 *   No enumeration. Asking for a code tells you nothing about whether the address has an account.
 *   The response is identical either way, and the decision to create or sign in happens after the
 *   code comes back, when we know a real person is holding the address.
 *
 *   Nothing sensitive in a URL. Codes and handles travel in form bodies; the handle is opaque and
 *   server-side-mapped, so a page cannot be edited into redeeming somebody else's code.
 *
 *   Forms are minted. Field names differ on every page load, the form is assembled by JavaScript,
 *   and the page reports what the browser did while it was open. See {@link FormMint} for what that
 *   does and does not buy.
 *
 *   Nobody gets a session until an admin approves them, except the addresses the config names as
 *   admins outright. See {@link io.hearth.auth.Access}.
 */
public class AccountRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(AccountRoutes.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Templates templates;
  private final Mailer mailer;
  private final FormMint mint;
  private final Verbose verbose;
  /**
   * The access-log recorder for the request being handled.
   *
   * A thread local rather than a parameter on twenty methods. Netty hands one request to one event
   * loop thread for its whole life, so this is safe, and the alternative -- threading a recorder
   * through every render and redirect -- would be noise in every signature for the sake of one
   * number.
   */
  private final ThreadLocal<WebHandler.Recorder> recorder = new ThreadLocal<>();

  public AccountRoutes(Templates templates, Mailer mailer, Verbose verbose) {
    this.templates = templates;
    this.mailer = mailer;
    this.mint = new FormMint();
    this.verbose = verbose;
  }

  public FormMint mint() {
    return mint;
  }

  /** dispatch one account request; the caller has already matched the path to a route */
  public void handle(SiteUrls.Route route, DomainConfig config, Accounts accounts,
                     ChannelHandlerContext ctx, FullHttpRequest req, WebHandler.Recorder recorder) {
    this.recorder.set(recorder);
    boolean post = HttpMethod.POST.equals(req.method());
    try {
      if (!post) {
        showForm(route, config, accounts, ctx, req, null, null, null);
        return;
      }
      if (route == SiteUrls.Route.logout) {
        // signing out is posted from the navigation, which is a plain form on every page rather
        // than a minted one. There is nothing here worth obfuscating: the caller already holds a
        // session, so the only thing to defend against is another site posting on their behalf.
        Forms plain = Forms.of(req);
        if (!Cookies.csrfMatches(plain.get(Cookies.CSRF_FIELD), Forms.cookie(req, Cookies.CSRF_COOKIE))) {
          verbose.detail("logout: csrf mismatch");
          status(303);
          Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
              new String[]{HttpHeaderNames.LOCATION.toString(), config.urls.afterLogin});
          return;
        }
        postLogout(config, accounts, ctx, req);
        return;
      }
      Submission submission = readSubmission(accounts, req);
      if (submission.problem != null) {
        verbose.detail("submission refused: " + submission.problem);
        showForm(route, config, accounts, ctx, req, submission.userMessage, null, null);
        return;
      }
      switch (route) {
        case register -> postRegister(config, accounts, ctx, req, submission);
        case login -> postLogin(config, accounts, ctx, req, submission);
        case forgot_password -> postForgot(config, accounts, ctx, req, submission);
        case reset_password -> postReset(config, accounts, ctx, req, submission);
        case admin, self -> showForm(route, config, accounts, ctx, req, null, null, null);
      }
    } catch (SQLException ex) {
      LOG.error("account-route-failed", ex);
      Map<String, Object> model = base(config, accounts, req, "Something went wrong", new HashMap<>());
      model.put("heading", "Something went wrong");
      model.put("message", "That did not work, and it is our fault rather than yours. Try again in a moment.");
      status(500);
      Responses.sendHtml(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, templates.render("message", model));
    }
  }

  // ---- reading a minted submission ---------------------------------------------------------

  /**
   * Translate an incoming form back into logical fields, and run every check that does not depend
   * on which route it was.
   *
   * Order matters: the ticket has to be found before anything can be named, and the CSRF token is
   * checked against both the cookie and the ticket the form was minted with, so a token lifted from
   * one page cannot be replayed against another.
   */
  private Submission readSubmission(Accounts accounts, FullHttpRequest req) {
    Forms raw = Forms.of(req);
    String ticketId = Forms.query(req.uri(), FormMint.TICKET_PARAM);
    FormMint.Ticket ticket = mint.find(ticketId);
    if (ticket == null) {
      return Submission.refused("no such form ticket", "That form expired. Please try again.");
    }
    Map<String, String> fields = new HashMap<>();
    for (Map.Entry<String, String> entry : raw.all().entrySet()) {
      String logical = ticket.logicalOf(entry.getKey());
      if (logical != null) {
        fields.put(logical, entry.getValue());
      }
    }
    // one submission per minted form; a captured body cannot be posted twice
    mint.spend(ticket.id);

    String csrf = fields.get(FormMint.CSRF);
    if (!Cookies.csrfMatches(csrf, Forms.cookie(req, Cookies.CSRF_COOKIE))
        || !Tokens.constantTimeEquals(csrf, ticket.csrfToken)) {
      return Submission.refused("csrf mismatch", "That form expired. Please try again.");
    }
    String trap = fields.get(FormMint.TRAP);
    if (trap != null && !trap.isEmpty()) {
      // our JavaScript leaves this empty; something filled in every input it could find
      return Submission.refused("honeypot filled", "That form could not be accepted.");
    }
    String proof = fields.get(FormMint.PROOF);
    if (!Tokens.constantTimeEquals(proof, ticket.expectedProof())) {
      return Submission.refused("proof missing or wrong; the page's script did not run",
          "That form could not be accepted. Is JavaScript switched off?");
    }
    Signals signals = Signals.parse(fields.get(FormMint.SIGNALS));
    if (!signals.plausible()) {
      return Submission.refused("zero interaction events",
          "That form could not be accepted.");
    }
    return new Submission(fields, signals, null, null);
  }

  /** a translated form body, or the reason it was thrown away */
  private record Submission(Map<String, String> fields, Signals signals, String problem, String userMessage) {
    static Submission refused(String problem, String userMessage) {
      return new Submission(Map.of(), Signals.NONE, problem, userMessage);
    }

    String get(String logical) {
      String value = fields.get(logical);
      if (value == null) {
        return null;
      }
      String trimmed = value.trim();
      return trimmed.isEmpty() ? null : trimmed;
    }

    /** passwords are taken exactly as typed */
    String raw(String logical) {
      String value = fields.get(logical);
      return value == null || value.isEmpty() ? null : value;
    }
  }

  // ---- register ----------------------------------------------------------------------------

  private void postRegister(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                            FullHttpRequest req, Submission form) throws SQLException {
    String handle = form.get(FormMint.HANDLE);
    if (handle != null) {
      redeemRegistration(config, accounts, ctx, req, form, handle);
      return;
    }
    String email = Tokens.normalizeEmail(form.get(FormMint.EMAIL));
    if (!Tokens.looksLikeEmail(email)) {
      showForm(SiteUrls.Route.register, config, accounts, ctx, req, "That does not look like an email address.", null, null);
      return;
    }
    if (!accounts.codes.allowRequest(email)) {
      showForm(SiteUrls.Route.register, config, accounts, ctx, req,
          "That address has asked for too many codes. Wait a while and try again.", null, null);
      return;
    }
    PendingCodes.Issued issued = accounts.codes.issue(PendingCodes.Purpose.register, email, null);
    if (accounts.bans.isBanned(email)) {
      // The ban is why this path is cheap: no mail, no scrypt, no row. What it deliberately is NOT
      // is visible -- a banned address sees the same "check your email" page a fresh one does,
      // because a ban that answers differently is an oracle for whether an address is banned, and
      // for whether it ever had an account here. The code they never receive simply expires.
      verbose.detail("register: " + email + " is banned; no mail sent, same page shown");
    } else {
      mailer.sendRegistrationCode(envelope(config, accounts, email), issued.code());
      verbose.detail("register: mailed a code to " + email + " for " + config.domain
          + " (" + form.signals().describe() + ")");
    }
    showCode(config, accounts, ctx, req, SiteUrls.Route.register, issued.handle(), email,
        "Check your email", "Create my account", accounts.security.usesPasswords(), null, form.signals());
  }

  private void redeemRegistration(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                                  FullHttpRequest req, Submission form, String handle) throws SQLException {
    LoginSecurity security = accounts.security;
    String email = accounts.codes.emailFor(handle);
    PendingCodes.Redeemed redeemed = accounts.codes.redeem(handle, PendingCodes.Purpose.register, form.get(FormMint.CODE));
    if (!redeemed.accepted()) {
      showCode(config, accounts, ctx, req, SiteUrls.Route.register, handle, email,
          "Check your email", "Create my account", security.usesPasswords(), redeemed.problem(), form.signals());
      return;
    }
    String passwordHash = null;
    if (security.usesPasswords()) {
      String password = form.raw(FormMint.PASSWORD);
      String problem = Passwords.reject(password, security);
      if (problem != null) {
        // the code is spent, so re-issue rather than stranding somebody who mistyped a password
        PendingCodes.Issued reissued = accounts.codes.issue(PendingCodes.Purpose.register, redeemed.email(), null);
        mailer.sendRegistrationCode(envelope(config, accounts, redeemed.email()), reissued.code());
        showCode(config, accounts, ctx, req, SiteUrls.Route.register, reissued.handle(), redeemed.email(),
            "Check your email", "Create my account", true, problem + " We sent a fresh code.", form.signals());
        return;
      }
      passwordHash = Passwords.hash(password);
    }
    // holding the address is the proof; whether the account already existed only decides
    // create-versus-sign-in, and either way the answer looks the same from outside
    UserRecord user = accounts.users.byEmail(redeemed.email());
    if (user == null) {
      Users.Signup signup = new Users.Signup(form.signals().total(), form.signals().toString(), clientIp(ctx));
      user = accounts.users.create(redeemed.email(), passwordHash, true, signup);
      verbose.detail("register: created account " + user.id() + " for " + redeemed.email()
          + " (" + form.signals().describe() + ")");
    } else {
      accounts.users.markVerified(user.id());
      if (passwordHash != null && !user.hasPassword()) {
        accounts.users.setPassword(user.id(), passwordHash);
      }
      verbose.detail("register: address already had account " + user.id() + "; signing in instead");
    }
    finishSignIn(config, accounts, ctx, req, user);
  }

  // ---- sign in -----------------------------------------------------------------------------

  private void postLogin(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                         FullHttpRequest req, Submission form) throws SQLException {
    LoginSecurity security = accounts.security;
    String handle = form.get(FormMint.HANDLE);
    if (handle != null) {
      String pendingEmail = accounts.codes.emailFor(handle);
      PendingCodes.Redeemed redeemed = accounts.codes.redeem(handle, PendingCodes.Purpose.login, form.get(FormMint.CODE));
      if (!redeemed.accepted()) {
        showCode(config, accounts, ctx, req, SiteUrls.Route.login, handle, pendingEmail,
            "Check your email", "Sign in", false, redeemed.problem(), form.signals());
        return;
      }
      UserRecord user = accounts.users.byEmail(redeemed.email());
      if (user == null) {
        showForm(SiteUrls.Route.login, config, accounts, ctx, req, "That account cannot sign in right now.", null, null);
        return;
      }
      accounts.users.markVerified(user.id());
      finishSignIn(config, accounts, ctx, req, user);
      return;
    }

    String email = Tokens.normalizeEmail(form.get(FormMint.EMAIL));
    if (!Tokens.looksLikeEmail(email)) {
      showForm(SiteUrls.Route.login, config, accounts, ctx, req, "That does not look like an email address.", null, null);
      return;
    }

    if (security.usesPasswords()) {
      passwordSignIn(config, accounts, ctx, req, form, email);
      return;
    }

    if (!accounts.codes.allowRequest(email)) {
      showForm(SiteUrls.Route.login, config, accounts, ctx, req,
          "That address has asked for too many codes. Wait a while and try again.", null, null);
      return;
    }
    // issue a code whether or not the account exists; the page below is identical either way
    PendingCodes.Issued issued = accounts.codes.issue(PendingCodes.Purpose.login, email, null);
    UserRecord existing = accounts.users.byEmail(email);
    if (existing != null && existing.canSignIn(System.currentTimeMillis())
        && !accounts.bans.isBanned(email)) {
      mailer.sendLoginCode(envelope(config, accounts, email), issued.code());
      verbose.detail("login: mailed a code to " + email);
    } else {
      verbose.detail("login: no usable account for " + email + "; no mail sent, same page shown");
    }
    showCode(config, accounts, ctx, req, SiteUrls.Route.login, issued.handle(), email,
        "Check your email", "Sign in", false, null, form.signals());
  }

  private void passwordSignIn(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                              FullHttpRequest req, Submission form, String email) throws SQLException {
    LoginSecurity security = accounts.security;
    String password = form.raw(FormMint.PASSWORD);
    UserRecord user = accounts.users.byEmail(email);
    long now = System.currentTimeMillis();
    // one message for every failure mode, so the form is not an oracle for which addresses exist
    String generic = "That email address and password did not match.";
    if (user == null || !user.hasPassword()) {
      // still pay the hashing cost, so a missing account is not visibly faster than a wrong password
      Passwords.verify(password == null ? "" : password, "$s0$e0801$AAAAAAAAAAAAAAAAAAAAAA==$" + "0".repeat(43));
      showForm(SiteUrls.Route.login, config, accounts, ctx, req, generic, null, null);
      return;
    }
    if (!user.canSignIn(now)) {
      showForm(SiteUrls.Route.login, config, accounts, ctx, req,
          user.isLocked(now) ? "That account is locked for a little while." : generic, null, null);
      return;
    }
    if (password == null || !Passwords.verify(password, user.passwordHash())) {
      accounts.users.recordFailure(user.id(), security);
      showForm(SiteUrls.Route.login, config, accounts, ctx, req, generic, null, null);
      return;
    }
    if (Passwords.needsRehash(user.passwordHash())) {
      accounts.users.setPassword(user.id(), Passwords.hash(password));
    }
    if (security.requiresSecondFactor()) {
      // the password was right; the second factor is a separate proof, mailed now
      PendingCodes.Issued issued = accounts.codes.issue(PendingCodes.Purpose.login, email, user.id());
      mailer.sendTwoFactorCode(envelope(config, accounts, email), issued.code());
      showCode(config, accounts, ctx, req, SiteUrls.Route.login, issued.handle(), email,
          "One more step", "Sign in", false, null, form.signals());
      return;
    }
    finishSignIn(config, accounts, ctx, req, user);
  }

  // ---- sign out ----------------------------------------------------------------------------

  private void postLogout(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                          FullHttpRequest req) throws SQLException {
    String token = Forms.cookie(req, accounts.security.cookieName);
    if (token != null) {
      // deleted rather than revoked, and the push subscription goes with it. A revoked row lingers
      // for a day, and for that day this server still holds a key that can make a notification
      // appear on a device somebody has just signed out of.
      Long sessionId = accounts.sessions.delete(token);
      if (sessionId != null) {
        int silenced = accounts.pushSubs.forgetSession(sessionId);
        verbose.detail(() -> "logout: session " + sessionId + " deleted on " + config.domain
            + (silenced > 0 ? ", " + silenced + " push subscription(s) with it" : ""));
      }
    }
    status(303);
    // the front page, not `after-login`: that is where somebody goes when they arrive, and the
    // dashboard it now points at would bounce a signed-out person straight back to the sign-in form
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY, new String[]{
        HttpHeaderNames.LOCATION.toString(), "/",
        HttpHeaderNames.SET_COOKIE.toString(), Cookies.clearSession(accounts.security)});
  }

  // ---- forgot and reset --------------------------------------------------------------------

  private void postForgot(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                          FullHttpRequest req, Submission form) throws SQLException {
    String email = Tokens.normalizeEmail(form.get(FormMint.EMAIL));
    if (!Tokens.looksLikeEmail(email)) {
      showForm(SiteUrls.Route.forgot_password, config, accounts, ctx, req, "That does not look like an email address.", null, null);
      return;
    }
    if (accounts.codes.allowRequest(email)) {
      PendingCodes.Issued issued = accounts.codes.issue(PendingCodes.Purpose.reset_password, email, null);
      UserRecord user = accounts.users.byEmail(email);
      if (user != null && !user.disabled()) {
        String link = "http://" + config.domain + config.urls.resetPassword;
        mailer.sendPasswordReset(envelope(config, accounts, email), issued.code(), link);
        verbose.detail("forgot: mailed a reset code to " + email);
      }
      showCode(config, accounts, ctx, req, SiteUrls.Route.reset_password, issued.handle(), email,
          "Choose a new password", "Set my password", true, null, form.signals());
      return;
    }
    showForm(SiteUrls.Route.forgot_password, config, accounts, ctx, req,
        "That address has asked for too many codes. Wait a while and try again.", null, null);
  }

  private void postReset(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                         FullHttpRequest req, Submission form) throws SQLException {
    LoginSecurity security = accounts.security;
    String handle = form.get(FormMint.HANDLE);
    String pendingEmail = accounts.codes.emailFor(handle);
    String password = form.raw(FormMint.PASSWORD);
    String problem = Passwords.reject(password, security);
    if (problem != null) {
      showCode(config, accounts, ctx, req, SiteUrls.Route.reset_password, handle, pendingEmail,
          "Choose a new password", "Set my password", true, problem, form.signals());
      return;
    }
    PendingCodes.Redeemed redeemed = accounts.codes.redeem(handle, PendingCodes.Purpose.reset_password, form.get(FormMint.CODE));
    if (!redeemed.accepted()) {
      showCode(config, accounts, ctx, req, SiteUrls.Route.reset_password, handle, pendingEmail,
          "Choose a new password", "Set my password", true, redeemed.problem(), form.signals());
      return;
    }
    UserRecord user = accounts.users.byEmail(redeemed.email());
    if (user == null || user.disabled()) {
      showForm(SiteUrls.Route.login, config, accounts, ctx, req, "That account cannot sign in right now.", null, null);
      return;
    }
    accounts.users.setPassword(user.id(), Passwords.hash(password));
    // a new password means every old session is somebody else's problem
    int ended = accounts.sessions.revokeAllFor(user.id());
    mailer.sendPasswordChanged(envelope(config, accounts, user.email()));
    verbose.detail("reset: password changed for " + user.email() + ", " + ended + " session(s) ended");
    finishSignIn(config, accounts, ctx, req, user);
  }

  // ---- shared ------------------------------------------------------------------------------

  /**
   * The last step of every flow: a session, or a page explaining why not.
   *
   * A session means "this person proved they can read that address". It does not mean approved.
   * That separation is deliberate and it is what makes approval workable: an unapproved person can
   * sign in, write a profile and answer questions -- which is exactly what an admin needs in order
   * to decide about them -- and can reach nothing else. Authentication and authorization are
   * different questions, and conflating them left the approver with nothing to read.
   *
   * Disabled and locked still get no session at all, because those are not "not yet", they are "no".
   */
  private void finishSignIn(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                            FullHttpRequest req, UserRecord user) throws SQLException {
    accounts.access.reconcileBootstrapAdmin(accounts.users, user);
    UserRecord fresh = accounts.users.byId(user.id());
    long now = System.currentTimeMillis();
    if (!fresh.canSignIn(now)) {
      String refusal = accounts.access.refusalFor(fresh, now);
      verbose.detail("no session for " + fresh.email() + ": " + refusal);
      Map<String, Object> model = base(config, accounts, req, "Cannot sign in", new HashMap<>());
      model.put("heading", "Cannot sign in");
      model.put("message", refusal);
      status(200);
      Responses.sendHtml(ctx, req, HttpResponseStatus.OK, templates.render("message", model));
      return;
    }
    accounts.users.markSignedIn(fresh.id());
    Sessions.Issued issued = accounts.sessions.create(fresh.id(), clientIp(ctx),
        req.headers().get(HttpHeaderNames.USER_AGENT));
    verbose.detail("signed in " + fresh.email() + " on " + config.domain
        + " (session " + issued.record().id() + ")");
    status(303);
    // an unapproved person lands on their own page, because writing a profile is the only useful
    // thing they can do until somebody says yes
    String landing = accounts.access.isApproved(fresh) ? config.urls.afterLogin : config.urls.self;
    // ...unless they were sent here from somewhere, in which case that is where they were going.
    // The OAuth flow depends on this: a connector sends an admin to /mcp/authorize, and dropping
    // them on the home page afterwards leaves the popup they are sitting in waiting forever.
    String requested = Landing.from(req.uri());
    if (requested != null && accounts.access.isApproved(fresh)) {
      landing = requested;
    }
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY, new String[]{
        HttpHeaderNames.LOCATION.toString(), landing,
        HttpHeaderNames.SET_COOKIE.toString(), Cookies.session(accounts.security, issued.token())});
  }

  /** the first step of a flow: mint a form and render the page that builds it */
  private void showForm(SiteUrls.Route route, DomainConfig config, Accounts accounts,
                        ChannelHandlerContext ctx, FullHttpRequest req, String problem,
                        String handle, Signals carried) {
    boolean wantsPassword = accounts.security.usesPasswords();
    // Where they were going, threaded onto every way out of this page as well as onto the form.
    //
    // Somebody bounced here from a link they were sent does not necessarily have an account, and
    // the first thing they do is press "Create an account" -- which used to drop the destination on
    // the floor and land them on the home page with no idea what they had come for. Every one of
    // these links stays inside the same flow, so every one of them keeps it. The value is already
    // through Landing.safe, and URL-encoded on the way into the href.
    String next = Landing.from(req.uri());
    switch (route) {
      case register -> render(config, accounts, ctx, req, route, problem, handle,
          "Create an account", "Enter your email address. We will send you a code.",
          "Send me a code", true, false, false,
          "Already have an account? <a href=\"" + Landing.carry(config.urls.login, next)
              + "\">Sign in</a>.");
      case login, logout -> render(config, accounts, ctx, req, SiteUrls.Route.login, problem, handle,
          "Sign in", wantsPassword ? "Enter your email address and password."
              : "Enter your email address. We will send you a code.",
          wantsPassword ? "Sign in" : "Send me a code", true, false, wantsPassword,
          "<a href=\"" + Landing.carry(config.urls.register, next) + "\">Create an account</a>"
              + (wantsPassword ? " &middot; <a href=\""
                  + Landing.carry(config.urls.forgotPassword, next)
                  + "\">Forgot your password?</a>" : ""));
      case forgot_password -> render(config, accounts, ctx, req, route, problem, handle,
          "Forgot your password", "Enter your email address. We will send you a code to choose a new one.",
          "Send me a code", true, false, false,
          "<a href=\"" + Landing.carry(config.urls.login, next) + "\">Back to sign in</a>.");
      case reset_password -> render(config, accounts, ctx, req, route, problem, handle,
          "Choose a new password", "Enter the code we sent you, and a new password.",
          "Set my password", false, true, true,
          "<a href=\"" + Landing.carry(config.urls.forgotPassword, next)
              + "\">Send me another code</a>.");
      // Everything else renders somewhere else, and reaching here means somebody posted to one of
      // them. A default rather than a list of names: a route added without a branch used to hold
      // the connection open with nothing written on it until the browser gave up, which is the
      // worst possible failure -- invisible in a log, and indistinguishable from a hung server.
      default -> {
        Map<String, Object> model = base(config, accounts, req, "That did not work",
            new HashMap<>());
        model.put("heading", "That did not work");
        model.put("message", "Try again from the page you came from.");
        status(400);
        Responses.sendHtml(ctx, req, HttpResponseStatus.BAD_REQUEST, templates.render("message", model));
      }
    }
  }

  /** the second step: same minted-form machinery, now asking for the code */
  private void showCode(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx, FullHttpRequest req,
                        SiteUrls.Route route, String handle, String email, String heading, String submit,
                        boolean needsPassword, String problem, Signals carried) {
    boolean wantsPassword = route == SiteUrls.Route.reset_password
        || (route == SiteUrls.Route.register && accounts.security.usesPasswords());
    render(config, accounts, ctx, req, route, problem, handle, heading,
        "We sent a " + accounts.security.codeLength + " digit code to "
            + (email == null ? "your address" : email) + ".",
        submit, false, true, wantsPassword,
        "Didn't get it? <a href=\"" + Landing.carry(pathOf(route, config), Landing.from(req.uri()))
            + "\">Start again</a>.");
  }

  /**
   * Render a minted form.
   *
   * The page carries a JSON blob and a script; the HTML has no form in it. Everything the script
   * needs -- the opaque names, the nonce to prove itself with, the action to post to -- is in the
   * blob, and the names are recomputed server-side from the ticket rather than trusted from the
   * request.
   */
  private void render(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx, FullHttpRequest req,
                      SiteUrls.Route route, String problem, String handle, String heading, String lead,
                      String submit, boolean wantEmail, boolean wantCode, boolean wantPassword,
                      String footnote) {
    // the browser's existing token, so another request cannot invalidate this form underneath it
    String csrf = Cookies.stableToken(req);
    String scriptNonce = Cookies.newCsrfToken();
    FormMint.Ticket ticket = mint.mint(csrf);

    ObjectNode blob = JSON.createObjectNode();
    // the destination rides on the action URL rather than in a field or a cookie: it is already
    // how the ticket survives the flow, and it means a person who abandons the form leaves nothing
    // behind that has to be cleaned up
    blob.put("action", Landing.carry(
        pathOf(route, config) + "?" + FormMint.TICKET_PARAM + "=" + ticket.id, Landing.from(req.uri())));
    blob.put("csrf", csrf);
    blob.put("nonce", ticket.nonce);
    blob.put("submit", submit);
    blob.put("codeLength", accounts.security.codeLength);
    blob.put("passwordLabel", route == SiteUrls.Route.reset_password ? "new password" : "password");
    if (handle != null) {
      blob.put("handle", handle);
    }
    ObjectNode names = blob.putObject("f");
    for (Map.Entry<String, String> entry : ticket.names().entrySet()) {
      names.put(entry.getKey(), entry.getValue());
    }
    ObjectNode want = blob.putObject("want");
    want.put("email", wantEmail);
    want.put("code", wantCode);
    want.put("password", wantPassword);

    Map<String, Object> model = base(config, accounts, req, heading, new HashMap<>());
    model.put("problem", problem);
    model.put("heading", heading);
    model.put("lead", lead);
    model.put("footnote", footnote);
    model.put("nonce", scriptNonce);
    model.put("mint", blob.toString());
    // the nav's sign-out form needs a token too, and it is the same one
    model.put("csrf", csrf);

    status(problem == null ? 200 : 400);
    Responses.send(ctx, req, problem == null ? HttpResponseStatus.OK : HttpResponseStatus.BAD_REQUEST,
        "text/html; charset=utf-8", templates.render("minted", model),
        new String[]{HttpHeaderNames.SET_COOKIE.toString(), Cookies.csrf(accounts.security, csrf)},
        scriptNonce);
  }

  /** the model every page shares: who we are, where the nav goes, whether anyone is signed in */
  Map<String, Object> base(DomainConfig config, Accounts accounts, FullHttpRequest req, String title, Map<String, Object> into) {
    Map<String, Object> model = new HashMap<>(into);
    io.hearth.web.Chrome.site(model, config, accounts, req);
    model.put("title", title + " - " + config.name);
    model.put("community", config.name);
    model.put("domain", config.domain);
    model.put("registerUrl", config.urls.register);
    model.put("loginUrl", config.urls.login);
    model.put("logoutUrl", config.urls.logout);
    model.put("forgotUrl", config.urls.forgotPassword);
    model.put("nav", Navigation.forRequest(config, accounts, req));
    return model;
  }


  static String pathOf(SiteUrls.Route route, DomainConfig config) {
    return switch (route) {
      case register -> config.urls.register;
      case login -> config.urls.login;
      case logout -> config.urls.logout;
      case forgot_password -> config.urls.forgotPassword;
      case reset_password -> config.urls.resetPassword;
      case admin -> config.urls.admin;
      case self -> config.urls.self;
    };
  }

  /** tell the access log what we answered with */
  private void status(int code) {
    WebHandler.Recorder current = recorder.get();
    if (current != null) {
      current.status(code);
    }
  }

  private Mailer.Envelope envelope(DomainConfig config, Accounts accounts, String email) {
    return Mailer.Envelope.to(config, accounts, email, null);
  }

  static String clientIp(ChannelHandlerContext ctx) {
    if (ctx.channel().remoteAddress() instanceof InetSocketAddress address && address.getAddress() != null) {
      return address.getAddress().getHostAddress();
    }
    return null;
  }

  /** the session behind this request, or null; used by the home page and the nav */
  public static SessionRecord currentSession(Accounts accounts, FullHttpRequest req) {
    if (accounts == null) {
      return null;
    }
    return accounts.sessions.resolve(Forms.cookie(req, accounts.security.cookieName));
  }
}
