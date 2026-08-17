package io.hearth.people;

import io.hearth.auth.Access;
import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.content.Markdown;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.hearth.web.AccountRoutes;
import io.hearth.web.Chrome;
import io.hearth.web.Forms;
import io.hearth.web.Landing;
import io.hearth.web.Navigation;
import io.hearth.web.Responses;
import io.hearth.web.WebHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is here.
 *
 * <pre>
 *   /members         everybody, with enough of each profile to recognise somebody
 *   /members/&lt;id&gt;    one person, in full
 * </pre>
 *
 * <b>Signed in, approved, and no exceptions.</b> A directory of a community's members -- names,
 * where they live, what they said about themselves -- is the single most valuable page on this
 * server to somebody who should not have it, and it is the page most likely to be left open by
 * accident. So the check is the first thing in the handler rather than a condition on a template,
 * an unapproved person gets the same waiting page they get everywhere else, and a stranger gets
 * sent to sign in.
 *
 * <b>The listing truncates and the page does not.</b> A directory where every entry is somebody's
 * full life story is one nobody scrolls; a directory that only ever shows a name is one nobody
 * clicks. So the listing carries the three things that let you recognise a person -- what they are
 * called, roughly where they are, and the first line or two about them -- and everything else is
 * one tap away.
 *
 * <b>Nobody's email address is on either page.</b> The admin section shows addresses because
 * approving somebody is a decision about an address. A member looking at the directory is looking
 * at people, and a community's member list is the easiest thing in the world to screenshot.
 */
public class MemberRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(MemberRoutes.class);
  /** how much of somebody's own words the listing shows before it stops */
  private static final int BLURB = 220;
  private static final int PAGE = 500;

  private final Templates templates;
  private final Verbose verbose;

  public MemberRoutes(Templates templates, Verbose verbose) {
    this.templates = templates;
    this.verbose = verbose;
  }

  public static boolean owns(DomainConfig config, String path) {
    String root = config.urls.members;
    return path.equals(root) || path.startsWith(root + "/");
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    String path = Forms.path(req.uri());
    SessionRecord session = AccountRoutes.currentSession(accounts, req);
    if (session == null) {
      // the whole point of this page is that it is not public
      recorder.status(303);
      Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
          new String[]{HttpHeaderNames.LOCATION.toString(),
              Landing.carry(config.urls.login, Landing.here(req))});
      return;
    }
    try {
      UserRecord me = accounts.users.byId(session.userId());
      if (!accounts.access.isApproved(me)) {
        // belt to the braces: WebHandler's approval gate already covers this path, and a directory
        // of everybody is not the place to rely on one check being in the right order
        recorder.status(303);
        Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
            new String[]{HttpHeaderNames.LOCATION.toString(), config.urls.self});
        return;
      }
      String rest = path.equals(config.urls.members) ? ""
          : path.substring(config.urls.members.length() + 1);
      if (rest.isEmpty()) {
        directory(config, accounts, ctx, req, recorder, me);
        return;
      }
      one(config, accounts, ctx, req, recorder, me, rest);
    } catch (SQLException ex) {
      LOG.error("members-route-failed", ex);
      recorder.status(500);
      Map<String, Object> model = base(config, accounts, req, "Something went wrong");
      model.put("heading", "Something went wrong");
      model.put("message", "That did not work, and it is our fault rather than yours.");
      Responses.sendHtml(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          templates.render("message", model));
    }
  }

  private void directory(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                         FullHttpRequest req, WebHandler.Recorder recorder, UserRecord me)
      throws SQLException {
    String query = orEmpty(Forms.query(req.uri(), "q")).trim().toLowerCase();
    Map<Long, ProfileRecord> profiles = accounts.people.allProfiles();
    Access access = accounts.access;

    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (UserRecord user : accounts.users.recent(PAGE)) {
      if (user.disabled() || !access.isApproved(user)) {
        // somebody waiting to be let in is not a member yet, and somebody turned off is not one
        // any more. Neither belongs on a list of who is here.
        continue;
      }
      ProfileRecord profile = profiles.getOrDefault(user.id(), ProfileRecord.blank(user.id()));
      String name = profile.nameOr(localPartOf(user.email()));
      String about = ProfileText.plain(profile.about());
      if (!query.isEmpty() && !matches(query, name, profile.location(), about)) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", user.id());
      row.put("url", config.urls.members + "/" + user.id());
      row.put("name", name);
      row.put("initial", initialOf(name));
      row.put("headline", profile.headline());
      row.put("anyHeadline", !profile.headline().isBlank());
      row.put("location", profile.location());
      row.put("anyLocation", !profile.location().isBlank());
      row.put("blurb", ProfileText.truncate(about, BLURB));
      row.put("anyBlurb", !about.isBlank());
      row.put("me", user.id() == me.id());
      row.put("empty", !profile.isFilledIn());
      rows.add(row);
    }
    rows.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));

    Map<String, Object> model = base(config, accounts, req, "Members");
    model.put("members", rows);
    model.put("anyMembers", !rows.isEmpty());
    model.put("count", rows.size());
    model.put("q", orEmpty(Forms.query(req.uri(), "q")));
    model.put("searching", !query.isEmpty());
    model.put("selfUrl", config.urls.self);
    model.put("action", config.urls.members);
    recorder.status(200);
    Responses.sendHtml(ctx, req, HttpResponseStatus.OK, templates.render("members", model));
  }

  private void one(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, WebHandler.Recorder recorder, UserRecord me, String rest)
      throws SQLException {
    long id = idOf(rest);
    UserRecord user = id <= 0 ? null : accounts.users.byId(id);
    // absent rather than forbidden, and for the usual reason: "that member exists but you may not
    // see them" is an answer, and this page has nothing worth confirming
    if (user == null || user.disabled() || !accounts.access.isApproved(user)) {
      recorder.status(404);
      Map<String, Object> model = base(config, accounts, req, "Not found");
      model.put("heading", "Not found");
      model.put("message", "There is nobody here by that name.");
      Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND,
          templates.render("message", model));
      return;
    }
    ProfileRecord profile = accounts.people.profileOf(user.id());
    if (profile == null) {
      profile = ProfileRecord.blank(user.id());
    }
    String name = profile.nameOr(localPartOf(user.email()));

    Map<String, Object> model = base(config, accounts, req, name);
    model.put("name", name);
    model.put("initial", initialOf(name));
    model.put("headline", profile.headline());
    model.put("anyHeadline", !profile.headline().isBlank());
    model.put("location", profile.location());
    model.put("anyLocation", !profile.location().isBlank());
    // somebody else's markdown, so the filtered renderer -- this is the page a member reads about
    // another member, and the author is whoever wrote it rather than whoever runs the site
    model.put("aboutHtml", Markdown.toSafeHtml(profile.about()));
    model.put("anyAbout", !profile.about().isBlank());
    model.put("links", profile.linkList());
    model.put("anyLinks", !profile.linkList().isEmpty());
    model.put("empty", !profile.isFilledIn());
    model.put("me", user.id() == me.id());
    model.put("selfUrl", config.urls.self);
    model.put("backUrl", config.urls.members);
    model.put("since", user.createdAt() == null ? "" : user.createdAt().toLocalDateTime()
        .toLocalDate().toString());
    recorder.status(200);
    Responses.sendHtml(ctx, req, HttpResponseStatus.OK, templates.render("member", model));
  }

  private Map<String, Object> base(DomainConfig config, Accounts accounts, FullHttpRequest req,
                                   String title) {
    Map<String, Object> model = new HashMap<>();
    Chrome.site(model, config, accounts, req);
    model.put("title", title + " · " + config.name);
    model.put("community", config.name);
    model.put("nav", Navigation.forRequest(config, accounts, req));
    return model;
  }

  private static boolean matches(String query, String name, String location, String about) {
    return contains(name, query) || contains(location, query) || contains(about, query);
  }

  private static boolean contains(String value, String query) {
    return value != null && value.toLowerCase().contains(query);
  }

  /** the letter in the circle beside a name, when nobody has a photograph */
  private static String initialOf(String name) {
    String trimmed = name == null ? "" : name.trim();
    return trimmed.isEmpty() ? "?" : trimmed.substring(0, 1).toUpperCase();
  }

  /** never the whole address: a member list is the easiest thing in the world to screenshot */
  private static String localPartOf(String email) {
    int at = email == null ? -1 : email.indexOf('@');
    return at > 0 ? email.substring(0, at) : "somebody";
  }

  private static long idOf(String raw) {
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException ex) {
      return -1;
    }
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
