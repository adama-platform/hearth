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
import io.hearth.content.ContentVersions;
import io.hearth.content.TextPatch;
import io.hearth.mail.Mailer;
import io.hearth.content.TemplateField;
import io.hearth.content.TemplateRecord;
import io.hearth.events.EventBus;
import io.hearth.events.MutationEvent;
import io.hearth.people.ProfileRecord;
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
  private final Flash flash;
  /** what the box is doing; sampled on the notifier's pass, never on a request */
  private final io.hearth.analytics.Machine machine = new io.hearth.analytics.Machine();

  public io.hearth.analytics.Machine machine() {
    return machine;
  }
  /** where the bytes live and what is cached; the screen reports both */
  private io.hearth.attach.AttachmentRoutes attachments;
  private final Verbose verbose;

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
                     io.hearth.mcp.AiLog aiLog, Mailer mailer,
                     io.hearth.common.ServerConfig settings, Verbose verbose) {
    this.settings = settings == null ? io.hearth.common.ServerConfig.defaults() : settings;
    this.mailer = mailer;
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
        case content -> actOnContent(accounts, form, me);
      case attachments -> actOnAttachment(config, accounts, form, me);
      case bundles -> actOnContent(accounts, form, me);
      case directories -> actOnDirectory(accounts, form, me);
      case unused -> actOnUnused(config, accounts, form, me);
        case templates -> actOnTemplate(accounts, form, me);
        case configuration -> actOnConfiguration(config, accounts, form, me);
        case setup -> actOnSetup(config, accounts, form, me);
        case appearance -> actOnAppearance(accounts, form, me);
        case legal -> actOnLegal(accounts, form, me);
        case messages -> actOnMessage(config, accounts, form, me);
        case ai -> actOnConnector(accounts, form, me);
        case roles -> actOnRole(accounts, form, me);
        case cleanup -> actOnCleanup(accounts, form, me);
        case tables -> actOnTable(config, accounts, form, me);
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
              "/", "test", target.id());
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
        int adopted = 0;
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
   * What each button on the content screens actually requires.
   *
   * Invariant 86 again, and this section is the worst case of it: `/admin/content` opens for
   * `content_read` -- "See pages and their history" -- which is the mildest thing anybody on that
   * screen can hold, and every button on it posts to the section's own path. `save` and `suggest`
   * asked for what they needed. **`delete` and `restore` asked for nothing**, so a member given
   * read-only access to the content section could remove any page on the site, or overwrite one
   * with any earlier version of it.
   *
   * An action nobody listed requires `everything`, so a new button fails closed.
   */
  private static Permission neededForContent(String action) {
    return switch (action) {
      // a restore is a save (invariant 58) and a delete is the end of one, so both are writing
      case "save", "delete", "restore", "import" -> Permission.content_write;
      default -> Permission.everything;
    };
  }

  private Outcome actOnContent(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    String action = String.valueOf(form.get("action"));
    if (!accounts.access.can(me, neededForContent(action))) {
      return Outcome.refused("You are not able to do that to a page.");
    }
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
    if (!action.equals("save")) {
      return Outcome.refused("That is not something this page can do.");
    }
    String uri = form.get("uri");
    if (uri == null || !uri.startsWith("/") || uri.length() > 512) {
      return Outcome.refused("A page needs a uri that starts with '/'.");
    }
    ContentRecord.Kind kind = ContentRecord.Kind.of(form.get("kind"));
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
    if (wantsPublished != publishedNow
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
          new String[]{HttpHeaderNames.SET_COOKIE.toString(),
              Cookies.csrf(accounts.security, csrf)});
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
            new String[]{HttpHeaderNames.SET_COOKIE.toString(),
                Cookies.csrf(accounts.security, csrf)});
        return;
      }
      case changes -> {
        // the same modal, showing what changed rather than what it became
        recorder.status(200);
        Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
            versionChanges(accounts, target.id()),
            new String[]{HttpHeaderNames.SET_COOKIE.toString(),
                Cookies.csrf(accounts.security, csrf)});
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
      case content -> contentPanel(model, accounts, config, req);
      case templates -> templatesPanel(model, accounts, config);
      case ai -> aiPanel(model, req);
      case roles -> rolesPanel(model, accounts, config);
      case attachments -> attachmentsPanel(model, accounts, config, req);
      case caching -> cachingPanel(model, accounts);
      case logs -> logsPanel(model, config, req);
      default -> {
      }
    }
    return templates.render(AdminView.panelTemplate(section), model);
  }


  private static int count(Map<String, Integer> counts, String state) {
    Integer found = counts.get(state);
    return found == null ? 0 : found;
  }





  /** the top bar, the sidebar, and what every admin page needs */
  private Map<String, Object> shell(DomainConfig config, Accounts accounts, UserRecord me,
                                    AdminView.Section section, String csrf) throws SQLException {
    Map<String, Object> model = new HashMap<>();
    Chrome.admin(model, accounts);
    // the bell belongs on every page somebody works from, and the admin section is where a
    // moderator spends their evening
    model.put("live", true);
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
        model.put("canImport", accounts.access.can(me, Permission.content_publish));
        model.put("pages", accounts.site.store().contentCount());
        model.put("templateCount", accounts.site.store().templateCount());
      }
      case templates -> model.put("newUrl", AdminView.Section.templates.path(config) + "/new");
      case navigation -> navigation(model, accounts, config);
      case configuration -> configuration(model, config, accounts);
      case setup -> setupWizard(model, config, accounts, req);
      case appearance -> appearance(model, accounts);
      case legal -> legal(model, config, accounts);
      case messages -> messages(model, config, accounts);
      case roles -> model.put("newUrl", AdminView.Section.roles.path(config) + "/new");
      case settings -> settings(model, config, accounts);
      case ai -> ai(model, accounts, config, req);
      case events -> {
        model.put("emitted", events.emitted());
        model.put("capacity", events.capacity());
      }
      case machine -> machine(model, config);
      case analytics -> analytics(model, config);
      case caching -> model.put("capacity", accessLog.capacity());
      case cleanup -> cleanup(model, accounts);
      case tables -> tablesSection(model, accounts, config, req);
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
      row.put("events", person.signupEvents());
      row.put("signals", orEmpty(person.signupSignals()));
      row.put("created", stamp(person.createdAt()));
      row.put("reviewUrl", AdminView.Section.people.path(config) + "/review/" + person.id());
      rows.add(row);
    }
    model.put("people", rows);
    model.put("anyPeople", !rows.isEmpty());
    model.put("count", rows.size());
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




  /** every table this community has, with the functions it gives a page; `[]` when there are none */
  private static String tablesJson(Accounts accounts) {
    ArrayList<Map<String, Object>> tables = new ArrayList<>();
    if (accounts.tables != null) {
      for (io.hearth.tables.UserTable table : accounts.tables.all()) {
        LinkedHashMap<String, Object> one = new LinkedHashMap<>();
        one.put("table", table.name());
        one.put("functions", table.functions());
        ArrayList<String> shape = new ArrayList<>();
        shape.add("id");
        for (io.hearth.tables.UserField field : table.fields()) {
          shape.add(field.name());
        }
        one.put("shape", String.join(", ", shape));
        tables.add(one);
      }
    }
    return io.hearth.tables.UserTables.toJson(tables);
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
      // How slow is this page, worst case, over the last fifty times it was built?
      //
      // Every kind, not only the dynamic one: the number is unreadable on its own and obvious
      // beside its neighbours. A page nothing has asked for since the last restart has no timing
      // at all, which the listing shows as a dash rather than as a zero.
      io.hearth.content.RenderTimes.Stat stat = accounts.site.times().of(page.uri());
      row.put("timed", stat != null);
      row.put("p99", stat == null ? "" : stat.p99Shown());
      row.put("p50", stat == null ? "" : stat.p50Shown());
      row.put("samples", stat == null ? 0 : stat.samples());
      row.put("slow", stat != null && stat.p99Millis() >= 50);
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

  /**
   * The tables this community invented, and the editor for one of them.
   *
   * `/admin/tables` lists them; `/admin/tables/edit/<name>` is one table's own page and
   * `/admin/tables/new` is a blank one -- identity in the path, because a listing is not a form.
   *
   * The screen leads with the JavaScript each table produces rather than with its columns, because
   * that is the thing somebody came here to find out. A table is a means; the four functions are
   * the feature.
   */
  private void tablesSection(Map<String, Object> model, Accounts accounts, DomainConfig config,
                             FullHttpRequest req) {
    // the listing only; `/admin/tables/new` and `/admin/tables/edit/<name>` are parsed as a
    // create and an edit by AdminView and land in formModel, like every other section's editor
    String prefix = AdminView.Section.tables.path(config);
    model.put("newUrl", prefix + "/new");
    model.put("backUrl", prefix);
    if (accounts.tables == null) {
      model.put("unavailable", true);
      model.put("tables", new ArrayList<>());
      model.put("any", false);
      return;
    }
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (io.hearth.tables.UserTable table : accounts.tables.all()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("name", table.name());
      row.put("fields", table.fields().size());
      row.put("indexes", String.join(", ", table.indexes()));
      row.put("anyIndexes", !table.indexes().isEmpty());
      long count;
      try {
        count = accounts.tables.count(table.name());
      } catch (SQLException ex) {
        count = -1;
      }
      row.put("rows", count < 0 ? "?" : String.valueOf(count));
      row.put("functions", table.functions());
      row.put("editUrl", prefix + "/edit/" + table.name());
      rows.add(row);
    }
    model.put("tables", rows);
    model.put("any", !rows.isEmpty());
    model.put("count", rows.size());
    model.put("file", accounts.tables.file().getName() + ".mv.db");
  }

  /** the editor for one table, or a blank one */
  private void tableForm(Map<String, Object> model, Accounts accounts,
                         io.hearth.tables.UserTable table) {
    model.put("editing", table != null);
    model.put("heading", table == null ? "A new table" : "Table: " + table.name());
    model.put("form_name", table == null ? "" : table.name());
    ArrayList<Map<String, Object>> fields = new ArrayList<>();
    if (table != null) {
      for (io.hearth.tables.UserField field : table.fields()) {
        LinkedHashMap<String, Object> one = new LinkedHashMap<>();
        one.put("name", field.name());
        one.put("type", field.type().name());
        one.put("indexed", table.hasIndex(field.name()));
        one.put("types", typeOptions(field.type()));
        fields.add(one);
      }
    }
    model.put("fields", fields);
    model.put("anyFields", !fields.isEmpty());
    model.put("blankTypes", typeOptions(io.hearth.tables.UserField.Type.text));
    model.put("functions", table == null ? new ArrayList<>() : table.functions());
    model.put("rowCount", rowCountOf(accounts, table));
  }

  private static String rowCountOf(Accounts accounts, io.hearth.tables.UserTable table) {
    if (table == null || accounts.tables == null) {
      return "0";
    }
    try {
      return String.valueOf(accounts.tables.count(table.name()));
    } catch (SQLException ex) {
      return "?";
    }
  }

  private static List<Map<String, Object>> typeOptions(io.hearth.tables.UserField.Type selected) {
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    for (io.hearth.tables.UserField.Type type : io.hearth.tables.UserField.Type.values()) {
      LinkedHashMap<String, Object> one = new LinkedHashMap<>();
      one.put("value", type.name());
      one.put("label", type.label);
      one.put("selected", type == selected);
      options.add(one);
    }
    return options;
  }

  /**
   * Create, change or drop one table.
   *
   * The whole field list arrives on every save, which makes this a replace rather than a merge --
   * correct here and the opposite of the rule for a page's declared values, because the form always
   * shows every field. A field missing from the submission is a field somebody removed, and
   * `alter` says so in the confirmation rather than doing it quietly.
   */
  private Outcome actOnTable(DomainConfig config, Accounts accounts, Forms form, UserRecord me)
      throws SQLException {
    if (accounts.tables == null) {
      return Outcome.refused("This community's data file could not be opened.");
    }
    String action = String.valueOf(form.get("action"));
    String name = io.hearth.tables.UserTable.normalize(form.get("name"));
    try {
      if (action.equals("drop")) {
        long held = accounts.tables.count(name);
        accounts.tables.drop(name, me.id());
        verbose.detail("admin: " + me.email() + " dropped the table " + name);
        return Outcome.done(name + " is gone, with " + held + " row(s) in it.",
            site -> AdminView.Section.tables.path(site));
      }
      if (!action.equals("save")) {
        return Outcome.refused("That is not something this page can do.");
      }
      Outcome oversized = oversized(form);
      if (oversized != null) {
        return oversized;
      }
      ArrayList<io.hearth.tables.UserField> fields = new ArrayList<>();
      ArrayList<String> indexes = new ArrayList<>();
      for (int k = 0; k < io.hearth.tables.UserTable.MAX_FIELDS; k++) {
        String fieldName = io.hearth.tables.UserTable.normalize(form.get("f_name_" + k));
        if (fieldName.isEmpty()) {
          continue;
        }
        io.hearth.tables.UserField.Type type =
            io.hearth.tables.UserField.Type.of(form.get("f_type_" + k));
        if (type == null) {
          return Outcome.refused("'" + fieldName + "' has no type this server knows.");
        }
        fields.add(new io.hearth.tables.UserField(fieldName, type, false));
        if (form.get("f_index_" + k) != null) {
          indexes.add(fieldName);
        }
      }
      io.hearth.tables.UserTable wanted =
          new io.hearth.tables.UserTable(name, fields, indexes);
      boolean exists = accounts.tables.byName(name) != null;
      if (exists) {
        List<String> done = accounts.tables.alter(wanted, me.id());
        return Outcome.done(done.isEmpty()
                ? name + " is unchanged."
                : name + ": " + String.join(", ", done) + ".",
            site -> AdminView.Section.tables.path(site) + "/edit/" + name);
      }
      accounts.tables.create(wanted, me.id());
      verbose.detail("admin: " + me.email() + " created the table " + name);
      return Outcome.done(name + " is ready. Its functions are on this page.",
          site -> AdminView.Section.tables.path(site) + "/edit/" + name);
    } catch (io.hearth.tables.UserTables.Refused refused) {
      return Outcome.refused(refused.getMessage());
    }
  }

  /**
   * What is still in the database that the software has stopped using.
   *
   * Read every time the screen is opened rather than remembered, because the answer changes when
   * somebody presses a button on it and a stale list here is a list with a delete button beside it.
   */
  private void cleanup(Map<String, Object> model, Accounts accounts) throws SQLException {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    long total = 0;
    try (java.sql.Connection connection = accounts.store.connection()) {
      for (io.hearth.store.Leftovers.Table table : io.hearth.store.Leftovers.find(connection)) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("name", table.name());
        row.put("rows", table.rows() < 0 ? "unknown" : String.valueOf(table.rows()));
        row.put("empty", table.empty());
        rows.add(row);
        total += Math.max(0, table.rows());
      }
    }
    model.put("tables", rows);
    model.put("any", !rows.isEmpty());
    model.put("count", rows.size());
    model.put("rowsHeld", total);
    model.put("database", accounts.store.databaseDomain);
  }

  /**
   * Drop one leftover table.
   *
   * One at a time, named in the form, with no "drop them all" button anywhere. This is the only
   * irreversible thing in the admin section and the friction is the feature: somebody removing four
   * tables should have to read four table names and press four buttons, because the fourth one
   * might be the one holding something they wanted.
   */
  private Outcome actOnCleanup(Accounts accounts, Forms form, UserRecord me) throws SQLException {
    if (!String.valueOf(form.get("action")).equals("drop")) {
      return Outcome.refused("That is not something this page can do.");
    }
    String name = form.get("table");
    try (java.sql.Connection connection = accounts.store.connection()) {
      io.hearth.store.Leftovers.drop(connection, name);
    } catch (SQLException ex) {
      return Outcome.refused(String.valueOf(ex.getMessage()));
    }
    verbose.detail("admin: " + me.email() + " dropped the leftover table " + name);
    return Outcome.done(name + " is gone. That cannot be undone from here -- the copy in your"
        + " backup is the only one left.");
  }

  private void cachingPanel(Map<String, Object> model, Accounts accounts) throws SQLException {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    ArrayList<TtlCache.Stats> all = new ArrayList<>(accounts.site.cacheStats());
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
  }

  /**
   * Is this unchanged line close enough to a change to be worth printing?
   *
   * A diff of a long page is mostly lines nobody changed, and printing all of them buries the three
   * that matter. Three either side is enough to see what a change is sitting in.
   */
  private static boolean nearAChange(java.util.List<TextPatch.Change> changes, int at) {
    int from = Math.max(0, at - 3);
    int to = Math.min(changes.size() - 1, at + 3);
    for (int k = from; k <= to; k++) {
      if (changes.get(k).kind() != TextPatch.Kind.same) {
        return true;
      }
    }
    return false;
  }

  /**
   * The people listing's state filter.
   *
   * Four states a person can be in, asked as a query on the listing rather than as four screens.
   * `admin` is the odd one: it is not a column but a question for `Access`, because the two
   * spellings of admin -- named in the config, or granted a role -- are one state to whoever is
   * looking at the list.
   */
  private static boolean matchesState(String state, UserRecord person, boolean isAdmin) {
    return switch (state) {
      case "waiting" -> !person.isApproved() && !person.disabled();
      case "approved" -> person.isApproved() && !person.disabled();
      case "disabled" -> person.disabled();
      case "admin" -> isAdmin;
      default -> true;
    };
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









  // ---- suggested edits ---------------------------------------------------------------------




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



  // ---- the calendar ----------------------------------------------------------------------------








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
   * One real message, down the same path everybody else's goes down.
   *
   * The switch is exhaustive over {@link io.hearth.mail.SystemTemplate} on purpose: there is no
   * default arm, so adding a flow stops compiling here rather than shipping a screen with a button
   * that refuses. It used to cover thirteen flows and the eight that named the board, the digest,
   * the invitations and the calendar went with them -- which is how the whole method came out and
   * left the button behind it.
   *
   * The code is a fixed fake. A real one would be a credential in an inbox and in a log, and the
   * point of this is the shape of the message rather than the digits in it.
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
    values.put("link", "https://" + config.domain + config.urls.resetPassword);
    // Every parameter any surviving flow declares has a value above. The ten that used to be here
    // -- the inviter, the excerpt, the title, the place -- belonged to the board, the invitations
    // and the calendar, and a preview cannot fill a hole no flow can ask for any more.
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
      case tables -> tableForm(model, accounts,
          accounts.tables == null ? null : accounts.tables.byName(id));
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
        // the box is only offered to somebody who could act on it: a checkbox that refuses the
        // save is worse than no checkbox, because it looks like a decision they are allowed to make
        model.put("canPublish", accounts.access.can(me, Permission.content_publish));
        if (page != null) {
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
        // what a program on this page could call, for the reference the editor shows. Rendered by
        // the server because only the server knows which tables exist right now -- and carried in a
        // script tag rather than interpolated into the script, per invariant 61.
        model.put("tablesJson", tablesJson(accounts));
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
    // A version is the whole page, published flag included (invariant 43), so restoring one moves
    // that flag -- and this was a way straight past `content_publish`. The save path is careful
    // about exactly this transition and checks it *on the change*; going around it meant somebody
    // who may write but not publish could take a page down, restore the version before it, and have
    // it live again. It works the other way too: restoring an unpublished version takes a live page
    // down.
    //
    // The whole restore is refused rather than the words being restored without the flag. A partial
    // restore that looked like a whole one is the kind of thing nobody checks afterwards.
    if (old.published() != current.published()
        && !accounts.access.can(me, Permission.content_publish)) {
      return Outcome.refused(old.published()
          ? "That version was published and this page is not, so restoring it would put the page"
              + " live -- which you are not able to do. Ask somebody who can publish."
          : "That version was not published and this page is, so restoring it would take the page"
              + " down -- which you are not able to do.");
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
  }

  // ---- option lists ------------------------------------------------------------------------------------

  private static void option(String value, String label, boolean selected, List<Map<String, Object>> into) {
    LinkedHashMap<String, Object> item = new LinkedHashMap<>();
    item.put("value", value);
    item.put("label", label);
    item.put("selected", selected);
    into.add(item);
  }


  private static List<Map<String, Object>> publishedOptions(String current) {
    String value = orEmpty(current);
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    option("", "published and drafts", value.isEmpty(), options);
    option("yes", "published only", value.equals("yes"), options);
    option("no", "drafts only", value.equals("no"), options);
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
      item.put("selected", page != null && page.kind() == kind);
      options.add(item);
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
    row(urls, "urls.self", config.urls.self, "");
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
    groups.add(group("The parts of the product", file,
        "Each of these also disappears entirely if its name is in `disabled`.", parts));

    ArrayList<Map<String, Object>> robots = new ArrayList<>();
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
            + " `rendered`, `templates`.", caching));

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
