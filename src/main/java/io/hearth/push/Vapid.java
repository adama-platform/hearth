package io.hearth.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Arrays;

/**
 * The signed claim that says who is sending a push.
 *
 * VAPID (RFC 8292) is one JWT, signed ES256, naming the push service as its audience and this
 * server as its subject. The push service checks it against the `applicationServerKey` the browser
 * used when it subscribed, which is how a subscription is bound to one sender and cannot be
 * replayed by anybody who scrapes the endpoint out of a database.
 *
 * <b>The keypair here is per session, which is unusual and deliberate.</b> The specification only
 * requires it to be per application, and one pair for the whole server is what most implementations
 * do. Making it per session means the key material dies exactly when the session does: revoking a
 * login does not merely stop us sending, it destroys the only key that push service will accept for
 * that subscription. The cost is real and worth naming -- a keypair generated per subscribing
 * session rather than once, and no possibility of rotating a shared key without re-subscribing
 * everybody. At this scale, generating a P-256 pair per login is nothing.
 */
public final class Vapid {
  private static final ObjectMapper JSON = new ObjectMapper();
  /** twelve hours; RFC 8292 caps a VAPID token at twenty-four */
  private static final long LIFETIME_SECONDS = 12 * 3600;

  private Vapid() {
  }

  /**
   * The Authorization header value for one push.
   *
   * The audience is the *origin* of the endpoint and nothing more -- including the path would make
   * the token specific to one subscription, which no push service expects and some refuse.
   */
  public static String authorization(String endpoint, String subject, KeyPair keys, long nowMillis)
      throws Exception {
    URI uri = URI.create(endpoint);
    String audience = uri.getScheme() + "://" + uri.getHost()
        + (uri.getPort() > 0 ? ":" + uri.getPort() : "");

    ObjectNode claims = JSON.createObjectNode();
    claims.put("aud", audience);
    claims.put("exp", nowMillis / 1000 + LIFETIME_SECONDS);
    claims.put("sub", subject);

    String header = PushCrypto.b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}"
        .getBytes(StandardCharsets.UTF_8));
    String payload = PushCrypto.b64(claims.toString().getBytes(StandardCharsets.UTF_8));
    String signingInput = header + "." + payload;

    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(keys.getPrivate());
    signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    String signature = PushCrypto.b64(joseFromDer(signer.sign()));

    return "vapid t=" + signingInput + "." + signature + ", k="
        + PushCrypto.b64(PushCrypto.publicKeyBytes(keys.getPublic()));
  }

  /**
   * Java signs ECDSA as a DER SEQUENCE of two INTEGERs; JOSE wants r and s as fixed-width bytes.
   *
   * Sending the DER form produces a token every push service rejects with an opaque 401, which is
   * a fun afternoon. The width is fixed at 32 because an r or s with a leading zero byte is
   * shorter, and left-padding is the difference between a valid signature and an invalid one.
   */
  static byte[] joseFromDer(byte[] der) {
    int offset = 3;
    if (der[1] == (byte) 0x81) {
      offset = 4;
    }
    int rLength = der[offset] & 0xff;
    int rStart = offset + 1;
    int sLength = der[rStart + rLength + 1] & 0xff;
    int sStart = rStart + rLength + 2;

    BigInteger r = new BigInteger(Arrays.copyOfRange(der, rStart, rStart + rLength));
    BigInteger s = new BigInteger(Arrays.copyOfRange(der, sStart, sStart + sLength));

    byte[] out = new byte[64];
    System.arraycopy(PushCrypto.unsigned(r, 32), 0, out, 0, 32);
    System.arraycopy(PushCrypto.unsigned(s, 32), 0, out, 32, 32);
    return out;
  }
}
