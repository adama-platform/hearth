package io.hearth.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.util.ArrayList;
import java.util.List;

/**
 * The two things this server does to HTML with a parser rather than with string handling.
 *
 * <b>Cleaning</b> is the answer to the stored HTML injection this project shipped with. Markdown
 * here renders raw HTML through, which is
 * right for a page an operator wrote -- they can already replace the whole document with a `page`
 * kind, so filtering their markdown would be a lock on a door with no wall around it. It is wrong
 * for anything a *member* typed: a profile, a post, a comment. Those are read by an administrator
 * who is obliged to open them, written by somebody not yet trusted, and the difference between the
 * two is who is holding the pen, not what they typed.
 *
 * The Content Security Policy already stops an injected script from running. That is defence in
 * depth and not a reason to stop here -- a policy is one header away from being wrong, and
 * defacement of the page a reviewer reads does not need script execution to be a problem.
 *
 * <b>Compacting</b> is the other direction: templates are written to be read, with a newline after
 * every block, and none of that whitespace means anything to a browser. Removing it needs a parser
 * for exactly one reason -- whitespace *between inline elements is content*. `<a>one</a> <a>two</a>`
 * is two words; `<p>one</p> <p>two</p>` is two paragraphs and a space nobody will ever see. A
 * regular expression cannot tell those apart, and the version of this that tried ran words together.
 *
 * Both are deliberately conservative. Nothing here reformats, re-indents, or rewrites markup it
 * understands -- the output is the same document with less air in it.
 */
public final class Html {
  /**
   * What a member may write.
   *
   * Relaxed, minus images. A remote image in a post is a request to somebody else's server carrying
   * the reader's address, which is the one thing this project will not do to its own members -- and
   * `default-src 'self'` would refuse to load it anyway, so allowing the tag would only produce a
   * broken-image icon. When uploads land, they will be same-origin and this is where they get let
   * back in.
   *
   * `javascript:` and `data:` URLs go with it: the protocol list on a href is an allowlist, so
   * anything not named is dropped rather than escaped.
   */
  private static final Safelist MEMBER = Safelist.relaxed()
      .removeTags("img")
      .addAttributes("a", "title")
      .addProtocols("a", "href", "http", "https", "mailto");

  /** what an anchor somebody else wrote always carries, whatever they typed */
  private static final String LINK_REL = "nofollow ugc noopener";

  private Html() {
  }

  /**
   * Strip everything from this HTML that a member has no business writing.
   *
   * Input is rendered markdown, so it is already HTML; this decides which of it survives. Unknown
   * tags lose their markup and keep their text, which is the behaviour that matters: somebody who
   * writes `<script>` in a post gets the words, not the tag, and nothing they wrote silently
   * vanishes.
   */
  public static String clean(String html) {
    if (html == null || html.isEmpty()) {
      return "";
    }
    Document cleaned = new Cleaner(MEMBER).clean(Jsoup.parseBodyFragment(html));
    for (Element link : cleaned.select("a[href]")) {
      // written by somebody who is not the operator, so it says so to a search engine and cannot
      // reach back through window.opener
      link.attr("rel", LINK_REL);
    }
    cleaned.outputSettings().prettyPrint(false);
    return cleaned.body().html();
  }

  /** the plain words, for a notification or a preview, with the markup taken off */
  public static String text(String html) {
    if (html == null || html.isEmpty()) {
      return "";
    }
    return Jsoup.parseBodyFragment(html).text();
  }

  /**
   * The same document with the template's whitespace taken out.
   *
   * A whitespace-only text node between two block elements is removed outright. Everywhere else a
   * run of whitespace collapses to a single space, which is exactly what a browser would have
   * rendered anyway. `pre` and `textarea` are left alone, because there the whitespace is the
   * content -- and `script` and `style` never come near this, since jsoup keeps their contents as
   * data rather than as text.
   */
  public static String compact(String html) {
    if (html == null || html.isEmpty()) {
      return html;
    }
    String leading = html.stripLeading();
    boolean whole = leading.regionMatches(true, 0, "<!doctype", 0, 9)
        || leading.regionMatches(true, 0, "<html", 0, 5);
    Document document = whole ? Jsoup.parse(html) : Jsoup.parseBodyFragment(html);
    document.outputSettings().prettyPrint(false);
    squeeze(document);
    return whole ? document.outerHtml() : document.body().html();
  }

  /**
   * Collect first, then mutate.
   *
   * Removing nodes during a traversal changes the tree the traversal is walking, and the bug that
   * produces is the kind that only shows up on one page in twenty.
   */
  private static void squeeze(Document document) {
    List<TextNode> texts = new ArrayList<>();
    document.traverse((node, depth) -> {
      if (node instanceof TextNode text && !preserved(text)) {
        texts.add(text);
      }
    });
    for (TextNode text : texts) {
      String whole = text.getWholeText();
      String squeezed = collapse(whole);
      if (squeezed.isBlank()) {
        if (betweenBlocks(text)) {
          text.remove();
        } else if (!" ".equals(whole)) {
          text.text(" ");
        }
      } else if (!squeezed.equals(whole)) {
        text.text(squeezed);
      }
    }
  }

  /** every run of whitespace becomes one space; the leading and trailing runs are kept as one */
  private static String collapse(String text) {
    StringBuilder out = new StringBuilder(text.length());
    boolean space = false;
    for (int k = 0; k < text.length(); k++) {
      char ch = text.charAt(k);
      if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\f') {
        space = true;
        continue;
      }
      if (space) {
        out.append(' ');
        space = false;
      }
      out.append(ch);
    }
    if (space) {
      out.append(' ');
    }
    return out.toString();
  }

  /** inside anything where the whitespace is the point */
  private static boolean preserved(TextNode text) {
    for (Node node = text.parent(); node != null; node = node.parent()) {
      if (node instanceof Element element && element.tag().preserveWhitespace()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Is this whitespace safe to delete outright?
   *
   * Only when nothing beside it is inline. A space between two paragraphs renders as nothing; a
   * space between two links is the gap between two words, and deleting it is a visible bug in
   * somebody's sentence.
   */
  private static boolean betweenBlocks(TextNode text) {
    return blockish(text.previousSibling()) && blockish(text.nextSibling())
        && !(text.parent() instanceof Element parent && parent.tag().isInline());
  }

  private static boolean blockish(Node sibling) {
    if (sibling == null) {
      return true;
    }
    return sibling instanceof Element element && !element.tag().isInline();
  }
}
