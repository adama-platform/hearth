package io.hearth.vhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class DomainTreeTests {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final File ROOT = new File("configs-test");

  private static DomainConfig config(String domain, boolean wildcard) throws ConfigException {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("wildcard", wildcard);
    return DomainConfig.of(domain, ROOT, new File(ROOT, domain + ".cfg"), new ConfigObject(node, domain));
  }

  private static DomainTree tree(DomainConfig... configs) {
    DomainTree.Builder builder = DomainTree.builder();
    for (DomainConfig config : configs) {
      builder.insert(config);
    }
    return builder.build();
  }

  @Test
  public void labelsSplitOutsideIn() {
    assertArrayEquals(new String[]{"junior", "example", "org"}, DomainTree.split("junior.example.org"));
    assertArrayEquals(new String[]{"localhost"}, DomainTree.split("localhost"));
  }

  @Test
  public void deepestConfigWins() throws Exception {
    DomainTree tree = tree(config("org", true), config("example.org", true), config("junior.example.org", true));
    assertEquals("junior.example.org", tree.resolve("junior.example.org").domain);
    assertEquals("example.org", tree.resolve("example.org").domain);
    assertEquals("example.org", tree.resolve("other.example.org").domain);
    assertEquals("org", tree.resolve("somethingelse.org").domain);
  }

  @Test
  public void wildcardCoversSubdomains() throws Exception {
    DomainTree tree = tree(config("example.org", true));
    assertEquals("example.org", tree.resolve("example.org").domain);
    assertEquals("example.org", tree.resolve("www.example.org").domain);
    assertEquals("example.org", tree.resolve("a.b.c.example.org").domain);
  }

  @Test
  public void nonWildcardIsExactOnly() throws Exception {
    DomainTree tree = tree(config("example.org", false));
    assertEquals("example.org", tree.resolve("example.org").domain);
    assertNull(tree.resolve("www.example.org"));
  }

  @Test
  public void aSpecificConfigBeatsANonWildcardParent() throws Exception {
    DomainTree tree = tree(config("example.org", false), config("junior.example.org", true));
    assertEquals("junior.example.org", tree.resolve("junior.example.org").domain);
    assertNull(tree.resolve("senior.example.org"));
  }

  @Test
  public void aNonWildcardNodeDoesNotStopTheDescent() throws Exception {
    // org is a wildcard, example.org is not; www.example.org should land back on org
    DomainTree tree = tree(config("org", true), config("example.org", false));
    assertEquals("org", tree.resolve("www.example.org").domain);
    assertEquals("example.org", tree.resolve("example.org").domain);
  }

  @Test
  public void junctionNodesServeNothing() throws Exception {
    // "org" exists in the tree only because example.org hangs off it
    DomainTree tree = tree(config("example.org", true));
    assertNull(tree.resolve("org"));
    assertNull(tree.resolve("other.org"));
  }

  @Test
  public void unrelatedDomainsResolveToNothing() throws Exception {
    DomainTree tree = tree(config("example.org", true));
    assertNull(tree.resolve("example.net"));
    assertNull(tree.resolve("localhost"));
    assertNull(tree.resolve(null));
    assertNull(tree.resolve(""));
  }

  @Test
  public void anEmptyTreeResolvesToNothing() {
    assertNull(DomainTree.EMPTY.resolve("example.org"));
    assertTrue(DomainTree.EMPTY.isEmpty());
    assertEquals(0, DomainTree.EMPTY.size());
  }

  @Test
  public void exactIgnoresWildcards() throws Exception {
    DomainTree tree = tree(config("example.org", true));
    assertEquals("example.org", tree.exact("example.org").domain);
    assertNull(tree.exact("www.example.org"));
    assertNull(tree.exact("org"));
    assertNull(tree.exact(null));
  }

  @Test
  public void siblingBranchesDoNotBleed() throws Exception {
    DomainTree tree = tree(config("a.example.com", true), config("b.example.com", true));
    assertEquals("a.example.com", tree.resolve("a.example.com").domain);
    assertEquals("b.example.com", tree.resolve("b.example.com").domain);
    assertNull(tree.resolve("c.example.com"));
    assertNull(tree.resolve("example.com"));
  }

  @Test
  public void aSingleLabelDomainWorks() throws Exception {
    DomainTree tree = tree(config("localhost", true));
    assertEquals("localhost", tree.resolve("localhost").domain);
    assertEquals("localhost", tree.resolve("api.localhost").domain);
    assertNull(tree.resolve("localhost.com"));
  }

  @Test
  public void insertReportsACollision() throws Exception {
    DomainTree.Builder builder = DomainTree.builder();
    DomainConfig first = config("example.org", true);
    assertNull(builder.insert(first));
    assertSame(first, builder.insert(config("example.org", false)));
    assertEquals(1, builder.size());
  }

  @Test
  public void allListsEveryDomainSorted() throws Exception {
    DomainTree tree = tree(config("example.org", true), config("junior.example.org", true), config("localhost", true));
    assertEquals("[example.org, junior.example.org, localhost]", tree.all().keySet().toString());
    assertEquals(3, tree.size());
  }

  @Test
  public void explainNarratesTheDescent() throws Exception {
    DomainTree tree = tree(config("example.org", true));
    List<String> lines = tree.explain("a.b.example.org");
    assertEquals("[descend org -> junction, no config here, "
        + "descend example.org -> config (wildcard) example.org.cfg, "
        + "descend b.example.org -> no such branch; stopping, "
        + "most specific match: example.org]", lines.toString());
  }

  @Test
  public void explainReportsAnExactHit() throws Exception {
    List<String> lines = tree(config("example.org", true)).explain("example.org");
    assertTrue(lines.toString(), lines.get(1).contains("config (exact) example.org.cfg"));
    assertEquals("most specific match: example.org", lines.get(lines.size() - 1));
  }

  @Test
  public void explainReportsAWildcardRefusal() throws Exception {
    List<String> lines = tree(config("example.org", false)).explain("www.example.org");
    assertTrue(lines.toString(), lines.get(1).contains("wildcard=false"));
    assertEquals("unresolved", lines.get(lines.size() - 1));
  }

  @Test
  public void explainHandlesNoHost() {
    assertEquals(1, DomainTree.EMPTY.explain(null).size());
    assertTrue(DomainTree.EMPTY.explain(null).get(0).contains("nothing to search for"));
    assertTrue(DomainTree.EMPTY.explain("").get(0).contains("nothing to search for"));
  }

  @Test
  public void specificityCounts() throws Exception {
    assertEquals(1, config("localhost", true).specificity());
    assertEquals(3, config("junior.example.org", true).specificity());
  }
}
