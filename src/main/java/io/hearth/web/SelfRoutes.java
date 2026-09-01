package io.hearth.web;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.content.Markdown;
import io.hearth.people.ProfileRecord;
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
  /** so that deleting an account also clears what the request log remembers of them */
  private final io.hearth.analytics.AccessLog accessLog;
  private final Verbose verbose;


  public SelfRoutes(Templates templates, io.hearth.analytics.AccessLog accessLog,
                    Verbose verbose) {
    this.templates = templates;
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
            new String[]{HttpHeaderNames.LOCATION.toString(), config.urls.self});
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
      tab = Tab.profile;
      done = "Profile saved.";
      verbose.detail("self: " + me.email() + " updated their profile");
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
    model.put("canEnterAdmin", accounts.access.canEnterAdmin(me));
    model.put("nav", Navigation.forRequest(config, accounts, req));
    model.put("tabs", tabs(config, active));
    model.put("onProfile", active == Tab.profile);
    model.put("onData", active == Tab.data);
    model.put("exportUrl", config.urls.self + "?tab=data&download=export");
    model.put("privacyUrl", "/legal/privacy-policy");
    model.put("isConfigAdmin", accounts.access.isBootstrapAdmin(me.email()));


    model.put("display_name", profile.displayName());
    model.put("headline", profile.headline());
    model.put("about", profile.about());
    model.put("location", profile.location());
    model.put("links", profile.links());


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


    recorder.status(problem == null ? 200 : 400);
    Responses.send(ctx, req, problem == null ? HttpResponseStatus.OK : HttpResponseStatus.BAD_REQUEST,
        "text/html; charset=utf-8", templates.render("self", model),
        new String[]{HttpHeaderNames.SET_COOKIE.toString(), Cookies.csrf(accounts.security, csrf)});
  }



  private static List<Map<String, Object>> tabs(DomainConfig config, Tab active) {
    ArrayList<Map<String, Object>> tabs = new ArrayList<>();
    for (Tab tab : Tab.values()) {
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("label", tab.label);
      item.put("href", config.urls.self + "?tab=" + tab.name());
      item.put("active", tab == active);
      tabs.add(item);
    }
    return tabs;
  }


  /** somebody's profile, rendered for an admin deciding whether to approve them */
  public static Map<String, Object> reviewOf(Accounts accounts, UserRecord person) throws SQLException {
    ProfileRecord profile = accounts.people.profileOf(person.id());
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
    return model;
  }

}
