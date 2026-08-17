package io.hearth.certs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.common.ConfigException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * The `--certs` directory: everything about this server's certificates, on disk, in one place.
 *
 * A cache in the sense that it can be deleted and rebuilt -- but not casually, because it also holds
 * the ACME account key, and losing that means registering a new account and starting the rate limit
 * clock over. So it is a cache the way a database is a cache of the schema: rebuildable in
 * principle, worth backing up in practice.
 *
 * The layout is deliberately boring and greppable, because the thing an operator does at 2am is
 * `ls` it:
 *
 * <pre>
 *   account.key                 the ACME account key (PEM); this identifies you to the CA
 *   account.json                the account URL, contact and directory it was registered against
 *   example.org.key             that domain's private key (PEM)
 *   example.org.crt             its certificate chain (PEM), as the CA returned it
 *   example.org.json            key (PKCS#8) + chain, bundled for a TLS listener to load
 * </pre>
 *
 * Keys are written 0600. On a filesystem that cannot express that, the write still happens and the
 * boot report says so rather than failing -- refusing to start over file permissions on somebody's
 * container would be the wrong trade.
 */
public class CertStore {
  private static final ObjectMapper JSON = new ObjectMapper();
  /** renew this far before expiry; Let's Encrypt issues for 90 days, so this leaves a wide margin */
  public static final long RENEW_BEFORE_MILLIS = 20L * 86_400_000L;

  private final File root;

  public CertStore(File root) {
    this.root = root;
  }

  public File root() {
    return root;
  }

  /**
   * Open the directory, creating it if it is not there.
   *
   * Refuses rather than guesses when the path is a file, or when it cannot be created: a cert cache
   * that silently is not one means certificates get re-ordered on every boot, which is how somebody
   * meets a rate limit.
   */
  public static CertStore open(File root) throws ConfigException {
    if (root == null) {
      throw new ConfigException("--certs is required to manage certificates");
    }
    if (root.exists() && !root.isDirectory()) {
      throw new ConfigException("--certs " + root + " is not a directory");
    }
    if (!root.exists() && !root.mkdirs()) {
      throw new ConfigException("--certs " + root + " could not be created");
    }
    return new CertStore(root);
  }

  // ---- the account ------------------------------------------------------------------------------

  public File accountKeyFile() {
    return new File(root, "account.key");
  }

  public File accountFile() {
    return new File(root, "account.json");
  }

  public boolean hasAccount() {
    return accountKeyFile().isFile() && accountFile().isFile();
  }

  /** what was agreed with the CA, and where the account lives */
  public record AccountRecord(String url, String contact, String directory, boolean staging) {
    public String describe() {
      return (staging ? "staging" : "production") + " account for " + contact;
    }
  }

  public AccountRecord readAccount() throws IOException {
    JsonNode node = JSON.readTree(accountFile());
    return new AccountRecord(
        node.path("url").asText(null),
        node.path("contact").asText(""),
        node.path("directory").asText(Acme.PRODUCTION),
        node.path("staging").asBoolean(false));
  }

  public void writeAccount(AccountRecord account) throws IOException {
    ObjectNode node = JSON.createObjectNode();
    node.put("url", account.url());
    node.put("contact", account.contact());
    node.put("directory", account.directory());
    node.put("staging", account.staging());
    node.put("registered_at", System.currentTimeMillis());
    Files.writeString(accountFile().toPath(), node.toPrettyString() + "\n");
  }

  public String readAccountKey() throws IOException {
    return Files.readString(accountKeyFile().toPath());
  }

  public void writeAccountKey(String pem) throws IOException {
    writePrivate(accountKeyFile(), pem);
  }

  // ---- per domain -------------------------------------------------------------------------------

  public File keyFile(String domain) {
    return new File(root, safe(domain) + ".key");
  }

  public File chainFile(String domain) {
    return new File(root, safe(domain) + ".crt");
  }

  public File bundleFile(String domain) {
    return new File(root, safe(domain) + ".json");
  }

  public boolean has(String domain) {
    return chainFile(domain).isFile() && keyFile(domain).isFile();
  }

  public String readKey(String domain) throws IOException {
    return Files.readString(keyFile(domain).toPath());
  }

  public void writeKey(String domain, String pem) throws IOException {
    writePrivate(keyFile(domain), pem);
  }

  /**
   * Store a freshly issued certificate.
   *
   * The bundle is written last and is what a TLS listener will read, so a crash between the two
   * writes leaves a chain on disk with no bundle -- which reads as "not issued yet" and simply
   * re-orders, rather than as a half-loaded certificate.
   */
  public void writeCertificate(String domain, String chainPem, String pkcs8Key) throws IOException {
    Files.writeString(chainFile(domain).toPath(), chainPem);
    ObjectNode bundle = JSON.createObjectNode();
    bundle.put("domain", domain);
    bundle.put("key", pkcs8Key);
    bundle.put("cert", chainPem);
    bundle.put("issued_at", System.currentTimeMillis());
    X509Certificate certificate = parse(chainPem);
    if (certificate != null) {
      bundle.put("not_after", certificate.getNotAfter().getTime());
    }
    Files.writeString(bundleFile(domain).toPath(), bundle.toString());
  }

  /** what is on disk for a domain, or null when there is nothing usable */
  public Held held(String domain) {
    if (!chainFile(domain).isFile()) {
      return null;
    }
    try {
      String pem = Files.readString(chainFile(domain).toPath());
      X509Certificate certificate = parse(pem);
      if (certificate == null) {
        return null;
      }
      return new Held(domain, certificate.getNotAfter(), chainFile(domain), keyFile(domain));
    } catch (IOException ex) {
      return null;
    }
  }

  /** a certificate this server is holding for a domain */
  public record Held(String domain, Date notAfter, File chain, File key) {
    public boolean needsRenewal(long now) {
      return notAfter.getTime() - RENEW_BEFORE_MILLIS <= now;
    }

    public boolean isExpired(long now) {
      return notAfter.getTime() <= now;
    }

    public long daysLeft(long now) {
      return Math.max(0, (notAfter.getTime() - now) / 86_400_000L);
    }
  }

  /** everything held, for the boot report and the admin page */
  public List<Held> all() {
    TreeMap<String, Held> found = new TreeMap<>();
    File[] files = root.listFiles();
    if (files == null) {
      return List.of();
    }
    for (File file : files) {
      String name = file.getName();
      if (!name.endsWith(".crt")) {
        continue;
      }
      String domain = name.substring(0, name.length() - 4);
      Held held = held(domain);
      if (held != null) {
        found.put(domain, held);
      }
    }
    return new ArrayList<>(found.values());
  }

  private static X509Certificate parse(String pem) {
    try {
      return (X509Certificate) CertificateFactory.getInstance("X509")
          .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      return null;
    }
  }

  /**
   * Write a private key with the narrowest permissions the filesystem will accept.
   *
   * Created empty and locked down *before* the bytes go in, so there is no window where the key
   * exists and is world readable.
   */
  private static void writePrivate(File file, String contents) throws IOException {
    if (!file.exists()) {
      Files.createFile(file.toPath());
    }
    try {
      Files.setPosixFilePermissions(file.toPath(),
          Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException | IOException ex) {
      // a filesystem that cannot express this is not a reason to refuse to run; the boot report
      // mentions it so nobody is surprised later
    }
    Files.writeString(file.toPath(), contents);
  }

  /** a domain is already a safe filename, but never build a path out of input without checking */
  static String safe(String domain) {
    if (domain == null || domain.isEmpty() || domain.contains("/") || domain.contains("\\")
        || domain.contains("..") || domain.startsWith(".")) {
      throw new IllegalArgumentException("refusing to build a cert path from '" + domain + "'");
    }
    return domain;
  }
}
