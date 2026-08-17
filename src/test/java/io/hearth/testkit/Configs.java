package io.hearth.testkit;

import io.hearth.vhost.DomainScanner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Builds throwaway configs directories for tests.
 *
 * Tests that want a domain served write the flat file an operator would write, rather than
 * constructing a DomainConfig by hand. That way the scan is under test too.
 */
public class Configs {
  private final Path root;
  /** where the .cfg files are; the root itself until asRoot() moves them into domains/ */
  private final Path domains;

  private Configs(Path root) {
    this(root, root);
  }

  private Configs(Path root, Path domains) {
    this.root = root;
    this.domains = domains;
  }

  public static Configs dir() throws IOException {
    Path root = Files.createTempDirectory("hearth-test-configs");
    root.toFile().deleteOnExit();
    return new Configs(root);
  }

  /** writes <domain>.cfg with the given JSON */
  public Configs domain(String domain, String json) throws IOException {
    return file(domain + DomainScanner.CONFIG_SUFFIX, json);
  }

  /** writes a file verbatim, for testing names the scanner should reject or ignore */
  public Configs file(String name, String contents) throws IOException {
    Files.write(root.resolve(name), contents.getBytes(StandardCharsets.UTF_8));
    return this;
  }

  /** a directory beside the configs, which the scanner must ignore */
  public Configs directory(String path) throws IOException {
    Files.createDirectories(root.resolve(path));
    return this;
  }

  /** where the .cfg files live: the root, or root/domains once {@link #asRoot()} has been called */
  public File file() {
    return domains.toFile();
  }

  public Path path() {
    return domains;
  }

  /**
   * The same tree, arranged the way a real installation is: a root with `domains/` inside it.
   *
   * `file()` still points at the domains directory, so anything that scans domains is unchanged;
   * `rootDir()` is what `--root` wants. Two views of one temp directory, because a test about the
   * command line and a test about the scanner want different halves of the same thing.
   */
  public Configs asRoot() throws IOException {
    Path domains = root.resolve("domains");
    Files.createDirectories(domains);
    try (var listing = Files.list(root)) {
      for (Path entry : listing.toList()) {
        if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".cfg")) {
          Files.move(entry, domains.resolve(entry.getFileName()));
        }
      }
    }
    return new Configs(root, domains);
  }

  /** the directory to hand to --root */
  public File rootDir() {
    return root.toFile();
  }

  public static Configs standard() throws IOException {
    return dir()
        .domain("localhost", "{\"name\":\"Local\",\"wildcard\":false}")
        .domain("example.com", "{\"name\":\"Example\",\"wildcard\":true}")
        .domain("blog.example.com", "{\"name\":\"Example Blog\"}")
        .domain("off.org", "{\"name\":\"Turned Off\",\"enabled\":false}");
  }

  public void delete() {
    try (Stream<Path> walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException ex) {
      // temp directory cleanup; nothing a test should fail over
    }
  }
}
