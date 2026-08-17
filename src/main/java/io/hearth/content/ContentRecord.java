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

  /** where a page's rows come from, when they come from anywhere */
  public enum Source {
    none, events, places, members
  }

  /**
   * How the body should be turned into a page.
   *
   * The first three are a document somebody wrote. The rest are a *shape* somebody wrote, filled in
   * from what the community already holds -- the events, the address book, the members -- so that a
   * community which wants its own front page for what is on does not have to choose between the
   * built-in page and writing a website by hand.
   *
   * <b>A feed page's uri is a pattern.</b> `/whats-on/{{page}}` and `/people/{{member_id}}` are
   * addresses with a hole in them; the request fills the hole and the hole becomes a parameter to
   * the query behind the page. One token each, deliberately: a URL language with two variables in it
   * is a router, and the shortest path from a router to a page nobody can debug is a second
   * variable.
   */
  public enum Kind {
    /** markdown, rendered and then wrapped in the template */
    markdown("Markdown content", "written in markdown, rendered and wrapped in the template"),
    /** an HTML fragment, wrapped in the template as-is */
    html("HTML content", "an HTML fragment, wrapped in the template as-is"),
    /** a whole document, served exactly as stored; no template involved */
    page("Full page", "a whole document served exactly as stored; no template is used"),

    event_listing("HTML Event Listing",
        "what is on, a page at a time -- put {{page}} in the uri for page two onwards",
        "{{page}}", true, Source.events),
    event("HTML Event", "one event -- the uri must contain {{event_id}}",
        "{{event_id}}", false, Source.events),
    place_listing("HTML Place Listing",
        "the address book, a page at a time -- put {{page}} in the uri",
        "{{page}}", true, Source.places),
    place("HTML Place", "one place -- the uri must contain {{place_id}}",
        "{{place_id}}", false, Source.places),
    member_listing("HTML Member Listing",
        "who is here, a page at a time -- put {{page}} in the uri",
        "{{page}}", true, Source.members),
    member("HTML Member", "one person -- the uri must contain {{member_id}}",
        "{{member_id}}", false, Source.members);

    /** what the editor calls it, because "markdown" alone does not say what happens to it */
    public final String label;
    public final String describe;
    /** the hole in the address, or null for a page whose address is just an address */
    public final String token;
    public final boolean listing;
    public final Source source;

    Kind(String label, String describe) {
      this(label, describe, null, false, Source.none);
    }

    Kind(String label, String describe, String token, boolean listing, Source source) {
      this.label = label;
      this.describe = describe;
      this.token = token;
      this.listing = listing;
      this.source = source;
    }

    /** does a page of this kind get wrapped in a template at all? */
    public boolean wantsTemplate() {
      return this != page;
    }

    /** is the body a shape to fill in rather than a document? */
    public boolean isFeed() {
      return source != Source.none;
    }

    /**
     * What the address for this kind has to look like, in one sentence.
     *
     * Written once and read in two places: the editor shows it when somebody picks a kind, and the
     * AI tools hand it to a model. A model building a site from a description has no screen to read
     * and no way to guess that a `member` page needs `{{member_id}}` in its uri -- and a rule that
     * exists only in a hint on a form is a rule an agent breaks on its first attempt.
     */
    public String uriRule() {
      if (token == null) {
        return "any path, e.g. /about";
      }
      return listing
          ? "put " + token + " in the path for page two onwards, e.g. /whats-on/" + token
              + " -- page one is always the bare path, /whats-on"
          : "the path must contain " + token + ", e.g. /events/" + token;
    }

    /** what a page of this kind can be given, beyond its address */
    public String settings() {
      return switch (this) {
        case event_listing, member_listing -> "page_size, sort";
        case place_listing -> "page_size, sort, place_kind";
        default -> "";
      };
    }

    /** the orders a listing of this kind can be in; the first is the default */
    public java.util.List<String> sorts() {
      return switch (this) {
        // an event listing has one useful order and it is the day it happens
        case event_listing -> java.util.List.of("date");
        case place_listing -> java.util.List.of("name", "kind", "newest", "oldest");
        case member_listing -> java.util.List.of("name", "joined", "newest");
        default -> java.util.List.of();
      };
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
