package io.hearth.auth;

import io.hearth.cache.Caches;
import io.hearth.common.Verbose;
import io.hearth.content.Site;
import io.hearth.people.PeopleStore;
import io.hearth.people.SurveyIndexer;
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
  /** invitations, and whether they turned into members */
  public final io.hearth.people.Invites invites;
  /** what is happening, and who said they would come */
  public final io.hearth.calendar.Calendar calendar;
  /** the discussion board */
  public final io.hearth.board.Board board;
  /** what a conversation is trying to settle: options, votes and what wins */
  public final io.hearth.board.Polls polls;
  /** projects, what a task is, one occasion of it, and what was recorded */
  public final io.hearth.tasks.TaskStore tasks;
  /** what happened while somebody was away */
  public final io.hearth.board.Inbox inbox;
  /** votes and flags: what the community thinks, and what it wants somebody to look at */
  public final io.hearth.board.Signals signals;
  /** how each person wants to hear about it */
  public final io.hearth.board.NotifyPrefs notifyPrefs;
  /** the feed and the rendered threads, dropped by the event bus */
  public final io.hearth.board.BoardCache boardCache;
  /** keeps the remaining-question counts current, off the request path */
  /** the address book: places, and the kinds of place this community records */
  public final io.hearth.places.Places places;
  /** which browsers we can reach, one row per session */
  public final io.hearth.push.PushSubs pushSubs;
  /** the colours this community chose, for the site and for the admin */
  public final io.hearth.theme.Themes themes;
  /** the terms and the privacy policy: what this community said, or what the software ships */
  public final io.hearth.legal.LegalDocs legal;
  /** what every message says, in this community's words when it has written any */
  public final io.hearth.mail.SystemTemplates messages;
  /** when a push went out and when somebody acted on it; buffered, flushed every few minutes */
  public final io.hearth.push.PushLedger pushLedger;
  /** the record of everything uploaded; the bytes live under the root, not in here */
  public final io.hearth.attach.Attachments attachments;
  /** when people can come, the calendars they pointed us at, and what those said */
  public final io.hearth.availability.Availability availability;
  /** the community's own pages for its events, its address book and its members */
  public final io.hearth.content.Feeds feeds;
  public final SurveyIndexer survey;
  /** the database itself, for the one caller that has to write across several tables at once */
  public final Store store;

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
    this.invites = new io.hearth.people.Invites(store);
    this.calendar = new io.hearth.calendar.Calendar(store);
    this.board = new io.hearth.board.Board(store);
    this.polls = new io.hearth.board.Polls(store);
    this.tasks = new io.hearth.tasks.TaskStore(store);
    this.inbox = new io.hearth.board.Inbox(store);
    this.signals = new io.hearth.board.Signals(store);
    this.notifyPrefs = new io.hearth.board.NotifyPrefs(store);
    this.boardCache =
        new io.hearth.board.BoardCache(databaseDomain, board, caches, events, verbose);
    this.places = new io.hearth.places.Places(store);
    this.pushSubs = new io.hearth.push.PushSubs(store);
    this.pushLedger = new io.hearth.push.PushLedger(store);
    this.availability = new io.hearth.availability.Availability(store);
    this.attachments = new io.hearth.attach.Attachments(store);
    this.themes = new io.hearth.theme.Themes(store);
    this.legal = new io.hearth.legal.LegalDocs(store);
    this.messages = new io.hearth.mail.SystemTemplates(store);
    this.sessions.cascadeTo(this.pushSubs);
    // after everything it reads from, and given each of them by name rather than given this whole
    // object: a feed page can show the calendar, the address book and the directory, and nothing
    // about that list should be discoverable by a page reaching for whatever is nearby
    this.feeds = new io.hearth.content.Feeds(databaseDomain, zone, site, site.store(), calendar,
        places, users, people, access, caches, events, verbose);
    this.survey = new SurveyIndexer(databaseDomain, people, events, verbose);
  }

  /**
   * Somebody has been let in.
   *
   * The one thing that has to happen at the moment of approval rather than at sign-up: an answer
   * they gave from outside -- a calendar reply to an event this community said anybody could come
   * to -- becomes an ordinary answer, with a seat and a place in the guest list. Before approval
   * they are not somebody this community can reach, and counting them into a room would be counting
   * a stranger.
   *
   * @return how many answers came across, which is worth saying out loud to whoever approved them
   */
  public int welcome(UserRecord user) throws java.sql.SQLException {
    if (user == null) {
      return 0;
    }
    return calendar.adopt(user.id(), user.email(), java.time.LocalDate.now());
  }

  public void start() {
    try {
      // the built-in roles exist before the socket opens, so the first request cannot arrive at a
      // database where nobody is an administrator
      roleDefs.seed();
      // and the kind of place that always exists, so removing a kind never removes an address
      places.seed();
      // read once, before the socket opens: every page render asks the theme for its colours and
      // every email footer asks where the terms are
      themes.load();
      legal.load();
      messages.load();
      // and the merge keys, so an export is a bundle every row of which can be brought back
      site.store().stampMissingUuids();
    } catch (java.sql.SQLException ex) {
      throw new IllegalStateException("could not seed the built-in roles", ex);
    }
    sessions.start();
    survey.start();
  }

  public void shutdown() {
    sessions.shutdown();
    survey.shutdown();
  }
}
