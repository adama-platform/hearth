package io.hearth.legal;

import io.hearth.auth.Accounts;
import io.hearth.common.Verbose;
import io.hearth.content.Markdown;
import io.hearth.template.Templates;
import io.hearth.theme.Theme;
import io.hearth.vhost.DomainConfig;
import io.hearth.web.Forms;
import io.hearth.web.Icons;
import io.hearth.web.Responses;
import io.hearth.web.WebHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The two documents, as pages anybody can read.
 *
 * <pre>
 *   /legal                       both of them, listed
 *   /legal/terms-of-service      what using this community means
 *   /legal/privacy-policy        what is held about a member
 * </pre>
 *
 * <b>Open to everybody, signed in or not.</b> Every email this server sends links to the terms, and
 * most of those emails go to somebody who has no account yet -- an invitation, a registration code.
 * A link in an email that lands on a sign-in page is a link that has told the reader nothing, and
 * "the terms you are accepting are behind a login" is not a defensible sentence.
 *
 * The paths are constants rather than entries in `urls`. They are quoted in email footers, in the
 * privacy policy, and by anybody who links to them from outside, and a configurable path would mean
 * a link that is right for one community and wrong for the next.
 *
 * These render in the <i>admin</i> palette rather than the site's. That is not an oversight: a
 * community's promises are not its decoration, and reading them in a plainer, steadier set of
 * colours than the rest of the site is the right signal. It also means a community that themes
 * itself into something unreadable has not themed its terms into something unreadable.
 */
public class LegalRoutes {
  /** the one path prefix, quoted in emails and never configurable */
  public static final String ROOT = "/legal";

  private final Templates templates;
  private final Verbose verbose;

  public LegalRoutes(Templates templates, Verbose verbose) {
    this.templates = templates;
    this.verbose = verbose;
  }

  public static boolean owns(String path) {
    return path.equals(ROOT) || path.startsWith(ROOT + "/");
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    String path = Forms.path(req.uri());
    String rest = path.equals(ROOT) ? "" : path.substring(ROOT.length() + 1);
    if (rest.isEmpty()) {
      index(config, accounts, ctx, req, recorder);
      return;
    }
    LegalDoc doc = LegalDoc.bySlug(rest);
    if (doc == null) {
      verbose.detail("no such legal document: " + rest + " -> 404");
      recorder.status(404);
      Map<String, Object> model = base(config, accounts, "Not found");
      model.put("heading", "Not found");
      model.put("bodyHtml", "<p>There is no document at that address.</p>");
      Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND,
          templates.render("legal", model));
      return;
    }
    Map<String, Object> model = base(config, accounts, doc.title);
    LegalDocs.Text text = accounts == null ? null : accounts.legal.of(doc);
    String markdown = text == null ? doc.standard() : text.markdown();
    // operator-written, so the trusted renderer: an administrator editing the terms can already
    // edit every page on the site, and the two documents most likely to want a table are these
    model.put("bodyHtml",
        Markdown.toHtml(LegalDoc.fill(markdown, config.name, config.domain)));
    model.put("heading", doc.title);
    model.put("docs", listing(config, doc));
    recorder.status(200);
    Responses.sendHtml(ctx, req, HttpResponseStatus.OK, templates.render("legal", model));
  }

  private void index(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    Map<String, Object> model = base(config, accounts, "Legal");
    StringBuilder body = new StringBuilder();
    body.append("<p>The promises this community makes, and what it asks of you.</p><ul>");
    for (LegalDoc doc : LegalDoc.values()) {
      body.append("<li><a href=\"").append(doc.path()).append("\">").append(doc.title)
          .append("</a> &mdash; ").append(doc.summary).append("</li>");
    }
    body.append("</ul>");
    model.put("heading", "Legal");
    model.put("bodyHtml", body.toString());
    model.put("docs", listing(config, null));
    recorder.status(200);
    Responses.sendHtml(ctx, req, HttpResponseStatus.OK, templates.render("legal", model));
  }

  private static java.util.List<Map<String, Object>> listing(DomainConfig config, LegalDoc active) {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (LegalDoc doc : LegalDoc.values()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("title", doc.title);
      row.put("href", doc.path());
      row.put("on", doc == active);
      rows.add(row);
    }
    return rows;
  }

  private static Map<String, Object> base(DomainConfig config, Accounts accounts, String title) {
    Map<String, Object> model = new HashMap<>();
    model.put("favicon", Icons.FAVICON_DATA_URI);
    model.put("title", title + " · " + config.name);
    model.put("community", config.name);
    model.put("home", "/");
    model.put("palette", accounts == null
        ? Theme.defaultFor(Theme.Scope.admin).css()
        : accounts.themes.css(Theme.Scope.admin));
    return model;
  }
}
