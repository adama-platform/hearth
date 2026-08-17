package io.hearth.web;

import io.hearth.auth.Accounts;
import io.hearth.theme.Theme;

import java.util.Map;

/**
 * The two things every page carries whatever it is: an icon, and a palette.
 *
 * One call rather than two puts because they are always wanted together, and the failure mode of
 * forgetting the second is quiet -- a page with no palette renders with no custom properties at all,
 * so the text and the background both fall back to the browser's defaults and the page is *nearly*
 * readable. That is the kind of bug somebody finds a month later on one screen.
 *
 * The scope is the caller's decision because it is a real one: the admin section and the legal pages
 * use the administration's colours, and everything a member sees uses the community's.
 */
public final class Chrome {
  private Chrome() {
  }

  /** the community's colours, for everything a member looks at */
  public static void site(Map<String, Object> model, Accounts accounts) {
    apply(model, accounts, Theme.Scope.site);
  }

  /**
   * The colours, and the live channel.
   *
   * The script is referenced by `src` rather than inlined, which is why no page needs a nonce for
   * it: `script-src 'self'` already allows a same-origin file, and the browser parses it once for
   * the whole site instead of on every navigation. Its configuration rides in `data-` attributes,
   * because an escaped template value inside a `<script>` block is invariant 22's bug.
   *
   * Only for somebody signed in. There is nothing live about a page for a stranger, and an
   * unauthenticated stream would be a connection anybody on the internet could hold open.
   */
  public static void site(Map<String, Object> model, io.hearth.vhost.DomainConfig config,
                          Accounts accounts, io.netty.handler.codec.http.FullHttpRequest req) {
    apply(model, accounts, Theme.Scope.site);
    // the standing "you owe the community N answers" line, on every page a member sees rather than
    // only on the two they might not visit
    if (config != null && accounts != null && req != null) {
      model.put("banner", Navigation.banner(config, accounts, req));
    }
    io.hearth.auth.SessionRecord session =
        accounts == null || req == null ? null : AccountRoutes.currentSession(accounts, req);
    boolean live = session != null && config != null;
    model.put("live", live);
    if (live) {
      model.put("liveUrl", io.hearth.live.LiveRoutes.ROOT);
      model.put("bellUrl", config.urls.self + "?tab=notifications");
      model.put("meId", session.userId());
    }
  }

  /** the administration's colours, for the admin section and the legal pages */
  public static void admin(Map<String, Object> model, Accounts accounts) {
    apply(model, accounts, Theme.Scope.admin);
  }

  /**
   * @param accounts null when no domain resolved -- a bad Host header, a 404 for a name this server
   *     knows nothing about. There is no community to ask, so the page gets what the software ships.
   */
  public static void apply(Map<String, Object> model, Accounts accounts, Theme.Scope scope) {
    model.put("favicon", Icons.FAVICON_DATA_URI);
    // both halves of the light/dark switch, on every page for the same reason the palette is: the
    // button lives in the layout, so a page that forgot these would render a switch with nothing
    // in it and nobody would notice until somebody tried to press it
    model.put("sunIcon", Icons.of("sun"));
    model.put("moonIcon", Icons.of("moon"));
    model.put("palette", accounts == null
        ? Theme.defaultFor(scope).css() : accounts.themes.css(scope));
    // the colour a phone paints around an installed app, and the one it flashes while a page
    // loads. The light background rather than the accent: this is the page's own edge, and an
    // accent-coloured bar over a white page is a stripe nobody asked for.
    Theme theme = accounts == null ? Theme.defaultFor(scope) : accounts.themes.of(scope);
    model.put("themeColor", theme.light.bg());
  }
}
