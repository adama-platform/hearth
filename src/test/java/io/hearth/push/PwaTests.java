package io.hearth.push;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The app shell, the manifest, the worker, and the subscription that belongs to a session.
 *
 * The load-bearing claim is the last one: signing out of a browser must make it unreachable. Not
 * "stop sending to it" -- unreachable, with the key destroyed. Half of what is below is checking
 * that nothing survives a sign-out, a reap, or an admin removing somebody.
 */
public class PwaTests {
  private Configs configs;
  private TestServer server;
  private Http http;
  private Browser ana;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Supper Club\",\"admin_emails\":[\"ana@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    http = new Http();
    ana = signIn("ana@example.com");
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

  // ---- the shell --------------------------------------------------------------------------------

  @Test
  public void theShellIsServedAndFramesTheSite() throws Exception {
    Browser.Page page = ana.get("/~app");
    assertEquals(200, page.status());
    assertTrue(page.contains("<iframe"));
    assertTrue("the manifest is linked, or nothing offers to install",
        page.contains("/manifest.webmanifest"));
    assertTrue(page.contains("Example Supper Club"));
  }

  @Test
  public void theShellStartsWhereItIsAskedToWhenThatIsOnThisSite() throws Exception {
    Browser.Page page = ana.get("/~app?to=%2Fboard");
    assertTrue(page.body(), page.contains("src=\"/board\""));
  }

  @Test
  public void theShellRefusesToFrameSomewhereElse() throws Exception {
    for (String hostile : new String[]{"https%3A%2F%2Fnot-your-community.example%2Flogin",
        "%2F%2Fnot-your-community.example", "%2F%5Cnot-your-community.example"}) {
      Browser.Page page = ana.get("/~app?to=" + hostile);
      assertEquals(200, page.status());
      assertFalse(page.body(), page.body().contains("not-your-community"));
    }
  }

  @Test
  public void theShellWorksSignedOutAndOffersNothingToSubscribe() throws Exception {
    Browser stranger = new Browser(server.port, "example.org");
    Browser.Page page = stranger.get("/~app");
    assertEquals("an installed app that 404s while signed out is one nobody reopens",
        200, page.status());
    assertTrue("and there is no key to subscribe with, because there is no session to bind it to",
        page.contains("data-key=\"\"") || page.contains("data-signed-in=\"false\""));
  }

  // ---- the manifest -----------------------------------------------------------------------------

  @Test
  public void theManifestIsPerDomainAndInstallable() throws Exception {
    Http.Response response = http.get(server.port, "example.org", "/manifest.webmanifest");
    assertEquals(200, response.status);
    assertTrue(response.header("content-type"),
        response.header("content-type").startsWith("application/manifest+json"));

    String body = response.body;
    assertTrue("the community's own name, not the product's", body.contains("Example Supper Club"));
    assertTrue("a short name, because a phone gives you twelve characters",
        body.contains("\"short_name\""));
    assertTrue("start_url is the shell", body.contains("\"start_url\":\"/~app\""));
    assertTrue(body.contains("\"display\":\"standalone\""));
    assertTrue("a maskable icon, or a phone puts it in a white square",
        body.contains("\"maskable\""));
    assertTrue("an id, so moving the start url does not create a second app",
        body.contains("\"id\":\"/~app\""));

    // Real PNGs at real addresses. These used to be the inline SVG as data: URIs -- correct by the
    // specification, refused in practice: Chrome downloads manifest icons and will not install an
    // app whose icons are data URIs, and iOS wants a PNG before it puts anything on a home screen.
    // The app had a manifest, a worker and no install button, and this line is why.
    assertTrue("192, or Chrome does not offer to install", body.contains("192x192"));
    assertTrue("512, or there is no splash screen", body.contains("512x512"));
    assertTrue(body.contains("/~app/icon-192.png"));
  }

  @Test
  public void theIconsAreRealBytesAtRealAddresses() throws Exception {
    for (String path : new String[]{"/~app/icon-192.png", "/~app/icon-512.png",
        "/~app/icon-maskable-512.png"}) {
      Http.Response response = http.get(server.port, "example.org", path);
      assertEquals(path, 200, response.status);
      assertEquals("image/png", response.header("content-type"));
      byte[] png = response.bytes;
      assertTrue(path + " is not a PNG", png.length > 100
          && (png[0] & 0xff) == 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G');
    }
    assertTrue("cached, because the bytes for one size and one colour never change",
        http.get(server.port, "example.org", "/~app/icon-192.png")
            .header("cache-control").contains("max-age"));
  }

  @Test
  public void everyPageOffersToBeInstalled() throws Exception {
    // the manifest used to be declared on the shell alone -- an address nobody reaches without
    // already knowing about it, which is why no browser ever offered to install this
    Browser.Page page = ana.get("/home");
    assertTrue(page.body(), page.contains("rel=\"manifest\""));
    assertTrue("iOS reads the touch icon from the page and ignores the manifest entirely",
        page.contains("apple-touch-icon"));
    assertTrue(page.contains("apple-mobile-web-app-capable"));
  }

  @Test
  public void theManifestIsServedWithoutASession() throws Exception {
    assertEquals(200, http.get(server.port, "example.org", "/manifest.webmanifest").status);
  }

  @Test
  public void aShortNameIsTrimmedRatherThanTruncatedMidWord() {
    assertEquals("Hearth", io.hearth.web.PwaRoutes.shortName(null));
    assertEquals("Short", io.hearth.web.PwaRoutes.shortName("Short"));
    String cut = io.hearth.web.PwaRoutes.shortName("A Very Long Community Name Indeed");
    assertTrue(cut, cut.length() <= 12);
    assertEquals("no trailing space under the icon", cut.trim(), cut);
    assertEquals("A Very Long", cut);
  }

  // ---- installing it, and proving it works ------------------------------------------------------

  @Test
  public void thereIsAScreenSayingHowToInstallItAndHowToTurnNotificationsOn() throws Exception {
    Browser.Page page = ana.get("/~app/help");
    assertEquals(200, page.status());
    assertTrue("Safari, because on iOS nothing else can do it", page.contains("Share"));
    assertTrue(page.contains("Add to Home Screen"));
    assertTrue("and Android, which is a different set of words", page.contains("Install app"));
    assertTrue(page.contains("Turn on notifications"));
    assertTrue("and the test, which is the point of the page",
        page.contains("Send me a test notification"));
    assertTrue("plus what to do when the browser has already said no",
        page.contains("will not ask again"));
    assertTrue("it is in the menu, or nobody finds it", ana.get("/home").contains("Get the app"));
  }

  @Test
  public void aTestNotificationNeedsASessionAndACsrfToken() throws Exception {
    Http.Response anonymous = http.send(server.port, "example.org", "POST", "/~app/selftest",
        new byte[0]);
    assertEquals(403, anonymous.status);
    assertTrue(anonymous.body, anonymous.body.contains("sign in first"));

    // GET is not a way to make the server send anybody a notification
    assertEquals(405, http.get(server.port, "example.org", "/~app/selftest").status);
  }

  @Test
  public void aTestSaysSoWhenThisBrowserHasNotSubscribed() throws Exception {
    // the failure people actually hit: pressing test before pressing the button, and a page that
    // said "sent" would teach them the feature is broken when it is not even switched on
    Browser.Page answer = ana.submitTo("/~app/selftest", java.util.Map.of());
    assertEquals(200, answer.status());
    assertTrue(answer.body(), answer.contains("\"ok\":false"));
    assertTrue(answer.body(), answer.contains("has not"));
  }

  // ---- the service worker -----------------------------------------------------------------------

  @Test
  public void theWorkerIsAtTheRootSoItsScopeIsTheWholeSite() throws Exception {
    Http.Response response = http.get(server.port, "example.org", "/sw.js");
    assertEquals(200, response.status);
    assertTrue(response.header("content-type").startsWith("text/javascript"));
    assertEquals("a worker under /~app could only ever control /~app", "/",
        response.header("Service-Worker-Allowed"));
    assertEquals("never cached, or a fix ships a week late", "no-cache",
        response.header("cache-control"));

    // A browser will not offer to install an app whose worker cannot answer a navigation with the
    // network down. This one still caches nothing: the only thing it can produce offline is a page
    // built inside the worker saying there is no connection, for which stale is not a possible
    // state.
    assertTrue("a fetch handler, or no browser offers to install it",
        response.body.contains("addEventListener('fetch'"));
    assertTrue(response.body.contains("No connection"));
    assertFalse("and still nothing is cached", response.body.contains("caches.open"));

    // and it tells whatever page is open that one landed, which is what the self-test waits for
    assertTrue(response.body.contains("push-arrived"));
  }

  @Test
  public void theWorkerHandlesPushAndReEntersRatherThanOpeningACopy() throws Exception {
    String script = http.get(server.port, "example.org", "/sw.js").body;
    assertTrue(script.contains("addEventListener('push'"));
    assertTrue(script.contains("addEventListener('notificationclick'"));
    assertTrue("focus an open window rather than opening another", script.contains("focus"));
    assertTrue(script.contains("matchAll"));
    assertTrue("and handle a rotated subscription rather than logging an error",
        script.contains("pushsubscriptionchange"));
    assertFalse("caching a members list offline is worse than saying we cannot reach the server",
        script.contains("caches.open"));
  }

  // ---- subscribing, and the session it belongs to -------------------------------------------------

  @Test
  public void theKeyIsMintedPerSessionAndIsStableAcrossReloads() throws Exception {
    String first = keyFromShell();
    String second = keyFromShell();
    assertNotNull(first);
    assertFalse(first.isEmpty());
    assertEquals("a second key would silently invalidate the subscription already registered",
        first, second);
  }

  @Test
  public void twoSessionsGetTwoDifferentKeys() throws Exception {
    String mine = keyFromShell();
    Browser phone = signIn("ana@example.com");
    String theirs = keyFromShell(phone);
    assertFalse("a phone and a laptop are two devices and two subscriptions",
        mine.equals(theirs));
  }

  @Test
  public void subscribingBindsTheBrowserToTheSession() throws Exception {
    keyFromShell();
    subscribe(ana, "https://push.example.net/abc");

    SessionRecord session = sessionOf(ana);
    PushSubs.Sub sub = subs().forSession(session.id());
    assertNotNull(sub);
    assertEquals("https://push.example.net/abc", sub.endpoint());
    assertEquals(1, subs().forUser(session.userId()).size());
  }

  @Test
  public void subscribingAgainUpdatesTheRowRatherThanAddingOne() throws Exception {
    keyFromShell();
    subscribe(ana, "https://push.example.net/abc");
    subscribe(ana, "https://push.example.net/abc");
    subscribe(ana, "https://push.example.net/rotated");

    assertEquals("a service worker may call this on every page load", 1, subs().count());
    assertEquals("https://push.example.net/rotated",
        subs().forSession(sessionOf(ana).id()).endpoint());
  }

  @Test
  public void oneBrowserSigningInAgainDoesNotLeaveTwoWaysToReachIt() throws Exception {
    keyFromShell();
    subscribe(ana, "https://push.example.net/same-browser");

    Browser again = signIn("ana@example.com");
    keyFromShell(again);
    subscribe(again, "https://push.example.net/same-browser");

    assertEquals("the same endpoint under two sessions would be two notifications for one device",
        1, subs().count());
  }

  @Test
  public void subscribingWithoutASessionIsRefused() throws Exception {
    Browser stranger = new Browser(server.port, "example.org");
    stranger.get("/~app");
    Browser.Page page = stranger.submitTo("/~app/push",
        Map.of("endpoint", "https://push.example.net/x", "p256dh", "k", "auth", "a"));
    assertEquals(403, page.status());
    assertEquals(0, subs().count());
  }

  @Test
  public void anEndpointThatIsNotHttpsIsRefused() throws Exception {
    keyFromShell();
    Browser.Page page = ana.submitTo("/~app/push",
        Map.of("endpoint", "http://push.example.net/x", "p256dh", "k", "auth", "a"));
    assertEquals(400, page.status());
    assertEquals(0, subs().count());
  }

  // ---- and the part that matters ------------------------------------------------------------------

  @Test
  public void signingOutDeletesTheSessionAndTheSubscriptionWithIt() throws Exception {
    keyFromShell();
    subscribe(ana, "https://push.example.net/abc");
    long sessionId = sessionOf(ana).id();
    assertEquals(1, subs().count());

    ana.submitTo("/logout", Map.of());

    assertNull("the row goes, not a flag on it -- a revoked session lingers for a day",
        subs().forSession(sessionId));
    assertEquals("and there is no key left that the push service would accept", 0, subs().count());
  }

  @Test
  public void signingOutDeletesTheSessionRowRatherThanRevokingIt() throws Exception {
    keyFromShell();
    long sessionId = sessionOf(ana).id();
    ana.submitTo("/logout", Map.of());

    assertNull("for that day the server still holds a key for a device somebody just signed out of",
        accounts().sessions.byId(sessionId));
  }

  @Test
  public void anAdminRemovingSomebodySilencesEveryDeviceTheyHad() throws Exception {
    Browser ben = signIn("ben@example.com");
    keyFromShell(ben);
    subscribe(ben, "https://push.example.net/bens-phone");
    assertEquals(1, subs().count());

    long id = accounts().users.byEmail("ben@example.com").id();
    ana.submitToAndFollow("/admin/people",
        Map.of("action", "reject", "user", Long.toString(id)));

    assertEquals(0, subs().count());
  }

  @Test
  public void aSubscriptionCannotOutliveItsSession() throws Exception {
    keyFromShell();
    subscribe(ana, "https://push.example.net/abc");
    long sessionId = sessionOf(ana).id();

    // the session goes some other way -- expiry, the reaper, a hand on the database
    accounts().sessions.deleteById(sessionId);
    assertEquals("the sweep is the belt to the delete's braces", 1, subs().sweepOrphans());
    assertEquals(0, subs().count());
  }

  @Test
  public void turningNotificationsOffRemovesTheSubscriptionButKeepsTheSession() throws Exception {
    keyFromShell();
    subscribe(ana, "https://push.example.net/abc");

    Browser.Page page = ana.submitTo("/~app/push", Map.of("action", "off"));
    assertEquals(204, page.status());
    assertEquals(0, subs().count());
    assertEquals("they are still signed in", 200, ana.get("/~app").status());
  }

  // ---- plumbing ---------------------------------------------------------------------------------

  private Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  private PushSubs subs() {
    return accounts().pushSubs;
  }

  private SessionRecord sessionOf(Browser browser) throws Exception {
    return accounts().sessions.resolve(browser.cookie(accounts().security.cookieName));
  }

  private String keyFromShell() throws Exception {
    return keyFromShell(ana);
  }

  private String keyFromShell(Browser browser) throws Exception {
    String body = browser.get("/~app").body();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("data-key=\"([^\"]*)\"").matcher(body);
    return matcher.find() ? matcher.group(1) : null;
  }

  private void subscribe(Browser browser, String endpoint) throws Exception {
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("endpoint", endpoint);
    form.put("p256dh", PushCrypto.b64(
        PushCrypto.publicKeyBytes(PushCrypto.generateKeyPair().getPublic())));
    form.put("auth", PushCrypto.b64(PushCrypto.randomBytes(16)));
    Browser.Page page = browser.submitTo("/~app/push", form);
    assertEquals(page.body(), 204, page.status());
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
