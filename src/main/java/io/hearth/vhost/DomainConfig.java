package io.hearth.vhost;

import io.hearth.auth.LoginSecurity;
import io.hearth.cache.Caches;
import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

import java.io.File;
import java.util.Map;

/**
 * One loaded config file. Immutable, built once at boot, then only read.
 *
 * Notably absent: any path to content on disk. Pages, templates and images all live in the
 * database, so a domain's config names policy and nothing else, and deploying a site is a database
 * file rather than a directory tree to keep in sync with it.
 *
 * Nothing on the request path opens a file to learn about a domain. The whole point of scanning
 * at startup is that the set of servable domains is fixed for the lifetime of the process, so a
 * bug in request handling cannot be talked into reading a config the operator didn't install. The
 * same goes for policy: login_security is parsed here, once, and a request evaluates against an
 * object that cannot change underneath it.
 */
public class DomainConfig {
  /** the domain this config is for, e.g. "junior.example.org" -- taken from the filename */
  public final String domain;
  /** the file it came from, e.g. configs/junior.example.org.cfg */
  public final File configFile;
  /** human name for the community */
  public final String name;
  /** an operator kill switch; a disabled domain reads as not-served */
  public final boolean enabled;
  /** does this config also cover subdomains that have no config of their own? */
  public final boolean wildcard;
  /**
   * Another domain whose database this one shares, or null to own its own.
   *
   * Sharing a database means sharing accounts and sessions, which is the point: a family of sites
   * where signing in once is enough. It is validated against the loaded domains at boot, so a typo
   * fails loudly instead of quietly creating a second, empty account space.
   */
  public final String useDatabaseDomain;
  /**
   * Addresses that are admins by fiat, regardless of the roles table.
   *
   * The escape hatch from the approval system: without it, the first person to sign up on a fresh
   * install has nobody who can approve them. Changing this list means editing a file on the box and
   * restarting, which is the right amount of access for appointing an administrator.
   */
  public final java.util.List<String> adminEmails;
  public final LoginSecurity loginSecurity;
  /** how long anything cached for this domain may be held */
  public final Caches caches;
  public final SiteUrls urls;
  /** whether this domain talks to models, and on what terms */
  public final io.hearth.mcp.McpConfig mcp;
  /**
   * The clock this community keeps.
   *
   * Inherited from `config.cfg` unless this domain says otherwise, which is the shape that matters:
   * one box frequently hosts one community, and occasionally hosts a supper club in Bristol and a
   * support group in Toronto. Every "today", every hour on the availability grid and every all-day
   * entry read out of somebody's calendar is read in this.
   */
  public final java.time.ZoneId zone;
  /**
   * Miles or kilometres, for the one screen that prints a distance.
   *
   * Beside the timezone and there for the same reason: this is a program for people who meet in a
   * room, and "twenty minutes away" is a fact about a place. A chart of travel distances in the
   * wrong unit is not wrong by a factor anybody notices -- it is wrong by being unreadable to the
   * people it is for. Metric by default because most of the world is, and one word in the config
   * for everybody else.
   */
  /** what may be uploaded here, and who may read it afterwards */
  public final io.hearth.attach.AttachmentConfig attachments;
  /** how this community sends email; off means codes print to the terminal */
  public final io.hearth.mail.SesConfig ses;
  /**
   * What this community switched off in one word.
   *
   * A broad decision that wins over the per-block flags: `"disabled": ["chat"]` means there is no
   * chat here whatever the chat block says. Ask {@link #has} rather than reading a block's own
   * `enabled`, so a surface cannot be off in the file and on in one handler that forgot.
   */
  public final java.util.Set<Surface> disabled;
  /**
   * Subdomains this config also answers for, by name.
   *
   * The middle setting between one config per host and `wildcard: true`. A wildcard answers for
   * *anything* underneath, which is right for a domain somebody owns entirely and wrong for one
   * where an unclaimed name should 404 -- and it is a poor thing to point a certificate order at,
   * because no authority will issue for "everything under this suffix" over HTTP-01. A list is
   * specific enough to be certifiable and to be checked.
   */
  public final java.util.List<String> subdomains;
  /** does this domain accept inbound mail, when the server is listening for any */
  public final boolean acceptsMail;
  /** account routes for this domain, path to route, resolved once */
  public final Map<String, SiteUrls.Route> routes;

  /**
   * The file this was parsed from, kept so it can be parsed again.
   *
   * The product half of a community's configuration lives in the database now, and applying it
   * means writing those values into a copy of this and re-reading the result -- so every check
   * that refuses a bad value at boot is the same check that refuses one typed into the admin
   * section. Keeping the source is what makes that possible without a second parser.
   *
   * It is the file's words rather than the running values, which is the point: the overrides are
   * applied to it fresh each time, so clearing one in the editor puts the file's answer back
   * rather than whatever happened to be in memory.
   */
  private final com.fasterxml.jackson.databind.node.ObjectNode source;
  private final String where;
  private final File configsRoot;
  private final java.time.ZoneId fallbackZone;

  private DomainConfig(String domain, File configFile, String name, boolean enabled,
                       java.time.ZoneId zone, boolean wildcard,
                       String useDatabaseDomain, java.util.List<String> adminEmails,
                       LoginSecurity loginSecurity, Caches caches, SiteUrls urls,
                       io.hearth.mcp.McpConfig mcp,
                       io.hearth.attach.AttachmentConfig attachments,
                       io.hearth.mail.SesConfig ses,
                       java.util.Set<Surface> disabled,
                       java.util.List<String> subdomains, boolean acceptsMail,
                       com.fasterxml.jackson.databind.node.ObjectNode source, String where,
                       File configsRoot, java.time.ZoneId fallbackZone) {
    this.domain = domain;
    this.configFile = configFile;
    this.name = name;
    this.enabled = enabled;
    this.zone = zone;
    this.wildcard = wildcard;
    this.useDatabaseDomain = useDatabaseDomain;
    this.adminEmails = java.util.List.copyOf(adminEmails);
    this.loginSecurity = loginSecurity;
    this.caches = caches;
    this.urls = urls;
    this.mcp = mcp;
    this.attachments = attachments;
    this.ses = ses;
    this.disabled = disabled;
    this.subdomains = java.util.List.copyOf(subdomains);
    this.acceptsMail = acceptsMail;
    this.routes = Map.copyOf(urls.routes());
    this.source = source;
    this.where = where;
    this.configsRoot = configsRoot;
    this.fallbackZone = fallbackZone;
  }

  public static DomainConfig of(String domain, File configsRoot, File configFile,
                                ConfigObject config) throws ConfigException {
    return of(domain, configsRoot, configFile, config, java.time.ZoneId.systemDefault());
  }

  /**
   * @param fallbackZone what `config.cfg` says, so a domain that names no zone keeps the box's.
   *     Passed in rather than read here, because a config file is parsed by something that has
   *     already read the server's -- and a domain guessing at the machine's zone would be a
   *     community whose clock changed when it was moved to another box.
   */
  public static DomainConfig of(String domain, File configsRoot, File configFile,
                                ConfigObject config, java.time.ZoneId fallbackZone)
      throws ConfigException {
    return of(domain, configsRoot, configFile, config, fallbackZone, null);
  }

  /**
   * @param rememberSource the JSON to keep for a later rebuild, when it is not the JSON being
   *     parsed. A rebuild parses the file's words with settings written over them, and the result
   *     has to remember the *file's* words rather than the overridden copy -- otherwise each
   *     rebuild would layer on the last and clearing a setting in the editor would revert to the
   *     previous edit instead of to what the operator wrote.
   */
  private static DomainConfig of(String domain, File configsRoot, File configFile,
                                 ConfigObject config, java.time.ZoneId fallbackZone,
                                 com.fasterxml.jackson.databind.node.ObjectNode rememberSource)
      throws ConfigException {
    String name = config.strOf("name", domain);
    java.time.ZoneId zone = io.hearth.common.ServerConfig.zoneOf(
        config.strOf("timezone", (fallbackZone == null
            ? java.time.ZoneId.systemDefault() : fallbackZone).getId()),
        configFile.getName() + ": timezone");
    boolean enabled = config.boolOf("enabled", true);
    boolean wildcard = config.boolOf("wildcard", true);
    String useDatabaseDomain = config.strOf("use_database_domain", null);
    String[] adminEmails = config.stringsOf("admin_emails", new String[0]);
    LoginSecurity loginSecurity = new LoginSecurity(config.child("login_security"));
    Caches caches = Caches.of(config.child("cache"));
    SiteUrls urls = new SiteUrls(config.child("urls"));
    io.hearth.mcp.McpConfig mcp = new io.hearth.mcp.McpConfig(config.child("mcp"));
    io.hearth.attach.AttachmentConfig attachments =
        new io.hearth.attach.AttachmentConfig(config.child("attachments"));
    io.hearth.mail.SesConfig ses = new io.hearth.mail.SesConfig(config.child("ses"));
    java.util.Set<Surface> disabled = Surface.parse(domain,
        java.util.List.of(config.stringsOf("disabled", new String[0])));

    // Each entry is a label, not a hostname: "www", never "www.example.org". Writing the whole name
    // reads fine and means a typo silently produces a host nobody is serving, so the shorter form
    // is the one that cannot be wrong about which domain it belongs to.
    java.util.ArrayList<String> subdomains = new java.util.ArrayList<>();
    for (String label : config.stringsOf("subdomains", new String[0])) {
      String clean = label == null ? "" : label.trim().toLowerCase();
      if (clean.isEmpty()) {
        continue;
      }
      if (!Hosts.isValidDomain(clean + "." + domain)) {
        throw new ConfigException("subdomains: '" + label + "' is not a usable label");
      }
      if (clean.contains(".")) {
        throw new ConfigException("subdomains: write '" + clean.split("\\.")[0]
            + "' rather than the whole hostname");
      }
      subdomains.add(clean);
    }
    boolean acceptsMail = config.boolOf("accepts-mail", true);
    config.assertKnownKeys();
    if (name.isEmpty() || name.length() > 128) {
      throw new ConfigException(configFile + ": 'name' must be 1 to 128 characters");
    }
    if (useDatabaseDomain != null && !Hosts.isValidDomain(useDatabaseDomain)) {
      throw new ConfigException(configFile + ": use_database_domain '" + useDatabaseDomain + "' is not a valid domain");
    }
    java.util.ArrayList<String> admins = new java.util.ArrayList<>();
    for (String email : adminEmails) {
      String clean = email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
      if (clean == null || clean.isEmpty() || clean.indexOf('@') <= 0) {
        throw new ConfigException(configFile + ": admin_emails entry '" + email + "' is not an email address");
      }
      admins.add(clean);
    }
    if (mcp.enabled && admins.isEmpty()) {
      // the flow requires an admin to click approve, and a domain with no admin_emails has nobody
      // who could ever be one on a fresh install -- so this would be a door with no key
      throw new ConfigException(configFile + ": mcp is enabled but admin_emails is empty,"
          + " so nobody could ever authorize a connector");
    }
    if (mcp.enabled && urls.collidesWith(mcp.path)) {
      throw new ConfigException(configFile + ": mcp.path '" + mcp.path
          + "' is already one of this domain's account pages");
    }
    return new DomainConfig(domain, configFile, name, enabled, zone,
        wildcard, useDatabaseDomain,
        admins, loginSecurity, caches, urls, mcp, attachments, ses,
        disabled, subdomains, acceptsMail,
        rememberSource != null ? rememberSource : config.node.deepCopy(),
        configFile.getName(), configsRoot, fallbackZone);
  }

  /**
   * The same community, with what it has decided about itself applied.
   *
   * Settings are written into a copy of the file's own JSON and the whole thing is parsed again,
   * which is deliberately the long way round. The short way -- reaching into the parsed objects and
   * replacing fields -- would need every block to know how to be edited, and would skip the
   * cross-checks at the end of {@link #of} that catch a path collision or an enabled endpoint with
   * nobody who could authorize it. This way a value from the database is exactly as suspect as a
   * value from the file, and is refused in the same words.
   *
   * Security-bearing keys are absent from the settings catalogue, so there is nothing in the map
   * that could reach one. This is the second half of that: what arrives here is applied to a copy
   * of the file, so even a row somebody wrote into the database by hand can only change a key the
   * catalogue knows, because {@link io.hearth.settings.Setting} is what knows where a key goes.
   */
  public DomainConfig with(java.util.Map<String, String> overrides) throws ConfigException {
    if (source == null || overrides == null || overrides.isEmpty()) {
      return this;
    }
    com.fasterxml.jackson.databind.node.ObjectNode copy = source.deepCopy();
    boolean touched = false;
    for (java.util.Map.Entry<String, String> entry : overrides.entrySet()) {
      io.hearth.settings.Setting setting = io.hearth.settings.Settings.byKey(entry.getKey());
      if (setting == null || io.hearth.settings.Settings.isMeta(entry.getKey())) {
        continue;
      }
      setting.applyTo(copy, entry.getValue());
      touched = true;
    }
    if (!touched) {
      return this;
    }
    return of(domain, configsRoot, configFile,
        new io.hearth.common.ConfigObject(copy, where), fallbackZone, source);
  }

  /** the domain whose database this one uses; itself unless it delegates */
  public String databaseDomain() {
    return useDatabaseDomain == null ? domain : useDatabaseDomain;
  }

  /** how specific this config is; more labels wins during resolution */
  public int specificity() {
    return Hosts.labelCount(domain);
  }

  /**
   * Is this part of the product here at all?
   *
   * The one question every handler and every navigation entry asks. It folds the broad switch and
   * the block's own flag together, so there is no way to check one and forget the other -- which is
   * how a "disabled" surface ends up still answering on a path somebody knows.
   */
  public boolean has(Surface surface) {
    if (disabled.contains(surface)) {
      return false;
    }
    return switch (surface) {
      case ai -> mcp.enabled;
      case attachments -> attachments.enabled;
      // the app shell has no block of its own; it is on unless the broad switch says otherwise
      case app -> true;
    };
  }

  @Override
  public String toString() {
    return domain + " (name=" + name + ", enabled=" + enabled + ", wildcard=" + wildcard + ")";
  }
}
