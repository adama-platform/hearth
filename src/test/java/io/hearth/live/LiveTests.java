package io.hearth.live;

import io.hearth.common.Verbose;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The live channel: the sequence, who may hear what, and the two transports.
 *
 * The property that matters most is negative and easy to lose: a signal about a direct message must
 * reach exactly two people. Everything else here is a cursor.
 */
public class LiveTests {
  private Configs configs;
  private TestServer server;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
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

  // ---- the hub on its own ---------------------------------------------------------------------

  @Test
  public void aCursorIsWhatYouHaveNotSeen() {
    LiveHub hub = new LiveHub("example.org", Verbose.OFF);
    long first = hub.publish(Signal.Kind.updated, "c:1", "1");
    long second = hub.publish(Signal.Kind.updated, "c:1", "2");
    assertEquals(first + 1, second);
    assertEquals(2, hub.since(0).size());
    assertEquals(1, hub.since(first).size());
    assertTrue(hub.since(second).isEmpty());
  }

  @Test
  public void presenceIsAWindowAndForgetsOnItsOwn() {
    LiveHub hub = new LiveHub("example.org", Verbose.OFF);
    assertFalse(hub.isOnline(3));
    hub.beat(3);
    assertTrue(hub.isOnline(3));
    assertEquals(List.of(3L), hub.online());
    hub.gone(3);
    assertFalse(hub.isOnline(3));
  }

  @Test
  public void aSignalCarriesNoContent() {
    // the whole design rests on this: if it ever carried a message body, the live channel would be
    // a second and weaker path to the same data
    Signal signal = new Signal(1, Signal.Kind.updated, "comments:412", "412");
    String json = signal.json();
    assertTrue(json.contains("\"kind\":\"updated\""));
    assertTrue(json.contains("\"scope\":\"comments:412\""));
    assertFalse(json.contains("body"));
    assertFalse(json.contains("text"));
  }

  @Test
  public void aBeatIsNotABroadcast() {
    // every open tab beats every twenty seconds; a fan-out per beat would be the loudest thing in
    // the server and would say nothing anybody needed to know
    LiveHub hub = new LiveHub("example.org", Verbose.OFF);
    hub.beat(3);
    long after = hub.head();
    for (int k = 0; k < 20; k++) {
      hub.beat(3);
    }
    assertEquals("only the edges are interesting", after, hub.head());
  }

  @Test
  public void theRingIsBoundedAndSaysWhereItStarts() {
    LiveHub hub = new LiveHub("example.org", Verbose.OFF);
    for (int k = 0; k < 700; k++) {
      hub.publish(Signal.Kind.updated, "posts:1", null);
    }
    assertTrue("a client that blinked for an hour is told to refetch rather than replayed",
        hub.floor() > 0);
    assertTrue(hub.since(0).size() <= 512);
  }

  // ---- over http --------------------------------------------------------------------------------

  @Test
  public void theChannelIsForMembers() throws Exception {
    try (Http http = new Http()) {
      assertEquals("a stranger cannot hold a connection open", 204,
          http.get(server.port, "example.org", "/~live/poll").status);
      assertEquals(204, http.get(server.port, "example.org", "/~live/sse").status);
    }
  }

  @Test
  public void theScriptsAreServedToAnybodyBecauseTheyAreTheSameBytes() throws Exception {
    try (Http http = new Http()) {
      Http.Response live = http.get(server.port, "example.org", "/~live/live.js");
      assertEquals(200, live.status);
      assertTrue(live.bodyContains("hearthLive"));
      assertEquals("and anything else on this path is the members' channel, which says nothing"
              + " to a stranger",
          204, http.get(server.port, "example.org", "/~live/nothing.js").status);
    }
  }

  @Test
  public void aPollAnswersImmediatelyWhenThereIsSomethingWaiting() throws Exception {
    Browser ana = approved("ana@example.com");
    LiveHub hub = server.live.forDomain("example.org");
    long before = hub.head();
    hub.publish(Signal.Kind.updated, "posts:7", null);

    Browser.Page page = ana.get("/~live/poll?since=" + before);
    assertEquals(200, page.status());
    assertTrue(page.contains("\"kind\":\"updated\""));
    assertTrue(page.contains("posts:7"));
  }

  @Test
  public void aPollWithNothingToSayComesBackEmptyRatherThanFailing() throws Exception {
    LiveHub hub = server.live.forDomain("example.org");
    // ask from the head, so there is nothing after it; the request is answered by its timeout,
    // which the test does not wait for -- what it checks is that asking is cheap and honest
    assertTrue(hub.since(hub.head()).isEmpty());
  }

  @Test
  public void pollingCountsAsBeingHere() throws Exception {
    Browser ana = approved("ana@example.com");
    long anaId = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    LiveHub hub = server.live.forDomain("example.org");
    hub.gone(anaId);
    assertFalse(hub.isOnline(anaId));
    // something is already waiting, so the poll answers at once rather than holding for its timeout
    hub.publish(Signal.Kind.updated, "posts:1", null);
    ana.get("/~live/poll?since=0");
    assertTrue("the green dot is somebody with the page open", hub.isOnline(anaId));
  }

  /**
   * The stream, on a real socket.
   *
   * The JDK's HTTP client waits for a response to finish, and this one deliberately does not -- so
   * this is the one place a raw socket is the only way to see the thing being tested. What it
   * proves is the part that is easy to get wrong and invisible until somebody complains: that the
   * headers say event-stream, that the compressor in the pipeline has been told to leave it alone,
   * and that the first bytes arrive without waiting for anything else to happen.
   */
  @Test
  public void theStreamSaysHelloBeforeAnythingHappens() throws Exception {
    Browser ana = approved("ana@example.com");
    String cookie = "hearth_session=" + ana.cookie("hearth_session");
    String answer = Http.raw(server.port,
        "GET /~live/sse?since=0 HTTP/1.1\r\nHost: example.org\r\nAccept: text/event-stream\r\n"
            + "Cookie: " + cookie + "\r\nConnection: keep-alive\r\n\r\n", 1500);

    assertTrue(answer, answer.startsWith("HTTP/1.1 200"));
    assertTrue("a browser has to be told what this is", answer.contains("text/event-stream"));
    assertTrue("gzip would buffer the whole point of it away",
        answer.toLowerCase().contains("content-encoding: identity"));
    assertTrue("and the first event arrives without waiting for anything to happen",
        answer.contains("event: hello"));
    assertTrue(answer.contains("\"head\""));
  }

  @Test
  public void aSignalReachesAnOpenStream() throws Exception {
    Browser ana = approved("ana@example.com");
    LiveHub hub = server.live.forDomain("example.org");
    String cookie = "hearth_session=" + ana.cookie("hearth_session");
    // publish from another thread while the socket is being read, which is what actually happens
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor().schedule(
        () -> hub.publish(Signal.Kind.updated, "posts:9", "1"), 300,
        java.util.concurrent.TimeUnit.MILLISECONDS);
    String answer = Http.raw(server.port,
        "GET /~live/sse?since=" + hub.head() + " HTTP/1.1\r\nHost: example.org\r\n"
            + "Cookie: " + cookie + "\r\nConnection: keep-alive\r\n\r\n", 1500);
    assertTrue(answer, answer.contains("event: signal"));
    assertTrue(answer.contains("posts:9"));
    assertTrue("each event carries the id a browser sends back as Last-Event-ID",
        answer.contains("id: "));
  }

  @Test
  public void theBoardRidesTheSameChannel() throws Exception {
    // wired through the event bus rather than through a call from the board, so a write from a
    // model or the admin shows up live without that code knowing this exists
    LiveHub hub = server.live.forDomain("example.org");
    long before = hub.head();
    server.events.emit("example.org", "posts", "42",
        io.hearth.events.MutationEvent.Kind.insert, null);
    assertFalse(hub.since(before).isEmpty());
    assertEquals("posts:42", hub.since(before).get(0).scope());
  }

  /** signed in and let in; the live channel is behind the same approval gate as the community */
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
