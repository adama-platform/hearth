package io.hearth.web;

import io.hearth.common.Verbose;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpUtil;

import java.nio.charset.StandardCharsets;

/**
 * A listener whose entire job is to say "use HTTPS instead".
 *
 * This exists because of load balancers. A balancer terminating TLS itself still wants somewhere to
 * send plain HTTP so that a person who typed `http://` gets bounced rather than refused -- and it
 * cannot be port 80 here, because port 80 is answering ACME challenges and must keep working for
 * anything that has not got a certificate yet. So this is a separate, deliberately tiny port that
 * does exactly one thing.
 *
 * It knows nothing. No virtual host lookup, no session, no access log, no template. It reads the
 * Host header and the path, and answers 308 with the same request under https. That is a security
 * property as much as a design one: this port is reachable by anything that can reach the machine,
 * and there is nothing behind it to reach.
 *
 * **308 rather than 301.** A permanent redirect that preserves the method and body -- a browser
 * following a 301 after a POST turns it into a GET and silently drops the form. Nobody should lose
 * a comment because they typed the wrong scheme.
 */
public class BounceHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
  /** the port to send people to; 443 is elided from the URL because it is the default */
  private final int httpsPort;
  private final Verbose verbose;

  public BounceHandler(int httpsPort, Verbose verbose) {
    this.httpsPort = httpsPort;
    this.verbose = verbose;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
    String host = req.headers().get(HttpHeaderNames.HOST);
    String target = location(host, req.uri(), httpsPort);
    if (target == null) {
      // no usable Host means no domain to send them to, and this port has nothing else to offer
      verbose.detail("bounce: no usable Host header -> 400");
      send(ctx, req, HttpResponseStatus.BAD_REQUEST, null);
      return;
    }
    verbose.detail(() -> "bounce: " + req.uri() + " -> " + target);
    send(ctx, req, HttpResponseStatus.PERMANENT_REDIRECT, target);
  }

  /**
   * Where to send them.
   *
   * The Host header is untrusted here as everywhere else, and this one goes straight into a Location
   * header -- so anything that could split the response or point at another site is refused rather
   * than sanitized. The port is stripped from the host because the incoming port is this bounce
   * port, which is not where anybody should be sent.
   */
  static String location(String rawHost, String uri, int httpsPort) {
    if (rawHost == null || rawHost.isEmpty() || rawHost.length() > 255) {
      return null;
    }
    String host = rawHost.trim();
    int colon = host.lastIndexOf(':');
    if (colon > 0 && host.indexOf(']') < colon) {
      host = host.substring(0, colon);
    }
    if (host.isEmpty()) {
      return null;
    }
    for (int k = 0; k < host.length(); k++) {
      char ch = host.charAt(k);
      boolean allowed = ch == '.' || ch == '-' || ch == '_' || ch == ':' || ch == '[' || ch == ']'
          || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
      if (!allowed) {
        return null;
      }
    }
    String path = uri == null || uri.isEmpty() ? "/" : uri;
    if (path.charAt(0) != '/') {
      // an absolute-form request line, or something stranger; either way not ours to rewrite
      return null;
    }
    for (int k = 0; k < path.length(); k++) {
      char ch = path.charAt(k);
      if (ch == '\r' || ch == '\n' || ch < 0x20) {
        return null;
      }
    }
    String port = httpsPort == 443 ? "" : ":" + httpsPort;
    return "https://" + host + port + path;
  }

  private void send(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus status,
                    String location) {
    byte[] body = location == null
        ? "This port only redirects to https.\n".getBytes(StandardCharsets.UTF_8)
        : ("Use " + location + "\n").getBytes(StandardCharsets.UTF_8);
    FullHttpResponse response = new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
    if (location != null) {
      response.headers().set(HttpHeaderNames.LOCATION, location);
    }
    // nothing here is worth caching, and a cached redirect to a port somebody later changes is a
    // support ticket that outlives the change
    response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
    boolean keepAlive = HttpUtil.isKeepAlive(req);
    if (keepAlive) {
      response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
      ctx.writeAndFlush(response);
    } else {
      ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    ctx.close();
  }
}
