package io.hearth.vhost;

import io.hearth.analytics.AccessLog;
import io.hearth.common.ConfigException;
import io.hearth.common.Verbose;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Switching a whole part of the product off in one word.
 *
 * The property worth testing is that "off" means off everywhere at once -- the path, the navigation
 * and anything that hangs off it. A surface that is missing from the menu and still answering on a
 * path somebody knows is worse than one that is on, because the operator believes it is gone.
 */
public class SurfaceTests {
  private Configs configs;
  private TestServer server;

  @After
  public void tearDown() {
    if (server != null) {
      server.close();
    }
    if (configs != null) {
      configs.delete();
    }
  }

  private void boot(String json) throws Exception {
    configs = Configs.dir().domain("example.org", json);
    server = TestServer.ofConfigs(configs.file());
  }

  @Test
  public void everythingIsOnUntilSomebodySaysOtherwise() throws Exception {
    boot("{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    DomainConfig config = server.tree.resolve("example.org");
    for (Surface surface : Surface.values()) {
      if (surface == Surface.ai) {
        // the one default in this server not tuned for a high-trust community: what it hands out
        // is the ability to rewrite the site
        assertFalse(config.has(surface));
        continue;
      }
      assertTrue(surface.name(), config.has(surface));
    }
  }

  @Test
  public void oneWordTurnsAPartOfTheProductOff() throws Exception {
    boot("{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"],"
        + "\"disabled\":[\"members\",\"places\"]}");
    DomainConfig config = server.tree.resolve("example.org");
    assertFalse(config.has(Surface.places));
    assertFalse(config.has(Surface.members));
    assertTrue("and nothing else moved", config.has(Surface.board));
    assertTrue(config.has(Surface.calendar));
  }

  @Test
  public void offMeansOffOnThePathAndInTheMenuAtOnce() throws Exception {
    boot("{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"],"
        + "\"disabled\":[\"members\",\"calendar\"]}");
    Browser member = approved("ana@example.com");
    Browser.Page home = member.get("/");
    assertFalse("gone from the navigation", home.contains(">Members<"));
    assertFalse(home.contains(">Events<"));
    assertTrue("and what is left is still there", home.contains(">Discussion<"));

    // the path is not merely hidden: an unrouted path falls through to the site rather than
    // answering, which is what "this community does not have one" looks like from outside
    assertFalse(member.get("/members").contains("class=\"members\""));
  }

  @Test
  public void aSwitchedOffSectionAnswersRatherThanHanging() throws Exception {
    // its path stays in the table -- that is what stops two sections sharing an address -- and it
    // used to fall into a handler with no branch for it, which wrote nothing and held the
    // connection until the browser gave up. The worst kind of failure: invisible in a log, and
    // indistinguishable from a hung server.
    boot("{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"],"
        + "\"disabled\":[\"board\",\"calendar\",\"places\",\"members\"]}");
    Browser member = approved("ana@example.com");
    for (String path : java.util.List.of("/board", "/events", "/places", "/members")) {
      Browser.Page page = member.get(path);
      assertTrue(path + " answered " + page.status(), page.status() < 500);
    }
  }

  @Test
  public void aBroadSwitchBeatsTheBlockItCoversRatherThanArguingWithIt() throws Exception {
    // the block says on and the broad switch says off; the broad switch is the decision somebody
    // took about what this community is
    boot("{\"name\":\"Example\",\"board\":{\"enabled\":true},\"disabled\":[\"board\"]}");
    DomainConfig config = server.tree.resolve("example.org");
    assertTrue("the block is untouched", config.board.enabled);
    assertFalse("and the answer is still no", config.has(Surface.board));
  }

  @Test
  public void aNameNobodyRecognisesStopsTheServer() throws Exception {
    Configs bad = Configs.dir().domain("example.org", "{\"disabled\":[\"boardd\"]}");
    try {
      DomainScanner.scan(bad.file(), Verbose.OFF);
      fail("a typo here is a surface somebody believes is off and is not");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("boardd"));
      assertTrue("and it says what it does know", ex.getMessage().contains("board"));
    } catch (Exception ex) {
      fail("wrong exception: " + ex);
    } finally {
      bad.delete();
    }
  }

  @Test
  public void aSurfaceNameIsCaseAndSpaceForgiving() {
    assertEquals(Surface.board, Surface.of("  BOARD "));
    assertNull(Surface.of("boardd"));
    assertNull(Surface.of(null));
  }

  // ---- the live channel is not traffic ----------------------------------------------------------

  @Test
  public void theLiveChannelIsCountedRatherThanLogged() {
    AccessLog log = new AccessLog(100);
    assertNull("an open tab asks this every few seconds forever",
        log.record(1, "example.org", "GET", "/~live/poll?since=4", 200, 10, "1.2.3.4", 1L, "x", null));
    log.record(2, "example.org", "GET", "/~live/sse", 200, 10, "1.2.3.4", 1L, "x", null);
    assertEquals(2, log.livePings());
    assertEquals("and none of it is in the ring", 0, log.recent().size());

    log.record(3, "example.org", "GET", "/~live/live.js", 200, 10, "1.2.3.4", 1L, "x", null);
    assertEquals("a script fetched once and cached is not a ping", 2, log.livePings());

    log.record(4, "example.org", "GET", "/board", 200, 10, "1.2.3.4", 1L, "x", null);
    assertEquals("a page somebody read is", 1, log.recent().size());
  }

  @Test
  public void thePagesSomebodyReadAreStillTheTopPages() throws Exception {
    boot("{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    Browser ana = approved("ana@example.com");
    io.hearth.live.LiveHub hub = server.live.forDomain("example.org");
    for (int k = 0; k < 20; k++) {
      // something is always waiting, so the poll answers at once rather than holding for its
      // timeout -- what is being tested is where the request lands, not how long it waits
      hub.publish(io.hearth.live.Signal.Kind.updated, "posts:1", null);
      ana.get("/~live/poll?since=0");
    }
    ana.get("/");
    AccessLog.Summary summary = server.accessLog.summarize("example.org", 5);
    assertFalse("the heartbeat never reaches a dashboard",
        summary.topPaths().stream().anyMatch(row -> String.valueOf(row).contains("~live")));
    assertTrue("but it is counted", server.accessLog.livePings() >= 20);
  }

  private Browser approved(String email) throws Exception {
    Browser admin = signIn("boss@example.com");
    Browser browser = signIn(email);
    long id = server.auth.forDomain("example.org").users.byEmail(email).id();
    admin.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(id)));
    return browser;
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
