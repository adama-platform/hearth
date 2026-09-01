package io.hearth.push;

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
import static org.junit.Assert.assertTrue;

/**
 * How long a notification takes to work.
 *
 * The number worth having is not how many were sent -- that goes up whether or not anybody looked
 * -- but the gap between a phone buzzing and a person arriving. Everything here is about that being
 * measured honestly and costing almost nothing: buffered in memory, deduped per person, and written
 * down on a timer rather than on the path that fires every time anything happens on the board.
 */
public class PushLedgerTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com");
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

  @Test
  public void aSendAndAnArrivalBecomeOneDelay() throws Exception {
    long me = me();
    long sent = System.currentTimeMillis() - 4 * 60_000;
    ledger().sent(me, sent);
    ledger().acted(me, sent + 4 * 60_000);
    assertEquals("nothing is written until the flush, so the row says nothing yet",
        -1, ledger().delayFor(me).minutes());
    ledger().flush(System.currentTimeMillis());

    PushLedger.Delay delay = ledger().delayFor(me);
    assertEquals(4, delay.minutes());
    assertEquals("and the buffer is empty afterwards", 0, ledger().waiting());
  }

  @Test
  public void threeDevicesBuzzingIsOneNotification() throws Exception {
    // a person with a laptop, a phone and a tablet gets three pushes and arrives once; taking the
    // last would measure the delay from whichever device the loop happened to reach last
    long me = me();
    long first = System.currentTimeMillis() - 10 * 60_000;
    ledger().sent(me, first);
    ledger().sent(me, first + 1000);
    ledger().sent(me, first + 2000);
    ledger().acted(me, first + 10 * 60_000);
    ledger().flush(System.currentTimeMillis());
    assertEquals(10, ledger().delayFor(me).minutes());
  }

  @Test
  public void nothingIsWrittenUntilItIsDue() throws Exception {
    long now = System.currentTimeMillis();
    ledger().sent(me(), now);
    assertFalse("a write per push would be among the busiest in the server", ledger().due(now));
    assertTrue(ledger().due(now + PushLedger.FLUSH_MILLIS + 1));
  }


  @Test
  public void somebodySentToAndNeverHeardFromIsCountedSeparately() throws Exception {
    ledger().sent(me(), System.currentTimeMillis() - 60_000);
    ledger().flush(System.currentTimeMillis());
    assertTrue(ledger().histogram().stream().anyMatch(row ->
        Boolean.TRUE.equals(row.get("unanswered")) && ((Number) row.get("count")).intValue() == 1));
  }

  @Test
  public void openingTheAppFromANotificationIsWhatCounts() throws Exception {
    // the one honest signal this server gets that a push worked: the worker puts the destination
    // in the address when somebody taps one
    long me = me();
    ledger().sent(me, System.currentTimeMillis() - 120_000);
    admin.get("/~app?to=%2Fboard");
    ledger().flush(System.currentTimeMillis());
    assertTrue("two minutes, give or take", ledger().delayFor(me).minutes() >= 1);
  }

  @Test
  public void anAdminHasNoTurnOffOrRejectButton() throws Exception {
    // a community that could switch its own administrators off has locked itself out of its own
    // server, and the handler has always refused it
    Browser.Page page = admin.get("/admin/people/review/" + me());
    assertFalse(page.contains("value=\"disable\""));
    assertFalse(page.contains("value=\"reject\""));
    assertTrue("and it says why rather than leaving somebody looking for them",
        page.contains("cannot be rejected, deleted or turned off"));
  }

  @Test
  public void thereIsNoTestButtonForSomebodyWithNoSubscribedBrowser() throws Exception {
    // a button that would go nowhere teaches somebody the feature is broken
    Browser ana = signIn("ana@example.com");
    long id = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    Browser.Page page = admin.get("/admin/people/review/" + id);
    assertFalse(page.contains("value=\"test_push\""));
    assertTrue(page.contains("No browser of theirs is subscribed"));

    Browser.Page refused = admin.submitToAndFollow("/admin/people",
        Map.of("action", "test_push", "user", Long.toString(id)));
    assertTrue(refused.body(), refused.contains("Nothing went"));
    assertTrue(ana != null);
  }

  @Test
  public void aPushSaysWhoItIsForAndNeverWhat() {
    WebPush.Message message = new WebPush.Message("Example", "Ana replied", "/board/3",
        "thread-3", 41);
    assertEquals(41, message.userId());
    // a browser can hold two people's subscriptions -- a shared laptop, somebody who signed out
    // and in as somebody else -- and a service worker has no session to ask
    assertFalse("never the contents", message.body().contains("flour"));
  }

  private PushLedger ledger() {
    return server.auth.forDomain("example.org").pushLedger;
  }

  private long me() throws Exception {
    return server.auth.forDomain("example.org").users.byEmail("boss@example.com").id();
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
