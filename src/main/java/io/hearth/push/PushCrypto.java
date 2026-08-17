package io.hearth.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Message encryption for Web Push: RFC 8188 content coding, keyed the way RFC 8291 says.
 *
 * Hand-rolled for the same reason {@link io.hearth.mail.SignatureV4} is -- the alternative is a
 * dependency tree for one POST -- and with the same discipline: the derivation is checked against
 * the published test vector in RFC 8291 section 5, value by value, not merely round-tripped. A
 * round trip proves an implementation agrees with itself, which is exactly the property a wrong one
 * also has.
 *
 * The shape, in order:
 *
 * <pre>
 *   ecdh_secret = ECDH(server_private, ua_public)
 *   PRK_key     = HMAC(auth_secret, ecdh_secret)
 *   key_info    = "WebPush: info" 0x00 ua_public server_public
 *   IKM         = HMAC(PRK_key, key_info 0x01)
 *   PRK         = HMAC(salt, IKM)
 *   CEK         = HMAC(PRK, "Content-Encoding: aes128gcm" 0x00 0x01)[0..16]
 *   NONCE       = HMAC(PRK, "Content-Encoding: nonce" 0x00 0x01)[0..12]
 * </pre>
 *
 * The body is a header (salt, record size, key id length, the server's public key) followed by one
 * AES-128-GCM record. One record, always: a push message is small, and multi-record framing is a
 * generality nothing here would ever exercise and everything here would have to be correct about.
 */
public final class PushCrypto {
  /** the elliptic curve every push service speaks */
  public static final String CURVE = "secp256r1";
  /** an uncompressed P-256 point: 0x04 then X then Y */
  public static final int PUBLIC_KEY_BYTES = 65;
  private static final byte[] KEY_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.UTF_8);
  private static final byte[] CEK_INFO =
      "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.UTF_8);
  private static final byte[] NONCE_INFO =
      "Content-Encoding: nonce\0".getBytes(StandardCharsets.UTF_8);
  /** the record size we advertise; one record, and this is its ceiling */
  private static final int RECORD_SIZE = 4096;

  private PushCrypto() {
  }

  /** the pieces of the derivation, kept so a test can check each against the RFC */
  public record Derived(byte[] sharedSecret, byte[] prkKey, byte[] keyInfo, byte[] ikm, byte[] prk,
                        byte[] cek, byte[] nonce) {
  }

  public static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec(CURVE), new SecureRandom());
      return generator.generateKeyPair();
    } catch (Exception ex) {
      throw new IllegalStateException("no P-256 on this JVM", ex);
    }
  }

  /**
   * Everything the recipe needs, in the order the RFC derives it.
   *
   * The ECDH inputs and the key_info inputs are separate arguments on purpose. Both sides derive
   * the same numbers, but from opposite halves of the pair -- the sender agrees its private key
   * with the browser's public one, the browser agrees its private key with the sender's -- while
   * key_info is always the browser's key then the sender's, whoever is doing the arithmetic.
   * Folding those together produced an implementation that encrypted correctly against the RFC and
   * could not decrypt its own output.
   */
  public static Derived derive(PrivateKey mine, byte[] theirs, byte[] uaPublic, byte[] serverPublic,
                               byte[] authSecret, byte[] salt) throws Exception {
    byte[] shared = agree(mine, theirs);
    byte[] prkKey = hmac(authSecret, shared);

    ByteArrayOutputStream keyInfo = new ByteArrayOutputStream();
    keyInfo.write(KEY_INFO_PREFIX);
    keyInfo.write(uaPublic);
    keyInfo.write(serverPublic);

    byte[] ikm = hkdfExpand(prkKey, keyInfo.toByteArray(), 32);
    byte[] prk = hmac(salt, ikm);
    byte[] cek = hkdfExpand(prk, CEK_INFO, 16);
    byte[] nonce = hkdfExpand(prk, NONCE_INFO, 12);
    return new Derived(shared, prkKey, keyInfo.toByteArray(), ikm, prk, cek, nonce);
  }

  /**
   * The whole body, ready to POST.
   *
   * The plaintext gets a single 0x02 delimiter appended -- the padding byte that says "this is the
   * last record". Omitting it produces something every push service accepts and no browser can
   * decrypt, which is the worst kind of wrong: it looks delivered.
   */
  public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret,
                               KeyPair serverKeys, byte[] salt) throws Exception {
    byte[] serverPublic = publicKeyBytes(serverKeys.getPublic());
    Derived derived = derive(serverKeys.getPrivate(), uaPublic, uaPublic, serverPublic,
        authSecret, salt);

    byte[] padded = Arrays.copyOf(plaintext, plaintext.length + 1);
    padded[plaintext.length] = 0x02;

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derived.cek(), "AES"),
        new GCMParameterSpec(128, derived.nonce()));
    byte[] ciphertext = cipher.doFinal(padded);

    ByteBuffer body = ByteBuffer.allocate(16 + 4 + 1 + serverPublic.length + ciphertext.length);
    body.put(salt);
    body.putInt(RECORD_SIZE);
    body.put((byte) serverPublic.length);
    body.put(serverPublic);
    body.put(ciphertext);
    return body.array();
  }

  /**
   * The inverse, which exists only so a test can prove a browser could read what we sent.
   *
   * Nothing in the server decrypts a push message -- there is nothing to decrypt one of.
   */
  public static byte[] decrypt(byte[] body, PrivateKey uaPrivate, byte[] uaPublic,
                               byte[] authSecret) throws Exception {
    ByteBuffer buffer = ByteBuffer.wrap(body);
    byte[] salt = new byte[16];
    buffer.get(salt);
    buffer.getInt();
    int keyLength = buffer.get() & 0xff;
    byte[] serverPublic = new byte[keyLength];
    buffer.get(serverPublic);
    byte[] ciphertext = new byte[buffer.remaining()];
    buffer.get(ciphertext);

    // the same numbers from the other side: the receiver's private key against the sender's public
    Derived derived = derive(uaPrivate, serverPublic, uaPublic, serverPublic, authSecret, salt);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derived.cek(), "AES"),
        new GCMParameterSpec(128, derived.nonce()));
    byte[] padded = cipher.doFinal(ciphertext);
    int end = padded.length;
    while (end > 0 && padded[end - 1] == 0) {
      end--;
    }
    // drop the delimiter
    return Arrays.copyOf(padded, Math.max(0, end - 1));
  }

  // ---- the small pieces ------------------------------------------------------------------------

  static byte[] agree(PrivateKey privateKey, byte[] peerPublic) throws Exception {
    KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
    agreement.init(privateKey);
    agreement.doPhase(publicKeyFrom(peerPublic), true);
    return agreement.generateSecret();
  }

  static byte[] hmac(byte[] key, byte[] message) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(message);
  }

  /** HKDF-Expand with a single block, which is all any of these need */
  static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
    ByteArrayOutputStream input = new ByteArrayOutputStream();
    input.write(info);
    input.write(1);
    byte[] block = hmac(prk, input.toByteArray());
    return Arrays.copyOf(block, length);
  }

  public static byte[] randomBytes(int count) {
    byte[] bytes = new byte[count];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }

  /** an uncompressed point, which is the only form anything in Web Push exchanges */
  public static byte[] publicKeyBytes(PublicKey key) {
    java.security.interfaces.ECPublicKey ec = (java.security.interfaces.ECPublicKey) key;
    byte[] x = unsigned(ec.getW().getAffineX(), 32);
    byte[] y = unsigned(ec.getW().getAffineY(), 32);
    byte[] out = new byte[PUBLIC_KEY_BYTES];
    out[0] = 0x04;
    System.arraycopy(x, 0, out, 1, 32);
    System.arraycopy(y, 0, out, 33, 32);
    return out;
  }

  public static byte[] privateKeyBytes(PrivateKey key) {
    return unsigned(((java.security.interfaces.ECPrivateKey) key).getS(), 32);
  }

  public static PublicKey publicKeyFrom(byte[] uncompressed) throws Exception {
    if (uncompressed.length != PUBLIC_KEY_BYTES || uncompressed[0] != 0x04) {
      throw new IllegalArgumentException("not an uncompressed P-256 point");
    }
    BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
    BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
    return KeyFactory.getInstance("EC")
        .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), params()));
  }

  public static PrivateKey privateKeyFrom(byte[] scalar) throws Exception {
    return KeyFactory.getInstance("EC")
        .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), params()));
  }

  static ECParameterSpec params() throws Exception {
    AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
    parameters.init(new ECGenParameterSpec(CURVE));
    return parameters.getParameterSpec(ECParameterSpec.class);
  }

  /** a fixed-width unsigned big-endian integer; BigInteger's own encoding has a sign byte */
  static byte[] unsigned(BigInteger value, int width) {
    byte[] raw = value.toByteArray();
    if (raw.length == width) {
      return raw;
    }
    byte[] out = new byte[width];
    if (raw.length > width) {
      System.arraycopy(raw, raw.length - width, out, 0, width);
    } else {
      System.arraycopy(raw, 0, out, width - raw.length, raw.length);
    }
    return out;
  }

  public static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static byte[] unb64(String value) {
    return Base64.getUrlDecoder().decode(value);
  }
}
