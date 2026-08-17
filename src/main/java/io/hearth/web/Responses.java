package io.hearth.web;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

/**
 * Response plumbing. Every byte that leaves this server goes out through here so that the
 * security headers are applied in exactly one place and cannot be forgotten by a new handler.
 */
public class Responses {
  public static final byte[] EMPTY = new byte[0];

  /**
   * Headers on every response, no exceptions.
   *
   * Strict-Transport-Security is deliberately absent while we are HTTP-only for developers --
   * sending HSTS over plaintext on localhost would pin a developer's browser to https for a port
   * that has no TLS behind it. It gets added when the SSL listener lands.
   */
  public static void addSecurityHeaders(HttpResponse res) {
    addSecurityHeaders(res, null);
  }

  /**
   * @param scriptNonce when set, exactly one inline script carrying this nonce may run. The register
   *     page builds its whole form in JavaScript, and 'unsafe-inline' would mean any script an
   *     injection managed to land could run too -- a nonce says "this one, that I minted for this
   *     response" and nothing else.
   */
  public static void addSecurityHeaders(HttpResponse res, String scriptNonce) {
    res.headers().set("X-Content-Type-Options", "nosniff");
    res.headers().set("Referrer-Policy", "no-referrer");
    res.headers().set("X-Frame-Options", "SAMEORIGIN");
    String contentType = res.headers().get(HttpHeaderNames.CONTENT_TYPE);
    if ("image/svg+xml".equals(contentType)) {
      res.headers().set("Content-Security-Policy", "script-src 'none'; frame-ancestors 'self'");
      return;
    }
    StringBuilder policy = new StringBuilder("default-src 'self'; style-src 'self' 'unsafe-inline'");
    // `'self'` is always here, and a nonce is added when the page carries an inline script.
    //
    // It used to be `'none'` when there was no nonce, which was wrong in a way that was invisible
    // until something depended on it: every script this server ships as a *file* -- the live
    // channel, the chat, a vendored library from /3rd -- is same-origin, and `'none'` refuses those
    // too. So the chat page loaded no JavaScript at all, the sidebar never drew, the composer was a
    // plain textarea and the bell never lit, on every page that did not happen to need a nonce for
    // something else.
    //
    // `'self'` is not a loosening. Same-origin here means "a file inside this jar", and the only
    // paths that serve one are /3rd and /~live. Inline is still nonce-only; `'unsafe-inline'`
    // remains the thing we will not do, and deliberately not `'strict-dynamic'`, which would let
    // any nonced script pull in anything from anywhere.
    policy.append("; script-src 'self'");
    if (scriptNonce != null) {
      policy.append(" 'nonce-").append(scriptNonce).append('\'');
    }
    // form-action and base-uri are not covered by default-src, and both close a path that stored
    // HTML injection would otherwise open: a form that looks like part of the site and posts
    // somewhere else, and a <base> that changes where every relative URL on the page points.
    // Defence in depth rather than the fix for stored injection; the safelist in Html is that.
    policy.append("; form-action 'self'; base-uri 'self'; frame-ancestors 'self'");
    res.headers().set("Content-Security-Policy", policy.toString());
  }

  /** send a complete response, honoring keep-alive only when the request actually succeeded */
  public static void send(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus status, String contentType, byte[] body) {
    send(ctx, req, status, contentType, body, null);
  }

  /**
   * As above, plus extra headers and HEAD handling.
   *
   * A HEAD response carries the Content-Length the matching GET would have produced but no body,
   * which is why the body is still computed and then dropped rather than short-circuited earlier.
   */
  public static void send(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus status, String contentType, byte[] body, String[] extraHeaders) {
    send(ctx, req, status, contentType, body, extraHeaders, null);
  }

  /** as above, with a nonce that authorises exactly one inline script on this page */
  public static void send(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus status,
                          String contentType, byte[] body, String[] extraHeaders, String scriptNonce) {
    boolean headOnly = HttpMethod.HEAD.equals(req.method());
    byte[] payload = headOnly ? EMPTY : body;
    FullHttpResponse res = new DefaultFullHttpResponse(responseVersion(req), status, Unpooled.wrappedBuffer(payload));
    HttpUtil.setContentLength(res, body.length);
    if (contentType != null) {
      res.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
    }
    res.headers().set(HttpHeaderNames.ACCEPT_RANGES, "none");
    if (extraHeaders != null) {
      for (int k = 0; k + 1 < extraHeaders.length; k += 2) {
        res.headers().set(extraHeaders[k], extraHeaders[k + 1]);
      }
    }
    addSecurityHeaders(res, scriptNonce);
    // only a success keeps the connection; anything else means we don't trust what comes next
    boolean keepAlive = HttpUtil.isKeepAlive(req) && status.code() == 200;
    HttpUtil.setKeepAlive(res, keepAlive);
    if (keepAlive) {
      ctx.writeAndFlush(res);
    } else {
      ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
    }
  }

  public static void sendHtml(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus status, byte[] body) {
    send(ctx, req, status, "text/html; charset=utf-8", body);
  }

  /**
   * Open a response that will be written to over time, and go on being written to.
   *
   * The live channel is the only thing here that is not request-and-answer, and this is where it
   * gets out -- through the same security headers as everything else, because invariant 4 is about
   * where bytes leave rather than about how many at a time.
   *
   * `Content-Encoding: identity` is load bearing. {@link io.netty.handler.codec.http.HttpContentCompressor}
   * sits in the pipeline and would otherwise gzip this, and a gzip stream buffers until it has
   * something worth compressing -- which for an event stream means every event arriving in a batch
   * some seconds late, or not at all. Setting the encoding tells the compressor this one is spoken
   * for. `X-Accel-Buffering` says the same thing to an nginx somebody put in front.
   */
  public static void beginStream(ChannelHandlerContext ctx, FullHttpRequest req,
                                 String contentType) {
    io.netty.handler.codec.http.HttpResponse res = new io.netty.handler.codec.http.DefaultHttpResponse(
        responseVersion(req), HttpResponseStatus.OK);
    res.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
    res.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store, no-transform");
    res.headers().set(HttpHeaderNames.CONTENT_ENCODING, "identity");
    res.headers().set("X-Accel-Buffering", "no");
    res.headers().set(HttpHeaderNames.ACCEPT_RANGES, "none");
    addSecurityHeaders(res, null);
    HttpUtil.setTransferEncodingChunked(res, true);
    ctx.writeAndFlush(res);
  }

  /** one more piece of an open stream; returns false once the connection has gone */
  public static boolean streamChunk(ChannelHandlerContext ctx, String text) {
    if (!ctx.channel().isActive()) {
      return false;
    }
    ctx.writeAndFlush(new io.netty.handler.codec.http.DefaultHttpContent(
        Unpooled.copiedBuffer(text, java.nio.charset.StandardCharsets.UTF_8)));
    return true;
  }

  /** finish a stream properly, so a client sees an end rather than a dropped socket */
  public static void endStream(ChannelHandlerContext ctx) {
    if (!ctx.channel().isActive()) {
      return;
    }
    ctx.writeAndFlush(io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT)
        .addListener(ChannelFutureListener.CLOSE);
  }

  /**
   * Netty will happily parse "HTTP/9.9" into a version object, and echoing it back would put a
   * token the client chose into our status line. We answer in a version we actually speak.
   */
  static HttpVersion responseVersion(FullHttpRequest req) {
    return isSupported(req.protocolVersion()) ? req.protocolVersion() : HttpVersion.HTTP_1_1;
  }

  /** the wire versions this server implements; HTTP/2 arrives with TLS and ALPN */
  public static boolean isSupported(HttpVersion version) {
    return HttpVersion.HTTP_1_1.equals(version) || HttpVersion.HTTP_1_0.equals(version);
  }
}
