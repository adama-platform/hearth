package io.hearth.inbox;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The HTML half of a message, made safe to put on a page.
 *
 * <b>This is the most hostile input this server handles.</b> Everything else it renders was typed
 * by somebody with an account; this arrives from strangers, all day, and is written by people whose
 * job is getting a browser to do something. So it goes through jsoup's cleaner against a closed
 * list -- not a filter that removes what looks dangerous, which is a game nobody wins, but a list of
 * what survives.
 *
 * <b>Nothing remote is ever fetched, and that is a promise the whole project makes.</b> A remote
 * image in an email is a tracking pixel: it tells the sender the moment you opened it, from which
 * address, on what. Every `src` pointing at somebody else's server is removed and counted, and the
 * screen says how many -- so "this message wanted to load 6 things from elsewhere" is information
 * about the sender rather than a silent decision. It is also exactly what `default-src 'self'`
 * would have refused anyway, so allowing them would produce broken-image icons and a lie.
 *
 * <b>Inline images are kept, because they are the message.</b> A `cid:` reference points at a part
 * of the same message, already on this machine, already through {@link Safety} -- so it is rewritten
 * to a URL this server serves and nothing leaves the building.
 *
 * <b>No style attributes and no `<style>` block.</b> CSS can position an element over the rest of
 * the page, and a message that can draw on top of the interface around it can draw a sign-in form.
 * The cost is that a newsletter looks plain, which is the right trade in a mail reader that is
 * trying to get you to zero rather than to browse.
 */
public final class MailHtml {
  /**
   * What survives.
   *
   * Deliberately smaller than jsoup's `relaxed`: no `style`, no `class`, no `id`, no `iframe`, no
   * form controls, no `object`, no `embed`. Tables stay because half of real email is a table.
   */
  private static final Safelist ALLOWED = new Safelist()
      .addTags("a", "b", "blockquote", "br", "caption", "cite", "code", "col", "colgroup", "dd",
          "div", "dl", "dt", "em", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "li",
          "ol", "p", "pre", "q", "s", "small", "span", "strike", "strong", "sub", "sup", "table",
          "tbody", "td", "tfoot", "th", "thead", "tr", "u", "ul")
      .addAttributes("a", "href", "title")
      .addAttributes("img", "src", "alt", "title", "width", "height")
      .addAttributes("blockquote", "cite")
      .addAttributes("col", "span")
      .addAttributes("colgroup", "span")
      .addAttributes("td", "colspan", "rowspan")
      .addAttributes("th", "colspan", "rowspan", "scope")
      .addProtocols("a", "href", "http", "https", "mailto", "tel")
      .addProtocols("blockquote", "cite", "http", "https");

  /** what a message's markup wanted, and what it got */
  public record Cleaned(String html, int blockedRemote) {
  }

  private MailHtml() {
  }

  /**
   * Sanitize a message body, rewriting the inline images it is allowed to keep.
   *
   * @param inlineUrls Content-ID to a URL on this server, for the parts that passed {@link Safety}.
   *                   A `cid:` reference that is not in this map is removed like a remote one: it
   *                   names a part that either does not exist or was refused, and an image tag
   *                   pointing at nothing is a broken icon in the middle of somebody's message.
   */
  public static Cleaned clean(String html, Map<String, String> inlineUrls) {
    if (html == null || html.isBlank()) {
      return new Cleaned("", 0);
    }
    Document parsed = Jsoup.parseBodyFragment(html);
    int blocked = 0;

    // Rewrite before cleaning, not after.
    //
    // The safelist has no idea what `cid:` is, so an untouched one is dropped by the protocol check
    // and the image is gone before there is a chance to fix it. Turning it into a same-origin path
    // first means the cleaner sees an ordinary relative URL and keeps it.
    for (Element image : parsed.select("img")) {
      String source = image.attr("src").trim();
      if (source.toLowerCase(Locale.ROOT).startsWith("cid:")) {
        String url = inlineUrls.get(source.substring(4).trim());
        if (url != null) {
          image.attr("src", url);
          continue;
        }
      }
      // Everything else goes, including `data:` -- an inline data URL is not a network request and
      // is still an arbitrary payload a browser will try to decode, from a stranger, for no benefit
      // this reader needs.
      if (!source.isEmpty()) {
        blocked++;
      }
      image.remove();
    }
    // background="..." and anything else that fetches; the safelist drops these anyway, and
    // counting them first is what makes the number on the screen true
    for (Element element : parsed.select("[background]")) {
      blocked++;
      element.removeAttr("background");
    }

    Document cleaned = new Cleaner(ALLOWED).clean(parsed);
    for (Element link : cleaned.select("a[href]")) {
      // Somebody else's link, opened in a new tab, unable to reach back through window.opener and
      // carrying no referrer -- a mail reader must not tell a sender which message you clicked from.
      link.attr("rel", "nofollow noopener noreferrer");
      link.attr("target", "_blank");
    }
    for (Element image : cleaned.select("img[src]")) {
      // after cleaning, anything left has to be one of ours; belt and braces, because this is the
      // one attribute where being wrong means a request leaving the machine
      String source = image.attr("src");
      if (!source.startsWith("/")) {
        image.remove();
      }
    }
    cleaned.outputSettings().prettyPrint(false);
    return new Cleaned(cleaned.body().html(), blocked);
  }

  /**
   * The plain-text half, turned into something a browser can show, when there is no HTML at all.
   *
   * Not markdown: a message is not markdown, and running it through a renderer turns asterisks into
   * italics in somebody's password reset email. This escapes, keeps the line breaks, and makes
   * bare URLs clickable -- which is the whole of what a plain-text message needs.
   */
  public static String fromText(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    StringBuilder out = new StringBuilder(text.length() + 64);
    for (String line : text.split("\r?\n", -1)) {
      String escaped = org.jsoup.nodes.Entities.escape(line);
      out.append(linkify(escaped)).append('\n');
    }
    return "<pre class=\"mail-plain\">" + out.toString().stripTrailing() + "</pre>";
  }

  /**
   * Bare URLs in plain text, made clickable.
   *
   * Runs on the <b>escaped</b> string, so anything that looked like markup is already inert and the
   * only thing being introduced is an anchor this code wrote. Doing it the other way round -- link
   * first, escape after -- would escape the anchor, and doing neither leaves somebody copying URLs
   * by hand out of every plain-text message.
   */
  private static String linkify(String escaped) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("https?://[^\\s<>\"']+").matcher(escaped);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String url = matcher.group();
      // trailing punctuation is almost always the sentence rather than the address
      String trailing = "";
      while (!url.isEmpty() && ".,;:!?)]".indexOf(url.charAt(url.length() - 1)) >= 0) {
        trailing = url.charAt(url.length() - 1) + trailing;
        url = url.substring(0, url.length() - 1);
      }
      matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
          "<a href=\"" + url + "\" rel=\"nofollow noopener noreferrer\" target=\"_blank\">"
              + url + "</a>" + trailing));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  /** the plain words of a message, for a preview or a notification */
  public static String toText(String html) {
    if (html == null || html.isBlank()) {
      return "";
    }
    Document parsed = Jsoup.parseBodyFragment(html);
    parsed.select("br").append("\\n");
    parsed.select("p, div, tr, li, h1, h2, h3, h4, h5, h6").append("\\n");
    return parsed.text().replace("\\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
  }

  /** empty rather than null, and never a map somebody else can add to */
  public static Map<String, String> noInlineImages() {
    return new LinkedHashMap<>();
  }
}
