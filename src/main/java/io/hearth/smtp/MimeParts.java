package io.hearth.smtp;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enough MIME to find the calendar part of a real reply, and no more.
 *
 * <b>This is a reader, not a mail library.</b> The only question it has to answer is "did somebody's
 * calendar program put a `text/calendar` part in this message, and what did it say" -- and that
 * question has to survive Gmail, Outlook, Apple Mail and whatever a phone did on the way. So it
 * walks multiparts, decodes base64 and quoted-printable, and gives up gracefully on everything it
 * does not recognise rather than refusing the message.
 *
 * <b>Giving up gracefully is the whole design.</b> A parser that throws on an unexpected encoding
 * turns "somebody accepted an invitation" into an SMTP failure and a bounce to a person who did
 * nothing wrong. Anything unreadable comes back as an empty part list, the reply is ignored, and the
 * nudge loop chases them once more -- which is the mild failure rather than the sharp one.
 *
 * What it deliberately does not do: nested message/rfc822, encrypted parts, or anything about
 * signatures. An invitation reply that arrives inside a forwarded encrypted message is not a case a
 * community server needs to win.
 */
public final class MimeParts {
  /** a hard stop on recursion; a message that nests deeper than this is not a calendar reply */
  private static final int MAX_DEPTH = 8;
  /** and on how much of one part we will hold */
  private static final int MAX_PART_BYTES = 1024 * 1024;

  private MimeParts() {
  }

  /** one leaf of the message: what it claims to be, and what it says */
  public record Part(String contentType, Map<String, String> parameters, String text) {
    public boolean isCalendar() {
      return contentType.startsWith("text/calendar") || contentType.startsWith("application/ics")
          || contentType.equals("application/octet-stream") && named(".ics");
    }

    /** the METHOD the content type claims; the file's own METHOD is what actually decides */
    public String method() {
      String value = parameters.get("method");
      return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean named(String suffix) {
      String name = parameters.get("name");
      return name != null && name.toLowerCase(Locale.ROOT).endsWith(suffix);
    }
  }

  /** every leaf part of a message, in the order they appear */
  public static List<Part> of(byte[] message) {
    ArrayList<Part> out = new ArrayList<>();
    if (message == null || message.length == 0) {
      return out;
    }
    walk(new String(message, StandardCharsets.ISO_8859_1), out, 0);
    return out;
  }

  private static void walk(String block, List<Part> into, int depth) {
    if (depth > MAX_DEPTH || into.size() > 64) {
      return;
    }
    int blank = endOfHeaders(block);
    String headerText = blank < 0 ? block : block.substring(0, blank);
    String body = blank < 0 ? "" : block.substring(blank);
    Map<String, String> headers = headers(headerText);
    String contentType = headers.getOrDefault("content-type", "text/plain");
    Map<String, String> parameters = parameters(contentType);
    String type = bare(contentType);
    String encoding = headers.getOrDefault("content-transfer-encoding", "7bit")
        .trim().toLowerCase(Locale.ROOT);

    if (type.startsWith("multipart/")) {
      String boundary = parameters.get("boundary");
      if (boundary == null || boundary.isBlank()) {
        return;
      }
      for (String piece : split(body, boundary)) {
        walk(piece, into, depth + 1);
      }
      return;
    }
    String decoded = decode(body, encoding, parameters.get("charset"));
    if (decoded.length() > MAX_PART_BYTES) {
      decoded = decoded.substring(0, MAX_PART_BYTES);
    }
    into.add(new Part(type, parameters, decoded));
  }

  /** the first blank line, which is where headers stop and the body starts */
  private static int endOfHeaders(String block) {
    int crlf = block.indexOf("\r\n\r\n");
    int lf = block.indexOf("\n\n");
    if (crlf >= 0 && (lf < 0 || crlf < lf)) {
      return crlf + 4;
    }
    return lf < 0 ? -1 : lf + 2;
  }

  /** headers, lower-cased and unfolded; the last one of a name wins, as everywhere in mail */
  static Map<String, String> headers(String text) {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    String name = null;
    StringBuilder value = new StringBuilder();
    for (String line : text.split("\r\n|\n", -1)) {
      if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t') && name != null) {
        value.append(' ').append(line.trim());
        continue;
      }
      if (name != null) {
        out.put(name, value.toString().trim());
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        name = null;
        continue;
      }
      name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      value = new StringBuilder(line.substring(colon + 1).trim());
    }
    if (name != null) {
      out.put(name, value.toString().trim());
    }
    return out;
  }

  static String bare(String contentType) {
    int semi = contentType.indexOf(';');
    return (semi < 0 ? contentType : contentType.substring(0, semi))
        .trim().toLowerCase(Locale.ROOT);
  }

  /** `text/calendar; charset=UTF-8; method=REPLY` to a map, quotes off, keys lower-cased */
  static Map<String, String> parameters(String contentType) {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    boolean quoted = false;
    ArrayList<String> pieces = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int k = 0; k < contentType.length(); k++) {
      char ch = contentType.charAt(k);
      if (ch == '"') {
        quoted = !quoted;
        continue;
      }
      if (ch == ';' && !quoted) {
        pieces.add(current.toString());
        current = new StringBuilder();
        continue;
      }
      current.append(ch);
    }
    pieces.add(current.toString());
    for (int k = 1; k < pieces.size(); k++) {
      int equals = pieces.get(k).indexOf('=');
      if (equals <= 0) {
        continue;
      }
      out.put(pieces.get(k).substring(0, equals).trim().toLowerCase(Locale.ROOT),
          pieces.get(k).substring(equals + 1).trim());
    }
    return out;
  }

  /** the pieces between `--boundary` lines, without the preamble or the epilogue */
  static List<String> split(String body, String boundary) {
    ArrayList<String> out = new ArrayList<>();
    String marker = "--" + boundary;
    String[] lines = body.split("\r\n|\n", -1);
    StringBuilder current = null;
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.equals(marker)) {
        if (current != null) {
          out.add(current.toString());
        }
        current = new StringBuilder();
        continue;
      }
      if (trimmed.equals(marker + "--")) {
        if (current != null) {
          out.add(current.toString());
          current = null;
        }
        break;
      }
      if (current != null) {
        current.append(line).append("\r\n");
      }
    }
    if (current != null) {
      out.add(current.toString());
    }
    return out;
  }

  /**
   * base64, quoted-printable, or as it came.
   *
   * Both encodings exist because mail is eight bit unsafe and both are still in daily use --
   * Outlook likes base64 for calendar parts, Apple Mail likes quoted-printable, and a parser that
   * only speaks one of them works for half the people who answer.
   */
  static String decode(String body, String encoding, String charset) {
    byte[] bytes;
    if (encoding.startsWith("base64")) {
      StringBuilder packed = new StringBuilder(body.length());
      for (int k = 0; k < body.length(); k++) {
        char ch = body.charAt(k);
        if (ch != '\r' && ch != '\n' && ch != ' ' && ch != '\t') {
          packed.append(ch);
        }
      }
      try {
        bytes = Base64.getMimeDecoder().decode(packed.toString());
      } catch (IllegalArgumentException ex) {
        // a part we cannot decode is a part we do not act on, which is the mild failure
        return "";
      }
    } else if (encoding.startsWith("quoted-printable")) {
      bytes = quotedPrintable(body);
    } else {
      bytes = body.getBytes(StandardCharsets.ISO_8859_1);
    }
    Charset target = StandardCharsets.UTF_8;
    if (charset != null && !charset.isBlank()) {
      try {
        target = Charset.forName(charset.trim());
      } catch (Exception ex) {
        target = StandardCharsets.UTF_8;
      }
    }
    return new String(bytes, target);
  }

  /** `=3D` is `=`, and a lone `=` at the end of a line is a soft break that disappears */
  static byte[] quotedPrintable(String body) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(body.length());
    for (int k = 0; k < body.length(); k++) {
      char ch = body.charAt(k);
      if (ch != '=') {
        if (ch != '\r') {
          out.write(ch);
        }
        continue;
      }
      if (k + 1 < body.length() && (body.charAt(k + 1) == '\n' || body.charAt(k + 1) == '\r')) {
        // soft line break: skip the newline that follows
        k++;
        if (k + 1 < body.length() && body.charAt(k) == '\r' && body.charAt(k + 1) == '\n') {
          k++;
        }
        continue;
      }
      if (k + 2 < body.length()) {
        try {
          out.write(Integer.parseInt(body.substring(k + 1, k + 3), 16));
          k += 2;
          continue;
        } catch (NumberFormatException ex) {
          // a stray `=` that is not an escape; mail is full of them
        }
      }
      out.write(ch);
    }
    return out.toByteArray();
  }

  /** the address out of a `From:` header, however it was dressed up */
  public static String addressIn(String header) {
    if (header == null) {
      return null;
    }
    String value = header.trim();
    int open = value.lastIndexOf('<');
    int close = value.lastIndexOf('>');
    if (open >= 0 && close > open) {
      value = value.substring(open + 1, close);
    }
    value = value.trim().toLowerCase(Locale.ROOT);
    return value.isEmpty() || value.indexOf('@') <= 0 || value.indexOf(' ') >= 0 ? null : value;
  }
}
