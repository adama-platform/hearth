package io.hearth.web;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.netty.handler.codec.http.FullHttpRequest;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pages that are not part of an account flow: the home page, and the two refusals.
 *
 * All three go through mustache, so the layout and the navigation are shared with the account pages
 * rather than being a second, drifting copy of the same markup.
 */
public class Pages {
  private final Templates templates;

  public Pages(Templates templates) {
    this.templates = templates;
  }

  /** the happy path: a domain that exists, named by its own config */
  public byte[] hello(DomainConfig config, Accounts accounts, FullHttpRequest req, boolean diagnostics, String csrf) {
    Map<String, Object> model = new HashMap<>();
    Chrome.site(model, config, accounts, req);
    model.put("title", config.name);
    model.put("community", config.name);
    model.put("domain", config.domain);
    model.put("registerUrl", config.urls.register);
    model.put("loginUrl", config.urls.login);
    model.put("nav", Navigation.forRequest(config, accounts, req));
    // the sign-out control in the nav is a form, so it needs the token like any other form
    model.put("csrf", csrf);
    // the config's path on disk only appears under --verbose; it is useful while working out why a
    // host resolved the way it did, and filesystem layout disclosure to anybody else
    model.put("diagnostics", diagnostics);
    model.put("configFile", config.configFile.toString());
    model.put("databaseDomain", config.databaseDomain());
    SessionRecord session = AccountRoutes.currentSession(accounts, req);
    model.put("signedIn", session != null);
    if (session != null) {
      model.put("email", emailFor(accounts, session));
    }
    return templates.render("landing", model);
  }

  /** signed in, but an admin has not said yes yet */
  public byte[] waiting(DomainConfig config, Accounts accounts, String selfUrl) {
    Map<String, Object> model = new HashMap<>();
    Chrome.site(model, accounts);
    model.put("title", "Waiting for approval");
    model.put("community", config.name);
    model.put("nav", List.of());
    model.put("heading", "Waiting for approval");
    model.put("message", "Your account exists and your address is confirmed. An admin has to say yes"
        + " before you can look around. In the meantime, filling in your profile is the thing most"
        + " likely to get you approved.");
    Map<String, Object> link = new HashMap<>();
    link.put("href", selfUrl);
    link.put("label", "Go to your page");
    model.put("link", link);
    return templates.render("message", model);
  }

  /**
   * A community that exists, and an address in it that does not.
   *
   * Different from {@link #notFound()}, which is about a *domain* this server knows nothing about
   * and therefore carries no community name, no colours and no navigation. Here the community is
   * real and the address is not, so the page wears its colours and offers the way out -- including,
   * for somebody with no session, the sign-in form carrying where they were going, because a
   * lapsed session on the way to a link is the ordinary reason to meet this page.
   */
  public byte[] missing(DomainConfig config, Accounts accounts, FullHttpRequest req) {
    Map<String, Object> model = new HashMap<>();
    Chrome.site(model, config, accounts, req);
    model.put("title", "not found");
    model.put("community", config.name);
    model.put("home", "/");
    model.put("nav", Navigation.forRequest(config, accounts, req));
    if (accounts != null && AccountRoutes.currentSession(accounts, req) == null) {
      model.put("signInUrl", Landing.carry(config.urls.login, Landing.here(req)));
    }
    return templates.render("missing", model);
  }

  public byte[] notFound() {
    return templates.render("notfound", shell("not found"));
  }

  public byte[] badHost() {
    return templates.render("badhost", shell("bad request"));
  }

  /**
   * The refusal pages carry no community name and no navigation on purpose: we are declining to
   * know anything about the domain that was asked for, so telling the caller about some other
   * domain's community would be strange at best.
   */
  private static Map<String, Object> shell(String title) {
    Map<String, Object> model = new HashMap<>();
    // no domain resolved, so there is no community to ask for colours
    Chrome.site(model, null);
    model.put("title", title);
    model.put("community", "Hearth");
    model.put("nav", List.of());
    return model;
  }

  private static String emailFor(Accounts accounts, SessionRecord session) {
    try {
      UserRecord user = accounts.users.byId(session.userId());
      return user == null ? "someone" : user.email();
    } catch (SQLException ex) {
      return "someone";
    }
  }
}
