package io.hearth.mail;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Signing a request the way AWS wants it.
 *
 * Adapted from adama's, which is the same algorithm because there is only one. Two differences, and
 * the first is a bug worth not copying: adama's SES caller passes the region into the URL but a
 * hardcoded `email.us-east-2.amazonaws.com` into the signature, so signing works only in one region
 * and fails opaquely everywhere else. Here the host comes from the region, once.
 *
 * The second is scope. Adama's supports presigned query strings for S3; this signs headers for one
 * POST to one service, so everything to do with query parameters is gone.
 *
 * There is nothing clever here and there must not be. SigV4 fails closed and silently -- a
 * mis-signed request comes back as a flat 403 with no hint about which of the dozen canonical
 * strings was wrong -- so this stays a literal transcription of the specification.
 */
public class SignatureV4 {
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  private final String accessKeyId;
  private final String secretKey;
  private final String region;
  private final String service;
  private final String method;
  private final String path;
  private final Instant now;
  private final TreeMap<String, String> headers = new TreeMap<>();
  private String contentSha256;

  public SignatureV4(String accessKeyId, String secretKey, String region, String service,
                     String method, String host, String path) {
    this(accessKeyId, secretKey, region, service, method, host, path, Instant.now());
  }

  /** the clock is a parameter so a test can assert a signature against a known one */
  public SignatureV4(String accessKeyId, String secretKey, String region, String service,
                     String method, String host, String path, Instant now) {
    this.accessKeyId = accessKeyId;
    this.secretKey = secretKey;
    this.region = region;
    this.service = service;
    this.method = method;
    this.path = path;
    this.now = now;
    headers.put("Host", host);
  }

  public SignatureV4 withHeader(String name, String value) {
    headers.put(name, value);
    return this;
  }

  public SignatureV4 withBody(byte[] body) {
    this.contentSha256 = hex(sha256(body));
    headers.put("X-Amz-Content-Sha256", contentSha256);
    return this;
  }

  /** every header the request must carry, including Authorization */
  public Map<String, String> sign() {
    if (contentSha256 == null) {
      withBody(new byte[0]);
    }
    headers.put("X-Amz-Date", STAMP.format(now));

    String scope = DAY.format(now) + "/" + region + "/" + service + "/aws4_request";
    StringBuilder signedHeaders = new StringBuilder();
    StringBuilder canonicalHeaders = new StringBuilder();
    TreeMap<String, String> lowered = new TreeMap<>();
    for (Map.Entry<String, String> header : headers.entrySet()) {
      lowered.put(header.getKey().toLowerCase(Locale.ROOT), header.getValue().trim().replaceAll("\\s+", " "));
    }
    for (Map.Entry<String, String> header : lowered.entrySet()) {
      if (signedHeaders.length() > 0) {
        signedHeaders.append(';');
      }
      signedHeaders.append(header.getKey());
      canonicalHeaders.append(header.getKey()).append(':').append(header.getValue()).append('\n');
    }

    // no query string: this signs one POST to one endpoint
    String canonicalRequest = method + "\n" + path + "\n" + "" + "\n"
        + canonicalHeaders + "\n" + signedHeaders + "\n" + contentSha256;
    String toSign = "AWS4-HMAC-SHA256\n" + STAMP.format(now) + "\n" + scope + "\n"
        + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

    byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), DAY.format(now));
    key = hmac(key, region);
    key = hmac(key, service);
    key = hmac(key, "aws4_request");
    String signature = hex(hmac(key, toSign));

    TreeMap<String, String> signed = new TreeMap<>(headers);
    signed.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope
        + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
    return signed;
  }

  static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 is required by the platform", ex);
    }
  }

  static byte[] hmac(byte[] key, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (Exception ex) {
      throw new IllegalStateException("HmacSHA256 is required by the platform", ex);
    }
  }

  static String hex(byte[] bytes) {
    StringBuilder hex = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
    }
    return hex.toString();
  }
}
