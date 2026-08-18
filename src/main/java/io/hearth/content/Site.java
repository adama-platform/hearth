package io.hearth.content;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import io.hearth.cache.CachePolicy;
import io.hearth.cache.Caches;
import io.hearth.cache.TtlCache;
import io.hearth.common.Verbose;
import io.hearth.events.EventBus;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serving pages out of the content table, with caches that the event bus keeps honest.
 *
 * Three caches, each with a configurable policy:
 *
 *   content   -- the row, keyed by uri, so a hit avoids a query
 *   rendered  -- the finished bytes, keyed by content id, so a hit avoids markdown and mustache
 *   templates -- compiled mustache, keyed by name, so a hit avoids parsing
 *
 * Invalidation is precise and event-driven. Saving a page drops that page. Saving a template drops
 * the compiled template AND every rendered page that named it -- the cascade -- because a layout
 * change that only took effect an hour later would feel broken. The TTL is the backstop for events
 * that never arrive, which today means a bug and tomorrow means another process.
 */
public class Site {
  private static final Logger LOG = LoggerFactory.getLogger(Site.class);
  private static final int MAX_PAGES = 5000;

  private final String domain;
  private final ContentStore content;
  private final TtlCache<String, ContentRecord> byUri;
  private final TtlCache<String, Rendered> rendered;
  private final TtlCache<String, Mustache> templates;
  private final Verbose verbose;

  public Site(String domain, Store store, Caches policies, EventBus events, Verbose verbose) {
    this.domain = domain;
    this.content = new ContentStore(store);
    this.byUri = new TtlCache<>(Caches.CONTENT, policies.forName(Caches.CONTENT));
    this.rendered = new TtlCache<>(Caches.RENDERED, policies.forName(Caches.RENDERED));
    this.templates = new TtlCache<>(Caches.TEMPLATES, policies.forName(Caches.TEMPLATES));
    this.verbose = verbose;
    events.subscribe(this::onMutation);
  }

  public ContentStore store() {
    return content;
  }

  public List<TtlCache.Stats> cacheStats() {
    return List.of(byUri.stats(), rendered.stats(), templates.stats());
  }

  /**
   * React to a change.
   *
   * Only events from this domain's database matter -- two domains sharing a database share content,
   * and two that do not must not invalidate each other.
   */
  private void onMutation(MutationEvent event) {
    if (!event.domain().equals(domain)) {
      return;
    }
    if (event.touches(Schema.CONTENT)) {
      // both caches are keyed by uri and the uri itself may have just changed, so they are cleared
      // by matching the row id in the value rather than by computing a key
      int rows = byUri.invalidateIf(page -> page != null && Long.toString(page.id()).equals(event.key()));
      int made = rendered.invalidateIf(page -> page != null && Long.toString(page.id()).equals(event.key()));
      verbose.detail(() -> "cache: content " + event.key() + " invalidated (" + rows + " row, " + made + " rendered)");
      return;
    }
    if (event.touches(Schema.TEMPLATES)) {
      // the event names the template row, and the name is what pages reference, so look it up once
      String name = templateNameFor(event.key());
      if (name == null) {
        // deleted, or unreadable; the safe answer is to drop everything rendered
        int all = rendered.clear();
        templates.clear();
        verbose.detail(() -> "cache: template " + event.key() + " gone, dropped " + all + " rendered page(s)");
        return;
      }
      templates.invalidate(name);
      int dropped = rendered.invalidateIf(page -> page != null && name.equals(page.templateName()));
      verbose.detail(() -> "cache: template " + name + " changed, dropped " + dropped + " rendered page(s)");
    }
  }

  private String templateNameFor(String id) {
    try {
      for (TemplateRecord template : content.allTemplates(MAX_PAGES)) {
        if (Long.toString(template.id()).equals(id)) {
          return template.name();
        }
      }
    } catch (SQLException ex) {
      LOG.error("template-lookup-failed", ex);
    }
    return null;
  }

  /**
   * The page at a uri, rendered and ready to send, or null when there is none.
   *
   * Keyed by uri and only by uri. An earlier version also stored it under the row id, which made
   * lookups marginally cheaper and invalidation wrong -- dropping one key left the other serving a
   * stale page. One key per entry, and invalidation matches on the id inside the value.
   */
  public Rendered page(String uri) {
    Rendered hit = rendered.get(uri);
    if (hit != null) {
      return hit;
    }
    ContentRecord page = lookup(uri);
    if (page == null || !page.published()) {
      return null;
    }
    Rendered made = render(page);
    if (made != null) {
      rendered.put(uri, made);
    }
    return made;
  }

  /**
   * The listing a directory template publishes, if this request is asking for one.
   *
   * This is what makes the content table behave like a blog without anybody building a blog. A
   * template with `directory` ticked owns an address; a request for that address gets every
   * published page naming that template, a page at a time, rendered through the template itself --
   * so the operator decides what a listing looks like exactly as they decide what a page looks
   * like.
   *
   * Deliberately not cached. A listing changes whenever any page using it changes, which is a
   * cascade with one more edge than the page cache has, and the query behind it is one indexed scan
   * of a few hundred rows. The pages *in* it are cached individually as always.
   */
  public Rendered directory(String path, String query) {
    try {
      for (TemplateRecord template : content.allTemplates(200)) {
        if (!template.publishesDirectory()) {
          continue;
        }
        int page = template.pageOf(path, query);
        if (page <= 0) {
          continue;
        }
        return renderDirectory(template, page);
      }
    } catch (java.sql.SQLException ex) {
      LOG.error("directory-render-failed path={}", path, ex);
    }
    return null;
  }

  private Rendered renderDirectory(TemplateRecord template, int page) throws java.sql.SQLException {
    int size = template.pageSize();
    int total = content.countUsingTemplate(template.name());
    int lastPage = Math.max(1, (total + size - 1) / size);
    if (page > lastPage) {
      // asking for page nine of a four-page listing is a link that has gone stale, and an empty
      // page that says nothing is worse than a 404 somebody can act on
      return null;
    }
    List<ContentRecord> entries =
        content.usingTemplate(template.name(), template.newestFirst(), (page - 1) * size, size);

    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (ContentRecord entry : entries) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("uri", entry.uri());
      row.put("title", entry.title());
      row.put("folder", entry.navFolder());
      // the first paragraph or so, as plain text: a listing that carried markup a page wrote would
      // be markup inside somebody else's layout, and an unclosed tag would take the page with it
      row.put("excerpt", excerptOf(entry));
      row.put("at", entry.createdAt() == null ? "" : entry.createdAt().toLocalDateTime()
          .toLocalDate().toString());
      // whatever the template asked every page to fill in is offered to the listing too, so a
      // "post" template with a `summary` field can show summaries rather than excerpts
      for (Map.Entry<String, String> field : fieldsOf(entry).entrySet()) {
        row.putIfAbsent(field.getKey(), field.getValue());
      }
      rows.add(row);
    }

    Map<String, Object> model = new HashMap<>();
    model.put("directory", true);
    model.put("entries", rows);
    model.put("anyEntries", !rows.isEmpty());
    model.put("title", template.name());
    model.put("uri", template.urlFor(page));
    model.put("count", total);
    model.put("page", page);
    model.put("pages", lastPage);
    model.put("first", page == 1);
    model.put("last", page == lastPage);
    model.put("hasPrev", page > 1);
    model.put("hasNext", page < lastPage);
    model.put("prevUrl", template.urlFor(page - 1));
    model.put("nextUrl", template.urlFor(page + 1));
    model.put("firstUrl", template.urlFor(1));
    model.put("lastUrl", template.urlFor(lastPage));
    ArrayList<Map<String, Object>> numbers = new ArrayList<>();
    for (int n = 1; n <= lastPage; n++) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("n", n);
      row.put("url", template.urlFor(n));
      row.put("here", n == page);
      numbers.add(row);
    }
    model.put("numbers", numbers);
    // `body` so a template that renders {{{body}}} shows something rather than nothing on the day
    // somebody ticks the box before writing the listing half
    model.put("body", fallbackListing(rows));

    // the index's own markup when there is one, and the page template when there is not -- every
    // directory written before the second body existed keeps working exactly as it did
    byte[] html = template.hasOwnIndex()
        ? renderInline("index:" + template.id() + ":"
            + (template.updatedAt() == null ? 0L : template.updatedAt().getTime()),
            template.directoryBody(), model)
        : renderWithTemplate(template.name(), model);
    if (html == null) {
      return null;
    }
    return new Rendered(0, template.urlFor(page), template.name(), html,
        template.updatedAt() == null ? 0L : template.updatedAt().getTime());
  }

  /**
   * Render a feed page: the operator's shape, filled in with what the community holds.
   *
   * The body is a mustache rather than markdown, because a listing is a loop and markdown has no
   * loops. It is compiled through the same cache the operator's templates use -- keyed by the
   * content row rather than by a template name -- so a shape used on every request is parsed once,
   * and editing it drops the compiled form exactly as editing a template does.
   *
   * The finished body then goes through the named template like any other page, so a feed page
   * wears the community's layout without the operator repeating it.
   *
   * @param tag what to remember this was built from, so the event bus can drop it later. It rides
   *     in the rendered page's template slot, which is what the invalidation predicate can see.
   */
  public Rendered renderFeed(ContentRecord row, Map<String, Object> model, String tag) {
    Mustache shape = compiledBody(row);
    if (shape == null) {
      return null;
    }
    StringWriter writer = new StringWriter(4096);
    try {
      shape.execute(writer, model).flush();
    } catch (Exception ex) {
      LOG.error("feed-render-failed uri={}", row.uri(), ex);
      return null;
    }
    String body = writer.toString();
    String html = wrap(row, body);
    return new Rendered(row.id(), row.uri(), tag, html.getBytes(StandardCharsets.UTF_8),
        row.updatedAt() == null ? 0L : row.updatedAt().getTime());
  }

  /**
   * Render an operator's markup that is not a named template: a feed page's body, a directory's
   * index. Compiled through the same cache, keyed by whatever the caller says identifies it.
   */
  byte[] renderInline(String key, String body, Map<String, Object> model) {
    Mustache shape = templates.get(key);
    if (shape == null) {
      try {
        shape = new DefaultMustacheFactory().compile(new StringReader(body == null ? "" : body),
            key);
        templates.put(key, shape);
      } catch (RuntimeException ex) {
        LOG.error("inline-compile-failed key={}", key, ex);
        return null;
      }
    }
    StringWriter writer = new StringWriter(4096);
    try {
      shape.execute(writer, model).flush();
    } catch (Exception ex) {
      LOG.error("inline-render-failed key={}", key, ex);
      return null;
    }
    return writer.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** the compiled form of one page's own body, cached beside the operator's templates */
  private Mustache compiledBody(ContentRecord row) {
    String key = "content:" + row.id() + ":"
        + (row.updatedAt() == null ? 0L : row.updatedAt().getTime());
    Mustache hit = templates.get(key);
    if (hit != null) {
      return hit;
    }
    try {
      Mustache made = new DefaultMustacheFactory()
          .compile(new StringReader(row.body() == null ? "" : row.body()), key);
      templates.put(key, made);
      return made;
    } catch (RuntimeException ex) {
      // a shape that does not parse is an operator error; better an honest 404 than a 500, and the
      // editor is where they will find out
      LOG.error("feed-compile-failed uri={}", row.uri(), ex);
      return null;
    }
  }

  /** the values a page filled in for its template's declared fields */
  public static Map<String, String> fieldsOf(ContentRecord entry) {
    LinkedHashMap<String, String> values = new LinkedHashMap<>();
    String blob = entry.fields();
    if (blob == null || blob.isBlank()) {
      return values;
    }
    try {
      com.fasterxml.jackson.databind.JsonNode node =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(blob);
      node.fields().forEachRemaining(field -> values.put(field.getKey(),
          field.getValue().isTextual() ? field.getValue().asText() : field.getValue().toString()));
    } catch (Exception ex) {
      // a blob nobody can read is a page with no extra fields, never a listing that fails
    }
    return values;
  }

  /** the plain first words of a page, for a listing */
  private static String excerptOf(ContentRecord entry) {
    String html = entry.kind() == ContentRecord.Kind.markdown
        ? Markdown.toHtml(entry.body()) : entry.body();
    String text = io.hearth.web.Html.text(html).replace('\n', ' ').trim();
    if (text.length() <= 240) {
      return text;
    }
    int cut = text.lastIndexOf(' ', 240);
    return text.substring(0, cut > 120 ? cut : 240).trim() + "…";
  }

  /** what a listing looks like before anybody has written the markup for one */
  private static String fallbackListing(List<Map<String, Object>> rows) {
    StringBuilder out = new StringBuilder("<ul class=\"directory\">");
    for (Map<String, Object> row : rows) {
      out.append("<li><a href=\"").append(esc(String.valueOf(row.get("uri")))).append("\">")
          .append(esc(String.valueOf(row.get("title")))).append("</a>");
      String at = String.valueOf(row.get("at"));
      if (!at.isBlank()) {
        out.append(" <small>").append(esc(at)).append("</small>");
      }
      String excerpt = String.valueOf(row.get("excerpt"));
      if (!excerpt.isBlank()) {
        out.append("<p>").append(esc(excerpt)).append("</p>");
      }
      out.append("</li>");
    }
    return out.append("</ul>").toString();
  }

  private static String esc(String value) {
    return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;");
  }

  private ContentRecord lookup(String uri) {
    ContentRecord cached = byUri.get(uri);
    if (cached != null) {
      return cached;
    }
    try {
      ContentRecord page = content.byUri(uri);
      if (page != null) {
        byUri.put(uri, page);
      }
      return page;
    } catch (SQLException ex) {
      LOG.error("content-lookup-failed", ex);
      return null;
    }
  }

  /** markdown or HTML into a body, then the template around it; 'page' skips both */
  /**
   * Render a page that is not the current one, for a preview.
   *
   * Goes through the same renderer as anything else -- and deliberately through the *current*
   * template, because restoring this version would produce exactly this. A preview that rendered
   * with some historical template would be showing something that cannot be brought back.
   */
  public String renderPreview(ContentRecord page) {
    Rendered rendered = render(page);
    return rendered == null
        ? "<p>That version could not be rendered.</p>"
        : new String(rendered.html(), java.nio.charset.StandardCharsets.UTF_8);
  }

  Rendered render(ContentRecord page) {
    try {
      // a page reconstructed from history has no timestamp, and rendering must not depend on one
      long updatedAt = page.updatedAt() == null ? 0L : page.updatedAt().getTime();
      if (page.kind() == ContentRecord.Kind.page) {
        return new Rendered(page.id(), page.uri(), page.templateName(),
            page.body().getBytes(StandardCharsets.UTF_8), updatedAt);
      }
      String body = page.kind() == ContentRecord.Kind.markdown
          ? Markdown.toHtml(page.body())
          : page.body();
      String html = wrap(page, body);
      return new Rendered(page.id(), page.uri(), page.templateName(),
          html.getBytes(StandardCharsets.UTF_8), updatedAt);
    } catch (RuntimeException ex) {
      LOG.error("content-render-failed", ex);
      return null;
    }
  }

  private String wrap(ContentRecord page, String body) {
    if (!page.usesTemplate()) {
      // no template named: the body is the document, which is the sensible thing for a quick page
      return body;
    }
    Mustache template = compiled(page.templateName());
    if (template == null) {
      verbose.detail(() -> "content: " + page.uri() + " names template '" + page.templateName()
          + "' which does not exist; serving the body alone");
      return body;
    }
    Map<String, Object> model = new HashMap<>();
    // triple-stash in the template, because the body is markup this server generated
    model.put("body", body);
    model.put("title", page.title());
    model.put("uri", page.uri());
    // What this page filled in for the fields its template declared -- and the whole point of
    // declaring one. Without this the feature was wired at both ends and not in the middle: the
    // template declares `subtitle`, the editor draws a box, the value is stored on the row, it is
    // handed to a directory listing's entries and to the feeds, and then `{{subtitle}}` on the
    // page's own template renders as nothing. Nobody meets that until they have written the
    // template, filled the box in and reloaded, at which point the obvious suspicion is the
    // template.
    //
    // putIfAbsent, so a field somebody called `title` or `body` cannot shadow the real one. Same
    // rule the listing rows already use, for the same reason.
    for (Map.Entry<String, String> field : fieldsOf(page).entrySet()) {
      model.putIfAbsent(field.getKey(), field.getValue());
    }
    StringWriter writer = new StringWriter(4096);
    try {
      template.execute(writer, model).flush();
    } catch (Exception ex) {
      LOG.error("template-render-failed", ex);
      return body;
    }
    return writer.toString();
  }

  /**
   * Render an arbitrary model through an operator-authored template.
   *
   * The address book uses this: a kind of place names a template, and every place of that kind is
   * rendered through it. Going through Site rather than compiling separately means these share the
   * template cache and the invalidation cascade -- editing a template updates the ranch pages at
   * the same moment it updates the content pages, which is the behaviour anybody would assume.
   *
   * Returns null when there is no such template, so the caller can fall back to a built-in page
   * rather than serving a blank one.
   */
  public byte[] renderWithTemplate(String name, Map<String, Object> model) {
    Mustache template = compiled(name);
    if (template == null) {
      return null;
    }
    StringWriter writer = new StringWriter(4096);
    try {
      template.execute(writer, model).flush();
    } catch (Exception ex) {
      LOG.error("template-render-failed name={}", name, ex);
      return null;
    }
    return writer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private Mustache compiled(String name) {
    Mustache hit = templates.get(name);
    if (hit != null) {
      return hit;
    }
    try {
      TemplateRecord record = content.templateByName(name);
      if (record == null) {
        return null;
      }
      // a factory per compile: these are operator-authored strings, not classpath resources, and
      // the shared factory caches by name in a way that would fight our own invalidation
      Mustache made = new DefaultMustacheFactory().compile(new StringReader(record.body()), name);
      templates.put(name, made);
      return made;
    } catch (SQLException ex) {
      LOG.error("template-load-failed", ex);
      return null;
    } catch (RuntimeException ex) {
      // a template that does not parse is an operator error; serve the body rather than a 500
      LOG.error("template-compile-failed", ex);
      return null;
    }
  }

  /** the bytes for a page, plus what they were made from */
  public record Rendered(long id, String uri, String templateName, byte[] html, long updatedAt) {
    public String templateName() {
      return templateName;
    }
  }
}
