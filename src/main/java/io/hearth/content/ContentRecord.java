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
   * Three of them are documents; the fourth is a program. There used to be six more -- shapes
   * filled in from the events, the address book and the members -- and they went when those did. A
   * page's address is an address again rather than a pattern with a hole in it.
   */
  public enum Kind {
    /** markdown, rendered and then wrapped in the template */
    markdown("Markdown content", "written in markdown, rendered and wrapped in the template"),
    /** an HTML fragment, wrapped in the template as-is */
    html("HTML content", "an HTML fragment, wrapped in the template as-is"),
    /** a whole document, served exactly as stored; no template involved */
    page("Full page", "a whole document served exactly as stored; no template is used"),
    /**
     * JavaScript, run on every request; what it renders is the body.
     *
     * The one kind whose body is not what is served. It gets `render(text)` to build the document
     * and `meta(key, value)` to set the title and whatever else the template asked for, and it has
     * nothing else -- no network, no storage, no way back into this server.
     */
    javascript("Dynamic JavaScript",
        "a program run on every request; render(text) builds the body and meta(key, value) sets"
            + " the title and the template's fields");

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
     * Is the body a program rather than a document?
     *
     * Asked where the difference matters and nowhere else: the editor draws a different box, the
     * renderer runs it instead of rendering it, and the page cache leaves it alone -- a page that
     * can answer differently on every request has no business being kept under its address.
     */
    public boolean isProgram() {
      return this == javascript;
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
