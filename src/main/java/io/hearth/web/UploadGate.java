package io.hearth.web;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;

/**
 * The ceiling, decided from the request line rather than after the body has arrived.
 *
 * <b>This exists because uploads and everything else want different limits.</b> A form is a few
 * kilobytes and a megabyte is already generous; a photograph off a phone is twelve. Netty's
 * aggregator takes one number, so raising it for the upload screen would raise it for every path on
 * the server -- and then a stranger POSTing twenty-five megabytes to `/` would have all of it
 * buffered in memory before anything got the chance to refuse.
 *
 * So the aggregator is set to the larger number and this sits in front of it: it reads the path and
 * the declared length off the head of the request, and refuses anything over the *small* limit
 * unless it is going to the one path that takes uploads. Which means the large ceiling only ever
 * applies where an upload is expected, and everything else is refused before a byte of the body is
 * held.
 *
 * A request with no `Content-Length` -- chunked -- is let past, because there is nothing to check
 * yet; the aggregator's own ceiling still stops it, which is the honest fallback rather than a
 * guess.
 */
public class UploadGate extends ChannelInboundHandlerAdapter {
  private final int ordinaryLimit;
  private final String uploadPath;

  /**
   * @param ordinaryLimit what every path except the upload one may send
   * @param uploadPath the one path allowed the larger ceiling; a prefix, matched exactly on the
   *     path so a query string cannot smuggle anything past it
   */
  public UploadGate(int ordinaryLimit, String uploadPath) {
    this.ordinaryLimit = ordinaryLimit;
    this.uploadPath = uploadPath;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object message) {
    if (message instanceof HttpRequest request) {
      long declared = HttpUtil.getContentLength(request, -1L);
      String path = Forms.path(request.uri());
      if (declared > ordinaryLimit && !path.equals(uploadPath)) {
        ReferenceCountUtil.release(message);
        refuse(ctx);
        return;
      }
    }
    ctx.fireChannelRead(message);
  }

  private void refuse(ChannelHandlerContext ctx) {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
        HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
    response.headers().set(HttpHeaderNames.CONNECTION, "close");
    // closed rather than drained: the rest of a body this server has already refused is bytes it
    // has no reason to read
    ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
  }
}
