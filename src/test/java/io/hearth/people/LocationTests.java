package io.hearth.people;

import io.hearth.places.Geocoder;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Where members live, which is the most sensitive thing this server has ever been asked to hold.
 *
 * The feature is worth having for one reason -- a planner who can see that a hall puts half the
 * community on an hour's journey books a different hall -- and it is only worth having if the
 * promise holds. So most of what is below is the promise: the address is not on a profile, not in
 * the directory, not on the admin's review screen, not in another member's export, and not in
 * anything a model can reach. What leaves is a distance in a bucket.
 */
public class LocationTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;
  private Browser ana;

  /** when set, every lookup fails as though the service were down */
  private static boolean down;
  /** what the atlas calls itself; changing it is how a test switches service */
  private static String called = "atlas";

  /** a geocoder that knows three places and has never heard of anywhere else */
  private static final Geocoder ATLAS = new Geocoder() {
    @Override
    public String name() {
      return called;
    }

    @Override
    public Point find(String query) throws Unavailable {
      if (down) {
        throw new Unavailable("the atlas is closed");
      }
      String where = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
      if (where.contains("hall")) {
        return new Point(51.5007, -0.1246, "The Hall");
      }
      if (where.contains("baker")) {
        return new Point(51.5238, -0.1586, "221B Baker Street");
      }
      if (where.contains("york")) {
        return new Point(53.9591, -1.0815, "York");
      }
      return null;
    }

    @Override
    public String describe() {
      return "a small atlas, for tests";
    }
  };

  @Before
  public void setUp() throws Exception {
    down = false;
    called = "atlas";
    TestServer.geocoding.set(ATLAS);
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"],\"units\":\"imperial\"}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com");
    ana = signIn("ana@example.com");
    approve("ana@example.com");
  }

  @After
  public void tearDown() {
    TestServer.geocoding.set(Geocoder.NONE);
    if (server != null) {
      server.close();
    }
    if (configs != null) {
      configs.delete();
    }
  }

  private PeopleStore people() {
    return server.auth.forDomain("example.org").people;
  }

  private long idOf(String email) throws Exception {
    return server.auth.forDomain("example.org").users.byEmail(email).id();
  }

  @Test
  public void anAddressIsLookedUpInTheBackgroundAndTurnsIntoAPoint() throws Exception {
    ana.submitToAndFollow("/self", Map.of("action", "address", "address", "221B Baker Street"));
    assertTrue("the queue is what does it, not the request", server.async.settle(5000));

    Home home = people().homeOf(idOf("ana@example.com"));
    assertEquals("221B Baker Street", home.address());
    assertTrue(home.hasPoint());
    assertTrue("it came from an address, so it is a doorstep rather than a town", home.isPrecise());
    assertEquals(51.5238, home.latitude(), 0.001);
  }

  @Test
  public void withNoAddressTheTownOnTheProfileIsUsedAndSaysSo() throws Exception {
    // somebody who will not give a street address is still worth counting, roughly, and a screen
    // reading a mix of the two has to be told which is which
    ana.submitToAndFollow("/self", Map.of("action", "profile", "display_name", "Ana",
        "location", "York"));
    assertTrue(server.async.settle(5000));

    Home home = people().homeOf(idOf("ana@example.com"));
    assertTrue(home.hasPoint());
    assertFalse(home.isPrecise());
    assertEquals(Home.CITY, home.precision());
  }

  @Test
  public void clearingTheAddressTakesThePointWithIt() throws Exception {
    ana.submitToAndFollow("/self", Map.of("action", "address", "address", "221B Baker Street"));
    assertTrue(server.async.settle(5000));
    assertTrue(people().homeOf(idOf("ana@example.com")).hasPoint());

    // a coordinate beside a deleted address is exactly the thing somebody was asking to be rid of
    Browser.Page cleared = ana.submitToAndFollow("/self", Map.of("action", "address",
        "address", ""));
    assertTrue(cleared.body(), cleared.contains("no longer counted"));
    assertTrue(server.async.settle(5000));
    Home home = people().homeOf(idOf("ana@example.com"));
    assertEquals("", home.address());
    assertFalse(home.hasPoint());
  }

  @Test
  public void anAddressNobodyCanFindIsMarkedUnfindableAndNothingAsksAgain() throws Exception {
    // The service answered and has never heard of it. Asking the same service the same question
    // tomorrow gets the same answer, so nothing does -- the first version of this left the row
    // looking exactly like one that had never been asked, and the sweep re-queued it every minute
    // forever, spending a slot each time to learn nothing.
    ana.submitToAndFollow("/self", Map.of("action", "address", "address", "nowhere at all"));
    assertTrue(server.async.settle(5000));
    Home home = people().homeOf(idOf("ana@example.com"));
    assertFalse(home.hasPoint());
    assertTrue(home.isUnfindable());
    assertFalse("and it is not the retrying kind of failure", home.isRetrying());
    assertEquals("atlas", home.placement().service());
    assertTrue(home.note(), home.note().contains("could not find"));
    assertTrue(ana.get("/self").contains("could not find"));

    assertTrue("nothing is due, so the sweep does nothing at all",
        people().dueForPlacement("atlas", System.currentTimeMillis(), 50).isEmpty());
  }

  @Test
  public void switchingServiceReopensEverythingTheOldOneCouldNotFind() throws Exception {
    // the usual reason somebody switches geocoder is precisely to place the addresses the old one
    // could not, and a state that survived the switch would defeat the whole point of it
    ana.submitToAndFollow("/self", Map.of("action", "address", "address", "nowhere at all"));
    assertTrue(server.async.settle(5000));
    assertTrue(people().homeOf(idOf("ana@example.com")).isUnfindable());

    called = "a better atlas";
    assertEquals("now due again, with nobody having pressed anything", 1,
        people().dueForPlacement("a better atlas", System.currentTimeMillis(), 50).size());
  }

  @Test
  public void aServiceThatCannotBeReachedIsRetriedOnAWideningSchedule() throws Exception {
    // this says nothing about the address, so it is a different state with a different answer
    down = true;
    ana.submitToAndFollow("/self", Map.of("action", "address", "address", "221B Baker Street"));
    long until = System.currentTimeMillis() + 10_000;
    while (!people().homeOf(idOf("ana@example.com")).isRetrying()
        && System.currentTimeMillis() < until) {
      Thread.sleep(20);
    }
    Home home = people().homeOf(idOf("ana@example.com"));
    assertTrue(home.placement().word(), home.isRetrying());
    assertFalse("nothing here is a claim about the address", home.isUnfindable());
    assertEquals("one episode is one attempt on the row, however many times the queue retried",
        1, home.placement().tries());
    assertNotNull("and a time to come back to it", home.placement().nextAt());

    // not due yet: fifteen minutes is the first wait, and the sweep must not spin on it
    assertTrue(people().dueForPlacement("atlas", System.currentTimeMillis(), 50).isEmpty());
    assertEquals(1, people().dueForPlacement("atlas",
        System.currentTimeMillis() + 16 * 60_000, 50).size());

    // and when the service comes back, the next sweep places it -- nobody has to re-enter
    // anything, which is the whole point of keeping the state on the row
    down = false;
    admin.submitToAndFollow("/admin/system/async", Map.of("action", "reopen"));
    assertTrue(server.async.settle(5000));
    assertTrue(people().homeOf(idOf("ana@example.com")).hasPoint());
  }

  @Test
  public void theWaitWidensAndThenStops() {
    long now = 0;
    assertEquals(15 * 60_000L, io.hearth.places.Placement.scheduleAfter(1, now).getTime());
    assertEquals(60 * 60_000L, io.hearth.places.Placement.scheduleAfter(2, now).getTime());
    assertEquals(240 * 60_000L, io.hearth.places.Placement.scheduleAfter(3, now).getTime());
    assertEquals("a day, and it never becomes never -- a service that comes back a week later is"
        + " picked up within a day of coming back",
        1440 * 60_000L, io.hearth.places.Placement.scheduleAfter(9, now).getTime());
  }

  @Test
  public void theAsyncScreenSaysWhichAddressesAreStuckAndWhy() throws Exception {
    ana.submitToAndFollow("/self", Map.of("action", "address", "address", "nowhere at all"));
    assertTrue(server.async.settle(5000));

    Browser.Page page = admin.get("/admin/system/async");
    assertTrue(page.body(), page.contains("no such address"));
    assertTrue("and it says why nothing is retrying them", page.contains("same answer"));

    // the button that says try them anyway
    Browser.Page again = admin.submitToAndFollow("/admin/system/async",
        Map.of("action", "reopen"));
    assertTrue(again.body(), again.contains("waiting again"));
    assertTrue(server.async.settle(5000));
    // still unfindable, because the atlas still has not heard of it -- but it was asked
    assertTrue(people().homeOf(idOf("ana@example.com")).isUnfindable());
  }

  @Test
  public void nobodyCanReadSomebodyElsesAddress() throws Exception {
    ana.submitToAndFollow("/self", Map.of("action", "profile", "display_name", "Ana",
        "location", "York"));
    ana.submitToAndFollow("/self", Map.of("action", "address", "address", "221B Baker Street"));
    assertTrue(server.async.settle(5000));
    long anaId = idOf("ana@example.com");

    // her own page shows it, because it is hers
    assertTrue(ana.get("/self").contains("221B Baker Street"));

    // and nowhere else does. The admin review screen is the strongest case: it is the screen that
    // exists to show an administrator everything about somebody.
    for (String page : new String[]{"/members", "/members/" + anaId,
        "/admin/people/review/" + anaId, "/admin/people", "/admin/people/list"}) {
      Browser.Page seen = admin.get(page);
      assertFalse(page + " shows her address", seen.contains("221B"));
      assertFalse(page + " shows her address", seen.contains("Baker Street"));
    }
    // the town she published is a different thing and is still public
    assertTrue(admin.get("/members/" + anaId).contains("York"));

    // an export of somebody else does not carry it either
    Browser.Page export = admin.get("/admin/people/export/" + anaId);
    assertFalse(export.body(), export.contains("221B"));

    // and a profile record cannot carry it at all -- the query that builds one does not name the
    // column, which is what makes every line above true by construction rather than by care
    ProfileRecord profile = people().profileOf(anaId);
    assertFalse(profile.toString().contains("221B"));
  }

  @Test
  public void yourOwnExportHasIt() throws Exception {
    // the policy promises to show somebody everything held about them, and the one field nobody
    // else can read is the one somebody is most entitled to a copy of
    ana.submitToAndFollow("/self", Map.of("action", "address", "address", "221B Baker Street"));
    assertTrue(server.async.settle(5000));
    Browser.Page export = ana.get("/self?tab=data&download=export");
    assertTrue(export.body(), export.contains("221B Baker Street"));
    assertTrue("and it says what it is for", export.contains("chart of"));
  }

  @Test
  public void erasingSomebodyErasesWhereTheyLive() throws Exception {
    ana.submitToAndFollow("/self", Map.of("action", "address", "address", "221B Baker Street"));
    assertTrue(server.async.settle(5000));
    long anaId = idOf("ana@example.com");
    admin.submitToAndFollow("/admin/people", Map.of("action", "erase",
        "user", Long.toString(anaId), "confirm", "delete"));
    assertEquals("", people().homeOf(anaId).address());
    assertFalse(people().homeOf(anaId).hasPoint());
  }

  @Test
  public void theEventFormShowsHowFarPeopleWouldComeAndNamesNobody() throws Exception {
    ana.submitToAndFollow("/self", Map.of("action", "address", "address", "221B Baker Street"));
    admin.submitToAndFollow("/self", Map.of("action", "address", "address", "York"));
    assertTrue(server.async.settle(5000));

    admin.submitToAndFollow("/admin/places", Map.of("action", "save",
        "type_slug", io.hearth.places.Places.DEFAULT_TYPE,
        "name", "The Hall", "address", "The Hall, London"));
    assertTrue("a place is placed by the queue too", server.async.settle(5000));
    io.hearth.places.Places.Place hall =
        server.auth.forDomain("example.org").places.all(10).get(0);
    assertNotNull("the lookup wrote the coordinates back", hall.latitude());

    admin.submitToAndFollow("/admin/calendar", Map.of("action", "save", "title", "Supper",
        "starts_on", java.time.LocalDate.now().plusDays(7).toString(),
        "place", Long.toString(hall.id()), "published", "on"));
    long eventId = server.auth.forDomain("example.org").calendar
        .upcoming(java.time.LocalDate.now(), 10).get(0).id();

    Browser.Page form = admin.get("/admin/calendar/edit/" + eventId);
    assertTrue(form.body(), form.contains("How far people would come"));
    assertTrue("miles, because this community said imperial", form.contains("mi"));
    assertTrue("it says how many of the members it is actually describing",
        form.contains("member(s) counted"));
    assertTrue(form.contains("counts"));
    // the whole promise, on the one screen that could break it
    assertFalse(form.contains("221B"));
    assertFalse(form.contains("ana@example.com"));
  }

  @Test
  public void distancesAreBucketsAndTheMathsIsSane() {
    // London to York is about 175 miles; the point is that a bucket boundary is not crossed by
    // rounding, not that the earth is an exact sphere
    double km = Distances.kilometres(51.5007, -0.1246, 53.9591, -1.0815);
    assertTrue(String.valueOf(km), km > 270 && km < 290);
    assertEquals(0, Distances.kilometres(51.5, -0.1, 51.5, -0.1), 0.0001);

    Map<Long, double[]> points = Map.of(
        1L, new double[]{51.5007, -0.1246, 1},
        2L, new double[]{51.5238, -0.1586, 1},
        3L, new double[]{53.9591, -1.0815, 0});
    Distances.Travel travel = Distances.from(points, 51.5007, -0.1246, 10, false);
    assertEquals(3, travel.placed());
    assertEquals("two doorsteps and a town", 2, travel.precise());
    assertEquals("and seven people who said nothing are not invented", 7, travel.unplaced());
    assertEquals("km", travel.unit());
    assertEquals("somebody at the venue is in the nearest bucket", 1,
        travel.buckets().get(0).count());
    assertEquals("and York is in the last one", 1,
        travel.buckets().get(travel.buckets().size() - 1).count());
    assertTrue("nothing here can name anybody",
        travel.buckets().stream().noneMatch(bucket -> bucket.label().contains("@")));
  }

  @Test
  public void nothingIsPlacedWhenTheGeocoderIsOff() throws Exception {
    // it is one word to switch off, and switching it off means no outbound request rather than a
    // request that quietly fails
    TestServer.geocoding.set(Geocoder.NONE);
    Configs quiet = Configs.dir().domain("quiet.org",
        "{\"name\":\"Quiet\",\"admin_emails\":[\"boss@quiet.org\"]}");
    try (TestServer off = TestServer.ofConfigs(quiet.file())) {
      Browser bob = new Browser(off.port, "quiet.org");
      bob.get("/register");
      bob.submit(Map.of("email", "boss@quiet.org"));
      bob.submit(Map.of("code", off.mail().lastCodeFor("boss@quiet.org")));
      Browser.Page page = bob.get("/self");
      assertFalse("the whole section is absent rather than a box that does nothing",
          page.contains("nobody sees this"));

      long id = off.auth.forDomain("quiet.org").users.byEmail("boss@quiet.org").id();
      assertNull(off.auth.forDomain("quiet.org").people.homeOf(id).latitude());
    } finally {
      quiet.delete();
    }
  }

  private void approve(String email) throws Exception {
    long id = idOf(email);
    admin.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(id)));
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
