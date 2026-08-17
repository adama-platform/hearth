package io.hearth.web;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.content.Markdown;
import io.hearth.people.AnswerSheet;
import io.hearth.people.ProfileRecord;
import io.hearth.people.Question;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A person's own page: who they are, and what they have answered.
 *
 * Two tabs rather than two pages, because they are two views of the same thing -- "here is what the
 * community knows about me". Each tab is still its own URL, so a link to somebody's unanswered
 * questions is a link, not an instruction to click something after arriving.
 *
 * This exists for the admin as much as for the member. Approval is a judgement call, and a judgement
 * call needs something to judge: the browser checks on the register form filter bots, and a profile
 * plus a few answered questions is what filters strangers. The survey is the same mechanism pointed
 * at engagement instead -- an admin asks the community something, and everybody has a small number
 * on their page until they say.
 */
public class SelfRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(SelfRoutes.class);

  /** the tabs, in order */
  public enum Tab {
    profile("Profile"),
    inbox("Inbox"),
    notifications("Notifications"),
    invite("Invite"),
    data("Your data");

    public final String label;

    Tab(String label) {
      this.label = label;
    }

    static Tab of(String raw) {
      for (Tab tab : values()) {
        if (tab.name().equals(raw)) {
          return tab;
        }
      }
      return profile;
    }
  }

  private final Templates templates;
  private final io.hearth.people.Invitations invitations;
  /** so that deleting an account also clears what the request log remembers of them */
  private final io.hearth.analytics.AccessLog accessLog;
  /** where the private address goes to be turned into a point; set after boot, may stay null */
  private io.hearth.places.Geocodes geocodes;
  private final Verbose verbose;

  public void knowsAbout(io.hearth.places.Geocodes geocodes) {
    this.geocodes = geocodes;
  }

  public SelfRoutes(Templates templates, io.hearth.mail.Mailer mailer,
                    io.hearth.analytics.AccessLog accessLog, Verbose verbose) {
    this.templates = templates;
    this.invitations = new io.hearth.people.Invitations(mailer);
    this.accessLog = accessLog;
    this.verbose = verbose;
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    try {
      SessionRecord session = AccountRoutes.currentSession(accounts, req);
      UserRecord me = session == null ? null : accounts.users.byId(session.userId());
      if (me == null) {
        // signed out: send them to sign in, carrying where they were going, so the sign-in they
        // did not ask for costs them the form and not the page they wanted
        recorder.status(303);
        Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
            new String[]{HttpHeaderNames.LOCATION.toString(),
                Landing.carry(config.urls.login, Landing.here(req))});
        return;
      }
      if (HttpMethod.POST.equals(req.method())) {
        act(config, accounts, ctx, req, me, recorder);
        return;
      }
      // their own copy, built when they ask and never stored. A GET because it is a download, and
      // theirs alone because the only id it takes is the session's.
      if ("export".equals(Forms.query(req.uri(), "download"))) {
        byte[] json = io.hearth.people.DataExport.of(accounts, me, config.name, config.domain, true);
        verbose.detail("self: " + me.email() + " downloaded their data (" + json.length + " bytes)");
        recorder.status(200);
        Responses.send(ctx, req, HttpResponseStatus.OK, "application/json; charset=utf-8", json,
            new String[]{"Content-Disposition",
                "attachment; filename=\"" + config.domain + "-my-data.json\""});
        return;
      }
      // the questions were a tab here for months, so the address is in bookmarks and in messages
      // people sent each other. It costs one line to send them where the survey went.
      if ("questions".equals(Forms.query(req.uri(), "tab"))) {
        recorder.status(303);
        Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
            new String[]{HttpHeaderNames.LOCATION.toString(), config.urls.survey});
        return;
      }
      show(config, accounts, ctx, req, me, Tab.of(Forms.query(req.uri(), "tab")), null, recorder);
    } catch (SQLException ex) {
      LOG.error("self-route-failed", ex);
      recorder.status(500);
      Map<String, Object> model = new HashMap<>();
      io.hearth.web.Chrome.site(model, accounts);
      model.put("title", "Something went wrong");
      model.put("community", config.name);
      model.put("nav", List.of());
      model.put("heading", "Something went wrong");
      model.put("message", "That did not work, and it is our fault rather than yours.");
      Responses.sendHtml(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, templates.render("message", model));
    }
  }

  private void act(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder) throws SQLException {
    // somebody writing about themselves, or answering an open question, is writing prose
    Forms form = Forms.of(req, Forms.MAX_CONTENT_BYTES);
    if (form.bodyTooLarge()) {
      show(config, accounts, ctx, req, me, Tab.profile,
          "That was too much to save in one go. Nothing was saved.", recorder);
      return;
    }
    if (!Cookies.csrfMatches(form.get(Cookies.CSRF_FIELD), Forms.cookie(req, Cookies.CSRF_COOKIE))) {
      show(config, accounts, ctx, req, me, Tab.profile, "That form expired. Please try again.", recorder);
      return;
    }

    String action = String.valueOf(form.get("action"));
    Tab tab;
    String done;
    if (action.equals("profile")) {
      String displayName = form.raw("display_name");
      String headline = form.raw("headline");
      String about = form.text("about");
      String location = form.get("location");
      String links = form.text("links");
      // after reading, before writing: the oversize list fills in as fields are read, and an empty
      // string written over somebody's paragraph is the bug this whole change exists for
      if (form.tooLong() != null) {
        show(config, accounts, ctx, req, me, Tab.profile,
            "That is too much text for '" + form.tooLong() + "'. Nothing was saved.", recorder);
        return;
      }
      // A name is the one required field, and it is required here rather than only in the browser:
      // an invitation, a comment and the member list all print it, and the alternative to a name is
      // an email address, which is not a person and is not something to publish either.
      if (displayName == null || displayName.isBlank()) {
        show(config, accounts, ctx, req, me, Tab.profile,
            "A name is needed -- it is what everybody else sees. Nothing was saved.", recorder);
        return;
      }
      String was = accounts.people.profileOf(me.id()).location();
      accounts.people.saveProfile(me.id(), displayName, headline, about, location, links);
      // A changed town needs placing again, but only for somebody who has not given a precise
      // address -- theirs is the better answer and re-reading the town would replace a doorstep
      // with a town centre.
      if (!java.util.Objects.equals(was, accounts.people.profileOf(me.id()).location())
          && !accounts.people.homeOf(me.id()).hasAddress()) {
        placeMe(accounts, me.id());
      }
      tab = Tab.profile;
      done = "Profile saved.";
      verbose.detail("self: " + me.email() + " updated their profile");
    } else if (action.equals("address")) {
      // Where somebody actually lives, which is not the location line above it.
      //
      // Written to its own columns through its own method, and nothing that renders a profile ever
      // reads them. The only thing that ever leaves is a distance, counted into a bucket with
      // everybody else's -- and clearing the box clears the point with it, because a coordinate
      // beside a deleted address is exactly the thing somebody was asking to be rid of.
      String address = form.raw("address");
      if (form.tooLong() != null) {
        show(config, accounts, ctx, req, me, Tab.profile,
            "That is too long for an address. Nothing was saved.", recorder);
        return;
      }
      accounts.people.saveAddress(me.id(), address == null ? "" : address);
      placeMe(accounts, me.id());
      tab = Tab.profile;
      done = address == null || address.isBlank()
          ? "Address removed. You are no longer counted in travel distances."
          : "Saved. It is being looked up, which takes a minute or two.";
      verbose.detail("self: " + me.email() + " updated their private address");
    } else if (action.equals("disconnect")) {
      // whoever connected it can take it away. The id is checked against their own agents rather
      // than trusted, because a session id in a form is a number somebody can change.
      long agentId = 0;
      try {
        agentId = Long.parseLong(String.valueOf(form.get("agent")).trim());
      } catch (NumberFormatException ex) {
        agentId = 0;
      }
      for (io.hearth.auth.SessionRecord agent : accounts.sessions.agentsFor(me.id())) {
        if (agent.id() == agentId) {
          accounts.sessions.deleteById(agent.id());
          verbose.detail("self: " + me.email() + " disconnected " + agent.agent());
          break;
        }
      }
      tab = Tab.profile;
      done = "Disconnected. Anything it was holding stops working now.";
    } else if (action.equals("answers")) {
      // the survey has a page of its own, and the merge rule lives in one place with it. A form
      // posting here is an old page in somebody's tab, so it is answered rather than refused.
      recorder.status(303);
      Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
          new String[]{HttpHeaderNames.LOCATION.toString(), config.urls.survey});
      return;
    } else if (action.equals("invite")) {
      String email = form.get("email");
      // No line from whoever is inviting.
      //
      // Invariant 114 already made this decision for the admin screen and it applies here twice
      // over: a per-invitation note is a member composing a sentence under pressure, and a
      // community whose invitations all read differently is one that looks like it is run by
      // nobody. There is one invitation, written once, and this is a name and an address.
      String note = "";
      if (form.tooLong() != null) {
        show(config, accounts, ctx, req, me, Tab.invite,
            "That is longer than this page can store: " + form.tooLong() + ".", recorder);
        return;
      }
      if (!mayInvite(config, accounts, me)) {
        show(config, accounts, ctx, req, me, Tab.invite,
            "Inviting is not open to members here.", recorder);
        return;
      }
      // the daily limit is enforced for a member and not for an admin: one enthusiastic person
      // with a contacts export can burn a sending domain for everybody
      io.hearth.people.Invitations.Result result = invitations.invite(config, accounts, email,
          note, me.id(), me.email(), true,
          !accounts.access.can(me, io.hearth.auth.Permission.invites_bulk));
      tab = Tab.invite;
      if (!result.ok()) {
        show(config, accounts, ctx, req, me, Tab.invite,
            result.email() + ": " + result.detail() + ".", recorder);
        return;
      }
      done = "Invitation sent to " + result.email() + ".";
      verbose.detail("self: " + me.email() + " invited " + result.email());
    } else if (action.equals("notifications")) {
      // read before the ceiling check, because the check is a list that fills in as fields are read
      String replyMode = form.get("reply_mode");
      String responseMode = form.get("response_mode");
      String phone = form.get("phone");
      if (form.tooLong() != null) {
        show(config, accounts, ctx, req, me, Tab.notifications,
            "That is longer than this page can store: " + form.tooLong() + ".", recorder);
        return;
      }
      io.hearth.board.NotifyPrefs.Mode reply = io.hearth.board.NotifyPrefs.Mode.of(replyMode,
          io.hearth.board.NotifyPrefs.DEFAULTS.replyMode());
      io.hearth.board.NotifyPrefs.Mode response = io.hearth.board.NotifyPrefs.Mode.of(responseMode,
          io.hearth.board.NotifyPrefs.DEFAULTS.responseMode());
      accounts.notifyPrefs.save(me.id(), reply, response, form.get("email") != null,
          form.get("sms") != null, phone);
      tab = Tab.notifications;
      done = "Saved. " + describe(reply, response) + ".";
      verbose.detail("self: " + me.email() + " set notifications to " + reply + "/" + response);
    } else if (action.equals("leave")) {
      // Somebody deleting themselves. The typed confirmation is not security theatre -- it is the
      // one button here that cannot be undone by anybody, including an administrator, and a
      // misclick on a phone should not end somebody's membership.
      if (!"delete".equalsIgnoreCase(String.valueOf(form.get("confirm")).trim())) {
        show(config, accounts, ctx, req, me, Tab.data,
            "Type 'delete' in the box to confirm, and nothing will happen until you do.", recorder);
        return;
      }
      if (accounts.access.isBootstrapAdmin(me.email())) {
        show(config, accounts, ctx, req, me, Tab.data,
            "Your address is named as an administrator in this community's configuration file, so"
                + " this cannot delete you -- the account would come back at the next restart."
                + " Take the address out of the file first.", recorder);
        return;
      }
      io.hearth.people.Erasure.Report report =
          io.hearth.people.Erasure.erase(accounts, accessLog, me, me.id(), false);
      verbose.say("self: " + report.email() + " deleted their own account (" + report.describe()
          + ")");
      recorder.status(303);
      Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY, new String[]{
          HttpHeaderNames.LOCATION.toString(), "/",
          HttpHeaderNames.SET_COOKIE.toString(), Cookies.clearSession(accounts.security)});
      return;
    } else {
      show(config, accounts, ctx, req, me, Tab.profile, "That is not something this page can do.", recorder);
      return;
    }
    recorder.status(303);
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY, new String[]{
        HttpHeaderNames.LOCATION.toString(), config.urls.self + "?tab=" + tab.name()
            + "&done=" + URLEncoder.encode(done, StandardCharsets.UTF_8)});
  }

  private void show(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx, FullHttpRequest req,
                    UserRecord me, Tab active, String problem, WebHandler.Recorder recorder) throws SQLException {
    String csrf = Cookies.stableToken(req);
    ProfileRecord profile = accounts.people.profileOf(me.id());
    AnswerSheet answers = accounts.people.answersOf(me.id());
    List<Question> questions = accounts.people.publishedQuestions();

    Map<String, Object> model = new HashMap<>();
    io.hearth.web.Chrome.site(model, config, accounts, req);
    model.put("title", "Your profile - " + config.name);
    model.put("community", config.name);
    model.put("csrf", csrf);
    model.put("problem", problem);
    model.put("done", Forms.query(req.uri(), "done"));
    model.put("action", config.urls.self);
    model.put("email", me.email());
    model.put("approved", accounts.access.isApproved(me));

    // What somebody is trusted with, on their own page, in the words the role editor uses. A
    // permission you hold and cannot see is one you will never use, and "why can Ana do that and I
    // cannot" is the question this answers before anybody has to ask an admin.
    java.util.Set<io.hearth.auth.Permission> allowed = accounts.access.permissionsOf(me);
    ArrayList<Map<String, Object>> roleRows = new ArrayList<>();
    for (String name : accounts.roles.of(me.id())) {
      io.hearth.auth.RoleDefs.Def def = accounts.roleDefs.byName(name);
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("label", def == null ? name : def.labelOr());
      row.put("description", def == null ? "" : def.description());
      row.put("color", def == null ? "" : def.color());
      roleRows.add(row);
    }
    if (accounts.access.isBootstrapAdmin(me.email())) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("label", "Administrator");
      row.put("description", "Named in this community's config file, so this cannot be taken away"
          + " from inside the site.");
      row.put("color", "red");
      roleRows.add(row);
    }
    model.put("roles", roleRows);
    model.put("anyRoles", !roleRows.isEmpty());

    ArrayList<Map<String, Object>> can = new ArrayList<>();
    boolean everything = allowed.contains(io.hearth.auth.Permission.everything);
    for (io.hearth.auth.Permission permission : io.hearth.auth.Permission.values()) {
      if (permission == io.hearth.auth.Permission.everything
          || permission == io.hearth.auth.Permission.admin_enter) {
        continue;
      }
      if (everything || allowed.contains(permission)) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("group", permission.group);
        row.put("label", permission.label);
        can.add(row);
      }
    }
    model.put("can", can);
    model.put("anyCan", !can.isEmpty());
    model.put("adminUrl", config.urls.admin);
    // a person's own credentials belong beside their other ones; the page itself is the one that
    // decides whether they may have any
    if (config.has(io.hearth.vhost.Surface.api)) {
      model.put("apiUrl", io.hearth.api.ApiConfig.PATH);
    }
    model.put("canEnterAdmin", accounts.access.canEnterAdmin(me));
    model.put("nav", Navigation.forRequest(config, accounts, req));
    model.put("tabs", tabs(config, active, accounts.survey.remainingFor(me.id()),
        accounts.inbox.unreadCount(me.id())));
    model.put("onProfile", active == Tab.profile);
    model.put("onInbox", active == Tab.inbox);
    model.put("onNotifications", active == Tab.notifications);
    model.put("onInvite", active == Tab.invite);
    model.put("onData", active == Tab.data);
    model.put("exportUrl", config.urls.self + "?tab=data&download=export");
    model.put("privacyUrl", "/legal/privacy-policy");
    model.put("isConfigAdmin", accounts.access.isBootstrapAdmin(me.email()));

    boolean mayInvite = mayInvite(config, accounts, me);
    model.put("mayInvite", mayInvite);
    model.put("inviteLimit", config.invites.memberDailyLimit);
    model.put("inviteLimited", config.invites.memberDailyLimit > 0
        && !accounts.access.can(me, io.hearth.auth.Permission.invites_bulk));
    model.put("invitedToday", accounts.invites.sentTodayBy(me.id()));
    ArrayList<Map<String, Object>> mine = new ArrayList<>();
    for (io.hearth.people.Invites.Invite invite : accounts.invites.all(200)) {
      if (invite.createdBy() == null || invite.createdBy() != me.id()) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("email", invite.email());
      row.put("stage", invite.stage());
      row.put("joined", invite.converted());
      row.put("touches", invite.touches());
      mine.add(row);
    }
    model.put("myInvites", mine);
    model.put("anyMyInvites", !mine.isEmpty());

    io.hearth.board.NotifyPrefs.Prefs prefs = accounts.notifyPrefs.forUser(me.id());
    model.put("replyModes", modeChoices(prefs.replyMode()));
    model.put("responseModes", modeChoices(prefs.responseMode()));
    model.put("notifyEmail", prefs.email());
    model.put("notifySms", prefs.sms());
    model.put("phone", prefs.phone());
    // the settings page never offers a channel nothing delivers on; saying so is more useful than
    // a checkbox that stores a preference the server cannot honour
    model.put("smsAvailable", io.hearth.sms.NoSms.INSTANCE.available());
    model.put("smsWhy", io.hearth.sms.NoSms.INSTANCE.describe());
    model.put("boardEnabled", config.has(io.hearth.vhost.Surface.board));

    // Opening the inbox is what marks it read. There is no per-notification read state, because
    // that is a lot of machinery for a distinction nobody makes.
    if (active == Tab.inbox) {
      accounts.inbox.markAllRead(me.id());
    }
    ArrayList<Map<String, Object>> notes = new ArrayList<>();
    for (io.hearth.board.Inbox.Note note : accounts.inbox.forUser(me.id(), 100)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("text", note.text());
      row.put("who", note.actorName());
      row.put("unread", note.unread());
      row.put("url", note.postId() == null ? null : config.urls.board + "/" + note.postId());
      notes.add(row);
    }
    model.put("notes", notes);
    model.put("anyNotes", !notes.isEmpty());

    model.put("display_name", profile.displayName());
    model.put("headline", profile.headline());
    model.put("about", profile.about());
    model.put("location", profile.location());
    model.put("links", profile.links());

    // The private half. Read separately from a separate record, which is the whole reason it can
    // be promised to nobody: a profile does not carry it, so no listing, export or model can.
    io.hearth.people.Home home = accounts.people.homeOf(me.id());
    model.put("address", home.address());
    model.put("homeStatus", home.status());
    model.put("placed", home.hasPoint());
    model.put("precise", home.isPrecise());
    model.put("geocoding", geocodes != null && geocodes.on());

    // The survey has a page of its own; what stays here is the one line that points at it, with
    // the number on it, because somebody looking at their own page is exactly who should be told
    // there is something outstanding.
    // The assistants this person has connected, and the button to disconnect one.
    //
    // On their own page rather than only in the admin section, because the endpoint is no longer
    // admin-only: whoever connected an assistant is who should be able to see it is still there
    // and take it away, without asking anybody. It is a session with a bit set, so revoking one is
    // the same revoke that signs a browser out.
    boolean mayConnect = config.mcp.enabled
        && accounts.access.can(me, io.hearth.auth.Permission.agent_connect);
    model.put("mayConnect", mayConnect);
    if (mayConnect) {
      ArrayList<Map<String, Object>> agents = new ArrayList<>();
      for (io.hearth.auth.SessionRecord agent : accounts.sessions.agentsFor(me.id())) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("id", agent.id());
        row.put("name", agent.agent() == null ? "an assistant" : agent.agent());
        row.put("since", java.time.Instant.ofEpochMilli(agent.createdAt()).toString());
        agents.add(row);
      }
      model.put("agents", agents);
      model.put("anyAgents", !agents.isEmpty());
    }

    model.put("surveyUrl", config.urls.survey);
    model.put("hasSurvey", config.has(io.hearth.vhost.Surface.survey) && !questions.isEmpty());
    model.put("remaining", accounts.survey.remainingFor(me.id()));
    model.put("anyRemaining", accounts.survey.remainingFor(me.id()) > 0);
    model.put("answered", answers.answered());
    model.put("total", questions.size());

    recorder.status(problem == null ? 200 : 400);
    Responses.send(ctx, req, problem == null ? HttpResponseStatus.OK : HttpResponseStatus.BAD_REQUEST,
        "text/html; charset=utf-8", templates.render("self", model),
        new String[]{HttpHeaderNames.SET_COOKIE.toString(), Cookies.csrf(accounts.security, csrf)});
  }

  /** the four modes as a radio group, with the one somebody chose already selected */
  /**
   * May this person invite anybody?
   *
   * Either the community has left it open to members, or they hold a role that says so. Two ways to
   * yes, because "everybody can invite" and "only the greeters can" are both reasonable settings
   * for a community to have, and neither should require the other to be edited.
   */
  private static boolean mayInvite(DomainConfig config, Accounts accounts, UserRecord me)
      throws SQLException {
    if (!config.invites.enabled) {
      return false;
    }
    if (accounts.access.can(me, io.hearth.auth.Permission.invites_send)) {
      return true;
    }
    return config.invites.membersMayInvite && accounts.access.isApproved(me);
  }

  private static List<Map<String, Object>> modeChoices(io.hearth.board.NotifyPrefs.Mode chosen) {
    ArrayList<Map<String, Object>> choices = new ArrayList<>();
    for (io.hearth.board.NotifyPrefs.Mode mode : io.hearth.board.NotifyPrefs.Mode.values()) {
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("value", mode.name());
      item.put("label", switch (mode) {
        case off -> "Never";
        case immediate -> "Straight away";
        case daily -> "Once a day";
        case weekly -> "Once a week";
      });
      item.put("checked", mode == chosen);
      choices.add(item);
    }
    return choices;
  }

  /** what to say after saving, in the words somebody would use */
  private static String describe(io.hearth.board.NotifyPrefs.Mode reply,
                                 io.hearth.board.NotifyPrefs.Mode response) {
    if (reply == io.hearth.board.NotifyPrefs.Mode.off
        && response == io.hearth.board.NotifyPrefs.Mode.off) {
      return "Nothing will be emailed; your inbox here still fills up";
    }
    return "Replies to you: " + response.name() + "; threads you watch: " + reply.name();
  }

  private static List<Map<String, Object>> tabs(DomainConfig config, Tab active, int remaining,
                                                int unread) {
    ArrayList<Map<String, Object>> tabs = new ArrayList<>();
    for (Tab tab : Tab.values()) {
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("label", tab.label);
      item.put("href", config.urls.self + "?tab=" + tab.name());
      item.put("active", tab == active);
      // the bubble: a small number that says there is something to do
      String bubble = null;
      if (tab == Tab.inbox && unread > 0) {
        bubble = Integer.toString(unread);
      }
      item.put("bubble", bubble);
      tabs.add(item);
    }
    return tabs;
  }

  /** flatten questions and the person's answers into what the template renders */
  static List<Map<String, Object>> questionRows(List<Question> questions, AnswerSheet answers) {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Question question : questions) {
      String answer = answers.answerTo(question.id());
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", question.id());
      row.put("field", "q" + question.id());
      row.put("prompt", question.prompt());
      row.put("help", question.help());
      row.put("required", question.required());
      row.put("answered", question.accepts(answer));
      row.put("answer", answer == null ? "" : answer);
      row.put("free", question.kind() == Question.Kind.free);
      row.put("choice", question.kind() == Question.Kind.choice);
      row.put("rating", question.kind() == Question.Kind.rating);
      ArrayList<Map<String, Object>> options = new ArrayList<>();
      for (String option : question.options()) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("value", option);
        item.put("selected", option.equals(answer));
        options.add(item);
      }
      row.put("options", options);
      ArrayList<Map<String, Object>> scale = new ArrayList<>();
      for (int value : question.scale()) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("value", value);
        item.put("selected", Integer.toString(value).equals(answer));
        scale.add(item);
      }
      row.put("scale", scale);
      rows.add(row);
    }
    return rows;
  }

  /** somebody's profile and answers, rendered for an admin deciding whether to approve them */
  public static Map<String, Object> reviewOf(Accounts accounts, UserRecord person) throws SQLException {
    ProfileRecord profile = accounts.people.profileOf(person.id());
    AnswerSheet answers = accounts.people.answersOf(person.id());
    List<Question> questions = accounts.people.publishedQuestions();
    Map<String, Object> model = new HashMap<>();
    model.put("email", person.email());
    model.put("displayName", profile.nameOr(""));
    model.put("headline", profile.headline());
    // the same renderer content uses, so a profile reads like the rest of the site
    // somebody describing themselves is the least trusted markdown on the server, and an
    // admin has to read it to decide whether to let them in
    model.put("aboutHtml", Markdown.toSafeHtml(profile.about()));
    model.put("location", profile.location());
    model.put("links", profile.linkList());
    model.put("anyLinks", !profile.linkList().isEmpty());
    model.put("filledIn", profile.isFilledIn());
    ArrayList<Map<String, Object>> answered = new ArrayList<>();
    for (Question question : questions) {
      String answer = answers.answerTo(question.id());
      if (answer == null || answer.isBlank()) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("prompt", question.prompt());
      row.put("answer", answer);
      answered.add(row);
    }
    model.put("answers", answered);
    model.put("anyAnswers", !answered.isEmpty());
    model.put("answeredCount", answers.answered());
    model.put("remainingCount", answers.remaining());
    return model;
  }

  /** ask for one member to be placed, if there is anything doing the placing */
  private void placeMe(Accounts accounts, long userId) {
    if (geocodes != null) {
      geocodes.forMember(accounts.store.databaseDomain, userId);
    }
  }
}
