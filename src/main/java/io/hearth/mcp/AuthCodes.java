package io.hearth.mcp;

import io.hearth.auth.Tokens;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authorization codes in flight.
 *
 * In memory only, and deliberately: a code lives for about as long as a redirect takes, and a
 * two-minute credential is not worth a row -- the same argument that keeps emailed sign-in codes out
 * of the database. Losing them on restart costs somebody one click.
 *
 * Every rule here exists because of a specific way this flow gets attacked:
 *
 * - **Single use.** Redeeming removes the code before anything is checked, so a stolen code cannot
 *   be raced against the legitimate client.
 * - **PKCE, S256 only.** `plain` is in the spec and is worth nothing; a code intercepted on the way
 *   back is useless without the verifier, and the verifier never travels over the front channel.
 * - **The redirect is part of the redemption.** A code issued for one redirect cannot be redeemed
 *   claiming another, which is what stops a mix-up between two clients on one server.
 * - **The client is part of the redemption.** A code issued to one client is not redeemable by
 *   another that happens to know it.
 */
public class AuthCodes {
  private static final int MAX_PENDING = 1_000;

  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private final long lifetimeMillis;

  public AuthCodes(int lifetimeSeconds) {
    this.lifetimeMillis = lifetimeSeconds * 1000L;
  }

  /** what was agreed at the consent screen, waiting for the client to come back for it */
  public record Pending(String clientId, long userId, String redirectUri, String challenge,
                        String scope, long issuedAt) {
  }

  /** the outcome of a redemption: either a grant or the reason there is not one */
  public record Redeemed(Pending grant, String problem) {
    public boolean ok() {
      return grant != null;
    }

    static Redeemed no(String problem) {
      return new Redeemed(null, problem);
    }
  }

  /**
   * Mint a code for a consent that just happened.
   *
   * The challenge is required. A flow without PKCE is one where anybody who can observe the
   * redirect owns the resulting token, and there is no client here that could not do PKCE.
   */
  public String issue(String clientId, long userId, String redirectUri, String challenge, String scope) {
    if (pending.size() > MAX_PENDING) {
      // a flood of unredeemed codes is either a bug or somebody probing; the oldest are the least
      // likely to still be wanted
      sweep(System.currentTimeMillis());
      if (pending.size() > MAX_PENDING) {
        pending.clear();
      }
    }
    String code = Tokens.newSessionToken();
    pending.put(code, new Pending(clientId, userId, redirectUri, challenge, scope, System.currentTimeMillis()));
    return code;
  }

  /** redeem a code, or say why not; the code is consumed either way */
  public Redeemed redeem(String code, String clientId, String redirectUri, String verifier) {
    if (code == null || code.isEmpty()) {
      return Redeemed.no("missing code");
    }
    Pending grant = pending.remove(code);
    if (grant == null) {
      return Redeemed.no("that code is not valid");
    }
    if (System.currentTimeMillis() - grant.issuedAt() > lifetimeMillis) {
      return Redeemed.no("that code expired");
    }
    if (!grant.clientId().equals(clientId)) {
      return Redeemed.no("that code was issued to a different client");
    }
    if (!grant.redirectUri().equals(redirectUri)) {
      return Redeemed.no("redirect_uri does not match the one the code was issued for");
    }
    if (verifier == null || verifier.length() < 43 || verifier.length() > 128) {
      return Redeemed.no("code_verifier must be 43 to 128 characters");
    }
    if (!Tokens.constantTimeEquals(grant.challenge(), challengeFor(verifier))) {
      return Redeemed.no("code_verifier does not match the challenge");
    }
    return new Redeemed(grant, null);
  }

  /** the S256 challenge for a verifier: base64url(sha256(ascii(verifier))), unpadded */
  public static String challengeFor(String verifier) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required by the platform", ex);
    }
  }

  /** drop everything past its life; called opportunistically rather than on a timer */
  public int sweep(long now) {
    int before = pending.size();
    pending.entrySet().removeIf(entry -> now - entry.getValue().issuedAt() > lifetimeMillis);
    return before - pending.size();
  }

  public int size() {
    return pending.size();
  }
}
