package io.hearth.attach;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What may be uploaded here, by extension, and what each thing is.
 *
 * <b>A closed table, and the extension decides the content type rather than the browser.</b> That
 * is the whole security design. A browser sends a `Content-Type` with an upload and it is a claim
 * by whoever is uploading; believing it means somebody names a file `photo.png`, declares it
 * `text/html`, and now this community's domain serves attacker-written HTML with every member's
 * session cookie attached to it. So: the extension is checked against this list, the type is looked
 * up here, and what the browser said is thrown away.
 *
 * <b>What is on the list is what people share in a room.</b> Photographs, a video of the thing that
 * happened, a recording, the PDF of the menu, the spreadsheet of who is bringing what. What is not
 * on it is everything a browser will execute or a mail client will open: no `.html`, no `.svg`
 * (which is a document that can carry script), no `.js`, no archives, no office macros. A community
 * that genuinely needs one of those can add it in a line of config and will have thought about it
 * once, which is one more time than a default would have.
 *
 * <b>Nothing here is ever `text/html`.</b> Not for any extension, not by configuration. Anything
 * this list cannot name is served as a download rather than as a page.
 */
public final class Kinds {
  /** what a thing is, for the picker and for how a page should embed it */
  public enum Kind {
    image, video, audio, document, other
  }

  /** one allowed extension: what it is called, what it is, and what to send it as */
  public record Type(String extension, String mime, Kind kind) {
    public boolean embeddable() {
      return kind == Kind.image || kind == Kind.video || kind == Kind.audio;
    }
  }

  private static final Map<String, Type> KNOWN = new LinkedHashMap<>();

  private static void known(String extension, String mime, Kind kind) {
    KNOWN.put(extension, new Type(extension, mime, kind));
  }

  static {
    // photographs, which is most of what any community uploads
    known("jpg", "image/jpeg", Kind.image);
    known("jpeg", "image/jpeg", Kind.image);
    known("png", "image/png", Kind.image);
    known("gif", "image/gif", Kind.image);
    known("webp", "image/webp", Kind.image);
    known("avif", "image/avif", Kind.image);
    known("heic", "image/heic", Kind.image);
    // deliberately no svg: it is a document that can carry script, and it arrives looking like a
    // picture. A community that wants logos in it can say so, and will have thought about it.

    known("mp4", "video/mp4", Kind.video);
    known("m4v", "video/x-m4v", Kind.video);
    known("mov", "video/quicktime", Kind.video);
    known("webm", "video/webm", Kind.video);

    known("mp3", "audio/mpeg", Kind.audio);
    known("m4a", "audio/mp4", Kind.audio);
    known("aac", "audio/aac", Kind.audio);
    known("ogg", "audio/ogg", Kind.audio);
    known("oga", "audio/ogg", Kind.audio);
    known("wav", "audio/wav", Kind.audio);
    known("flac", "audio/flac", Kind.audio);

    // the things people actually pass around at a meeting
    known("pdf", "application/pdf", Kind.document);
    known("txt", "text/plain; charset=utf-8", Kind.document);
    known("csv", "text/csv; charset=utf-8", Kind.document);
    known("md", "text/plain; charset=utf-8", Kind.document);
    known("ics", "text/calendar; charset=utf-8", Kind.document);
    known("vcf", "text/vcard; charset=utf-8", Kind.document);
  }

  /** what a community gets when it has said nothing: everything above */
  public static final List<String> DEFAULT_EXTENSIONS = List.copyOf(KNOWN.keySet());

  private Kinds() {
  }

  /** every extension this server knows how to serve safely */
  public static List<String> all() {
    return new ArrayList<>(KNOWN.keySet());
  }

  public static Type of(String extension) {
    return extension == null ? null : KNOWN.get(clean(extension));
  }

  public static boolean isKnown(String extension) {
    return of(extension) != null;
  }

  /** lowercase, no dot, letters and digits only; anything else is not an extension */
  public static String clean(String raw) {
    if (raw == null) {
      return "";
    }
    String value = raw.trim().toLowerCase(Locale.ROOT);
    if (value.startsWith(".")) {
      value = value.substring(1);
    }
    StringBuilder out = new StringBuilder();
    for (char ch : value.toCharArray()) {
      if (Character.isLetterOrDigit(ch)) {
        out.append(ch);
      }
    }
    String clean = out.toString();
    return clean.length() > 8 ? "" : clean;
  }

  /**
   * The extension of a filename somebody uploaded.
   *
   * Taken from the last dot and nothing else. `report.pdf.exe` is an `exe`, which is not on the
   * list, which is the correct answer -- and `.tar.gz` reads as `gz`, which is also correct because
   * neither is on the list either.
   */
  public static String extensionOf(String filename) {
    if (filename == null) {
      return "";
    }
    String name = filename.trim();
    int dot = name.lastIndexOf('.');
    return dot < 0 || dot == name.length() - 1 ? "" : clean(name.substring(dot + 1));
  }

  /**
   * A filename somebody can read, with nothing in it that means anything to a filesystem.
   *
   * Kept only to show them and to name the download; the file on disk is called after its id. A
   * name is a place for `../../etc/passwd`, for a null byte, and for a right-to-left override that
   * makes `gpj.exe` look like `exe.jpg`, and none of those has to be a problem if the name is never
   * a path and never rendered raw.
   */
  public static String safeName(String raw) {
    if (raw == null || raw.isBlank()) {
      return "file";
    }
    String name = raw.trim();
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    StringBuilder out = new StringBuilder();
    for (char ch : name.toCharArray()) {
      if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '-' || ch == '_' || ch == ' ') {
        out.append(ch);
      }
      if (out.length() >= 120) {
        break;
      }
    }
    String clean = out.toString().trim();
    return clean.isEmpty() || clean.equals(".") || clean.equals("..") ? "file" : clean;
  }
}
