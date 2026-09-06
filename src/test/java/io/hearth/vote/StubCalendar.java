package io.hearth.vote;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A calendar server that is not on the internet.
 *
 * Enough of {@link HttpClient} to hand back one ICS file. A test that reached a real calendar to
 * prove a weighting would be testing somebody else's server and would fail on a train.
 */
final class StubCalendar extends HttpClient {
  private final String body;

  private StubCalendar(String body) {
    this.body = body;
  }

  static StubCalendar answering(String ics) {
    return new StubCalendar(ics);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    return (HttpResponse<T>) new Answer(request, body);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    return CompletableFuture.completedFuture(send(request, handler));
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    return sendAsync(request, handler);
  }

  @Override
  public Optional<java.net.CookieHandler> cookieHandler() {
    return Optional.empty();
  }

  @Override
  public Optional<java.time.Duration> connectTimeout() {
    return Optional.empty();
  }

  @Override
  public Redirect followRedirects() {
    return Redirect.NEVER;
  }

  @Override
  public Optional<java.net.ProxySelector> proxy() {
    return Optional.empty();
  }

  @Override
  public javax.net.ssl.SSLContext sslContext() {
    try {
      return javax.net.ssl.SSLContext.getDefault();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Override
  public javax.net.ssl.SSLParameters sslParameters() {
    return new javax.net.ssl.SSLParameters();
  }

  @Override
  public Optional<java.net.Authenticator> authenticator() {
    return Optional.empty();
  }

  @Override
  public Version version() {
    return Version.HTTP_1_1;
  }

  @Override
  public Optional<java.util.concurrent.Executor> executor() {
    return Optional.empty();
  }

  private record Answer(HttpRequest request, String body) implements HttpResponse<String> {
    @Override
    public int statusCode() {
      return 200;
    }

    @Override
    public HttpRequest request() {
      return request;
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(Map.of("content-type", List.of("text/calendar")), (a, b) -> true);
    }

    @Override
    public String body() {
      return body;
    }

    @Override
    public Optional<javax.net.ssl.SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }
}
