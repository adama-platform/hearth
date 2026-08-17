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

  /** the unread count, which must never be the reason a page fails to render */
  private static int unreadFor(Accounts accounts, long userId) {
    try {
      return accounts.inbox.unreadCount(userId);
    } catch (java.sql.SQLException ex) {
      return 0;
    }
  }

  /**
   * Is the community asking anything at all?
   *
   * One scan of a table with a few dozen rows in it, per page, for a signed-in person -- which is
   * the cheap answer at a hundred to a thousand members and would be the wrong one at a hundred
   * thousand. The alternative is an entry in the bar that leads to a page saying "nothing here",
   * which is how people learn to stop looking at a bar. Never the reason a page fails to render.
   */
  private static boolean anyQuestions(Accounts accounts) {
    try {
      return !accounts.people.publishedQuestions().isEmpty();
    } catch (java.sql.SQLException ex) {
      return false;
    }
  }

  /**
   * The one line above every page when the community is waiting on somebody.
   *
   * <b>Not a banner and not a modal.</b> It is one sentence with a link, it is only there when there
   * is something to do, and it goes away the moment they do it -- which is what stops it becoming
   * furniture people stop seeing. A survey nobody is reminded about is a survey that gets answered
   * by the four people who were going to answer anyway.
   */
  public static Map<String, Object> banner(DomainConfig config, Accounts accounts,
                                           FullHttpRequest req) {
    SessionRecord session = accounts == null ? null : AccountRoutes.currentSession(accounts, req);
    if (session == null || !config.has(io.hearth.vhost.Surface.survey)) {
      return null;
    }
    int remaining = accounts.survey.remainingFor(session.userId());
    if (remaining <= 0) {
      return null;
    }
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("text", remaining == 1
        ? "There is a question waiting for you."
        : "There are " + remaining + " questions waiting for you.");
    out.put("href", config.urls.survey);
    out.put("label", remaining == 1 ? "Answer it" : "Answer them");
    return out;
  }

  public static List<Map<String, Object>> forRequest(DomainConfig config, Accounts accounts, FullHttpRequest req) {
    ArrayList<Map<String, Object>> items = new ArrayList<>();
    SessionRecord session = accounts == null ? null : AccountRoutes.currentSession(accounts, req);
    // "Home" means two different pages, deliberately. For a stranger it is the community's front
    // page, which is whatever they wrote there; for a member it is the dashboard, which is about
    // them. One label because it is the same idea -- the place you start -- and two destinations
    // because a front page written for newcomers is not where somebody who is already here wants
    // to land.
    items.add(link("Home", accounts != null && session != null ? config.urls.home : "/"));
    if (accounts == null) {
      return items;
    }
    if (session != null) {
      if (config.has(io.hearth.vhost.Surface.board)) {
        items.add(link("Discussion", config.urls.board));
      }
      if (config.has(io.hearth.vhost.Surface.calendar)) {
        items.add(link("Events", config.urls.calendar));
      }
      if (config.has(io.hearth.vhost.Surface.places)) {
        items.add(link(config.places.label, config.urls.places));
      }
      if (config.has(io.hearth.vhost.Surface.members)) {
        items.add(link("Members", config.urls.members));
      }
      // Below the community's own sections: this is the one place in the bar that is mostly about
      // the person rather than the group -- their own routine, their own list -- and it earns its
      // spot because it is opened daily by whoever uses it at all.
      if (config.has(io.hearth.vhost.Surface.tasks)) {
        items.add(link("Projects", config.urls.tasks));
      }
      // beside the events rather than on somebody's profile: it is a thing you fill in once and a
      // thing whoever plans the events reads, and both of those are about the calendar
      if (config.has(io.hearth.vhost.Surface.availability)
          && config.has(io.hearth.vhost.Surface.calendar)) {
        items.add(link("When", config.urls.availability));
      }
      // The survey is a place of its own now, so it carries its own number rather than competing
      // with the inbox for the one on somebody's profile. A community that asks nothing gets no
      // entry at all, because a permanently empty page in the bar is a page people learn to skip.
      int remaining = accounts.survey.remainingFor(session.userId());
      if (config.has(io.hearth.vhost.Surface.survey) && anyQuestions(accounts)) {
        Map<String, Object> survey = link("Questions", config.urls.survey);
        if (remaining > 0) {
          survey.put("bubble", Integer.toString(remaining));
        }
        items.add(survey);
      }
      int unread = unreadFor(accounts, session.userId());
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
      // The bell. Drawn dark and lit from the live channel, so a reply arriving in a conversation
      // somebody is watching is visible from whatever page they are on. It goes to their
      // notifications, which is where the same thing is written down.
      LinkedHashMap<String, Object> bell = new LinkedHashMap<>();
      bell.put("label", "Notifications");
      bell.put("href", config.urls.self + "?tab=notifications");
      bell.put("post", false);
      bell.put("bell", true);
      if (unread > 0) {
        bell.put("lit", true);
        bell.put("count", Integer.toString(unread));
      }
      items.add(bell);
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
