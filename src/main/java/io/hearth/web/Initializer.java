package io.hearth.web;

import io.hearth.analytics.AccessLog;
import io.hearth.auth.AuthSystem;
import io.hearth.common.Verbose;
import io.hearth.vhost.DomainTree;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;

import java.util.concurrent.TimeUnit;

/**
 * Builds the pipeline for each accepted connection.
 *
 * Today this is plain HTTP/1.1 for developers. The shape follows Adama's Initializer so the two
 * pieces that come next drop into known places:
 *
 *   SSL -- an SniHandler in front of everything, resolving a certificate per domain from the same
 *          domain tree this class already holds. SNI virtual hosting and config virtual hosting
 *          want the same lookup, which is why the tree is passed in rather than a single config.
 *
 *   HTTP/2 -- an ApplicationProtocolNegotiationHandler after SNI; on h2 the pipeline becomes
 *          Http2FrameCodec + Http2MultiplexHandler with Http2StreamFrameToHttpObjectCodec, so
 *          WebHandler keeps working unchanged against FullHttpRequest.
 *
 * netty-codec-http2 is already a dependency for exactly that reason.
 */
public class Initializer extends ChannelInitializer<SocketChannel> {
  private final WebConfig webConfig;
  private final DomainTree domains;
  private final AuthSystem auth;
  private final Pages pages;
  private final AccountRoutes accounts;
  private final AdminRoutes admin;
  private final SelfRoutes self;
  private final io.hearth.mcp.McpRoutes mcp;
  private final io.hearth.api.ApiRoutes api;
  private final io.hearth.availability.AvailabilityRoutes availability;
  private final io.hearth.attach.AttachmentRoutes attachments;
  private final io.hearth.certs.Challenges challenges;
  private final AccessLog accessLog;
  /** non-null on the TLS listener; null on plain HTTP */
  private final io.hearth.board.BoardRoutes board;
  private final io.hearth.calendar.CalendarRoutes calendar;
  private final PwaRoutes pwa;
  private final io.hearth.places.PlaceRoutes places;
  private final io.hearth.legal.LegalRoutes legal;
  private final io.hearth.people.MemberRoutes members;
  private final io.hearth.people.SurveyRoutes survey;
  private final io.hearth.tasks.TaskRoutes tasks;
  private final HomeRoutes dashboard;
  private final io.hearth.people.OrientationRoutes welcome;
  private final io.hearth.live.LiveRoutes liveRoutes;
  private final io.hearth.certs.TlsContexts tls;
  private final Verbose verbose;

  public Initializer(WebConfig webConfig, DomainTree domains, AuthSystem auth, Pages pages,
                     AccountRoutes accounts, AdminRoutes admin, SelfRoutes self,
                     io.hearth.mcp.McpRoutes mcp, io.hearth.api.ApiRoutes api,
                   io.hearth.availability.AvailabilityRoutes availability,
                   io.hearth.attach.AttachmentRoutes attachments,
                   io.hearth.board.BoardRoutes board,
                    io.hearth.calendar.CalendarRoutes calendar,
                    PwaRoutes pwa,
                    io.hearth.places.PlaceRoutes places,
                    io.hearth.legal.LegalRoutes legal,
                    io.hearth.people.MemberRoutes members,
                    io.hearth.people.SurveyRoutes survey,
                    io.hearth.tasks.TaskRoutes tasks,
                    HomeRoutes dashboard,
                    io.hearth.people.OrientationRoutes welcome,
                    io.hearth.live.LiveRoutes liveRoutes,
                     io.hearth.certs.Challenges challenges,
                     AccessLog accessLog, Verbose verbose, io.hearth.certs.TlsContexts tls) {
    this.webConfig = webConfig;
    this.domains = domains;
    this.auth = auth;
    this.pages = pages;
    this.accounts = accounts;
    this.admin = admin;
    this.self = self;
    this.mcp = mcp;
    this.api = api;
    this.availability = availability;
    this.attachments = attachments;
    this.board = board;
    this.calendar = calendar;
    this.pwa = pwa;
    this.places = places;
    this.legal = legal;
    this.members = members;
    this.survey = survey;
    this.tasks = tasks;
    this.dashboard = dashboard;
    this.welcome = welcome;
    this.liveRoutes = liveRoutes;
    this.challenges = challenges;
    this.accessLog = accessLog;
    this.verbose = verbose;
    this.tls = tls;
  }

  @Override
  protected void initChannel(SocketChannel ch) {
    verbose.say(() -> "accepted connection from " + ch.remoteAddress());
    ChannelPipeline pipeline = ch.pipeline();
    pipeline.addLast(new ReadTimeoutHandler(webConfig.idleReadSeconds, TimeUnit.SECONDS));
    if (tls == null) {
      configureHttp1(pipeline);
      return;
    }
    // SNI first: the client names the host it wants during the handshake, and that is how one
    // process serves several communities on one address with a certificate each. The mapping is
    // consulted per handshake rather than captured here, so a renewal months from now is picked
    // up without a restart.
    pipeline.addLast(new SniHandler(tls));
    if (!webConfig.http2) {
      configureHttp1(pipeline);
      return;
    }
    // Which protocol was agreed is decided inside the handshake, so the rest of the pipeline
    // cannot be built until it finishes. HTTP/1.1 is the default for anything that did not ask.
    pipeline.addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
      @Override
      protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
          configureHttp2(ctx.pipeline());
        } else {
          configureHttp1(ctx.pipeline());
        }
      }
    });
  }

  /**
   * HTTP/2, as a stream of ordinary requests.
   *
   * Each stream becomes its own channel, and {@link Http2StreamFrameToHttpObjectCodec} turns its
   * frames into the same FullHttpRequest the HTTP/1.1 path produces -- so {@link WebHandler} is
   * reached unchanged and there is exactly one implementation of what a request means. A second
   * handler for the second protocol would be two places to fix every bug, and the one that gets
   * fixed is always the one somebody is looking at.
   */
  private void configureHttp2(ChannelPipeline pipeline) {
    pipeline.addLast(Http2FrameCodecBuilder.forServer().build());
    pipeline.addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
      @Override
      protected void initChannel(Channel stream) {
        stream.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true));
        stream.pipeline().addLast(new UploadGate(webConfig.maxContentLengthSize,
            io.hearth.attach.AttachmentRoutes.UPLOAD));
        stream.pipeline().addLast(new HttpObjectAggregator(webConfig.uploadCeiling()));
        stream.pipeline().addLast(new HttpContentCompressor());
        stream.pipeline().addLast(new WebHandler(domains, auth, pages, accounts, admin, self, mcp, api, availability, attachments,
            board, calendar, pwa, places, legal, members, survey, tasks, dashboard, welcome, liveRoutes, challenges, accessLog, verbose));
      }
    }));
  }

  /** the HTTP/1.1 chain; also the ALPN fallback once TLS is in place */
  private void configureHttp1(ChannelPipeline pipeline) {
    pipeline.addLast(new HttpServerCodec());
    // The ceiling is the larger of the two numbers, and this is what makes that safe: anything
    // over the ordinary limit is refused from the request line unless it is going to the upload
    // path, so the large ceiling only ever applies where an upload is expected.
    pipeline.addLast(new UploadGate(webConfig.maxContentLengthSize,
        io.hearth.attach.AttachmentRoutes.UPLOAD));
    pipeline.addLast(new HttpObjectAggregator(webConfig.uploadCeiling()));
    pipeline.addLast(new HttpContentCompressor());
    pipeline.addLast(new WebHandler(domains, auth, pages, accounts, admin, self, mcp, api, availability, attachments, board, calendar, pwa, places, legal, members, survey, tasks, dashboard, welcome, liveRoutes, challenges, accessLog, verbose));
  }
}
