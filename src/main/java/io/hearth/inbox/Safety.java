package io.hearth.inbox;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What may leave this server, and what a file has to actually be before it does.
 *
 * <b>An allow list, closed, by extension.</b> A deny list of dangerous extensions is wrong the day
 * somebody finds the next one, and there have been dozens -- `.scr`, `.pif`, `.lnk`, `.iso`,
 * `.msix`, `.appref-ms`. A closed list of what is *allowed* is wrong in the harmless direction: a
 * new document format is refused until somebody adds it, which is a nuisance rather than a
 * compromise.
 *
 * <b>Nothing is hidden.</b> A refused part is still listed with its name and its size, and the raw
 * message is still downloadable -- so the person knows exactly what arrived and can get at it
 * deliberately if they mean to. Silently dropping an attachment is how somebody misses a contract
 * and never finds out there was one.
 *
 * <b>The bytes have to agree with the name.</b> An extension is a claim made by whoever sent the
 * message; for every format with a recognisable signature this checks it, so `invoice.pdf` that
 * begins `MZ` is refused rather than served with a content type that invites a browser to be
 * helpful about it.
 *
 * <b>Images are opened far enough to know they are images.</b> Dimensions come out of the header
 * without decoding a pixel, which catches both the file that is not what it says and the 60,000 by
 * 60,000 PNG that is nine kilobytes on the wire and four gigabytes in a decoder.
 */
public final class Safety {
  /** past this, a picture is a way to make somebody's browser fall over */
  public static final long MAX_PIXELS = 80_000_000L;
  /** the largest attachment worth handing back; the message ceiling is smaller anyway */
  public static final int MAX_PART_BYTES = 25 * 1024 * 1024;

  /**
   * What may be downloaded, and what this server will call it.
   *
   * The content type served is <b>this</b> table's, never the sender's: a message may say whatever
   * it likes about its own attachment, and a browser that believes it is the whole attack. Nothing
   * here is a type a browser will execute, render as a document in the page's own origin, or treat
   * as script.
   */
  private static final Map<String, String> ALLOWED = Map.ofEntries(
      Map.entry("pdf", "application/pdf"),
      Map.entry("png", "image/png"),
      Map.entry("jpg", "image/jpeg"),
      Map.entry("jpeg", "image/jpeg"),
      Map.entry("gif", "image/gif"),
      Map.entry("webp", "image/webp"),
      Map.entry("heic", "image/heic"),
      Map.entry("txt", "text/plain"),
      Map.entry("csv", "text/csv"),
      Map.entry("md", "text/plain"),
      Map.entry("log", "text/plain"),
      Map.entry("ics", "text/calendar"),
      Map.entry("vcf", "text/vcard"),
      Map.entry("doc", "application/msword"),
      Map.entry("docx",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
      Map.entry("xls", "application/vnd.ms-excel"),
      Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
      Map.entry("ppt", "application/vnd.ms-powerpoint"),
      Map.entry("pptx",
          "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
      Map.entry("odt", "application/vnd.oasis.opendocument.text"),
      Map.entry("ods", "application/vnd.oasis.opendocument.spreadsheet"),
      Map.entry("mp3", "audio/mpeg"),
      Map.entry("m4a", "audio/mp4"),
      Map.entry("wav", "audio/wav"),
      Map.entry("mp4", "video/mp4"),
      Map.entry("mov", "video/quicktime"));

  /**
   * Types refused by name rather than by absence, so the reason can be a sentence.
   *
   * Everything not on the allow list is refused anyway; these are the ones somebody will actually
   * meet, and "we do not hand back .zip files because we cannot see inside one" is worth saying.
   */
  private static final Map<String, String> EXPLAINED = Map.ofEntries(
      Map.entry("zip", "an archive, and this server cannot see what is inside one"),
      Map.entry("rar", "an archive, and this server cannot see what is inside one"),
      Map.entry("7z", "an archive, and this server cannot see what is inside one"),
      Map.entry("gz", "an archive, and this server cannot see what is inside one"),
      Map.entry("tar", "an archive, and this server cannot see what is inside one"),
      Map.entry("html", "a document that can carry script and run it as you"),
      Map.entry("htm", "a document that can carry script and run it as you"),
      Map.entry("svg", "a picture that can carry script; it arrives looking like an image"),
      Map.entry("exe", "a program"),
      Map.entry("msi", "an installer"),
      Map.entry("dll", "a program"),
      Map.entry("bat", "a script"),
      Map.entry("cmd", "a script"),
      Map.entry("com", "a program"),
      Map.entry("scr", "a program wearing a screensaver's name"),
      Map.entry("pif", "a program wearing a shortcut's name"),
      Map.entry("lnk", "a shortcut, which can point at anything"),
      Map.entry("vbs", "a script"),
      Map.entry("js", "a script"),
      Map.entry("jar", "a program"),
      Map.entry("ps1", "a script"),
      Map.entry("sh", "a script"),
      Map.entry("app", "a program"),
      Map.entry("dmg", "a disk image"),
      Map.entry("iso", "a disk image"),
      Map.entry("apk", "an application package"),
      Map.entry("docm", "a document that carries macros"),
      Map.entry("xlsm", "a spreadsheet that carries macros"),
      Map.entry("pptm", "a presentation that carries macros"));

  /** what a downloadable part is, once it has been looked at */
  public record Verdict(boolean allowed, String contentType, String reason, int width,
                        int height) {
    public static Verdict no(String reason) {
      return new Verdict(false, "", reason, 0, 0);
    }

    public static Verdict yes(String contentType) {
      return new Verdict(true, contentType, "", 0, 0);
    }

    public boolean isImage() {
      return allowed && contentType.startsWith("image/");
    }
  }

  private Safety() {
  }

  /**
   * The extension of a filename, as a lowercase word with no dot.
   *
   * The <b>last</b> one, deliberately. `invoice.pdf.exe` is an executable, and reading the first
   * extension is precisely the mistake the double-extension trick exists to provoke. A name with no
   * dot has no extension and is refused by the allow list like anything else.
   */
  public static String extensionOf(String filename) {
    if (filename == null) {
      return "";
    }
    // any path a sender put in the name is dropped before it is looked at; a filename is a name
    String name = filename.replace('\\', '/');
    int slash = name.lastIndexOf('/');
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) {
      return "";
    }
    return name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  /**
   * A name safe to put in a Content-Disposition header and on somebody's disk.
   *
   * Nothing from a message goes into a header unfiltered: a filename carrying a CRLF is header
   * injection, and one carrying `../` is a path. What comes back has neither and keeps its
   * extension, because the extension is what the operating system will use to decide what the file
   * is.
   */
  public static String safeName(String filename, String fallback) {
    String name = filename == null ? "" : filename;
    name = name.replace('\\', '/');
    int slash = name.lastIndexOf('/');
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    StringBuilder out = new StringBuilder(name.length());
    for (int k = 0; k < name.length() && out.length() < 120; k++) {
      char ch = name.charAt(k);
      boolean ok = Character.isLetterOrDigit(ch) || ch == '.' || ch == '-' || ch == '_'
          || ch == ' ' || ch == '(' || ch == ')' || ch == '+' || ch == ',';
      out.append(ok ? ch : '_');
    }
    String clean = out.toString().trim();
    while (clean.startsWith(".")) {
      clean = clean.substring(1);
    }
    return clean.isBlank() ? fallback : clean;
  }

  /** why a type is refused, in words, or null when it is not one this server knows to explain */
  public static String explain(String extension) {
    return EXPLAINED.get(extension);
  }

  public static boolean isAllowedExtension(String extension) {
    return ALLOWED.containsKey(extension);
  }

  public static Set<String> allowed() {
    return ALLOWED.keySet();
  }

  /**
   * May this part be handed back, and as what?
   *
   * Three questions in order, each of which can only say no: is the extension on the list, do the
   * bytes look like what the extension claims, and -- for a picture -- are its dimensions ones a
   * browser can survive.
   */
  public static Verdict check(String filename, byte[] content) {
    String extension = extensionOf(filename);
    if (extension.isEmpty()) {
      return Verdict.no("it has no file extension, so there is no way to know what it is");
    }
    String declared = ALLOWED.get(extension);
    if (declared == null) {
      String why = EXPLAINED.get(extension);
      return Verdict.no(why == null
          ? "." + extension + " is not a type this server hands back"
          : "." + extension + " is " + why);
    }
    if (content == null || content.length == 0) {
      return Verdict.no("it is empty");
    }
    if (content.length > MAX_PART_BYTES) {
      return Verdict.no("it is larger than this server will hand back");
    }
    Signature signature = signatureOf(content);
    if (signature != Signature.unknown && !signature.matches(extension)) {
      // the bytes and the name disagree, which is the whole reason to look
      return Verdict.no("it is named ." + extension + " and its contents are "
          + signature.describe() + "; nothing is served on a name alone");
    }
    if (declared.startsWith("image/")) {
      return image(extension, declared, content, signature);
    }
    return Verdict.yes(declared);
  }

  private static Verdict image(String extension, String declared, byte[] content,
                               Signature signature) {
    if (signature == Signature.unknown) {
      // A picture whose header this does not recognise is refused rather than trusted.
      //
      // Every format on the allow list has a signature, so reaching here means the file is not one
      // of them however it is named -- and an image is the one attachment a person will open
      // without thinking about it.
      return Verdict.no("it does not begin like any picture this server recognises");
    }
    int[] size = dimensions(content, signature);
    if (size == null) {
      // heic and some webp variants have no dimensions this reads; the signature already proved
      // what the file is, which is the part that matters
      return Verdict.yes(declared);
    }
    if (size[0] <= 0 || size[1] <= 0) {
      return Verdict.no("its header says it is " + size[0] + " by " + size[1] + " pixels");
    }
    long pixels = (long) size[0] * size[1];
    if (pixels > MAX_PIXELS) {
      // small on the wire, enormous in a decoder; the ratio is the attack
      return Verdict.no("it is " + size[0] + " by " + size[1] + " pixels, which is large enough to"
          + " be a way to make a browser fall over");
    }
    return new Verdict(true, declared, "", size[0], size[1]);
  }

  // ---- what the bytes say --------------------------------------------------------------------

  /** the formats this can recognise from their first few bytes */
  enum Signature {
    png("a PNG", List.of("png")),
    jpeg("a JPEG", List.of("jpg", "jpeg")),
    gif("a GIF", List.of("gif")),
    webp("a WebP", List.of("webp")),
    heic("a HEIC", List.of("heic")),
    pdf("a PDF", List.of("pdf")),
    zip("a ZIP container", List.of("docx", "xlsx", "pptx", "odt", "ods")),
    ole("an old Office document", List.of("doc", "xls", "ppt")),
    executable("an executable", List.of()),
    unknown("", List.of());

    private final String description;
    private final List<String> extensions;

    Signature(String description, List<String> extensions) {
      this.description = description;
      this.extensions = extensions;
    }

    boolean matches(String extension) {
      return extensions.contains(extension);
    }

    String describe() {
      return description.isEmpty() ? "something else" : description;
    }
  }

  static Signature signatureOf(byte[] b) {
    if (starts(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
      return Signature.png;
    }
    if (starts(b, 0xFF, 0xD8, 0xFF)) {
      return Signature.jpeg;
    }
    if (starts(b, 'G', 'I', 'F', '8')) {
      return Signature.gif;
    }
    if (starts(b, 'R', 'I', 'F', 'F') && b.length > 11 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B'
        && b[11] == 'P') {
      return Signature.webp;
    }
    if (b.length > 11 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p'
        && (b[8] == 'h' && b[9] == 'e' && b[10] == 'i' || b[8] == 'm' && b[9] == 'i')) {
      return Signature.heic;
    }
    if (starts(b, '%', 'P', 'D', 'F')) {
      return Signature.pdf;
    }
    // MZ and ELF, checked so that an executable named .pdf is refused with the right sentence
    if (starts(b, 'M', 'Z') || starts(b, 0x7F, 'E', 'L', 'F')) {
      return Signature.executable;
    }
    if (starts(b, 'P', 'K', 0x03, 0x04) || starts(b, 'P', 'K', 0x05, 0x06)) {
      return Signature.zip;
    }
    if (starts(b, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
      return Signature.ole;
    }
    return Signature.unknown;
  }

  private static boolean starts(byte[] bytes, int... expected) {
    if (bytes.length < expected.length) {
      return false;
    }
    for (int k = 0; k < expected.length; k++) {
      if ((bytes[k] & 0xFF) != (expected[k] & 0xFF)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Width and height out of the header, without decoding a pixel.
   *
   * That is the whole point: a decompression bomb is dangerous precisely when you decode it, so the
   * size has to come from the metadata that says how big it claims to be. Every format here states
   * it in the first few dozen bytes.
   */
  static int[] dimensions(byte[] b, Signature signature) {
    try {
      switch (signature) {
        case png -> {
          // IHDR is always the first chunk: 8 bytes of signature, 4 of length, 4 of type
          if (b.length < 24) {
            return null;
          }
          return new int[]{be32(b, 16), be32(b, 20)};
        }
        case gif -> {
          if (b.length < 10) {
            return null;
          }
          return new int[]{le16(b, 6), le16(b, 8)};
        }
        case jpeg -> {
          return jpegSize(b);
        }
        case webp -> {
          return webpSize(b);
        }
        default -> {
          return null;
        }
      }
    } catch (RuntimeException ex) {
      // a header that runs off the end of the file; the caller treats null as "cannot tell"
      return null;
    }
  }

  /**
   * Walk JPEG's segment chain to the frame header.
   *
   * There is no fixed offset: a JPEG is a list of segments and the one carrying the dimensions
   * (SOF0 through SOF15, minus the four that are not frames) can be anywhere after any number of
   * comment and quantisation segments.
   */
  private static int[] jpegSize(byte[] b) {
    int k = 2;
    while (k + 9 < b.length) {
      if ((b[k] & 0xFF) != 0xFF) {
        k++;
        continue;
      }
      int marker = b[k + 1] & 0xFF;
      if (marker == 0xFF) {
        k++;
        continue;
      }
      // 0xC4, 0xC8 and 0xCC are inside the SOF range and are not frames
      boolean frame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8
          && marker != 0xCC;
      if (frame) {
        return new int[]{be16(b, k + 7), be16(b, k + 5)};
      }
      if (marker == 0xD8 || marker >= 0xD0 && marker <= 0xD9) {
        k += 2;
        continue;
      }
      int length = be16(b, k + 2);
      if (length < 2) {
        return null;
      }
      k += 2 + length;
    }
    return null;
  }

  private static int[] webpSize(byte[] b) {
    if (b.length < 30) {
      return null;
    }
    String kind = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
    if (kind.equals("VP8X")) {
      return new int[]{le24(b, 24) + 1, le24(b, 27) + 1};
    }
    if (kind.equals("VP8L")) {
      int bits = (b[21] & 0xFF) | (b[22] & 0xFF) << 8 | (b[23] & 0xFF) << 16
          | (b[24] & 0xFF) << 24;
      return new int[]{(bits & 0x3FFF) + 1, (bits >> 14 & 0x3FFF) + 1};
    }
    if (kind.equals("VP8 ")) {
      return new int[]{le16(b, 26) & 0x3FFF, le16(b, 28) & 0x3FFF};
    }
    return null;
  }

  private static int be32(byte[] b, int at) {
    return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8
        | b[at + 3] & 0xFF;
  }

  private static int be16(byte[] b, int at) {
    return (b[at] & 0xFF) << 8 | b[at + 1] & 0xFF;
  }

  private static int le16(byte[] b, int at) {
    return (b[at + 1] & 0xFF) << 8 | b[at] & 0xFF;
  }

  private static int le24(byte[] b, int at) {
    return (b[at + 2] & 0xFF) << 16 | (b[at + 1] & 0xFF) << 8 | b[at] & 0xFF;
  }
}
