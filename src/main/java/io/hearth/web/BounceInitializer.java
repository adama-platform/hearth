package io.hearth.web;

import io.hearth.common.Verbose;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.ReadTimeoutHandler;

import java.util.concurrent.TimeUnit;

/**
 * The pipeline for the bounce port: read a request line, answer a redirect, and nothing else.
 *
 * Deliberately not the main pipeline with a flag. No compression, no virtual host resolution, no
 * access log, no route table -- because none of it is needed to write a Location header, and every
 * one of them is a thing that could go wrong on a port whose whole value is that it always works.
 *
 * The aggregator is capped at 8KB rather than the server's 1MB. This port never reads a body it
 * cares about, so anything larger is either a mistake or somebody probing, and there is no reason
 * to hold it in memory while deciding to redirect.
 */
public class BounceInitializer extends ChannelInitializer<SocketChannel> {
  private static final int MAX_REQUEST = 8 * 1024;
  private static final int IDLE_SECONDS = 15;

  private final int httpsPort;
  private final Verbose verbose;

  public BounceInitializer(int httpsPort, Verbose verbose) {
    this.httpsPort = httpsPort;
    this.verbose = verbose;
  }

  @Override
  protected void initChannel(SocketChannel ch) {
    ch.pipeline().addLast(new ReadTimeoutHandler(IDLE_SECONDS, TimeUnit.SECONDS));
    ch.pipeline().addLast(new HttpServerCodec());
    ch.pipeline().addLast(new HttpObjectAggregator(MAX_REQUEST));
    ch.pipeline().addLast(new BounceHandler(httpsPort, verbose));
  }
}
