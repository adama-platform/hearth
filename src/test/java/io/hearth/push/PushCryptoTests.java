package io.hearth.push;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Push encryption, checked against the published numbers.
 *
 * Every value below is copied out of RFC 8291 section 5. That matters more here than anywhere else
 * in this project: a round trip only proves an implementation agrees with itself, and a wrong
 * derivation agrees with itself perfectly. The failure it would produce is a push service happily
 * accepting a message no browser can decrypt -- delivered, and silent.
 */
public class PushCryptoTests {
  // ---- RFC 8291 section 5 ----------------------------------------------------------------------
  private static final String UA_PUBLIC =
      "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
  private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
  private static final String AS_PUBLIC =
      "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
  private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
  private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
  private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
  private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";

  private static final String SHARED = "kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs";
  private static final String PRK_KEY = "Snr3JMxaHVDXHWJn5wdC52WjpCtd2EIEGBykDcZW32k";
  private static final String KEY_INFO = "V2ViUHVzaDogaW5mbwAEJXGyvs3942BVGq8e0PTNNmwRzr5VX4m8t7GG"
      + "pTM5FzFo7OLr4BhZe9MEebhuPI-OztV3ylkYfpJGmQ22ggCLDgT-M_SrDepxkU21WCP3O1SUj0EwbZIHMtu5pZpT"
      + "KGSCIA5Zent7wmC6HCJ5mFgJkuk5cwAvMBKiiujwa7t45ewP";
  private static final String IKM = "S4lYMb_L0FxCeq0WhDx813KgSYqU26kOyzWUdsXYyrg";
  private static final String PRK = "09_eUZGrsvxChDCGRCdkLiDXrReGOEVeSCdCcPBSJSc";
  private static final String CEK = "oIhVW04MRdy2XN9CiKLxTg";
  private static final String NONCE = "4h_95klXJ5E_qnoN";

  private static PushCrypto.Derived rfcDerivation() throws Exception {
    return PushCrypto.derive(PushCrypto.privateKeyFrom(PushCrypto.unb64(AS_PRIVATE)),
        PushCrypto.unb64(UA_PUBLIC), PushCrypto.unb64(UA_PUBLIC), PushCrypto.unb64(AS_PUBLIC),
        PushCrypto.unb64(AUTH), PushCrypto.unb64(SALT));
  }

  // ---- the derivation, value by value ----------------------------------------------------------

  @Test
  public void theSharedSecretMatchesTheRfc() throws Exception {
    assertEquals(SHARED, PushCrypto.b64(rfcDerivation().sharedSecret()));
  }

  @Test
  public void thePseudorandomKeyFromTheAuthSecretMatchesTheRfc() throws Exception {
    assertEquals(PRK_KEY, PushCrypto.b64(rfcDerivation().prkKey()));
  }

  @Test
  public void theKeyInfoIsBuiltInTheRightOrder() throws Exception {
    // "WebPush: info" 0x00, then the *receiver's* key, then the sender's. Swapping the two produces
    // a perfectly self-consistent implementation that no browser can read.
    assertEquals(KEY_INFO, PushCrypto.b64(rfcDerivation().keyInfo()));
  }

  @Test
  public void theInputKeyingMaterialMatchesTheRfc() throws Exception {
    assertEquals(IKM, PushCrypto.b64(rfcDerivation().ikm()));
  }

  @Test
  public void theContentEncryptionKeyAndNonceMatchTheRfc() throws Exception {
    PushCrypto.Derived derived = rfcDerivation();
    assertEquals(PRK, PushCrypto.b64(derived.prk()));
    assertEquals("16 bytes, not 32", CEK, PushCrypto.b64(derived.cek()));
    assertEquals("12 bytes", NONCE, PushCrypto.b64(derived.nonce()));
    assertEquals(16, derived.cek().length);
    assertEquals(12, derived.nonce().length);
  }

  @Test
  public void theHeaderIsExactlyWhatTheRfcShows() throws Exception {
    byte[] body = PushCrypto.encrypt(PLAINTEXT.getBytes(StandardCharsets.UTF_8),
        PushCrypto.unb64(UA_PUBLIC), PushCrypto.unb64(AUTH), rfcKeyPair(),
        PushCrypto.unb64(SALT));

    byte[] header = java.util.Arrays.copyOf(body, 86);
    assertEquals("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vC"
        + "YLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8", PushCrypto.b64(header));
  }

  @Test
  public void theBrowserInTheRfcCouldReadWhatWeProduce() throws Exception {
    byte[] body = PushCrypto.encrypt(PLAINTEXT.getBytes(StandardCharsets.UTF_8),
        PushCrypto.unb64(UA_PUBLIC), PushCrypto.unb64(AUTH), rfcKeyPair(),
        PushCrypto.unb64(SALT));

    byte[] back = PushCrypto.decrypt(body, PushCrypto.privateKeyFrom(PushCrypto.unb64(UA_PRIVATE)),
        PushCrypto.unb64(UA_PUBLIC), PushCrypto.unb64(AUTH));
    assertEquals(PLAINTEXT, new String(back, StandardCharsets.UTF_8));
  }

  // ---- and with keys nobody published -----------------------------------------------------------

  @Test
  public void afreshPairRoundTripsToo() throws Exception {
    KeyPair ua = PushCrypto.generateKeyPair();
    KeyPair server = PushCrypto.generateKeyPair();
    byte[] auth = PushCrypto.randomBytes(16);
    byte[] message = "Ana replied to you in Where should we meet?"
        .getBytes(StandardCharsets.UTF_8);

    byte[] body = PushCrypto.encrypt(message, PushCrypto.publicKeyBytes(ua.getPublic()), auth,
        server, PushCrypto.randomBytes(16));
    byte[] back = PushCrypto.decrypt(body, ua.getPrivate(),
        PushCrypto.publicKeyBytes(ua.getPublic()), auth);
    assertArrayEquals(message, back);
  }

  @Test
  public void aDifferentSaltProducesADifferentBody() throws Exception {
    KeyPair ua = PushCrypto.generateKeyPair();
    KeyPair server = PushCrypto.generateKeyPair();
    byte[] auth = PushCrypto.randomBytes(16);
    byte[] message = "hello".getBytes(StandardCharsets.UTF_8);
    byte[] uaPublic = PushCrypto.publicKeyBytes(ua.getPublic());

    byte[] one = PushCrypto.encrypt(message, uaPublic, auth, server, PushCrypto.randomBytes(16));
    byte[] two = PushCrypto.encrypt(message, uaPublic, auth, server, PushCrypto.randomBytes(16));
    assertNotEquals("a fixed salt would make identical messages identical on the wire",
        PushCrypto.b64(one), PushCrypto.b64(two));
  }

  @Test
  public void theWrongAuthSecretCannotRead() throws Exception {
    KeyPair ua = PushCrypto.generateKeyPair();
    KeyPair server = PushCrypto.generateKeyPair();
    byte[] uaPublic = PushCrypto.publicKeyBytes(ua.getPublic());
    byte[] body = PushCrypto.encrypt("hello".getBytes(StandardCharsets.UTF_8), uaPublic,
        PushCrypto.randomBytes(16), server, PushCrypto.randomBytes(16));

    try {
      PushCrypto.decrypt(body, ua.getPrivate(), uaPublic, PushCrypto.randomBytes(16));
      org.junit.Assert.fail("decrypted with the wrong auth secret");
    } catch (Exception expected) {
      // AEAD failing closed is the point
    }
  }

  // ---- key encoding -----------------------------------------------------------------------------

  @Test
  public void keysSurviveGoingToBytesAndBack() throws Exception {
    KeyPair pair = PushCrypto.generateKeyPair();
    byte[] publicBytes = PushCrypto.publicKeyBytes(pair.getPublic());
    byte[] privateBytes = PushCrypto.privateKeyBytes(pair.getPrivate());

    assertEquals("uncompressed point", 65, publicBytes.length);
    assertEquals(0x04, publicBytes[0]);
    assertEquals("a fixed-width scalar; BigInteger's own encoding carries a sign byte",
        32, privateBytes.length);

    assertArrayEquals(publicBytes,
        PushCrypto.publicKeyBytes(PushCrypto.publicKeyFrom(publicBytes)));
    assertArrayEquals(privateBytes,
        PushCrypto.privateKeyBytes(PushCrypto.privateKeyFrom(privateBytes)));
  }

  @Test
  public void theRfcKeysReEncodeToTheirPublishedForm() throws Exception {
    assertEquals(AS_PUBLIC, PushCrypto.b64(
        PushCrypto.publicKeyBytes(PushCrypto.publicKeyFrom(PushCrypto.unb64(AS_PUBLIC)))));
    assertEquals(AS_PRIVATE, PushCrypto.b64(
        PushCrypto.privateKeyBytes(PushCrypto.privateKeyFrom(PushCrypto.unb64(AS_PRIVATE)))));
  }

  @Test
  public void somethingThatIsNotAPointIsRefused() {
    for (byte[] rubbish : new byte[][]{new byte[0], new byte[65], new byte[64],
        "not a key at all".getBytes(StandardCharsets.UTF_8)}) {
      try {
        PushCrypto.publicKeyFrom(rubbish);
        org.junit.Assert.fail("accepted " + rubbish.length + " bytes of rubbish");
      } catch (Exception expected) {
        assertTrue(true);
      }
    }
  }

  private static KeyPair rfcKeyPair() throws Exception {
    return new KeyPair(PushCrypto.publicKeyFrom(PushCrypto.unb64(AS_PUBLIC)),
        PushCrypto.privateKeyFrom(PushCrypto.unb64(AS_PRIVATE)));
  }
}
