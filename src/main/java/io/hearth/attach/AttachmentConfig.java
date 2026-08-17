package io.hearth.attach;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What this community lets people upload, and what happens to it afterwards.
 *
 * <b>The extension list is the security boundary and it is explicit.</b> Not "block the dangerous
 * ones" -- an allow list, checked against a table this server knows how to serve, because the set
 * of things a browser will execute grows and the set of things a supper club needs does not. A
 * community that wants something else adds a line; a community that has thought about none of this
 * gets photographs, video, audio and the handful of documents people actually pass around.
 *
 * <b>Hotlinking is refused by default.</b> Without that, a community's server is a free image host
 * for whoever finds a url -- somebody else's forum, somebody else's advertisement -- paid for in
 * this community's bandwidth and appearing in this community's logs. The check is the referrer,
 * which is weak evidence and the only evidence a browser offers.
 */
public class AttachmentConfig {
  /** the ceiling on the ceiling: past this an upload is a file transfer, not a photograph */
  public static final int MAX_UPLOAD_BYTES = 256 * 1024 * 1024;

  public final boolean enabled;
  /** what may be uploaded, by extension; every one has to be something Kinds knows */
  public final List<String> extensions;
  /** the largest single upload */
  public final int maxBytes;
  /** how much of the recently-served bytes to keep in memory, so a spike is not a disk storm */
  public final int cacheBytes;
  /** how long a browser may keep one; the url carries an id, so the bytes never change */
  public final int browserCacheSeconds;
  /**
   * Refuse a request that came from somebody else's page.
   *
   * On by default. The exception is a request with no referrer at all, which is honoured: a browser
   * omits it on a direct navigation, on a bookmark, behind a privacy setting and under several
   * referrer policies, and refusing those would mean refusing members rather than hotlinkers.
   */
  public final boolean checkReferrer;
  /** other hosts allowed to embed these; a community's own domain never needs listing */
  public final List<String> allowedReferrers;

  public static AttachmentConfig defaults() {
    return new AttachmentConfig();
  }

  private AttachmentConfig() {
    this.enabled = true;
    this.extensions = Kinds.DEFAULT_EXTENSIONS;
    this.maxBytes = 25 * 1024 * 1024;
    this.cacheBytes = 64 * 1024 * 1024;
    this.browserCacheSeconds = 86400;
    this.checkReferrer = true;
    this.allowedReferrers = List.of();
  }

  public AttachmentConfig(ConfigObject config) throws ConfigException {
    this.enabled = config.boolOf("enabled", true);
    String[] declared = config.stringsOf("extensions", new String[0]);
    if (declared.length == 0) {
      this.extensions = Kinds.DEFAULT_EXTENSIONS;
    } else {
      LinkedHashSet<String> wanted = new LinkedHashSet<>();
      for (String raw : declared) {
        String clean = Kinds.clean(raw);
        if (!Kinds.isKnown(clean)) {
          throw new ConfigException("attachments.extensions lists '" + raw + "', which this server"
              + " does not know how to serve safely. It knows: " + String.join(", ", Kinds.all()));
        }
        wanted.add(clean);
      }
      this.extensions = List.copyOf(wanted);
    }
    this.maxBytes = config.intOf("max-bytes", 25 * 1024 * 1024);
    this.cacheBytes = config.intOf("cache-bytes", 64 * 1024 * 1024);
    this.browserCacheSeconds = config.intOf("browser-cache-seconds", 86400);
    this.checkReferrer = config.boolOf("check-referrer", true);
    ArrayList<String> hosts = new ArrayList<>();
    for (String host : config.stringsOf("allowed-referrers", new String[0])) {
      String clean = host == null ? "" : host.trim().toLowerCase(java.util.Locale.ROOT);
      if (!clean.isEmpty()) {
        hosts.add(clean);
      }
    }
    this.allowedReferrers = List.copyOf(hosts);
    config.assertKnownKeys();
    if (maxBytes < 1024 || maxBytes > MAX_UPLOAD_BYTES) {
      throw new ConfigException("attachments.max-bytes must be between 1024 and "
          + MAX_UPLOAD_BYTES);
    }
    if (cacheBytes < 0) {
      throw new ConfigException("attachments.cache-bytes must be zero or more; zero means no cache");
    }
    if (browserCacheSeconds < 0 || browserCacheSeconds > 31_536_000) {
      throw new ConfigException("attachments.browser-cache-seconds must be between 0 and a year");
    }
  }

  public boolean allows(String extension) {
    String clean = Kinds.clean(extension);
    return Kinds.isKnown(clean) && extensions.contains(clean);
  }

  /** every type a community accepts, for the picker's accept attribute and for the help text */
  public List<Kinds.Type> allowed() {
    ArrayList<Kinds.Type> types = new ArrayList<>();
    for (String extension : extensions) {
      Kinds.Type type = Kinds.of(extension);
      if (type != null) {
        types.add(type);
      }
    }
    return types;
  }

  /** the hosts allowed to embed, including the community's own */
  public Set<String> referrersFor(String domain) {
    LinkedHashSet<String> hosts = new LinkedHashSet<>();
    if (domain != null) {
      hosts.add(domain.toLowerCase(java.util.Locale.ROOT));
    }
    hosts.addAll(allowedReferrers);
    return hosts;
  }

  public String describe() {
    return enabled
        ? extensions.size() + " kind(s), up to " + (maxBytes / (1024 * 1024)) + "MB"
        : "off";
  }
}
