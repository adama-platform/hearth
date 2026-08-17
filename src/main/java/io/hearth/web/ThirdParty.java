package io.hearth.web;

import io.hearth.common.Verbose;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Somebody else's JavaScript, served from inside the jar.
 *
 * <pre>
 *   /3rd/&lt;package&gt;/&lt;version&gt;/&lt;file&gt;
 *   /3rd/milkdown/7.5.0/milkdown.js
 * </pre>
 *
 * **This is the one exception to "no bytes on disk except the database", and it is not really an
 * exception.** These files are classpath resources baked into `hearth.jar` at build time by
 * `just 3rd`, so there is still exactly one artifact to deploy, still nothing beside it to keep in
 * sync, and still no directory an operator has to know about. The rule that mattered was never
 * "the jar must be small" -- it was "a running server does not depend on files somebody could
 * forget to copy". A resource inside the jar cannot be forgotten.
 *
 * The version is in the path on purpose. It makes the URL immutable, which is what lets these be
 * cached for a year -- and it means two versions can be present during an upgrade without a
 * cache somewhere serving a mixture. It also means the path in a template says exactly which
 * version that template was written against, which a bare `/3rd/milkdown/milkdown.js` never does.
 *
 * Nothing here is fetched at runtime. A community's editor must not stop working because a CDN is
 * down, changed a file under the same URL, or decided to log who loaded it -- and a page that
 * reaches out to somebody else's server is a page that told them a member was reading it.
 */
public class ThirdParty {
  public static final String PREFIX = "/3rd/";
  /** where the packages live inside the jar */
  private static final String ROOT = "3rd/";
  /** immutable by construction, so a year is honest rather than optimistic */
  private static final String CACHE = "public, max-age=31536000, immutable";
  private static final int MAX_BYTES = 8 * 1024 * 1024;
  /** one name per vendored package, matching a file in `3rd-licenses`; see THIRD-PARTY.md */
  private static final java.util.List<String> LICENSED = java.util.List.of("milkdown");

  private final Verbose verbose;

  public ThirdParty(Verbose verbose) {
    this.verbose = verbose;
  }

  /** where the licences are read back out, and the one path here that is not a package file */
  public static final String LICENSES = "/3rd/licenses";

  public static boolean owns(String path) {
    return path != null && path.startsWith(PREFIX);
  }

  /**
   * Serve one file, or 404.
   *
   * The path is checked character by character rather than normalized, because this is the one
   * place in the server that turns a request path into a resource lookup, and a `..` that got
   * through would read anything on the classpath. Refusing anything that is not
   * `package/version/file` is easier to be sure of than sanitizing.
   */
  public void handle(ChannelHandlerContext ctx, FullHttpRequest req, WebHandler.Recorder recorder) {
    String path = Forms.path(req.uri());
    if (path.equals(LICENSES)) {
      licenses(ctx, req, recorder);
      return;
    }
    String rest = path.substring(PREFIX.length());
    if (!safe(rest)) {
      verbose.detail(() -> "3rd: refused " + path);
      notFound(ctx, req, recorder);
      return;
    }
    byte[] bytes = read(ROOT + rest);
    if (bytes == null) {
      notFound(ctx, req, recorder);
      return;
    }
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, contentType(rest), bytes,
        new String[]{HttpHeaderNames.CACHE_CONTROL.toString(), CACHE});
  }

  /**
   * Every licence this jar redistributes under, in one plain-text page.
   *
   * <b>Vendoring is redistribution, and every licence here asks for its notice to travel with the
   * code.</b> The bundles used to be baked into the jar with nothing beside them, which is a
   * licence breach on the part of whoever ships the jar -- that is the operator, not the person who
   * wrote this. It costs one page.
   *
   * Text rather than HTML because a licence is a document to be copied, not decorated, and because
   * this has to be right even in a jar built without the bundles: the licence files are in git and
   * the packages are not.
   */
  private void licenses(ChannelHandlerContext ctx, FullHttpRequest req,
                        WebHandler.Recorder recorder) {
    StringBuilder out = new StringBuilder();
    out.append("Third-party code redistributed inside this server\n")
        .append("================================================\n\n")
        .append("Each library below is served from this machine rather than from somebody else's,")
        .append("\nso a page here never tells a third party who is reading it. Their licences")
        .append("\nfollow, in full.\n");
    for (String name : LICENSED) {
      byte[] text = read("3rd-licenses/" + name + ".txt");
      out.append("\n\n---- ").append(name).append(" ").append("-".repeat(60 - name.length()))
          .append("\n\n");
      out.append(text == null ? "(the licence file is missing from this build, which is a bug)"
          : new String(text, java.nio.charset.StandardCharsets.UTF_8));
    }
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/plain; charset=utf-8",
        out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
        new String[]{HttpHeaderNames.CACHE_CONTROL.toString(), "public, max-age=3600"});
  }

  /**
   * package/version/file, and nothing else.
   *
   * No leading slash, no empty segment, exactly three or more segments, and every character from a
   * small set that cannot spell `..` or an absolute path. A dot is allowed inside a segment (for
   * `7.5.0` and for `.js`) but a segment that *is* dots is refused, which is the whole attack.
   */
  static boolean safe(String rest) {
    if (rest == null || rest.isEmpty() || rest.length() > 256) {
      return false;
    }
    String[] parts = rest.split("/", -1);
    if (parts.length < 3) {
      return false;
    }
    for (String part : parts) {
      if (part.isEmpty() || part.chars().allMatch(ch -> ch == '.')) {
        return false;
      }
      for (int k = 0; k < part.length(); k++) {
        char ch = part.charAt(k);
        boolean allowed = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
            || (ch >= '0' && ch <= '9') || ch == '.' || ch == '-' || ch == '_' || ch == '@';
        if (!allowed) {
          return false;
        }
      }
    }
    return true;
  }

  static String contentType(String rest) {
    String lower = rest.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
      return "text/javascript; charset=utf-8";
    }
    if (lower.endsWith(".css")) {
      return "text/css; charset=utf-8";
    }
    if (lower.endsWith(".json")) {
      return "application/json; charset=utf-8";
    }
    if (lower.endsWith(".svg")) {
      return "image/svg+xml";
    }
    if (lower.endsWith(".woff2")) {
      return "font/woff2";
    }
    if (lower.endsWith(".map")) {
      // served so a developer's console works; harmless and small
      return "application/json; charset=utf-8";
    }
    return "application/octet-stream";
  }

  private byte[] read(String resource) {
    try (InputStream in = ThirdParty.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        return null;
      }
      byte[] bytes = in.readNBytes(MAX_BYTES);
      return bytes.length == 0 ? null : bytes;
    } catch (IOException ex) {
      return null;
    }
  }

  private void notFound(ChannelHandlerContext ctx, FullHttpRequest req,
                        WebHandler.Recorder recorder) {
    recorder.status(404);
    Responses.send(ctx, req, HttpResponseStatus.NOT_FOUND, "text/plain; charset=utf-8",
        "not here\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
