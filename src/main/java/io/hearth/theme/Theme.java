package io.hearth.theme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The colours a community uses, everywhere it is seen.
 *
 * Six of them, twice -- once for a light screen and once for a dark one -- because a palette that
 * only works in one is a palette half the community reads through an inverted filter their browser
 * invented. There is no clever derivation here: a community that wants a dark accent picks one,
 * because guessing a dark counterpart from a light colour is how you end up with unreadable text on
 * somebody else's monitor and no way to fix it.
 *
 * <b>Semantic colours are not themeable.</b> Red means a refusal, green means it worked, and letting
 * those be chosen would eventually put a community in the position of having a red "approved" and a
 * green "banned". They are constants below, and they are the reason this is a fixed set of slots
 * rather than a free map of names.
 *
 * Two scopes, {@link Scope#site} and {@link Scope#admin}. The site's colours are what members and
 * every email see; the admin's are what the people running it see, and they carry the legal pages --
 * those are the community's promises rather than its decoration, and they read as documents rather
 * than as part of the site.
 *
 * Stored as JSON rather than as columns. A palette is one thing that is read and written whole, and
 * twelve columns would be twelve schema versions the first time somebody wants a thirteenth colour.
 */
public final class Theme {
  private static final ObjectMapper JSON = new ObjectMapper();

  /** what red, green, amber and purple mean here; never chosen by anybody */
  public static final String BAD = "#b3261e";
  public static final String GOOD = "#1a7f37";
  public static final String WARN = "#9a6400";
  public static final String PURPLE = "#7b3fd4";
  public static final String BAD_DARK = "#f2b8b5";
  public static final String GOOD_DARK = "#57ab5a";
  public static final String WARN_DARK = "#e0b341";
  public static final String PURPLE_DARK = "#c9a6ff";

  public enum Scope {
    /** the public site, every page a member sees, and every email */
    site,
    /** the admin section, and the legal pages */
    admin;

    public static Scope of(String raw) {
      if (raw == null) {
        return null;
      }
      try {
        return valueOf(raw.trim().toLowerCase());
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }
  }

  /** one screen's worth of colour */
  public record Palette(String accent, String fg, String bg, String panel, String dim, String line) {
    public Palette {
      accent = colour(accent, "#2f5cff");
      fg = colour(fg, "#1a1a1a");
      bg = colour(bg, "#fbfbfa");
      panel = colour(panel, "#ffffff");
      dim = colour(dim, "#6b6b6b");
      line = colour(line, "#e3e3e0");
    }

    public String get(String slot) {
      return switch (slot) {
        case "accent" -> accent;
        case "fg" -> fg;
        case "bg" -> bg;
        case "panel" -> panel;
        case "dim" -> dim;
        case "line" -> line;
        default -> "";
      };
    }

    public Palette with(String slot, String value) {
      return new Palette(
          "accent".equals(slot) ? value : accent,
          "fg".equals(slot) ? value : fg,
          "bg".equals(slot) ? value : bg,
          "panel".equals(slot) ? value : panel,
          "dim".equals(slot) ? value : dim,
          "line".equals(slot) ? value : line);
    }
  }

  /** the slots, in the order the editor shows them, with what each one is for */
  public static final List<String[]> SLOTS = List.of(
      new String[]{"accent", "Accent", "links, buttons and anything asking to be pressed"},
      new String[]{"fg", "Text", "the words themselves"},
      new String[]{"bg", "Background", "the page behind everything"},
      new String[]{"panel", "Panel", "cards and boxes that sit on the background"},
      new String[]{"dim", "Quiet text", "labels, timestamps and anything secondary"},
      new String[]{"line", "Lines", "borders, rules and table separators"});

  public static final Palette SITE_LIGHT =
      new Palette("#2f5cff", "#1a1a1a", "#fbfbfa", "#ffffff", "#6b6b6b", "#e3e3e0");
  public static final Palette SITE_DARK =
      new Palette("#8aa4ff", "#e8e8e6", "#171716", "#1c1c1a", "#9a9a96", "#2e2e2c");
  public static final Palette ADMIN_LIGHT =
      new Palette("#2f5cff", "#1a1a1a", "#fbfbfa", "#ffffff", "#6b6b6b", "#e3e3e0");
  public static final Palette ADMIN_DARK =
      new Palette("#8aa4ff", "#e8e8e6", "#141413", "#1c1c1a", "#9a9a96", "#2e2e2c");

  public final Scope scope;
  public final Palette light;
  public final Palette dark;

  public Theme(Scope scope, Palette light, Palette dark) {
    this.scope = scope;
    this.light = light;
    this.dark = dark;
  }

  /** what a community starts with: the colours this program has always shipped */
  public static Theme defaultFor(Scope scope) {
    return scope == Scope.admin
        ? new Theme(Scope.admin, ADMIN_LIGHT, ADMIN_DARK)
        : new Theme(Scope.site, SITE_LIGHT, SITE_DARK);
  }

  public boolean isDefault() {
    Theme standard = defaultFor(scope);
    return standard.light.equals(light) && standard.dark.equals(dark);
  }

  /**
   * The two lines of CSS every page carries.
   *
   * Interpolated raw into a `<style>` block, which is safe for one reason and one reason only:
   * every value came through {@link #colour}, so it is `#` and six hex digits or it is the default.
   * A palette that could carry arbitrary text into a stylesheet would be an injection with extra
   * steps -- `}` ends the rule and everything after it is somebody else's CSS.
   *
   * <b>Light unless somebody says otherwise.</b> This used to key the dark palette off
   * `prefers-color-scheme`, which meant a community that had chosen its colours had them shown to
   * roughly half its members inverted into a scheme nobody had looked at -- and no way for a person
   * to disagree with their laptop. The dark half is now behind an attribute on the root element,
   * set by the one shipped script from what that person last chose. An operating system preference
   * is a reasonable guess about a text editor and a poor one about somebody's community.
   */
  public String css() {
    return ":root{color-scheme:light;" + vars(light, BAD, GOOD, WARN, PURPLE) + "}\n"
        + ":root[data-theme=\"dark\"]{color-scheme:dark;"
        + vars(dark, BAD_DARK, GOOD_DARK, WARN_DARK, PURPLE_DARK) + "}";
  }

  private static String vars(Palette palette, String bad, String good, String warn, String purple) {
    return "--fg:" + palette.fg() + ";--dim:" + palette.dim() + ";--bg:" + palette.bg()
        + ";--panel:" + palette.panel() + ";--line:" + palette.line()
        + ";--accent:" + palette.accent() + ";--bad:" + bad + ";--good:" + good
        + ";--purple:" + purple + ";--warn:" + warn;
  }

  /** what the editor renders: one row per slot, both palettes side by side */
  public List<Map<String, Object>> rows() {
    java.util.ArrayList<Map<String, Object>> rows = new java.util.ArrayList<>();
    for (String[] slot : SLOTS) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("slot", slot[0]);
      row.put("label", slot[1]);
      row.put("hint", slot[2]);
      row.put("light", light.get(slot[0]));
      row.put("dark", dark.get(slot[0]));
      rows.add(row);
    }
    return rows;
  }

  public String toJson() {
    ObjectNode node = JSON.createObjectNode();
    write(node.putObject("light"), light);
    write(node.putObject("dark"), dark);
    return node.toString();
  }

  private static void write(ObjectNode node, Palette palette) {
    for (String[] slot : SLOTS) {
      node.put(slot[0], palette.get(slot[0]));
    }
  }

  /**
   * Read a stored palette, falling back slot by slot.
   *
   * A row that predates a new slot, or one somebody edited by hand into nonsense, produces a theme
   * with the default for whatever it could not read rather than an exception. This is decoration:
   * refusing to serve the site because a colour is misspelt would be the wrong trade by a distance.
   */
  public static Theme fromJson(Scope scope, String json) {
    Theme fallback = defaultFor(scope);
    if (json == null || json.isBlank()) {
      return fallback;
    }
    try {
      JsonNode node = JSON.readTree(json);
      return new Theme(scope, read(node.path("light"), fallback.light),
          read(node.path("dark"), fallback.dark));
    } catch (Exception ex) {
      return fallback;
    }
  }

  private static Palette read(JsonNode node, Palette fallback) {
    if (!node.isObject()) {
      return fallback;
    }
    Palette palette = fallback;
    for (String[] slot : SLOTS) {
      String value = node.path(slot[0]).asText("");
      if (isColour(value)) {
        palette = palette.with(slot[0], normalize(value));
      }
    }
    return palette;
  }

  /** `#rgb` or `#rrggbb`, and nothing else on earth */
  public static boolean isColour(String value) {
    if (value == null) {
      return false;
    }
    String trimmed = value.trim();
    if (trimmed.length() != 4 && trimmed.length() != 7) {
      return false;
    }
    if (trimmed.charAt(0) != '#') {
      return false;
    }
    for (int k = 1; k < trimmed.length(); k++) {
      char ch = Character.toLowerCase(trimmed.charAt(k));
      boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
      if (!hex) {
        return false;
      }
    }
    return true;
  }

  /** the long spelling, lowercased, so two ways of writing one colour compare equal */
  public static String normalize(String value) {
    String trimmed = value.trim().toLowerCase();
    if (trimmed.length() != 4) {
      return trimmed;
    }
    StringBuilder out = new StringBuilder("#");
    for (int k = 1; k < 4; k++) {
      out.append(trimmed.charAt(k)).append(trimmed.charAt(k));
    }
    return out.toString();
  }

  private static String colour(String value, String fallback) {
    return isColour(value) ? normalize(value) : fallback;
  }
}
