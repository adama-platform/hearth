package io.hearth.tables;

import java.util.Locale;

/**
 * One column of a table somebody invented, and the small closed set of things it can hold.
 *
 * <b>Four types, chosen to land cleanly in JavaScript.</b> Everything here has an obvious JSON
 * shape, because the whole point of the table is to be read by a program: text is a string, number
 * is a double, flag is a boolean, and moment is milliseconds since the epoch rather than a
 * formatted date. A date type that arrived in JavaScript as `"2026-09-01 14:00:00.0"` would make
 * every page that wants to sort or compare do string surgery, so there is not one.
 *
 * <b>Text has a ceiling and it is the same one prose gets elsewhere.</b> Not because 8192 is a
 * magic number, but because a column with no ceiling is a column somebody eventually puts a
 * megabyte in, and `_all()` promises to return the whole table.
 */
public record UserField(String name, Type type, boolean required) {
  /** the physical column; see the note in {@link UserTable} about MODE=STRICT reserving keywords */
  public String physical() {
    return "f_" + name;
  }

  public enum Type {
    text("Text", "VARCHAR(8192)", "a string"),
    number("Number", "DOUBLE PRECISION", "a number"),
    flag("True or false", "BOOLEAN", "a boolean"),
    moment("A moment in time", "BIGINT", "milliseconds since the epoch, as a number");

    /** what the admin screen calls it */
    public final String label;
    /** what the database calls it */
    public final String sql;
    /** what a JavaScript program gets */
    public final String inJavaScript;

    Type(String label, String sql, String inJavaScript) {
      this.label = label;
      this.sql = sql;
      this.inJavaScript = inJavaScript;
    }

    public static Type of(String raw) {
      if (raw == null) {
        return null;
      }
      try {
        return valueOf(raw.trim().toLowerCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }
  }
}
