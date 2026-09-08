package io.hearth.inbox;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A whole message, taken apart: every part, its bytes, and what it says it is.
 *
 * <b>This is the fuller sibling of {@link io.hearth.smtp.MimeParts}, and the difference is bytes.</b>
 * That one answers a single question -- did a calendar program put a `text/calendar` part in here --
 * and decodes everything to text on the way, which is right for reading an RRULE and useless for
 * handing somebody a photograph. This keeps the decoded octets of every leaf, its filename, whether
 * it was meant to be shown inline or saved, and the `Content-ID` an HTML body refers to it by.
 *
 * <b>Lenient in, strict out.</b> Real mail is malformed constantly: a boundary that never closes, a
 * charset that does not exist, base64 with a stray character, a `Content-Type` with an unquoted
 * semicolon in it. None of those is a reason to refuse somebody's mail, so every one of them
 * degrades to "this part is bytes we could not read" rather than throwing. What is *not* lenient is
 * what happens to the result afterwards -- see {@link Safety}, which decides what may ever leave
 * this server.
 *
 * <b>Everything is bounded.</b> Depth, part count and total size, because a message is attacker
 * -supplied input and a nesting bomb is four lines of Python. The caps are generous enough that no
 * real message has ever come near them.
 */
public final class MimeTree {
  /** how deep a real message nests; signed-and-encrypted-and-forwarded is about four */
  public static final int MAX_DEPTH = 12;
  /** how many leaves one message may have */
  public static final int MAX_PARTS = 250;
  /** the longest header line we will reassemble before deciding somebody is playing */
  private static final int MAX_HEADER_BYTES = 64 * 1024;

  private MimeTree() {
  }

  /**
   * One part of a message.
   *
   * `path` is what a download URL carries: `1.2` is the second child of the first child. It is
   * derived from position rather than stored, so it is stable for a message that never changes --
   * and the raw bytes are on disk unchanged, so the same path always names the same part.
   */
  public record Part(String path, String contentType, Map<String, String> parameters,
                     Map<String, String> headers, String disposition, String filename,
                     String contentId, byte[] content, List<Part> children) {

    public boolean isMultipart() {
      return contentType.startsWith("multipart/");
    }

    public boolean isText() {
      return contentType.startsWith("text/");
    }

    public boolean isCalendar() {
      return contentType.startsWith("text/calendar") || contentType.startsWith("application/ics")
          || (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".ics"));
    }

    /**
     * Is this something a person is meant to open, rather than part of the body?
     *
     * The disposition is a hint and a wrong one often enough that the filename decides too: plenty
     * of mailers attach a document with no disposition at all, and a part with a name is a file
     * whatever the header claims. An inline image referred to by an HTML body is deliberately not
     * an attachment -- it is furniture, and listing it beside a real document trains somebody to
     * ignore the list.
     */
    public boolean isAttachment() {
      if (isMultipart()) {
        return false;
      }
      if ("attachment".equalsIgnoreCase(disposition)) {
        return true;
      }
      if (contentId != null && !contentId.isBlank()) {
        return false;
      }
      return filename != null && !filename.isBlank();
    }

    /** the text of this part, decoded with whatever charset it claimed */
    public String text() {
      return decodeText(content, parameters.get("charset"));
    }

    public int size() {
      return content == null ? 0 : content.length;
    }
  }

  /** the parsed message: its top-level headers and the part tree under them */
  public record Message(Map<String, String> headers, Part root) {
    public String header(String name) {
      String value = headers.get(name.toLowerCase(Locale.ROOT));
      return value == null ? "" : value;
    }

    /** every leaf, depth first, in the order somebody reading the source would meet them */
    public List<Part> leaves() {
      ArrayList<Part> out = new ArrayList<>();
      collect(root, out);
      return out;
    }

    private static void collect(Part part, List<Part> into) {
      if (part == null) {
        return;
      }
      if (part.children().isEmpty()) {
        into.add(part);
        return;
      }
      for (Part child : part.children()) {
        collect(child, into);
      }
    }

    public Part byPath(String path) {
      for (Part part : leaves()) {
        if (part.path().equals(path)) {
          return part;
        }
      }
      return null;
    }
  }

  /** take a message apart */
  public static Message parse(byte[] raw) {
    if (raw == null || raw.length == 0) {
      return new Message(Map.of(), leaf("1", Map.of(), new byte[0]));
    }
    int split = endOfHeaders(raw, 0, raw.length);
    Map<String, String> headers = headers(raw, 0, split);
    int[] budget = {MAX_PARTS};
    Part root = part("1", headers, raw, bodyStart(raw, split), raw.length, 0, budget);
    return new Message(headers, root);
  }

  // ---- the walk ----------------------------------------------------------------------------------

  private static Part part(String path, Map<String, String> headers, byte[] raw, int from, int to,
                           int depth, int[] budget) {
    String rawType = headers.getOrDefault("content-type", "text/plain");
    String contentType = bare(rawType);
    Map<String, String> parameters = parameters(rawType);
    String disposition = bare(headers.getOrDefault("content-disposition", ""));
    Map<String, String> dispositionParameters = parameters(headers.getOrDefault(
        "content-disposition", ""));
    String filename = filenameFrom(dispositionParameters, parameters);
    String contentId = trimAngles(headers.get("content-id"));

    if (contentType.startsWith("multipart/") && depth < MAX_DEPTH) {
      String boundary = parameters.get("boundary");
      if (boundary != null && !boundary.isBlank()) {
        ArrayList<Part> children = new ArrayList<>();
        int index = 0;
        for (int[] span : split(raw, from, to, boundary)) {
          if (budget[0]-- <= 0) {
            break;
          }
          index++;
          int headerEnd = endOfHeaders(raw, span[0], span[1]);
          Map<String, String> childHeaders = headers(raw, span[0], headerEnd);
          children.add(part(path + "." + index, childHeaders, raw,
              bodyStart(raw, headerEnd), span[1], depth + 1, budget));
        }
        return new Part(path, contentType, parameters, headers, disposition, filename, contentId,
            new byte[0], List.copyOf(children));
      }
      // a multipart with no boundary is not a multipart; treat what is there as one blob rather
      // than losing the message
    }

    byte[] content = decode(raw, from, to, headers.getOrDefault("content-transfer-encoding", ""));
    return new Part(path, contentType, parameters, headers, disposition, filename, contentId,
        content, List.of());
  }

  private static Part leaf(String path, Map<String, String> headers, byte[] content) {
    return new Part(path, "text/plain", Map.of(), headers, "", null, null, content, List.of());
  }

  /**
   * The spans between one boundary and the next.
   *
   * <b>An unterminated multipart still yields its parts.</b> The closing `--boundary--` is missing
   * from more real mail than anybody would believe, usually because something truncated the message,
   * and treating that as unparseable throws away every part that did arrive intact.
   */
  static List<int[]> split(byte[] raw, int from, int to, String boundary) {
    byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
    ArrayList<int[]> spans = new ArrayList<>();
    int cursor = from;
    int openedAt = -1;
    while (cursor < to) {
      int lineEnd = lineEnd(raw, cursor, to);
      if (isBoundaryLine(raw, cursor, lineEnd, marker)) {
        if (openedAt >= 0) {
          spans.add(new int[]{openedAt, trimTrailingNewline(raw, openedAt, cursor)});
        }
        boolean closing = lineEnd - cursor >= marker.length + 2
            && raw[cursor + marker.length] == '-' && raw[cursor + marker.length + 1] == '-';
        if (closing) {
          return spans;
        }
        openedAt = nextLine(raw, lineEnd, to);
      }
      cursor = nextLine(raw, lineEnd, to);
    }
    if (openedAt >= 0 && openedAt < to) {
      spans.add(new int[]{openedAt, to});
    }
    return spans;
  }

  private static boolean isBoundaryLine(byte[] raw, int from, int lineEnd, byte[] marker) {
    if (lineEnd - from < marker.length) {
      return false;
    }
    for (int k = 0; k < marker.length; k++) {
      if (raw[from + k] != marker[k]) {
        return false;
      }
    }
    // Anything after the boundary must be the closing "--" or whitespace.
    //
    // Without this, a boundary of "x" matches a part boundary of "xyz" and the message comes apart
    // in the wrong places -- which is not hypothetical, because plenty of mailers choose short
    // boundaries and nest them.
    for (int k = from + marker.length; k < lineEnd; k++) {
      byte ch = raw[k];
      if (ch == '-' && k < from + marker.length + 2) {
        continue;
      }
      if (ch != ' ' && ch != '\t' && ch != '\r') {
        return false;
      }
    }
    return true;
  }

  private static int trimTrailingNewline(byte[] raw, int from, int at) {
    int end = at;
    if (end > from && raw[end - 1] == '\n') {
      end--;
    }
    if (end > from && raw[end - 1] == '\r') {
      end--;
    }
    return end;
  }

  /** where this line's content stops: before the CR of a CRLF, or before a bare LF */
  private static int lineEnd(byte[] raw, int from, int to) {
    for (int k = from; k < to; k++) {
      if (raw[k] == '\n') {
        return k > from && raw[k - 1] == '\r' ? k - 1 : k;
      }
    }
    return to;
  }

  private static int nextLine(byte[] raw, int lineEnd, int to) {
    int k = lineEnd;
    while (k < to && raw[k] != '\n') {
      k++;
    }
    return Math.min(to, k + 1);
  }

  // ---- headers -----------------------------------------------------------------------------------

  /** where the header block ends: the first blank line, or the whole thing if there is none */
  static int endOfHeaders(byte[] raw, int from, int to) {
    for (int k = from; k + 1 < to; k++) {
      if (raw[k] == '\n' && raw[k + 1] == '\n') {
        return k;
      }
      if (k + 3 < to && raw[k] == '\r' && raw[k + 1] == '\n' && raw[k + 2] == '\r'
          && raw[k + 3] == '\n') {
        return k;
      }
    }
    return to;
  }

  private static int bodyStart(byte[] raw, int headerEnd) {
    int k = headerEnd;
    if (k < raw.length && raw[k] == '\r') {
      k++;
    }
    if (k < raw.length && raw[k] == '\n') {
      k++;
    }
    if (k < raw.length && raw[k] == '\r') {
      k++;
    }
    if (k < raw.length && raw[k] == '\n') {
      k++;
    }
    return k;
  }

  /**
   * The headers of a block, unfolded, lowercased by name, first occurrence winning.
   *
   * First rather than last, for the same reason {@link io.hearth.smtp.Envelope} does it: a message
   * with two Subject lines is either broken or trying something, and neither is a reason to prefer
   * the second. Encoded words are decoded here, because every caller wants the words rather than
   * `=?UTF-8?B?...?=`.
   */
  static Map<String, String> headers(byte[] raw, int from, int to) {
    LinkedHashMap<String, String> headers = new LinkedHashMap<>();
    StringBuilder line = new StringBuilder();
    int cursor = from;
    while (cursor < to) {
      int end = lineEnd(raw, cursor, to);
      String text = new String(raw, cursor, Math.max(0, end - cursor), StandardCharsets.ISO_8859_1);
      if (!text.isEmpty() && (text.charAt(0) == ' ' || text.charAt(0) == '\t')
          && line.length() > 0) {
        // folded continuation: one space, whatever the original whitespace was
        if (line.length() < MAX_HEADER_BYTES) {
          line.append(' ').append(text.trim());
        }
      } else {
        take(headers, line.toString());
        line.setLength(0);
        line.append(text);
      }
      cursor = nextLine(raw, end, to);
    }
    take(headers, line.toString());
    return headers;
  }

  private static void take(Map<String, String> headers, String line) {
    int colon = line.indexOf(':');
    if (colon <= 0) {
      return;
    }
    headers.putIfAbsent(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
        decodeWords(line.substring(colon + 1).trim()));
  }

  /** `text/plain; charset=utf-8` becomes `text/plain` */
  static String bare(String value) {
    if (value == null) {
      return "";
    }
    int semicolon = value.indexOf(';');
    return (semicolon < 0 ? value : value.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
  }

  /**
   * The parameters of a structured header, quoted values and RFC 2231 continuations included.
   *
   * `filename*=UTF-8''caf%C3%A9.pdf` and `filename*0=`/`filename*1=` both exist in real mail, and a
   * parser that ignores them hands somebody a document called `null`.
   */
  static Map<String, String> parameters(String value) {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    if (value == null) {
      return out;
    }
    LinkedHashMap<String, StringBuilder> continued = new LinkedHashMap<>();
    for (String piece : splitParameters(value)) {
      int equals = piece.indexOf('=');
      if (equals <= 0) {
        continue;
      }
      String name = piece.substring(0, equals).trim().toLowerCase(Locale.ROOT);
      String raw = piece.substring(equals + 1).trim();
      if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.endsWith("\"")) {
        raw = raw.substring(1, raw.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
      }
      boolean extended = name.endsWith("*");
      String base = name;
      if (extended) {
        base = name.substring(0, name.length() - 1);
      }
      int star = base.indexOf('*');
      if (star > 0) {
        // filename*0, filename*1 ... assembled in the order they appear
        String stem = base.substring(0, star);
        continued.computeIfAbsent(stem, key -> new StringBuilder())
            .append(extended ? decodeExtended(raw) : raw);
        continue;
      }
      out.putIfAbsent(base, extended ? decodeExtended(raw) : raw);
    }
    for (Map.Entry<String, StringBuilder> entry : continued.entrySet()) {
      out.putIfAbsent(entry.getKey(), entry.getValue().toString());
    }
    return out;
  }

  /** split on semicolons that are not inside quotes */
  private static List<String> splitParameters(String value) {
    ArrayList<String> pieces = new ArrayList<>();
    boolean quoted = false;
    int start = 0;
    for (int k = 0; k < value.length(); k++) {
      char ch = value.charAt(k);
      if (ch == '"' && (k == 0 || value.charAt(k - 1) != '\\')) {
        quoted = !quoted;
      } else if (ch == ';' && !quoted) {
        pieces.add(value.substring(start, k));
        start = k + 1;
      }
    }
    pieces.add(value.substring(start));
    if (!pieces.isEmpty()) {
      pieces.remove(0);
    }
    return pieces;
  }

  /** `UTF-8''caf%C3%A9.pdf` */
  static String decodeExtended(String value) {
    String work = value;
    Charset charset = StandardCharsets.UTF_8;
    int first = work.indexOf('\'');
    if (first >= 0) {
      int second = work.indexOf('\'', first + 1);
      if (second > first) {
        charset = charsetOr(work.substring(0, first), StandardCharsets.UTF_8);
        work = work.substring(second + 1);
      }
    }
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    for (int k = 0; k < work.length(); k++) {
      char ch = work.charAt(k);
      if (ch == '%' && k + 2 < work.length()) {
        int high = Character.digit(work.charAt(k + 1), 16);
        int low = Character.digit(work.charAt(k + 2), 16);
        if (high >= 0 && low >= 0) {
          bytes.write(high * 16 + low);
          k += 2;
          continue;
        }
      }
      bytes.write(ch);
    }
    return new String(bytes.toByteArray(), charset);
  }

  /**
   * RFC 2047 encoded words: `=?UTF-8?B?SGVsbG8=?=`.
   *
   * Adjacent encoded words with only whitespace between them are joined with nothing, which is what
   * the RFC asks for and is what makes a subject split across two words read as one sentence rather
   * than as two halves with a gap in the middle of a character.
   */
  static String decodeWords(String value) {
    if (value == null || value.indexOf("=?") < 0) {
      return value == null ? "" : value;
    }
    StringBuilder out = new StringBuilder(value.length());
    int cursor = 0;
    boolean previousWasWord = false;
    while (cursor < value.length()) {
      int start = value.indexOf("=?", cursor);
      if (start < 0) {
        out.append(value, cursor, value.length());
        break;
      }
      int charsetEnd = value.indexOf('?', start + 2);
      int encodingEnd = charsetEnd < 0 ? -1 : value.indexOf('?', charsetEnd + 1);
      int end = encodingEnd < 0 ? -1 : value.indexOf("?=", encodingEnd + 1);
      if (charsetEnd < 0 || encodingEnd != charsetEnd + 2 || end < 0) {
        out.append(value, cursor, start + 2);
        cursor = start + 2;
        previousWasWord = false;
        continue;
      }
      String between = value.substring(cursor, start);
      if (!(previousWasWord && between.isBlank())) {
        out.append(between);
      }
      Charset charset = charsetOr(value.substring(start + 2, charsetEnd), StandardCharsets.UTF_8);
      char encoding = Character.toUpperCase(value.charAt(charsetEnd + 1));
      String payload = value.substring(encodingEnd + 1, end);
      out.append(decodeWord(payload, encoding, charset));
      cursor = end + 2;
      previousWasWord = true;
    }
    return out.toString();
  }

  private static String decodeWord(String payload, char encoding, Charset charset) {
    try {
      if (encoding == 'B') {
        return new String(Base64.getMimeDecoder().decode(payload), charset);
      }
      if (encoding == 'Q') {
        // in an encoded word, and only there, an underscore is a space
        return new String(quotedPrintable(payload.replace('_', ' ')
            .getBytes(StandardCharsets.ISO_8859_1), 0, payload.length()), charset);
      }
    } catch (RuntimeException ex) {
      // a malformed encoded word keeps its own text rather than eating the header
    }
    return payload;
  }

  private static String trimAngles(String value) {
    if (value == null) {
      return null;
    }
    String clean = value.trim();
    if (clean.startsWith("<") && clean.endsWith(">") && clean.length() > 1) {
      return clean.substring(1, clean.length() - 1);
    }
    return clean.isEmpty() ? null : clean;
  }

  /** the disposition's filename wins over the content type's name, which is the older spelling */
  private static String filenameFrom(Map<String, String> disposition, Map<String, String> type) {
    String name = disposition.get("filename");
    if (name == null || name.isBlank()) {
      name = type.get("name");
    }
    return name == null || name.isBlank() ? null : decodeWords(name).trim();
  }

  // ---- decoding ----------------------------------------------------------------------------------

  /** the octets of a part, with its transfer encoding taken off */
  static byte[] decode(byte[] raw, int from, int to, String encoding) {
    String how = bare(encoding);
    int length = Math.max(0, to - from);
    if (how.equals("base64")) {
      try {
        return Base64.getMimeDecoder().decode(
            new String(raw, from, length, StandardCharsets.ISO_8859_1));
      } catch (IllegalArgumentException ex) {
        // Base64 with something in it that is not base64.
        //
        // The lenient decoder already ignores whitespace and unknown characters at the end; what
        // reaches here is genuinely broken, and the honest answer is the bytes as they lie rather
        // than nothing at all -- somebody can still download the raw message.
        return java.util.Arrays.copyOfRange(raw, from, to);
      }
    }
    if (how.equals("quoted-printable")) {
      return quotedPrintable(raw, from, to);
    }
    return java.util.Arrays.copyOfRange(raw, from, to);
  }

  static byte[] quotedPrintable(byte[] raw, int from, int to) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(16, to - from));
    for (int k = from; k < to; k++) {
      byte ch = raw[k];
      if (ch != '=') {
        out.write(ch);
        continue;
      }
      if (k + 1 < to && raw[k + 1] == '\n') {
        // a soft line break: the newline is formatting and vanishes
        k += 1;
        continue;
      }
      if (k + 2 < to && raw[k + 1] == '\r' && raw[k + 2] == '\n') {
        k += 2;
        continue;
      }
      if (k + 2 < to) {
        int high = Character.digit((char) raw[k + 1], 16);
        int low = Character.digit((char) raw[k + 2], 16);
        if (high >= 0 && low >= 0) {
          out.write(high * 16 + low);
          k += 2;
          continue;
        }
      }
      // a lone '=' that is not an escape; real mail has these and they are literal
      out.write(ch);
    }
    return out.toByteArray();
  }

  /**
   * Bytes to text, with a charset that may be a lie.
   *
   * Replacing rather than reporting: a body with one bad byte in it is still somebody's message,
   * and refusing to show any of it because byte 4,000 is not valid UTF-8 helps nobody. The
   * replacement character is visible, which is the right amount of honesty about it.
   */
  static String decodeText(byte[] content, String charsetName) {
    if (content == null || content.length == 0) {
      return "";
    }
    Charset charset = charsetOr(charsetName, StandardCharsets.UTF_8);
    CharsetDecoder decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE);
    try {
      return decoder.decode(java.nio.ByteBuffer.wrap(content)).toString();
    } catch (Exception ex) {
      return new String(content, StandardCharsets.UTF_8);
    }
  }

  static Charset charsetOr(String name, Charset fallback) {
    if (name == null || name.isBlank()) {
      return fallback;
    }
    String clean = name.trim().replaceAll("^\"|\"$", "");
    try {
      return Charset.forName(clean);
    } catch (Exception ex) {
      // A charset this JVM has never heard of, which happens: `unicode-1-1-utf-8`, `cp-850`, and a
      // long tail of typos. Guessing UTF-8 shows most of the message; refusing shows none of it.
      return fallback;
    }
  }
}
