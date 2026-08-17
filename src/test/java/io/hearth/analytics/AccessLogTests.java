package io.hearth.analytics;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccessLogTests {
  private static AccessLog log(int capacity) {
    return new AccessLog(capacity);
  }

  private static void hit(AccessLog log, String uri, int status, String ip, Long user, String agent) {
    log.record(System.currentTimeMillis(), "a.test", "GET", uri, status, 1500, ip, user, agent, null);
  }

  @Test
  public void aHitRemembersWhatMatters() {
    AccessLog log = log(10);
    Hit hit = log.record(1000L, "a.test", "GET", "/about?x=1", 200, 2500, "10.0.0.1", 7L,
        "Mozilla/5.0 (X11) Firefox/121.0", "https://example.com/");
    assertEquals("/about?x=1", hit.uri());
    assertEquals("the path is what a top-pages list wants", "/about", hit.path());
    assertEquals(Long.valueOf(7), hit.userId());
    assertEquals("firefox", hit.agent());
    assertEquals(2, hit.durationMillis());
    assertTrue(hit.bySomebodyKnown());
    assertFalse(hit.isError());
  }

  @Test
  public void theBufferOverwrites() {
    AccessLog log = log(3);
    for (int k = 0; k < 10; k++) {
      hit(log, "/p" + k, 200, "1.1.1.1", null, "curl/8");
    }
    assertEquals(3, log.recent().size());
    assertEquals("/p9", log.recent().get(0).uri());
    assertEquals(10, log.total());
  }

  @Test
  public void theSummaryCountsWhatTheDashboardShows() {
    AccessLog log = log(100);
    hit(log, "/", 200, "1.1.1.1", 1L, "Mozilla/5.0 Chrome/120 Safari/537");
    hit(log, "/", 200, "1.1.1.1", 1L, "Mozilla/5.0 Chrome/120 Safari/537");
    hit(log, "/about", 200, "2.2.2.2", null, "curl/8.4");
    hit(log, "/missing", 404, "2.2.2.2", null, "Googlebot/2.1");

    AccessLog.Summary summary = log.summarize("a.test", 10);
    assertEquals(4, summary.total());
    assertEquals(1, summary.errors());
    assertEquals(25, summary.errorRate());
    assertEquals("only the browser hits count as people", 2, summary.people());
    assertEquals(2, summary.signedIn());
    assertEquals("/", summary.topPaths().get(0).label());
    assertEquals(2, summary.topPaths().get(0).count());
    assertEquals("1", summary.topUsers().get(0).label());
    assertTrue(summary.topIps().stream().anyMatch(count -> count.label().equals("1.1.1.1")));
  }

  @Test
  public void theSummaryIsPerDomain() {
    AccessLog log = log(100);
    log.record(1, "a.test", "GET", "/", 200, 100, "1.1.1.1", null, "curl/8", null);
    log.record(2, "b.test", "GET", "/", 200, 100, "1.1.1.1", null, "curl/8", null);
    assertEquals(1, log.summarize("a.test", 5).total());
    assertEquals(2, log.summarize(null, 5).total());
  }

  @Test
  public void searchMatchesThePath() {
    AccessLog log = log(100);
    hit(log, "/about", 200, "1.1.1.1", null, "curl/8");
    hit(log, "/contact", 200, "1.1.1.1", null, "curl/8");
    List<Hit> found = log.search(AccessLog.Query.of("a.test", "about", null, null, null, 100));
    assertEquals(1, found.size());
    assertEquals("/about", found.get(0).uri());
  }

  @Test
  public void searchMatchesTheStatusAsText() {
    AccessLog log = log(100);
    hit(log, "/a", 200, "1.1.1.1", null, "curl/8");
    hit(log, "/b", 404, "1.1.1.1", null, "curl/8");
    assertEquals("typing 404 into one box should just work",
        1, log.search(AccessLog.Query.of("a.test", "404", null, null, null, 100)).size());
  }

  @Test
  public void searchMatchesIpAndAgent() {
    AccessLog log = log(100);
    hit(log, "/a", 200, "10.0.0.5", null, "curl/8");
    hit(log, "/b", 200, "10.0.0.6", null, "Mozilla/5.0 Firefox/121");
    assertEquals(1, log.search(AccessLog.Query.of("a.test", "10.0.0.5", null, null, null, 100)).size());
    assertEquals(1, log.search(AccessLog.Query.of("a.test", "firefox", null, null, null, 100)).size());
  }

  @Test
  public void searchFiltersErrorsAndUsers() {
    AccessLog log = log(100);
    hit(log, "/a", 200, "1.1.1.1", 4L, "curl/8");
    hit(log, "/b", 500, "1.1.1.1", 5L, "curl/8");
    assertEquals(1, log.search(AccessLog.Query.of("a.test", null, null, null, true, 100)).size());
    assertEquals(1, log.search(AccessLog.Query.of("a.test", null, null, 4L, null, 100)).size());
    assertEquals(1, log.search(AccessLog.Query.of("a.test", null, 500, null, null, 100)).size());
  }

  @Test
  public void anEmptySearchReturnsEverything() {
    AccessLog log = log(100);
    hit(log, "/a", 200, "1.1.1.1", null, "curl/8");
    hit(log, "/b", 200, "1.1.1.1", null, "curl/8");
    assertEquals(2, log.search(AccessLog.Query.of("a.test", "", null, null, null, 100)).size());
    assertEquals(2, log.search(AccessLog.Query.of("a.test", null, null, null, null, 100)).size());
  }

  @Test
  public void aSearchLimitIsHonoured() {
    AccessLog log = log(100);
    for (int k = 0; k < 20; k++) {
      hit(log, "/p", 200, "1.1.1.1", null, "curl/8");
    }
    assertEquals(5, log.search(AccessLog.Query.of("a.test", null, null, null, null, 5)).size());
  }
}
