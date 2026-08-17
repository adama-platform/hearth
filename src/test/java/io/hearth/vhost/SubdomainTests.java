package io.hearth.vhost;

import io.hearth.certs.CertSetup;
import io.hearth.common.ConfigException;
import io.hearth.common.Verbose;
import io.hearth.smtp.SmtpRouting;
import io.hearth.testkit.Configs;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Named subdomains: the middle option between "this exact hostname" and "everything under it".
 *
 * A wildcard is the wrong tool for `www` and `blog`, because HTTP-01 cannot get a certificate for
 * one -- so a wildcard domain serves subdomains over plain HTTP forever, and the operator finds out
 * from a browser warning. Writing the list down means the certificate order knows what to ask for,
 * and mail is allowed to accept for them without any of that becoming a relay.
 *
 * The whole point is that a listed subdomain is the *same community*: one config, one database, one
 * set of accounts. These tests are as much about what a subdomain must not become as what it is.
 */
public class SubdomainTests {
  private static DomainTree scan(Configs configs) throws ConfigException {
    return DomainScanner.scan(configs.file(), Verbose.OFF).tree;
  }

  @Test
  public void aListedSubdomainResolvesToTheSameConfig() throws Exception {
    Configs configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"wildcard\":false,\"subdomains\":[\"www\",\"blog\"]}");
    try {
      DomainTree tree = scan(configs);
      DomainConfig home = tree.resolve("example.org");
      assertNotNull(home);
      assertEquals("the same community, not a second one",
          home, tree.resolve("www.example.org"));
      assertEquals(home, tree.resolve("blog.example.org"));
      assertNull("with wildcard off, nothing that was not written down",
          tree.resolve("shop.example.org"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void aSubdomainIsNotASecondCommunity() throws Exception {
    // everything that walks all() -- the databases, the mailers, the notifier -- must see one entry
    // per community. An alias in there would give www.example.org a database and accounts of its own.
    Configs configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"subdomains\":[\"www\"]}");
    try {
      DomainTree tree = scan(configs);
      assertEquals(1, tree.size());
      assertFalse(tree.all().containsKey("www.example.org"));
      assertNull("exact() is about config files", tree.exact("www.example.org"));
      assertEquals(List.of("www.example.org"), tree.aliasesOf("example.org"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void aHostWithItsOwnConfigWinsOverSomebodyElseNamingIt() throws Exception {
    // this is the collision the scanner refuses; the tree resolves it the safe way regardless, so
    // that a future caller building a tree by hand cannot hand a config somebody else's traffic
    DomainTree.Builder builder = DomainTree.builder();
    builder.insert(config("example.org", "{\"subdomains\":[\"junior\"]}"));
    builder.insert(config("junior.example.org", "{}"));
    DomainTree tree = builder.build();
    assertEquals("its own file is the more specific statement",
        "junior.example.org", tree.resolve("junior.example.org").domain);
  }

  @Test
  public void twoConfigsFightingOverOneNameRefusesToStart() throws Exception {
    Configs configs = Configs.dir()
        .domain("example.org", "{\"name\":\"Example\",\"subdomains\":[\"junior\"]}")
        .domain("junior.example.org", "{\"name\":\"Junior\"}");
    try {
      scan(configs);
      fail("a name claimed twice is an operator mistake, not something to pick a winner for");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("subdomain conflict"));
      assertTrue(ex.getMessage(), ex.getMessage().contains("junior.example.org"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void awholeHostnameIsNotALabel() {
    // "www.example.org" in the list would silently become "www.example.org.example.org"
    try {
      config("example.org", "{\"subdomains\":[\"www.example.org\"]}");
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("subdomains"));
    }
  }

  // ---- what the certificate order asks for -----------------------------------------------------

  @Test
  public void listedSubdomainsAreOrderedToo() throws Exception {
    Configs configs = Configs.dir()
        .domain("example.org", "{\"name\":\"Example\",\"subdomains\":[\"www\"]}")
        .domain("localhost", "{\"name\":\"Local\",\"subdomains\":[\"dev\"]}");
    try {
      List<String> managed = CertSetup.managedDomains(scan(configs));
      assertTrue(managed.contains("example.org"));
      assertTrue("a subdomain nobody asks for is a subdomain served over plain HTTP",
          managed.contains("www.example.org"));
      assertFalse("no authority issues under localhost", managed.contains("dev.localhost"));
    } finally {
      configs.delete();
    }
  }

  // ---- and what mail may do with them ----------------------------------------------------------

  @Test
  public void mailIsAcceptedForAListedSubdomain() throws Exception {
    Configs configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"subdomains\":[\"mail\"]}");
    try {
      SmtpRouting routing = new SmtpRouting(scan(configs));
      assertTrue(routing.accepts("hello@example.org"));
      assertTrue("written down is neither a wildcard nor an accident",
          routing.accepts("hello@mail.example.org"));
      assertFalse(routing.accepts("hello@other.example.org"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void aWildcardStillNeverAcceptsMail() throws Exception {
    Configs configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"wildcard\":true}");
    try {
      SmtpRouting routing = new SmtpRouting(scan(configs));
      assertTrue(routing.accepts("hello@example.org"));
      assertFalse("a wildcard is a decision about serving web pages",
          routing.accepts("hello@anything.example.org"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void aDomainCanSayItTakesNoMailAtAll() throws Exception {
    Configs configs = Configs.dir()
        .domain("quiet.example.org", "{\"name\":\"Quiet\",\"accepts-mail\":false}")
        .domain("loud.example.org", "{\"name\":\"Loud\"}");
    try {
      SmtpRouting routing = new SmtpRouting(scan(configs));
      assertTrue("accepting is the default", routing.accepts("hello@loud.example.org"));
      assertFalse(routing.accepts("hello@quiet.example.org"));
    } finally {
      configs.delete();
    }
  }

  private static DomainConfig config(String domain, String json) throws ConfigException {
    try {
      com.fasterxml.jackson.databind.node.ObjectNode node =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
      java.io.File root = new java.io.File("configs-test");
      return DomainConfig.of(domain, root, new java.io.File(root, domain + ".cfg"),
          new io.hearth.common.ConfigObject(node, domain));
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new ConfigException("bad test json");
    }
  }
}
