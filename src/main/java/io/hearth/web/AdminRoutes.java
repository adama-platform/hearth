package io.hearth.web;

import io.hearth.analytics.AccessLog;
import io.hearth.analytics.Hit;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.auth.Accounts;
import io.hearth.auth.Permission;
import io.hearth.auth.RoleDefs;
import io.hearth.auth.Bans;
import io.hearth.auth.Roles;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.Tokens;
import io.hearth.auth.UserRecord;
import io.hearth.cache.TtlCache;
import io.hearth.common.Verbose;
import io.hearth.content.ContentRecord;
import io.hearth.board.Board;
import io.hearth.calendar.Calendar;
import io.hearth.content.ContentVersions;
import io.hearth.content.Proposals;
import io.hearth.content.TextPatch;
import io.hearth.mail.Mailer;
import io.hearth.people.InvitePixel;
import io.hearth.people.Invitations;
import io.hearth.people.Invites;
import io.hearth.places.Geocoder;
import io.hearth.places.Places;
import io.hearth.content.TemplateField;
import io.hearth.content.TemplateRecord;
import io.hearth.events.EventBus;
import io.hearth.events.MutationEvent;
import io.hearth.people.ProfileRecord;
import io.hearth.people.Question;
import io.hearth.template.Templates;
import io.hearth.legal.LegalDoc;
import io.hearth.legal.LegalDocs;
import io.hearth.theme.Theme;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The admin section.
 *
 * Four rules, each of which came from something going wrong:
 *
 * **Every sub-view has its own URL.** A refreshable panel is a path, not a query flag, and it
 * renders the same HTML embedded in its page or fetched on its own -- the page gets its panel by
 * calling the very method the panel's URL calls, so the two cannot drift. The previous design used
 * `?fragment=1`, which is invisible in an access log and which broke outright: mustache escapes `=`
 * to `&#61;`, entities are not decoded inside a script block, so the live button fetched
 * `?fragment&#61;1`, the flag never parsed, and the entire page rendered inside the panel.
 *
 * **Identity in the path, filters in the query, mutations in a POST.** `/admin/content/edit/41`,
 * `/admin/content/list?q=about`, `POST /admin/content`. Nothing that changes state carries a query
 * parameter, and what is left in the log is readable.
 *
 * **A listing is not a form.** Creating or editing anything is a page transition to its own URL,
 * rather than a form wedged above the list whose state goes ambiguous the moment the list moves.
 *
 * **Nothing is announced in a URL.** Confirmations go through {@link Flash}, keyed by session and
 * read once, instead of trailing `?done=...` through the history and the log.
 */
public class AdminRoutes {
  /** for the blobs handed to the editor's script; a mapper is thread safe once configured */
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON_OUT =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private static final Logger LOG = LoggerFactory.getLogger(AdminRoutes.class);
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
  private static final int PAGE_SIZE = 200;

  private final Templates templates;
  private final EventBus events;
  private final AccessLog accessLog;
  private final io.hearth.mcp.AiLog aiLog;
  /** invitations go out from here, so the admin needs the mailer */
  private final Mailer mailer;
  private final Invitations invitations;
  /** the calendar's own invitations, which are a different thing: a file rather than a message */
  private final io.hearth.calendar.Invitations calendarInvites;
  /**
   * Is this server receiving mail at all?
   *
   * Read once at boot and carried, because it decides whether a calendar invitation can honestly be
   * sent -- every one of them says "answer from your calendar", and a calendar answers by email to
   * this machine. It is a property of the box rather than of a community, which is why it arrives
   * here rather than sitting in a domain's config.
   */
  private final boolean inboundMail;
  /** turns an address into a point when a place is saved, or does nothing */
  private final Geocoder geocoder;
  private final Flash flash;
  private final io.hearth.live.Live live;
  /** what the box is doing; sampled on the notifier's pass, never on a request */
  private final io.hearth.analytics.Machine machine = new io.hearth.analytics.Machine();

  public io.hearth.analytics.Machine machine() {
    return machine;
  }
  /**
   * The weekly grid, so somebody putting an event up is not guessing at a night.
   *
   * Read, never built: the event form asks what the indexer last worked out, which is the whole
   * reason the indexer exists.
   */
  private io.hearth.availability.Availabilities availabilities;
  /** where the bytes live and what is cached; the screen reports both */
  private io.hearth.attach.AttachmentRoutes attachments;
  /**
   * The queue that turns addresses into points, and the screen that reports on it.
   *
   * Set after construction, like the availabilities and the attachments, because it needs the whole
   * account system and that is built after the routes are.
   */
  private io.hearth.places.Geocodes geocodes;
  private final Verbose verbose;

  public void knowsAbout(io.hearth.places.Geocodes geocodes) {
    this.geocodes = geocodes;
  }

  public io.hearth.places.Geocodes geocodes() {
    return geocodes;
  }

  /**
   * Everything this box was started with, so one screen can say what is actually switched on.
   *
   * The config is read once before the socket opens and never again, which is a deliberate
   * property and also the reason an operator cannot see it: it exists only as fields on objects
   * scattered through the process. Carrying it here is what makes `/admin/system/settings` a
   * report rather than a guess.
   */
  private final io.hearth.common.ServerConfig settings;

  public AdminRoutes(Templates templates, EventBus events, AccessLog accessLog,
                     io.hearth.mcp.AiLog aiLog, Mailer mailer, io.hearth.live.Live live,
                     boolean inboundMail, Geocoder geocoder,
                     io.hearth.common.ServerConfig settings, Verbose verbose) {
    this.settings = settings == null ? io.hearth.common.ServerConfig.defaults() : settings;
    this.live = live;
    this.mailer = mailer;
    this.invitations = new Invitations(mailer);
    this.calendarInvites = new io.hearth.calendar.Invitations(mailer);
    this.inboundMail = inboundMail;
    this.geocoder = geocoder;
    this.templates = templates;
    this.events = events;
    this.accessLog = accessLog;
    this.aiLog = aiLog;
    this.flash = new Flash();
    this.verbose = verbose;
  }

  public Flash flash() {
    return flash;
  }

  /** wired after construction, because the grids need the domains and the domains need routes */
  public void knowsAbout(io.hearth.availability.Availabilities grids) {
    this.availabilities = grids;
  }

  /** the uploads screen needs the store and the cache, which live with the serving route */
  public void knowsAbout(io.hearth.attach.AttachmentRoutes files) {
    this.attachments = files;
  }

  // ---- dispatch --------------------------------------------------------------------------------

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    try {
      SessionRecord session = AccountRoutes.currentSession(accounts, req);
      UserRecord me = session == null ? null : accounts.users.byId(session.userId());
      if (me == null || !accounts.access.canEnterAdmin(me)) {
        // the same answer whether they are signed out or simply not allowed in: this section does
        // not confirm its own existence to people who cannot use it.
        //
        // The way back is on the page rather than in the status code. A 303 to the sign-in form
        // would be a better door for an administrator whose session lapsed and a worse one for
        // everybody else, because it says "this path is guarded" to whoever asked -- so the page
        // that comes back offers to sign them in and return them here, and only when there is
        // nobody signed in. Somebody who *is* signed in and may not enter sees exactly what a
        // missing page looks like, which is the whole point of answering 404.
        verbose.detail("admin: refused " + (me == null ? "an anonymous request" : me.email()));
        recorder.status(404);
        Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, notFoundPage(config, accounts, req));
        return;
      }
      java.util.Set<Permission> allowed = accounts.access.permissionsOf(me);
      AdminView.Target target = AdminView.resolve(config, Forms.path(req.uri()));
      // a section somebody may not open answers exactly as one that does not exist. Anything else
      // is a directory of what this community has that they are not trusted with.
      if (target != null && !AdminView.permits(allowed, target.section().needs)) {
        verbose.detail(() -> "admin: " + me.email() + " may not reach " + target.section());
        recorder.status(404);
        Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, notFoundPage(config, accounts, req));
        return;
      }
      if (target == null) {
        recorder.status(404);
        Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, notFoundPage(config, accounts, req));
        return;
      }
      if (HttpMethod.POST.equals(req.method())) {
        act(target.section(), config, accounts, ctx, req, me, session, recorder);
        return;
      }
      show(target, config, accounts, ctx, req, me, session, recorder);
    } catch (SQLException | RuntimeException ex) {
      // a runtime failure used to escape into Netty, which closes the connection with no bytes --
      // indistinguishable from a hung server, and invisible in a log
      LOG.error("admin-route-failed", ex);
      recorder.status(500);
      Map<String, Object> model = new HashMap<>();
      Chrome.admin(model, accounts);
      model.put("title", "Something went wrong");
      model.put("community", config.name);
      model.put("nav", List.of());
      model.put("heading", "Something went wrong");
      model.put("message", "That did not work, and it is our fault rather than yours.");
      Responses.sendHtml(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, templates.render("message", model));
    }
  }

  // ---- doing things ----------------------------------------------------------------------------

  private void act(AdminView.Section section, DomainConfig config, Accounts accounts,
                   ChannelHandlerContext ctx, FullHttpRequest req, UserRecord me,
                   SessionRecord session, WebHandler.Recorder recorder) throws SQLException {
    // the content and template editors post whole pages; the default ceiling is for account forms
    Forms form = Forms.of(req, Forms.MAX_CONTENT_BYTES);
    Outcome outcome;
    if (form.bodyTooLarge()) {
      // before the CSRF check: an oversized body parses to no fields, so the token is missing for
      // a reason that has nothing to do with the token
      outcome = Outcome.refused("That was too large to save in one go. Nothing was changed.");
    } else if (!Cookies.csrfMatches(form.get(Cookies.CSRF_FIELD), Forms.cookie(req, Cookies.CSRF_COOKIE))) {
      outcome = Outcome.refused("That form expired. Please try again.");
    } else {
      outcome = switch (section) {
        case people -> actOnPerson(config, accounts, form, me);
        case bans -> actOnBan(accounts, form, me);
        case invites -> actOnInvite(config, accounts, form, me);
        case content -> actOnContent(accounts, form, me);
      case attachments -> actOnAttachment(config, accounts, form, me);
      case bundles -> actOnContent(accounts, form, me);
      case directories -> actOnDirectory(accounts, form, me);
      case unused -> actOnUnused(config, accounts, form, me);
        case templates -> actOnTemplate(accounts, form, me);
        case survey -> actOnQuestion(accounts, form, me);
        case retired -> actOnRetiredQuestion(accounts, form, me);
        case configuration -> actOnConfiguration(config, accounts, form, me);
        case setup -> actOnSetup(config, accounts, form, me);
        case appearance -> actOnAppearance(accounts, form, me);
        case legal -> actOnLegal(accounts, form, me);
        case messages -> actOnMessage(config, accounts, form, me);
        case ai -> actOnConnector(accounts, form, me);
        case board -> actOnPost(accounts, form, me);
        case flagged -> actOnFlag(accounts, form, me);
        case calendar, suggestions -> actOnEvent(config, accounts, form, me);
        case roles -> actOnRole(accounts, form, me);
        case proposals -> actOnProposal(accounts, form, me);
        case places -> actOnPlace(accounts, form, me);
        case async -> actOnAsync(config, accounts, form, me);
        case placetypes -> actOnPlaceType(accounts, form, me);
        default -> Outcome.refused("That is not something this page can do.");
      };
    }
    flash.set(Flash.keyFor(session), outcome.problem() != null ? outcome.problem() : outcome.done(),
        outcome.problem() != null);
    verbose.detail("admin: " + me.email() + (outcome.problem() == null
        ? " -> " + outcome.done() : " refused -- " + outcome.problem()));
    String where = outcome.goTo() == null ? section.path(config) : outcome.goTo().apply(config);
    // redirect after every POST, so a refresh cannot repeat it and the URL stays clean
    recorder.status(303);
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
        new String[]{HttpHeaderNames.LOCATION.toString(), where});
  }

  /**
   * Refuse if any field read so far was too long to accept.
   *
   * Called immediately before a write, never earlier: the oversize list is filled in as fields are
   * read, so checking it before the handler has read anything checks an empty list. Getting that
   * wrong is what let the original bug through, in a slightly different shape.
   */
  private static Outcome oversized(Forms form) {
    String field = form.tooLong();
    if (field == null) {
      return null;
    }
    return Outcome.refused("'" + field + "' is too long to store (" + form.tooLongBy(field)
        + " characters). Nothing was changed.");
  }

  /**
   * What each thing on the People screen actually requires.
   *
   * The section itself only needs `people_read`, because reading the member list is the mildest
   * thing here -- and every button on it was reachable with nothing more than that, including the
   * one that hands somebody the whole server. A section permission is permission to *see* a screen;
   * it was never permission to press what is on it.
   */
  private static Permission neededForPerson(String action) {
    return switch (action) {
      case "approve" -> Permission.people_approve;
      case "reject", "reject_and_ban", "erase", "disable", "enable" -> Permission.people_remove;
      case "grant_admin", "revoke_admin" -> Permission.people_roles;
      // an action nobody listed is refused rather than defaulting to the mildest one
      default -> Permission.everything;
    };
  }

  private Outcome actOnPerson(DomainConfig config, Accounts accounts, Forms form,
                              UserRecord me) throws SQLException {
    Permission needed = neededForPerson(String.valueOf(form.get("action")));
    if (!accounts.access.can(me, needed)) {
      return Outcome.refused("You are not able to do that. It needs '" + needed.label + "'.");
    }
    UserRecord target = userFrom(accounts, form);
    if (target == null) {
      return Outcome.refused("That person could not be found.");
    }
    boolean targetIsAdmin = accounts.access.isAdmin(target);
    Function<DomainConfig, String> toList = site -> AdminView.Section.people.path(site);
    return switch (String.valueOf(form.get("action"))) {
      case "test_push" -> {
        int sent = 0;
        for (io.hearth.push.PushSubs.Sub sub : accounts.pushSubs.forUser(target.id())) {
          io.hearth.push.WebPush.Message message = new io.hearth.push.WebPush.Message(
              config.name, "A test from " + me.email() + ". Nothing is wrong.",
              config.urls.home, "test", target.id());
          if (new io.hearth.push.WebPush(verbose).send(sub, message,
              "mailto:no-reply@" + config.domain).delivered()) {
            accounts.pushSubs.recordSuccess(sub.id());
            sent++;
          }
        }
        if (sent > 0) {
          // the stamp goes down like any other: a test that arrives and is tapped is a real
          // measurement of the delay, and pretending otherwise would mean two code paths
          accounts.pushLedger.sent(target.id(), System.currentTimeMillis());
        }
        yield sent == 0
            ? Outcome.refused("Nothing went. Either no browser of theirs is subscribed, or the"
                + " push service refused it.")
            : Outcome.done("Sent to " + sent + " of their browser(s). If they tap it, the delay is"
                + " recorded and shows up on the engagement screen.");
      }
      case "approve" -> {
        accounts.users.approve(target.id(), me.id());
        int adopted = accounts.welcome(target);
        yield Outcome.done(target.email() + " is approved."
            + (adopted == 0 ? "" : " " + adopted + " answer(s) they gave from outside are now on"
                + " the guest list."));
      }
      case "erase" -> {
        // The right to erasure, with the button an administrator needs to answer it inside the
        // month the law allows. Typed confirmation because nobody, including them, can undo it.
        if (targetIsAdmin) {
          yield Outcome.refused("An administrator cannot be deleted from here. Remove the role"
              + " first, so that this is two decisions rather than one.");
        }
        if (!"delete".equalsIgnoreCase(String.valueOf(form.get("confirm")).trim())) {
          yield Outcome.refused("Type 'delete' in the box to confirm. Nothing was changed.");
        }
        io.hearth.people.Erasure.Report report = io.hearth.people.Erasure.erase(accounts,
            accessLog, target, me.id(), form.get("and_words") != null);
        yield Outcome.done(report.email() + " is gone, along with " + report.describe() + ".",
            toList);
      }
      case "reject", "reject_and_ban" -> {
        // Reject means no, not "not yet". The account, its profile and its answers go, because
        // keeping a rejected stranger's data is keeping data nobody will look at again.
        if (targetIsAdmin) {
          yield Outcome.refused("Admins cannot be rejected. Remove their admin role first.");
        }
        boolean andBan = form.get("action").equals("reject_and_ban");
        if (andBan) {
          // before the erasure, because the ban is written from the address on the account
          accounts.bans.ban(target.email(), "rejected by " + me.email(), me.id());
        }
        // the same erasure a request to be forgotten gets. It used to be a smaller sweep that left
        // the address in four other tables, which made "rejected and removed" not quite true.
        io.hearth.people.Erasure.Report report =
            io.hearth.people.Erasure.erase(accounts, accessLog, target, me.id(), false);
        yield Outcome.done(report.email()
            + (andBan ? " was rejected, removed and banned." : " was rejected and removed."),
            toList);
      }
      case "disable" -> {
        if (targetIsAdmin && target.id() != me.id()) {
          yield Outcome.refused("Remove their admin role before turning the account off.");
        }
        accounts.users.setDisabled(target.id(), true);
        accounts.sessions.revokeAllFor(target.id());
        yield Outcome.done(target.email() + " is turned off, and has been signed out.");
      }
      case "enable" -> {
        accounts.users.setDisabled(target.id(), false);
        yield Outcome.done(target.email() + " is turned back on.");
      }
      case "grant_admin" -> {
        accounts.roles.grant(target.id(), Roles.ADMIN, me.id());
        accounts.users.approve(target.id(), me.id());
        accounts.welcome(target);
        yield Outcome.done(target.email() + " is now an admin.");
      }
      case "revoke_admin" -> {
        if (accounts.access.isBootstrapAdmin(target.email())) {
          yield Outcome.refused(target.email() + " is an admin because the config says so; remove"
              + " them from admin_emails and restart to change that.");
        }
        accounts.roles.revoke(target.id(), Roles.ADMIN);
        yield Outcome.done(target.email() + " is no longer an admin.");
      }
      default -> Outcome.refused("That is not something this page can do.");
    };
  }

  private Outcome actOnBan(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    String action = String.valueOf(form.get("action"));
    if (action.equals("lift")) {
      Long id = longOf(form.get("id"));
      if (id == null) {
        return Outcome.refused("That ban could not be found.");
      }
      accounts.bans.lift(id, me.id());
      return Outcome.done("ban lifted.");
    }
    if (!action.equals("ban")) {
      return Outcome.refused("That is not something this page can do.");
    }
    String email = Tokens.normalizeEmail(form.get("email"));
    if (!Tokens.looksLikeEmail(email)) {
      return Outcome.refused("That does not look like an email address.");
    }
    if (accounts.access.isBootstrapAdmin(email)) {
      return Outcome.refused("That address is an admin in the config; it cannot be banned.");
    }
    UserRecord existing = accounts.users.byEmail(email);
    if (existing != null && accounts.access.isAdmin(existing)) {
      return Outcome.refused("That address belongs to an admin; remove their role first.");
    }
    accounts.bans.ban(email, form.get("reason"), me.id());
    if (existing != null) {
      io.hearth.people.Erasure.Report report =
          io.hearth.people.Erasure.erase(accounts, accessLog, existing, me.id(), false);
      return Outcome.done(email + " is banned, and the account was removed along with "
          + report.describe() + ".");
    }
    return Outcome.done(email + " is banned.");
  }

  /**
   * Invitations: write one, send it, chase it, or give up on it.
   *
   * Sending is separate from creating because they fail for different reasons and at different
   * times -- an address that was mistyped is a bad invitation, an SES key that is wrong is a bad
   * afternoon, and conflating them means one error message for two problems.
   */
  private Outcome actOnInvite(DomainConfig config, Accounts accounts, Forms form, UserRecord me)
      throws SQLException {
    String action = String.valueOf(form.get("action"));
    if (action.equals("create")) {
      // no note, and that is the change: one message for the whole community, written once in the
      // config, and inviting somebody is an address in a box. A per-invitation line meant every
      // admin composing a sentence under pressure, and it is the field that made this page clunky.
      // Sending is what the button says, so it is not also a box.
      //
      // Writing one down without sending it was a state with no purpose: an invitation nobody
      // received is a row, and the person who pressed "Send the invitation" was told it went.
      Invitations.Result result = invitations.invite(config, accounts, form.get("email"),
          null, me.id(), me.email(), true, false);
      return result.ok()
          ? Outcome.done("Invitation to " + result.email() + " " + result.detail() + ".",
              domain -> AdminView.Section.invites.path(domain))
          : Outcome.refused(result.email() + ": " + result.detail() + ".");
    }
    if (action.equals("bulk")) {
      if (!accounts.access.can(me, Permission.invites_bulk)) {
        return Outcome.refused("You are not able to send invitations in bulk.");
      }
      String pasted = form.text("addresses");
      Outcome oversized = oversized(form);
      if (oversized != null) {
        return oversized;
      }
      List<Invitations.Result> results =
          invitations.bulk(config, accounts, pasted, null, me.id(), me.email(),
              form.get("send") != null);
      if (results.isEmpty()) {
        return Outcome.refused("No email addresses in that.");
      }
      long ok = results.stream().filter(Invitations.Result::ok).count();
      StringBuilder said = new StringBuilder(ok + " of " + results.size() + " went out.");
      // every refusal named. A bulk operation that reports only the total hides the ones that
      // failed, and those are the addresses somebody has to do something about.
      for (Invitations.Result result : results) {
        if (!result.ok()) {
          said.append(' ').append(result.email()).append(" -- ").append(result.detail()).append('.');
        }
      }
      return ok == results.size()
          ? Outcome.done(said.toString(), domain -> AdminView.Section.invites.path(domain))
          : Outcome.refused(said.toString());
    }
    Long id = longOf(form.get("id"));
    if (id == null) {
      return Outcome.refused("That invitation could not be found.");
    }
    Invites.Invite invite = accounts.invites.byId(id);
    if (invite == null) {
      return Outcome.refused("That invitation could not be found.");
    }
    return switch (action) {
      case "send" -> send(config, accounts, invite, me);
      case "revoke" -> {
        if (invite.converted()) {
          yield Outcome.refused("That one already became a member; there is nothing to revoke.");
        }
        accounts.invites.revoke(id, me.id());
        yield Outcome.done("Invitation to " + invite.email() + " revoked.");
      }
      case "delete" -> {
        accounts.invites.delete(id, me.id());
        yield Outcome.done("Invitation to " + invite.email() + " deleted.");
      }
      default -> Outcome.refused("That is not something this page can do.");
    };
  }

  /** put one in the post, and record exactly what the mailer said */
  private Outcome send(DomainConfig config, Accounts accounts, Invites.Invite invite, UserRecord me)
      throws SQLException {
    Invitations.Result result = invitations.sendTouch(config, accounts, invite);
    return result.ok()
        ? Outcome.done("Invitation to " + result.email() + ": " + result.detail() + ".")
        : Outcome.refused("Could not send to " + result.email() + ": " + result.detail());
  }

  private Outcome actOnContent(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    String action = String.valueOf(form.get("action"));
    if (action.equals("delete")) {
      Long id = longOf(form.get("id"));
      if (id == null) {
        return Outcome.refused("That page could not be found.");
      }
      accounts.site.store().deleteContent(id, me.id());
      return Outcome.done("page deleted.");
    }
    if (action.equals("restore")) {
      return restoreVersion(accounts, form, me);
    }
    if (action.equals("import")) {
      // Bringing a bundle back.
      //
      // Both permissions, because an import writes pages *and* puts them live: a bundle whose rows
      // say published would otherwise be a way for somebody who may write but not publish to
      // publish. It is one decision on one screen, so it asks for both rather than half-applying.
      if (!accounts.access.can(me, Permission.content_write)
          || !accounts.access.can(me, Permission.content_publish)) {
        return Outcome.refused("Bringing a bundle in writes pages and puts them live, so it needs"
            + " both 'write pages' and 'publish pages'.");
      }
      String json = form.text("bundle");
      Outcome tooBig = oversized(form);
      if (tooBig != null) {
        return tooBig;
      }
      if (json == null || json.isBlank()) {
        return Outcome.refused("Paste the JSON, or upload the file into the box.");
      }
      io.hearth.content.Bundle.Report report =
          io.hearth.content.Bundle.apply(accounts.site.store(), json, me.id(), me.email());
      verbose.say("admin: " + me.email() + " imported a bundle -- " + report.describe());
      return report.total() == 0 && !report.problems().isEmpty()
          ? Outcome.refused(report.describe())
          : Outcome.done(report.describe(), site -> AdminView.Section.content.path(site));
    }
    boolean suggesting = action.equals("suggest");
    if (!action.equals("save") && !suggesting) {
      return Outcome.refused("That is not something this page can do.");
    }
    if (suggesting && !accounts.access.can(me, Permission.content_propose)) {
      return Outcome.refused("You are not able to suggest edits.");
    }
    if (!suggesting && !accounts.access.can(me, Permission.content_write)) {
      // somebody who may only suggest cannot save by posting the other action; the button they see
      // is a courtesy, this is the rule
      return Outcome.refused("You are not able to save pages. You can suggest a change instead.");
    }
    String uri = form.get("uri");
    if (uri == null || !uri.startsWith("/") || uri.length() > 512) {
      return Outcome.refused("A page needs a uri that starts with '/'.");
    }
    ContentRecord.Kind kind = ContentRecord.Kind.of(form.get("kind"));
    // A page for one row needs somewhere in its address to say which row.
    //
    // Without the token it can never match anything, which looks from the outside exactly like the
    // page being broken -- so it is refused here rather than saved and quietly answering 404.
    if (kind.isFeed() && !kind.listing && !uri.contains(kind.token)) {
      return Outcome.refused("A " + kind.label + " needs " + kind.token + " somewhere in its"
          + " address, which is how it knows which one to show.");
    }
    String templateName = kind.wantsTemplate() ? form.get("template_name") : null;
    com.fasterxml.jackson.databind.node.ObjectNode node =
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    if (templateName != null) {
      TemplateRecord template = accounts.site.store().templateByName(templateName);
      if (template != null) {
        for (TemplateField field : template.fields()) {
          String value = form.text("field_" + field.name());
          if (field.required() && (value == null || value.isBlank())) {
            return Outcome.refused("'" + field.labelOr() + "' is required by the "
                + templateName + " template.");
          }
          node.put(field.name(), value == null ? "" : value);
        }
      }
    }
    // how many rows a listing shows, in the same blob the template's fields live in rather than in
    // a column: it is a property of this one page, and a column would be null on every other row
    if (kind.listing) {
      String size = form.get("page_size");
      if (size != null && !size.isBlank()) {
        node.put("page_size", size.trim());
      }
      String sort = form.get("sort");
      if (sort != null && kind.sorts().contains(sort)) {
        node.put("sort", sort);
      }
      if (kind == ContentRecord.Kind.place_listing) {
        String placeKind = form.get("place_kind");
        node.put("place_kind", placeKind == null || placeKind.isBlank() ? "*" : placeKind);
      }
    }
    String fields = node.toString();
    Long id = longOf(form.get("id"));
    // renaming onto an address something else already answers on is a refusal, not a constraint
    // violation somebody reads as a 500
    ContentRecord clash = accounts.site.store().byUri(uri);
    if (clash != null && id != null && clash.id() != id) {
      return Outcome.refused(uri + " is already the address of another page.");
    }
    // Publishing is its own permission, and it was not being asked for -- `content_publish` was a
    // checkbox in the role editor that decided nothing, so anybody who could write could also put
    // a page in front of the whole community. The check is on the *change*: a writer editing a page
    // that is already live is not publishing anything, and refusing that would make the box on
    // their screen unusable rather than honest.
    boolean wantsPublished = form.get("published") != null;
    boolean publishedNow = false;
    if (id != null) {
      ContentRecord existing = accounts.site.store().byId(id);
      publishedNow = existing != null && existing.published();
    }
    if (!suggesting && wantsPublished != publishedNow
        && !accounts.access.can(me, Permission.content_publish)) {
      return Outcome.refused(wantsPublished
          ? "You are able to write pages but not to publish them. Save it unpublished and ask"
              + " somebody who can put it live."
          : "You are able to write pages but not to take them down.");
    }
    // the day it counts as published, which somebody can move: a community bringing twenty years
    // of a newsletter in wants 2011 to say 2011
    java.time.LocalDate publishedOn = dateOf(form.get("published_at"));
    ContentRecord page = new ContentRecord(id == null ? 0 : id, uri,
        form.get("title") == null ? "" : form.get("title"), kind, templateName,
        orEmpty(form.get("nav_folder")), fields,
        form.text("body") == null ? "" : form.text("body"),
        wantsPublished, form.get("human_only") != null,
        publishedOn == null ? null : java.sql.Timestamp.valueOf(publishedOn.atStartOfDay()),
        null, null, me.id());
    Outcome refused = oversized(form);
    if (refused != null) {
      return refused;
    }
    if (suggesting) {
      // stored as the same canonical document a version is, so approving it is a plain save and
      // the reviewer sees exactly what the page will become
      int base = id == null ? 0 : accounts.site.store().versions().latestVersion(id);
      accounts.site.store().proposals().propose(id, uri, page.title(),
          ContentVersions.documentOf(page), base, form.text("suggestion_note"), me.id(),
          me.email());
      return Outcome.done("Suggested. Somebody who can publish will take a look.",
          config -> AdminView.Section.content.path(config));
    }
    accounts.site.store().save(page, me.id(), me.email());
    return Outcome.done(uri + " saved.", config -> AdminView.Section.content.path(config));
  }

  private Outcome actOnTemplate(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    String action = String.valueOf(form.get("action"));
    if (action.equals("delete")) {
      Long id = longOf(form.get("id"));
      if (id == null) {
        return Outcome.refused("That template could not be found.");
      }
      accounts.site.store().deleteTemplate(id, me.id());
      return Outcome.done("template deleted.");
    }
    if (!action.equals("save")) {
      return Outcome.refused("That is not something this page can do.");
    }
    String name = form.get("name");
    if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}")) {
      return Outcome.refused("A template name is letters, digits, underscore or hyphen.");
    }
    List<TemplateField> fields = new ArrayList<>();
    for (int k = 0; k < TemplateField.MAX_FIELDS; k++) {
      String fieldName = form.get("p_name_" + k);
      if (fieldName == null) {
        continue;
      }
      if (!TemplateField.isValidName(fieldName)) {
        return Outcome.refused("'" + fieldName + "' is not a usable field name -- lowercase letters,"
            + " digits and underscore, starting with a letter.");
      }
      fields.add(new TemplateField(fieldName, TemplateField.Type.of(form.get("p_type_" + k)),
          form.get("p_label_" + k), form.get("p_help_" + k), form.get("p_required_" + k) != null));
    }
    String templateBody = orEmpty(form.text("body"));
    Outcome refused = oversized(form);
    if (refused != null) {
      return refused;
    }
    // The index half is edited on its own screen now, so a form that does not mention it keeps
    // what is there. Reading these as "unticked, no path" would mean saving a page template
    // silently switching its directory off, which is the worst kind of bug: invisible, and
    // discovered by somebody else finding a 404 where their blog used to be.
    TemplateRecord had = accounts.site.store().templateByName(name);
    boolean mentionsIndex = form.all().containsKey("directory_path")
        || form.all().containsKey("directory");
    boolean directory = mentionsIndex ? form.get("directory") != null
        : had != null && had.directory();
    String directoryPath = mentionsIndex ? orEmpty(form.get("directory_path")).trim()
        : (had == null ? "" : had.directoryPath());
    if (directory && !directoryPath.startsWith("/")) {
      return Outcome.refused("A directory index needs an address to live at, starting with '/'.");
    }
    String pattern = mentionsIndex ? orEmpty(form.get("directory_pattern")).trim()
        : (had == null ? "" : had.directoryPattern());
    if (directory && !pattern.isEmpty() && !pattern.contains(TemplateRecord.PAGE_TOKEN)) {
      return Outcome.refused("The pattern needs " + TemplateRecord.PAGE_TOKEN
          + " in it, which is where the page number goes.");
    }
    int pageSize = mentionsIndex ? (int) longOr(form.get("directory_page_size"), 10)
        : (had == null ? 10 : had.pageSize());
    accounts.site.store().saveTemplate(name, templateBody, TemplateField.toBlob(fields),
        directory, directoryPath, pattern, pageSize,
        mentionsIndex ? ("oldest".equals(form.get("directory_order")) ? "oldest" : "newest")
            : (had == null || had.newestFirst() ? "newest" : "oldest"), me.id());
    return Outcome.done("template " + name + " saved; every page using it was re-rendered.",
        config -> AdminView.Section.templates.path(config));
  }

  private Outcome actOnQuestion(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    String action = String.valueOf(form.get("action"));
    if (action.equals("delete")) {
      Long id = longOf(form.get("id"));
      if (id == null) {
        return Outcome.refused("That question could not be found.");
      }
      accounts.people.deleteQuestion(id, me.id());
      return Outcome.done("question deleted; everybody's remaining count is being recalculated.");
    }
    if (!action.equals("save")) {
      return Outcome.refused("That is not something this page can do.");
    }
    String prompt = form.text("prompt");
    if (prompt == null || prompt.isBlank()) {
      return Outcome.refused("A question needs something to ask.");
    }
    Question.Kind kind = Question.Kind.of(form.get("kind"));
    List<String> options = Question.optionsFrom(form.text("options"));
    if (kind == Question.Kind.choice && options.isEmpty()) {
      return Outcome.refused("A dropdown needs at least one option, one per line.");
    }
    String definition = Question.definition(kind, prompt, form.text("help"), options,
        intOr(form.get("min"), 1), intOr(form.get("max"), 5), form.get("required") != null);
    int position = intOr(form.get("position"), 0);
    boolean published = form.get("published") != null;
    Function<DomainConfig, String> toList = config -> AdminView.Section.survey.path(config);
    Outcome refused = oversized(form);
    if (refused != null) {
      return refused;
    }
    Long id = longOf(form.get("id"));
    if (id == null) {
      accounts.people.askQuestion(definition, position, published, me.id());
      return Outcome.done("question asked; everybody now has one more to answer.", toList);
    }
    accounts.people.updateQuestion(id, definition, position, published, me.id());
    return Outcome.done("question updated; everybody's remaining count is being recalculated.", toList);
  }

  /**
   * The cleanup nobody should do by accident.
   *
   * Restoring is free -- the answers never went anywhere. Purging is the cascade: it strips the
   * question's answer out of every sheet in the community, and it says how many it rewrote, because
   * "done" is not an acceptable report for an irreversible rewrite of everybody's data.
   */
  private Outcome actOnRetiredQuestion(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    Long id = longOf(form.get("id"));
    if (id == null) {
      return Outcome.refused("That question could not be found.");
    }
    Question question = accounts.people.questionById(id);
    if (question == null || !question.deleted()) {
      return Outcome.refused("That question is not waiting to be cleaned up.");
    }
    return switch (String.valueOf(form.get("action"))) {
      case "restore" -> {
        accounts.people.restoreQuestion(id, me.id());
        yield Outcome.done("That question is being asked again, with its old answers intact.");
      }
      case "purge" -> {
        int rewritten = accounts.people.purgeQuestion(id, me.id());
        yield Outcome.done("Question deleted for good; " + rewritten
            + " answer sheet(s) were rewritten to drop it.");
      }
      default -> Outcome.refused("That is not something this page can do.");
    };
  }

  private Outcome actOnConnector(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    Long id = longOf(form.get("id"));
    if (id == null) {
      return Outcome.refused("That connector could not be found.");
    }
    return switch (String.valueOf(form.get("action"))) {
      case "disable" -> {
        accounts.oauthClients.setDisabled(id, true, me.id());
        yield Outcome.done("That connector can no longer ask for a token.");
      }
      case "enable" -> {
        accounts.oauthClients.setDisabled(id, false, me.id());
        yield Outcome.done("That connector can ask for a token again.");
      }
      case "disconnect" -> {
        // the tokens first: deleting the registration alone would leave live agent tokens whose
        // client nobody can look up
        io.hearth.mcp.OauthClients.ClientRecord client = accounts.oauthClients.byId(id);
        if (client == null) {
          yield Outcome.refused("That connector could not be found.");
        }
        int revoked = accounts.sessions.revokeAgentsOf(client.name());
        accounts.oauthClients.delete(id, me.id());
        yield Outcome.done("Connector removed and " + revoked + " token(s) revoked.");
      }
      default -> Outcome.refused("That is not something this page can do.");
    };
  }

  // ---- showing things --------------------------------------------------------------------------

  private void show(AdminView.Target target, DomainConfig config, Accounts accounts,
                    ChannelHandlerContext ctx, FullHttpRequest req, UserRecord me,
                    SessionRecord session, WebHandler.Recorder recorder) throws SQLException {
    AdminView.Section section = target.section();
    String csrf = Cookies.stableToken(req);

    if (target.kind() == AdminView.Target.Kind.panel) {
      recorder.status(200);
      Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
          renderPanel(section, config, accounts, req, csrf, me),
          new String[]{HttpHeaderNames.SET_COOKIE.toString(), Cookies.csrf(accounts.security, csrf)});
      return;
    }

    Map<String, Object> model = shell(config, accounts, me, section, csrf);
    model.put("flash", flash.take(Flash.keyFor(session)));
    String template = section.template();

    switch (target.kind()) {
      case create, edit -> {
        template = "admin/" + section.name() + "_form";
        formModel(section, config, accounts, me, model, target.id());
      }
      case review -> {
        template = "admin/people_review";
        reviewModel(config, accounts, model, target.id());
      }
      case export -> {
        // a subject access request, answered by a download rather than by an afternoon with a SQL
        // client. Behind `people_read` like the rest of this section, and logged as its own path.
        UserRecord person = accounts.users.byId(longOr(target.id()));
        if (person == null) {
          recorder.status(404);
          Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND,
              notFoundPage(config, accounts, req));
          return;
        }
        byte[] json = io.hearth.people.DataExport.of(accounts, person, config.name, config.domain);
        verbose.detail("admin: " + me.email() + " exported everything about " + person.email());
        recorder.status(200);
        Responses.send(ctx, req, HttpResponseStatus.OK, "application/json; charset=utf-8", json,
            new String[]{"Content-Disposition", "attachment; filename=\"" + person.id()
                + "-data.json\""});
        return;
      }
      case bundle -> {
        // Every page and every template as one JSON document, or one page of it.
        //
        // Behind the permission that writes content rather than the one that reads it: a bundle is
        // every page including the drafts and the ones locked away from AI, which is a different
        // thing from being able to open the listing.
        if (!accounts.access.can(me, Permission.content_write)) {
          recorder.status(404);
          Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND,
              notFoundPage(config, accounts, req));
          return;
        }
        Long onlyPage = target.id() == null ? null : longOr(target.id());
        byte[] json = io.hearth.content.Bundle.of(accounts.site.store(), config.name, config.domain,
            java.time.Instant.now().toString(), onlyPage);
        verbose.detail("admin: " + me.email() + " downloaded "
            + (onlyPage == null ? "the whole site" : "page " + onlyPage));
        recorder.status(200);
        Responses.send(ctx, req, HttpResponseStatus.OK, "application/json; charset=utf-8", json,
            new String[]{"Content-Disposition", "attachment; filename=\""
                + config.domain + (onlyPage == null ? "-content" : "-page-" + onlyPage)
                + ".json\""});
        return;
      }
      case history -> {
        template = "admin/content_history";
        historyModel(config, accounts, model, target.id());
      }
      case version -> {
        // the preview: just the version, no shell, so it can go straight into a modal
        recorder.status(200);
        Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
            versionPreview(config, accounts, target.id(), csrf),
            new String[]{HttpHeaderNames.SET_COOKIE.toString(), Cookies.csrf(accounts.security, csrf)});
        return;
      }
      case changes -> {
        // the same modal, showing what changed rather than what it became
        recorder.status(200);
        Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
            section == AdminView.Section.proposals
                ? proposalChanges(accounts, target.id())
                : versionChanges(accounts, target.id()),
            new String[]{HttpHeaderNames.SET_COOKIE.toString(), Cookies.csrf(accounts.security, csrf)});
        return;
      }
      default -> {
        if (AdminView.hasPanel(section)) {
          // the page embeds exactly what the panel URL would return; one code path, two entries
          model.put("panel", new String(renderPanel(section, config, accounts, req, csrf, me),
              StandardCharsets.UTF_8));
          model.put("panelUrl", AdminView.panelPath(section, config));
        }
        pageModel(section, config, accounts, me, model, req);
      }
    }

    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
        templates.render(template, model),
        new String[]{HttpHeaderNames.SET_COOKIE.toString(), Cookies.csrf(accounts.security, csrf)},
        (String) model.get("nonce"));
  }

  /** the refreshable part of a section, identical embedded or fetched */
  private byte[] renderPanel(AdminView.Section section, DomainConfig config, Accounts accounts,
                             FullHttpRequest req, String csrf, UserRecord me) throws SQLException {
    Map<String, Object> model = new HashMap<>();
    model.put("csrf", csrf);
    model.put("action", section.path(config));
    model.put("adminUrl", config.urls.admin);
    switch (section) {
      case people -> peoplePanel(model, accounts, config, req);
      case bans -> bansPanel(model, accounts);
      case invites -> invitesPanel(model, accounts, config, req, me);
      case content -> contentPanel(model, accounts, config, req);
      case templates -> templatesPanel(model, accounts, config);
      case survey -> surveyPanel(model, accounts, config, req);
      case retired -> retiredPanel(model, accounts);
      case ai -> aiPanel(model, req);
      case board -> boardPanel(model, accounts, config, req);
      case flagged -> flaggedPanel(model, accounts, config);
      case calendar -> calendarPanel(model, accounts, config);
      case suggestions -> suggestionsPanel(model, accounts, config);
      case roles -> rolesPanel(model, accounts, config);
      case proposals -> proposalsPanel(model, accounts, config);
      case places -> placesPanel(model, accounts, config, req);
      case placetypes -> placeTypesPanel(model, accounts, config);
      case events -> model.put("events", eventRows(events.recent(events.capacity())));
      case attachments -> attachmentsPanel(model, accounts, config, req);
      case tasks -> tasksPanel(model, accounts, config, req);
      case caching -> cachingPanel(model, accounts);
      case async -> asyncPanel(model, accounts, config);
      case logs -> logsPanel(model, config, req);
      default -> {
      }
    }
    return templates.render(AdminView.panelTemplate(section), model);
  }

  /**
   * What the box is asking somebody else, and how it is going.
   *
   * The queue is one for the whole server, because a rate limit is a property of this machine as
   * somebody else's client. The numbers here are therefore global, and the list of finished items
   * is filtered to this community -- another community's addresses on this screen would be a small
   * but real leak between two groups who have nothing to do with each other.
   */
  private void asyncPanel(Map<String, Object> model, Accounts accounts, DomainConfig config)
      throws SQLException {
    // What the *rows* say, which is a different question from what the queue is doing. The queue
    // forgets everything on a restart; the rows are where "this address cannot be found" and "the
    // service was down when we asked" actually live, and they are what the two buttons act on.
    Map<String, Integer> members = accounts.people.placementCounts();
    Map<String, Integer> places = accounts.places.placementCounts();
    model.put("states", placementRows(members, places));
    model.put("stuck", count(members, io.hearth.places.Placement.NOT_FOUND)
        + count(places, io.hearth.places.Placement.NOT_FOUND));
    model.put("retrying", count(members, io.hearth.places.Placement.UNREACHABLE)
        + count(places, io.hearth.places.Placement.UNREACHABLE));
    asyncQueuePanel(model, config);
  }

  private static int count(Map<String, Integer> counts, String state) {
    Integer found = counts.get(state);
    return found == null ? 0 : found;
  }

  private static List<Map<String, Object>> placementRows(Map<String, Integer> members,
                                                         Map<String, Integer> places) {
    String[][] states = {
        {io.hearth.places.Placement.PLACED, "placed"},
        {io.hearth.places.Placement.UNKNOWN, "waiting to be looked up"},
        {io.hearth.places.Placement.UNREACHABLE, "the service could not be reached -- will retry"},
        {io.hearth.places.Placement.NOT_FOUND, "no such address -- nothing will retry on its own"},
    };
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (String[] state : states) {
      int forMembers = count(members, state[0]);
      int forPlaces = count(places, state[0]);
      if (forMembers == 0 && forPlaces == 0) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("label", state[1]);
      row.put("members", forMembers);
      row.put("places", forPlaces);
      row.put("stuck", io.hearth.places.Placement.NOT_FOUND.equals(state[0]));
      rows.add(row);
    }
    return rows;
  }

  private void asyncQueuePanel(Map<String, Object> model, DomainConfig config) {
    io.hearth.places.Geocodes work = geocodes;
    io.hearth.async.AsyncQueue queue = work == null ? null : work.queue();
    model.put("hasQueue", queue != null);
    model.put("geocoding", settings.gps.describe());
    model.put("geocodingOn", work != null && work.on());
    model.put("wantsContact", settings.gps.wantsContact());
    if (queue == null) {
      return;
    }
    io.hearth.async.AsyncQueue.Counts counts = queue.counts();
    model.put("running", queue.isRunning());
    model.put("depth", queue.depth());
    model.put("capacity", io.hearth.async.AsyncQueue.CAPACITY);
    model.put("full", queue.depth() >= io.hearth.async.AsyncQueue.CAPACITY);
    model.put("share", queue.depth() * 100 / io.hearth.async.AsyncQueue.CAPACITY);
    model.put("gap", io.hearth.async.AsyncQueue.GAP_MILLIS / 1000.0);
    model.put("inFlight", queue.inFlight());
    model.put("nextIn", Math.round(queue.waitingFor() / 1000.0));
    long backoff = queue.backoffLeft();
    model.put("backoff", backoff > 0);
    model.put("backoffFor", Math.round(backoff / 1000.0));
    model.put("backoffNext", queue.backoffNext() / 1000);
    model.put("lastProblem", queue.lastProblem());
    model.put("accepted", counts.accepted());
    model.put("answered", counts.answered());
    model.put("nothing", counts.nothing());
    model.put("failed", counts.failed());
    model.put("abandoned", counts.abandoned());
    model.put("refused", counts.refused());
    model.put("attempts", io.hearth.async.AsyncQueue.ATTEMPTS);

    model.put("service", work.service());

    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    long now = System.currentTimeMillis();
    for (io.hearth.async.AsyncQueue.Finished item
        : queue.recentFor(config.databaseDomain())) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("label", item.label());
      row.put("outcome", item.outcome().name());
      row.put("good", item.outcome() == io.hearth.async.AsyncQueue.Outcome.answered);
      row.put("bad", item.outcome() == io.hearth.async.AsyncQueue.Outcome.abandoned
          || item.outcome() == io.hearth.async.AsyncQueue.Outcome.refused);
      row.put("ago", io.hearth.analytics.Machine.uptime(now - item.finishedAt()));
      row.put("millis", item.millis());
      row.put("attempts", item.attempts());
      row.put("detail", item.detail());
      rows.add(row);
    }
    model.put("recent", rows);
    model.put("anyRecent", !rows.isEmpty());
  }

  /**
   * The two things an operator can do about the queue.
   *
   * Clearing is for somebody who queued a mistake -- a bulk import against the wrong file -- and it
   * drops only this community's waiting work, never another's. Re-asking puts everybody who has
   * said where they are and has never been placed back on it, which is the button for after a
   * service was down all day.
   */
  private Outcome actOnAsync(DomainConfig config, Accounts accounts, Forms form, UserRecord me)
      throws SQLException {
    if (!accounts.access.can(me, Permission.system_read)) {
      return Outcome.refused("You are not able to do that.");
    }
    String action = String.valueOf(form.get("action"));
    if (geocodes == null) {
      return Outcome.refused("There is no queue on this server.");
    }
    if (action.equals("clear")) {
      int dropped = geocodes.queue().clear(config.databaseDomain());
      return Outcome.done(dropped + " waiting ask(s) dropped.",
          settings -> AdminView.Section.async.path(settings));
    }
    if (action.equals("retry")) {
      // everything due right now: the row is what says so, so this is a query rather than a
      // memory of what failed
      int queued = geocodes.sweep(config.databaseDomain(), io.hearth.async.AsyncQueue.CAPACITY);
      return Outcome.done(queued == 0 ? "Nothing is waiting to be placed."
          : queued + " lookup(s) queued.",
          settings -> AdminView.Section.async.path(settings));
    }
    if (action.equals("reopen")) {
      // and the harder button: forget every failure, including the addresses the service said it
      // had never heard of. For after somebody fixed whatever was wrong at the other end.
      int reopened = geocodes.reopen(config.databaseDomain());
      int queued = geocodes.sweep(config.databaseDomain(), io.hearth.async.AsyncQueue.CAPACITY);
      return Outcome.done(reopened == 0 ? "Nothing had been given up on."
          : reopened + " given up on, now waiting again (" + queued + " queued).",
          settings -> AdminView.Section.async.path(settings));
    }
    return Outcome.refused("That is not something this page can do.");
  }

  /**
   * Every project on this community, for somebody who keeps the shared ones.
   *
   * <b>What is here and what is deliberately not.</b> The community's own projects are an
   * administrative record and are opened and edited like anything else. A member's are listed --
   * with a name, a count and when it was last touched -- and are not opened from here. Somebody
   * running a community has a legitimate reason to know that half the members have a routine and
   * three have not touched one since February; they have no reason at all to read what Ana lifted
   * on Tuesday, and a screen that showed it would be a screen people stop putting anything into.
   *
   * That is the whole of the inspection design, and it is a smaller answer than "admins see
   * everything" on purpose. A community that genuinely needs to look at one person's log can ask
   * them for the export they can download themselves.
   */
  private void tasksPanel(Map<String, Object> model, Accounts accounts, DomainConfig config,
                          FullHttpRequest req) throws SQLException {
    long now = System.currentTimeMillis();
    ArrayList<Map<String, Object>> shared = new ArrayList<>();
    ArrayList<Map<String, Object>> personal = new ArrayList<>();
    int peopleUsingIt = 0;
    java.util.HashSet<Long> owners = new java.util.HashSet<>();
    for (io.hearth.tasks.Records.Project project : accounts.tasks.allProjects()) {
      List<io.hearth.tasks.Records.Task> tasks = accounts.tasks.tasksIn(project.id());
      int open = 0;
      for (io.hearth.tasks.Records.Task task : tasks) {
        if (!task.done()) {
          open++;
        }
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", project.id());
      row.put("name", project.name());
      row.put("summary", project.summary());
      row.put("count", tasks.size());
      row.put("open", open);
      row.put("board", project.isBoard());
      row.put("archived", project.archived());
      row.put("touched", project.updatedAt() == null ? "" 
          : io.hearth.analytics.Machine.uptime(now - project.updatedAt().getTime()) + " ago");
      if (project.isShared()) {
        // the community's own, which is an administrative record like any other
        row.put("url", config.urls.tasks + "/" + project.id());
        shared.add(row);
      } else {
        // somebody's own. A name and a count, and no way in from here.
        row.put("who", io.hearth.people.Names.nameOf(accounts, project.ownerId()));
        owners.add(project.ownerId());
        personal.add(row);
      }
    }
    peopleUsingIt = owners.size();
    model.put("shared", shared);
    model.put("anyShared", !shared.isEmpty());
    model.put("personal", personal);
    model.put("anyPersonal", !personal.isEmpty());
    model.put("people", peopleUsingIt);

    ArrayList<Map<String, Object>> library = new ArrayList<>();
    for (io.hearth.tasks.Records.Def def : accounts.tasks.sharedDefs()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", def.id());
      row.put("name", def.name());
      row.put("summary", def.summary());
      row.put("measure", def.measure().label);
      row.put("hasInstructions", !def.instructions().isBlank());
      row.put("url", config.urls.tasks + "/library/" + def.id());
      library.add(row);
    }
    model.put("library", library);
    model.put("anyLibrary", !library.isEmpty());
    model.put("tasksUrl", config.urls.tasks);
  }

  /** the top bar, the sidebar, and what every admin page needs */
  private Map<String, Object> shell(DomainConfig config, Accounts accounts, UserRecord me,
                                    AdminView.Section section, String csrf) throws SQLException {
    Map<String, Object> model = new HashMap<>();
    Chrome.admin(model, accounts);
    // the bell belongs on every page somebody works from, and the admin section is where a
    // moderator spends their evening
    model.put("live", true);
    model.put("liveUrl", io.hearth.live.LiveRoutes.ROOT);
    model.put("bellUrl", config.urls.self + "?tab=notifications");
    model.put("meId", me == null ? 0 : me.id());
    model.put("title", section.label + " - " + config.name + " admin");
    model.put("heading", section.label);
    model.put("community", config.name);
    model.put("domain", config.domain);
    model.put("databaseDomain", accounts.databaseDomain);
    model.put("me", me == null ? "" : me.email());
    model.put("csrf", csrf);
    model.put("nonce", Cookies.newCsrfToken());
    model.put("logoutUrl", config.urls.logout);
    model.put("selfUrl", config.urls.self);
    model.put("siteUrl", "/");
    model.put("adminUrl", config.urls.admin);
    model.put("action", section.path(config));
    model.put("sidebar", AdminView.sidebar(config, section, accounts.access.permissionsOf(me)));
    // on a phone the sidebar is a closed menu, so the button says which section is behind it --
    // otherwise the only thing on screen naming the page is the page's own heading, below the fold
    model.put("sectionLabel", section.label);
    // every filter bar on every section draws the same magnifier; one place to change it, and no
    // page has to remember which icon name it wanted
    model.put("searchIcon", Icons.of("search"));
    model.put("plusIcon", Icons.of("plus"));
    return model;
  }

  // ---- section pages ------------------------------------------------------------------------------

  private void pageModel(AdminView.Section section, DomainConfig config, Accounts accounts,
                         UserRecord me, Map<String, Object> model, FullHttpRequest req)
      throws SQLException {
    switch (section) {
      case overview -> overview(model, config, accounts, me);
      case people -> {
        model.put("bootstrapAdmins", String.join(", ", accounts.access.bootstrapAdmins()));
        model.put("anyBootstrapAdmins", !accounts.access.bootstrapAdmins().isEmpty());
        model.put("states", stateOptions(Forms.query(req.uri(), "state")));
        model.put("q", orEmpty(Forms.query(req.uri(), "q")));
      }
      case content -> {
        model.put("newUrl", AdminView.Section.content.path(config) + "/new");
        model.put("q", orEmpty(Forms.query(req.uri(), "q")));
        model.put("publishedOptions", publishedOptions(Forms.query(req.uri(), "published")));
        // taking the whole site away and bringing it back is its own screen: it is a different
        // job from writing a page, and it was a button competing with "New page"
        if (accounts.access.can(me, Permission.content_write)) {
          model.put("bundlesUrl", AdminView.Section.bundles.path(config));
        }
      }
      case attachments -> {
        model.put("uploadUrl", io.hearth.attach.AttachmentRoutes.UPLOAD);
        model.put("maxMb", config.attachments.maxBytes / (1024 * 1024));
        model.put("accept", acceptOf(config));
        model.put("kinds", kindOptions(Forms.query(req.uri(), "kind")));
        model.put("q", orEmpty(Forms.query(req.uri(), "q")));
        model.put("folder", orEmpty(Forms.query(req.uri(), "folder")));
        ArrayList<Map<String, Object>> folders = new ArrayList<>();
        for (String folder : accounts.attachments.folders()) {
          folders.add(Map.of("value", folder));
        }
        model.put("folders", folders);
        ArrayList<Map<String, Object>> tags = new ArrayList<>();
        for (String tag : accounts.attachments.tags(24)) {
          tags.add(Map.of("tag", tag,
              "url", AdminView.Section.attachments.path(config) + "?q=" + urlencode(tag)));
        }
        model.put("tags", tags);
        model.put("anyTags", !tags.isEmpty());
        model.put("extensions", String.join(", ", config.attachments.extensions));
        model.put("count", accounts.attachments.count());
        model.put("held", megabytes(accounts.attachments.totalBytes()));
        model.put("unusedUrl", AdminView.Section.unused.path(config));
      }
      case unused -> unused(model, accounts, config);
      case directories -> {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (TemplateRecord template : accounts.site.store().allTemplates(200)) {
          LinkedHashMap<String, Object> row = new LinkedHashMap<>();
          row.put("name", template.name());
          row.put("publishes", template.publishesDirectory());
          row.put("path", template.directoryPath());
          row.put("pattern", template.directoryPattern());
          row.put("size", template.pageSize());
          row.put("order", template.newestFirst() ? "newest first" : "oldest first");
          row.put("entries", accounts.site.store().countUsingTemplate(template.name()));
          row.put("ownIndex", template.hasOwnIndex());
          row.put("editUrl",
              AdminView.Section.directories.path(config) + "/edit/" + template.name());
          rows.add(row);
        }
        model.put("directories", rows);
        model.put("anyDirectories", !rows.isEmpty());
        model.put("templatesUrl", AdminView.Section.templates.path(config));
      }
      case bundles -> {
        model.put("bundleUrl", AdminView.Section.content.path(config) + "/bundle");
        model.put("uploadUrl", io.hearth.attach.AttachmentRoutes.UPLOAD);
        model.put("apiUrl", "https://" + config.domain + io.hearth.api.ApiRoutes.V1 + "/content");
        model.put("apiPage", config.has(io.hearth.vhost.Surface.api)
            ? io.hearth.api.ApiConfig.PATH : null);
        model.put("canImport", accounts.access.can(me, Permission.content_publish));
        model.put("pages", accounts.site.store().contentCount());
        model.put("templateCount", accounts.site.store().templateCount());
      }
      case templates -> model.put("newUrl", AdminView.Section.templates.path(config) + "/new");
      case survey -> {
        model.put("newUrl", AdminView.Section.survey.path(config) + "/new");
        model.put("q", orEmpty(Forms.query(req.uri(), "q")));
        model.put("kinds", questionKindOptions(Forms.query(req.uri(), "kind")));
        int retired = accounts.people.deletedQuestions().size();
        model.put("retiredCount", retired);
        model.put("anyRetired", retired > 0);
        model.put("retiredUrl", AdminView.Section.retired.path(config));
      }
      case navigation -> navigation(model, accounts, config);
      case configuration -> configuration(model, config, accounts);
      case setup -> setupWizard(model, config, accounts, req);
      case appearance -> appearance(model, accounts);
      case engagement -> engagement(model, config, accounts);
      case legal -> legal(model, config, accounts);
      case messages -> messages(model, config, accounts);
      case board -> {
        model.put("boardUrl", config.urls.board);
        model.put("q", orEmpty(Forms.query(req.uri(), "q")));
      }
      case roles -> model.put("newUrl", AdminView.Section.roles.path(config) + "/new");
      case places -> {
        model.put("newUrl", AdminView.Section.places.path(config) + "/new");
        model.put("kindsUrl", AdminView.Section.placetypes.path(config));
        model.put("publicUrl", config.urls.places);
        model.put("q", orEmpty(Forms.query(req.uri(), "q")));
        model.put("anyKinds", !accounts.places.allTypes().isEmpty());
      }
      case placetypes -> {
        model.put("newUrl", AdminView.Section.placetypes.path(config) + "/new");
        model.put("backUrl", AdminView.Section.places.path(config));
      }
      case proposals -> model.put("contentUrl", AdminView.Section.content.path(config));
      case calendar -> {
        model.put("newUrl", AdminView.Section.calendar.path(config) + "/new");
        model.put("calendarUrl", config.urls.calendar);
        int open = accounts.calendar.openSuggestions();
        model.put("openSuggestions", open);
        model.put("anySuggested", open > 0);
        model.put("suggestionsUrl", AdminView.Section.suggestions.path(config));
        model.put("suggestionsOn", config.calendar.suggestions);
        // what an invitation would say and whether it could be sent at all, both on the screen
        // where somebody is about to press the button
        String why = io.hearth.calendar.Invitations.whyNot(config, inboundMail);
        model.put("invitesOn", why == null);
        model.put("invitesWhyNot", why);
        io.hearth.mail.SystemTemplates.Wording wording =
            accounts.messages.of(io.hearth.mail.SystemTemplate.event_invite);
        model.put("inviteTemplate", wording.body());
        model.put("customTemplate", wording.overridden());
        model.put("messagesUrl", AdminView.Section.messages.path(config));
        model.put("replyTo", io.hearth.calendar.Invitations.replyTo(config));
        model.put("remindOn", !config.calendar.remindDaysBefore.isEmpty());
        model.put("remindDays", config.calendar.remindDaysBefore.stream()
            .map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")));
      }
      case suggestions -> {
        model.put("calendarUrl", AdminView.Section.calendar.path(config));
        model.put("suggestionsOn", config.calendar.suggestions);
      }
      case invites -> {
        invitesPage(model, accounts, req);
        model.put("canBulk", accounts.access.can(me, Permission.invites_bulk));
        model.put("newUrl", AdminView.Section.invites.path(config) + "/new");
        model.put("bulkMax", io.hearth.people.Invitations.MAX_BULK);
        model.put("cadence", config.invites.remindersEnabled
            ? "A welcome goes out first. If nothing happens, a friendly reminder follows after "
                + config.invites.reminderAfterDays + " day(s), and a last note "
                + config.invites.apologyAfterDays + " day(s) after that."
            : "Reminders are switched off for this community, so only the welcome is sent.");
      }
      case settings -> settings(model, config, accounts);
      case retired -> model.put("backUrl", AdminView.Section.survey.path(config));
      case ai -> ai(model, accounts, config, req);
      case events -> {
        model.put("emitted", events.emitted());
        model.put("capacity", events.capacity());
      }
      case machine -> machine(model, config);
      case analytics -> analytics(model, config);
      case caching -> model.put("capacity", accessLog.capacity());
      case async -> asyncPanel(model, accounts, config);
      case tasks -> tasksPanel(model, accounts, config, req);
      case logs -> {
        model.put("q", orEmpty(Forms.query(req.uri(), "q")));
        model.put("errorsOnly", "1".equals(Forms.query(req.uri(), "errors")));
        model.put("capacity", accessLog.capacity());
      }
      default -> {
      }
    }
  }

  /**
   * The overview, which used to lead with the last ten row changes.
   *
   * That was a debugging view wearing a dashboard's clothes: interesting exactly once, when you are
   * wondering whether writes are landing, and never again. Who is here right now is the thing an
   * administrator actually looks at this page for -- it is the only number that changes while you
   * are watching, and it is the only one you can act on, because every name is a person you could
   * say something to. The row changes are still at /admin/system/events, where somebody goes when
   * they want them.
   */
  /**
   * The overview, showing each person only what they could have reached anyway.
   *
   * <b>This page is behind `admin_enter`, which every permission implies</b> -- so anybody with any
   * role at all lands here, and it used to print who was online *with their email addresses* and
   * every count in the database. Somebody trusted to choose the community's colours could read the
   * membership off the front page of the admin section, which is not a decision anybody made.
   *
   * So each block asks for what its own section asks for. What is left for a narrow role is a page
   * that says little, which is the honest outcome: an overview is a summary of what you can do, and
   * if you can do one thing it is a summary of one thing.
   */
  private void overview(Map<String, Object> model, DomainConfig config, Accounts accounts,
                        UserRecord me) throws SQLException {
    boolean people = accounts.access.can(me, Permission.people_read);
    boolean system = accounts.access.can(me, Permission.system_read);
    boolean content = accounts.access.can(me, Permission.content_read);
    model.put("showPeople", people);
    model.put("showSystem", system);
    model.put("showContent", content);
    if (people) {
      model.put("people", accounts.users.count());
      model.put("waiting", accounts.users.awaitingApproval(PAGE_SIZE).size());
      model.put("sessions", accounts.sessions.count());
      model.put("banCount", accounts.bans.count());
    }
    if (content) {
      model.put("pages", accounts.site.store().contentCount());
      model.put("templateCount", accounts.site.store().templateCount());
      model.put("questionCount", accounts.people.questionCount());
    }
    // The process numbers used to be here and are not any more.
    //
    // Requests, error rate, mutations and live pings are a debugging view, and a debugging view on
    // the page everybody lands on is a page that teaches people to skim. They live under System,
    // which is where somebody goes when they are asking a question about the machine -- and the
    // overview keeps what a person running a community can act on.
    if (system) {
      model.put("systemUrl", AdminView.Section.machine.path(config));
    }

    // who is here is a fact about people, so it needs the permission that reading people needs --
    // and the address beside each name is the reason, since this is the only screen in the product
    // that prints one
    ArrayList<Map<String, Object>> here = new ArrayList<>();
    if (people) {
      io.hearth.live.LiveHub hub = live.forDomain(config.domain);
      for (long userId : hub.online()) {
        UserRecord user = accounts.users.byId(userId);
        if (user == null) {
          continue;
        }
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("name", displayNameOf(accounts, user));
        row.put("email", user.email());
        row.put("url", AdminView.Section.people.path(config) + "/review/" + user.id());
        row.put("admin", accounts.access.can(user, Permission.everything));
        here.add(row);
      }
    }
    model.put("online", here);
    model.put("onlineCount", here.size());
    model.put("anyOnline", !here.isEmpty());
  }

  /**
   * The box: processor, memory, and a day of both.
   *
   * Read from `/proc` where there is one, which is the assumption this server already makes about
   * where it runs. Anything unreadable is *absent* rather than guessed at -- a made-up number on an
   * operations screen is worse than a blank one, because somebody will act on it.
   */
  private void machine(Map<String, Object> model, DomainConfig config) {
    io.hearth.analytics.Machine.Now now = machine.now();
    model.put("cpuNow", now.cpuKnown() ? now.cpuPercent() : -1);
    model.put("cpuKnown", now.cpuKnown());
    int cpuAverage = machine.averageCpu();
    model.put("cpuAverage", cpuAverage < 0 ? "not yet" : cpuAverage + "%");
    model.put("processors", now.processors());
    model.put("load", String.format("%.2f", now.loadAverage()));
    // load is per runnable process rather than a percentage, and reading it as one is the most
    // common mistake on a screen like this
    model.put("loadPerCore", String.format("%.2f", now.loadAverage() / Math.max(1, now.processors())));

    model.put("heapUsed", io.hearth.analytics.Machine.bytes(now.heapUsed()));
    model.put("heapMax", io.hearth.analytics.Machine.bytes(now.heapMax()));
    model.put("heapCommitted", io.hearth.analytics.Machine.bytes(now.heapCommitted()));
    model.put("heapPercent", now.heapPercent());
    model.put("hostKnown", now.hostKnown());
    model.put("hostTotal", io.hearth.analytics.Machine.bytes(now.hostTotal()));
    model.put("hostAvailable", io.hearth.analytics.Machine.bytes(now.hostAvailable()));
    model.put("hostPercent", now.hostPercent());
    model.put("uptime", io.hearth.analytics.Machine.uptime(now.uptimeMillis()));
    model.put("version", io.hearth.Server.VERSION);
    model.put("samples", machine.size());
    model.put("sampleMinutes", io.hearth.analytics.Machine.SAMPLE_SECONDS / 60);

    // the graph, as bars a template can draw. No script and no canvas: a column with a height is a
    // graph, and it is one a screen reader can be given numbers for.
    ArrayList<Map<String, Object>> bars = new ArrayList<>();
    for (Map<String, Object> point : machine.graph(96)) {
      LinkedHashMap<String, Object> bar = new LinkedHashMap<>();
      int cpu = (int) point.get("cpu");
      int host = (int) point.get("host");
      int heap = (int) point.get("heap");
      bar.put("cpu", cpu);
      bar.put("host", host);
      bar.put("heap", heap);
      bar.put("cpuHeight", Math.max(1, cpu));
      bar.put("hostHeight", Math.max(1, host));
      bar.put("heapHeight", Math.max(1, heap));
      bar.put("when", java.time.Instant.ofEpochMilli((long) point.get("at"))
          .atZone(config.zone).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
      bars.add(bar);
    }
    model.put("bars", bars);
    model.put("anyBars", !bars.isEmpty());
    model.put("hours", io.hearth.analytics.Machine.HISTORY / 60);
    model.put("averageMinutes", io.hearth.analytics.Machine.AVERAGE_MINUTES);

    // the process numbers that used to be on the overview
    AccessLog.Summary summary = accessLog.summarize(null, 5);
    model.put("hits", summary.total());
    model.put("errorRate", summary.errorRate());
    model.put("livePings", accessLog.livePings());
    model.put("eventsEmitted", events.emitted());
    model.put("eventsUrl", AdminView.Section.events.path(config));
  }

  /** what to call somebody: what they chose, or the part of their address before the @ */
  private static String displayNameOf(Accounts accounts, UserRecord user) {
    try {
      io.hearth.people.ProfileRecord profile = accounts.people.profileOf(user.id());
      if (profile != null && profile.displayName() != null && !profile.displayName().isBlank()) {
        return profile.displayName();
      }
    } catch (SQLException ex) {
      // a name is not worth failing a page for
    }
    int at = user.email().indexOf('@');
    return at > 0 ? user.email().substring(0, at) : user.email();
  }

  private void navigation(Map<String, Object> model, Accounts accounts, DomainConfig config) throws SQLException {
    ArrayList<Map<String, Object>> folders = new ArrayList<>();
    for (Map.Entry<String, List<ContentRecord>> entry : accounts.site.store().navigation().entrySet()) {
      LinkedHashMap<String, Object> folder = new LinkedHashMap<>();
      folder.put("name", entry.getKey());
      ArrayList<Map<String, Object>> pages = new ArrayList<>();
      for (ContentRecord page : entry.getValue()) {
        pages.add(navRow(page, config));
      }
      folder.put("pages", pages);
      folder.put("count", pages.size());
      folders.add(folder);
    }
    model.put("folders", folders);
    model.put("anyFolders", !folders.isEmpty());

    ArrayList<Map<String, Object>> orphans = new ArrayList<>();
    for (ContentRecord page : accounts.site.store().allContent(PAGE_SIZE)) {
      if (page.isOutsideNavigation()) {
        orphans.add(navRow(page, config));
      }
    }
    model.put("orphans", orphans);
    model.put("anyOrphans", !orphans.isEmpty());
  }

  private static Map<String, Object> navRow(ContentRecord page, DomainConfig config) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("uri", page.uri());
    row.put("title", page.title());
    row.put("editUrl", AdminView.Section.content.path(config) + "/edit/" + page.id());
    return row;
  }

  private void ai(Map<String, Object> model, Accounts accounts, DomainConfig config,
                  FullHttpRequest req) throws SQLException {
    model.put("enabled", config.has(io.hearth.vhost.Surface.ai));
    model.put("policy", config.mcp.describe());
    model.put("endpoint", config.mcp.path);
    model.put("readOnly", config.mcp.readOnly);
    model.put("recorded", aiLog.recorded());
    model.put("capacity", aiLog.capacity());
    model.put("writes", aiLog.writeCount());
    model.put("q", orEmpty(Forms.query(req.uri(), "q")));
    model.put("writesOnly", "1".equals(Forms.query(req.uri(), "writes")));
    model.put("outcomes", outcomeOptions(Forms.query(req.uri(), "outcome")));

    ArrayList<Map<String, Object>> connectors = new ArrayList<>();
    for (io.hearth.mcp.OauthClients.ClientRecord client : accounts.oauthClients.all(PAGE_SIZE)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", client.id());
      row.put("name", client.name());
      row.put("vendor", client.vendor().label);
      row.put("clientId", client.clientId());
      row.put("redirects", client.redirectList());
      row.put("disabled", client.disabled());
      row.put("created", stamp(client.createdAt()));
      row.put("tokens", accounts.sessions.agentTokensFor(client.name()));
      connectors.add(row);
    }
    model.put("connectors", connectors);
    model.put("anyConnectors", !connectors.isEmpty());
    model.put("lockedPages", lockedCount(accounts));
  }

  private static long lockedCount(Accounts accounts) throws SQLException {
    long locked = 0;
    for (ContentRecord page : accounts.site.store().allContent(PAGE_SIZE)) {
      if (page.humanOnly()) {
        locked++;
      }
    }
    return locked;
  }

  private static List<Map<String, Object>> outcomeOptions(String current) {
    String value = orEmpty(current);
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    option("all", "everything", value.isEmpty() || value.equals("all"), options);
    option("ok", "succeeded", value.equals("ok"), options);
    option("refused", "refused", value.equals("refused"), options);
    option("failed", "failed", value.equals("failed"), options);
    return options;
  }

  private void analytics(Map<String, Object> model, DomainConfig config) {
    AccessLog.Summary summary = accessLog.summarize(config.domain, 12);
    model.put("total", summary.total());
    model.put("errors", summary.errors());
    model.put("errorRate", summary.errorRate());
    model.put("people", summary.people());
    model.put("signedIn", summary.signedIn());
    model.put("medianMillis", summary.medianMillis());
    model.put("windowMinutes", summary.windowMinutes());
    model.put("capacity", accessLog.capacity());
    model.put("topPaths", counts(summary.topPaths(), summary.total()));
    model.put("topIps", counts(summary.topIps(), summary.total()));
    model.put("topAgents", counts(summary.topAgents(), summary.total()));
    model.put("topUsers", counts(summary.topUsers(), summary.total()));
    model.put("statuses", counts(summary.statuses(), summary.total()));
    model.put("unknownAgents", accessLog.agents().unknownCount());
    ArrayList<Map<String, Object>> unknowns = new ArrayList<>();
    accessLog.agents().unknowns().forEach((agent, count) -> {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("label", agent);
      row.put("count", count);
      unknowns.add(row);
    });
    model.put("unknowns", unknowns);
    model.put("anyUnknowns", !unknowns.isEmpty());
    model.put("logUrl", AdminView.Section.logs.path(config));
  }

  // ---- panels ---------------------------------------------------------------------------------------

  private void peoplePanel(Map<String, Object> model, Accounts accounts, DomainConfig config,
                           FullHttpRequest req) throws SQLException {
    String query = orEmpty(Forms.query(req.uri(), "q")).toLowerCase(Locale.ROOT);
    String state = orEmpty(Forms.query(req.uri(), "state"));
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (UserRecord person : accounts.users.recent(PAGE_SIZE)) {
      boolean isAdmin = accounts.access.isAdmin(person);
      boolean bootstrap = accounts.access.isBootstrapAdmin(person.email());
      if (!query.isEmpty() && !person.email().toLowerCase(Locale.ROOT).contains(query)) {
        continue;
      }
      if (!matchesState(state, person, isAdmin)) {
        continue;
      }
      ProfileRecord profile = accounts.people.profileOf(person.id());
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", person.id());
      row.put("email", person.email());
      row.put("approved", person.isApproved());
      row.put("disabled", person.disabled());
      // config admins are red, promoted admins purple: one is a fact about a file on the box, the
      // other a decision somebody made in this UI, and confusing them wastes an afternoon
      row.put("configAdmin", bootstrap);
      row.put("promotedAdmin", isAdmin && !bootstrap);
      row.put("hasProfile", profile.isFilledIn());
      row.put("answered", accounts.people.answersOf(person.id()).answered());
      row.put("events", person.signupEvents());
      row.put("signals", orEmpty(person.signupSignals()));
      row.put("created", stamp(person.createdAt()));
      // where they came from. An invitation that converted is the one thing about a member that
      // the account itself cannot tell you, and it is the whole point of tracking invitations --
      // a funnel with no way to see who came through it is a number nobody can act on.
      Invites.Invite from = accounts.invites.forUser(person.id());
      row.put("invited", from != null);
      row.put("invitedBy", from == null ? "" : from.createdByEmail());
      row.put("reviewUrl", AdminView.Section.people.path(config) + "/review/" + person.id());
      rows.add(row);
    }
    model.put("people", rows);
    model.put("anyPeople", !rows.isEmpty());
    model.put("count", rows.size());
  }

  private static boolean matchesState(String state, UserRecord person, boolean isAdmin) {
    return switch (state) {
      case "waiting" -> !person.isApproved() && !person.disabled();
      case "approved" -> person.isApproved() && !person.disabled();
      case "disabled" -> person.disabled();
      case "admin" -> isAdmin;
      default -> true;
    };
  }

  private void bansPanel(Map<String, Object> model, Accounts accounts) throws SQLException {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Bans.BanRecord ban : accounts.bans.all(PAGE_SIZE)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", ban.id());
      row.put("email", ban.email());
      row.put("reason", ban.reason());
      row.put("created", stamp(ban.createdAt()));
      rows.add(row);
    }
    model.put("bans", rows);
    model.put("anyBans", !rows.isEmpty());
    model.put("count", rows.size());
  }

  private void invitesPage(Map<String, Object> model, Accounts accounts, FullHttpRequest req)
      throws SQLException {
    Invites.Funnel funnel = accounts.invites.funnel();
    model.put("total", funnel.total());
    model.put("sent", funnel.sent());
    model.put("opened", funnel.opened());
    model.put("clicked", funnel.clicked());
    model.put("converted", funnel.converted());
    model.put("outstanding", funnel.outstanding());
    model.put("openRate", funnel.openRate());
    model.put("clickRate", funnel.clickRate());
    model.put("conversionRate", funnel.conversionRate());
    model.put("stages", stageOptions(Forms.query(req.uri(), "stage")));
    model.put("q", orEmpty(Forms.query(req.uri(), "q")));
  }

  private void invitesPanel(Map<String, Object> model, Accounts accounts, DomainConfig config,
                            FullHttpRequest req, UserRecord me) throws SQLException {
    String query = orEmpty(Forms.query(req.uri(), "q")).toLowerCase(Locale.ROOT);
    String stage = orEmpty(Forms.query(req.uri(), "stage"));
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Invites.Invite invite : accounts.invites.all(PAGE_SIZE)) {
      if (!query.isEmpty() && !invite.email().toLowerCase(Locale.ROOT).contains(query)) {
        continue;
      }
      if (!stage.isEmpty() && !stage.equals("all") && !stage.equals(invite.stage())) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", invite.id());
      row.put("email", invite.email());
      row.put("note", invite.note());
      row.put("stage", invite.stage());
      row.put("sent", invite.sent());
      row.put("opened", invite.opened());
      row.put("clicked", invite.clicked());
      row.put("clicks", invite.clicks());
      row.put("converted", invite.converted());
      row.put("revoked", invite.revoked());
      row.put("opens", invite.opens());
      row.put("invitedBy", invite.createdByEmail());
      row.put("created", stamp(invite.createdAt()));
      row.put("sentAt", stamp(invite.sentAt()));
      row.put("openedAt", stamp(invite.openedAt()));
      row.put("clickedAt", stamp(invite.clickedAt()));
      row.put("convertedAt", stamp(invite.convertedAt()));
      row.put("sendDetail", orEmpty(invite.sendDetail()));
      // ...and only for somebody who can open the people section. A link that answers 404 is a
      // door drawn on a wall: it tells somebody there is something there and refuses to say what.
      if (invite.convertedUser() != null && accounts.access.can(me, Permission.people_read)) {
        row.put("memberUrl",
            AdminView.Section.people.path(config) + "/review/" + invite.convertedUser());
      }
      rows.add(row);
    }
    model.put("invites", rows);
    model.put("anyInvites", !rows.isEmpty());
    model.put("count", rows.size());
  }

  private static List<Map<String, Object>> stageOptions(String current) {
    String value = orEmpty(current);
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    option("all", "every invitation", value.isEmpty() || value.equals("all"), options);
    option("not sent", "written, not sent", value.equals("not sent"), options);
    option("sent", "sent, no evidence of an open", value.equals("sent"), options);
    option("opened", "opened, not clicked", value.equals("opened"), options);
    option("clicked", "clicked, not joined", value.equals("clicked"), options);
    option("joined", "joined", value.equals("joined"), options);
    return options;
  }

  private void contentPanel(Map<String, Object> model, Accounts accounts, DomainConfig config,
                            FullHttpRequest req) throws SQLException {
    String query = orEmpty(Forms.query(req.uri(), "q")).toLowerCase(Locale.ROOT);
    String published = orEmpty(Forms.query(req.uri(), "published"));
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (ContentRecord page : accounts.site.store().allContent(PAGE_SIZE)) {
      if (!query.isEmpty() && !contains(query, page.uri(), page.title(), page.body())) {
        continue;
      }
      if (published.equals("yes") && !page.published()) {
        continue;
      }
      if (published.equals("no") && page.published()) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", page.id());
      row.put("uri", page.uri());
      row.put("title", page.title());
      row.put("kind", page.kind().label);
      row.put("humanOnly", page.humanOnly());
      row.put("template", orEmpty(page.templateName()));
      row.put("folder", orEmpty(page.navFolder()));
      // a page nobody can navigate to is a page nobody will find
      row.put("noNavigation", page.isOutsideNavigation());
      row.put("published", page.published());
      row.put("updated", stamp(page.updatedAt()));
      row.put("editUrl", AdminView.Section.content.path(config) + "/edit/" + page.id());
      rows.add(row);
    }
    model.put("pages", rows);
    model.put("anyPages", !rows.isEmpty());
    model.put("count", rows.size());
  }

  private void templatesPanel(Map<String, Object> model, Accounts accounts, DomainConfig config) throws SQLException {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (TemplateRecord template : accounts.site.store().allTemplates(PAGE_SIZE)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", template.id());
      row.put("name", template.name());
      row.put("uses", accounts.site.store().urisUsingTemplate(template.name()).size());
      row.put("fields", template.fields().size());
      row.put("directory", template.publishesDirectory());
      row.put("directoryPath", template.directoryPath());
      row.put("updated", stamp(template.updatedAt()));
      row.put("editUrl", AdminView.Section.templates.path(config) + "/edit/" + template.name());
      rows.add(row);
    }
    model.put("templates", rows);
    model.put("anyTemplates", !rows.isEmpty());
    model.put("count", rows.size());
  }

  private void surveyPanel(Map<String, Object> model, Accounts accounts, DomainConfig config,
                           FullHttpRequest req) throws SQLException {
    String query = orEmpty(Forms.query(req.uri(), "q")).toLowerCase(Locale.ROOT);
    String kind = orEmpty(Forms.query(req.uri(), "kind"));
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Question question : accounts.people.allQuestions()) {
      if (!query.isEmpty() && !contains(query, question.prompt(), question.help())) {
        continue;
      }
      if (!kind.isEmpty() && !kind.equals("all") && !question.kind().name().equals(kind)) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", question.id());
      row.put("prompt", question.prompt());
      row.put("kind", question.kind().label);
      row.put("position", question.position());
      row.put("published", question.published());
      row.put("required", question.required());
      row.put("options", String.join(", ", question.options()));
      // maintained by the survey indexer on its sweep, not counted here
      row.put("answers", accounts.survey.answersFor(question.id()));
      row.put("editUrl", AdminView.Section.survey.path(config) + "/edit/" + question.id());
      rows.add(row);
    }
    model.put("questions", rows);
    model.put("anyQuestions", !rows.isEmpty());
    model.put("count", rows.size());
    model.put("sheets", accounts.people.answerCount());
  }

  private void retiredPanel(Map<String, Object> model, Accounts accounts) throws SQLException {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Question question : accounts.people.deletedQuestions()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", question.id());
      row.put("prompt", question.prompt());
      row.put("kind", question.kind().label);
      row.put("answers", accounts.survey.answersFor(question.id()));
      rows.add(row);
    }
    model.put("questions", rows);
    model.put("anyQuestions", !rows.isEmpty());
    model.put("count", rows.size());
  }

  private void aiPanel(Map<String, Object> model, FullHttpRequest req) {
    String query = Forms.query(req.uri(), "q");
    boolean writesOnly = "1".equals(Forms.query(req.uri(), "writes"));
    io.hearth.mcp.AiLog.Outcome outcome = null;
    String rawOutcome = orEmpty(Forms.query(req.uri(), "outcome"));
    if (!rawOutcome.isEmpty() && !rawOutcome.equals("all")) {
      try {
        outcome = io.hearth.mcp.AiLog.Outcome.valueOf(rawOutcome);
      } catch (IllegalArgumentException ex) {
        outcome = null;
      }
    }
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (io.hearth.mcp.AiLog.Action action : aiLog.search(query, outcome, writesOnly, 300)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("seq", action.seq());
      row.put("at", CLOCK.format(Instant.ofEpochMilli(action.atMillis())));
      row.put("agent", action.agent());
      row.put("email", action.email());
      row.put("tool", action.tool());
      row.put("subject", orEmpty(action.subject()));
      row.put("detail", orEmpty(action.detail()));
      row.put("ok", action.outcome() == io.hearth.mcp.AiLog.Outcome.ok);
      row.put("refused", action.outcome() == io.hearth.mcp.AiLog.Outcome.refused);
      row.put("failed", action.outcome() == io.hearth.mcp.AiLog.Outcome.failed);
      row.put("wrote", action.changedSomething());
      row.put("millis", action.millis());
      // the raw JSON is what was kept; this is the view of it
      row.put("arguments", action.prettyArguments());
      row.put("result", action.prettyResult());
      row.put("anyArguments", !action.prettyArguments().isBlank());
      row.put("anyResult", !action.prettyResult().isBlank());
      rows.add(row);
    }
    model.put("actions", rows);
    model.put("anyActions", !rows.isEmpty());
    model.put("count", rows.size());
  }

  private void cachingPanel(Map<String, Object> model, Accounts accounts) throws SQLException {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    ArrayList<TtlCache.Stats> all = new ArrayList<>(accounts.site.cacheStats());
    all.addAll(accounts.feeds.cacheStats());
    all.addAll(accounts.boardCache.cacheStats());
    for (TtlCache.Stats stats : all) {
      rows.add(cacheRow(stats.name(), stats.policy(), stats.size(), stats.hits(), stats.misses(),
          stats.hitRate(), Long.toString(stats.invalidations())));
    }
    long total = accounts.sessions.cacheHits() + accounts.sessions.cacheMisses();
    rows.add(cacheRow("sessions",
        accounts.security.cacheMaxSessions + " max, " + accounts.security.cacheTtlSeconds + "s",
        accounts.sessions.cacheSize(), accounts.sessions.cacheHits(), accounts.sessions.cacheMisses(),
        total == 0 ? 0 : (int) (accounts.sessions.cacheHits() * 100 / total), ""));
    model.put("caches", rows);
    model.put("indexed", accounts.survey.indexedCount());
    model.put("sweeps", accounts.survey.sweepCount());
    model.put("bubbles", accounts.people.answerCount());
  }

  private static Map<String, Object> cacheRow(String name, String policy, int size, long hits,
                                              long misses, int rate, String invalidations) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("name", name);
    row.put("policy", policy);
    row.put("size", size);
    row.put("hits", hits);
    row.put("misses", misses);
    row.put("rate", rate);
    row.put("invalidations", invalidations);
    return row;
  }

  private void logsPanel(Map<String, Object> model, DomainConfig config, FullHttpRequest req) {
    String text = Forms.query(req.uri(), "q");
    boolean errorsOnly = "1".equals(Forms.query(req.uri(), "errors"));
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Hit hit : accessLog.search(AccessLog.Query.of(config.domain, text, null, null, errorsOnly, 500))) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("at", CLOCK.format(Instant.ofEpochMilli(hit.atMillis())));
      row.put("method", hit.method());
      row.put("uri", hit.uri());
      row.put("status", hit.status());
      row.put("error", hit.isError());
      row.put("millis", hit.durationMillis());
      row.put("ip", orEmpty(hit.ip()));
      row.put("user", hit.userId() == null ? "" : Long.toString(hit.userId()));
      row.put("agent", hit.agent());
      rows.add(row);
    }
    model.put("hits", rows);
    model.put("anyHits", !rows.isEmpty());
    model.put("count", rows.size());
  }

  // ---- forms and review -----------------------------------------------------------------------------

  // ---- the address book ------------------------------------------------------------------------

  private Outcome actOnPlaceType(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    String action = String.valueOf(form.get("action"));
    String slug = form.get("slug");
    if (action.equals("delete")) {
      Places.Type type = accounts.places.typeBySlug(slug);
      if (type == null) {
        return Outcome.refused("That kind of place could not be found.");
      }
      int moved = accounts.places.retireType(slug, me.id());
      if (moved < 0) {
        return Outcome.refused("Unsorted is where addresses go when their kind is removed, so it"
            + " cannot be removed itself.");
      }
      return Outcome.done(type.pluralOr() + " removed."
          + (moved > 0
              ? " " + moved + " address(es) moved to Unsorted and taken off the listing -- nothing"
                  + " was deleted, and what was recorded about them is still there."
              : ""));
    }
    if (!action.equals("save")) {
      return Outcome.refused("That is not something this page can do.");
    }
    String clean = Places.slugify(slug);
    if (clean == null) {
      return Outcome.refused("A kind of place needs a short name.");
    }
    List<TemplateField> fields = new ArrayList<>();
    for (int k = 0; k < TemplateField.MAX_FIELDS; k++) {
      String fieldName = form.get("p_name_" + k);
      if (fieldName == null) {
        continue;
      }
      if (!TemplateField.isValidName(fieldName)) {
        return Outcome.refused("'" + fieldName + "' is not a usable field name -- lowercase"
            + " letters, digits and underscore, starting with a letter.");
      }
      fields.add(new TemplateField(fieldName, TemplateField.Type.of(form.get("p_type_" + k)),
          form.get("p_label_" + k), form.get("p_help_" + k), form.get("p_required_" + k) != null));
    }
    Outcome oversized = oversized(form);
    if (oversized != null) {
      return oversized;
    }
    accounts.places.saveType(clean, form.get("label"), form.get("plural"),
        form.text("description"), fields, form.get("template_name"), form.get("icon"),
        form.get("published") != null, (int) longOr(orEmpty(form.get("sort"))), me.id());
    return Outcome.done("Saved with " + fields.size() + " field(s).",
        config -> AdminView.Section.placetypes.path(config));
  }

  private Outcome actOnPlace(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    String action = String.valueOf(form.get("action"));
    Long id = longOf(form.get("id"));
    if (action.equals("delete")) {
      if (id == null || accounts.places.byId(id) == null) {
        return Outcome.refused("That address could not be found.");
      }
      Places.Place place = accounts.places.byId(id);
      accounts.places.delete(id, me.id());
      return Outcome.done(place.name() + " removed from the book.");
    }
    if (!action.equals("save")) {
      return Outcome.refused("That is not something this page can do.");
    }
    Places.Type type = accounts.places.typeBySlug(form.get("type_slug"));
    if (type == null) {
      return Outcome.refused("Pick a kind of place first. Add one under Kinds if there are none.");
    }
    String name = form.get("name");
    if (name == null || name.isBlank()) {
      return Outcome.refused("An address needs a name.");
    }
    String slug = Places.slugify(orEmpty(form.get("slug")).isBlank() ? name : form.get("slug"));
    if (slug == null) {
      return Outcome.refused("That name cannot be turned into a web address; add a short name.");
    }

    // What the browser was holding for kinds other than the one on screen. With the editor's
    // script running this carries answers typed under one kind and then swapped away from, which
    // are not in the database yet and would otherwise be lost on save. Without the script it is
    // simply absent, and the merge against what is stored does the same job a step later.
    LinkedHashMap<String, String> values = new LinkedHashMap<>();
    values.putAll(carriedValues(form.text("fields_json")));
    for (TemplateField field : type.fields()) {
      String value = form.text("field_" + field.name());
      if (field.required() && (value == null || value.isBlank())) {
        return Outcome.refused("'" + field.labelOr() + "' is required for a " + type.labelOr()
            + ".");
      }
      values.put(field.name(), value == null ? "" : value);
    }
    String body = form.text("body");
    Outcome oversized = oversized(form);
    if (oversized != null) {
      return oversized;
    }

    // Coordinates: whatever was typed. Never overwriting a number somebody entered by hand -- they
    // were standing in the field, and a geocoder is reading a string.
    Double latitude = doubleOf(form.get("latitude"));
    Double longitude = doubleOf(form.get("longitude"));
    Places.Place place = new Places.Place(id == null ? 0 : id, type.slug(), slug, name,
        orEmpty(form.text("address")), orEmpty(form.get("locality")), orEmpty(form.get("region")),
        orEmpty(form.get("postcode")), orEmpty(form.get("country")),
        latitude, longitude,
        orEmpty(form.get("url")), orEmpty(form.get("phone")), orEmpty(form.get("email")),
        Places.mergeValues(id == null ? null : existingFieldsOf(accounts, id),
            type.fields(), values), orEmpty(body),
        form.get("published") != null, form.get("human_only") != null, null, null, me.id());
    Places.Place saved = accounts.places.save(place, me.id());
    // and the lookup goes on the queue rather than into this request. It used to happen right here,
    // which meant an admin adding forty places on a Sunday afternoon made forty requests to
    // somebody else's server as fast as they could type -- and waited on each one.
    boolean queued = latitude == null && longitude == null && geocodes != null && geocodes.on();
    if (queued) {
      geocodes.forPlace(accounts.store.databaseDomain, saved.id(),
          Places.addressLine(orEmpty(form.text("address")), orEmpty(form.get("locality")),
              orEmpty(form.get("region")), orEmpty(form.get("postcode")),
              orEmpty(form.get("country")), name));
    }
    return Outcome.done(name + " saved." + (queued
        ? " Its position is being looked up and will appear in a minute or two." : ""),
        config -> AdminView.Section.places.path(config));
  }

  /**
   * The travel chart for one place.
   *
   * Everything about it is aggregate. `points()` is the widest view of member locations that exists
   * anywhere in this server -- coordinates with no address and no name attached -- and what comes
   * back from here is a set of counts. There is deliberately no way to ask this for one person, and
   * no screen that would have somewhere to put the answer.
   */
  private void travelTo(Map<String, Object> model, Accounts accounts, DomainConfig config,
                        Long placeId) throws SQLException {
    if (placeId == null) {
      return;
    }
    io.hearth.places.Places.Place place = accounts.places.byId(placeId);
    if (place == null || place.latitude() == null || place.longitude() == null) {
      // a place nobody has placed yet: say so rather than drawing an empty chart, because an empty
      // chart reads as "nobody is coming"
      model.put("travelUnplaced", place != null);
      return;
    }
    io.hearth.people.Distances.Travel travel = io.hearth.people.Distances.from(
        accounts.people.points(), place.latitude(), place.longitude(),
        accounts.users.approvedCount(), config.imperial);
    Map<String, Object> chart = io.hearth.people.Distances.model(travel);
    chart.put("place", place.name());
    model.put("travel", chart);
  }

  private void placesPanel(Map<String, Object> model, Accounts accounts, DomainConfig config,
                           FullHttpRequest req) throws SQLException {
    String query = orEmpty(Forms.query(req.uri(), "q")).toLowerCase(Locale.ROOT);
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Places.Place place : accounts.places.all(500)) {
      if (!query.isEmpty() && !contains(query, place.name(), place.oneLine(), place.typeSlug())) {
        continue;
      }
      Places.Type type = accounts.places.typeBySlug(place.typeSlug());
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", place.id());
      row.put("name", place.name());
      row.put("kind", type == null ? place.typeSlug() : type.labelOr());
      row.put("address", place.oneLine());
      // Why this one has no coordinates, when it has none. An address the geocoder has never heard
      // of is a thing an admin can fix in ten seconds by adding a town -- and could not previously
      // find out about at all, because the failure was indistinguishable from not having been
      // asked yet.
      if (place.latitude() == null) {
        io.hearth.places.Placement placement = accounts.places.placementOf(place.id());
        if (!placement.isUntried()) {
          row.put("geoProblem", placement.describe());
          row.put("geoStuck", placement.isNotFound());
        }
      }
      row.put("published", place.published());
      row.put("humanOnly", place.humanOnly());
      row.put("editUrl", AdminView.Section.places.path(config) + "/edit/" + place.id());
      row.put("viewUrl", config.urls.places + "/" + place.typeSlug() + "/" + place.slug());
      rows.add(row);
    }
    model.put("places", rows);
    model.put("anyPlaces", !rows.isEmpty());
    model.put("count", rows.size());
  }

  private void placeTypesPanel(Map<String, Object> model, Accounts accounts, DomainConfig config)
      throws SQLException {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Places.Type type : accounts.places.allTypes()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("slug", type.slug());
      row.put("label", type.labelOr());
      row.put("plural", type.pluralOr());
      row.put("description", type.description());
      row.put("published", type.published());
      row.put("template", type.templateName());
      row.put("fields", type.fields().size());
      row.put("count", accounts.places.countIn(type.slug()));
      row.put("editUrl", AdminView.Section.placetypes.path(config) + "/edit/" + type.slug());
      // Unsorted is where addresses go when their kind is removed, so it cannot be removed itself.
      // The handler has always refused it; drawing the button anyway was a control that says no,
      // which teaches people the software is broken (invariant 149).
      row.put("canRemove", !Places.DEFAULT_TYPE.equals(type.slug()));
      rows.add(row);
    }
    model.put("kinds", rows);
    model.put("anyKinds", !rows.isEmpty());
    model.put("count", rows.size());
  }

  /**
   * The values the editor was carrying, which are not in the database yet.
   *
   * Untrusted like any form field, so an unreadable blob is no values rather than a failed save --
   * and it never decides what is *declared*, only what is remembered. The type's own field list is
   * still the only thing that says what a place of this kind has.
   */
  private static Map<String, String> carriedValues(String blob) {
    LinkedHashMap<String, String> carried = new LinkedHashMap<>();
    if (blob == null || blob.isBlank()) {
      return carried;
    }
    try {
      com.fasterxml.jackson.databind.JsonNode node = JSON_OUT.readTree(blob);
      if (node.isObject()) {
        node.fieldNames().forEachRemaining(name -> {
          if (TemplateField.isValidName(name)) {
            carried.put(name, node.get(name).asText(""));
          }
        });
      }
    } catch (Exception ex) {
      return new LinkedHashMap<>();
    }
    return carried;
  }

  /** what is already stored, so a save keeps what a kind this place used to have recorded */
  private static String existingFieldsOf(Accounts accounts, Long id) throws SQLException {
    Places.Place existing = id == null ? null : accounts.places.byId(id);
    return existing == null ? null : existing.fields();
  }

  private static Double doubleOf(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Double.parseDouble(raw.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  // ---- suggested edits ---------------------------------------------------------------------

  /**
   * Approving is a save; declining keeps the row.
   *
   * Somebody spent time on a suggestion, and "no, because" is a thing they should be able to read.
   * Deleting a declined proposal would make the queue a place where work quietly disappears.
   */
  private Outcome actOnProposal(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    Long id = longOf(form.get("id"));
    if (id == null) {
      return Outcome.refused("That suggestion could not be found.");
    }
    Proposals proposals = accounts.site.store().proposals();
    Proposals.Proposal proposal = proposals.byId(id);
    if (proposal == null) {
      return Outcome.refused("That suggestion could not be found.");
    }
    if (!proposal.isOpen()) {
      return Outcome.refused("That suggestion was already " + proposal.state() + ".");
    }
    String note = form.text("note");
    Outcome oversized = oversized(form);
    if (oversized != null) {
      return oversized;
    }
    return switch (String.valueOf(form.get("action"))) {
      case "approve" -> {
        if (!accounts.access.can(me, Permission.content_review)) {
          yield Outcome.refused("You are not able to approve suggestions.");
        }
        proposals.approve(id, me.id(), me.email(), orEmpty(note));
        yield Outcome.done("Applied " + proposal.uri() + ". It is now a version like any other.");
      }
      case "decline" -> {
        if (!accounts.access.can(me, Permission.content_review)) {
          yield Outcome.refused("You are not able to decline suggestions.");
        }
        proposals.decline(id, me.id(), me.email(), orEmpty(note));
        yield Outcome.done("Declined. The suggestion stays here so whoever wrote it can read why.");
      }
      case "withdraw" -> {
        if (proposal.proposedBy() == null || proposal.proposedBy() != me.id()) {
          yield Outcome.refused("Only the person who suggested it can take it back.");
        }
        proposals.withdraw(id, me.id());
        yield Outcome.done("Taken back.");
      }
      default -> Outcome.refused("That is not something this page can do.");
    };
  }

  private void proposalsPanel(Map<String, Object> model, Accounts accounts, DomainConfig config)
      throws SQLException {
    Proposals proposals = accounts.site.store().proposals();
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Proposals.Proposal proposal : proposals.recent(100)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", proposal.id());
      row.put("uri", proposal.uri());
      row.put("title", proposal.title());
      row.put("who", proposal.who());
      row.put("when", stamp(proposal.createdAt()));
      row.put("note", proposal.note());
      row.put("state", proposal.state().name());
      row.put("open", proposal.isOpen());
      row.put("newPage", proposal.isNewPage());
      row.put("stale", proposals.isStale(proposal));
      row.put("decidedBy", proposal.decidedByEmail());
      row.put("decisionNote", proposal.decisionNote());
      if (proposal.contentId() != null) {
        row.put("editUrl",
            AdminView.Section.content.path(config) + "/edit/" + proposal.contentId());
        row.put("changesUrl", AdminView.Section.proposals.path(config) + "/changes/"
            + proposal.id());
      }
      rows.add(row);
    }
    model.put("proposals", rows);
    model.put("anyProposals", !rows.isEmpty());
    model.put("count", rows.size());
    model.put("openCount", proposals.openCount());
  }

  /** what a suggestion would change, as a diff against the page as it stands */
  private byte[] proposalChanges(Accounts accounts, String id) throws SQLException {
    Proposals.Proposal proposal = accounts.site.store().proposals().byId(longOr(id));
    Map<String, Object> model = new HashMap<>();
    if (proposal == null) {
      model.put("problem", "That suggestion could not be found.");
      return templates.render("admin/content_changes", model);
    }
    model.put("version", "suggested");
    model.put("previous", "now");
    ContentRecord current = proposal.contentId() == null
        ? null : accounts.site.store().byId(proposal.contentId());
    String before = current == null ? "" : ContentVersions.documentOf(current);
    diffInto(model, before, proposal.document());
    return templates.render("admin/content_changes", model);
  }

  // ---- roles -----------------------------------------------------------------------------------

  private Outcome actOnRole(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    String action = String.valueOf(form.get("action"));
    String name = form.get("name");
    if (action.equals("delete")) {
      RoleDefs.Def def = accounts.roleDefs.byName(name);
      if (def == null) {
        return Outcome.refused("That role could not be found.");
      }
      if (def.builtin()) {
        return Outcome.refused("The " + def.labelOr() + " role is built in and cannot be removed.");
      }
      accounts.roleDefs.delete(name, me.id());
      return Outcome.done(def.labelOr() + " removed, along with everybody who held it.");
    }
    if (!action.equals("save")) {
      return Outcome.refused("That is not something this page can do.");
    }
    String clean = RoleDefs.normalize(name);
    if (clean == null) {
      return Outcome.refused("A role needs a name made of letters, digits and dashes.");
    }
    if (RoleDefs.ADMIN.equals(clean)) {
      return Outcome.refused("The administrator role is built in and cannot be edited.");
    }
    java.util.EnumSet<Permission> wanted = java.util.EnumSet.noneOf(Permission.class);
    for (Permission permission : Permission.values()) {
      if (form.get("p_" + permission.name()) != null) {
        wanted.add(permission);
      }
    }
    // Nobody may hand out a power they do not have.
    //
    // Without this, `people_roles` was the whole server by a slightly longer route: invent a role
    // with content_write, chat_manage and appearance_write, grant it to yourself, and you are an
    // administrator in everything but the word. `everything` is already stripped from any role but
    // the built-in one, which is why that shape was not obvious -- the escalation was sideways
    // rather than upwards.
    java.util.Set<Permission> mine = accounts.access.permissionsOf(me);
    if (!mine.contains(Permission.everything)) {
      for (Permission permission : wanted) {
        if (!mine.contains(permission)) {
          return Outcome.refused("You cannot give away '" + permission.label
              + "', because you do not have it yourself.");
        }
      }
    }
    String label = form.get("label");
    String description = form.text("description");
    Outcome refused = oversized(form);
    if (refused != null) {
      return refused;
    }
    accounts.roleDefs.save(clean, label, orEmpty(description), wanted, form.get("color"), me.id());
    return Outcome.done((label == null || label.isBlank() ? clean : label) + " saved with "
            + wanted.size() + " permission(s).",
        config -> AdminView.Section.roles.path(config));
  }

  private void rolesPanel(Map<String, Object> model, Accounts accounts, DomainConfig config)
      throws SQLException {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (RoleDefs.Def def : accounts.roleDefs.all()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("name", def.name());
      row.put("label", def.labelOr());
      row.put("description", def.description());
      row.put("builtin", def.builtin());
      row.put("color", def.color());
      row.put("count", def.count());
      row.put("holders", accounts.roles.holdersOf(def.name()).size());
      row.put("editUrl", AdminView.Section.roles.path(config) + "/edit/" + def.name());
      ArrayList<String> names = new ArrayList<>();
      for (Permission permission : Permission.values()) {
        if (permission != Permission.everything && def.allows(permission)) {
          names.add(permission.label);
        }
      }
      row.put("allows", String.join(", ", names));
      rows.add(row);
    }
    model.put("roles", rows);
    model.put("anyRoles", !rows.isEmpty());
    model.put("count", rows.size());
    model.put("bootstrapAdmins", String.join(", ", accounts.access.bootstrapAdmins()));
  }

  /** the permission checkboxes, gathered under their headings, with what this role already has */
  private static List<Map<String, Object>> permissionGroups(java.util.Set<Permission> held) {
    ArrayList<Map<String, Object>> groups = new ArrayList<>();
    for (Map.Entry<String, List<Permission>> entry : Permission.byGroup().entrySet()) {
      LinkedHashMap<String, Object> group = new LinkedHashMap<>();
      group.put("group", entry.getKey());
      ArrayList<Map<String, Object>> items = new ArrayList<>();
      for (Permission permission : entry.getValue()) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("field", "p_" + permission.name());
        item.put("name", permission.name());
        item.put("label", permission.label);
        item.put("checked", held.contains(permission));
        items.add(item);
      }
      group.put("items", items);
      groups.add(group);
    }
    return groups;
  }

  // ---- the board, moderated --------------------------------------------------------------------

  /**
   * The three things an admin has to be able to do to a conversation.
   *
   * Pin, lock, remove -- and nothing else. There is deliberately no editing of somebody else's
   * words here: changing what a person said and leaving their name on it is the one moderation
   * power that cannot be undone by the person it was used on. Removing says removed, and the thread
   * keeps its shape.
   */
  private Outcome actOnPost(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    String action = String.valueOf(form.get("action"));
    Long id = longOf(form.get("id"));
    if (id == null) {
      return Outcome.refused("That could not be found.");
    }
    if (action.equals("remove_comment")) {
      Board.Comment comment = accounts.board.commentById(id);
      if (comment == null) {
        return Outcome.refused("That comment could not be found.");
      }
      accounts.board.removeComment(id, me.id());
      return Outcome.done("Comment removed. The replies underneath it are still there.");
    }
    Board.Post post = accounts.board.postById(id);
    if (post == null) {
      return Outcome.refused("That post could not be found.");
    }
    return switch (action) {
      case "pin" -> {
        accounts.board.setFlags(id, !post.pinned(), post.locked(), me.id());
        yield Outcome.done(post.pinned() ? "Unpinned." : "Pinned to the top of the feed.");
      }
      case "lock" -> {
        accounts.board.setFlags(id, post.pinned(), !post.locked(), me.id());
        yield Outcome.done(post.locked() ? "Unlocked; it takes replies again."
            : "Locked. It stays readable and takes no more replies.");
      }
      case "remove" -> {
        accounts.board.removePost(id, me.id());
        yield Outcome.done("Removed from the feed. The thread is kept, because the replies in it"
            + " are other people's words.");
      }
      case "expiry" -> {
        Long days = longOf(form.get("days"));
        // zero means forever here exactly as it does in board.expiry-days. Passing it through as a
        // day count would set the expiry to this instant, which is the opposite of what the button
        // says and would delete the thread the operator was trying to keep.
        accounts.board.setExpiry(id, days == null || days == 0 ? null : days.intValue(), me.id());
        yield Outcome.done(days == null || days == 0
            ? "That thread will now be kept indefinitely."
            : "That thread will age out in " + days + " day(s).");
      }
      default -> Outcome.refused("That is not something this page can do.");
    };
  }

  private void boardPanel(Map<String, Object> model, Accounts accounts, DomainConfig config,
                          FullHttpRequest req) throws SQLException {
    String q = orEmpty(Forms.query(req.uri(), "q")).toLowerCase();
    long now = System.currentTimeMillis();
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Board.Post post : accounts.board.all(500)) {
      if (!q.isEmpty() && !contains(q, post.title(), post.authorEmail())) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", post.id());
      row.put("title", post.title());
      row.put("url", config.urls.board + "/" + post.id());
      row.put("author", post.authorEmail());
      row.put("comments", post.commentCount());
      row.put("watchers", post.watchers().size());
      row.put("when", stamp(post.createdAt()));
      row.put("pinned", post.pinned());
      row.put("locked", post.locked());
      row.put("removed", post.removed());
      row.put("expires", post.expires());
      row.put("expired", post.expired(now));
      row.put("daysLeft", post.daysLeft(now));
      rows.add(row);
    }
    model.put("posts", rows);
    model.put("anyPosts", !rows.isEmpty());
    model.put("count", rows.size());
  }

  // ---- the calendar ----------------------------------------------------------------------------

  /**
   * What each button on the events screens actually requires.
   *
   * Invariant 86, applied to a section that grew a second door. `/admin/calendar/suggestions` opens
   * for `calendar_review` -- "decide what members put forward" -- and every button on both screens
   * posts to a path under it, so a handler that only checked the section would have let a reviewer
   * delete any event on the calendar along with everybody's answers. An action nobody listed
   * requires `everything`, so a new button fails closed.
   */
  private static Permission neededForEvent(String action) {
    return switch (action) {
      case "accept", "decline" -> Permission.calendar_review;
      case "save", "cancel", "delete", "invite", "take_proposal", "repropose" ->
          Permission.calendar_write;
      // marking somebody absent is a statement about a person, so it needs the permission that
      // covers keeping the calendar rather than the one that covers reading it
      case "no_show", "attended" -> Permission.calendar_write;
      default -> Permission.everything;
    };
  }

  private Outcome actOnEvent(DomainConfig config, Accounts accounts, Forms form, UserRecord me)
      throws SQLException {
    String action = String.valueOf(form.get("action"));
    if (!accounts.access.can(me, neededForEvent(action))) {
      return Outcome.refused("You are not able to do that to an event.");
    }
    Long id = longOf(form.get("id"));
    if (action.equals("save")) {
      String title = form.get("title");
      if (title == null || title.isBlank()) {
        return Outcome.refused("An event needs a name.");
      }
      java.time.LocalDate starts = dateOf(form.get("starts_on"));
      if (starts == null) {
        return Outcome.refused("An event needs a day it happens on, as YYYY-MM-DD.");
      }
      java.time.LocalDate ends = dateOf(form.get("ends_on"));
      if (ends == null) {
        // one day is the common case, and repeating the date to say so is a chore
        ends = starts;
      }
      String body = form.text("body");
      Long capacity = longOf(form.get("capacity"));
      Outcome refused = oversized(form);
      if (refused != null) {
        return refused;
      }
      boolean published = form.get("published") != null;
      Long placeId = longOf(form.get("place"));
      // Sending on save, when it is announced and somebody left the box ticked.
      //
      // The default is on for a new event, because an event nobody is told about is the failure
      // this whole feature exists to prevent -- and off for an edit, because a draft saved four
      // times must not be four invitations and a typo fixed in the title is not worth everybody's
      // calendar buzzing.
      boolean invite = published && form.get("invite") != null;
      if (id == null) {
        Calendar.Event made = accounts.calendar.create(title, orEmpty(body), form.get("location"),
            placeId, Calendar.State.accepted,
            starts, ends, form.get("start_time"), capacity == null ? null : capacity.intValue(),
            published, me.id(), me.email());
        String sent = "";
        if (invite) {
          sent = " " + calendarInvites.invite(config, accounts, made, inboundMail).detail() + ".";
        }
        return Outcome.done(made.title() + (published ? " announced." : " saved as a draft.")
            + sent, site -> AdminView.Section.calendar.path(site));
      }
      accounts.calendar.update(id, title, orEmpty(body), form.get("location"), placeId, starts,
          ends, form.get("start_time"), capacity == null ? null : capacity.intValue(), published,
          me.id());
      Calendar.Event saved = accounts.calendar.byId(id);
      String sent = "";
      if (invite) {
        // a change everybody has already been invited to needs a higher sequence, or their
        // calendar ignores the update entirely
        if (saved.invitedAt() != null) {
          saved = accounts.calendar.bumpSequence(id, me.id());
        }
        sent = " " + calendarInvites.invite(config, accounts, saved, inboundMail).detail() + ".";
      }
      return Outcome.done(title + " saved." + sent,
          site -> AdminView.Section.calendar.path(site));
    }
    if (id == null) {
      return Outcome.refused("That event could not be found.");
    }
    Calendar.Event event = accounts.calendar.byId(id);
    if (event == null) {
      return Outcome.refused("That event could not be found.");
    }
    return switch (action) {
      case "cancel" -> {
        accounts.calendar.cancel(id, !event.cancelled(), me.id());
        yield Outcome.done(event.cancelled() ? "Back on." : "Cancelled. The page and the guest list"
            + " stay, because the people who said they were coming are the ones who need to see"
            + " it.");
      }
      case "delete" -> {
        accounts.calendar.delete(id, me.id());
        yield Outcome.done("Event deleted, along with everybody's answers.");
      }
      case "invite" -> {
        // sending is explicit rather than automatic on save, for the same reason writing an
        // invitation is separate from sending it: a draft saved four times is not four invitations,
        // and an event whose day is still being argued about should not be on anybody's calendar.
        io.hearth.calendar.Invitations.Sent sent =
            calendarInvites.invite(config, accounts, event, inboundMail);
        yield sent.anything() ? Outcome.done(sent.detail()) : Outcome.refused(sent.detail());
      }
      case "take_proposal", "repropose" -> {
        // Moving an event, and deciding what happens to the answers.
        //
        // Somebody suggested a day (take_proposal) or an admin typed one (repropose); either way
        // the hard part is the same and it is not technical. Forty people said yes to a Tuesday and
        // it is now a Thursday: keeping their answers claims forty people are coming to an evening
        // none of them agreed to, and clearing them starts from nothing. Which is right depends on
        // how far it moved, so the screen asks and the person decides.
        java.time.LocalDate to;
        Long who = longOf(form.get("user"));
        if (action.equals("take_proposal")) {
          Calendar.Rsvp rsvp = who == null ? null : accounts.calendar.rsvpFor(id, who);
          if (rsvp == null || rsvp.proposedOn() == null) {
            yield Outcome.refused("There is no suggested day from them to take.");
          }
          to = rsvp.proposedOn();
        } else {
          to = dateOf(form.get("starts_on"));
          if (to == null) {
            yield Outcome.refused("A new day, as YYYY-MM-DD.");
          }
        }
        long span = event.startsOn().until(event.endsOn()).getDays();
        boolean keep = form.get("keep_answers") != null;
        int cleared = accounts.calendar.reschedule(id, to, to.plusDays(Math.max(0, span)), keep,
            me.id());
        if (who != null) {
          accounts.calendar.propose(id, who, null, "");
        }
        accounts.calendar.bumpSequence(id, me.id());
        String sent = event.invitedAt() == null ? ""
            : " " + calendarInvites.invite(config, accounts, accounts.calendar.byId(id),
                inboundMail).detail() + ".";
        yield Outcome.done("Moved to " + to + "."
            + (keep ? " Everybody's answer stands."
                : " " + cleared + " answer(s) cleared; the people who said no were left alone.")
            + sent);
      }
      case "open_public" -> {
        // its own action rather than a box on the form, because it changes who may read the event
        // and where an answer may come from -- a decision, not a detail
        accounts.calendar.openToPublic(id, !event.openToPublic(), me.id());
        yield Outcome.done(event.openToPublic()
            ? "Members only again. What people from outside already said is kept."
            : "Open to anybody. Somebody with no account here can take the file and answer from"
                + " their calendar, and what they say lands on this page for you to read.");
      }
      case "invite_outsider" -> {
        if (!accounts.access.can(me, Permission.invites_send)) {
          yield Outcome.refused("Inviting somebody needs 'invite somebody by email'.");
        }
        if (!config.has(io.hearth.vhost.Surface.invites)) {
          yield Outcome.refused("This community does not send invitations.");
        }
        Long which = longOf(form.get("guest"));
        Calendar.Outsider guest = null;
        for (Calendar.Outsider row : accounts.calendar.outsiders(id)) {
          if (which != null && row.id() == which) {
            guest = row;
          }
        }
        if (guest == null) {
          yield Outcome.refused("That answer could not be found.");
        }
        io.hearth.people.Invitations.Result result = invitations.invite(config, accounts,
            guest.email(), null, me.id(), me.email(), true, false);
        if (!result.ok()) {
          yield Outcome.refused(guest.email() + ": " + result.detail() + ".");
        }
        accounts.calendar.markOutsiderInvited(guest.id(), me.id());
        yield Outcome.done(guest.email() + " has been invited. When they join, what they said"
            + " about this becomes an ordinary answer.");
      }
      case "no_show", "attended" -> {
        Long who = longOf(form.get("user"));
        if (who == null) {
          yield Outcome.refused("That person could not be found.");
        }
        accounts.calendar.markNoShow(id, who, action.equals("no_show"), me.id());
        yield Outcome.done(action.equals("no_show")
            ? "Noted as not there. It is a note for you, not a mark against them."
            : "Noted as there after all.");
      }
      case "accept" -> {
        accounts.calendar.decide(id, Calendar.State.accepted, "", me.id());
        yield Outcome.done(event.title() + " is on the calendar.",
            site -> AdminView.Section.calendar.path(site));
      }
      case "decline" -> {
        // the reason is kept and shown to whoever suggested it. A queue where things quietly
        // disappear is a queue nobody uses twice.
        accounts.calendar.decide(id, Calendar.State.declined, orEmpty(form.text("note")), me.id());
        yield Outcome.done("Declined, with the reason.",
            site -> AdminView.Section.suggestions.path(site));
      }
      default -> Outcome.refused("That is not something this page can do.");
    };
  }

  /**
   * The suggestion queue.
   *
   * The same handler as the calendar, because a suggestion is the same row -- accepting one does
   * not copy it anywhere, it changes a word on it.
   */
  private void suggestionsPanel(Map<String, Object> model, Accounts accounts, DomainConfig config)
      throws SQLException {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Calendar.Event event : accounts.calendar.suggestions(200)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", event.id());
      row.put("title", event.title());
      row.put("body", event.body());
      row.put("who", event.createdByEmail());
      row.put("when", event.spansDays()
          ? event.startsOn() + " to " + event.endsOn() : event.startsOn().toString());
      row.put("startTime", event.startTime());
      row.put("location", locationOf(accounts, event));
      row.put("editUrl", AdminView.Section.calendar.path(config) + "/edit/" + event.id());
      rows.add(row);
    }
    model.put("suggestions", rows);
    model.put("anySuggestions", !rows.isEmpty());
    model.put("count", rows.size());
  }

  /** where an event is, in one line: the place if it has one, plus whatever was typed beside it */
  private static String locationOf(Accounts accounts, Calendar.Event event) {
    String extra = event.location() == null ? "" : event.location().trim();
    if (event.placeId() == null) {
      return extra;
    }
    try {
      io.hearth.places.Places.Place place = accounts.places.byId(event.placeId());
      if (place == null) {
        return extra;
      }
      return extra.isEmpty() ? place.name() : place.name() + ", " + extra;
    } catch (SQLException ex) {
      return extra;
    }
  }

  /**
   * Everything waiting for a person to look at it, with the words in front of them.
   *
   * The content is on the page rather than behind a link on purpose: triage means reading, and a
   * queue that makes somebody open twelve tabs to read twelve comments is a queue that gets cleared
   * by pressing whatever is quickest.
   */
  private void flaggedPanel(Map<String, Object> model, Accounts accounts, DomainConfig config)
      throws SQLException {
    // group by what was flagged: four flags on one comment is one thing to look at, not four
    java.util.LinkedHashMap<String, java.util.List<io.hearth.board.Signals.Signal>> grouped =
        new java.util.LinkedHashMap<>();
    for (io.hearth.board.Signals.Signal signal : accounts.signals.openFlags(200)) {
      grouped.computeIfAbsent(signal.subject().key(), key -> new ArrayList<>()).add(signal);
    }
    io.hearth.people.Names names = io.hearth.people.Names.of(accounts);
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (java.util.List<io.hearth.board.Signals.Signal> flags : grouped.values()) {
      io.hearth.board.Subject subject = flags.get(0).subject();
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("subjectKind", subject.kind().name());
      row.put("subjectId", subject.id());
      row.put("count", flags.size());
      row.put("one", flags.size() == 1);
      row.put("when", stamp(flags.get(0).createdAt()));
      ArrayList<String> reasons = new ArrayList<>();
      for (io.hearth.board.Signals.Signal flag : flags) {
        if (flag.reason() != null && !flag.reason().isBlank()) {
          reasons.add(flag.reason());
        }
      }
      row.put("reasons", reasons);
      fillFlagged(row, accounts, config, subject, names);
      rows.add(row);
    }
    model.put("flags", rows);
    model.put("anyFlags", !rows.isEmpty());
    model.put("count", rows.size());
  }

  /** what the flagged thing actually says, so somebody can decide without leaving the page */
  private void fillFlagged(Map<String, Object> row, Accounts accounts, DomainConfig config,
                           io.hearth.board.Subject subject, io.hearth.people.Names names)
      throws SQLException {
    switch (subject.kind()) {
      case comment -> {
        io.hearth.board.Board.Comment comment = accounts.board.commentById(subject.id());
        row.put("kindLabel", "a comment");
        row.put("bodyHtml", comment == null ? "<p class=\"gone\">it is not there any more</p>"
            : io.hearth.content.Markdown.toSafeHtml(comment.body()));
        row.put("author", comment == null ? "" : names.of(comment.authorId()));
        row.put("removed", comment == null || comment.removed());
        if (comment != null && comment.subject().kind() == io.hearth.board.Subject.Kind.post) {
          row.put("url", config.urls.board + "/" + comment.postId());
        }
      }
      case post -> {
        io.hearth.board.Board.Post post = accounts.board.postById(subject.id());
        row.put("kindLabel", "a conversation");
        row.put("bodyHtml", post == null ? "<p class=\"gone\">it is not there any more</p>"
            // the title through the same safelist as the body: it is a member's typing, and this
            // page interpolates it raw so that the body can be markdown
            : io.hearth.content.Markdown.toSafeHtml("### " + post.title() + "\n\n" + post.body()));
        row.put("author", post == null ? "" : names.of(post.authorId()));
        row.put("removed", post == null || post.removed());
        row.put("url", config.urls.board + "/" + subject.id());
      }
      default -> {
        row.put("kindLabel", subject.kind().name());
        row.put("bodyHtml", "");
        row.put("author", "");
        row.put("removed", false);
      }
    }
  }

  private Outcome actOnFlag(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    io.hearth.board.Subject subject;
    try {
      subject = new io.hearth.board.Subject(
          io.hearth.board.Subject.Kind.valueOf(String.valueOf(form.get("subject_kind")).trim()),
          longOr(form.get("subject_id")));
    } catch (IllegalArgumentException ex) {
      return Outcome.refused("That is not something that can be flagged.");
    }
    String action = String.valueOf(form.get("action"));
    if (action.equals("clear")) {
      int cleared = accounts.signals.clear(subject, me.id());
      return Outcome.done(cleared + " flag(s) cleared. The record of what was reported stays.");
    }
    if (action.equals("remove")) {
      // taking something down is the section's own permission, wherever the flag came from --
      // a board moderator does not get to remove a comment on a place by way of this queue
      if (!accounts.access.can(me, subject.moderatedBy())) {
        return Outcome.refused("Taking that down needs '" + subject.moderatedBy().label + "'.");
      }
      if (subject.kind() == io.hearth.board.Subject.Kind.comment) {
        accounts.board.removeComment(subject.id(), me.id());
      } else if (subject.kind() == io.hearth.board.Subject.Kind.post) {
        accounts.board.removePost(subject.id(), me.id());
      }
      accounts.signals.clear(subject, me.id());
      return Outcome.done("Taken down, and the flags cleared with it.");
    }
    return Outcome.refused("That is not something this page can do.");
  }

  /**
   * Everything uploaded, filtered the way somebody actually looks for a photograph.
   *
   * By folder, by kind, and by words -- against the filename, the description and the tags. A
   * picture somebody uploaded eight months ago is findable by the thing it is of, which is the only
   * search anybody performs on a pile of images.
   */
  private void attachmentsPanel(Map<String, Object> model, Accounts accounts, DomainConfig config,
                                FullHttpRequest req) throws SQLException {
    String folder = Forms.query(req.uri(), "folder");
    String kind = Forms.query(req.uri(), "kind");
    String query = Forms.query(req.uri(), "q");
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (io.hearth.attach.Attachments.Attachment file
        : accounts.attachments.search(folder, kind, query, 400)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", file.id());
      row.put("url", file.url());
      row.put("filename", file.filename());
      row.put("description", file.description());
      row.put("folder", file.folder());
      row.put("tags", file.tags());
      row.put("size", file.size());
      row.put("kind", file.kind().name());
      row.put("isImage", file.kind() == io.hearth.attach.Kinds.Kind.image);
      row.put("isVideo", file.kind() == io.hearth.attach.Kinds.Kind.video);
      row.put("isAudio", file.kind() == io.hearth.attach.Kinds.Kind.audio);
      row.put("public", file.isPublic());
      row.put("who", file.uploadedByEmail());
      row.put("at", file.createdAt() == null ? "" : stamp(file.createdAt()));
      // what to paste into a page: markdown for a picture, an html element for the rest, because
      // markdown has no way to say "video"
      row.put("embed", embedFor(file));
      rows.add(row);
    }
    model.put("files", rows);
    model.put("anyFiles", !rows.isEmpty());
    // the same panel, in the shape the page editor's picker wants: one button per file rather than
    // an editing form. One template, because two would drift the moment a field was added.
    model.put("picking", "1".equals(Forms.query(req.uri(), "pick")));
    model.put("count", rows.size());
    model.put("action", AdminView.Section.attachments.path(config));
  }

  /** what somebody pastes into a page to show this file */
  static String embedFor(io.hearth.attach.Attachments.Attachment file) {
    String alt = file.description() == null || file.description().isBlank()
        ? file.filename() : file.description();
    return switch (file.kind()) {
      case image -> "![" + alt.replace("]", "") + "](" + file.url() + ")";
      case video -> "<video controls width=\"100%\" src=\"" + file.url() + "\"></video>";
      case audio -> "<audio controls src=\"" + file.url() + "\"></audio>";
      default -> "[" + alt.replace("]", "") + "](" + file.url() + ")";
    };
  }

  /**
   * The one action screen for a file: where it sits, what it is, and who may see it.
   *
   * Deleting takes the bytes with the row, in that order -- an orphan file costs disk and confuses
   * nobody, and a row whose file is gone is a broken image on a page with nothing to do about it.
   */
  private Outcome actOnAttachment(DomainConfig config, Accounts accounts, Forms form,
                                  UserRecord me) throws SQLException {
    String action = String.valueOf(form.get("action"));
    if (action.equals("move")) {
      // the one action about a folder rather than about a file, so it is answered before anything
      // goes looking for an id it was never given
      int moved = accounts.attachments.moveFolder(form.get("from"), form.get("to"), me.id());
      return moved == 0
          ? Outcome.refused("Nothing is in that folder.")
          : Outcome.done(moved + " file(s) moved.");
    }
    Long id = longOf(form.get("id"));
    io.hearth.attach.Attachments.Attachment file = id == null ? null : accounts.attachments.byId(id);
    if (file == null) {
      return Outcome.refused("That file could not be found.");
    }
    return switch (action) {
      case "save" -> {
        Outcome oversized = oversized(form);
        if (oversized != null) {
          yield oversized;
        }
        accounts.attachments.update(file.id(), form.get("folder"), form.get("tags"),
            form.text("description"), form.get("public") != null, me.id());
        yield Outcome.done(file.filename() + " saved.");
      }
      case "delete" -> {
        accounts.attachments.delete(file.id(), me.id());
        if (attachments != null) {
          attachments.files().delete(file.id(), file.extension());
          attachments.cache().invalidate(file.id(), file.extension());
        }
        yield Outcome.done(file.filename() + " deleted, and the file with it. Anything on a page"
            + " that pointed at it now points at nothing.");
      }
      default -> Outcome.refused("That is not something this page can do.");
    };
  }

  /**
   * What nothing is pointing at any more.
   *
   * The scan runs when this page is opened rather than on a timer: it is a read of every text
   * column in the database, which is cheap at this scale and is not something to be doing at three
   * in the morning for a screen nobody is looking at. What it costs is a moment when somebody opens
   * it, which is exactly when the answer needs to be current.
   */
  private void unused(Map<String, Object> model, Accounts accounts, DomainConfig config)
      throws SQLException {
    io.hearth.attach.AttachmentSweep.Result swept =
        io.hearth.attach.AttachmentSweep.run(accounts);
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (io.hearth.attach.Attachments.Attachment file : swept.unused()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", file.id());
      row.put("url", file.url());
      row.put("filename", file.filename());
      row.put("description", file.description());
      row.put("folder", file.folder());
      row.put("size", file.size());
      row.put("kind", file.kind().name());
      row.put("isImage", file.kind() == io.hearth.attach.Kinds.Kind.image);
      row.put("who", file.uploadedByEmail());
      row.put("at", file.createdAt() == null ? "" : stamp(file.createdAt()));
      rows.add(row);
    }
    model.put("unused", rows);
    model.put("anyUnused", !rows.isEmpty());
    model.put("count", rows.size());
    model.put("freeable", megabytes(swept.freeable()));
    model.put("rowsRead", swept.rowsRead());
    model.put("referenced", swept.referenced().size());
    ArrayList<Map<String, Object>> where = new ArrayList<>();
    for (Map.Entry<String, Integer> entry
        : io.hearth.attach.AttachmentSweep.bySource(swept).entrySet()) {
      where.add(Map.of("source", entry.getKey(), "count", entry.getValue()));
    }
    model.put("where", where);
    model.put("anyWhere", !where.isEmpty());
    ArrayList<Map<String, Object>> looked = new ArrayList<>();
    for (String source : io.hearth.attach.AttachmentSweep.describeSources()) {
      looked.add(Map.of("source", source));
    }
    model.put("looked", looked);
    // a scan that could not read something is a scan whose answer is "I do not know", and a delete
    // button on top of that would be offering to remove files it never looked for
    model.put("trustworthy", swept.trustworthy());
    model.put("problems", swept.problems());
    model.put("graceHours", io.hearth.attach.AttachmentSweep.GRACE.toHours());
    model.put("backUrl", AdminView.Section.attachments.path(config));
  }

  private Outcome actOnUnused(DomainConfig config, Accounts accounts, Forms form, UserRecord me)
      throws SQLException {
    String action = String.valueOf(form.get("action"));
    if (!action.equals("sweep")) {
      return Outcome.refused("That is not something this page can do.");
    }
    // The scan runs again inside the action rather than trusting what the page was showing.
    // Somebody could have written a page mentioning one of these between the screen being drawn
    // and the button being pressed, and deleting a file that is now on a page would be exactly the
    // failure this whole screen exists to avoid.
    io.hearth.attach.AttachmentSweep.Result swept =
        io.hearth.attach.AttachmentSweep.run(accounts);
    if (!swept.trustworthy()) {
      return Outcome.refused("Part of the scan could not be read, so nothing was deleted: "
          + String.join("; ", swept.problems()));
    }
    if (!"delete".equals(form.get("confirm"))) {
      return Outcome.refused("Type delete to confirm. Nothing here can be brought back.");
    }
    int gone = 0;
    long freed = 0;
    for (io.hearth.attach.Attachments.Attachment file : swept.unused()) {
      accounts.attachments.delete(file.id(), me.id());
      if (attachments != null) {
        attachments.files().delete(file.id(), file.extension());
        attachments.cache().invalidate(file.id(), file.extension());
      }
      gone++;
      freed += file.bytes();
    }
    verbose.say("attachments: " + me.email() + " deleted " + gone + " unreferenced file(s)");
    return Outcome.done(gone == 0
        ? "Nothing was unreferenced by the time the button was pressed."
        : gone + " file(s) deleted, " + megabytes(freed) + " freed.",
        site -> AdminView.Section.unused.path(site));
  }

  /**
   * The index half of a template: whether it publishes one, where, and what it looks like.
   *
   * Its own screen because it is its own thing. A directory index is a second template with a
   * second job -- a list with pagination rather than a document with a body -- and having it as a
   * checkbox in the middle of the page editor meant one file branching on `{{#directory}}` at the
   * top, which is a shape somebody writes once and nobody can edit six months later.
   */
  private Outcome actOnDirectory(Accounts accounts, Forms form, UserRecord me)
      throws SQLException {
    String name = form.get("name");
    TemplateRecord template = name == null ? null : accounts.site.store().templateByName(name);
    if (template == null) {
      return Outcome.refused("That template could not be found.");
    }
    if (!String.valueOf(form.get("action")).equals("save")) {
      return Outcome.refused("That is not something this page can do.");
    }
    Outcome oversized = oversized(form);
    if (oversized != null) {
      return oversized;
    }
    boolean publishes = form.get("directory") != null;
    String body = form.text("directory_body");
    accounts.site.store().saveTemplate(template.name(), template.body(), template.parameters(),
        publishes, orEmpty(form.get("directory_path")), orEmpty(form.get("directory_pattern")),
        body == null ? "" : body,
        (int) longOr(orEmpty(form.get("directory_page_size"))),
        form.get("newest") != null ? "newest" : "oldest", me.id());
    return Outcome.done(publishes
        ? template.name() + " publishes its index at " + orEmpty(form.get("directory_path")) + "."
        : template.name() + " no longer publishes an index; its markup is kept.",
        site -> AdminView.Section.directories.path(site));
  }

  /** the accept attribute for the file box: what this community actually takes */
  private static String acceptOf(DomainConfig config) {
    ArrayList<String> accept = new ArrayList<>();
    for (String extension : config.attachments.extensions) {
      accept.add("." + extension);
    }
    return String.join(",", accept);
  }

  private static List<Map<String, Object>> kindOptions(String current) {
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    option("all", "everything", current == null || current.isBlank() || current.equals("all"),
        options);
    for (io.hearth.attach.Kinds.Kind kind : io.hearth.attach.Kinds.Kind.values()) {
      option(kind.name(), kind.name(), kind.name().equals(current), options);
    }
    return options;
  }

  private static String megabytes(long bytes) {
    return bytes < 1024 * 1024
        ? Math.max(1, bytes / 1024) + " KB" : String.format("%.1f MB", bytes / (1024.0 * 1024.0));
  }

  private static String urlencode(String value) {
    return java.net.URLEncoder.encode(value == null ? "" : value,
        java.nio.charset.StandardCharsets.UTF_8);
  }

  private void calendarPanel(Map<String, Object> model, Accounts accounts, DomainConfig config)
      throws SQLException {
    java.time.LocalDate today = java.time.LocalDate.now();
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Calendar.Event event : accounts.calendar.all(500)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", event.id());
      row.put("title", event.title());
      row.put("url", config.urls.calendar + "/" + event.id());
      row.put("editUrl", AdminView.Section.calendar.path(config) + "/edit/" + event.id());
      row.put("when", event.spansDays()
          ? event.startsOn() + " to " + event.endsOn() : event.startsOn().toString());
      row.put("location", event.location());
      row.put("published", event.published());
      row.put("cancelled", event.cancelled());
      row.put("over", event.over(today));
      row.put("going", event.goingCount());
      row.put("maybe", event.maybeCount());
      row.put("waiting", event.waitlistCount());
      row.put("limited", event.limited());
      row.put("capacity", event.capacity());
      // three states worth telling apart: never invited, invited, and invited before the last
      // change -- which is the one that means everybody is holding a stale day in their calendar
      row.put("invited", event.invitedAt() != null);
      row.put("staleInvites", event.invitedAt() != null && !event.invitesAreCurrent());
      rows.add(row);
    }
    model.put("events", rows);
    model.put("anyEvents", !rows.isEmpty());
    model.put("count", rows.size());
  }

  /** a date as somebody typed it, or null when it is not one */
  private static java.time.LocalDate dateOf(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return java.time.LocalDate.parse(raw.trim());
    } catch (java.time.format.DateTimeParseException ex) {
      return null;
    }
  }

  /**
   * The engagement loop, written down in one place.
   *
   * Every rule here already exists somewhere -- an invite cadence in a config block, a fuse in the
   * notifier, a default in the notification settings. What did not exist was anywhere to read them
   * together, which meant nobody running a community could answer the only question that matters
   * about any of it: what does this server do to somebody who stops turning up, and when does it
   * stop doing it?
   *
   * The answer is a screen rather than a set of switches on purpose. Almost none of this should be
   * tuned. The numbers come from what the sequence research says works and from what makes a
   * community feel like people rather than a system, and a page of sliders would invite an
   * afternoon of moving them.
   */
  private void engagement(Map<String, Object> model, DomainConfig config, Accounts accounts)
      throws SQLException {
    // How long a notification takes to work, which is the only thing worth measuring about one.
    // Not how many went out -- that number goes up whether or not anybody looked.
    model.put("pushDelays", accounts.pushLedger.histogram());
    model.put("pushWaiting", accounts.pushLedger.waiting());
    io.hearth.people.Invites.Funnel funnel = accounts.invites.funnel();
    model.put("sent", funnel.sent());
    model.put("joined", funnel.converted());
    model.put("conversionRate", funnel.conversionRate());
    model.put("waitingToSend", accounts.inbox.undelivered(500).size());
    model.put("livePings", accessLog.livePings());
    model.put("online", live.forDomain(config.domain).online().size());
    model.put("subscribed", accounts.pushSubs.count());

    model.put("invitesOn", config.has(io.hearth.vhost.Surface.invites) && config.invites.enabled);
    model.put("remindersOn", config.invites.remindersEnabled);
    model.put("reminderAfter", config.invites.reminderAfterDays);
    model.put("apologyAfter", config.invites.apologyAfterDays);
    model.put("membersMayInvite", config.invites.membersMayInvite);
    model.put("memberDailyLimit", config.invites.memberDailyLimit);
    model.put("boardOn", config.has(io.hearth.vhost.Surface.board));
    model.put("calendarOn", config.has(io.hearth.vhost.Surface.calendar));
    model.put("suggestionsOn", config.calendar.suggestions);
    model.put("appOn", config.has(io.hearth.vhost.Surface.app));
    model.put("invitesUrl", AdminView.Section.invites.path(config));
    model.put("calendarUrl", AdminView.Section.calendar.path(config));
  }

  private static long longOr(String raw, long fallback) {
    Long value = longOf(raw);
    return value == null ? fallback : value;
  }

  // ---- appearance and legal -----------------------------------------------------------------

  /**
   * Both palettes, as the editor shows them.
   *
   * The two scopes are one form each rather than one form with everything in it, because saving the
   * site's colours and saving the admin's are separate decisions and a single Save covering twelve
   * pickers is a button nobody presses with confidence.
   */
  // ---- settings ---------------------------------------------------------------------------------

  /**
   * One box per setting, with what it means beside it.
   *
   * Drawn entirely from {@link io.hearth.settings.Settings}, so a setting added to that list gets a
   * form, a label and its explanation here without anybody editing a template -- and cannot be
   * added without an explanation, because the catalogue has nowhere to put one that is missing.
   *
   * Each box shows what is <em>in force</em> rather than what is in the table, and says which of
   * the two it is. "Set here" means somebody typed it; anything else is the config file or the
   * built-in, and clearing a box puts that back rather than storing an empty value.
   */
  private void configuration(Map<String, Object> model, DomainConfig config, Accounts accounts) {
    Map<String, String> decided = accounts.settings.overrides();
    ArrayList<Map<String, Object>> groups = new ArrayList<>();
    for (String group : io.hearth.settings.Settings.groups()) {
      LinkedHashMap<String, Object> block = new LinkedHashMap<>();
      block.put("group", group);
      ArrayList<Map<String, Object>> rows = new ArrayList<>();
      for (io.hearth.settings.Setting setting
          : io.hearth.settings.Settings.inGroup(group)) {
        rows.add(settingRow(setting, config, decided));
      }
      block.put("settings", rows);
      groups.add(block);
    }
    model.put("groups", groups);
    model.put("setupDone", accounts.settings.isSetupComplete());
    model.put("setupUrl", AdminView.Section.setup.path(config));
    model.put("configFile", config.configFile == null ? "" : config.configFile.getName());
  }

  private Map<String, Object> settingRow(io.hearth.settings.Setting setting, DomainConfig config,
                                         Map<String, String> decided) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    String current = io.hearth.settings.Settings.currentValue(config, setting.key());
    boolean overridden = decided.containsKey(setting.key());
    row.put("key", setting.key());
    row.put("field", setting.field());
    row.put("label", setting.label());
    row.put("help", setting.help());
    row.put("value", current);
    row.put("overridden", overridden);
    row.put("isText", setting.kind() == io.hearth.settings.Setting.Kind.text);
    row.put("isMultiline", setting.kind() == io.hearth.settings.Setting.Kind.multiline
        || setting.kind() == io.hearth.settings.Setting.Kind.words
        || setting.kind() == io.hearth.settings.Setting.Kind.numbers);
    row.put("isNumber", setting.kind() == io.hearth.settings.Setting.Kind.integer);
    row.put("isBool", setting.kind() == io.hearth.settings.Setting.Kind.bool);
    row.put("isChoice", setting.kind() == io.hearth.settings.Setting.Kind.choice);
    row.put("checked", "true".equalsIgnoreCase(current));
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    for (String choice : setting.choices()) {
      LinkedHashMap<String, Object> option = new LinkedHashMap<>();
      option.put("value", choice);
      option.put("selected", choice.equalsIgnoreCase(current));
      options.add(option);
    }
    row.put("options", options);
    row.put("anyOptions", !options.isEmpty());
    return row;
  }

  /**
   * The walkthrough, one screen at a time.
   *
   * A step is a path (`/admin/configuration/setup?step=2`) rather than everything on one page,
   * because the point of a wizard is that somebody reads four questions instead of skimming thirty
   * -- and because each step saves as it goes, so somebody interrupted half way through has kept
   * what they answered rather than losing the lot.
   */
  private void setupWizard(Map<String, Object> model, DomainConfig config, Accounts accounts,
                           io.netty.handler.codec.http.FullHttpRequest req) {
    java.util.List<io.hearth.settings.Settings.Step> steps =
        io.hearth.settings.Settings.walkthrough();
    int step = (int) longOr(Forms.query(req.uri(), "step"), 1);
    if (step < 1) {
      step = 1;
    }
    if (step > steps.size()) {
      step = steps.size();
    }
    io.hearth.settings.Settings.Step current = steps.get(step - 1);
    Map<String, String> decided = accounts.settings.overrides();
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (io.hearth.settings.Setting setting : current.settings()) {
      rows.add(settingRow(setting, config, decided));
    }
    model.put("stepTitle", current.title());
    model.put("stepBlurb", current.blurb());
    model.put("settings", rows);
    model.put("step", step);
    model.put("steps", steps.size());
    model.put("isLast", step == steps.size());
    model.put("hasPrev", step > 1);
    model.put("prevUrl", AdminView.Section.setup.path(config) + "?step=" + (step - 1));
    model.put("setupDone", accounts.settings.isSetupComplete());
    model.put("settingsUrl", AdminView.Section.configuration.path(config));
    ArrayList<Map<String, Object>> crumbs = new ArrayList<>();
    for (int k = 0; k < steps.size(); k++) {
      LinkedHashMap<String, Object> crumb = new LinkedHashMap<>();
      crumb.put("n", k + 1);
      crumb.put("title", steps.get(k).title());
      crumb.put("here", k + 1 == step);
      crumb.put("url", AdminView.Section.setup.path(config) + "?step=" + (k + 1));
      crumbs.add(crumb);
    }
    model.put("crumbs", crumbs);
  }

  private Outcome actOnConfiguration(DomainConfig config, Accounts accounts, Forms form,
                                     UserRecord me) throws SQLException {
    String action = String.valueOf(form.get("action"));
    if (action.equals("reset")) {
      String key = form.get("key");
      if (!io.hearth.settings.Settings.isKnown(key)) {
        return Outcome.refused("That is not a setting this server has.");
      }
      accounts.settings.clear(key, me.id());
      return Outcome.done("Back to what the config file says.",
          c -> AdminView.Section.configuration.path(c));
    }
    if (!action.equals("save")) {
      return Outcome.refused("That is not something this page can do.");
    }
    Outcome refused = saveSettings(config, accounts, form, me,
        io.hearth.settings.Settings.all());
    if (refused != null) {
      return refused;
    }
    return Outcome.done("Saved.", c -> AdminView.Section.configuration.path(c));
  }

  private Outcome actOnSetup(DomainConfig config, Accounts accounts, Forms form, UserRecord me)
      throws SQLException {
    java.util.List<io.hearth.settings.Settings.Step> steps =
        io.hearth.settings.Settings.walkthrough();
    int step = (int) longOr(form.get("step"), 1);
    if (step < 1 || step > steps.size()) {
      return Outcome.refused("That is not a step of the setup.");
    }
    Outcome refused = saveSettings(config, accounts, form, me, steps.get(step - 1).settings());
    if (refused != null) {
      return refused;
    }
    if (step < steps.size()) {
      int next = step + 1;
      return Outcome.done("Saved.",
          c -> AdminView.Section.setup.path(c) + "?step=" + next);
    }
    accounts.settings.set(io.hearth.settings.Settings.SETUP_DONE, "true", me.id());
    return Outcome.done("Setup finished. Everything here can still be changed.",
        c -> AdminView.Section.configuration.path(c));
  }

  /**
   * Write a batch of settings, refusing the whole batch if the result would not parse.
   *
   * The rebuild is attempted <b>before</b> anything is committed, which is the difference between a
   * form that says no and a community that is briefly running on a configuration nobody checked. It
   * is the same parse the server does at boot, so the message somebody reads here is the message
   * they would have got from a bad config file.
   */
  private Outcome saveSettings(DomainConfig config, Accounts accounts, Forms form, UserRecord me,
                               java.util.List<io.hearth.settings.Setting> settings)
      throws SQLException {
    Map<String, String> wanted = new java.util.LinkedHashMap<>(accounts.settings.overrides());
    Map<String, String> writing = new java.util.LinkedHashMap<>();
    for (io.hearth.settings.Setting setting : settings) {
      if (io.hearth.settings.Settings.isMeta(setting.key())) {
        continue;
      }
      String value;
      if (setting.kind() == io.hearth.settings.Setting.Kind.bool) {
        // an unticked box posts nothing at all, so "off" has to be written down rather than read
        // as "say nothing about this" -- otherwise a community could never turn one off again
        value = form.get(setting.field()) != null ? "true" : "false";
      } else {
        value = form.text(setting.field());
      }
      value = value == null ? "" : value.trim();
      writing.put(setting.key(), value);
      if (value.isEmpty()) {
        wanted.remove(setting.key());
      } else {
        wanted.put(setting.key(), value);
      }
    }
    Outcome oversized = oversized(form);
    if (oversized != null) {
      return oversized;
    }
    try {
      config.with(wanted);
    } catch (io.hearth.common.ConfigException ex) {
      return Outcome.refused(ex.getMessage());
    }
    for (Map.Entry<String, String> entry : writing.entrySet()) {
      accounts.settings.set(entry.getKey(), entry.getValue(), me.id());
    }
    return null;
  }

  private void appearance(Map<String, Object> model, Accounts accounts) {
    ArrayList<Map<String, Object>> scopes = new ArrayList<>();
    for (Theme.Scope scope : Theme.Scope.values()) {
      Theme theme = accounts.themes.of(scope);
      LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
      entry.put("scope", scope.name());
      entry.put("title", scope == Theme.Scope.site ? "The community" : "The administration");
      entry.put("about", scope == Theme.Scope.site
          ? "What members see on every page, and what every email this community sends is built from."
          : "This section, and the terms and privacy policy.");
      entry.put("custom", !theme.isDefault());
      entry.put("rows", theme.rows());
      scopes.add(entry);
    }
    model.put("scopes", scopes);
  }

  private void legal(Map<String, Object> model, DomainConfig config, Accounts accounts) {
    ArrayList<Map<String, Object>> docs = new ArrayList<>();
    for (LegalDoc doc : LegalDoc.values()) {
      LegalDocs.Text text = accounts.legal.of(doc);
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("title", doc.title);
      row.put("summary", doc.summary);
      row.put("publicUrl", doc.path());
      row.put("editUrl", AdminView.Section.legal.path(config) + "/edit/" + doc.slug);
      row.put("overridden", text.overridden());
      row.put("updated", text.updatedAt() == null ? "" : "edited " + stamp(text.updatedAt()));
      docs.add(row);
    }
    model.put("docs", docs);
    model.put("lbrace", "{{");
    model.put("rbrace", "}}");
  }

  /**
   * Every message this server sends, and whether anybody has rewritten it.
   *
   * One screen rather than a setting scattered across four config files, which is what this
   * replaced: the invitation's paragraph lived in `calendar.invite-template` and nothing else did,
   * so a community that wanted to change the sign-in code's wording had nowhere to go.
   */
  private void messages(Map<String, Object> model, DomainConfig config, Accounts accounts) {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (io.hearth.mail.SystemTemplate template : io.hearth.mail.SystemTemplate.values()) {
      io.hearth.mail.SystemTemplates.Wording wording = accounts.messages.of(template);
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("slug", template.name());
      row.put("label", template.label);
      row.put("subject", wording.subject());
      row.put("overridden", wording.overridden());
      row.put("updated", wording.updatedAt() == null ? "" : "edited " + stamp(wording.updatedAt()));
      row.put("editUrl", AdminView.Section.messages.path(config) + "/edit/" + template.name());
      rows.add(row);
    }
    model.put("messages", rows);
    model.put("lbrace", "{{");
    model.put("rbrace", "}}");
  }

  private Outcome actOnMessage(DomainConfig config, Accounts accounts, Forms form,
                               UserRecord me) throws SQLException {
    io.hearth.mail.SystemTemplate template =
        io.hearth.mail.SystemTemplate.of(form.get("slug"));
    if (template == null) {
      return Outcome.refused("That is not a message this server sends.");
    }
    String action = String.valueOf(form.get("action"));
    if (action.equals("reset")) {
      accounts.messages.reset(template, me.id());
      return Outcome.done(template.label + " is back to the wording that ships.",
          site -> AdminView.Section.messages.path(site));
    }
    if (action.equals("test")) {
      // Sent for real, to an address somebody typed, right now.
      //
      // A preview renders the words; it cannot tell anybody whether the message arrives, whether
      // it looks right in Outlook, or whether it lands in a spam folder -- and those are the three
      // things somebody editing an email actually wants to know.
      String to = form.get("to");
      if (to == null || to.indexOf('@') <= 0) {
        return Outcome.refused("An address to send it to.");
      }
      Mailer.Outcome sent = sendTest(config, accounts, template, to);
      return sent.delivered()
          ? Outcome.done("Sent to " + to + ". It went through the same path a real one does, so"
              + " what arrives is what everybody gets.",
              site -> AdminView.Section.messages.path(site) + "/edit/" + template.name())
          : Outcome.refused("That did not send: " + sent.detail());
    }
    if (!action.equals("save")) {
      return Outcome.refused("That is not something this page can do.");
    }
    String subject = form.text("subject");
    String lead = form.text("lead");
    String body = form.text("body");
    Outcome oversized = oversized(form);
    if (oversized != null) {
      return oversized;
    }
    if (subject == null || subject.isBlank()) {
      return Outcome.refused("A message needs a subject line.");
    }
    io.hearth.mail.SystemTemplates.Wording saved =
        accounts.messages.save(template, subject, lead, body, me.id());
    return Outcome.done(saved.overridden()
        ? template.label + " saved."
        : template.label + " matched the wording that ships, so the override was removed.",
        site -> AdminView.Section.messages.path(site));
  }

  /**
   * One real message, to one address, for whoever is editing the wording.
   *
   * Every flow goes through the same {@link Mailer} the community uses, with values that read like
   * a real message rather than like a test -- because the point is to see the thing somebody will
   * receive, and a preview cannot say whether it arrives, whether it survives Outlook, or whether
   * it lands in a spam folder.
   */
  private Mailer.Outcome sendTest(DomainConfig config, Accounts accounts,
                                  io.hearth.mail.SystemTemplate template, String to) {
    Mailer.Envelope envelope = Mailer.Envelope.to(config, accounts, to, null);
    return switch (template) {
      case register_code -> mailer.sendRegistrationCode(envelope, "482913");
      case login_code -> mailer.sendLoginCode(envelope, "482913");
      case two_factor -> mailer.sendTwoFactorCode(envelope, "482913");
      case password_reset -> mailer.sendPasswordReset(envelope, "482913",
          "https://" + config.domain + config.urls.resetPassword + "?code=482913");
      case password_changed -> mailer.sendPasswordChanged(envelope);
      case board_notice -> mailer.sendBoardNotice(envelope, new Mailer.Notice("replied to you",
          "Ana Rivera", "I will bring the flour.",
          "https://" + config.domain + config.urls.board));
      case digest -> mailer.sendDigest(envelope, new Mailer.Digest("today",
          List.of(new Mailer.Notice("posted", "Ana Rivera", "", "")),
          "https://" + config.domain + config.urls.board, null));
      case invite_welcome, invite_reminder, invite_apology -> mailer.sendInvite(envelope,
          new io.hearth.mail.InviteMail.Invitation(config.name, config.domain,
              switch (template) {
                case invite_reminder -> io.hearth.mail.InviteMail.Touch.reminder;
                case invite_apology -> io.hearth.mail.InviteMail.Touch.apology;
                default -> io.hearth.mail.InviteMail.Touch.welcome;
              },
              "https://" + config.domain + config.urls.register, null, null, "Ana Rivera",
              config.invites));
      case event_invite, event_changed, event_cancelled, event_reminder ->
          mailer.sendEventInvite(envelope, new Mailer.EventInvite("Supper club",
              "Saturday 14 May, 7pm", "The Oak, back room", "Bring a chair.",
              "https://" + config.domain + config.urls.calendar, "", "REQUEST",
              io.hearth.calendar.Invitations.replyTo(config), switch (template) {
                case event_changed -> Mailer.Note.changed;
                case event_cancelled -> Mailer.Note.cancelled;
                case event_reminder -> Mailer.Note.reminder;
                default -> Mailer.Note.invitation;
              }));
    };
  }

  /**
   * Save or reset one palette.
   *
   * Every value is read through {@link Theme#isColour}, and a slot whose value is not a colour keeps
   * what it had rather than failing the save. A browser that does not implement `type=color` posts
   * whatever was typed, and the answer to that is "ignore it", not "lose the other eleven".
   */
  private Outcome actOnAppearance(Accounts accounts, Forms form, UserRecord me)
      throws SQLException {
    String action = String.valueOf(form.get("action"));
    Theme.Scope scope = Theme.Scope.of(form.get("scope"));
    if (scope == null) {
      return Outcome.refused("That is not a palette this server has.");
    }
    if (action.equals("reset")) {
      accounts.themes.reset(scope, me.id());
      return Outcome.done("the " + scope.name() + " palette is back to the defaults.");
    }
    if (!action.equals("save")) {
      return Outcome.refused("That is not something this page can do.");
    }
    Theme current = accounts.themes.of(scope);
    Theme.Palette light = current.light;
    Theme.Palette dark = current.dark;
    for (String[] slot : Theme.SLOTS) {
      String lightValue = form.get("light_" + slot[0]);
      if (Theme.isColour(lightValue)) {
        light = light.with(slot[0], Theme.normalize(lightValue));
      }
      String darkValue = form.get("dark_" + slot[0]);
      if (Theme.isColour(darkValue)) {
        dark = dark.with(slot[0], Theme.normalize(darkValue));
      }
    }
    accounts.themes.save(new Theme(scope, light, dark), me.id());
    return Outcome.done("the " + scope.name() + " palette is saved.");
  }

  /**
   * Believable values for the preview.
   *
   * Believable rather than "x", because the thing a preview is for is noticing that a sentence
   * reads badly with a real name in it -- and "Your {{community}} code" with the word "community"
   * in the middle of it looks fine right up until it goes out.
   */
  private static java.util.Map<String, String> sampleValues(DomainConfig config,
                                                            io.hearth.mail.SystemTemplate template) {
    java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
    values.put("community", config.name);
    values.put("domain", config.domain);
    values.put("site", "https://" + config.domain + "/");
    values.put("code", "482913");
    values.put("minutes", "10");
    values.put("link", "https://" + config.domain + config.urls.register);
    values.put("inviter", "Ana Rivera");
    values.put("about", config.invites.about);
    values.put("tagline", config.invites.tagline);
    values.put("who", "Bo Chen");
    values.put("what", "replied to you");
    values.put("excerpt", "I will bring the flour.");
    values.put("count", "4");
    values.put("period", "today");
    values.put("title", "Supper club");
    values.put("when", "Saturday 14 May, 7pm");
    values.put("where", "The Oak, back room");
    values.put("details", "Bring a chair.");
    return values;
  }

  private Outcome actOnLegal(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    String action = String.valueOf(form.get("action"));
    LegalDoc doc = LegalDoc.bySlug(form.get("slug"));
    if (doc == null) {
      return Outcome.refused("That is not a document this server publishes.");
    }
    if (action.equals("reset")) {
      accounts.legal.reset(doc, me.id());
      return Outcome.done(doc.title + " is back to the text this server ships.",
          config -> AdminView.Section.legal.path(config));
    }
    if (!action.equals("save")) {
      return Outcome.refused("That is not something this page can do.");
    }
    // a whole document, so the prose ceiling; checked immediately before the write, because the
    // list of oversized fields fills in as fields are read
    String body = orEmpty(form.text("body"));
    Outcome refused = oversized(form);
    if (refused != null) {
      return refused;
    }
    accounts.legal.save(doc, body, me.id());
    return Outcome.done(body.isBlank()
            ? doc.title + " is back to the text this server ships."
            : doc.title + " saved, and it is live at " + doc.path() + ".",
        config -> AdminView.Section.legal.path(config));
  }

  private void formModel(AdminView.Section section, DomainConfig config, Accounts accounts,
                         UserRecord me, Map<String, Object> model, String id) throws SQLException {
    model.put("backUrl", section.path(config));
    switch (section) {
      case invites -> {
        model.put("canBulk", accounts.access.can(me, Permission.invites_bulk));
        model.put("bulkMax", io.hearth.people.Invitations.MAX_BULK);
        model.put("heading", "Invite somebody");
        model.put("community", config.name);
        model.put("lbrace", "{{");
        model.put("rbrace", "}}");
        // what will actually be sent, shown rather than described. An admin who can see the
        // message does not have to trust a sentence about it, and the one thing they might want to
        // change is right there rather than in a field they have to fill in every time.
        // The preview is the message as it will actually go, which means the wording comes from
        // where the mailer reads it: the community's own, when it has written one, and the shipped
        // text when it has not. Showing the config's default beside a message that says something
        // else would be a preview of a thing nobody receives.
        io.hearth.mail.SystemTemplates.Wording invite =
            accounts.messages.of(io.hearth.mail.SystemTemplate.invite_welcome);
        java.util.Map<String, String> values = sampleValues(config,
            io.hearth.mail.SystemTemplate.invite_welcome);
        values.put("inviter", me.email());
        model.put("previewTagline", config.invites.taglineFor(config.name));
        model.put("previewAbout",
            io.hearth.mail.SystemTemplates.fill(invite.body(), values));
        model.put("previewLead", io.hearth.mail.SystemTemplates.fill(invite.lead(), values));
        model.put("previewSubject", io.hearth.mail.SystemTemplates.fill(invite.subject(), values));
        model.put("previewButton", config.invites.callToAction);
        model.put("wordingUrl",
            AdminView.Section.messages.path(config) + "/edit/invite_welcome");
        model.put("overridden", invite.overridden());
        model.put("cadence", config.invites.remindersEnabled
            ? "A welcome goes out first. If nothing happens, a friendly reminder follows after "
                + config.invites.reminderAfterDays + " day(s), and a last note "
                + config.invites.apologyAfterDays + " day(s) after that."
            : "Reminders are switched off for this community, so only the welcome is sent.");
      }
      case legal -> {
        LegalDoc doc = LegalDoc.bySlug(id);
        if (doc == null) {
          doc = LegalDoc.terms;
        }
        LegalDocs.Text text = accounts.legal.of(doc);
        model.put("heading", doc.title);
        model.put("summary", doc.summary);
        model.put("slug", doc.slug);
        model.put("publicUrl", doc.path());
        model.put("overridden", text.overridden());
        // the box is pre-filled with whatever is published today, override or not, so that
        // "edit the terms" starts from the terms rather than from an empty page
        model.put("form_body", text.markdown());
        model.put("lbrace", "{{");
        model.put("rbrace", "}}");
      }
      case messages -> {
        io.hearth.mail.SystemTemplate template = io.hearth.mail.SystemTemplate.of(id);
        if (template == null) {
          template = io.hearth.mail.SystemTemplate.register_code;
        }
        io.hearth.mail.SystemTemplates.Wording wording = accounts.messages.of(template);
        model.put("heading", template.label);
        model.put("slug", template.name());
        model.put("overridden", wording.overridden());
        // pre-filled with what is being sent today, override or not, so "change the wording"
        // starts from the wording rather than from an empty box
        model.put("form_subject", wording.subject());
        model.put("form_lead", wording.lead());
        model.put("form_body", wording.body());
        ArrayList<Map<String, Object>> parameters = new ArrayList<>();
        for (String name : template.availableParameters()) {
          parameters.add(Map.of("name", name));
        }
        model.put("parameters", parameters);
        // a preview with the values filled in, because a template referring to something that does
        // not exist renders as a hole and nobody notices until it has gone out
        java.util.Map<String, String> sample = sampleValues(config, template);
        model.put("previewSubject",
            io.hearth.mail.SystemTemplates.fill(wording.subject(), sample));
        model.put("previewLead", io.hearth.mail.SystemTemplates.fill(wording.lead(), sample));
        model.put("previewBody", io.hearth.mail.SystemTemplates.fill(wording.body(), sample));
        model.put("lbrace", "{{");
        model.put("rbrace", "}}");
        model.put("backUrl", AdminView.Section.messages.path(config));
      }
      case placetypes -> {
        Places.Type type = id == null ? null : accounts.places.typeBySlug(id);
        model.put("editing", type != null);
        model.put("heading", type == null ? "New kind of place" : "Edit " + type.pluralOr());
        model.put("form_slug", type == null ? "" : type.slug());
        model.put("form_label", type == null ? "" : type.label());
        model.put("form_plural", type == null ? "" : type.plural());
        model.put("form_description", type == null ? "" : type.description());
        model.put("form_template", type == null ? "" : type.templateName());
        model.put("form_icon", type == null ? "" : type.icon());
        model.put("form_sort", type == null ? "0" : Integer.toString(type.sort()));
        model.put("form_published", type != null && type.published());
        ArrayList<Map<String, Object>> fieldRows = new ArrayList<>();
        int index = 0;
        for (TemplateField field : type == null ? List.<TemplateField>of() : type.fields()) {
          LinkedHashMap<String, Object> row = new LinkedHashMap<>();
          row.put("index", index++);
          row.put("name", field.name());
          row.put("label", orEmpty(field.label()));
          row.put("help", orEmpty(field.help()));
          row.put("required", field.required());
          row.put("types", fieldTypeOptions(field.type()));
          fieldRows.add(row);
        }
        model.put("fields", fieldRows);
        model.put("anyFields", !fieldRows.isEmpty());
        model.put("nextIndex", index);
        model.put("fieldTypes", fieldTypeOptions(null));
        ArrayList<Map<String, Object>> names = new ArrayList<>();
        for (TemplateRecord template : accounts.site.store().allTemplates(200)) {
          LinkedHashMap<String, Object> row = new LinkedHashMap<>();
          row.put("name", template.name());
          row.put("selected", type != null && template.name().equals(type.templateName()));
          names.add(row);
        }
        model.put("templates", names);
      }
      case places -> {
        Places.Place place = id == null ? null : accounts.places.byId(longOr(id));
        model.put("editing", place != null);
        model.put("heading", place == null ? "New address" : "Edit " + place.name());
        model.put("form_id", place == null ? "" : Long.toString(place.id()));
        model.put("form_slug", place == null ? "" : place.slug());
        model.put("form_name", place == null ? "" : place.name());
        model.put("form_address", place == null ? "" : place.address());
        model.put("form_locality", place == null ? "" : place.locality());
        model.put("form_region", place == null ? "" : place.region());
        model.put("form_postcode", place == null ? "" : place.postcode());
        model.put("form_country", place == null ? "" : place.country());
        model.put("form_latitude", place == null || place.latitude() == null
            ? "" : place.latitude().toString());
        model.put("form_longitude", place == null || place.longitude() == null
            ? "" : place.longitude().toString());
        model.put("form_url", place == null ? "" : place.url());
        model.put("form_phone", place == null ? "" : place.phone());
        model.put("form_email", place == null ? "" : place.email());
        model.put("form_body", place == null ? "" : place.body());
        model.put("form_published", place != null && place.published());
        model.put("form_human_only", place != null && place.humanOnly());
        model.put("kindsUrl", AdminView.Section.placetypes.path(config));
        model.put("form_kind", place == null ? "" : place.typeSlug());

        // The extra boxes come from the type, so the editor for a ranch asks about grass-finished
        // and the editor for a vendor asks about the discount. Changing a kind changes every
        // editor for it at once, which is the whole reason the fields are data.
        ArrayList<Map<String, Object>> kinds = new ArrayList<>();
        Places.Type chosen = place == null ? null : accounts.places.typeBySlug(place.typeSlug());
        for (Places.Type type : accounts.places.allTypes()) {
          LinkedHashMap<String, Object> row = new LinkedHashMap<>();
          row.put("slug", type.slug());
          row.put("label", type.labelOr());
          row.put("selected", chosen != null && chosen.slug().equals(type.slug()));
          kinds.add(row);
          if (chosen == null && place == null) {
            chosen = type;
          }
        }
        model.put("kinds", kinds);
        model.put("anyKinds", !kinds.isEmpty());
        ArrayList<Map<String, Object>> extras = new ArrayList<>();
        if (chosen != null) {
          Map<String, String> values = place == null ? Map.of() : place.values();
          for (TemplateField field : chosen.fields()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("field", "field_" + field.name());
            row.put("label", field.labelOr());
            row.put("help", field.help());
            row.put("required", field.required());
            row.put("value", values.getOrDefault(field.name(), ""));
            row.put("longText", field.type() == TemplateField.Type.multiline);
            extras.add(row);
          }
          model.put("kindLabel", chosen.labelOr());
        }
        model.put("extras", extras);
        model.put("anyExtras", !extras.isEmpty());

        // Every kind's declarations, and every value this place holds, handed to the browser so
        // that changing the kind is a swap rather than a page load. The blob goes in a data
        // attribute rather than into the script: mustache escapes for HTML and a script block does
        // not decode entities, which is invariant 22 and how the live panels broke once.
        ObjectNode shapes = JSON_OUT.createObjectNode();
        for (Places.Type type : accounts.places.allTypes()) {
          ArrayNode declared = shapes.putArray(type.slug());
          for (TemplateField field : type.fields()) {
            ObjectNode spec = declared.addObject();
            spec.put("name", field.name());
            spec.put("label", field.labelOr());
            spec.put("help", orEmpty(field.help()));
            spec.put("required", field.required());
            spec.put("multiline", field.type() == TemplateField.Type.multiline
                || field.type() == TemplateField.Type.markdown);
          }
        }
        model.put("kindShapes", shapes.toString());
        ObjectNode held = JSON_OUT.createObjectNode();
        if (place != null) {
          place.values().forEach(held::put);
        }
        model.put("kindValues", held.toString());
      }
      case roles -> {
        RoleDefs.Def def = id == null ? null : accounts.roleDefs.byName(id);
        model.put("editing", def != null);
        model.put("heading", def == null ? "New role" : "Edit " + def.labelOr());
        model.put("form_name", def == null ? "" : def.name());
        model.put("form_label", def == null ? "" : def.label());
        model.put("form_description", def == null ? "" : def.description());
        model.put("form_color", def == null ? "blue" : def.color());
        model.put("builtin", def != null && def.builtin());
        model.put("groups", permissionGroups(
            def == null ? java.util.EnumSet.noneOf(Permission.class) : def.permissions()));
      }
      case calendar -> {
        Calendar.Event event = id == null ? null : accounts.calendar.byId(longOr(id));
        model.put("editing", event != null);
        model.put("heading", event == null ? "New event" : "Edit " + event.title());
        model.put("form_id", event == null ? "" : Long.toString(event.id()));
        model.put("form_title", event == null ? "" : event.title());
        model.put("form_body", event == null ? "" : event.body());
        model.put("form_location", event == null ? "" : event.location());
        // the address book, offered as the location. Somewhere the community already wrote down is
        // better than the same address typed slightly differently on four events.
        ArrayList<Map<String, Object>> placeRows = new ArrayList<>();
        for (io.hearth.places.Places.Place place : accounts.places.all(500)) {
          LinkedHashMap<String, Object> row = new LinkedHashMap<>();
          row.put("id", place.id());
          row.put("name", place.name());
          row.put("address", place.address());
          row.put("selected", event != null && Long.valueOf(place.id()).equals(event.placeId()));
          placeRows.add(row);
        }
        model.put("places", placeRows);
        model.put("anyPlaces", !placeRows.isEmpty());
        // only for somebody who can open the address book; otherwise the sentence stands without
        // a link rather than pointing at a 404
        if (accounts.access.can(me, Permission.places_write)) {
          model.put("placesUrl", AdminView.Section.places.path(config));
        }
        // the three best hours of the week, said out loud on the form where a night is chosen.
        // This is the whole complaint the availability grid answers: somebody picks a Tuesday
        // because it was a Tuesday last time.
        if (config.has(io.hearth.vhost.Surface.availability) && availabilities != null) {
          io.hearth.availability.AvailabilityIndexer when =
              availabilities.forDomain(config.domain);
          if (when != null) {
            ArrayList<Map<String, Object>> best = new ArrayList<>();
            io.hearth.availability.Heatmap.Grid whenGrid = when.grid();
            for (io.hearth.availability.Heatmap.Cell cell
                : io.hearth.availability.Heatmap.bestHours(whenGrid, 3)) {
              LinkedHashMap<String, Object> row = new LinkedHashMap<>();
              row.put("when", io.hearth.availability.Heatmap.describe(cell));
              row.put("clear", cell.clear());
              row.put("ideal", cell.ideal());
              best.add(row);
            }
            model.put("bestHours", best);
            model.put("anyBestHours", !best.isEmpty());
            model.put("whenUrl", config.urls.availability);
          }
        }
        // How far people would have to come, if this event has a place and that place has been
        // put on the map. Counts and nothing else: what a private address is allowed to become is
        // a distance in a bucket, and never a name, an order or a pin.
        travelTo(model, accounts, config, event == null ? null : event.placeId());
        model.put("suggested", event != null && event.suggested());
        model.put("newEvent", event == null);
        String whyNot = io.hearth.calendar.Invitations.whyNot(config, inboundMail);
        model.put("invitesOn", whyNot == null);
        model.put("invitesWhyNot", whyNot);
        model.put("form_starts_on",
            event == null ? java.time.LocalDate.now().toString() : event.startsOn().toString());
        model.put("form_ends_on", event == null ? "" : event.endsOn().toString());
        model.put("form_start_time", event == null ? "" : event.startTime());
        model.put("form_capacity",
            event == null || event.capacity() == null ? "" : event.capacity().toString());
        model.put("form_published", event != null && event.published());
        if (event != null) {
          model.put("viewUrl", config.urls.calendar + "/" + event.id());
          model.put("icsUrl", config.urls.calendar + "/" + event.id() + ".ics");
          model.put("going", event.goingCount());
          model.put("waiting", event.waitlistCount());
          model.put("openToPublic", event.openToPublic());
          // Everybody from outside who answered, with their address.
          //
          // This is the one screen in the product where an address belongs beside a name for
          // somebody who is not a member: the decision being made here *is* about an address --
          // whether to invite it -- and it is the same reason the people section shows them.
          ArrayList<Map<String, Object>> outside = new ArrayList<>();
          for (Calendar.Outsider guest : accounts.calendar.outsiders(event.id())) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("id", guest.id());
            row.put("email", guest.email());
            row.put("name", guest.display());
            row.put("answer", guest.answer().name());
            row.put("party", guest.party());
            row.put("plus", guest.party() > 1 ? " +" + (guest.party() - 1) : "");
            row.put("invited", guest.invited());
            row.put("converted", guest.converted());
            row.put("at", guest.createdAt() == null ? "" : stamp(guest.createdAt()));
            outside.add(row);
          }
          model.put("outside", outside);
          model.put("anyOutside", !outside.isEmpty());
          model.put("canInvite", accounts.access.can(me, Permission.invites_send)
              && config.has(io.hearth.vhost.Surface.invites));
        }
      }
      case content -> {
        ContentRecord page = id == null ? null : accounts.site.store().byId(longOr(id));
        model.put("editing", page != null);
        model.put("heading", page == null ? "New page" : "Edit " + page.uri());
        if (page != null) {
          model.put("historyUrl",
              AdminView.Section.content.path(config) + "/history/" + page.id());
          model.put("versionCount", accounts.site.store().versions().count(page.id()));
          model.put("bundleUrl",
              AdminView.Section.content.path(config) + "/bundle/" + page.id());
          model.put("mergeKey", orEmpty(accounts.site.store().uuidOf(page.id())));
        }
        model.put("canSave", accounts.access.can(me, Permission.content_write));
        model.put("canSuggest", accounts.access.can(me, Permission.content_propose));
        // the box is only offered to somebody who could act on it: a checkbox that refuses the
        // save is worse than no checkbox, because it looks like a decision they are allowed to make
        model.put("canPublish", accounts.access.can(me, Permission.content_publish));
        model.put("suggestOnly", !accounts.access.can(me, Permission.content_write)
            && accounts.access.can(me, Permission.content_propose));
        if (page != null) {
          model.put("openSuggestions",
              accounts.site.store().proposals().forContent(page.id(), 20).stream()
                  .filter(io.hearth.content.Proposals.Proposal::isOpen).count());
          model.put("proposalsUrl", AdminView.Section.proposals.path(config));
        }
        model.put("form_id", page == null ? "" : Long.toString(page.id()));
        model.put("form_uri", page == null ? "" : page.uri());
        model.put("form_title", page == null ? "" : page.title());
        model.put("form_body", page == null ? "" : page.body());
        model.put("form_folder", page == null ? "" : orEmpty(page.navFolder()));
        model.put("form_published", page == null || page.published());
        model.put("form_human_only", page != null && page.humanOnly());
        model.put("form_published_at", page == null || page.publishedOn() == null ? ""
            : page.publishedOn().toLocalDateTime().toLocalDate().toString());
        model.put("sorts", sortOptions(page));
        model.put("anySorts", page != null && !page.kind().sorts().isEmpty());
        // which kind of place a place listing shows; * is every one, and it is the default so a
        // listing that says nothing lists everything rather than nothing
        ArrayList<Map<String, Object>> placeKinds = new ArrayList<>();
        String chosenKind = page == null ? "*"
            : io.hearth.content.Feeds.setting(page, "place_kind", "*");
        placeKinds.add(Map.of("value", "*", "label", "* -- every kind",
            "selected", "*".equals(chosenKind)));
        for (io.hearth.places.Places.Type type : accounts.places.allTypes()) {
          placeKinds.add(Map.of("value", type.slug(), "label", type.pluralOr(),
              "selected", type.slug().equals(chosenKind)));
        }
        model.put("placeKinds", placeKinds);
        model.put("isPlaceListing",
            page != null && page.kind() == ContentRecord.Kind.place_listing);
        model.put("kinds", contentKindOptions(page));
        // the picker, offered only to somebody who could upload one anyway: a button that opens a
        // panel they cannot fetch is a door drawn on a wall
        if (config.has(io.hearth.vhost.Surface.attachments)
            && accounts.access.can(me, Permission.attachments_write)) {
          model.put("filesUrl", AdminView.panelPath(AdminView.Section.attachments, config)
              + "?pick=1");
        }
        model.put("lbrace", "{{");
        model.put("rbrace", "}}");
        model.put("templateNames", templateOptions(accounts, page));
        model.put("folders", folderOptions(accounts));
        // every template's fields, so changing the template swaps the right boxes in without
        // another round trip
        model.put("templateFieldsJson", templateFieldJson(accounts, page));
        model.put("wantsTemplate", page == null || page.kind().wantsTemplate());
        model.put("form_page_size", page == null ? ""
            : orEmpty(io.hearth.content.Site.fieldsOf(page).get("page_size")));
      }
      case directories -> {
        TemplateRecord template = id == null ? null : accounts.site.store().templateByName(id);
        if (template == null) {
          model.put("heading", "No such template");
          model.put("missing", true);
          break;
        }
        model.put("heading", template.name() + " — the index it publishes");
        model.put("form_name", template.name());
        model.put("form_directory", template.directory());
        model.put("form_directory_path", template.directoryPath());
        model.put("form_directory_pattern", template.directoryPattern());
        model.put("form_directory_page_size", template.pageSize());
        model.put("form_newest", template.newestFirst());
        model.put("form_directory_body",
            template.hasOwnIndex() ? template.directoryBody()
                : io.hearth.content.ContentStore.defaultIndexBody());
        model.put("ownIndex", template.hasOwnIndex());
        model.put("entries", accounts.site.store().countUsingTemplate(template.name()));
        model.put("templateUrl",
            AdminView.Section.templates.path(config) + "/edit/" + template.name());
        model.put("lbrace", "{{");
        model.put("rbrace", "}}");
        ArrayList<Map<String, Object>> fields = new ArrayList<>();
        for (TemplateField field : template.fields()) {
          fields.add(Map.of("name", field.name(), "label", field.labelOr()));
        }
        model.put("fields", fields);
        model.put("anyFields", !fields.isEmpty());
      }
      case templates -> {
        TemplateRecord template = id == null ? null : accounts.site.store().templateByName(id);
        model.put("editing", template != null);
        model.put("heading", template == null ? "New template" : "Edit " + template.name());
        model.put("form_name", template == null ? "" : template.name());
        model.put("form_body", template == null ? defaultTemplate() : template.body());
        model.put("form_directory", template != null && template.directory());
        model.put("form_directory_path", template == null ? "" : template.directoryPath());
        if (template != null) {
          model.put("directoriesUrl",
              AdminView.Section.directories.path(config) + "/edit/" + template.name());
        }
        model.put("form_directory_pattern", template == null ? "" : template.directoryPattern());
        model.put("form_directory_page_size", template == null ? 10 : template.pageSize());
        model.put("form_newest", template == null || template.newestFirst());
        model.put("lbrace", "{{");
        model.put("rbrace", "}}");
        ArrayList<Map<String, Object>> fields = new ArrayList<>();
        int index = 0;
        for (TemplateField field : template == null ? List.<TemplateField>of() : template.fields()) {
          LinkedHashMap<String, Object> row = new LinkedHashMap<>();
          row.put("index", index++);
          row.put("name", field.name());
          row.put("label", orEmpty(field.label()));
          row.put("help", orEmpty(field.help()));
          row.put("required", field.required());
          row.put("types", fieldTypeOptions(field.type()));
          fields.add(row);
        }
        model.put("fields", fields);
        model.put("anyFields", !fields.isEmpty());
        model.put("nextIndex", index);
        model.put("fieldTypes", fieldTypeOptions(null));
      }
      case survey -> {
        Question question = id == null ? null : accounts.people.questionById(longOr(id));
        model.put("editing", question != null);
        model.put("heading", question == null ? "Ask a question" : "Edit question");
        model.put("form_id", question == null ? "" : Long.toString(question.id()));
        model.put("form_prompt", question == null ? "" : question.prompt());
        model.put("form_help", question == null ? "" : question.help());
        model.put("form_options", question == null ? "" : String.join("\n", question.options()));
        model.put("form_min", question == null ? 1 : question.min());
        model.put("form_max", question == null ? 5 : question.max());
        model.put("form_required", question != null && question.required());
        model.put("form_published", question == null || question.published());
        model.put("form_position", question == null ? 0 : question.position());
        model.put("kinds", questionKindEditorOptions(question));
      }
      default -> {
      }
    }
  }

  /**
   * Everything a page has ever said, newest first.
   *
   * This is the screen that replaces reaching for git, so it leads with who and when and what
   * changed -- the three things somebody scanning a history is actually looking for -- and puts the
   * preview one click away rather than making them reconstruct anything in their head.
   */
  private void historyModel(DomainConfig config, Accounts accounts, Map<String, Object> model,
                            String id) throws SQLException {
    long contentId = longOr(id);
    ContentRecord page = accounts.site.store().byId(contentId);
    model.put("backUrl", AdminView.Section.content.path(config));
    if (page == null) {
      model.put("missing", true);
      model.put("heading", "Not found");
      return;
    }
    model.put("heading", "History of " + page.uri());
    model.put("uri", page.uri());
    model.put("editUrl", AdminView.Section.content.path(config) + "/edit/" + contentId);
    model.put("previewBase",
        AdminView.Section.content.path(config) + "/history/" + contentId + "/version/");
    model.put("changesBase",
        AdminView.Section.content.path(config) + "/history/" + contentId + "/changes/");
    model.put("action", AdminView.Section.content.path(config));
    model.put("contentId", contentId);

    ContentVersions versions = accounts.site.store().versions();
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    List<ContentVersions.Entry> entries = versions.history(contentId);
    for (ContentVersions.Entry entry : entries) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("version", entry.version());
      row.put("summary", entry.summary());
      row.put("who", entry.who());
      row.put("when", stamp(entry.createdAt()));
      row.put("snapshot", entry.snapshot());
      row.put("bytes", entry.bytes());
      row.put("latest", entry.version() == entries.get(0).version());
      row.put("firstVersion", entry.version() <= 1);
      rows.add(row);
    }
    model.put("versions", rows);
    model.put("anyVersions", !rows.isEmpty());
    model.put("count", rows.size());
  }

  /**
   * Put an old version back.
   *
   * A restore is a *save*, not a rewind. The old text becomes the newest version and the history
   * keeps everything that came before it, including the edit being undone -- because a history that
   * loses the mistake also loses the evidence of what was tried, and an undo that quietly deleted
   * three versions would be the most dangerous button on the page. This is what `git revert` does
   * and what `git reset --hard` does not, and the difference is the reason to have a history.
   *
   * The uri comes from the *current* page rather than the version. Restoring the words is what
   * somebody means; moving the page back to an address that may now hold something else is not, and
   * would be a collision nobody asked for.
   */
  private Outcome restoreVersion(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    Long id = longOf(form.get("id"));
    Long version = longOf(form.get("version"));
    if (id == null || version == null) {
      return Outcome.refused("That version could not be found.");
    }
    ContentRecord current = accounts.site.store().byId(id);
    if (current == null) {
      return Outcome.refused("That page is gone, and its history went with it.");
    }
    ContentRecord old;
    try {
      old = accounts.site.store().versions().versionOf(id, version.intValue());
    } catch (TextPatch.PatchException ex) {
      return Outcome.refused("That version could not be rebuilt: " + ex.getMessage());
    }
    if (old == null) {
      return Outcome.refused("There is no version " + version + " of that page.");
    }
    ContentRecord restored = new ContentRecord(current.id(), current.uri(), old.title(), old.kind(),
        old.templateName(), old.navFolder(), old.fields(), old.body(), old.published(),
        old.humanOnly(), null, null, me.id());
    accounts.site.store().save(restored, me.id(), me.email());
    return Outcome.done("Restored version " + version + " of " + current.uri()
        + " as a new version.", config ->
        AdminView.Section.content.path(config) + "/history/" + current.id());
  }

  /**
   * What one save actually changed.
   *
   * Against the version before it rather than against now, because "what did this edit do" is the
   * question somebody scanning a history is asking -- and it is the question a commit answers. The
   * first version has nothing before it and says so rather than showing the whole page as an
   * insertion, which would be true and useless.
   *
   * Unchanged runs are collapsed to a marker. A diff that prints four hundred identical lines
   * around a one-word fix is a diff nobody reads, which is the same failure as the patch that was
   * correct and enormous.
   */
  private byte[] versionChanges(Accounts accounts, String id) throws SQLException {
    int colon = id.indexOf(':');
    long contentId = longOr(colon < 0 ? id : id.substring(0, colon));
    int version = colon < 0 ? -1 : (int) longOr(id.substring(colon + 1));
    ContentVersions versions = accounts.site.store().versions();

    Map<String, Object> model = new HashMap<>();
    model.put("version", version);
    model.put("previous", version - 1);
    if (version <= 1) {
      model.put("first", true);
      return templates.render("admin/content_changes", model);
    }
    try {
      String after = versions.reconstruct(contentId, version);
      String before = versions.reconstruct(contentId, version - 1);
      if (after == null || before == null) {
        model.put("problem", "One of those versions is not in the history.");
        return templates.render("admin/content_changes", model);
      }
      diffInto(model, before, after);
    } catch (TextPatch.PatchException ex) {
      // a broken chain is reported, never papered over -- same rule as the preview
      model.put("problem", "Those versions could not be compared: " + ex.getMessage());
    }
    return templates.render("admin/content_changes", model);
  }

  /**
   * One comparison, rendered for a person.
   *
   * Shared by the history (what one save did) and the review queue (what a suggestion would do),
   * because two implementations of "show me the difference" would eventually disagree and the one
   * somebody is looking at is the one they would believe.
   */
  private static void diffInto(Map<String, Object> model, String before, String after) {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    int same = 0;
    int added = 0;
    int removed = 0;
    List<TextPatch.Change> changes = TextPatch.changes(before, after);
    for (int k = 0; k < changes.size(); k++) {
      TextPatch.Change change = changes.get(k);
      if (change.kind() == TextPatch.Kind.same) {
        same++;
        if (!nearAChange(changes, k)) {
          continue;
        }
      } else if (change.kind() == TextPatch.Kind.added) {
        added++;
      } else {
        removed++;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("kind", change.kind().name());
      row.put("added", change.kind() == TextPatch.Kind.added);
      row.put("removed", change.kind() == TextPatch.Kind.removed);
      row.put("same", change.kind() == TextPatch.Kind.same);
      row.put("text", change.text().isEmpty() ? " " : change.text());
      row.put("before", change.beforeLine() == 0 ? "" : Integer.toString(change.beforeLine()));
      row.put("after", change.afterLine() == 0 ? "" : Integer.toString(change.afterLine()));
      rows.add(row);
    }
    model.put("rows", rows);
    model.put("anyRows", !rows.isEmpty());
    model.put("addedCount", added);
    model.put("removedCount", removed);
    model.put("unchanged", same);
    model.put("identical", added == 0 && removed == 0);
  }

  /** whether an unchanged line is close enough to a change to be worth showing as context */
  private static boolean nearAChange(List<TextPatch.Change> changes, int at) {
    int from = Math.max(0, at - 2);
    int to = Math.min(changes.size() - 1, at + 2);
    for (int k = from; k <= to; k++) {
      if (changes.get(k).kind() != TextPatch.Kind.same) {
        return true;
      }
    }
    return false;
  }

  /**
   * One old version, rendered the way the site would render it.
   *
   * Rendered rather than shown as source, because "what did this page look like" is the question --
   * and rendered through the *current* template, because that is what restoring it would produce.
   */
  private byte[] versionPreview(DomainConfig config, Accounts accounts, String id, String csrf)
      throws SQLException {
    Map<String, Object> model = new HashMap<>();
    int colon = id == null ? -1 : id.indexOf(':');
    long contentId = colon < 0 ? -1 : longOr(id.substring(0, colon));
    int version = colon < 0 ? -1 : (int) longOr(id.substring(colon + 1));
    ContentVersions versions = accounts.site.store().versions();

    model.put("version", version);
    try {
      ContentRecord old = versions.versionOf(contentId, version);
      if (old == null) {
        model.put("problem", "There is no version " + version + " of that page.");
        return templates.render("admin/content_version", model);
      }
      model.put("uri", old.uri());
      model.put("title", old.title());
      model.put("kind", old.kind().label);
      model.put("template", orEmpty(old.templateName()));
      model.put("folder", orEmpty(old.navFolder()));
      model.put("published", old.published());
      model.put("humanOnly", old.humanOnly());
      model.put("body", old.body());
      model.put("rendered", accounts.site.renderPreview(old));
    } catch (RuntimeException ex) {
      // a broken chain is reported, never papered over: a history that shows a plausible wrong page
      // is worse than one that admits a gap
      model.put("problem", "That version could not be rebuilt: " + ex.getMessage());
    }
    return templates.render("admin/content_version", model);
  }

  private void reviewModel(DomainConfig config, Accounts accounts, Map<String, Object> model,
                           String id) throws SQLException {
    UserRecord person = accounts.users.byId(longOr(id));
    model.put("backUrl", AdminView.Section.people.path(config));
    if (person == null) {
      model.put("missing", true);
      model.put("heading", "Not found");
      return;
    }
    boolean isAdmin = accounts.access.isAdmin(person);
    model.put("heading", person.email());
    model.put("person", SelfRoutes.reviewOf(accounts, person));
    model.put("id", person.id());
    model.put("approved", person.isApproved());
    model.put("disabled", person.disabled());
    model.put("configAdmin", accounts.access.isBootstrapAdmin(person.email()));
    model.put("promotedAdmin", isAdmin && !accounts.access.isBootstrapAdmin(person.email()));
    model.put("admin", isAdmin);
    // saying why the buttons are absent beats hiding them and leaving somebody guessing
    model.put("rejectable", !isAdmin);
    // A test notification, for the one question nobody can answer from a screen: does this
    // actually reach their phone? Only offered when a browser of theirs is subscribed, because a
    // button that would go nowhere is a button that teaches somebody the feature is broken.
    try {
      int devices = accounts.pushSubs.forUser(person.id()).size();
      model.put("anyPush", devices > 0);
      model.put("canNotify", devices > 0);
      io.hearth.push.PushLedger.Delay delay = accounts.pushLedger.delayFor(person.id());
      if (delay.sentAt() != null) {
        long minutes = delay.minutes();
        model.put("pushSummary", devices + " subscribed browser(s). Last push "
            + stamp(delay.sentAt())
            + (minutes < 0 ? ", and nothing has come back from it yet."
                : ", answered " + minutes + " minute(s) later."));
      } else {
        model.put("pushSummary", devices + " subscribed browser(s), nothing sent yet.");
      }
    } catch (SQLException ex) {
      model.put("anyPush", false);
    }
    model.put("erasable", !isAdmin);
    model.put("exportUrl", AdminView.Section.people.path(config) + "/export/" + person.id());
    model.put("events", person.signupEvents());
    model.put("signals", orEmpty(person.signupSignals()));
    model.put("ip", orEmpty(person.signupIp()));
    model.put("created", stamp(person.createdAt()));
    Invites.Invite from = accounts.invites.forUser(person.id());
    model.put("invited", from != null);
    model.put("invitedBy", from == null ? "" : from.createdByEmail());
    model.put("invitedOn", from == null ? "" : stamp(from.createdAt()));
    model.put("inviteNote", from == null ? "" : from.note());
  }

  // ---- option lists ------------------------------------------------------------------------------------

  private static void option(String value, String label, boolean selected, List<Map<String, Object>> into) {
    LinkedHashMap<String, Object> item = new LinkedHashMap<>();
    item.put("value", value);
    item.put("label", label);
    item.put("selected", selected);
    into.add(item);
  }

  private static List<Map<String, Object>> stateOptions(String current) {
    String state = orEmpty(current);
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    option("all", "everybody", state.isEmpty() || state.equals("all"), options);
    option("waiting", "waiting", state.equals("waiting"), options);
    option("approved", "approved", state.equals("approved"), options);
    option("disabled", "turned off", state.equals("disabled"), options);
    option("admin", "admins", state.equals("admin"), options);
    return options;
  }

  private static List<Map<String, Object>> publishedOptions(String current) {
    String value = orEmpty(current);
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    option("", "published and drafts", value.isEmpty(), options);
    option("yes", "published only", value.equals("yes"), options);
    option("no", "drafts only", value.equals("no"), options);
    return options;
  }

  private static List<Map<String, Object>> questionKindOptions(String current) {
    String value = orEmpty(current);
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    option("all", "every kind", value.isEmpty() || value.equals("all"), options);
    for (Question.Kind kind : Question.Kind.values()) {
      option(kind.name(), kind.label, value.equals(kind.name()), options);
    }
    return options;
  }

  private static List<Map<String, Object>> questionKindEditorOptions(Question question) {
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    for (Question.Kind kind : Question.Kind.values()) {
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("value", kind.name());
      item.put("label", kind.label);
      item.put("describe", kind.describe);
      item.put("selected", question != null && question.kind() == kind);
      options.add(item);
    }
    return options;
  }

  private static List<Map<String, Object>> contentKindOptions(ContentRecord page) {
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    for (ContentRecord.Kind kind : ContentRecord.Kind.values()) {
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("value", kind.name());
      item.put("label", kind.label);
      item.put("describe", kind.describe);
      item.put("wantsTemplate", kind.wantsTemplate());
      // the address rule for this kind, carried on the option so the form can say it the moment
      // somebody picks one rather than making them read a paragraph covering all six
      item.put("uriRule", kind.uriRule());
      item.put("listing", kind.listing);
      item.put("placeKind", kind == ContentRecord.Kind.place_listing);
      item.put("selected", page != null && page.kind() == kind);
      options.add(item);
    }
    return options;
  }

  /** the orders a listing can be in, per kind, for the editor's dropdown */
  private static List<Map<String, Object>> sortOptions(ContentRecord page) {
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    ContentRecord.Kind kind = page == null ? ContentRecord.Kind.markdown : page.kind();
    String current = page == null ? "" : io.hearth.content.Feeds.setting(page, "sort", "");
    for (String sort : kind.sorts()) {
      option(sort, sort, sort.equals(current), options);
    }
    return options;
  }

  private static List<Map<String, Object>> fieldTypeOptions(TemplateField.Type current) {
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    for (TemplateField.Type type : TemplateField.Type.values()) {
      option(type.name(), type.name() + " -- " + type.describe, current == type, options);
    }
    return options;
  }

  private static List<Map<String, Object>> templateOptions(Accounts accounts, ContentRecord page) throws SQLException {
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    option("", "(none)", page == null || page.templateName() == null || page.templateName().isEmpty(), options);
    for (TemplateRecord template : accounts.site.store().allTemplates(PAGE_SIZE)) {
      option(template.name(), template.name(),
          page != null && template.name().equals(page.templateName()), options);
    }
    return options;
  }

  private static List<Map<String, Object>> folderOptions(Accounts accounts) throws SQLException {
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    for (String folder : accounts.site.store().folders()) {
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("value", folder);
      options.add(item);
    }
    return options;
  }

  /**
   * Every template's declared fields with this page's values, as JSON for the editor's script.
   *
   * In a data attribute rather than interpolated into the script, because mustache escapes for HTML
   * and HTML entities are not decoded inside a script block -- the exact mismatch that broke the
   * live buttons. An attribute is decoded by the parser, so it survives.
   */
  private static String templateFieldJson(Accounts accounts, ContentRecord page) throws SQLException {
    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.JsonNode values;
    try {
      values = mapper.readTree(page == null || page.fields() == null || page.fields().isBlank()
          ? "{}" : page.fields());
    } catch (Exception ex) {
      values = mapper.createObjectNode();
    }
    com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
    for (TemplateRecord template : accounts.site.store().allTemplates(PAGE_SIZE)) {
      com.fasterxml.jackson.databind.node.ArrayNode array = root.putArray(template.name());
      for (TemplateField field : template.fields()) {
        com.fasterxml.jackson.databind.node.ObjectNode node = array.addObject();
        node.put("name", "field_" + field.name());
        node.put("label", field.labelOr());
        node.put("help", orEmpty(field.help()));
        node.put("type", field.type().name());
        node.put("required", field.required());
        node.put("value", values.path(field.name()).asText(""));
      }
    }
    return root.toString();
  }

  // ---- plumbing --------------------------------------------------------------------------------------------

  private List<Map<String, Object>> eventRows(List<MutationEvent> recent) {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (MutationEvent event : recent) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("seq", event.seq());
      row.put("at", CLOCK.format(Instant.ofEpochMilli(event.atMillis())));
      row.put("domain", event.domain());
      row.put("table", event.table());
      row.put("key", event.key());
      row.put("kind", event.kind().name());
      row.put("actor", event.actor() == null ? "" : Long.toString(event.actor()));
      rows.add(row);
    }
    return rows;
  }

  private static List<Map<String, Object>> counts(List<AccessLog.Count> counts, long total) {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    long top = counts.isEmpty() ? 1 : Math.max(1, counts.get(0).count());
    for (AccessLog.Count count : counts) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("label", count.label());
      row.put("count", count.count());
      row.put("percent", total == 0 ? 0 : count.count() * 100 / total);
      row.put("width", count.count() * 100 / top);
      rows.add(row);
    }
    return rows;
  }

  /**
   * Remove an account and everything hanging off it, in an order that cannot strand a row.
   *
   * Children first, then the account, because the reverse leaves a profile pointing at an id that
   * no longer resolves -- and each DAO emits its own event, so every cache hears about its own
   * table rather than inferring from somebody else's.
   */
  private static UserRecord userFrom(Accounts accounts, Forms form) throws SQLException {
    Long id = longOf(form.get("user"));
    return id == null ? null : accounts.users.byId(id);
  }

  private static String stamp(java.sql.Timestamp at) {
    return at == null ? "" : WHEN.format(Instant.ofEpochMilli(at.getTime()));
  }

  private static boolean contains(String needle, String... haystacks) {
    for (String haystack : haystacks) {
      if (haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static Long longOf(String raw) {
    try {
      return raw == null ? null : Long.parseLong(raw);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static long longOr(String raw) {
    Long value = longOf(raw);
    return value == null ? -1 : value;
  }

  private static int intOr(String raw, int fallback) {
    try {
      return raw == null ? fallback : Integer.parseInt(raw.trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  /** what a brand new template starts as, so the first one an operator makes actually works */
  private static String defaultTemplate() {
    return "<!doctype html>\n<html lang=\"en\">\n<head>\n"
        + "  <meta charset=\"utf-8\">\n"
        + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
        + "  <title>{{title}}</title>\n</head>\n<body>\n"
        + "  <main>\n    {{{body}}}\n  </main>\n</body>\n</html>\n";
  }

  private byte[] notFoundPage(DomainConfig config, Accounts accounts, FullHttpRequest req) {
    Map<String, Object> model = new HashMap<>();
    Chrome.admin(model, accounts);
    model.put("title", "not found");
    model.put("community", config.name);
    model.put("nav", Navigation.forRequest(config, accounts, req));
    // nobody signed in: offer the way back to whatever they were asking for. An administrator whose
    // session lapsed on the way to a link is the common case, and a bare 404 leaves them with
    // nothing to press.
    if (AccountRoutes.currentSession(accounts, req) == null) {
      model.put("signInUrl", Landing.carry(config.urls.login, Landing.here(req)));
    }
    return templates.render("notfound", model);
  }


  /**
   * Every setting this box is actually running with, and where to change it.
   *
   * <b>A report, not an editor.</b> The config is read once before the socket opens and never
   * again -- invariant 1, and the reason nothing here has a save button. What that costs an
   * operator is the ability to *see* it: the values exist as fields on objects scattered through
   * the process, so "is `suggestions` on for this community" was previously answerable only by
   * reading a file on the box and trusting that it was the file the running process read.
   *
   * Every row names the key, so the answer to "how do I change this" is on the same line as the
   * thing being changed rather than in a manual somebody has to go and find.
   */
  private void settings(Map<String, Object> model, DomainConfig config, Accounts accounts)
      throws SQLException {
    ArrayList<Map<String, Object>> groups = new ArrayList<>();

    ArrayList<Map<String, Object>> server = new ArrayList<>();
    row(server, "bind", settings.bind, "which address the listeners bind to");
    row(server, "timezone", settings.zone.getId(),
        "an IANA zone id -- Europe/London, America/New_York, UTC. Every community here inherits it"
            + " unless its own config says otherwise.");
    row(server, "http-port", settings.httpPort,
        "always on: it serves the site and answers the certificate challenge, so it can never"
            + " become a redirect");
    row(server, "enable-https", settings.httpsEnabled, "terminate TLS, picking the certificate by"
        + " the name asked for");
    row(server, "https-port", settings.httpsPort, "where, when it is on");
    row(server, "http-bounce-port", settings.bouncePort == io.hearth.web.WebConfig.NO_PORT
        ? "off" : settings.bouncePort, "a listener that does nothing but send plain traffic to"
        + " https; off unless asked for");
    row(server, "enable-http2", settings.http2, "negotiated during the TLS handshake; HTTP/1.1"
        + " remains the fallback");
    row(server, "compact-html", settings.compactHtml,
        "take the template's own whitespace out of every page on the way to the browser");
    row(server, "max-request-bytes", settings.maxRequestBytes, "anything larger is a 413");
    row(server, "idle-seconds", settings.idleSeconds, "how long a quiet connection is held");
    row(server, "verbose", settings.verbose, "narrate every decision; --verbose also turns it on");
    groups.add(group("This server", io.hearth.cli.Root.CONFIG_FILE,
        "Ports, limits and the things that are true of the machine rather than of any one"
            + " community.", server));

    ArrayList<Map<String, Object>> mail = new ArrayList<>();
    row(mail, "smtp.enabled", settings.smtp.enabled,
        "receive email: calendar answers, and invitations mailed in");
    row(mail, "smtp.port", settings.smtp.port, "25 needs root; a higher port needs something in"
        + " front of it");
    row(mail, "smtp.hostname", settings.smtp.hostname, "what this server calls itself in a"
        + " greeting");
    row(mail, "smtp.check-senders", settings.smtp.checkSenders,
        "run SPF, DKIM and DMARC and stamp the result on every message");
    row(mail, "smtp.enforce-dmarc", settings.smtp.enforceDmarc,
        "refuse a message that fails a published p=reject; off by default because these have never"
            + " met real mail");
    row(mail, "smtp.max-message-bytes", settings.smtp.maxMessageBytes, "");
    row(mail, "smtp.max-recipients", settings.smtp.maxRecipients, "");
    groups.add(group("Inbound mail", io.hearth.cli.Root.CONFIG_FILE,
        "Off by default: port 25 needs root, and an unconfigured listener on it is found by"
            + " scanners within the hour.", mail));

    ArrayList<Map<String, Object>> gps = new ArrayList<>();
    row(gps, "gps.enabled", settings.gps.enabled, "turn an address into coordinates, for places in"
        + " the address book and for members who said where they are. On by default.");
    row(gps, "gps.service", settings.gps.service, "nominatim, opencage or geoapify -- the three"
        + " whose terms allow keeping the answer");
    row(gps, "gps.key", settings.gps.key == null || settings.gps.key.isBlank() ? "" : "set",
        "never shown here, and never in a log");
    row(gps, "gps.contact", settings.gps.contact.isBlank()
            ? "NOT SET -- please set one" : settings.gps.contact,
        "a way to reach whoever runs this server, sent in the User-Agent. Nominatim's policy asks"
            + " for it, and a client that cannot be reached can be blocked without warning --"
            + " which from in here looks exactly like geocoding quietly stopping.");
    row(gps, "(queue)", "one every " + (io.hearth.async.AsyncQueue.GAP_MILLIS / 1000.0)
            + "s, up to " + io.hearth.async.AsyncQueue.CAPACITY + " waiting",
        "not configurable, and deliberately slower than any of the services allow. The Async"
            + " screen shows what it is doing.");
    groups.add(group("Geocoding", io.hearth.cli.Root.CONFIG_FILE,
        "On by default, on OpenStreetMap's own service, which needs no account and no key. It is"
            + " the one thing here that sends anything a member typed to another company, so it is"
            + " one word to switch off and the privacy policy describes it. `--setup-gps` walks"
            + " through the paid alternatives.", gps));

    String file = config.configFile.getName();
    ArrayList<Map<String, Object>> community = new ArrayList<>();
    row(community, "name", config.name, "what this community is called, everywhere");
    row(community, "enabled", config.enabled, "a disabled domain looks unconfigured from outside");
    // the clock, and what time it actually is in it. A zone id is easy to mistype into something
    // real and wrong -- America/Indiana/Indianapolis is a place, Europe/Dublin is not London --
    // and the one thing that makes that obvious is the current time beside it.
    row(community, "timezone", config.zone.getId(),
        "an IANA zone id like Europe/London or America/New_York, inherited from config.cfg unless"
            + " this domain names one. It is now "
            + java.time.ZonedDateTime.now(config.zone)
                .format(java.time.format.DateTimeFormatter.ofPattern("EEEE HH:mm"))
            + " here (UTC" + java.time.ZonedDateTime.now(config.zone).getOffset() + ").");
    row(community, "units", config.imperial ? "imperial" : "metric",
        "'metric' for kilometres, 'imperial' for miles. It decides the buckets on the travel chart"
            + " an event's page shows, which is the only place this server prints a distance.");
    row(community, "wildcard", config.wildcard, "serve everything under this name; a wildcard can"
        + " never have a certificate, so name subdomains instead");
    row(community, "subdomains", config.subdomains.isEmpty() ? "none"
        : String.join(", ", config.subdomains), "the same community under another name");
    row(community, "accepts-mail", config.acceptsMail, "receive email for this domain at all");
    row(community, "use_database_domain",
        config.useDatabaseDomain == null ? "its own" : config.useDatabaseDomain,
        "share another community's accounts and content");
    row(community, "admin_emails", config.adminEmails.isEmpty() ? "none"
        : String.join(", ", config.adminEmails),
        "admins by fiat: approved, holding the role, and un-revocable from inside");
    row(community, "disabled", disabledList(config), "parts of the product this community does"
        + " not have");
    groups.add(group("This community", file,
        "Read once at boot from this file. Everything is on until it is named in `disabled`.",
        community));

    ArrayList<Map<String, Object>> urls = new ArrayList<>();
    row(urls, "urls.register", config.urls.register, "");
    row(urls, "urls.login", config.urls.login, "");
    row(urls, "urls.logout", config.urls.logout, "");
    row(urls, "urls.forgot-password", config.urls.forgotPassword, "");
    row(urls, "urls.reset-password", config.urls.resetPassword, "");
    row(urls, "urls.admin", config.urls.admin, "");
    row(urls, "urls.home", config.urls.home, "the member's dashboard, which is not the front page");
    row(urls, "urls.self", config.urls.self, "");
    row(urls, "urls.survey", config.urls.survey, "");
    row(urls, "urls.orientation", config.urls.orientation, "");
    row(urls, "urls.members", config.urls.members, "");
    row(urls, "urls.board", config.urls.board, "");
    row(urls, "urls.calendar", config.urls.calendar, "");
    row(urls, "urls.places", config.urls.places, "");
    row(urls, "urls.after-login", config.urls.afterLogin, "where signing in lands somebody");
    groups.add(group("Addresses", file,
        "Every account page has its own path and no two may share one; the server refuses to start"
            + " on a collision.", urls));

    ArrayList<Map<String, Object>> security = new ArrayList<>();
    io.hearth.auth.LoginSecurity login = config.loginSecurity;
    row(security, "login_security.mode", login.mode.name(),
        "passwordless, password, or password_and_code");
    row(security, "login_security.session-lifetime-seconds", login.sessionLifetimeSeconds,
        "0 means a session never expires on its own");
    row(security, "login_security.session-idle-seconds", login.sessionIdleSeconds,
        "0 means idleness never kills one");
    row(security, "login_security.max-active-sessions", login.maxActiveSessions,
        "0 means no cap; the cap only ever takes sessions older than the grace window");
    row(security, "login_security.max-active-sessions-grace-seconds", login.sessionCapGraceSeconds,
        "");
    row(security, "login_security.reaper-interval-seconds", login.reaperIntervalSeconds, "");
    row(security, "login_security.code-length", login.codeLength, "");
    row(security, "login_security.code-lifetime-seconds", login.codeLifetimeSeconds, "");
    row(security, "login_security.code-max-attempts", login.codeMaxAttempts, "");
    row(security, "login_security.code-requests-per-hour", login.codeRequestsPerHour, "");
    row(security, "login_security.lockout-threshold", login.lockoutThreshold,
        "0 means an account is never locked");
    row(security, "login_security.lockout-seconds", login.lockoutSeconds, "");
    row(security, "login_security.signup-ip-days", login.signupIpDays,
        "how long the sign-up address is kept; the privacy policy describes this number");
    row(security, "login_security.cookie-name", login.cookieName, "");
    row(security, "login_security.cookie-secure", login.cookieSecure,
        "set this once you are on https, or the session cookie travels in the clear");
    row(security, "login_security.cookie-same-site", login.cookieSameSite, "");
    row(security, "login_security.password-min-length", login.passwordMinLength,
        "only meaningful when passwords are in use");
    groups.add(group("Signing in", file,
        "Defaults are for a high-trust community; every one of them tightens with a line.",
        security));

    ArrayList<Map<String, Object>> parts = new ArrayList<>();
    row(parts, "board.enabled", config.board.enabled, config.board.describe());
    row(parts, "board.expiry-days", config.board.expiryDays,
        "0 keeps threads forever; the default is that a thread has a life");
    row(parts, "board.notification-days", config.board.notificationDays, "");
    row(parts, "calendar.enabled", config.calendar.enabled, "");
    row(parts, "calendar.past-days", config.calendar.pastDays, "how far back the calendar shows");
    row(parts, "calendar.suggestions", config.calendar.suggestions,
        "any approved member may put an event forward, into a queue");
    row(parts, "calendar.invites", config.calendar.invites,
        "send real calendar invitations; refused when inbound mail is off, because an answer has"
            + " nowhere to come back to");
    row(parts, "calendar.events-address",
        config.calendar.eventsAddressOr(config.ses.from, config.domain),
        "where an answer comes back to; derived from the sending address unless set");
    row(parts, "calendar.events-name", config.calendar.eventsNameOr(config.name), "");
    row(parts, "calendar.remind-days-before", config.calendar.remindDaysBefore.isEmpty() ? "off"
        : config.calendar.remindDaysBefore.toString(),
        "days before an event to nudge whoever has not answered");
    row(parts, "calendar.attendance-days", config.calendar.attendanceDays, "");
    row(parts, "availability.enabled", config.availability.enabled,
        "the weekly grid of when people can come");
    row(parts, "availability.refresh-hour", config.availability.refreshHour,
        "the hour everybody's calendars are read, once a day");
    row(parts, "availability.horizon-days", config.availability.horizonDays,
        "how far ahead a busy block counts against an hour");
    row(parts, "availability.max-links", config.availability.maxLinks,
        "calendars one person may attach");
    row(parts, "availability.fetch-timeout-seconds", config.availability.fetchTimeoutSeconds, "");
    row(parts, "attachments.enabled", config.attachments.enabled, "uploads at all");
    row(parts, "attachments.extensions", String.join(", ", config.attachments.extensions),
        "an allow list, and the only thing standing between an upload and this domain serving it");
    row(parts, "attachments.max-bytes", config.attachments.maxBytes, "the largest single upload");
    row(parts, "attachments.cache-bytes", config.attachments.cacheBytes,
        "recently-served bytes kept in memory, so a spike is not a disk storm");
    row(parts, "attachments.browser-cache-seconds", config.attachments.browserCacheSeconds,
        "browsers may keep one this long; shared caches never may");
    row(parts, "attachments.check-referrer", config.attachments.checkReferrer,
        "refuse a request that came from somebody else's page");
    row(parts, "attachments.allowed-referrers",
        config.attachments.allowedReferrers.isEmpty() ? "" 
            : String.join(", ", config.attachments.allowedReferrers),
        "other hosts allowed to embed these; this community's own never needs listing");
    row(parts, "places.enabled", config.places.enabled, "");
    row(parts, "places.label", config.places.label, "what the address book is called");
    row(parts, "invites.enabled", config.invites.enabled, "");
    row(parts, "invites.members-may-invite", config.invites.membersMayInvite, "");
    row(parts, "invites.member-daily-limit", config.invites.memberDailyLimit, "0 means no limit");
    row(parts, "invites.reminders", config.invites.remindersEnabled,
        "three messages and the third says it is the last");
    row(parts, "invites.reminder-after-days", config.invites.reminderAfterDays, "");
    row(parts, "invites.apology-after-days", config.invites.apologyAfterDays, "");
    groups.add(group("The parts of the product", file,
        "Each of these also disappears entirely if its name is in `disabled`.", parts));

    ArrayList<Map<String, Object>> robots = new ArrayList<>();
    row(robots, "api.enabled", config.api.enabled,
        "a person's own token, in a program: the same powers they have here and nothing more");
    row(robots, "api.token-days", config.api.tokenDays, "0 means a token never expires");
    row(robots, "api.max-tokens", config.api.maxTokens, "how many one person may hold at once");
    row(robots, "mcp.enabled", config.mcp.enabled,
        "the model endpoint; the one thing here that is off until asked for");
    row(robots, "mcp.path", config.mcp.path, "");
    row(robots, "mcp.read-only", config.mcp.readOnly, "a model that can read and never write");
    row(robots, "mcp.dynamic-registration", config.mcp.dynamicRegistration, "");
    groups.add(group("Programs and models", file,
        "Two different things: a token is a person holding a different keyboard; a connector is a"
            + " model somebody authorised on a consent screen.", robots));

    ArrayList<Map<String, Object>> outbound = new ArrayList<>();
    row(outbound, "ses.enabled", config.ses.enabled,
        config.ses.enabled ? "real email through Amazon" : "messages are printed to the terminal");
    row(outbound, "ses.region", config.ses.region, "");
    row(outbound, "ses.from", config.ses.from, "");
    row(outbound, "ses.from-name", config.ses.fromName, "");
    row(outbound, "ses.reply-to", config.ses.replyTo, "");
    row(outbound, "ses.access-key-id",
        config.ses.accessKeyId == null || config.ses.accessKeyId.isBlank() ? "" : "set",
        "never shown here; --setup-email writes it");
    groups.add(group("Outbound mail", file,
        "Per community, because the credentials are: one box can have a live community and one"
            + " being set up.", outbound));

    ArrayList<Map<String, Object>> files = new ArrayList<>();
    if (attachments != null) {
      row(files, "where", attachments.files().describe(),
          "one directory per extension, then a hundred buckets, then <id>.blob");
      row(files, "held", megabytes(attachments.files().totalBytes()), "on disk");
      io.hearth.attach.BlobCache.Stats blobs = attachments.cache().stats();
      row(files, "cached", blobs.entries() + " file(s), " + megabytes(blobs.heldBytes()),
          "of " + megabytes(blobs.budgetBytes()) + ", " + blobs.hitRate() + "% hit rate");
    }
    row(files, "rows", accounts.attachments.count(), "what the database knows about");
    groups.add(group("Uploads", io.hearth.cli.Root.CONFIG_FILE,
        "The third thing on disk, and the largest. A photograph does not belong in a database row.",
        files));

    ArrayList<Map<String, Object>> caching = new ArrayList<>();
    row(caching, "cache.ttl-seconds", config.caches.catchAll().ttlSeconds(),
        "a backstop for an event that never arrives, not the invalidation mechanism");
    row(caching, "cache.max-entries", config.caches.catchAll().maxEntries(), "");
    row(caching, "cache.enabled", config.caches.catchAll().enabled(), "");
    groups.add(group("Caching", file,
        "Named caches inherit from the catch-all and change only what they name: `content`,"
            + " `rendered`, `templates`, `board-feed`, `board-threads`, `feeds`.", caching));

    model.put("groups", groups);
    model.put("configFile", config.configFile.getAbsolutePath());
    model.put("serverFile", io.hearth.cli.Root.CONFIG_FILE);
    model.put("schemaVersion", io.hearth.store.Schema.VERSION);
    model.put("version", io.hearth.Server.VERSION);
  }

  private static String disabledList(DomainConfig config) {
    ArrayList<String> off = new ArrayList<>();
    for (io.hearth.vhost.Surface surface : io.hearth.vhost.Surface.values()) {
      if (!config.has(surface)) {
        off.add(surface.name());
      }
    }
    return off.isEmpty() ? "nothing -- everything is on" : String.join(", ", off);
  }

  private static Map<String, Object> group(String title, String file, String about,
                                           List<Map<String, Object>> rows) {
    LinkedHashMap<String, Object> group = new LinkedHashMap<>();
    group.put("title", title);
    group.put("file", file);
    group.put("about", about);
    group.put("rows", rows);
    return group;
  }

  /** one setting: what it is called in the file, what it is set to, and what it does */
  private static void row(List<Map<String, Object>> rows, String key, Object value, String hint) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("key", key);
    String shown = value == null ? "" : String.valueOf(value);
    row.put("value", shown.isEmpty() ? "not set" : shown);
    row.put("unset", shown.isEmpty());
    row.put("on", Boolean.TRUE.equals(value));
    row.put("off", Boolean.FALSE.equals(value));
    row.put("bool", value instanceof Boolean);
    row.put("hint", hint == null ? "" : hint);
    rows.add(row);
  }

  /** what an admin action did, or why it did not, and where to land afterwards */
  private record Outcome(String done, String problem, Function<DomainConfig, String> goTo) {
    static Outcome done(String message) {
      return new Outcome(message, null, null);
    }

    static Outcome done(String message, Function<DomainConfig, String> goTo) {
      return new Outcome(message, null, goTo);
    }

    static Outcome refused(String message) {
      return new Outcome(null, message, null);
    }
  }
}
