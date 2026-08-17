package io.hearth.certs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The HTTP-01 challenges this server is currently answering.
 *
 * A certificate authority proves you control a domain by asking for a specific string at a specific
 * path on port 80 of that domain. goatbot answered by uploading the file to an S3 bucket that
 * something else served; here the server that wants the certificate answers for itself, which
 * removes the bucket, the credentials, and the question of whether the two are in sync.
 *
 * In memory, because a challenge lives for the few seconds between "tell the CA to check" and "the
 * CA checked". Nothing is worth writing down, and anything left over from a crashed order is
 * garbage that should not come back on restart.
 *
 * The tokens are the CA's, unguessable, and only ever match orders this server placed -- so serving
 * them to anybody who asks costs nothing. What matters is the reverse: the path must answer *before*
 * anything else in the request path can refuse it, or validation fails for reasons that have nothing
 * to do with certificates.
 */
public class Challenges {
  public static final String PREFIX = "/.well-known/acme-challenge/";

  private final Map<String, String> pending = new ConcurrentHashMap<>();
  private final AtomicLong served = new AtomicLong();
  private final AtomicLong missed = new AtomicLong();

  /** publish the answer for a token, until the order is done with it */
  public void publish(String token, String keyAuthorization) {
    if (token != null && keyAuthorization != null) {
      pending.put(token, keyAuthorization);
    }
  }

  public void withdraw(String token) {
    if (token != null) {
      pending.remove(token);
    }
  }

  /** does this path want a challenge answer? */
  public static boolean isChallenge(String path) {
    return path != null && path.startsWith(PREFIX) && path.length() > PREFIX.length();
  }

  /** the answer for a request path, or null when we are not expecting that token */
  public String answerFor(String path) {
    if (!isChallenge(path)) {
      return null;
    }
    String token = path.substring(PREFIX.length());
    String answer = pending.get(token);
    if (answer == null) {
      // worth counting: a validation that arrives after the order gave up looks exactly like a
      // scanner, and the difference matters when somebody is asking why issuance failed
      missed.incrementAndGet();
    } else {
      served.incrementAndGet();
    }
    return answer;
  }

  public int pendingCount() {
    return pending.size();
  }

  public long servedCount() {
    return served.get();
  }

  public long missedCount() {
    return missed.get();
  }

  public void clear() {
    pending.clear();
  }
}
