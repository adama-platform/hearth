package io.hearth.web;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.ssl.SslHandler;

/**
 * A community has one address, and everything else points at it.
 *
 * `www.example.org`, `blog.example.org` and whatever a wildcard swept up all resolve to the same
 * config -- the same database, the same accounts, the same pages. Serving them under every name they
 * arrived under is how a community ends up with two of everything: two sets of links people paste to
 * each other, two entries in a search engine, and two host-scoped session cookies, so signing in at
 * one leaves you signed out at the other. That last one is not a tidiness problem; it is somebody
 * being asked to sign in again for no reason they can see.
 *
 * So a subdomain answers `308` to the same path on the primary domain, and it keeps three things:
 *
 *   - the <b>path and query</b>, because the point of a redirect is that the link still works;
 *   - the <b>scheme</b>, because sending an https request to http would strip the encryption from a
 *     request that already had it, and sending an http request to https would fail on a domain whose
 *     certificate has not been issued yet;
 *   - the <b>port</b>, because a developer on 8080 who gets sent to 443 has no idea what happened.
 *
 * <b>308 rather than 301.</b> A permanent redirect that preserves the method and the body -- a
 * browser following a 301 after a POST turns it into a GET and silently drops the form. Same reason
 * {@link BounceHandler} uses it.
 *
 * The ACME challenge, the invitation pixel and `/3rd` are all answered earlier in {@link WebHandler}
 * and so are never redirected. That ordering is load bearing for the first of them: an authority
 * validating `www.example.org` fetches the token *from that name*, and a redirect is not an answer.
 */
public final class Canonical {
  private Canonical() {
  }

  /**
   * Was this request encrypted?
   *
   * The TLS handler is the honest answer and is checked first -- on HTTP/2 it sits on the parent
   * channel, because each stream is its own child channel with a pipeline of its own. Only when
   * there is no TLS here at all does `X-Forwarded-Proto` get a say, which is for the load balancer
   * case: it terminates TLS and this server never sees a certificate. That header is somebody
   * else's claim rather than a fact, and it is trusted for exactly one thing -- which of our own two
   * schemes to name in a redirect back to ourselves.
   */
  public static String scheme(ChannelHandlerContext ctx, FullHttpRequest req) {
    if (encrypted(ctx.channel())) {
      return "https";
    }
    String forwarded = req.headers().get("X-Forwarded-Proto");
    if (forwarded != null && forwarded.trim().equalsIgnoreCase("https")) {
      return "https";
    }
    return "http";
  }

  private static boolean encrypted(Channel channel) {
    for (Channel at = channel; at != null; at = at.parent()) {
      if (at.pipeline().get(SslHandler.class) != null) {
        return true;
      }
    }
    return false;
  }

  /**
   * Where a request that arrived on the wrong name should go, or null when it cannot be said safely.
   *
   * The Host header is untrusted input and this puts part of it -- the port -- into a `Location`
   * header, so anything that is not plainly a port number is dropped rather than repaired. The host
   * itself is never taken from the request: it is the domain out of the config, which is the whole
   * point.
   */
  public static String location(String scheme, String domain, String rawHost, String uri) {
    if (domain == null || domain.isEmpty()) {
      return null;
    }
    String path = uri == null || uri.isEmpty() ? "/" : uri;
    if (path.charAt(0) != '/') {
      // an absolute-form request line, or something stranger; not ours to rewrite
      return null;
    }
    for (int k = 0; k < path.length(); k++) {
      if (path.charAt(k) < 0x20 || path.charAt(k) == 0x7f) {
        return null;
      }
    }
    return scheme + "://" + domain + portOf(rawHost) + path;
  }

  /** ":8080", or an empty string when the request named no port or named one we will not echo */
  private static String portOf(String rawHost) {
    if (rawHost == null) {
      return "";
    }
    int colon = rawHost.lastIndexOf(':');
    if (colon < 0 || rawHost.indexOf(']') > colon) {
      return "";
    }
    String port = rawHost.substring(colon + 1).trim();
    if (port.isEmpty() || port.length() > 5) {
      return "";
    }
    for (int k = 0; k < port.length(); k++) {
      if (port.charAt(k) < '0' || port.charAt(k) > '9') {
        return "";
      }
    }
    return ":" + port;
  }
}
