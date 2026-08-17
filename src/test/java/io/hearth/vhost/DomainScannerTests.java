package io.hearth.vhost;

import io.hearth.common.ConfigException;
import io.hearth.common.Verbose;
import io.hearth.testkit.Configs;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DomainScannerTests {
  private static DomainScanner.Result scan(Configs configs) throws ConfigException {
    return DomainScanner.scan(configs.file(), Verbose.OFF);
  }

  private static void expectRefusal(Configs configs, String expected) {
    try {
      scan(configs);
      fail("expected a refusal mentioning: " + expected);
    } catch (ConfigException ex) {
      assertTrue("wanted '" + expected + "' in: " + ex.getMessage(), ex.getMessage().contains(expected));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void filenamesBecomeDomains() throws Exception {
    Configs configs = Configs.dir()
        .domain("localhost", "{}")
        .domain("example.org", "{}")
        .domain("junior.example.org", "{}");
    try {
      DomainTree tree = scan(configs).tree;
      assertEquals(3, tree.size());
      assertNotNull(tree.exact("localhost"));
      assertNotNull(tree.exact("example.org"));
      assertNotNull(tree.exact("junior.example.org"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void defaultsApply() throws Exception {
    Configs configs = Configs.dir().domain("example.org", "{}");
    try {
      DomainConfig config = scan(configs).tree.exact("example.org");
      assertEquals("example.org", config.name);
      assertTrue(config.enabled);
      assertTrue(config.wildcard);
      // nothing about content on disk: pages, templates and images all live in the database
    } finally {
      configs.delete();
    }
  }

  @Test
  public void valuesOverrideDefaults() throws Exception {
    Configs configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"enabled\":false,\"wildcard\":false}");
    try {
      DomainConfig config = scan(configs).tree.exact("example.org");
      assertEquals("Example Community", config.name);
      assertFalse(config.enabled);
      assertFalse(config.wildcard);
    } finally {
      configs.delete();
    }
  }

  @Test
  public void nonConfigFilesAreIgnored() throws Exception {
    Configs configs = Configs.dir()
        .domain("example.org", "{}")
        .file("README.md", "# notes")
        .file(".gitignore", "x")
        .file("example.org.cfg.bak", "{}")
        .file("notes.txt", "hello");
    try {
      assertEquals(1, scan(configs).tree.size());
    } finally {
      configs.delete();
    }
  }

  @Test
  public void directoriesAreIgnored() throws Exception {
    Configs configs = Configs.dir()
        .domain("example.org", "{}")
        .directory("example.org/css")
        .directory("content");
    try {
      DomainTree tree = scan(configs).tree;
      assertEquals(1, tree.size());
      assertNull(tree.exact("content"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void aDisabledDomainWarnsButLoads() throws Exception {
    Configs configs = Configs.dir().domain("example.org", "{\"enabled\":false}");
    try {
      DomainScanner.Result result = scan(configs);
      assertEquals(1, result.tree.size());
      assertFalse(result.warnings.isEmpty());
      assertTrue(result.warnings.get(0).contains("enabled=false"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void anEmptyDirectoryIsAnEmptyTree() throws Exception {
    Configs configs = Configs.dir();
    try {
      DomainScanner.Result result = scan(configs);
      assertTrue(result.tree.isEmpty());
      assertTrue(result.warnings.isEmpty());
    } finally {
      configs.delete();
    }
  }

  // ---- the refusals ------------------------------------------------------------------------

  @Test
  public void uppercaseFilenameIsFatal() throws Exception {
    // rejected rather than folded, so Example Community.com.cfg can't become a second spelling
    expectRefusal(Configs.dir().domain("Example Community.com", "{}"), "not named after a valid domain");
  }

  @Test
  public void underscoreInFilenameIsFatal() throws Exception {
    expectRefusal(Configs.dir().domain("exam_ple.com", "{}"), "not named after a valid domain");
  }

  @Test
  public void emptyLabelInFilenameIsFatal() throws Exception {
    expectRefusal(Configs.dir().domain("example..com", "{}"), "not named after a valid domain");
  }

  @Test
  public void leadingDotFilenameIsFatal() throws Exception {
    expectRefusal(Configs.dir().domain(".example.org", "{}"), "not named after a valid domain");
  }

  @Test
  public void aBareSuffixIsFatal() throws Exception {
    expectRefusal(Configs.dir().file(DomainScanner.CONFIG_SUFFIX, "{}"), "named after its domain");
  }

  @Test
  public void unknownKeyIsFatal() throws Exception {
    expectRefusal(Configs.dir().domain("example.org", "{\"nmae\":\"typo\"}"), "unknown key");
  }

  @Test
  public void wrongTypeIsFatal() throws Exception {
    expectRefusal(Configs.dir().domain("example.org", "{\"enabled\":\"yes\"}"), "must be true or false");
  }

  @Test
  public void malformedJsonIsFatal() throws Exception {
    expectRefusal(Configs.dir().domain("example.org", "{oops"), "not readable JSON");
  }

  @Test
  public void nonObjectJsonIsFatal() throws Exception {
    expectRefusal(Configs.dir().domain("example.org", "[1,2,3]"), "must contain a JSON object");
  }

  @Test
  public void emptyFileIsFatal() throws Exception {
    // Jackson yields a missing node rather than an error for empty input, so it lands on the
    // "not an object" check instead of the parse failure -- either way it refuses to boot
    expectRefusal(Configs.dir().domain("example.org", ""), "must contain a JSON object");
  }

  @Test
  public void whitespaceOnlyFileIsFatal() throws Exception {
    expectRefusal(Configs.dir().domain("example.org", "   \n  "), "must contain a JSON object");
  }

  @Test
  public void anUnknownKeyIsStillFatalAfterAKeyIsRemoved() throws Exception {
    // static-root used to be a key; a config that still names it must fail loudly rather than
    // silently ignoring a setting the operator believes is in effect
    expectRefusal(Configs.dir().domain("example.org", "{\"static-root\":\"somewhere\"}"), "unknown key");
  }

  @Test
  public void symlinkedConfigIsFatal() throws Exception {
    Configs configs = Configs.dir().domain("example.org", "{}");
    Path link = configs.path().resolve("copy.com" + DomainScanner.CONFIG_SUFFIX);
    try {
      Files.createSymbolicLink(link, configs.path().resolve("example.org" + DomainScanner.CONFIG_SUFFIX));
    } catch (UnsupportedOperationException | java.io.IOException ex) {
      configs.delete();
      return; // platform without symlink support; nothing to assert
    }
    expectRefusal(configs, "symlink");
  }

  @Test
  public void missingDirectoryIsFatal() {
    try {
      DomainScanner.scan(new File("/definitely/not/here/hearth"), Verbose.OFF);
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage().contains("does not exist"));
    }
  }

  @Test
  public void aFileInsteadOfADirectoryIsFatal() throws Exception {
    Configs configs = Configs.dir().file("notes.txt", "x");
    try {
      DomainScanner.scan(new File(configs.file(), "notes.txt"), Verbose.OFF);
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage().contains("is not a directory"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void nullDirectoryIsFatal() {
    try {
      DomainScanner.scan(null, Verbose.OFF);
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage().contains("--configs is required"));
    }
  }

  // ---- the checked-in directory ------------------------------------------------------------

  @Test
  public void theCheckedInConfigsDirectoryLoads() throws Exception {
    File configs = new File("configs");
    if (!configs.isDirectory()) {
      return; // surefire runs from the project basedir, but don't fail elsewhere
    }
    DomainTree tree = DomainScanner.scan(configs, Verbose.OFF).tree;
    assertNotNull(tree.exact("localhost"));
    assertNotNull(tree.exact("example.org"));
    assertNotNull(tree.exact("junior.example.org"));
    assertEquals("Example Community Junior", tree.resolve("junior.example.org").name);
    assertEquals("Example Community", tree.resolve("www.example.org").name);
    assertNull(tree.resolve("api.localhost"));
  }
}
