package io.hearth.vhost;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the account pages live on a given domain.
 *
 * Configurable because a community that already has a /login for something else, or wants
 * /join instead of /register, should not have to fight the server over it. Resolved once at boot
 * into an exact-match routing table, so the request path is a hash lookup rather than a chain of
 * string comparisons.
 *
 * Paths are validated hard: they must be absolute, single-segment-safe, and free of the characters
 * that make a path ambiguous. A route table built from operator input is still a route table built
 * from input.
 */
public class SiteUrls {
  public final String register;
  public final String login;
  public final String logout;
  public final String forgotPassword;
  public final String resetPassword;
  /** the admin section; approving people lives here */
  public final String admin;
  /** where somebody reviews their own profile and answers */
  public final String self;
  /**
   * Where a successful sign-in lands.
   *
   * The community's own front page. There used to be a member dashboard at `/home` and this pointed
   * at that; the dashboard was a read of the board, the calendar and the survey, and it went when
   * they did rather than staying on as a page with nothing to say.
   */
  public final String afterLogin;

  public SiteUrls(ConfigObject config) throws ConfigException {
    this.register = path(config, "register", "/register");
    this.login = path(config, "login", "/login");
    this.logout = path(config, "logout", "/logout");
    this.forgotPassword = path(config, "forgot-password", "/forgot-password");
    this.resetPassword = path(config, "reset-password", "/reset-password");
    this.admin = path(config, "admin", "/admin");
    this.self = path(config, "self", "/self");
    this.afterLogin = path(config, "after-login", "/");
    config.assertKnownKeys();
    checkForCollisions();
  }

  /** every account route on this domain, path to route name */
  public Map<String, Route> routes() {
    LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
    routes.put(register, Route.register);
    routes.put(login, Route.login);
    routes.put(logout, Route.logout);
    routes.put(forgotPassword, Route.forgot_password);
    routes.put(resetPassword, Route.reset_password);
    routes.put(admin, Route.admin);
    routes.put(self, Route.self);
    return routes;
  }

  /** does some other feature's path clash with an account page? */
  public boolean collidesWith(String path) {
    return routes().containsKey(path);
  }

  private void checkForCollisions() throws ConfigException {
    String[][] pairs = {
        {"register", register}, {"login", login}, {"logout", logout},
        {"forgot-password", forgotPassword}, {"reset-password", resetPassword},
        {"admin", admin}, {"self", self}};
    for (int a = 0; a < pairs.length; a++) {
      for (int b = a + 1; b < pairs.length; b++) {
        if (pairs[a][1].equals(pairs[b][1])) {
          throw new ConfigException("urls." + pairs[a][0] + " and urls." + pairs[b][0]
              + " are both '" + pairs[a][1] + "'; each account page needs its own path");
        }
      }
    }
  }

  private static String path(ConfigObject config, String key, String fallback) throws ConfigException {
    String value = config.strOf(key, fallback);
    if (value.isEmpty() || value.charAt(0) != '/') {
      throw new ConfigException("urls." + key + " must start with '/'");
    }
    if (value.length() > 128) {
      throw new ConfigException("urls." + key + " must be at most 128 characters");
    }
    if (value.contains("//") || value.contains("..") || value.contains("?") || value.contains("#")) {
      throw new ConfigException("urls." + key + " must be a plain path with no '//', '..', '?' or '#'");
    }
    for (int k = 0; k < value.length(); k++) {
      char ch = value.charAt(k);
      boolean allowed = ch == '/' || ch == '-' || ch == '_' || ch == '.'
          || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
      if (!allowed) {
        throw new ConfigException("urls." + key + " may only contain letters, digits, '/', '-', '_' and '.'");
      }
    }
    if (value.length() > 1 && value.endsWith("/")) {
      throw new ConfigException("urls." + key + " must not end with '/'");
    }
    return value;
  }

  /**
   * The paths this server owns, and which of them are account pages.
   *
   * Every one of these is in the table so that two of them cannot be pointed at the same address,
   * which is what {@link #checkForCollisions} exists for. Only some of them are *forms*, though,
   * and the difference used to be implicit: {@link io.hearth.web.AccountRoutes} switched on the
   * route and simply had no branch for the others, which was invisible while every other surface
   * answered on its own path first. The moment one could be switched off, the request reached a
   * handler with nothing to say and the connection was held open until the browser gave up.
   */
  public enum Route {
    register,
    login,
    logout,
    forgot_password,
    reset_password,
    admin,
    self;

    /**
     * Is this a form this server renders, rather than a section with a handler of its own?
     *
     * A section that is switched off must fall through to the site rather than into a handler that
     * does not know what it is -- so this is the question the request path asks, and a route added
     * without an answer is not an account page.
     */
    public boolean isAccountPage() {
      return this == register || this == login || this == logout
          || this == forgot_password || this == reset_password;
    }

    /**
     * May somebody who has not been approved yet reach this?
     *
     * <b>A closed list, and it used to be "is it in the table at all".</b> That answered yes for
     * every surface there was -- the community itself, which is the one thing approval exists to
     * gate -- so a stranger who had proved an email address could read all of it. Found by a
     * review and reproduced from the outside.
     *
     * What is on the list is what somebody waiting has any business with: their own account and
     * their own page, which is what an administrator reads before saying yes. The admin section is
     * here because it does its own, stricter check and answers 404 to anybody who is not one.
     */
    public boolean isReachableUnapproved() {
      return isAccountPage() || this == admin || this == self;
    }
  }
}
