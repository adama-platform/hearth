package io.hearth.mail;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A whole email as bytes, for the one message that cannot be built out of a subject and two strings.
 *
 * <b>A calendar invitation is not an attachment; it is a third alternative.</b> That distinction is
 * the entire reason this file exists. An `.ics` sent as an attachment is a file somebody has to
 * notice, open and confirm; the same bytes sent as `text/calendar; method=REQUEST` inside
 * `multipart/alternative` are an invitation that Gmail, Outlook and Apple Mail all draw as
 * accept/maybe/decline buttons above the message. One is a link with extra steps. The other is what
 * this feature is for.
 *
 * The shape every client agrees about, arrived at by everybody who has done this before us:
 *
 * <pre>
 *   multipart/mixed
 *     multipart/alternative
 *       text/plain
 *       text/html
 *       text/calendar; method=REQUEST     &lt;- the buttons come from this one
 *     application/ics (as an attachment)  &lt;- for the clients that ignore the above
 * </pre>
 *
 * The trailing attachment is not belt-and-braces so much as the price of the long tail: a client
 * that does not understand the calendar part shows a file somebody can open, which is worse than
 * buttons and much better than nothing.
 *
 * <b>Everything is base64.</b> Quoted-printable is smaller and is a swamp -- soft line breaks, the
 * 76 character rule, `=` needing escaping, trailing whitespace mattering. Base64 has one rule.
 */
public final class Mime {
  private static final String CRLF = "\r\n";

  private Mime() {
  }

  /**
   * Build the message.
   *
   * @param boundary must differ between the two levels, and must not appear in any part. Both are
   *     satisfied by deriving them from a random token the caller passes in -- a fixed boundary is
   *     a message that breaks the first time somebody quotes it in a reply.
   */
  public static byte[] withCalendar(String from, String to, String subject, String text,
                                    String html, String ics, String method, String filename,
                                    String boundary) {
    String outer = "hearth-" + boundary;
    String inner = "hearth-alt-" + boundary;
    StringBuilder out = new StringBuilder();
    out.append("From: ").append(header(from)).append(CRLF);
    out.append("To: ").append(header(to)).append(CRLF);
    out.append("Subject: ").append(encodedWord(subject)).append(CRLF);
    out.append("MIME-Version: 1.0").append(CRLF);
    out.append("Content-Type: multipart/mixed; boundary=\"").append(outer).append('"').append(CRLF);
    out.append(CRLF);

    out.append("--").append(outer).append(CRLF);
    out.append("Content-Type: multipart/alternative; boundary=\"").append(inner).append('"')
        .append(CRLF).append(CRLF);

    part(out, inner, "text/plain; charset=UTF-8", null, text);
    part(out, inner, "text/html; charset=UTF-8", null, html);
    // the one that matters. `method` has to be on the content type as well as inside the file:
    // clients read the header to decide whether this is an invitation before parsing anything.
    part(out, inner, "text/calendar; charset=UTF-8; method=" + method, null, ics);
    out.append("--").append(inner).append("--").append(CRLF).append(CRLF);

    part(out, outer, "application/ics; name=\"" + filename + "\"",
        "attachment; filename=\"" + filename + "\"", ics);
    out.append("--").append(outer).append("--").append(CRLF);
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void part(StringBuilder out, String boundary, String contentType,
                           String disposition, String body) {
    out.append("--").append(boundary).append(CRLF);
    out.append("Content-Type: ").append(contentType).append(CRLF);
    if (disposition != null) {
      out.append("Content-Disposition: ").append(disposition).append(CRLF);
    }
    out.append("Content-Transfer-Encoding: base64").append(CRLF).append(CRLF);
    out.append(base64(body)).append(CRLF);
  }

  /** base64 in 76 character lines, which is the one rule the encoding has */
  static String base64(String body) {
    String encoded = Base64.getEncoder()
        .encodeToString((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
    StringBuilder out = new StringBuilder(encoded.length() + encoded.length() / 76 * 2);
    for (int k = 0; k < encoded.length(); k += 76) {
      out.append(encoded, k, Math.min(k + 76, encoded.length())).append(CRLF);
    }
    return out.toString();
  }

  /**
   * A header value with nothing header-shaped left in it.
   *
   * A newline in a display name is header injection -- an extra `Bcc:` written by whoever chose
   * their own name -- and this is the one place in the mail path where a person's typing becomes a
   * header rather than a body.
   */
  static String header(String value) {
    return value == null ? "" : value.replaceAll("[\\r\\n]", " ").trim();
  }

  /**
   * A subject that may contain anything, encoded so that it survives.
   *
   * RFC 2047 for the non-ASCII case, which is any community whose events have an accent in the
   * name. Plain when it is plain, because an encoded word in a subject that did not need one is a
   * subject some old client shows verbatim.
   */
  static String encodedWord(String subject) {
    String clean = header(subject);
    boolean plain = true;
    for (int k = 0; k < clean.length(); k++) {
      if (clean.charAt(k) > 126 || clean.charAt(k) < 32) {
        plain = false;
        break;
      }
    }
    if (plain) {
      return clean;
    }
    return "=?UTF-8?B?" + Base64.getEncoder()
        .encodeToString(clean.getBytes(StandardCharsets.UTF_8)) + "?=";
  }
}
