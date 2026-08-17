package io.hearth.places;

import io.hearth.auth.Accounts;
import io.hearth.common.Verbose;
import io.hearth.content.Markdown;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.hearth.web.Forms;
import io.hearth.web.Icons;
import io.hearth.web.Navigation;
import io.hearth.web.Responses;
import io.hearth.web.WebHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
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
 * The address book, as pages anybody in the community can read.
 *
 * <pre>
 *   /places                    the kinds of place this community keeps
 *   /places/&lt;type&gt;             everything of that kind
 *   /places/&lt;type&gt;/&lt;slug&gt;      one of them
 * </pre>
 *
 * A place's page is rendered through the template its *type* names, so a community styles all its
 * ranches once rather than one at a time -- and the template is edited in the admin like any other,
 * which is what makes the listing something a group can shape rather than something this program
 * decided. When a type names no template, the built-in page renders the address, the declared
 * fields and the markdown, which is enough to be useful on the first day.
 *
 * Reading is open to members and closed to nobody else, exactly like the rest of the community: a
 * list of the vendors who give a discount to people with MS is not something to leave on the open
 * web with the community's name attached.
 */
public class PlaceRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(PlaceRoutes.class);
  private static final int PAGE_SIZE = 500;

  private final Templates templates;
  private final Verbose verbose;

  public PlaceRoutes(Templates templates, Verbose verbose) {
    this.templates = templates;
    this.verbose = verbose;
  }

  public static boolean owns(DomainConfig config, String path) {
    String root = config.urls.places;
    return path.equals(root) || path.startsWith(root + "/");
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    try {
      // the address book is community content: a list of the vendors who give a discount to people
      // with MS is not something to leave on the open web with the community's name on it
      if (io.hearth.web.AccountRoutes.currentSession(accounts, req) == null) {
        recorder.status(303);
        Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
            new String[]{io.netty.handler.codec.http.HttpHeaderNames.LOCATION.toString(),
                io.hearth.web.Landing.carry(config.urls.login, io.hearth.web.Landing.here(req))});
        return;
      }
      String path = Forms.path(req.uri());
      if (io.netty.handler.codec.http.HttpMethod.POST.equals(req.method())) {
        comment(config, accounts, ctx, req, recorder);
        return;
      }
      String rest = path.substring(config.urls.places.length());
      if (rest.startsWith("/")) {
        rest = rest.substring(1);
      }
      if (rest.isEmpty()) {
        index(config, accounts, ctx, req, recorder);
        return;
      }
      int slash = rest.indexOf('/');
      if (slash < 0) {
        listing(config, accounts, ctx, req, rest, recorder);
        return;
      }
      one(config, accounts, ctx, req, rest.substring(0, slash), rest.substring(slash + 1),
          recorder);
    } catch (SQLException ex) {
      LOG.error("places-route-failed", ex);
      recorder.status(500);
      Map<String, Object> model = base(config, accounts, req, "Something went wrong");
      model.put("heading", "Something went wrong");
      model.put("message", "That did not work, and it is our fault rather than yours.");
      Responses.sendHtml(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          templates.render("message", model));
    }
  }

  private void index(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) throws SQLException {
    Map<String, Object> model = base(config, accounts, req, "Places");
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Places.Type type : accounts.places.publishedTypes()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("slug", type.slug());
      row.put("label", type.labelOr());
      row.put("plural", type.pluralOr());
      row.put("description", type.description());
      row.put("icon", Icons.of(type.icon()));
      row.put("url", config.urls.places + "/" + type.slug());
      row.put("count", accounts.places.inType(type.slug(), true, PAGE_SIZE).size());
      rows.add(row);
    }
    model.put("types", rows);
    model.put("anyTypes", !rows.isEmpty());
    recorder.status(200);
    Responses.sendHtml(ctx, req, HttpResponseStatus.OK, templates.render("places", model));
  }

  private void listing(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                       FullHttpRequest req, String typeSlug, WebHandler.Recorder recorder)
      throws SQLException {
    Places.Type type = accounts.places.typeBySlug(typeSlug);
    if (type == null || !type.published()) {
      notHere(config, accounts, ctx, req, recorder);
      return;
    }
    String query = Forms.query(req.uri(), "q");
    Map<String, Object> model = base(config, accounts, req, type.pluralOr());
    model.put("label", type.labelOr());
    model.put("plural", type.pluralOr());
    model.put("description", type.description());
    model.put("backUrl", config.urls.places);
    model.put("action", config.urls.places + "/" + type.slug());
    model.put("q", query == null ? "" : query);

    // the columns a listing shows are the fields the type declared, so a community that added
    // "grass finished" gets a column for it without anybody touching this code
    ArrayList<Map<String, Object>> columns = new ArrayList<>();
    for (io.hearth.content.TemplateField field : type.fields()) {
      LinkedHashMap<String, Object> column = new LinkedHashMap<>();
      column.put("name", field.name());
      column.put("label", field.labelOr());
      columns.add(column);
    }
    model.put("columns", columns);
    model.put("anyColumns", !columns.isEmpty());

    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    List<Places.Place> found = query == null || query.isBlank()
        ? accounts.places.inType(type.slug(), true, PAGE_SIZE)
        : accounts.places.search(query, true, PAGE_SIZE);
    for (Places.Place place : found) {
      if (!place.typeSlug().equals(type.slug())) {
        continue;
      }
      rows.add(row(config, type, place));
    }
    model.put("places", rows);
    model.put("anyPlaces", !rows.isEmpty());
    model.put("count", rows.size());

    recorder.status(200);
    Responses.sendHtml(ctx, req, HttpResponseStatus.OK, templates.render("place_list", model));
  }

  /**
   * Something said under a place.
   *
   * The same machinery the board and the calendar use. What it adds to an address book is the part
   * an address book is missing: "they do a discount on Tuesdays", "the back room is the quiet one",
   * "they moved" -- which is the knowledge a community actually has about a place and which has
   * nowhere else to live.
   */
  private void comment(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                       FullHttpRequest req, WebHandler.Recorder recorder) throws SQLException {
    io.hearth.auth.SessionRecord session =
        io.hearth.web.AccountRoutes.currentSession(accounts, req);
    Forms form = Forms.of(req, Forms.MAX_CONTENT_BYTES);
    String where = config.urls.places;
    if (session == null || form.bodyTooLarge()
        || !io.hearth.web.Cookies.csrfMatches(form.get(io.hearth.web.Cookies.CSRF_FIELD),
            Forms.cookie(req, io.hearth.web.Cookies.CSRF_COOKIE))) {
      redirect(ctx, req, recorder, where);
      return;
    }
    long placeId = idOf(form.get("place"));
    Places.Place place = placeId <= 0 ? null : accounts.places.byId(placeId);
    if (place == null || !place.published()) {
      redirect(ctx, req, recorder, where);
      return;
    }
    io.hearth.auth.UserRecord me = accounts.users.byId(session.userId());
    io.hearth.board.CommentBox.act(accounts, io.hearth.board.Subject.place(placeId), me, form,
        accounts.access.can(me, io.hearth.auth.Permission.places_moderate));
    redirect(ctx, req, recorder,
        config.urls.places + "/" + place.typeSlug() + "/" + place.slug() + "#comments");
  }

  private static long idOf(String raw) {
    try {
      return Long.parseLong(String.valueOf(raw).trim());
    } catch (NumberFormatException ex) {
      return -1;
    }
  }

  private void redirect(ChannelHandlerContext ctx, FullHttpRequest req,
                        WebHandler.Recorder recorder, String where) {
    recorder.status(303);
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
        new String[]{io.netty.handler.codec.http.HttpHeaderNames.LOCATION.toString(), where});
  }

  private void one(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, String typeSlug, String slug,
                   WebHandler.Recorder recorder) throws SQLException {
    Places.Type type = accounts.places.typeBySlug(typeSlug);
    Places.Place place = accounts.places.bySlug(typeSlug, slug);
    if (type == null || !type.published() || place == null || !place.published()) {
      notHere(config, accounts, ctx, req, recorder);
      return;
    }

    Map<String, Object> model = base(config, accounts, req, place.name());
    model.putAll(row(config, type, place));
    model.put("bodyHtml", Markdown.toHtml(place.body()));
    model.put("backUrl", config.urls.places + "/" + type.slug());
    model.put("label", type.labelOr());
    model.put("plural", type.pluralOr());
    io.hearth.auth.SessionRecord session =
        io.hearth.web.AccountRoutes.currentSession(accounts, req);
    io.hearth.auth.UserRecord me =
        session == null ? null : accounts.users.byId(session.userId());
    model.put("csrf", io.hearth.web.Cookies.stableToken(req));
    io.hearth.board.CommentBox.render(model, accounts, io.hearth.board.Subject.place(place.id()),
        me, config.urls.places,
        me != null && accounts.access.can(me, io.hearth.auth.Permission.places_moderate));
    model.put("commentSubjectField", "place");
    model.put("commentSubjectId", place.id());

    // The type names the template. Rendering through the site's own renderer would mean a place
    // page could only ever look like a content page; this way a community writes one template per
    // kind and every ranch gets it.
    String template = type.templateName();
    byte[] html = template == null || template.isBlank()
        ? null : accounts.site.renderWithTemplate(template, model);
    if (html == null) {
      // no template named, or one that has since been deleted: the built-in page is enough to be
      // useful on the first day, which is when a community has not written a template yet
      html = templates.render("place", model);
    }
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8", html,
        new String[]{io.netty.handler.codec.http.HttpHeaderNames.SET_COOKIE.toString(),
            io.hearth.web.Cookies.csrf(accounts.security,
                io.hearth.web.Cookies.stableToken(req))});
  }

  /** everything about one place that both the listing and the page need */
  private Map<String, Object> row(DomainConfig config, Places.Type type, Places.Place place) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("id", place.id());
    row.put("slug", place.slug());
    row.put("name", place.name());
    row.put("url", config.urls.places + "/" + place.typeSlug() + "/" + place.slug());
    row.put("address", place.oneLine());
    row.put("anyAddress", !place.oneLine().isBlank());
    row.put("locality", place.locality());
    row.put("website", place.url());
    row.put("phone", place.phone());
    row.put("email", place.email());
    row.put("mapped", place.mapped());
    row.put("latitude", place.latitude());
    row.put("longitude", place.longitude());
    // a link a phone opens in whatever map app it has, rather than one this server chooses
    row.put("mapUrl", place.mapped()
        ? "geo:" + place.latitude() + "," + place.longitude() : null);

    Map<String, String> values = place.values();
    ArrayList<Map<String, Object>> fields = new ArrayList<>();
    for (io.hearth.content.TemplateField field : type.fields()) {
      String value = values.getOrDefault(field.name(), "");
      LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", field.name());
      entry.put("label", field.labelOr());
      entry.put("value", value);
      entry.put("any", !value.isBlank());
      fields.add(entry);
    }
    row.put("fields", fields);
    row.put("anyFields", !fields.isEmpty());
    // and by name, so a template can write {{extra.grass_finished}} rather than looping
    LinkedHashMap<String, Object> byName = new LinkedHashMap<>();
    byName.putAll(values);
    row.put("extra", byName);
    return row;
  }

  private void notHere(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                       FullHttpRequest req, WebHandler.Recorder recorder) {
    Map<String, Object> model = base(config, accounts, req, "Not here");
    model.put("heading", "That is not here");
    model.put("message", "It may have been removed, or it may not be published yet.");
    recorder.status(404);
    Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, templates.render("message", model));
  }

  private Map<String, Object> base(DomainConfig config, Accounts accounts, FullHttpRequest req,
                                   String title) {
    Map<String, Object> model = new HashMap<>();
    io.hearth.web.Chrome.site(model, config, accounts, req);
    model.put("title", title);
    model.put("community", config.name);
    model.put("placesUrl", config.urls.places);
    model.put("nav", Navigation.forRequest(config, accounts, req));
    return model;
  }
}
