package io.hearth.auth;

import io.hearth.cache.Caches;
import io.hearth.common.Verbose;
import io.hearth.content.Site;
import io.hearth.people.PeopleStore;
import io.hearth.events.EventBus;
import io.hearth.store.Store;

/**
 * Everything account-shaped for one database: the people, their sessions, the codes in flight, and
 * the policy all three answer to.
 *
 * Bound to a database rather than to a domain, because that is what sharing actually means. When
 * junior.example.org points its use_database_domain at example.org, the two do not merely happen
 * to have the same rows -- they are one account space, so a session minted on one is valid on the
 * other and one policy has to govern both.
 *
 * That policy is the database owner's login_security. A shared database with two different session
 * lifetimes would be two answers to one question, so the owning domain's answer is the answer, and
 * the boot report says so.
 */
public class Accounts {
  /** the domain that owns the database, and whose login_security governs it */
  public final String databaseDomain;
  public final LoginSecurity security;
  public final Users users;
  public final Sessions sessions;
  public final PendingCodes codes;
  public final Roles roles;
  /** what each role means; the grants live in roles */
  public final RoleDefs roleDefs;
  public final Access access;
  /** addresses refused before anything expensive happens */
  public final Bans bans;
  /** the connectors allowed to hold an agent token here */
  public final io.hearth.mcp.OauthClients oauthClients;
  /** the static pages this database serves, and the caches in front of them */
  public final Site site;
  /** profiles, questions and answers */
  public final PeopleStore people;
  /** keeps the remaining-question counts current, off the request path */
  /** which browsers we can reach, one row per session */
  public final io.hearth.push.PushSubs pushSubs;
  /** the colours this community chose, for the site and for the admin */
  public final io.hearth.theme.Themes themes;

  /**
   * What this community decided about itself, as opposed to what its operator did.
   *
   * Lives beside the other per-database caches because that is what it is. The values it holds are
   * applied by rebuilding the domain's whole {@link io.hearth.vhost.DomainConfig}, so nothing here
   * is read on a request path -- readers go on holding a finished config object.
   */
  public final io.hearth.settings.SettingStore settings;
  /** the terms and the privacy policy: what this community said, or what the software ships */
  public final io.hearth.legal.LegalDocs legal;
  /** what every message says, in this community's words when it has written any */
  public final io.hearth.mail.SystemTemplates messages;
  /** when a push went out and when somebody acted on it; buffered, flushed every few minutes */
  public final io.hearth.push.PushLedger pushLedger;
  /** the record of everything uploaded; the bytes live under the root, not in here */
  public final io.hearth.attach.Attachments attachments;
  /** the database itself, for the one caller that has to write across several tables at once */
  public final Store store;

  /**
   * This account space's clock, which an administrator can now change.
   *
   * Volatile and not final because the timezone is a setting: it decides what "today" means to
   * everybody reading a page, and a copy taken at boot would go on being a few hours wrong until
   * somebody restarted the server. {@link io.hearth.auth.AuthSystem#applySettings} moves it in the
   * same breath as it swaps the rebuilt config in.
   */
  private volatile java.time.ZoneId zone;

  public Accounts(Store store, String databaseDomain, LoginSecurity security,
                  java.util.Set<String> bootstrapAdmins, Caches caches, EventBus events,
                  Verbose verbose) {
    this(store, databaseDomain, security, bootstrapAdmins, caches, events,
        java.time.ZoneId.systemDefault(), verbose);
  }

  /**
   * @param zone the community's clock, which decides what "today" means on anything this builds.
   */
  public Accounts(Store store, String databaseDomain, LoginSecurity security,
                  java.util.Set<String> bootstrapAdmins, Caches caches, EventBus events,
                  java.time.ZoneId zone, Verbose verbose) {
    this.databaseDomain = databaseDomain;
    this.security = security;
    this.store = store;
    this.users = new Users(store);
    this.sessions = new Sessions(store, security, verbose);
    this.codes = new PendingCodes(security);
    this.roles = new Roles(store);
    this.roleDefs = new RoleDefs(store);
    this.access = new Access(roles, roleDefs, bootstrapAdmins);
    this.bans = new Bans(store, databaseDomain);
    this.oauthClients = new io.hearth.mcp.OauthClients(store);
    this.site = new Site(databaseDomain, store, caches, events, verbose);
    this.people = new PeopleStore(store);
    this.pushSubs = new io.hearth.push.PushSubs(store);
    this.pushLedger = new io.hearth.push.PushLedger(store);
    this.attachments = new io.hearth.attach.Attachments(store);
    this.themes = new io.hearth.theme.Themes(store);
    this.settings = new io.hearth.settings.SettingStore(store);
    this.legal = new io.hearth.legal.LegalDocs(store);
    this.messages = new io.hearth.mail.SystemTemplates(store);
    this.sessions.cascadeTo(this.pushSubs);
    // after everything it reads from, and given each of them by name rather than given this whole
    // object: a feed page can show the calendar, the address book and the directory, and nothing
    // about that list should be discoverable by a page reaching for whatever is nearby
    this.zone = zone == null ? java.time.ZoneId.systemDefault() : zone;
  }


  /** what "today" means here */
  public java.time.ZoneId zone() {
    return zone;
  }

  /** moved when the community changes its clock, so nothing holds a copy of the old one */
  public void clockIs(java.time.ZoneId moved) {
    if (moved != null) {
      this.zone = moved;
    }
  }

  public void start() {
    try {
      // the built-in roles exist before the socket opens, so the first request cannot arrive at a
      // database where nobody is an administrator
      roleDefs.seed();
      // read once, before the socket opens: every page render asks the theme for its colours and
      // every email footer asks where the terms are
      themes.load();
      settings.load();
      legal.load();
      messages.load();
      // and the merge keys, so an export is a bundle every row of which can be brought back
      site.store().stampMissingUuids();
    } catch (java.sql.SQLException ex) {
      throw new IllegalStateException("could not seed the built-in roles", ex);
    }
    sessions.start();
  }

  public void shutdown() {
    sessions.shutdown();
  }
}
