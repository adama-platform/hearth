package io.hearth.people;

import java.util.Base64;

/**
 * The one pixel image that says an invitation was opened.
 *
 * A transparent GIF at a path carrying the invitation's token. A mail client that loads remote
 * images fetches it; one that does not, does not. That makes this exactly as good as most read
 * receipts and no better, which is worth being precise about because the number it produces will be
 * used to make decisions:
 *
 * - **An open means the message was rendered with images on.** Good evidence somebody saw it.
 * - **No open means no evidence.** It does not mean unread. Most clients block remote images by
 *   default now, and a plain-text reader will never fetch this at all.
 *
 * The admin screen says "no evidence" rather than "unopened" for that reason. A tracking pixel that
 * gets reported as a read rate is a tracking pixel that will be believed.
 *
 * The bytes are the smallest valid transparent GIF, 43 of them, inline because there is nothing on
 * disk to serve and a request budget to keep.
 */
public final class InvitePixel {
  /** where the pixel lives; under .well-known so it reads as infrastructure rather than content */
  public static final String PREFIX = "/.well-known/hearth-invite/";

  /** a 1x1 transparent GIF */
  public static final byte[] GIF = Base64.getDecoder().decode(
      "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");

  private InvitePixel() {
  }

  public static boolean isPixel(String path) {
    return path != null && path.startsWith(PREFIX) && path.length() > PREFIX.length();
  }

  /** the token out of a pixel path; never trusted beyond being looked up */
  public static String tokenOf(String path) {
    if (!isPixel(path)) {
      return null;
    }
    String token = path.substring(PREFIX.length());
    int dot = token.indexOf('.');
    // the path ends .gif so it looks like an image to anything that inspects it
    return dot < 0 ? token : token.substring(0, dot);
  }

  /** the absolute URL to embed, which has to be absolute because it is fetched from a mail client */
  public static String urlFor(String domain, String token, boolean https) {
    return (https ? "https://" : "http://") + domain + PREFIX + token + ".gif";
  }
}
