package io.hearth.content;

import java.sql.Timestamp;

/**
 * One row of the templates table: a page shell an operator wrote.
 *
 * A template can also be a <b>directory</b>, which is what lets the content table behave like a
 * blog. Turning it on gives every page that names this template a listing: an index at
 * {@link #directoryPath}, paginated, with page two and onwards addressed by
 * {@link #directoryPattern}. Nothing else changes -- writing a post is still writing a page, and
 * the listing is a property of the shape rather than a second thing somebody has to keep in step.
 */
public record TemplateRecord(long id, String name, String parameters, String body,
                             boolean directory, String directoryPath, String directoryPattern,
                             String directoryBody, int directoryPageSize, String directoryOrder,
                             Timestamp createdAt, Timestamp updatedAt, Long updatedBy) {
  /** the shape callers that know nothing about the index still write */
  public TemplateRecord(long id, String name, String parameters, String body, boolean directory,
                        String directoryPath, String directoryPattern, int directoryPageSize,
                        String directoryOrder, Timestamp createdAt, Timestamp updatedAt,
                        Long updatedBy) {
    this(id, name, parameters, body, directory, directoryPath, directoryPattern, "",
        directoryPageSize, directoryOrder, createdAt, updatedAt, updatedBy);
  }

  /**
   * What to render an index with: its own template, or the page one.
   *
   * The fallback keeps every directory written before this existed working exactly as it did --
   * those templates branch on `{{#directory}}` internally, which is precisely the shape this
   * replaces, and breaking them to make a point would be a poor trade.
   */
  public String indexBody() {
    return directoryBody == null || directoryBody.isBlank() ? body : directoryBody;
  }

  public boolean hasOwnIndex() {
    return directoryBody != null && !directoryBody.isBlank();
  }
  /** what a pattern substitutes for the page number */
  public static final String PAGE_TOKEN = "{page}";

  /** the fields this template asks every page using it to fill in */
  public java.util.List<TemplateField> fields() {
    return TemplateField.parse(parameters);
  }

  /** is this actually publishing a listing, or merely ticked with nowhere to put it? */
  public boolean publishesDirectory() {
    return directory && directoryPath != null && directoryPath.startsWith("/");
  }

  public int pageSize() {
    return directoryPageSize <= 0 ? 10 : Math.min(directoryPageSize, 200);
  }

  public boolean newestFirst() {
    return !"oldest".equals(directoryOrder);
  }

  /**
   * The address of page N.
   *
   * Page one is always the bare path, whatever the pattern says. A blog whose front page is
   * `/blog/page/1` has two addresses for one page, and a search engine will find both.
   */
  public String urlFor(int page) {
    if (page <= 1) {
      return directoryPath;
    }
    String pattern = directoryPattern == null || directoryPattern.isBlank()
        ? directoryPath + "/page/" + PAGE_TOKEN : directoryPattern;
    return pattern.replace(PAGE_TOKEN, Integer.toString(page));
  }

  /**
   * Which page of this listing a request is asking for, or 0 when it is not asking for one.
   *
   * Answers 1 for the bare path and N for whatever the pattern produces. Matching by building the
   * expected address and comparing, rather than by parsing the pattern, so the two can never
   * disagree about what page four looks like.
   */
  public int pageOf(String path, String query) {
    if (!publishesDirectory()) {
      return 0;
    }
    if (path.equals(directoryPath)) {
      // a query-shaped pattern puts the number in the query string, so the bare path is page one
      // unless it says otherwise
      String pattern = directoryPattern == null ? "" : directoryPattern;
      if (pattern.contains("?") && query != null && !query.isBlank()) {
        for (int page = 2; page <= 5000; page++) {
          String expected = urlFor(page);
          int mark = expected.indexOf('?');
          if (mark >= 0 && expected.substring(mark + 1).equals(query)) {
            return page;
          }
        }
      }
      return 1;
    }
    String whole = query == null || query.isBlank() ? path : path + "?" + query;
    for (int page = 2; page <= 5000; page++) {
      if (urlFor(page).equals(whole)) {
        return page;
      }
    }
    return 0;
  }
}
