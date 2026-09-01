package io.hearth.content;

import java.sql.Timestamp;

/** One row of the content table: a page as its author typed it. */
public record ContentRecord(long id, String uri, String title, Kind kind, String templateName,
                            String navFolder, String fields, String body, boolean published,
                            boolean humanOnly, Timestamp publishedAt, Timestamp createdAt,
                            Timestamp updatedAt, Long updatedBy) {
  /** the shape every caller that does not care about the date still writes */
  public ContentRecord(long id, String uri, String title, Kind kind, String templateName,
                       String navFolder, String fields, String body, boolean published,
                       boolean humanOnly, Timestamp createdAt, Timestamp updatedAt,
                       Long updatedBy) {
    this(id, uri, title, kind, templateName, navFolder, fields, body, published, humanOnly, null,
        createdAt, updatedAt, updatedBy);
  }

  /**
   * The day this counts as published: what somebody set, or the day it was first saved.
   *
   * Never null to a reader, so nothing downstream has to decide what an absent date means -- and
   * ordering a listing is exactly the place where two answers to that would put a page in two
   * different positions depending on which code asked.
   */
  public Timestamp publishedOn() {
    return publishedAt != null ? publishedAt : createdAt;
  }

  /**
   * Locked away from AI.
   *
   * A page marked this way is invisible to every agent read and refused on every agent write. It
   * changes nothing about who among the humans can see it -- an unlisted page is what `published`
   * is for. This is the one switch that says "a model must not be part of this", which some pages
   * genuinely need and most do not.
   */
  public boolean isLockedToHumans() {
    return humanOnly;
  }

  /**
   * How the body should be turned into a page.
   *
   * Three ways of writing a document, and that is all there is now. There used to be six more --
   * shapes filled in from the events, the address book and the members -- and they went when those
   * did. A page's address is an address again rather than a pattern with a hole in it.
   */
  public enum Kind {
    /** markdown, rendered and then wrapped in the template */
    markdown("Markdown content", "written in markdown, rendered and wrapped in the template"),
    /** an HTML fragment, wrapped in the template as-is */
    html("HTML content", "an HTML fragment, wrapped in the template as-is"),
    /** a whole document, served exactly as stored; no template involved */
    page("Full page", "a whole document served exactly as stored; no template is used");

    /** what the editor calls it, because "markdown" alone does not say what happens to it */
    public final String label;
    public final String describe;

    Kind(String label, String describe) {
      this.label = label;
      this.describe = describe;
    }

    /** does a page of this kind get wrapped in a template at all? */
    public boolean wantsTemplate() {
      return this != page;
    }

    /**
     * What the address for this kind has to look like, in one sentence.
     *
     * A page's address is just an address now. The kinds that filled a hole in a uri from a query
     * -- an event, a place, a member -- went with the features behind them.
     */
    public String uriRule() {
      return "any path, e.g. /about";
    }

    public static Kind of(String raw) {
      if (raw == null) {
        return markdown;
      }
      try {
        return valueOf(raw.trim().toLowerCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return markdown;
      }
    }
  }

  public boolean usesTemplate() {
    return kind != Kind.page && templateName != null && !templateName.isEmpty();
  }

  /** is this page missing from the navigation tree? the listing warns about it */
  public boolean isOutsideNavigation() {
    return navFolder == null || navFolder.isBlank();
  }

  /** the cache key for the rendered form of this page */
  public String cacheKey() {
    return Long.toString(id);
  }
}
