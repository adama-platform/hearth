package io.hearth.web;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

/**
 * Was this request encrypted?
 *
 * The answer decides the scheme in a redirect back to ourselves, and getting it wrong in either
 * direction is bad in a different way: an https request sent to http strips the encryption off a
 * request that already had it, and an http request sent to https fails outright on a domain whose
 * certificate has not been issued yet.
 */
public class CanonicalSchemeTests {
  @Test
  public void aPlainConnectionIsPlain() {
    assertEquals("http", schemeOf(new EmbeddedChannel(), request(null)));
  }

  @Test
  public void aTlsHandlerInThePipelineIsTheHonestAnswer() throws Exception {
    SelfSignedCertificate cert = new SelfSignedCertificate();
    EmbeddedChannel channel = new EmbeddedChannel(
        SslContextBuilder.forServer(cert.certificate(), cert.privateKey()).build()
            .newHandler(io.netty.buffer.ByteBufAllocator.DEFAULT));
    assertEquals("https", schemeOf(channel, request(null)));
    cert.delete();
  }

  @Test
  public void aBalancerThatTerminatedTlsGetsASayWhenThereIsNoneHere() {
    // somebody else's claim rather than a fact, and trusted for exactly one thing: which of our own
    // two schemes to name in a redirect to ourselves
    assertEquals("https", schemeOf(new EmbeddedChannel(), request("https")));
    // netty refuses a header value with leading whitespace before this is ever asked, so the
    // trim here is belt and braces rather than the thing doing the work
    assertEquals("https", schemeOf(new EmbeddedChannel(), request("HTTPS")));
    assertEquals("http", schemeOf(new EmbeddedChannel(), request("http")));
    assertEquals("http", schemeOf(new EmbeddedChannel(), request("gopher")));
  }

  private static FullHttpRequest request(String forwardedProto) {
    FullHttpRequest req = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1, HttpMethod.GET, "/", Unpooled.EMPTY_BUFFER);
    if (forwardedProto != null) {
      req.headers().set("X-Forwarded-Proto", forwardedProto);
    }
    return req;
  }

  /** run one request through a handler so that a real ChannelHandlerContext is what answers */
  private static String schemeOf(EmbeddedChannel channel, FullHttpRequest req) {
    AtomicReference<String> seen = new AtomicReference<>();
    channel.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
      @Override
      protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        seen.set(Canonical.scheme(ctx, request));
      }
    });
    channel.writeInbound(req);
    channel.finishAndReleaseAll();
    return seen.get();
  }
}
