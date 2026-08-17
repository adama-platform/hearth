package io.hearth.mail;

import io.hearth.theme.Theme;

/**
 * One shape for every message this server sends.
 *
 * Email is not HTML. It is HTML from 2004 with a different renderer in every client, and each of the
 * rules below is here because something breaks without it:
 *
 *   - <b>tables, not divs</b> -- Outlook renders through Word, which has no flexbox and no grid;
 *   - <b>inline styles only</b> -- Gmail discards a stylesheet, including the one in the head;
 *   - <b>a bulletproof button</b> -- a table cell with a background colour and the anchor filling
 *     it, because a styled anchor loses its background in several clients;
 *   - <b>600px, fluid below that</b> -- the width every client's reading pane assumes;
 *   - <b>a preheader</b> -- the grey line beside the subject, which otherwise shows whatever text
 *     comes first, and that is usually the community name for the second time;
 *   - <b>a declared colour scheme</b> -- so a dark-mode client knows we have thought about it
 *     rather than inverting the message on our behalf;
 *   - <b>every link repeated as text</b> -- for the clients that disable them and the people who
 *     copy them into another browser;
 *   - <b>always a plain-text half</b> -- some people read that one, and every spam filter does.
 *
 * There used to be two implementations of this: a plain one for codes and a designed one for
 * invitations. Two implementations of "what our email looks like" is one that gets the community's
 * colours and one that does not, so this is the only one, and the invitation is a caller like any
 * other.
 *
 * The footer is not optional and is why this class exists as much as the styling does. Every message
 * says which community sent it, why this person is receiving it, and that interacting with the
 * community means accepting its terms -- with the link. A promise nobody was shown is not one.
 */
public final class MailLayout {
  private static final int WIDTH = 600;
  private static final String FONT =
      "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
  private static final String MONO = "ui-monospace,SFMono-Regular,Menlo,Consolas,monospace";

  private final MailBrand brand;
  private final Theme.Palette palette;
  private final String title;
  private final String preheader;
  private final StringBuilder rows = new StringBuilder(4096);
  private String why;
  private boolean first = true;

  public MailLayout(MailBrand brand, String title, String preheader) {
    this.brand = brand;
    this.palette = brand.palette();
    this.title = title;
    this.preheader = preheader;
  }

  /** the line in the footer saying why this landed in their inbox; every flow should set one */
  public MailLayout because(String reason) {
    this.why = reason;
    return this;
  }

  /** the first sentence, in the reading size */
  public MailLayout lead(String text) {
    return row(pad() + "font-family:" + FONT + ";font-size:16px;line-height:24px;color:"
        + palette.fg() + ";", esc(text));
  }

  public MailLayout paragraph(String text) {
    return row(pad() + "font-family:" + FONT + ";font-size:15px;line-height:23px;color:"
        + palette.fg() + ";", esc(text).replace("\n", "<br>"));
  }

  /** smaller and quieter: a closing note, an explanation, something nobody has to read */
  public MailLayout note(String text) {
    return row(pad() + "font-family:" + FONT + ";font-size:13px;line-height:20px;color:"
        + palette.dim() + ";", esc(text).replace("\n", "<br>"));
  }

  /** somebody else's words, set apart so they read as theirs */
  public MailLayout quote(String text) {
    return row(pad(), "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\""
        + " cellspacing=\"0\" border=\"0\"><tr><td style=\"border-left:3px solid " + palette.line()
        + ";padding:4px 0 4px 14px;font-family:" + FONT + ";font-size:15px;line-height:23px;color:"
        + palette.fg() + ";\">" + esc(text) + "</td></tr></table>");
  }

  /**
   * The code, which is the whole message when there is one.
   *
   * Big, monospaced and letter-spaced, because it is read off one screen and typed into another.
   * `user-select:all` is a small kindness on the clients that honour it: one tap selects the code
   * rather than three characters of it.
   */
  public MailLayout code(String code) {
    return row(pad(), "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
        + " style=\"border:1px solid " + palette.line() + ";border-radius:10px;background:"
        + tint() + ";\"><tr><td align=\"center\" style=\"padding:14px 26px;font-family:" + MONO
        + ";font-size:30px;line-height:1.15;font-weight:700;letter-spacing:.22em;color:"
        + palette.fg() + ";-webkit-user-select:all;user-select:all;\">" + esc(code)
        + "</td></tr></table>");
  }

  /** a table cell with a background and the link filling it; the only button email can rely on */
  public MailLayout button(String label, String href) {
    return row(pad(), "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\""
        + " border=\"0\"><tr><td align=\"center\" bgcolor=\"" + palette.accent()
        + "\" style=\"background:" + palette.accent() + ";border-radius:8px;\">"
        + "<a href=\"" + esc(href) + "\" style=\"display:inline-block;padding:13px 26px;"
        + "font-family:" + FONT + ";font-size:16px;font-weight:600;color:" + onAccent()
        + ";text-decoration:none;border-radius:8px;\">" + esc(label) + "</a></td></tr></table>");
  }

  /** the same address as text, for the clients that strip links */
  public MailLayout linkAsText(String href) {
    return row("padding:4px 32px 0;font-family:" + FONT + ";font-size:12px;line-height:19px;color:"
        + palette.dim() + ";word-break:break-all;",
        "Or paste this into a browser:<br>" + esc(href));
  }

  /** a list of things that happened, for a digest */
  public MailLayout items(java.util.List<String> lines) {
    StringBuilder list = new StringBuilder("<table role=\"presentation\" width=\"100%\""
        + " cellpadding=\"0\" cellspacing=\"0\" border=\"0\">");
    for (String line : lines) {
      list.append("<tr><td style=\"padding:7px 0;border-bottom:1px solid ").append(palette.line())
          .append(";font-family:").append(FONT).append(";font-size:15px;line-height:22px;color:")
          .append(palette.fg()).append(";\">").append(esc(line)).append("</td></tr>");
    }
    return row(pad(), list.append("</table>").toString());
  }

  /** the whole message; the pixel is last and only when there is one */
  public String html(String pixel) {
    StringBuilder out = new StringBuilder(8192);
    out.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        .append("<meta name=\"color-scheme\" content=\"light dark\">")
        .append("<meta name=\"supported-color-schemes\" content=\"light dark\">")
        .append("<title>").append(esc(title)).append("</title></head>")
        .append("<body style=\"margin:0;padding:0;background:").append(page())
        .append(";-webkit-text-size-adjust:100%;\">");

    if (preheader != null && !preheader.isBlank()) {
      out.append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">")
          .append(esc(preheader)).append("</div>");
    }

    out.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"")
        .append(" border=\"0\" style=\"background:").append(page())
        .append(";\"><tr><td align=\"center\" style=\"padding:24px 12px;\">")
        .append("<table role=\"presentation\" width=\"").append(WIDTH).append("\" cellpadding=\"0\"")
        .append(" cellspacing=\"0\" border=\"0\" style=\"width:100%;max-width:").append(WIDTH)
        .append("px;background:").append(palette.panel()).append(";border-radius:12px;border:1px solid ")
        .append(palette.line()).append(";\">");

    // the head: who this is from, before anything about what it says
    out.append("<tr><td style=\"padding:26px 32px 6px;font-family:").append(FONT)
        .append(";font-size:19px;font-weight:600;letter-spacing:-.01em;color:")
        .append(palette.fg()).append(";\">").append(esc(brand.nameOr())).append("</td></tr>");
    out.append("<tr><td style=\"padding:0 32px;\"><table role=\"presentation\" width=\"36\"")
        .append(" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"height:3px;")
        .append("background:").append(palette.accent()).append(";border-radius:2px;font-size:0;")
        .append("line-height:0;\">&nbsp;</td></tr></table></td></tr>");

    out.append(rows);
    out.append(footer());
    out.append("</table></td></tr></table>");

    if (pixel != null && !pixel.isBlank()) {
      out.append("<img src=\"").append(esc(pixel)).append("\" width=\"1\" height=\"1\" alt=\"\"")
          .append(" style=\"display:block;width:1px;height:1px;border:0;\">");
    }
    return out.append("</body></html>").toString();
  }

  private String footer() {
    StringBuilder foot = new StringBuilder();
    foot.append("<tr><td style=\"padding:22px 32px 26px;margin-top:8px;font-family:").append(FONT)
        .append(";font-size:12px;line-height:19px;color:").append(palette.dim())
        .append(";border-top:1px solid ").append(palette.line()).append(";\">");
    if (why != null && !why.isBlank()) {
      foot.append(esc(why)).append("<br>");
    }
    // the sentence that makes the link mean something. Interacting with a community is what accepts
    // its terms, and saying so in the message is the only place most people will ever read it.
    foot.append("Using ").append(esc(brand.nameOr()))
        .append(" &mdash; including replying to this message &mdash; means you accept its ")
        .append(link("terms", brand.termsUrl())).append(" and ")
        .append(link("privacy policy", brand.privacyUrl())).append(".<br>")
        .append(link(esc(brand.domain()), brand.siteUrl()));
    return foot.append("</td></tr>").toString();
  }

  private String link(String label, String href) {
    return "<a href=\"" + esc(href) + "\" style=\"color:" + palette.accent()
        + ";text-decoration:underline;\">" + label + "</a>";
  }

  private MailLayout row(String style, String content) {
    rows.append("<tr><td style=\"").append(style).append("\">").append(content).append("</td></tr>");
    first = false;
    return this;
  }

  /** the first block sits closer to the rule above it than the ones after it do */
  private String pad() {
    return first ? "padding:18px 32px 0;" : "padding:14px 32px 0;";
  }

  /** the page behind the card: the community's background, which is usually near-white */
  private String page() {
    return palette.bg();
  }

  /** a wash of the accent, for the box a code sits in */
  private String tint() {
    return mix(palette.accent(), palette.panel(), 0.07);
  }

  /**
   * Black or white on the accent, whichever can be read.
   *
   * A community that picks a pale yellow accent gets dark text on its buttons rather than white on
   * white. The threshold is the usual relative-luminance one; getting this wrong makes a button
   * that looks fine to whoever chose the colour and is invisible to everybody else.
   */
  private String onAccent() {
    return luminance(palette.accent()) > 0.55 ? "#1a1a1a" : "#ffffff";
  }

  private static double luminance(String hex) {
    int[] rgb = rgb(hex);
    return (0.2126 * channel(rgb[0]) + 0.7152 * channel(rgb[1]) + 0.0722 * channel(rgb[2]));
  }

  private static double channel(int value) {
    double c = value / 255.0;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  private static String mix(String front, String back, double amount) {
    int[] a = rgb(front);
    int[] b = rgb(back);
    StringBuilder out = new StringBuilder("#");
    for (int k = 0; k < 3; k++) {
      int value = (int) Math.round(a[k] * amount + b[k] * (1 - amount));
      out.append(String.format("%02x", Math.max(0, Math.min(255, value))));
    }
    return out.toString();
  }

  private static int[] rgb(String hex) {
    String value = Theme.isColour(hex) ? Theme.normalize(hex) : "#000000";
    return new int[]{
        Integer.parseInt(value.substring(1, 3), 16),
        Integer.parseInt(value.substring(3, 5), 16),
        Integer.parseInt(value.substring(5, 7), 16)};
  }

  /**
   * The plain-text half's footer, which carries exactly the same promise.
   *
   * A message whose HTML says what you are agreeing to and whose text does not is a message that
   * says nothing to whoever reads the text -- and spam filters read the text.
   */
  public static String textFooter(MailBrand brand, String why) {
    StringBuilder foot = new StringBuilder("\n--\n");
    if (why != null && !why.isBlank()) {
      foot.append(why).append('\n');
    }
    foot.append("Using ").append(brand.nameOr())
        .append(" means you accept its terms and privacy policy:\n")
        .append(brand.termsUrl()).append('\n')
        .append(brand.privacyUrl()).append('\n')
        .append(brand.nameOr()).append(", ").append(brand.domain()).append('\n');
    return foot.toString();
  }

  /** escaping for both text and attributes, since half of this is inside one */
  static String esc(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(value.length() + 16);
    for (int k = 0; k < value.length(); k++) {
      char ch = value.charAt(k);
      switch (ch) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(ch);
      }
    }
    return out.toString();
  }
}
