package io.hearth.smtp;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One message, as it arrived.
 *
 * The envelope -- who the sending server said it was from, and who it said it was for -- is kept
 * separate from the headers inside the message, because they routinely disagree and only the
 * envelope decided where this went. A `From:` header is a claim the message makes about itself; the
 * envelope sender is what the connection actually said. Conflating them is how a mail system ends
 * up routing on something a spammer chose.
 *
 * The body is kept as raw bytes. Parsing MIME is a real piece of work and nothing here needs it
 * yet; a few headers are pulled off the front so a log line can say something useful, and the rest
 * is handed on untouched, so whatever parses it properly later gets exactly what was sent.
 */
public record Envelope(String from, List<String> recipients, byte[] data, String remoteAddress,
                       String helo, String domain, long receivedAtMillis) {

  /** how big this was on the wire */
  public int size() {
    return data == null ? 0 : data.length;
  }

  /**
   * The headers at the front, as far as the blank line.
   *
   * Deliberately shallow: wrapped lines are joined and that is all -- no decoding of encoded words,
   * no MIME, no structured parsing. It exists so a handler can put a subject in a log line, and
   * anything that needs more should parse {@link #data()} properly rather than trusting this.
   *
   * The first occurrence of a header wins. A message with two Subject lines is either broken or
   * trying something, and neither is a reason to prefer the second.
   */
  public Map<String, String> headers() {
    LinkedHashMap<String, String> headers = new LinkedHashMap<>();
    if (data == null) {
      return headers;
    }
    String text = new String(data, StandardCharsets.UTF_8);
    String name = null;
    StringBuilder value = new StringBuilder();
    for (String line : text.split("\r?\n", -1)) {
      if (line.isEmpty()) {
        break;
      }
      if ((line.startsWith(" ") || line.startsWith("\t")) && name != null) {
        value.append(' ').append(line.trim());
        continue;
      }
      if (name != null) {
        headers.putIfAbsent(name, value.toString().trim());
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        name = null;
        continue;
      }
      name = line.substring(0, colon).trim().toLowerCase();
      value = new StringBuilder(line.substring(colon + 1).trim());
    }
    if (name != null) {
      headers.putIfAbsent(name, value.toString().trim());
    }
    return headers;
  }

  public String header(String name) {
    return headers().get(name == null ? "" : name.toLowerCase());
  }

  public String subject() {
    String subject = header("subject");
    return subject == null ? "" : subject;
  }

  /** the body, or as much of it as is worth showing */
  public String bodyPreview(int limit) {
    if (data == null) {
      return "";
    }
    String text = new String(data, StandardCharsets.UTF_8);
    int blank = text.indexOf("\r\n\r\n");
    int skip = 4;
    if (blank < 0) {
      blank = text.indexOf("\n\n");
      skip = 2;
    }
    String body = blank < 0 ? "" : text.substring(Math.min(text.length(), blank + skip));
    body = body.strip();
    return body.length() <= limit ? body : body.substring(0, limit) + "…";
  }
}
