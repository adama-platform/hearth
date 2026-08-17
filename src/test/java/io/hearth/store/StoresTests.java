package io.hearth.store;

import io.hearth.auth.AuthSystem;
import io.hearth.common.ConfigException;
import io.hearth.common.Verbose;
import io.hearth.testkit.Configs;
import io.hearth.vhost.DomainScanner;
import io.hearth.vhost.DomainTree;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** One database per domain, unless a domain says it shares another's. */
public class StoresTests {
  private File storesRoot;
  private Configs configs;

  @Before
  public void setUp() throws Exception {
    storesRoot = Files.createTempDirectory("hearth-stores-test").toFile();
  }

  @After
  public void tearDown() {
    if (configs != null) {
      configs.delete();
    }
    deleteTree(storesRoot);
  }

  private static void deleteTree(File root) {
    File[] children = root.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteTree(child);
      }
    }
    root.delete();
  }

  private DomainTree treeOf(Configs configs) throws ConfigException {
    return DomainScanner.scan(configs.file(), Verbose.OFF).tree;
  }

  @Test
  public void eachDomainGetsItsOwnDatabaseByDefault() throws Exception {
    configs = Configs.dir().domain("a.test", "{}").domain("b.test", "{}");
    try (Stores stores = Stores.open(storesRoot, treeOf(configs), Verbose.OFF)) {
      assertEquals(2, stores.databaseCount());
      assertEquals(2, stores.domainCount());
      assertEquals("a.test", stores.forDomain("a.test").databaseDomain);
      assertEquals("b.test", stores.forDomain("b.test").databaseDomain);
    }
    assertTrue(new File(storesRoot, "a.test.mv.db").isFile());
    assertTrue(new File(storesRoot, "b.test.mv.db").isFile());
  }

  @Test
  public void useDatabaseDomainSharesOneDatabase() throws Exception {
    configs = Configs.dir()
        .domain("example.org", "{}")
        .domain("junior.example.org", "{\"use_database_domain\":\"example.org\"}");
    try (Stores stores = Stores.open(storesRoot, treeOf(configs), Verbose.OFF)) {
      assertEquals("one file for two domains", 1, stores.databaseCount());
      assertEquals(2, stores.domainCount());
      assertSame(stores.forDomain("example.org"), stores.forDomain("junior.example.org"));
      assertEquals("example.org", stores.forDomain("junior.example.org").databaseDomain);
    }
    assertTrue(new File(storesRoot, "example.org.mv.db").isFile());
    assertTrue("the sharing domain must not get a file of its own",
        !new File(storesRoot, "junior.example.org.mv.db").exists());
  }

  @Test
  public void sharingADatabaseMeansSharingAccountsAndSessions() throws Exception {
    configs = Configs.dir()
        .domain("example.org", "{}")
        .domain("junior.example.org", "{\"use_database_domain\":\"example.org\"}");
    DomainTree tree = treeOf(configs);
    try (Stores stores = Stores.open(storesRoot, tree, Verbose.OFF);
         AuthSystem auth = AuthSystem.of(stores, tree, Verbose.OFF)) {
      assertEquals(1, auth.size());
      assertSame("one account space, not two that happen to match",
          auth.forDomain("example.org"), auth.forDomain("junior.example.org"));
      // and the owning domain's policy is the one that governs both
      assertEquals("example.org", auth.forDomain("junior.example.org").databaseDomain);
    }
  }

  @Test
  public void theOwnersPolicyGovernsASharedDatabase() throws Exception {
    configs = Configs.dir()
        .domain("owner.test", "{\"login_security\":{\"mode\":\"password\",\"max-active-sessions\":4}}")
        .domain("guest.test", "{\"use_database_domain\":\"owner.test\","
            + "\"login_security\":{\"mode\":\"passwordless\",\"max-active-sessions\":99}}")
        .file("owner.test.cfg.note", "");
    DomainTree tree = treeOf(configs);
    try (Stores stores = Stores.open(storesRoot, tree, Verbose.OFF);
         AuthSystem auth = AuthSystem.of(stores, tree, Verbose.OFF)) {
      // one database means one answer to "how long is a session"; the owner's answer wins
      assertEquals(4, auth.forDomain("guest.test").security.maxActiveSessions);
      assertTrue(auth.forDomain("guest.test").security.usesPasswords());
    }
  }

  @Test
  public void reopeningFindsTheSameDatabases() throws Exception {
    configs = Configs.dir().domain("a.test", "{}");
    try (Stores stores = Stores.open(storesRoot, treeOf(configs), Verbose.OFF)) {
      assertTrue(stores.audits().get(0).changed());
    }
    try (Stores stores = Stores.open(storesRoot, treeOf(configs), Verbose.OFF)) {
      Store.Audit audit = stores.audits().get(0);
      assertTrue("the second boot should find the file", audit.existed());
      assertTrue("and have nothing to do", !audit.changed());
    }
  }

  // ---- the refusals ------------------------------------------------------------------------

  @Test
  public void pointingAtADomainThatIsNotServedIsFatal() throws Exception {
    configs = Configs.dir().domain("a.test", "{\"use_database_domain\":\"nowhere.test\"}");
    try {
      Stores.open(storesRoot, treeOf(configs), Verbose.OFF);
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("has no config of its own"));
    }
  }

  @Test
  public void pointingAtYourselfIsFatal() throws Exception {
    configs = Configs.dir().domain("a.test", "{\"use_database_domain\":\"a.test\"}");
    try {
      Stores.open(storesRoot, treeOf(configs), Verbose.OFF);
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("points at itself"));
    }
  }

  @Test
  public void aChainOfDelegationIsFatal() throws Exception {
    // a -> b -> c would make "which database am I on" a traversal, and a cycle would make it
    // unanswerable; one level keeps the question a lookup
    configs = Configs.dir()
        .domain("c.test", "{}")
        .domain("b.test", "{\"use_database_domain\":\"c.test\"}")
        .domain("a.test", "{\"use_database_domain\":\"b.test\"}");
    try {
      Stores.open(storesRoot, treeOf(configs), Verbose.OFF);
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("delegation is one level only"));
    }
  }

  @Test
  public void aMalformedDatabaseDomainIsFatalAtScanTime() throws Exception {
    configs = Configs.dir().domain("a.test", "{\"use_database_domain\":\"NOT A DOMAIN\"}");
    try {
      treeOf(configs);
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("is not a valid domain"));
    }
  }

  @Test
  public void aMissingStoresDirectoryIsCreated() throws Exception {
    configs = Configs.dir().domain("a.test", "{}");
    File fresh = new File(storesRoot, "not/there/yet");
    try (Stores stores = Stores.open(fresh, treeOf(configs), Verbose.OFF)) {
      assertEquals(1, stores.databaseCount());
    }
    assertTrue(fresh.isDirectory());
  }

  @Test
  public void aStoresPathThatIsAFileIsFatal() throws Exception {
    configs = Configs.dir().domain("a.test", "{}");
    File notADirectory = new File(storesRoot, "notes.txt");
    Files.writeString(notADirectory.toPath(), "hello");
    try {
      Stores.open(notADirectory, treeOf(configs), Verbose.OFF);
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("not a directory"));
    }
  }

  @Test
  public void aNullStoresPathIsFatal() throws Exception {
    configs = Configs.dir().domain("a.test", "{}");
    try {
      Stores.open(null, treeOf(configs), Verbose.OFF);
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("--stores is required"));
    }
  }

  @Test
  public void noDomainsMeansNoDatabases() throws Exception {
    configs = Configs.dir();
    try (Stores stores = Stores.open(storesRoot, treeOf(configs), Verbose.OFF)) {
      assertEquals(0, stores.databaseCount());
      assertNull(stores.forDomain("anything.test"));
    }
  }

  @Test
  public void theSharingReportNamesEveryUser() throws Exception {
    configs = Configs.dir()
        .domain("owner.test", "{}")
        .domain("one.test", "{\"use_database_domain\":\"owner.test\"}")
        .domain("two.test", "{\"use_database_domain\":\"owner.test\"}");
    try (Stores stores = Stores.open(storesRoot, treeOf(configs), Verbose.OFF)) {
      assertEquals("[one.test, owner.test, two.test]", stores.sharing().get("owner.test").toString());
      assertNotNull(stores.forDomain("two.test"));
    }
  }
}
