package io.hearth.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The icon set, inline.
 *
 * SVG, drawn with stroke and currentColor, emitted straight into the markup. No image requests, no
 * icon font, no sprite sheet, no build step -- a page costs exactly one request and whatever the
 * database had to say. That is the resource budget this whole product is designed around, and an
 * icon set is where it usually gets quietly spent.
 *
 * currentColor matters as much as the format: an icon inherits the text colour around it, so light
 * and dark mode need no second copy and no CSS filter.
 *
 * These are hand-trimmed outline icons in the heroicons idiom -- 24x24 viewBox, 1.5 stroke, round
 * caps -- because that idiom is legible at 16px and costs about eighty bytes each.
 */
public class Icons {
  private static final String OPEN =
      "<svg class=\"icon\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\""
          + " stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">";
  private static final Map<String, String> PATHS = new LinkedHashMap<>();

  static {
    PATHS.put("home", "<path d=\"M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z\"/>");
    PATHS.put("people", "<path d=\"M15 19v-1a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v1\"/><circle cx=\"8.5\" cy=\"7\" r=\"3.5\"/>"
        + "<path d=\"M22 19v-1a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75\"/>");
    PATHS.put("content", "<path d=\"M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z\"/>"
        + "<path d=\"M14 3v5h5M9 13h6M9 17h4\"/>");
    PATHS.put("template", "<rect x=\"3\" y=\"4\" width=\"18\" height=\"16\" rx=\"2\"/><path d=\"M3 9h18M9 9v11\"/>");
    PATHS.put("events", "<path d=\"M13 2 4.5 13H11l-1 9 8.5-11H12z\"/>");
    PATHS.put("analytics", "<path d=\"M3 3v18h18\"/><path d=\"M7 15l3.5-4 3 3L20 7\"/>");
    PATHS.put("logs", "<path d=\"M4 5h16M4 10h16M4 15h10M4 20h7\"/>");
    PATHS.put("questions", "<circle cx=\"12\" cy=\"12\" r=\"9\"/>"
        + "<path d=\"M9.5 9a2.5 2.5 0 1 1 3.2 2.4c-.7.2-1.2.9-1.2 1.6v.5\"/><path d=\"M12 17h.01\"/>");
    PATHS.put("profile", "<circle cx=\"12\" cy=\"8\" r=\"4\"/><path d=\"M4 21v-1a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v1\"/>");
    PATHS.put("check", "<path d=\"m5 13 4 4L19 7\"/>");
    PATHS.put("x", "<path d=\"M6 6l12 12M18 6 6 18\"/>");
    PATHS.put("logout", "<path d=\"M15 17l5-5-5-5\"/><path d=\"M20 12H9\"/><path d=\"M12 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h6\"/>");
    PATHS.put("star", "<path d=\"m12 3 2.8 5.7 6.2.9-4.5 4.4 1 6.2-5.5-2.9L6.5 20l1-6.2L3 9.4l6.2-.9z\"/>");
    PATHS.put("search", "<circle cx=\"11\" cy=\"11\" r=\"7\"/><path d=\"m20 20-3.5-3.5\"/>");
    PATHS.put("plus", "<path d=\"M12 5v14M5 12h14\"/>");
    PATHS.put("edit", "<path d=\"M12 20h9\"/><path d=\"M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z\"/>");
    PATHS.put("pin", "<path d=\"M12 21v-7\"/><path d=\"M8 3h8l-1 6 3 3H6l3-3z\"/>");
    PATHS.put("place", "<path d=\"M12 21s7-5.4 7-11a7 7 0 1 0-14 0c0 5.6 7 11 7 11z\"/><circle cx=\"12\" cy=\"10\" r=\"2.5\"/>");
    PATHS.put("chat", "<path d=\"M21 12a8 8 0 0 1-8 8H7l-4 3v-4.5A8 8 0 0 1 11 4h2a8 8 0 0 1 8 8z\"/>");
    // the light/dark switch: a sun and a crescent, one shown at a time by CSS rather than by
    // rendering the right one, so the button does not have to know what the browser decided
    PATHS.put("sun", "<circle cx=\"12\" cy=\"12\" r=\"4\"/>"
        + "<path d=\"M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2"
        + "M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4\"/>");
    PATHS.put("moon", "<path d=\"M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z\"/>");
    PATHS.put("external", "<path d=\"M14 4h6v6\"/><path d=\"M20 4 10 14\"/>"
        + "<path d=\"M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5\"/>");
  }

  /**
   * The favicon, as a data URI in the page's head.
   *
   * A browser that finds this never asks for /favicon.ico, which is one fewer request per visit and
   * -- less obviously -- one fewer response that could have overwritten a cookie. The alternative,
   * serving a file, would be the only thing on disk in a product whose whole premise is that there
   * isn't one.
   */
  public static final String FAVICON_DATA_URI =
      "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E"
          + "%3Ccircle cx='12' cy='12' r='10' fill='%232f5cff'/%3E"
          + "%3Cpath d='M7 12.5l3.2 3.2L17 9' fill='none' stroke='white' stroke-width='2.2'"
          + " stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E";

  /**
   * The same mark, drawn for a phone's own icon shape.
   *
   * A maskable icon is cropped to whatever silhouette the platform uses -- a circle, a squircle, a
   * rounded square -- so it needs its art inside the middle 80% and colour all the way to the edge.
   * The plain favicon would come back with its edges shaved off.
   */
  public static final String MASKABLE_DATA_URI =
      "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E"
          + "%3Crect width='24' height='24' fill='%232f5cff'/%3E"
          + "%3Cpath d='M8.4 12.4l2.6 2.6 4.6-5.4' fill='none' stroke='white' stroke-width='2'"
          + " stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E";

  private Icons() {
  }

  /** the markup for an icon, or an empty string when there is no such icon */
  public static String of(String name) {
    String path = PATHS.get(name);
    return path == null ? "" : OPEN + path + "</svg>";
  }

  public static boolean has(String name) {
    return PATHS.containsKey(name);
  }

  public static java.util.Set<String> names() {
    return PATHS.keySet();
  }
}
