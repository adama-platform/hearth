package io.hearth.web;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.vhost.DomainConfig;
import io.netty.handler.codec.http.FullHttpRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The navigation bar, built per request from the domain's configured URLs.
 *
 * This is the reason for a template engine at all. The nav differs by domain (every path is
 * configurable) and by who is looking (signed in or not), and expressing that as string
 * concatenation inside a handler is how a page ends up with a "Sign out" link for somebody who
 * isn't signed in.
 *
 * The list is plain maps rather than a class because mustache walks it by name, and a record would
 * only add a layer for the template to see through.
 */
public class Navigation {
  private Navigation() {
  }



  /**
   * The one line above every page when the community is waiting on somebody.
   *
   * <b>Not a banner and not a modal.</b> It is one sentence with a link, it is only there when there
   * is something to do, and it goes away the moment they do it -- which is what stops it becoming
   * furniture people stop seeing. A survey nobody is reminded about is a survey that gets answered
   * by the four people who were going to answer anyway.
   */
  /** nothing sits above the bar any more; the survey it used to nag about is gone */
  public static Map<String, Object> banner(DomainConfig config, Accounts accounts,
                                           FullHttpRequest req) {
    return null;
  }

  public static List<Map<String, Object>> forRequest(DomainConfig config, Accounts accounts, FullHttpRequest req) {
    ArrayList<Map<String, Object>> items = new ArrayList<>();
    SessionRecord session = accounts == null ? null : AccountRoutes.currentSession(accounts, req);
    // "Home" means two different pages, deliberately. For a stranger it is the community's front
    // page, which is whatever they wrote there; for a member it is the dashboard, which is about
    // them. One label because it is the same idea -- the place you start -- and two destinations
    // because a front page written for newcomers is not where somebody who is already here wants
    // to land.
    items.add(link("Home", "/"));
    if (accounts == null) {
      return items;
    }
    if (session != null) {
      int unread = 0;
      Map<String, Object> self = link("Your profile", config.urls.self);
      if (unread > 0) {
        self.put("bubble", Integer.toString(unread));
      }
      items.add(self);
      // How to put it on a home screen and how to prove a notification actually arrives. It is in
      // the menu rather than mentioned once in a welcome email because the day somebody wants it
      // is the day they missed something, and that is a day they will be looking at this bar.
      if (config.has(io.hearth.vhost.Surface.app)) {
        items.add(link("Get the app", io.hearth.web.PwaRoutes.HELP));
      }
      // signing out changes state, so it is a form with a token rather than a link. A link would
      // mean any page on the internet could sign somebody out by pointing an image at it.
      items.add(form("Sign out", config.urls.logout));
    } else {
      items.add(link("Sign in", config.urls.login));
      items.add(link("Create an account", config.urls.register));
    }
    return items;
  }

  private static Map<String, Object> link(String label, String href) {
    LinkedHashMap<String, Object> item = new LinkedHashMap<>();
    item.put("label", label);
    item.put("href", href);
    item.put("post", false);
    return item;
  }

  private static Map<String, Object> form(String label, String href) {
    LinkedHashMap<String, Object> item = new LinkedHashMap<>();
    item.put("label", label);
    item.put("href", href);
    item.put("post", true);
    return item;
  }
}
