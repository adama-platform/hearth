package io.hearth.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A connector, as a test client.
 *
 * It does what a model vendor's connector does and nothing more convenient: registers itself,
 * bounces a human through the consent screen, redeems a code with a PKCE verifier, and then speaks
 * JSON-RPC with a bearer token. There is no shortcut that reaches into Sessions to mint a token,
 * because the flow is most of what is being tested -- a test that skipped it would pass just as
 * happily with the authorization checks deleted.
 *
 * The one thing it fakes is being a browser at the redirect: it reads the code out of the Location
 * header instead of navigating there, since the far side is grok.com and this is a unit test.
 */
public class McpClient {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SecureRandom RANDOM = new SecureRandom();

  private final int port;
  private final String host;
  private final String root;
  private final HttpClient http;
  private final AtomicInteger nextId = new AtomicInteger();

  private String clientId;
  private String token;
  private String verifier;

  public McpClient(int port, String host) {
    this(port, host, "/mcp");
  }

  public McpClient(int port, String host, String root) {
    this.port = port;
    this.host = host;
    this.root = root;
    this.http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  public String clientId() {
    return clientId;
  }

  public String token() {
    return token;
  }

  /** pretend a token was issued for us; for testing what happens with a token that is not ours */
  public McpClient withToken(String token) {
    this.token = token;
    return this;
  }

  // ---- the flow ----------------------------------------------------------------------------------

  /** RFC 7591 dynamic registration */
  public Response register(String name, String redirectUri) throws Exception {
    ObjectNode body = JSON.createObjectNode();
    body.put("client_name", name);
    body.putArray("redirect_uris").add(redirectUri);
    Response response = post(root + "/register", "application/json", body.toString());
    if (response.status() == 201) {
      clientId = response.json().get("client_id").asText();
    }
    return response;
  }

  /**
   * Walk a signed-in admin through the consent screen and come back with a code.
   *
   * The browser is the one carrying the session cookie, which is the point: the authorization step
   * belongs to a person, and this client never sees their credentials.
   */
  public String authorize(Browser admin, String redirectUri, String state) throws Exception {
    verifier = randomVerifier();
    String challenge = challengeFor(verifier);
    String url = root + "/authorize?response_type=code&client_id=" + enc(clientId)
        + "&redirect_uri=" + enc(redirectUri)
        + "&code_challenge=" + enc(challenge)
        + "&code_challenge_method=S256"
        + (state == null ? "" : "&state=" + enc(state));

    Browser.Page consent = admin.get(url);
    if (consent.status() != 200) {
      return null;
    }
    Browser.Page redirected = admin.submitTo(root + "/authorize", Map.of(
        "client_id", clientId,
        "redirect_uri", redirectUri,
        "state", state == null ? "" : state,
        "code_challenge", challenge,
        "code_challenge_method", "S256",
        "scope", "",
        "approve", "1"));
    return codeFrom(redirected.location());
  }

  /** the consent screen itself, for asserting on what it promises */
  public Browser.Page consentPage(Browser admin, String redirectUri) throws Exception {
    verifier = randomVerifier();
    return admin.get(root + "/authorize?response_type=code&client_id=" + enc(clientId)
        + "&redirect_uri=" + enc(redirectUri)
        + "&code_challenge=" + enc(challengeFor(verifier))
        + "&code_challenge_method=S256");
  }

  /** redeem a code; the token is kept for later calls when it works */
  public Response redeem(String code, String redirectUri) throws Exception {
    return redeem(code, redirectUri, verifier);
  }

  public Response redeem(String code, String redirectUri, String withVerifier) throws Exception {
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("code", code == null ? "" : code);
    form.put("client_id", clientId == null ? "" : clientId);
    form.put("redirect_uri", redirectUri);
    form.put("code_verifier", withVerifier == null ? "" : withVerifier);
    Response response = post(root + "/token", "application/x-www-form-urlencoded", encode(form));
    if (response.status() == 200 && response.json().hasNonNull("access_token")) {
      token = response.json().get("access_token").asText();
    }
    return response;
  }

  /** the whole dance, for the tests whose subject is what happens afterwards */
  public McpClient connect(Browser admin, String redirectUri) throws Exception {
    if (clientId == null) {
      register("Test Connector", redirectUri);
    }
    String code = authorize(admin, redirectUri, "xyz");
    redeem(code, redirectUri);
    return this;
  }

  // ---- JSON-RPC ------------------------------------------------------------------------------------

  public Response rpc(String method, ObjectNode params) throws Exception {
    ObjectNode body = JSON.createObjectNode();
    body.put("jsonrpc", "2.0");
    body.put("id", nextId.incrementAndGet());
    body.put("method", method);
    if (params != null) {
      body.set("params", params);
    }
    return post(root, "application/json", body.toString());
  }

  public Response initialize() throws Exception {
    ObjectNode params = JSON.createObjectNode();
    params.put("protocolVersion", "2025-06-18");
    params.putObject("capabilities");
    params.putObject("clientInfo").put("name", "test-connector");
    return rpc("initialize", params);
  }

  public Response listTools() throws Exception {
    return rpc("tools/list", null);
  }

  /** call a tool; arguments are name/value pairs, strings unless they parse as something else */
  public Response call(String tool, Object... pairs) throws Exception {
    ObjectNode params = JSON.createObjectNode();
    params.put("name", tool);
    ObjectNode arguments = params.putObject("arguments");
    for (int k = 0; k + 1 < pairs.length; k += 2) {
      String key = String.valueOf(pairs[k]);
      Object value = pairs[k + 1];
      if (value instanceof Boolean flag) {
        arguments.put(key, flag);
      } else if (value instanceof Integer number) {
        arguments.put(key, number);
      } else if (value instanceof Long number) {
        arguments.put(key, number);
      } else if (value instanceof JsonNode json) {
        // Structured arguments have to arrive as structure. Everything else here stringifies, and
        // an object argument turned into "{a=b}" would be a test proving the server copes with a
        // shape no real client sends -- which is how a tool advertising an object parameter went
        // years being silently unusable without a single test noticing.
        arguments.set(key, json);
      } else {
        arguments.put(key, String.valueOf(value));
      }
    }
    return rpc("tools/call", params);
  }

  // ---- transport -------------------------------------------------------------------------------------

  private Response post(String path, String contentType, String body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(10))
        .header("Host", host)
        .header("Content-Type", contentType)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    return new Response(response.statusCode(), response.body(),
        response.headers().firstValue("www-authenticate").orElse(null));
  }

  /** one answer, with the JSON already parsed and the MCP shape unwrapped */
  public record Response(int status, String body, String wwwAuthenticate) {
    public JsonNode json() {
      try {
        return JSON.readTree(body);
      } catch (Exception ex) {
        return JSON.createObjectNode();
      }
    }

    /** the `result` of a JSON-RPC answer */
    public JsonNode result() {
      return json().path("result");
    }

    /**
     * The text a tool returned, parsed back into JSON.
     *
     * MCP wraps a tool result in a content array of typed blocks; every tool here answers with one
     * text block holding JSON, so this is what a caller actually wants to assert against.
     */
    public JsonNode toolResult() {
      String text = result().path("content").path(0).path("text").asText("");
      try {
        return JSON.readTree(text);
      } catch (Exception ex) {
        return JSON.createObjectNode().put("text", text);
      }
    }

    public boolean isToolError() {
      return result().path("isError").asBoolean(false);
    }

    public String refusal() {
      return toolResult().path("refused").asText("");
    }

    public String rpcError() {
      return json().path("error").path("message").asText("");
    }

    public boolean contains(String text) {
      return body != null && body.contains(text);
    }
  }

  // ---- PKCE ----------------------------------------------------------------------------------------

  public static String randomVerifier() {
    byte[] bytes = new byte[42];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String challengeFor(String verifier) throws Exception {
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }

  private static String codeFrom(String location) {
    if (location == null) {
      return null;
    }
    for (String pair : location.substring(location.indexOf('?') + 1).split("&")) {
      if (pair.startsWith("code=")) {
        return java.net.URLDecoder.decode(pair.substring(5), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static String enc(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String encode(Map<String, String> fields) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> field : fields.entrySet()) {
      if (sb.length() > 0) {
        sb.append('&');
      }
      sb.append(enc(field.getKey())).append('=').append(enc(field.getValue()));
    }
    return sb.toString();
  }
}
