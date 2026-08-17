package io.hearth.web;

import io.hearth.auth.Tokens;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forms whose field names are different every time somebody loads the page.
 *
 * The goal is not to be unbreakable -- nothing rendered in a browser can be. The goal is to make the
 * cheap attack expensive. A scraper that hardcodes name="email" stops working; one that parses the
 * HTML finds no form at all, because the form is assembled by JavaScript from a blob; one that runs
 * the JavaScript still has to produce plausible mouse and keyboard traffic. Each rung costs the
 * operator nothing and costs the attacker a rewrite.
 *
 * A ticket is a short-lived server-side secret plus the mapping it generates:
 *
 *   email -> "q7c1f4a9b2e8"     opaque prefix + HMAC(ticket secret, "email")
 *
 * The mapping is never sent to the server; the server recomputes it from the ticket and reverses it.
 * Two people loading the page at the same second get entirely different names for the same fields,
 * so a name harvested from one page is worthless on another.
 *
 * Tickets live in memory with a short expiry, like emailed codes and for the same reason: they are
 * credentials with a ten-minute life, and writing them down buys nothing.
 */
public class FormMint {
  /** how long a rendered form stays submittable */
  public static final long TICKET_TTL_MILLIS = 30 * 60 * 1000L;
  /** a bound on how many forms can be in flight, so rendering pages cannot exhaust memory */
  public static final int MAX_TICKETS = 20_000;
  /** the query parameter carrying the ticket; the ticket id is a handle, not a secret */
  public static final String TICKET_PARAM = "ft";

  /** the logical fields a minted form can carry */
  public static final String EMAIL = "email";
  public static final String CODE = "code";
  public static final String PASSWORD = "password";
  public static final String HANDLE = "handle";
  public static final String CSRF = "csrf";
  /** written by JavaScript from the nonce; absent means no JavaScript ran */
  public static final String PROOF = "proof";
  /** the interaction counts; see {@link Signals} */
  public static final String SIGNALS = "signals";
  /** never filled by our JavaScript; anything in it came from something filling in every input */
  public static final String TRAP = "trap";

  private static final String[] FIELDS = {EMAIL, CODE, PASSWORD, HANDLE, CSRF, PROOF, SIGNALS, TRAP};
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final char[] PREFIX_ALPHABET = "abcdefghijkmnopqrstuvwxyz".toCharArray();

  private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();

  /** mint a form: a fresh id, a fresh secret, a fresh set of names */
  public Ticket mint(String csrfToken) {
    sweep();
    if (tickets.size() >= MAX_TICKETS) {
      // shed the oldest rather than refusing to render pages
      long cutoff = System.currentTimeMillis() - TICKET_TTL_MILLIS / 2;
      tickets.values().removeIf(ticket -> ticket.createdAt < cutoff);
    }
    byte[] secret = new byte[32];
    RANDOM.nextBytes(secret);
    String id = Tokens.newHandle();
    String prefix = randomPrefix();
    String nonce = Tokens.newHandle();
    Ticket ticket = new Ticket(id, secret, prefix, nonce, csrfToken, System.currentTimeMillis());
    tickets.put(id, ticket);
    return ticket;
  }

  /** the ticket for an id, or null when it never existed or has expired */
  public Ticket find(String id) {
    if (id == null) {
      return null;
    }
    Ticket ticket = tickets.get(id);
    if (ticket == null) {
      return null;
    }
    if (System.currentTimeMillis() - ticket.createdAt > TICKET_TTL_MILLIS) {
      tickets.remove(id, ticket);
      return null;
    }
    return ticket;
  }

  /** a form is good for one submission; spend it so a captured body cannot be replayed */
  public void spend(String id) {
    if (id != null) {
      tickets.remove(id);
    }
  }

  public int size() {
    return tickets.size();
  }

  public void sweep() {
    long cutoff = System.currentTimeMillis() - TICKET_TTL_MILLIS;
    tickets.values().removeIf(ticket -> ticket.createdAt < cutoff);
  }

  private static String randomPrefix() {
    // two letters, so the opaque names look like nothing in particular and never start with a digit
    return "" + PREFIX_ALPHABET[RANDOM.nextInt(PREFIX_ALPHABET.length)]
        + PREFIX_ALPHABET[RANDOM.nextInt(PREFIX_ALPHABET.length)];
  }

  /**
   * The proof JavaScript has to compute.
   *
   * Not a real MAC, and it cannot be: the code that computes it is shipped to the browser. All it
   * establishes is that something ran the script rather than posting the raw HTML, which is the
   * only thing a client-side check can ever establish.
   *
   * The arithmetic is deliberately small, and that is the interesting part. The obvious choice is
   * FNV-1a, and the obvious FNV-1a is wrong here: JavaScript numbers are doubles, so its 32-bit
   * multiply overflows past 2^53 and loses the low bits, while Java's int multiply wraps exactly.
   * The two silently disagree and every browser is told its JavaScript is switched off. Keeping
   * every intermediate under 2^31 makes the two implementations agree structurally rather than
   * because somebody remembered Math.imul -- and a later "simplification" cannot reintroduce it.
   *
   * ProofContractTests runs the shipped script under node and compares.
   */
  public static String proofOf(String nonce) {
    int hash = 7;
    for (int k = 0; k < nonce.length(); k++) {
      hash = (hash * 31 + nonce.charAt(k)) % 1000003;
    }
    return Integer.toString(hash, 36);
  }

  /** one rendered form: the names it uses, and the secrets behind them */
  public static class Ticket {
    public final String id;
    private final byte[] secret;
    public final String prefix;
    public final String nonce;
    /** the CSRF token this form was rendered with; checked as well as the cookie */
    public final String csrfToken;
    final long createdAt;
    private final Map<String, String> byLogical;
    private final Map<String, String> byOpaque;

    Ticket(String id, byte[] secret, String prefix, String nonce, String csrfToken, long createdAt) {
      this.id = id;
      this.secret = secret;
      this.prefix = prefix;
      this.nonce = nonce;
      this.csrfToken = csrfToken;
      this.createdAt = createdAt;
      LinkedHashMap<String, String> logical = new LinkedHashMap<>();
      LinkedHashMap<String, String> opaque = new LinkedHashMap<>();
      for (String field : FIELDS) {
        String name = prefix + mac(secret, field);
        logical.put(field, name);
        opaque.put(name, field);
      }
      this.byLogical = logical;
      this.byOpaque = opaque;
    }

    /** the wire name for a logical field */
    public String nameOf(String logical) {
      return byLogical.get(logical);
    }

    /** the logical field a wire name refers to, or null for anything we did not mint */
    public String logicalOf(String opaque) {
      return byOpaque.get(opaque);
    }

    public Map<String, String> names() {
      return byLogical;
    }

    public String expectedProof() {
      return proofOf(nonce);
    }

    private static String mac(byte[] secret, String field) {
      try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        byte[] out = mac.doFinal(field.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(10);
        for (int k = 0; k < 5; k++) {
          sb.append(Character.forDigit((out[k] >> 4) & 0xf, 16));
          sb.append(Character.forDigit(out[k] & 0xf, 16));
        }
        return sb.toString();
      } catch (Exception ex) {
        throw new IllegalStateException("HmacSHA256 is required and missing", ex);
      }
    }
  }
}
