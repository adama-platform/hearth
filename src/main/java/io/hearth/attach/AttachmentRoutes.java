package io.hearth.attach;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.vhost.DomainConfig;
import io.hearth.web.AccountRoutes;
import io.hearth.web.Forms;
import io.hearth.web.Responses;
import io.hearth.web.WebHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * `/attachment/<id>.<ext>`: the one path that sends somebody else's bytes.
 *
 * Four things stand between a request and a file, and each of them is a real failure somebody has
 * had:
 *
 * <ul>
 *   <li><b>The type comes from the extension, never from the upload.</b> A browser's declared
 *       content type is a claim by whoever uploaded the file. Believing it means a member uploads
 *       `photo.png`, calls it `text/html`, and this community's own domain now serves
 *       attacker-written HTML to members who are signed in to it. The extension is checked against
 *       a closed table and that table decides.</li>
 *   <li><b>Private is the default and it means a member.</b> Public means anybody; private means a
 *       signed-in, approved person, checked here rather than inherited from wherever the link was
 *       found.</li>
 *   <li><b>Somebody else's page does not get to embed these.</b> Without a referrer check a
 *       community's server is a free image host for whoever finds a url, paid for in its bandwidth.
 *       A request with no referrer at all is honoured, because browsers omit it constantly and
 *       refusing those would mean refusing members.</li>
 *   <li><b>Browsers may cache; proxies may not.</b> `private` is the whole point: these are
 *       frequently a photograph of somebody's children, and a shared cache holding one is a copy in
 *       a place nobody in this community chose. The url carries an id and the bytes at an id never
 *       change, so a long max-age costs nothing.</li>
 * </ul>
 *
 * <b>Nothing here parses a path.</b> The id is a long and the extension is looked up in a table;
 * the file is found by computing a path from both. There is no string from a request anywhere near
 * the filesystem.
 */
public class AttachmentRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(AttachmentRoutes.class);
  /** where they live, as a constant: a link in a page cannot depend on a per-domain setting */
  public static final String PREFIX = "/attachment/";
  /**
   * Where an upload is posted.
   *
   * A constant, and the one path in the server allowed a body larger than a form -- see
   * {@link io.hearth.web.UploadGate}. It has to be known to the pipeline, which has not resolved a
   * domain yet, so it cannot be a per-community setting.
   */
  public static final String UPLOAD = "/attachment/upload";

  private final AttachmentStore store;
  private final BlobCache cache;
  private final Verbose verbose;

  public AttachmentRoutes(AttachmentStore store, BlobCache cache, Verbose verbose) {
    this.store = store;
    this.cache = cache;
    this.verbose = verbose;
  }

  public static boolean owns(String path) {
    return path.startsWith(PREFIX);
  }

  public BlobCache cache() {
    return cache;
  }

  public AttachmentStore files() {
    return store;
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    String path = Forms.path(req.uri());
    if (path.equals(UPLOAD)) {
      if (!HttpMethod.POST.equals(req.method())) {
        recorder.status(405);
        Responses.send(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, null, Responses.EMPTY,
            new String[]{HttpHeaderNames.ALLOW.toString(), "POST"});
        return;
      }
      upload(config, accounts, ctx, req, recorder);
      return;
    }
    if (!HttpMethod.GET.equals(req.method()) && !HttpMethod.HEAD.equals(req.method())) {
      recorder.status(405);
      Responses.send(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, null, Responses.EMPTY,
          new String[]{HttpHeaderNames.ALLOW.toString(), "GET, HEAD"});
      return;
    }
    Asked asked = parse(path);
    if (asked == null || !config.attachments.allows(asked.extension())) {
      notThere(ctx, req, recorder);
      return;
    }
    try {
      Attachments.Attachment attachment = accounts.attachments.byId(asked.id());
      if (attachment == null || !attachment.extension().equals(asked.extension())) {
        // the extension has to match the row: /attachment/12.png must not serve the mp4 that is
        // actually 12, or a page could dress a video up as a picture
        notThere(ctx, req, recorder);
        return;
      }
      if (!attachment.isPublic() && !isMember(accounts, req)) {
        // a 404 rather than a 401: whether a private attachment exists is itself private, and a
        // sign-in form is no use to the <img> tag that asked
        verbose.detail(() -> "attachment " + asked.id() + " is private and this is not a member");
        notThere(ctx, req, recorder);
        return;
      }
      if (config.attachments.checkReferrer
          && !refererIsOurs(req, config.attachments.referrersFor(config.domain))) {
        verbose.detail(() -> "attachment " + asked.id() + " refused: embedded somewhere else");
        recorder.status(403);
        Responses.send(ctx, req, HttpResponseStatus.FORBIDDEN, "text/plain; charset=utf-8",
            "this file belongs to another site\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            new String[]{HttpHeaderNames.CACHE_CONTROL.toString(), "no-store"});
        return;
      }

      byte[] bytes = cache.get(attachment.id(), attachment.extension());
      if (bytes == null) {
        bytes = store.get(attachment.id(), attachment.extension());
        if (bytes == null) {
          // a row whose file is gone: a 404 and a line in the log, which is a diagnosable state
          LOG.warn("attachment-missing id={} at {}", attachment.id(),
              store.pathOf(attachment.id(), attachment.extension()));
          notThere(ctx, req, recorder);
          return;
        }
        cache.put(attachment.id(), attachment.extension(), bytes);
      }
      send(config, ctx, req, recorder, attachment, bytes);
    } catch (SQLException | IOException ex) {
      LOG.error("attachment-failed path={}", path, ex);
      recorder.status(500);
      Responses.send(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "text/plain; charset=utf-8",
          "that did not work\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  private void send(DomainConfig config, ChannelHandlerContext ctx, FullHttpRequest req,
                    WebHandler.Recorder recorder, Attachments.Attachment attachment, byte[] bytes) {
    Kinds.Type type = Kinds.of(attachment.extension());
    String mime = type == null ? "application/octet-stream" : type.mime();
    // Anything this server cannot name is a download rather than a page. `inline` is only ever
    // used for the kinds a browser renders without executing anything.
    boolean inline = type != null && type.embeddable();
    String disposition = (inline ? "inline" : "attachment")
        + "; filename=\"" + Kinds.safeName(attachment.filename()).replace("\"", "") + "\"";
    // private: browsers yes, shared caches never. These are frequently photographs of somebody's
    // children, and a copy in a proxy is a copy in a place nobody here chose.
    String cacheControl = "private, max-age=" + config.attachments.browserCacheSeconds
        + (config.attachments.browserCacheSeconds > 0 ? ", immutable" : ", no-cache");
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, mime, bytes, new String[]{
        HttpHeaderNames.CACHE_CONTROL.toString(), cacheControl,
        HttpHeaderNames.CONTENT_DISPOSITION.toString(), disposition,
        // belt and braces on the type: this file is what the extension said and nothing is to go
        // looking for a better answer inside the bytes
        "X-Content-Type-Options", "nosniff",
        HttpHeaderNames.ETAG.toString(), "\"" + attachment.digest() + "\""});
  }

  /**
   * Somebody uploading a file.
   *
   * Lives here rather than in the admin section for one reason: this is the only path on the server
   * allowed a body bigger than a form, and that ceiling is enforced in the pipeline by path -- so
   * the path has to be a constant, and the handler for it has to be somewhere the pipeline can name
   * without having resolved a domain. Everything else about it is an ordinary admin action: a
   * session, a permission, a CSRF token, and a 303 afterwards.
   */
  private void upload(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                      FullHttpRequest req, WebHandler.Recorder recorder) {
    String back = config.urls.admin + "/attachments";
    try {
      SessionRecord session = AccountRoutes.currentSession(accounts, req);
      UserRecord me = session == null ? null : accounts.users.byId(session.userId());
      if (me == null || !accounts.access.can(me, io.hearth.auth.Permission.attachments_write)) {
        // the same 404 the admin section gives: this path does not confirm its own existence to
        // somebody who cannot use it
        notThere(ctx, req, recorder);
        return;
      }
      Uploads.Received received = Uploads.of(req, config.attachments.maxBytes);
      if (!io.hearth.web.Cookies.csrfMatches(received.field("csrf"),
          Forms.cookie(req, io.hearth.web.Cookies.CSRF_COOKIE))) {
        redirect(ctx, req, recorder, back);
        return;
      }
      // A bundle of content arrives the same way a photograph does, because a file is a file and
      // this is the one path in the server allowed a big one. What it is for is different, so it
      // is handled by the content importer and never written down as an attachment.
      if ("1".equals(received.field("bundle"))) {
        importBundle(config, accounts, ctx, req, me, session, received, recorder);
        return;
      }
      String problem = store(config, accounts, me, received);
      if (problem != null) {
        adminFlash.set(session.tokenHash(), problem, true);
      }
      redirect(ctx, req, recorder, back);
    } catch (SQLException | RuntimeException ex) {
      LOG.error("attachment-upload-failed", ex);
      redirect(ctx, req, recorder, back);
    }
  }

  /**
   * A bundle of content, arriving as a file.
   *
   * The same merge the paste box and the API do -- one implementation, so a file and a paste and a
   * script cannot disagree about what an import means.
   */
  private void importBundle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                            FullHttpRequest req, UserRecord me, SessionRecord session,
                            Uploads.Received received, WebHandler.Recorder recorder)
      throws SQLException {
    String back = config.urls.admin + "/content/bundles";
    if (!accounts.access.can(me, io.hearth.auth.Permission.content_write)
        || !accounts.access.can(me, io.hearth.auth.Permission.content_publish)) {
      adminFlash.set(session.tokenHash(), "Bringing a bundle in writes pages and puts them live,"
          + " so it needs both 'write pages' and 'publish pages'.", true);
      redirect(ctx, req, recorder, back);
      return;
    }
    Uploads.File file = received.file();
    if (file == null || file.bytes().length == 0) {
      adminFlash.set(session.tokenHash(), "Nothing arrived. Pick a file first.", true);
      redirect(ctx, req, recorder, back);
      return;
    }
    io.hearth.content.Bundle.Report report = io.hearth.content.Bundle.apply(
        accounts.site.store(), new String(file.bytes(), java.nio.charset.StandardCharsets.UTF_8),
        me.id(), me.email());
    verbose.say("content: " + me.email() + " imported " + file.filename() + " -- "
        + report.describe());
    adminFlash.set(session.tokenHash(), report.describe(),
        report.total() == 0 && !report.problems().isEmpty());
    redirect(ctx, req, recorder, back);
  }

  /**
   * Write one down: the bytes first, then the row.
   *
   * The other order leaves a row whose file does not exist, which is a broken attachment on a page
   * with nothing to do about it. This way round the failure is an orphan file nothing points at,
   * which costs disk and confuses nobody -- and the id has to exist before the bytes can be named
   * after it, so the row is created and then filled.
   */
  private String store(DomainConfig config, Accounts accounts, UserRecord me,
                       Uploads.Received received) throws SQLException {
    Uploads.File file = received.file();
    if (file == null || file.bytes().length == 0) {
      return "Nothing arrived. Pick a file first.";
    }
    if (received.tooLarge()) {
      return "That is larger than " + (config.attachments.maxBytes / (1024 * 1024))
          + "MB, which is as much as this community accepts.";
    }
    String extension = Kinds.extensionOf(file.filename());
    if (!config.attachments.allows(extension)) {
      return extension.isEmpty()
          ? "That file has no extension, so there is no way to know what it is."
          : "This community does not accept ." + extension + " files. It accepts: "
              + String.join(", ", config.attachments.extensions) + ".";
    }
    Kinds.Type type = Kinds.of(extension);
    String digest = io.hearth.auth.Tokens.hash(new String(file.bytes(),
        java.nio.charset.StandardCharsets.ISO_8859_1));
    Attachments.Attachment made = accounts.attachments.create(file.filename(), extension,
        type.mime(), type.kind(), file.bytes().length, digest, "disk",
        received.field("folder"), received.field("tags"), received.field("description"),
        "on".equals(received.field("public")), me.id(), me.email());
    try {
      store.put(made.id(), extension, file.bytes());
    } catch (IOException ex) {
      // the row goes away with the bytes it never had: an attachment somebody can see and cannot
      // open is worse than one that plainly did not upload
      LOG.error("attachment-write-failed id={}", made.id(), ex);
      accounts.attachments.delete(made.id(), me.id());
      return "That could not be written to disk. Nothing was kept.";
    }
    verbose.say("attachment: " + me.email() + " uploaded " + made.filename()
        + " (" + made.size() + ")");
    return null;
  }

  /** the admin section's flash, so an upload can say why it did not work on the page it lands on */
  private io.hearth.web.Flash adminFlash = new io.hearth.web.Flash();

  public void sharesFlashWith(io.hearth.web.Flash flash) {
    this.adminFlash = flash;
  }

  private static void redirect(ChannelHandlerContext ctx, FullHttpRequest req,
                               WebHandler.Recorder recorder, String where) {
    recorder.status(303);
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
        new String[]{HttpHeaderNames.LOCATION.toString(), where});
  }

  /** what a request asked for: an id and an extension, or nothing at all */
  record Asked(long id, String extension) {
  }

  static Asked parse(String path) {
    if (!owns(path)) {
      return null;
    }
    String rest = path.substring(PREFIX.length());
    int dot = rest.lastIndexOf('.');
    if (dot <= 0 || dot == rest.length() - 1 || rest.indexOf('/') >= 0) {
      return null;
    }
    String extension = Kinds.clean(rest.substring(dot + 1));
    if (extension.isEmpty()) {
      return null;
    }
    try {
      long id = Long.parseLong(rest.substring(0, dot));
      return id <= 0 ? null : new Asked(id, extension);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /**
   * Is this request coming from a page on this site?
   *
   * A missing referrer passes. Browsers omit it on a direct navigation, on a bookmark, from an
   * address bar, under `Referrer-Policy: no-referrer`, and from several privacy extensions -- and a
   * check that refused all of those would be refusing members to inconvenience a hotlinker who can
   * forge the header anyway. This is a bandwidth measure, and treating it as a security boundary
   * would be the mistake invariant 13 is about.
   */
  static boolean refererIsOurs(FullHttpRequest req, Set<String> allowed) {
    String referer = req.headers().get(HttpHeaderNames.REFERER);
    if (referer == null || referer.isBlank()) {
      return true;
    }
    String host;
    try {
      host = java.net.URI.create(referer.trim()).getHost();
    } catch (RuntimeException ex) {
      // a referrer nobody can parse is not a page on this site
      return false;
    }
    if (host == null) {
      return false;
    }
    String lower = host.toLowerCase(java.util.Locale.ROOT);
    for (String ours : allowed) {
      if (lower.equals(ours) || lower.endsWith("." + ours)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isMember(Accounts accounts, FullHttpRequest req) {
    try {
      SessionRecord session = AccountRoutes.currentSession(accounts, req);
      if (session == null) {
        return false;
      }
      UserRecord user = accounts.users.byId(session.userId());
      return user != null && !user.disabled() && accounts.access.isApproved(user);
    } catch (SQLException ex) {
      LOG.error("attachment-session-failed", ex);
      return false;
    }
  }

  private static void notThere(ChannelHandlerContext ctx, FullHttpRequest req,
                               WebHandler.Recorder recorder) {
    recorder.status(404);
    Responses.send(ctx, req, HttpResponseStatus.NOT_FOUND, "text/plain; charset=utf-8",
        "no such file\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        new String[]{HttpHeaderNames.CACHE_CONTROL.toString(), "no-store"});
  }
}
