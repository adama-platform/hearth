package io.hearth.testkit;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The HTTP client the tests drive the server with.
 *
 * Two levels, because they answer different questions:
 *
 *   - the JDK's HttpClient, for "does a normal client get the right answer". It reuses
 *     connections, handles chunking, and behaves like a browser would.
 *   - a raw socket, for "what does the server do with bytes a normal client would never send" --
 *     a malformed request line, a missing Host, two requests pipelined on one connection, or a
 *     check that the socket actually closed.
 *
 * Setting Host explicitly is the whole point of a virtual hosting test, and the JDK client refuses
 * that header unless jdk.httpclient.allowRestrictedHeaders includes it. Surefire sets that in
 * pom.xml; {@link #hostHeaderIsSettable()} proves it took effect so a misconfigured build fails
 * loudly instead of silently testing one hostname.
 */
public class Http implements AutoCloseable {
  private final HttpClient client;

  public Http() {
    this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build();
  }

  /** true when the JDK client will let us set Host; false means the build lost its system property */
  public static boolean hostHeaderIsSettable() {
    try {
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/")).header("Host", "example.com").GET().build();
      return true;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  public Response get(int port, String host, String path) throws Exception {
    return send(port, host, "GET", path, null);
  }

  public Response head(int port, String host, String path) throws Exception {
    return send(port, host, "HEAD", path, null);
  }

  public Response send(int port, String host, String method, String path, byte[] body) throws Exception {
    return send(port, host, method, path, body, new String[0]);
  }

  /** the same, with headers a browser would not send: a bearer token, a content type of our own */
  public Response send(int port, String host, String method, String path, byte[] body,
                       String... headers) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(10));
    if (host != null) {
      builder.header("Host", host);
    }
    for (int k = 0; k + 1 < headers.length; k += 2) {
      builder.header(headers[k], headers[k + 1]);
    }
    HttpRequest.BodyPublisher publisher = body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofByteArray(body);
    builder.method(method, publisher);
    HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    return new Response(response);
  }

  /**
   * Write raw bytes to the port and read everything back until close or a quiet socket. Used for
   * requests the JDK client will not produce.
   */
  public static String raw(int port, String request, int readTimeoutMs) throws Exception {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
      socket.setSoTimeout(readTimeoutMs);
      OutputStream out = socket.getOutputStream();
      out.write(request.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      return readAll(socket.getInputStream());
    }
  }

  public static String raw(int port, String request) throws Exception {
    return raw(port, request, 3000);
  }

  private static String readAll(InputStream in) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[4096];
    try {
      int read;
      while ((read = in.read(chunk)) > 0) {
        buffer.write(chunk, 0, read);
      }
    } catch (java.net.SocketTimeoutException ex) {
      // the server is keeping the connection open; whatever arrived is the answer
    } catch (java.io.IOException ex) {
      // peer closed mid-read; same deal
    }
    return buffer.toString(StandardCharsets.ISO_8859_1);
  }

  /** how many status lines came back, i.e. how many requests the server answered on one socket */
  public static int countResponses(String raw) {
    int count = 0;
    int at = 0;
    while ((at = raw.indexOf("HTTP/1.1 ", at)) >= 0) {
      count++;
      at += 9;
    }
    return count;
  }

  @Override
  public void close() {
    // HttpClient has no close() before Java 21's AutoCloseable variant; letting it go is fine here
  }

  /** what came back, in the shape assertions want */
  public static class Response {
    public final int status;
    public final String body;
    public final byte[] bytes;
    private final HttpResponse<byte[]> raw;

    Response(HttpResponse<byte[]> raw) {
      this.raw = raw;
      this.status = raw.statusCode();
      this.bytes = raw.body();
      this.body = new String(raw.body(), StandardCharsets.UTF_8);
    }

    public String header(String name) {
      Optional<String> value = raw.headers().firstValue(name);
      return value.orElse(null);
    }

    public List<String> headers(String name) {
      return raw.headers().allValues(name);
    }

    public boolean bodyContains(String needle) {
      return body.contains(needle);
    }
  }
}
