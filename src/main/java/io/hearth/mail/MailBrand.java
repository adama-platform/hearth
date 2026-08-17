package io.hearth.mail;

import io.hearth.legal.LegalDoc;
import io.hearth.theme.Theme;

/**
 * What a message looks like and what it has to say about itself, decided per community.
 *
 * Two things travel together here because both are properties of the community rather than of the
 * message: the colours it chose, and where its terms live. Every email carries both -- the palette
 * so that a message looks like the place it came from, and the terms link because a person receiving
 * mail from a community is interacting with it, and being told what that means is the point of
 * having written it down.
 *
 * <b>Only the light palette.</b> Email clients handle `prefers-color-scheme` badly and
 * inconsistently: some ignore it, some invert colours themselves, and some do both to different
 * parts of one message. A dark background chosen here would arrive at half the readers as a black
 * box with black text on it, which is worse than a design that ignores dark mode. The message
 * declares `color-scheme: light dark` so a client that inverts knows we have thought about it, and
 * everything else is light.
 */
public record MailBrand(String domain, String communityName, Theme.Palette palette) {
  public MailBrand {
    palette = palette == null ? Theme.SITE_LIGHT : palette;
  }

  /** what a community that has never opened the appearance screen sends */
  public static MailBrand standard(String domain, String communityName) {
    return new MailBrand(domain, communityName, Theme.SITE_LIGHT);
  }

  public String nameOr() {
    return communityName == null || communityName.isBlank() ? domain : communityName;
  }

  /**
   * Absolute, because this is going into somebody's inbox.
   *
   * https rather than a scheme the sending server happens to be running: a link in an email is
   * clicked days later from a different network, and a community with a certificate is the normal
   * case. A community with no certificate gets a redirect rather than a broken link.
   */
  public String url(String path) {
    return "https://" + domain + path;
  }

  public String termsUrl() {
    return url(LegalDoc.terms.path());
  }

  public String privacyUrl() {
    return url(LegalDoc.privacy.path());
  }

  public String siteUrl() {
    return url("/");
  }

  /**
   * Where somebody turns this off.
   *
   * The privacy policy this software ships says notifications and digests rest on consent, "which
   * you withdraw by changing the setting" -- and the messages did not say where the setting was.
   * A promise in a policy and a way out in the message are the same obligation written twice, and
   * only one of them is in front of the person at the moment they want it.
   *
   * A path rather than a configured URL because `urls.self` is per domain and the mailer does not
   * hold a config; every community this server sends for has this page, and an operator who moved
   * it will be redirected there by the server itself.
   */
  public String settingsUrl() {
    return url("/self?tab=notifications");
  }
}
