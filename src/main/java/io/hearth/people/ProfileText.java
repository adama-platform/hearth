package io.hearth.people;

import io.hearth.content.Markdown;
import io.hearth.web.Html;

/**
 * Somebody's own words, cut down to a size that fits a listing.
 *
 * Truncating markdown as text is the mistake this exists to avoid: cutting `**a very long` in half
 * leaves an unclosed emphasis that swallows the rest of the page, and cutting a link leaves half an
 * address. So it is rendered, flattened to plain text, and then cut -- which also means a listing
 * never carries markup a member wrote, which is one fewer place for the filtered renderer to have
 * been forgotten.
 */
public final class ProfileText {
  private ProfileText() {
  }

  /** the plain words, with the markdown taken off */
  public static String plain(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return "";
    }
    return Html.text(Markdown.toSafeHtml(markdown)).replace('\n', ' ').trim();
  }

  /** cut at a word boundary, with an ellipsis, or left alone when it already fits */
  public static String truncate(String text, int limit) {
    if (text == null) {
      return "";
    }
    String tidy = text.trim();
    if (tidy.length() <= limit) {
      return tidy;
    }
    int cut = tidy.lastIndexOf(' ', limit);
    return tidy.substring(0, cut > limit / 2 ? cut : limit).trim() + "…";
  }
}
