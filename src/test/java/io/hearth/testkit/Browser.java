package io.hearth.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A test client that behaves like a browser: it keeps cookies, runs what the page's script would
 * have done, and does not follow redirects on its own.
 *
 * The account pages have no form in their HTML -- it is assembled in JavaScript from a JSON blob,
 * with field names that differ on every page load. So this client does what that script does: read
 * the blob, translate logical field names into the opaque ones the server minted, compute the proof,
 * and report interaction counts. A test therefore exercises the real protocol, including the parts
 * designed to be annoying, rather than a bypass.
 *
 * Everything the script does is also something a test may want to NOT do -- {@link #withoutProof()}
 * and {@link #withoutSignals()} are how the refusals get tested.
 *
 * Redirects are deliberately not followed. Where a flow sends somebody is part of what is being
 * tested, so the 303 and its Location are the assertion, not a step to skip past.
 */
public class Browser {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern HIDDEN = Pattern.compile(
      "<input[^>]*type=\"hidden\"[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"[^>]*>");
  private static final Pattern FORM_ACTION = Pattern.compile("<form[^>]*action=\"([^\"]+)\"");
  private static final Pattern MINT_BLOB = Pattern.compile(
      "<script type=\"application/json\" id=\"mint\">(.*?)</script>", Pattern.DOTALL);

  private final HttpClient client;
  private final int port;
  private final String host;
  private final TreeMap<String, String> cookies = new TreeMap<>();
  private Page current;
  private boolean sendProof = true;
  private String signals = "m:24|k:37|t:0|p:4|s:2|f:3|e:9100";
  private String trapValue = "";

  public Browser(int port, String host) {
    this.port = port;
    this.host = host;
    this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build();
  }

  /** stop computing the proof, as a client that never ran the script would */
  public Browser withoutProof() {
    this.sendProof = false;
    return this;
  }

  /** report no interaction at all, as an automated poster would */
  public Browser withoutSignals() {
    this.signals = "";
    return this;
  }

  public Browser withSignals(String raw) {
    this.signals = raw;
    return this;
  }

  /** fill in the honeypot, as something that fills every input it finds would */
  public Browser fillingTheTrap() {
    this.trapValue = "spam@example.com";
    return this;
  }

  public Page get(String path) throws Exception {
    return send("GET", path, null);
  }

  /**
   * Submit the current page's form.
   *
   * Fields are named logically -- "email", "code", "password" -- and translated to whatever this
   * particular page called them. On a plain HTML form (the admin page) the hidden inputs are carried
   * forward instead.
   */
  public Page submit(Map<String, String> fields) throws Exception {
    if (current == null) {
      throw new IllegalStateException("no page loaded to submit");
    }
    JsonNode mint = current.mint();
    if (mint == null) {
      return send("POST", current.action(), encode(plainBody(fields)));
    }
    return send("POST", mint.get("action").asText(), encode(mintedBody(mint, fields)));
  }

  /** what the page's script would have posted, plus whatever the test asked for */
  private Map<String, String> mintedBody(JsonNode mint, Map<String, String> fields) {
    JsonNode names = mint.get("f");
    LinkedHashMap<String, String> body = new LinkedHashMap<>();
    body.put(names.get("csrf").asText(), mint.get("csrf").asText());
    if (sendProof) {
      body.put(names.get("proof").asText(), proofOf(mint.get("nonce").asText()));
    }
    body.put(names.get("signals").asText(), signals);
    body.put(names.get("trap").asText(), trapValue);
    if (mint.hasNonNull("handle")) {
      body.put(names.get("handle").asText(), mint.get("handle").asText());
    }
    for (Map.Entry<String, String> field : fields.entrySet()) {
      JsonNode opaque = names.get(field.getKey());
      body.put(opaque == null ? field.getKey() : opaque.asText(), field.getValue());
    }
    return body;
  }

  /**
   * The same value the page's script computes.
   *
   * A second implementation of a two-implementation algorithm, which is exactly the hazard
   * ProofContractTests exists to close: this agreeing with the server proves nothing about the
   * script a browser actually runs.
   */
  public static String proofOf(String nonce) {
    int hash = 7;
    for (int k = 0; k < nonce.length(); k++) {
      hash = (hash * 31 + nonce.charAt(k)) % 1000003;
    }
    return Integer.toString(hash, 36);
  }

  /**
   * Submit a page captured earlier, not whatever was loaded last.
   *
   * A person has one form on screen while the browser goes on making other requests -- the favicon,
   * a second tab, a prefetch. Modelling that needs a form that outlives the current page, and
   * without it the class of bug where one response invalidates another page's form is untestable.
   */
  public Page submitPage(Page page, Map<String, String> fields) throws Exception {
    JsonNode mint = page.mint();
    if (mint == null) {
      LinkedHashMap<String, String> body = new LinkedHashMap<>();
      String csrf = page.hidden().get("csrf");
      if (csrf != null) {
        body.put("csrf", csrf);
      }
      body.putAll(fields);
      return send("POST", page.action(), encode(body));
    }
    return send("POST", mint.get("action").asText(), encode(mintedBody(mint, fields)));
  }

  /**
   * Follow a redirect, the way a browser would.
   *
   * Redirects are not followed automatically because where a POST lands is part of what is being
   * tested -- the 303 and its Location are an assertion, not a step to skip. This is for the tests
   * that have already made that assertion and now want the page on the other side.
   */
  public Page follow(Page page) throws Exception {
    if (page.location() == null) {
      throw new IllegalStateException("nothing to follow: " + page.status() + " with no Location");
    }
    return get(page.location());
  }

  /** submit and land where the server sent us; for the flows where the redirect is not the point */
  public Page submitToAndFollow(String path, Map<String, String> fields) throws Exception {
    Page posted = submitTo(path, fields);
    return posted.location() == null ? posted : follow(posted);
  }

  /** submit to a specific path, ignoring the current page's action */
  public Page submitTo(String path, Map<String, String> fields) throws Exception {
    return send("POST", path, encode(plainBody(fields)));
  }

  /**
   * A plain form submission: the page's CSRF token plus exactly the fields given.
   *
   * Deliberately NOT every hidden input on the page. An admin page has one form per row plus an
   * editor, and carrying all of their hidden fields would silently mix them -- a save would pick up
   * the `id` from some row's delete button and quietly become an edit. A browser submits one form's
   * fields; so does this.
   */
  private Map<String, String> plainBody(Map<String, String> fields) {
    LinkedHashMap<String, String> body = new LinkedHashMap<>();
    String csrf = current == null ? null : current.hidden().get("csrf");
    if (csrf == null) {
      // whatever page is on screen, the token in the jar is the one the browser would echo back --
      // that is what double-submit means. Falling back to it keeps a test from having to load a
      // page it does not care about just to obtain a token it already holds.
      csrf = cookies.get("hearth_csrf");
    }
    if (csrf != null) {
      body.put("csrf", csrf);
    }
    body.putAll(fields);
    return body;
  }

  /** post exactly these fields and nothing else -- for testing what happens with nothing minted */
  public Page submitRaw(String path, Map<String, String> fields) throws Exception {
    return send("POST", path, encode(new LinkedHashMap<>(fields)));
  }

  /** the Cookie header this browser would send, for a test that needs a raw client */
  public String cookieHeader() {
    StringBuilder header = new StringBuilder();
    for (Map.Entry<String, String> cookie : cookies.entrySet()) {
      if (header.length() > 0) {
        header.append("; ");
      }
      header.append(cookie.getKey()).append('=').append(cookie.getValue());
    }
    return header.toString();
  }

  /**
   * A file, the way a browser sends one: `multipart/form-data`, with the CSRF token beside it.
   *
   * Written out by hand rather than by a library, because the thing under test is a server reading
   * exactly what a browser produces -- and a helper that built something slightly different would
   * be a test of the helper.
   *
   * @return the status code
   */
  public int uploadTo(String path, String filename, String contentType, byte[] file,
                      Map<String, String> fields) throws Exception {
    String boundary = "----hearth" + System.nanoTime();
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    LinkedHashMap<String, String> all = new LinkedHashMap<>(fields);
    if (!all.containsKey("csrf")) {
      all.put("csrf", cookies.get("hearth_csrf") == null ? "" : cookies.get("hearth_csrf"));
    }
    for (Map.Entry<String, String> field : all.entrySet()) {
      body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
          + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n")
          .getBytes(StandardCharsets.UTF_8));
    }
    body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\";"
        + " filename=\"" + filename + "\"\r\nContent-Type: " + contentType + "\r\n\r\n")
        .getBytes(StandardCharsets.UTF_8));
    body.write(file);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .header("Host", host)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary);
    if (!cookies.isEmpty()) {
      builder.header("Cookie", cookieHeader());
    }
    builder.method("POST", HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
    HttpResponse<String> response =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    absorbCookies(response.headers().allValues("set-cookie"));
    return response.statusCode();
  }

  private Page send(String method, String path, String body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(10))
        .header("Host", host);
    if (!cookies.isEmpty()) {
      StringBuilder header = new StringBuilder();
      for (Map.Entry<String, String> cookie : cookies.entrySet()) {
        if (header.length() > 0) {
          header.append("; ");
        }
        header.append(cookie.getKey()).append('=').append(cookie.getValue());
      }
      builder.header("Cookie", header.toString());
    }
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/x-www-form-urlencoded")
          .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    absorbCookies(response.headers().allValues("set-cookie"));
    current = new Page(response.statusCode(), response.body(), path,
        response.headers().firstValue("location").orElse(null),
        response.headers().allValues("set-cookie"),
        response.headers().firstValue("content-security-policy").orElse(null));
    return current;
  }

  /** apply Set-Cookie the way a browser would, including deletions via Max-Age=0 */
  private void absorbCookies(List<String> setCookies) {
    for (String header : setCookies) {
      int semi = header.indexOf(';');
      String pair = semi < 0 ? header : header.substring(0, semi);
      int equals = pair.indexOf('=');
      if (equals <= 0) {
        continue;
      }
      String name = pair.substring(0, equals).trim();
      String value = pair.substring(equals + 1).trim();
      String attributes = semi < 0 ? "" : header.substring(semi).toLowerCase(java.util.Locale.ROOT);
      if (value.isEmpty() || attributes.contains("max-age=0")) {
        cookies.remove(name);
      } else {
        cookies.put(name, value);
      }
    }
  }

  public String cookie(String name) {
    return cookies.get(name);
  }

  public boolean hasCookie(String name) {
    return cookies.containsKey(name);
  }

  /** plant a cookie by hand; for the tests about what happens when the wrong one is presented */
  public Browser setCookie(String name, String value) {
    cookies.put(name, value);
    return this;
  }

  /** throw away every cookie -- a fresh browser against the same server */
  public void forgetCookies() {
    cookies.clear();
  }

  private static String encode(Map<String, String> fields) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> field : fields.entrySet()) {
      if (sb.length() > 0) {
        sb.append('&');
      }
      sb.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8));
    }
    return sb.toString();
  }

  /** one response, with the bits a test wants to assert on or feed back in */
  public record Page(int status, String body, String requestedPath, String location,
                     List<String> setCookies, String csp) {
    /** the minted-form blob this page carries, or null when it is a plain page */
    public JsonNode mint() {
      Matcher matcher = MINT_BLOB.matcher(body);
      if (!matcher.find()) {
        return null;
      }
      try {
        return JSON.readTree(matcher.group(1));
      } catch (Exception ex) {
        throw new IllegalStateException("the mint blob did not parse: " + matcher.group(1), ex);
      }
    }

    /** the wire name this page chose for a logical field, or null when there is no minted form */
    public String nameFor(String logical) {
      JsonNode mint = mint();
      if (mint == null) {
        return null;
      }
      JsonNode name = mint.get("f").get(logical);
      return name == null ? null : name.asText();
    }

    /** does the page's script intend to collect this field? */
    public boolean wants(String logical) {
      JsonNode mint = mint();
      return mint != null && mint.get("want").path(logical).asBoolean(false);
    }

    /** every hidden input on the page; only plain forms have any */
    public Map<String, String> hidden() {
      LinkedHashMap<String, String> fields = new LinkedHashMap<>();
      Matcher matcher = HIDDEN.matcher(body);
      while (matcher.find()) {
        fields.put(matcher.group(1), matcher.group(2));
      }
      return fields;
    }

    /** where this page's form posts to, defaulting to the path it was fetched from */
    public String action() {
      Matcher matcher = FORM_ACTION.matcher(body);
      return matcher.find() ? matcher.group(1) : requestedPath;
    }

    public boolean contains(String needle) {
      return body.contains(needle);
    }

    public boolean hasField(String name) {
      return body.contains("name=\"" + name + "\"");
    }

    /** the Set-Cookie header for one cookie name, so attributes can be asserted on */
    public String setCookie(String name) {
      for (String header : setCookies) {
        if (header.startsWith(name + "=")) {
          return header;
        }
      }
      return null;
    }
  }
}
