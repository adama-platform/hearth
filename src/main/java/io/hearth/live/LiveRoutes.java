package io.hearth.live;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.common.Verbose;
import io.hearth.vhost.DomainConfig;
import io.hearth.web.AccountRoutes;
import io.hearth.web.Forms;
import io.hearth.web.Responses;
import io.hearth.web.WebHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.ScheduledFuture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The live channel, over two transports and one script.
 *
 * <pre>
 *   GET /~live/sse?since=N     an event stream, held open
 *   GET /~live/poll?since=N    the same signals, one request at a time
 *   GET /~live/live.js         the shared client: the connection, the bell, the page updates
 * </pre>
 *
 * <b>Two transports because one of them is always broken somewhere.</b> Server-sent events are the
 * right answer and are supported everywhere that matters -- but they are the first thing a corporate
 * proxy buffers into uselessness, and a browser that has spent six seconds without a byte has no way
 * to tell a quiet community from a wedged connection. So the client tries the stream, and falls back
 * to a long poll that behaves identically from the outside: both return signals after a cursor, and
 * both are answered by the same hub.
 *
 * <b>Neither carries content.</b> A signal says a room moved. The client then fetches that room the
 * ordinary way, with its ordinary session, through the ordinary authorisation. That is what keeps
 * the live path from becoming a second and weaker way into the same data, and it is why a direct
 * message is safe on a shared channel: the signal for one is only sent to its two people, and even
 * if that were wrong, the fetch behind it would still refuse.
 *
 * <b>The read timeout comes off.</b> A held-open stream sends nothing inbound, and the pipeline's
 * `ReadTimeoutHandler` would close it every minute. It is removed for this connection only -- the
 * heartbeat below is what proves the connection is alive instead, and it does so in a way the
 * client can see rather than one only the server can.
 */
public class LiveRoutes {
  public static final String ROOT = "/~live";
  /** a comment down the stream this often, so a proxy sees traffic and the client sees life */
  private static final long HEARTBEAT_SECONDS = 20;
  /** a stream is retired after this long and the client reconnects; nothing lives forever */
  private static final long STREAM_MINUTES = 30;
  /** how long a long poll waits before answering with nothing */
  private static final long POLL_SECONDS = 25;
  /** how many streams one community may hold open at once */
  private static final int MAX_STREAMS = 400;

  private final Live live;
  private final Verbose verbose;

  public LiveRoutes(Live live, Verbose verbose) {
    this.live = live;
    this.verbose = verbose;
  }

  public static boolean owns(String path) {
    return path.equals(ROOT) || path.startsWith(ROOT + "/");
  }

  /** the script is the same public bytes for everybody, so it answers before anything else */
  public static boolean isScript(String path) {
    return path.equals(ROOT + "/live.js");
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    String path = Forms.path(req.uri());
    if (isScript(path)) {
      script(ctx, req, recorder, path);
      return;
    }
    SessionRecord session = accounts == null ? null : AccountRoutes.currentSession(accounts, req);
    if (session == null) {
      // the live channel is for members. A 204 rather than a redirect: this is fetched by a script,
      // and a sign-in page arriving where a JSON array was expected is a confusing way to say no.
      recorder.status(204);
      Responses.send(ctx, req, HttpResponseStatus.NO_CONTENT, null, Responses.EMPTY);
      return;
    }
    LiveHub hub = live.forDomain(config.domain);
    long userId = session.userId();
    // a request on this channel is somebody with the page open, which is the only activity signal
    // worth having: it is a person, not a crawler, and it is now rather than five minutes ago
    hub.beat(userId);
    accounts.sessions.active(session);

    if (path.equals(ROOT + "/poll")) {
      poll(hub, ctx, req, recorder, userId);
      return;
    }
    if (path.equals(ROOT + "/sse")) {
      stream(hub, ctx, req, recorder, userId);
      return;
    }
    recorder.status(404);
    Responses.send(ctx, req, HttpResponseStatus.NOT_FOUND, null, Responses.EMPTY);
  }

  // ---- server-sent events -----------------------------------------------------------------------

  private void stream(LiveHub hub, ChannelHandlerContext ctx, FullHttpRequest req,
                      WebHandler.Recorder recorder, long userId) {
    if (hub.connections() >= MAX_STREAMS) {
      // out of stream slots is not an error the person can do anything about, and the long poll
      // works without one; 503 is what tells the client to fall back rather than to retry forever
      verbose.detail("live: too many streams, telling the client to poll instead");
      recorder.status(503);
      Responses.send(ctx, req, HttpResponseStatus.SERVICE_UNAVAILABLE, "text/plain",
          "poll".getBytes(StandardCharsets.UTF_8));
      return;
    }
    long cursor = cursorOf(req);
    recorder.status(200);
    Responses.beginStream(ctx, req, "text/event-stream; charset=utf-8");
    // the pipeline closes a connection that has been quiet for a minute, which is every healthy
    // stream. The heartbeat below is the liveness check instead.
    if (ctx.pipeline().get(ReadTimeoutHandler.class) != null) {
      ctx.pipeline().remove(ReadTimeoutHandler.class);
    }

    AtomicBoolean closed = new AtomicBoolean();
    StreamWaiter waiter = new StreamWaiter(ctx, userId, closed);
    // subscribed before the backlog is sent, so nothing can happen in the gap. A signal arriving
    // twice is harmless: the client keeps a cursor and ignores anything at or below it.
    hub.addWaiter(waiter);
    send(ctx, "hello", "{\"head\":" + hub.head() + ",\"floor\":" + hub.floor() + "}");
    for (Signal signal : hub.since(cursor)) {
      waiter.wake(signal);
    }

    ScheduledFuture<?> beat = ctx.executor().scheduleAtFixedRate(() -> {
      if (closed.get() || !ctx.channel().isActive()) {
        return;
      }
      // a comment rather than an event: it keeps proxies and the client's own timer happy without
      // becoming something the client has to understand
      Responses.streamChunk(ctx, ": beat\n\n");
    }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

    ScheduledFuture<?> retire = ctx.executor().schedule(() -> {
      verbose.detail("live: retiring a stream after " + STREAM_MINUTES + " minutes");
      finish(ctx, hub, waiter, closed);
    }, STREAM_MINUTES, TimeUnit.MINUTES);

    ctx.channel().closeFuture().addListener(future -> {
      closed.set(true);
      beat.cancel(false);
      retire.cancel(false);
      hub.removeWaiter(waiter);
      hub.gone(userId);
    });
  }

  private void finish(ChannelHandlerContext ctx, LiveHub hub, StreamWaiter waiter,
                      AtomicBoolean closed) {
    if (closed.getAndSet(true)) {
      return;
    }
    hub.removeWaiter(waiter);
    // "retry: 1000" on the way out, so the browser comes back promptly rather than on its own
    // schedule, which some browsers make three seconds
    Responses.streamChunk(ctx, "retry: 1000\n\n");
    Responses.endStream(ctx);
  }

  private static void send(ChannelHandlerContext ctx, String event, String data) {
    Responses.streamChunk(ctx, "event: " + event + "\ndata: " + data + "\n\n");
  }

  /** one open stream; writes on the publishing thread, which is why it does nothing but write */
  private static final class StreamWaiter implements LiveHub.Waiter {
    private final ChannelHandlerContext ctx;
    private final long userId;
    private final AtomicBoolean closed;

    StreamWaiter(ChannelHandlerContext ctx, long userId, AtomicBoolean closed) {
      this.ctx = ctx;
      this.userId = userId;
      this.closed = closed;
    }

    @Override
    public void wake(Signal signal) {
      if (closed.get()) {
        return;
      }
      // the id line is what a browser sends back as Last-Event-ID when it reconnects, which is how
      // a dropped connection resumes without the client having to store anything
      Responses.streamChunk(ctx,
          "id: " + signal.seq() + "\nevent: signal\ndata: " + signal.json() + "\n\n");
    }

    @Override
    public long userId() {
      return userId;
    }
  }

  // ---- long poll ----------------------------------------------------------------------------------

  /**
   * The same answer, one request at a time.
   *
   * Answers immediately when there is already something after the cursor, and otherwise holds the
   * request until something happens or the timeout runs out. An empty array is a normal answer, not
   * a failure: the client comes straight back with the same cursor.
   */
  private void poll(LiveHub hub, ChannelHandlerContext ctx, FullHttpRequest req,
                    WebHandler.Recorder recorder, long userId) {
    long cursor = cursorOf(req);
    List<Signal> waiting = hub.since(cursor);
    if (!waiting.isEmpty()) {
      answer(ctx, req, recorder, hub, waiting);
      return;
    }
    // Recorded now rather than when the answer goes out. The access log is written in the
    // handler's `finally`, which runs the moment this method returns -- so a held request that
    // stamped its status later would appear in every dashboard as a 500, once per poll per member.
    recorder.status(200);
    // and the request is held past the end of the handler, so netty must not recycle it underneath
    // us. Released on every path out below.
    io.netty.util.ReferenceCountUtil.retain(req);
    AtomicBoolean answered = new AtomicBoolean();
    java.util.concurrent.ConcurrentLinkedQueue<Signal> collected =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    LiveHub.Waiter waiter = new LiveHub.Waiter() {
      @Override
      public void wake(Signal signal) {
        collected.add(signal);
        // give the publisher's thread back immediately and answer on the connection's own executor
        ctx.executor().execute(() -> {
          if (answered.compareAndSet(false, true)) {
            hub.removeWaiter(this);
            answer(ctx, req, recorder, hub, new java.util.ArrayList<>(collected));
            io.netty.util.ReferenceCountUtil.release(req);
          }
        });
      }

      @Override
      public long userId() {
        return userId;
      }
    };
    hub.addWaiter(waiter);
    ctx.executor().schedule(() -> {
      if (answered.compareAndSet(false, true)) {
        hub.removeWaiter(waiter);
        answer(ctx, req, recorder, hub, List.of());
        io.netty.util.ReferenceCountUtil.release(req);
      }
    }, POLL_SECONDS, TimeUnit.SECONDS);
    ctx.channel().closeFuture().addListener(future -> {
      if (answered.compareAndSet(false, true)) {
        hub.removeWaiter(waiter);
        io.netty.util.ReferenceCountUtil.release(req);
      }
    });
  }

  private void answer(ChannelHandlerContext ctx, FullHttpRequest req,
                      WebHandler.Recorder recorder, LiveHub hub, List<Signal> signals) {
    if (!ctx.channel().isActive()) {
      return;
    }
    StringBuilder out = new StringBuilder(256);
    out.append("{\"head\":").append(hub.head()).append(",\"floor\":").append(hub.floor())
        .append(",\"signals\":[");
    for (int k = 0; k < signals.size(); k++) {
      if (k > 0) {
        out.append(',');
      }
      out.append(signals.get(k).json());
    }
    out.append("]}");
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "application/json; charset=utf-8",
        out.toString().getBytes(StandardCharsets.UTF_8),
        new String[]{HttpHeaderNames.CACHE_CONTROL.toString(), "no-store"});
  }

  /**
   * Where this client got to.
   *
   * `since` when the client asked for one, and otherwise the `Last-Event-ID` the browser sends back
   * by itself after a dropped stream -- which is the whole reason each event carries an id.
   */
  private static long cursorOf(FullHttpRequest req) {
    String since = Forms.query(req.uri(), "since");
    if (since == null) {
      since = req.headers().get("Last-Event-ID");
    }
    if (since == null) {
      return Long.MAX_VALUE;
    }
    try {
      return Math.max(0, Long.parseLong(since.trim()));
    } catch (NumberFormatException ex) {
      // a cursor nobody can read means "start from now", which is what a fresh client wants
      return Long.MAX_VALUE;
    }
  }

  // ---- the scripts --------------------------------------------------------------------------------

  /**
   * The client, from inside the jar.
   *
   * One file per domain rather than one per page, and referenced by `src` rather than inlined:
   * `script-src 'self'` already allows it, so no page needs a nonce for it, and the browser caches
   * it once for the whole site instead of re-parsing it on every navigation. That is the same
   * argument as `/3rd`, applied to our own code.
   */
  private void script(ChannelHandlerContext ctx, FullHttpRequest req,
                      WebHandler.Recorder recorder, String path) {
    String name = path.substring(ROOT.length() + 1);
    byte[] body = read(name);
    if (body == null) {
      recorder.status(404);
      Responses.send(ctx, req, HttpResponseStatus.NOT_FOUND, null, Responses.EMPTY);
      return;
    }
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/javascript; charset=utf-8", body,
        new String[]{HttpHeaderNames.CACHE_CONTROL.toString(), "no-cache, must-revalidate"});
  }

  private static byte[] read(String name) {
    // the name is one of exactly two constants checked above, so this cannot be a traversal --
    // but the check is here as well, because a classpath lookup built from a request path is
    // precisely the shape that grows a third caller who forgot
    if (!name.equals("live.js")) {
      return null;
    }
    try (InputStream stream = LiveRoutes.class.getResourceAsStream("/live/" + name)) {
      return stream == null ? null : stream.readAllBytes();
    } catch (IOException ex) {
      return null;
    }
  }
}
