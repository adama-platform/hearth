package io.hearth.template;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import io.hearth.common.Verbose;
import io.hearth.web.Html;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mustache, compiled once at boot.
 *
 * Every template is parsed during startup and held as a compiled object, so rendering a page is
 * string building with no file access. That keeps the promise that the disk is a startup concern,
 * and it means a broken template is a server that refuses to start rather than a 500 the first time
 * somebody visits the page it broke.
 *
 * Mustache specifically because it is logic-less. A template can loop over the navigation and
 * substitute a name; it cannot query the database or decide who is allowed to see something. That
 * boundary is worth more than the convenience a richer template language would buy.
 *
 * HTML escaping is on by default for {{value}} -- names, email addresses, and anything else a
 * person typed go through it. {{{value}}} does not escape, and is used only for markup this code
 * generated.
 *
 * Rendering is also where the whitespace comes out. Templates are written with a newline after
 * every block so that a person can read them, and none of it means anything to a browser --
 * {@link Html#compact} takes it out with a parser, which is the only thing that can tell the space
 * between two paragraphs from the space between two words. This is the right place for it because
 * it is the one funnel every page goes through, and because a page cached by {@link
 * io.hearth.content.Site} is then cached in the form it will be sent in rather than being squeezed
 * again on every hit.
 */
public class Templates {
  /** every page this server can render; compiled at boot, so a typo here fails fast */
  public static final List<String> PAGES = List.of(
      "landing", "notfound", "missing", "badhost", "minted", "message", "self", "connect",
      "app", "sw", "legal", "ask", "install",
      // the admin: one page per section, plus the panel each refreshable section renders on its own
      // URL, plus the create/edit forms that live on pages of their own rather than above a listing
      "admin/overview",
      "admin/people", "admin/people_panel", "admin/people_review",
      "admin/bans", "admin/bans_panel",
      "admin/content", "admin/content_panel", "admin/content_form",
      "admin/bundles", "admin/content_history", "admin/content_version", "admin/content_changes",
      "admin/templates", "admin/templates_panel", "admin/templates_form",
      "admin/directories", "admin/directories_form",
      "admin/navigation",
      "admin/roles", "admin/roles_panel", "admin/roles_form",
      "admin/ai", "admin/ai_panel",
      "admin/events", "admin/events_panel",
      "admin/analytics", "admin/machine",
      "admin/caching", "admin/caching_panel",
      "admin/logs", "admin/logs_panel",
      "admin/appearance", "admin/configuration", "admin/setup",
      "admin/legal", "admin/legal_form",
      "admin/messages", "admin/messages_form",
      "admin/attachments", "admin/attachments_panel", "admin/unused",
      "admin/settings", "admin/cleanup");

  /**
   * Templates whose output is not HTML.
   *
   * The service worker is JavaScript. Handing it to an HTML parser would produce an HTML document
   * containing a program, which is a comical failure and an easy one to make -- so the list is here
   * rather than in a guess about what the output looks like.
   */
  private static final java.util.Set<String> NOT_HTML = java.util.Set.of("sw");

  private final Map<String, Mustache> compiled;
  private final boolean compact;

  private Templates(Map<String, Mustache> compiled, boolean compact) {
    this.compiled = compiled;
    this.compact = compact;
  }

  /** compile everything from the classpath; throws if any template is missing or malformed */
  public static Templates compile(Verbose verbose) throws TemplateException {
    return compile(verbose, true);
  }

  /**
   * @param compact take the template's own whitespace out of every page. On by default; the switch
   *     exists because this rewrites every byte a browser sees, and an operator staring at output
   *     that looks wrong should be able to take one variable out of the question.
   */
  public static Templates compile(Verbose verbose, boolean compact) throws TemplateException {
    MustacheFactory factory = new DefaultMustacheFactory("templates");
    LinkedHashMap<String, Mustache> compiled = new LinkedHashMap<>();
    for (String page : PAGES) {
      String resource = page + ".mustache";
      try {
        compiled.put(page, factory.compile(resource));
        verbose.detail("compiled template " + resource);
      } catch (Exception ex) {
        throw new TemplateException("could not compile templates/" + resource + ": " + ex.getMessage(), ex);
      }
    }
    verbose.say("compiled " + compiled.size() + " template(s)"
        + (compact ? "" : "; whitespace compaction is off"));
    return new Templates(compiled, compact);
  }

  /** render a page; the model is a plain map, which mustache walks by name */
  public byte[] render(String page, Map<String, Object> model) {
    Mustache mustache = compiled.get(page);
    if (mustache == null) {
      throw new IllegalArgumentException("no such template: " + page);
    }
    StringWriter writer = new StringWriter(4096);
    try {
      mustache.execute(writer, model).flush();
    } catch (IOException ex) {
      // a StringWriter does not do I/O; this cannot happen outside a JVM bug
      throw new IllegalStateException("rendering " + page + " failed", ex);
    }
    String rendered = writer.toString();
    if (compact && !NOT_HTML.contains(page)) {
      rendered = Html.compact(rendered);
    }
    return rendered.getBytes(StandardCharsets.UTF_8);
  }

  public boolean has(String page) {
    return compiled.containsKey(page);
  }

  /** a template that would not compile; fatal at boot */
  public static class TemplateException extends Exception {
    public TemplateException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
