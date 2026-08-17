package io.hearth.content;

import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.ext.image.attributes.ImageAttributesExtension;
import org.commonmark.ext.ins.InsExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.List;

/**
 * Markdown, with everything switched on.
 *
 * The intent is that somebody writing a page never hits a feature that "should" work and doesn't:
 * tables, strikethrough, task lists, footnotes, autolinks, heading anchors, image sizing, and
 * front matter are all enabled. Choosing a smaller subset would only produce a document that looks
 * right in a preview and wrong on the site.
 *
 * Raw HTML in markdown is allowed, and there are two renderers because that is only true for half
 * the callers. {@link #toHtml} is for words an operator wrote: they can already replace the whole
 * page with a 'page' kind document, so blocking a script tag in their markdown would be a lock on a
 * door with no wall around it. {@link #toSafeHtml} is for words a *member* wrote -- a profile, a
 * post, a comment -- where the same passthrough is stored HTML injection aimed at whoever reads it
 * next, and that is frequently an administrator opening a page they are obliged to review.
 *
 * The boundary is who is holding the pen. Picking the wrong one of these is the whole bug, so they
 * are named for the answer rather than for what they do.
 *
 * The parser and renderer are built once and are thread safe by contract, so rendering is pure
 * CPU with no per-request setup.
 */
public class Markdown {
  private static final List<org.commonmark.Extension> EXTENSIONS = List.of(
      TablesExtension.create(),
      StrikethroughExtension.create(),
      InsExtension.create(),
      AutolinkExtension.create(),
      TaskListItemsExtension.create(),
      ImageAttributesExtension.create(),
      FootnotesExtension.create(),
      YamlFrontMatterExtension.create(),
      // ids on headings, so a table of contents and deep links work without the author doing anything
      HeadingAnchorExtension.create());

  private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
  private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).build();

  private Markdown() {
  }

  /** for markdown written by somebody who could edit the whole page anyway */
  public static String toHtml(String markdown) {
    if (markdown == null || markdown.isEmpty()) {
      return "";
    }
    Node document = PARSER.parse(markdown);
    return RENDERER.render(document);
  }

  /**
   * For markdown written by a member.
   *
   * Rendered exactly the same way and then put through {@link io.hearth.web.Html#clean}, so the
   * features all work -- tables, footnotes, task lists -- and the tags nobody should be able to
   * write from a comment box do not survive. Rendering first and filtering after is deliberate:
   * filtering the markdown source would have to understand markdown, and every escape somebody
   * found would be a hole.
   */
  public static String toSafeHtml(String markdown) {
    return io.hearth.web.Html.clean(toHtml(markdown));
  }

  /** the first heading or line, for a listing where the author gave no title */
  public static String inferTitle(String markdown) {
    if (markdown == null) {
      return "";
    }
    for (String line : markdown.split("\n", 40)) {
      String trimmed = line.trim();
      if (trimmed.startsWith("#")) {
        return trimmed.replaceAll("^#+\\s*", "").trim();
      }
      if (!trimmed.isEmpty() && !trimmed.startsWith("---")) {
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
      }
    }
    return "";
  }
}
