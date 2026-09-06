package io.hearth.hevy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;

/**
 * Hevy, without Hevy.
 *
 * A {@link HttpClient} that answers whatever it was told to and remembers what it was asked, so a
 * test can assert the two things this server is actually responsible for: that the key goes in the
 * `api-key` header and nowhere else, and that a request which should have been refused never left
 * at all.
 *
 * Deliberately not a mock of Hevy's behaviour. Asserting that their API accepts what this sends
 * needs their server; a stub that agrees with whatever the code happens to do would prove only that
 * the code agrees with itself, which is why that gap is written down under "not verified" instead.
 */
final class StubHevy extends HttpClient {
  private final int status;
  private final String body;
  private volatile HttpRequest last;

  private StubHevy(int status, String body) {
    this.status = status;
    this.body = body;
  }

  static StubHevy answering(int status, String body) {
    return new StubHevy(status, body);
  }

  String lastUri() {
    return last == null ? null : last.uri().toString();
  }

  String lastHeader(String name) {
    return last == null ? null : last.headers().firstValue(name).orElse(null);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    this.last = request;
    return (HttpResponse<T>) new Answer(request, status, body);
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

  /** one answer, with only the parts anything here reads filled in */
  private record Answer(HttpRequest request, int status, String body)
      implements HttpResponse<String> {
    @Override
    public int statusCode() {
      return status;
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
      BiPredicate<String, String> all = (a, b) -> true;
      return HttpHeaders.of(Map.of("content-type", List.of("application/json")), all);
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
