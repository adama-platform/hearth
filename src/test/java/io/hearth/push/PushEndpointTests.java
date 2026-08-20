package io.hearth.push;

import io.hearth.common.PublicAddress;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
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
 * A push endpoint is a url this server will POST to on its own, so it is checked like one.
 *
 * The availability grid already treats a member-supplied url as what invariant 202 says it is — an
 * instruction to make a request — and resolves it, refusing every private range. The push endpoint
 * a browser hands over is the same kind of thing arriving one seam away, and the whole test on it
 * was that the string began with `https://`.
 *
 * So an approved member could subscribe with an endpoint inside the network the server is on and
 * have it knock on that address, from a background thread, for as long as the subscription lived.
 * Blind — the response never reaches the member — and bounded by TLS, since the certificate has to
 * match the name asked for. Neither of those is a reason to make the request.
 */
public class PushEndpointTests {
  private Configs configs;
  private TestServer server;
  private Browser member;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    member = signIn("boss@example.com");
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

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }

  private io.hearth.auth.Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  private Browser.Page subscribeTo(String endpoint) throws Exception {
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("endpoint", endpoint);
    form.put("p256dh", "BJxSFRV1mVIvMSTS9YXNJTLYPJTvUCFPHRdBhSCBHTXqRQzZ9SBiOFRFqvUXhSlvUnWQ");
    form.put("auth", "cGFzc3dvcmRwYXNzd29yZA");
    member.get("/~app");
    return member.submitTo("/~app/push", form);
  }

  private int subscriptions() throws Exception {
    return accounts().pushSubs.forUser(
        accounts().users.byEmail("boss@example.com").id()).size();
  }

  // ---- the finding ------------------------------------------------------------------------------

  @Test
  public void anEndpointOnALoopbackAddressIsRefused() throws Exception {
    assertEquals(400, subscribeTo("https://127.0.0.1/push/abc").status());
    assertEquals("nothing may be written for an address this server will not ask",
        0, subscriptions());
  }

  @Test
  public void anEndpointOnAPrivateNetworkIsRefused() throws Exception {
    assertEquals(400, subscribeTo("https://10.0.0.5/push/abc").status());
    assertEquals(400, subscribeTo("https://192.168.1.20/push/abc").status());
    assertEquals(400, subscribeTo("https://172.16.4.4/push/abc").status());
    assertEquals(0, subscriptions());
  }

  /** the one that matters most on a rented box: the metadata service every provider runs */
  @Test
  public void anEndpointOnTheLinkLocalRangeIsRefused() throws Exception {
    assertEquals(400, subscribeTo("https://169.254.169.254/latest/meta-data/").status());
    assertEquals(0, subscriptions());
  }

  @Test
  public void anEndpointOnIpv6LoopbackOrUniqueLocalIsRefused() throws Exception {
    assertEquals(400, subscribeTo("https://[::1]/push/abc").status());
    assertEquals(400, subscribeTo("https://[fd00::1]/push/abc").status());
    assertEquals(0, subscriptions());
  }

  /** plain http was already refused, and stays refused */
  @Test
  public void aPlainHttpEndpointIsStillRefused() throws Exception {
    assertEquals(400, subscribeTo("http://push.example/abc").status());
    assertEquals(0, subscriptions());
  }

  // ---- and the ranges themselves, without a server in the way -------------------------------------

  @Test
  public void theSharedCheckKnowsEveryRangeThatIsNotThePublicInternet() {
    assertNotNull(PublicAddress.refuse("127.0.0.1"));
    assertNotNull(PublicAddress.refuse("10.0.0.1"));
    assertNotNull(PublicAddress.refuse("192.168.0.1"));
    assertNotNull(PublicAddress.refuse("172.20.0.1"));
    assertNotNull(PublicAddress.refuse("169.254.169.254"));
    assertNotNull("carrier grade NAT is just as much inside", PublicAddress.refuse("100.64.0.1"));
    assertNotNull(PublicAddress.refuse("0.0.0.0"));
    assertNotNull(PublicAddress.refuse("::1"));
    assertNotNull(PublicAddress.refuse("fd00::1"));
    // .invalid is reserved by RFC 2606 and never resolves, with or without a network
    assertNotNull("a name that does not resolve is not a name to ask",
        PublicAddress.refuse("no-such-host.invalid"));
    assertNull("and a public address is fine", PublicAddress.refuse("198.51.100.10"));
    assertNotNull(PublicAddress.refuse(null));
    assertNotNull(PublicAddress.refuse(""));
  }

  /**
   * The two callers ask the same question, which is the point of moving it.
   *
   * The calendar side adds its own sentence to the refusal, so the wording a member reads still
   * says what this server was declining to do.
   */
  @Test
  public void theCalendarSideAsksTheSameQuestion() {
    String refused = io.hearth.availability.CalendarFetch.refusePrivate("127.0.0.1");
    assertNotNull(refused);
    assertTrue(refused, refused.contains("private network"));
    assertTrue(refused, refused.contains("calendars"));
    assertNull(io.hearth.availability.CalendarFetch.refusePrivate("198.51.100.10"));
  }

  /** and an ordinary public endpoint is still accepted, or the feature is gone */
  @Test
  public void anOrdinaryPushServiceIsStillAccepted() throws Exception {
    // an address literal rather than a real push service's hostname: resolving one would make this
    // suite depend on DNS, and a literal in a public range asks the same question without leaving
    // the machine. 198.51.100.0/24 is the documentation range, which is public and routes nowhere.
    assertEquals(204, subscribeTo("https://198.51.100.10/wpush/v2/abc").status());
    assertEquals(1, subscriptions());
    assertFalse(accounts().pushSubs.forUser(
        accounts().users.byEmail("boss@example.com").id()).isEmpty());
  }
}
