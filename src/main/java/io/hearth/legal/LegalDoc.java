package io.hearth.legal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The documents every community has, whether or not anybody has thought about them.
 *
 * A closed list rather than a general "pages an admin can write", because these two are different
 * from ordinary content in three ways: every email links to them, they exist before anybody has
 * written anything, and the default text is a considered thing rather than a placeholder. A
 * community that wants a third document writes a page.
 *
 * The defaults ship as markdown in the jar and are loaded once at class initialisation, which keeps
 * the promise that the disk is a startup concern. They are written for the shape of community this
 * program is for -- a few hundred people who meet in person -- and they say plainly that the
 * software's authors and the hosting provider are not parties to anything, because in a
 * self-hosted community that is the fact somebody has to be told.
 *
 * <b>They are a starting point, not legal advice.</b> Nobody here is a lawyer, the defaults cannot
 * know which country a community is in, and the admin screen says so above the editor.
 */
public enum LegalDoc {
  terms("terms-of-service", "Terms of Service",
      "What using this community means, and who is on the hook for what"),
  privacy("privacy-policy", "Privacy Policy",
      "What is held about a member, why, and for how long");

  /** the path segment, and the filename the default lives in */
  public final String slug;
  public final String title;
  /** one line, for the admin listing */
  public final String summary;
  private final String standard;

  LegalDoc(String slug, String title, String summary) {
    this.slug = slug;
    this.title = title;
    this.summary = summary;
    this.standard = load(slug);
  }

  /** the public path; fixed rather than configurable, because emails from anywhere link to it */
  public String path() {
    return LegalRoutes.ROOT + "/" + slug;
  }

  /** the text this community starts with */
  public String standard() {
    return standard;
  }

  public static LegalDoc bySlug(String slug) {
    if (slug == null) {
      return null;
    }
    for (LegalDoc doc : values()) {
      if (doc.slug.equals(slug)) {
        return doc;
      }
    }
    return null;
  }

  /**
   * Fill in what only the running server knows.
   *
   * Three substitutions and no template engine: this text is edited by administrators in a
   * markdown box, and anything that could evaluate what they typed would be a way to reach the
   * model from a text field. A literal replace cannot do anything but replace.
   */
  public static String fill(String markdown, String community, String domain) {
    if (markdown == null) {
      return "";
    }
    return markdown
        .replace("{{community}}", community == null || community.isBlank() ? domain : community)
        .replace("{{domain}}", domain == null ? "" : domain);
  }

  private static String load(String slug) {
    String resource = "/legal/" + slug + ".md";
    try (InputStream stream = LegalDoc.class.getResourceAsStream(resource)) {
      if (stream == null) {
        // a jar built without its own documents; loud, because the alternative is a community
        // publishing an empty privacy policy and nobody noticing
        throw new IllegalStateException("missing " + resource + " in the jar");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("could not read " + resource, ex);
    }
  }
}
