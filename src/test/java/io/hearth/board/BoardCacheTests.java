package io.hearth.board;

import io.hearth.auth.Accounts;
import io.hearth.cache.TtlCache;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * What the board keeps, and what makes it let go.
 *
 * The half worth testing is the letting go. A cache that never hits is slow; a cache that never
 * drops shows somebody a conversation that has moved on, and looks exactly like it is working.
 * Every test here writes something and then asks whether the next read saw it.
 */
public class BoardCacheTests {
  private Configs configs;
  private TestServer server;
  private Browser ana;
  private Browser ben;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"ana@example.com\",\"ben@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    ana = signIn("ana@example.com");
    ben = signIn("ben@example.com");
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.close();
    }
    if (configs != null) {
      configs.delete();
    }
  }

  // ---- hitting ---------------------------------------------------------------------------------

  @Test
  public void theSecondReadOfAFeedIsAHit() throws Exception {
    post(ana, "Where should we meet?", "The back room is small.");
    cache().feed(60);
    long before = stat("board-feed").hits();
    cache().feed(60);
    cache().feed(60);
    assertEquals("two reads after the first are two hits", before + 2, stat("board-feed").hits());
  }

  @Test
  public void aThreadIsRenderedOnceAndHandedOutAfterwards() throws Exception {
    long id = post(ana, "Where should we meet?", "The back room is small.");
    BoardCache.Thread first = cache().thread(id);
    BoardCache.Thread second = cache().thread(id);
    assertSame("the same value, not an equal one: the markdown ran once", first, second);
    assertTrue(first.bodyHtml().contains("The back room is small."));
  }

  @Test
  public void aThreadThatDoesNotExistIsNotCached() throws Exception {
    assertNull(cache().thread(9999));
    assertNull("a miss for something absent must stay a miss, or a new post is invisible",
        cache().thread(9999));
    assertEquals(0, stat("board-threads").size());
  }

  // ---- letting go ------------------------------------------------------------------------------

  @Test
  public void aNewPostShowsUpInTheFeedImmediately() throws Exception {
    post(ana, "First", "one");
    assertEquals(1, cache().feed(60).size());

    post(ben, "Second", "two");
    assertEquals("the feed was dropped by the event, not by a timer",
        2, cache().feed(60).size());
    assertTrue(ben.get("/board").contains("Second"));
  }

  @Test
  public void aReplyShowsUpInItsThreadImmediately() throws Exception {
    long id = post(ana, "Where should we meet?", "The back room is small.");
    assertEquals(0, cache().thread(id).comments().size());

    reply(ben, id, null, "The library has a room.");
    List<BoardCache.Rendered> comments = cache().thread(id).comments();
    assertEquals(1, comments.size());
    assertTrue(comments.get(0).bodyHtml().contains("The library has a room."));
    assertTrue("and over HTTP, which is the thing that actually matters",
        ana.get("/board/" + id).contains("The library has a room."));
  }

  @Test
  public void aReplyInOneThreadDoesNotDropAnother() throws Exception {
    long first = post(ana, "First", "one");
    long second = post(ana, "Second", "two");
    cache().thread(first);
    cache().thread(second);
    assertEquals(2, stat("board-threads").size());

    reply(ben, first, null, "about the first");
    assertEquals("only the thread that changed", 1, stat("board-threads").size());
    assertNotNull("and the other one is still there", cache().thread(second));
  }

  @Test
  public void joiningAConversationDropsTheThreadThatRecordedIt() throws Exception {
    long id = post(ana, "Where should we meet?", "The back room is small.");
    assertFalse(cache().thread(id).post().isWatchedBy(idOf("ben@example.com")));

    reply(ben, id, null, "The library has a room.");
    assertTrue("the watcher list lives on the post, so the cached post has to go too",
        cache().thread(id).post().isWatchedBy(idOf("ben@example.com")));
  }

  @Test
  public void unwatchingIsVisibleOnTheVeryNextPageLoad() throws Exception {
    long id = post(ana, "Where should we meet?", "The back room is small.");
    reply(ben, id, null, "The library has a room.");
    assertTrue(cache().thread(id).post().isWatchedBy(idOf("ben@example.com")));

    ben.get("/board/" + id);
    ben.submitTo("/board", Map.of("action", "unwatch", "post", Long.toString(id)));
    assertFalse(cache().thread(id).post().isWatchedBy(idOf("ben@example.com")));
  }

  @Test
  public void aRemovedPostLeavesTheFeedAtOnce() throws Exception {
    long id = post(ana, "Where should we meet?", "The back room is small.");
    assertEquals(1, cache().feed(60).size());

    accounts().board.removePost(id, idOf("ana@example.com"));
    assertEquals(0, cache().feed(60).size());
    assertEquals("and the thread is gone rather than stale",
        404, ben.get("/board/" + id).status());
  }

  @Test
  public void aRemovedCommentReadsAsRemovedRatherThanAsItsOldSelf() throws Exception {
    long id = post(ana, "Where should we meet?", "The back room is small.");
    reply(ben, id, null, "Something regrettable.");
    long comment = cache().thread(id).comments().get(0).id();
    assertTrue(ana.get("/board/" + id).contains("Something regrettable."));

    accounts().board.removeComment(comment, idOf("ben@example.com"));
    assertTrue(cache().thread(id).comments().get(0).removed());
    assertFalse("a cache that kept this would be showing what somebody took back",
        ana.get("/board/" + id).contains("Something regrettable."));
  }

  // ---- the caches are visible ------------------------------------------------------------------

  @Test
  public void theAdminCachingPageNamesBothBoardCaches() throws Exception {
    post(ana, "Where should we meet?", "The back room is small.");
    ana.get("/board");
    Browser.Page page = ana.get("/admin/system/caching");
    assertEquals(200, page.status());
    assertTrue("a cache nobody can see the hit rate of is a cache nobody can tune",
        page.contains("board-feed"));
    assertTrue(page.contains("board-threads"));
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  private BoardCache cache() {
    return accounts().boardCache;
  }

  private TtlCache.Stats stat(String name) {
    for (TtlCache.Stats stats : cache().cacheStats()) {
      if (stats.name().equals(name)) {
        return stats;
      }
    }
    throw new IllegalStateException("no cache named " + name);
  }

  private long idOf(String email) throws Exception {
    return accounts().users.byEmail(email).id();
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }

  private long post(Browser who, String title, String body) throws Exception {
    who.get("/board");
    Browser.Page done = who.submitTo("/board",
        Map.of("action", "post", "title", title, "body", body));
    assertEquals(303, done.status());
    return Long.parseLong(done.location().substring("/board/".length()));
  }

  private void reply(Browser who, long postId, Long parentId, String body) throws Exception {
    who.get("/board/" + postId);
    var form = new java.util.LinkedHashMap<String, String>();
    form.put("action", "reply");
    form.put("post", Long.toString(postId));
    if (parentId != null) {
      form.put("parent", Long.toString(parentId));
    }
    form.put("body", body);
    assertEquals(303, who.submitTo("/board", form).status());
  }
}
