package io.hearth.board;

import io.hearth.auth.Permission;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Comments, wherever they are.
 *
 * One table and one set of rules under three different things, which is the whole reason to have
 * done it this way: the third one cannot behave slightly differently from the first two, because
 * there is no third implementation for it to differ in.
 */
public class CommentsTests {
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

  private Board board() {
    return server.auth.forDomain("example.org").board;
  }

  // ---- events ------------------------------------------------------------------------------

  @Test
  public void anEventCanBeTalkedAbout() throws Exception {
    long id = event("Supper club");
    Browser ana = member("ana@example.com");

    ana.get("/events/" + id);
    ana.submitToAndFollow("/events", Map.of("action", "comment", "event", Long.toString(id),
        "body", "Can I bring my sister?"));

    assertEquals(1, board().commentCount(Subject.event(id)));
    Browser.Page page = ana.get("/events/" + id);
    assertTrue(page.contains("Can I bring my sister?"));
    assertTrue("and it updates itself", page.contains("data-live-region=\"comments\""));
  }

  @Test
  public void aCommentOnOneEventIsNotOnAnother() throws Exception {
    long one = event("One");
    long two = event("Two");
    Browser ana = member("ana@example.com");
    ana.get("/events/" + one);
    ana.submitToAndFollow("/events",
        Map.of("action", "comment", "event", Long.toString(one), "body", "only here"));

    assertEquals(1, board().commentCount(Subject.event(one)));
    assertEquals(0, board().commentCount(Subject.event(two)));
    assertFalse(ana.get("/events/" + two).contains("only here"));
  }

  // ---- places ------------------------------------------------------------------------------

  @Test
  public void aPlaceCanBeTalkedAbout() throws Exception {
    long id = place("the-oak", "The Oak");
    Browser ana = member("ana@example.com");

    ana.get("/places/venue/the-oak");
    ana.submitToAndFollow("/places", Map.of("action", "comment", "place", Long.toString(id),
        "body", "The back room is the quiet one."));

    assertEquals(1, board().commentCount(Subject.place(id)));
    assertTrue(ana.get("/places/venue/the-oak").contains("The back room is the quiet one."));
  }

  // ---- who may take one down -----------------------------------------------------------------

  @Test
  public void anAuthorCanAlwaysTakeBackTheirOwn() throws Exception {
    long id = event("Supper club");
    Browser ana = member("ana@example.com");
    ana.get("/events/" + id);
    ana.submitToAndFollow("/events",
        Map.of("action", "comment", "event", Long.toString(id), "body", "oops"));
    long commentId = board().thread(Subject.event(id)).get(0).id();

    ana.submitToAndFollow("/events", Map.of("action", "remove_comment",
        "event", Long.toString(id), "comment", Long.toString(commentId)));
    Board.Comment after = board().commentById(commentId);
    assertNotNull("the row stays, so replies underneath do not become orphans", after);
    assertTrue(after.removed());
  }

  @Test
  public void moderatingIsPerSection() throws Exception {
    long eventId = event("Supper club");
    long placeId = place("the-oak", "The Oak");
    Browser ana = member("ana@example.com");
    ana.get("/events/" + eventId);
    ana.submitToAndFollow("/events",
        Map.of("action", "comment", "event", Long.toString(eventId), "body", "on the event"));
    ana.get("/places/venue/the-oak");
    ana.submitToAndFollow("/places",
        Map.of("action", "comment", "place", Long.toString(placeId), "body", "on the place"));
    long onEvent = board().thread(Subject.event(eventId)).get(0).id();
    long onPlace = board().thread(Subject.place(placeId)).get(0).id();

    // somebody who keeps the calendar tidy is not automatically somebody who keeps the address
    // book tidy: the permission is per section, which is the whole point of having three
    Browser mild = member("mild@example.com");
    give("mild@example.com", "eventkeeper", Permission.calendar_moderate);

    mild.submitToAndFollow("/places", Map.of("action", "remove_comment",
        "place", Long.toString(placeId), "comment", Long.toString(onPlace)));
    assertFalse("not theirs to take down", board().commentById(onPlace).removed());

    mild.submitToAndFollow("/events", Map.of("action", "remove_comment",
        "event", Long.toString(eventId), "comment", Long.toString(onEvent)));
    assertTrue("and this one is", board().commentById(onEvent).removed());
  }

  @Test
  public void aCommentIdFromAnotherPageIsNotThisPagesToTouch() throws Exception {
    long eventId = event("Supper club");
    long placeId = place("the-oak", "The Oak");
    Browser ana = member("ana@example.com");
    ana.get("/events/" + eventId);
    ana.submitToAndFollow("/events",
        Map.of("action", "comment", "event", Long.toString(eventId), "body", "on the event"));
    long onEvent = board().thread(Subject.event(eventId)).get(0).id();

    // the admin holds every permission, and the subject still has to match: a page that acted on
    // an id it was handed would let one section moderate another
    admin.submitToAndFollow("/places", Map.of("action", "remove_comment",
        "place", Long.toString(placeId), "comment", Long.toString(onEvent)));
    assertFalse(board().commentById(onEvent).removed());
  }

  @Test
  public void keepingASectionImpliesKeepingItTidyButNotTheOtherWay() {
    assertTrue(Permission.calendar_write.implies().contains(Permission.calendar_moderate));
    assertTrue(Permission.places_write.implies().contains(Permission.places_moderate));
    assertFalse("a community can hand somebody the moderating without the editing",
        Permission.calendar_moderate.implies().contains(Permission.calendar_write));
  }

  // ---- what a member typed --------------------------------------------------------------------

  @Test
  public void whatSomebodyWritesIsNeverMarkupOnSomebodyElsesScreen() throws Exception {
    long id = event("Supper club");
    Browser ana = member("ana@example.com");
    ana.get("/events/" + id);
    ana.submitToAndFollow("/events", Map.of("action", "comment", "event", Long.toString(id),
        "body", "<script>alert(1)</script>"));
    ana.submitToAndFollow("/events", Map.of("action", "comment", "event", Long.toString(id),
        "body", "and **bold** works"));

    Browser bo = member("bo@example.com");
    Browser.Page page = bo.get("/events/" + id);
    assertFalse(page.contains("<script>alert"));
    // a line that starts with raw HTML is one commonmark block, so the markdown on it is not
    // markdown -- which is why this is a second comment rather than a second clause
    assertTrue("and markdown still works", page.contains("<strong>bold</strong>"));
  }

  @Test
  public void anEmptyCommentIsNotAComment() throws Exception {
    long id = event("Supper club");
    Browser ana = member("ana@example.com");
    ana.get("/events/" + id);
    ana.submitToAndFollow("/events",
        Map.of("action", "comment", "event", Long.toString(id), "body", "   "));
    assertEquals(0, board().commentCount(Subject.event(id)));
  }

  // ---- clumping by age --------------------------------------------------------------------------

  @Test
  public void aShortThreadIsJustAThread() {
    List<Board.Comment> comments = new ArrayList<>();
    for (int k = 0; k < 5; k++) {
      comments.add(comment(k, 0, daysAgo(k)));
    }
    List<CommentGroups.Group> groups = CommentGroups.of(comments, System.currentTimeMillis());
    assertEquals("three labelled boxes for five comments would be a worse thread", 1, groups.size());
    assertFalse(groups.get(0).collapsed());
    assertEquals("", groups.get(0).label());
  }

  @Test
  public void aLongThreadFoldsTheOldOnesAway() {
    List<Board.Comment> comments = new ArrayList<>();
    for (int k = 0; k < 40; k++) {
      // one a month going back, newest last, which is how a thread reads
      comments.add(comment(k, 0, daysAgo(40 - k) * 1));
    }
    List<CommentGroups.Group> groups = CommentGroups.of(comments, System.currentTimeMillis());
    assertTrue(groups.size() > 2);
    assertTrue("the oldest arrives folded", groups.get(0).collapsed());
    assertFalse("and the recent conversation is open", groups.get(groups.size() - 1).collapsed());
    int total = 0;
    for (CommentGroups.Group group : groups) {
      total += group.count();
    }
    assertEquals("nothing is lost by folding it", comments.size(), total);
  }

  @Test
  public void aReplyGoesWhereItsQuestionWent() {
    // bucketing each comment by its own timestamp would put somebody answering a two-year-old
    // question under "this week", with no question above it
    List<Board.Comment> comments = new ArrayList<>();
    for (int k = 0; k < 25; k++) {
      comments.add(comment(k, 0, daysAgo(400)));
    }
    comments.add(comment(99, 1, daysAgo(0)));
    List<CommentGroups.Group> groups = CommentGroups.of(comments, System.currentTimeMillis());
    CommentGroups.Group last = groups.get(groups.size() - 1);
    assertTrue("the reply is in its parent's clump",
        last.comments().stream().anyMatch(c -> c.id() == 99));
    assertTrue(last.comments().stream().anyMatch(c -> c.depth() == 0));
  }

  @Test
  public void aClumpIsCalledSomethingAPersonWouldSay() {
    ZoneId zone = ZoneId.systemDefault();
    LocalDate today = LocalDate.of(2026, 8, 3);
    assertEquals("Today", CommentGroups.labelFor(on(2026, 8, 3), today, zone));
    assertEquals("Yesterday", CommentGroups.labelFor(on(2026, 8, 2), today, zone));
    assertEquals("This week", CommentGroups.labelFor(on(2026, 7, 30), today, zone));
    assertEquals("March", CommentGroups.labelFor(on(2026, 3, 4), today, zone));
    assertEquals("March 2025", CommentGroups.labelFor(on(2025, 3, 4), today, zone));
    assertEquals("Earlier", CommentGroups.labelFor(null, today, zone));
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private static Timestamp on(int year, int month, int day) {
    return Timestamp.valueOf(LocalDate.of(year, month, day).atStartOfDay());
  }

  private static long daysAgo(int days) {
    return System.currentTimeMillis() - days * 86_400_000L;
  }

  private static Board.Comment comment(long id, int depth, long at) {
    return new Board.Comment(id, Subject.post(1), depth == 0 ? null : 1L,
        String.format("%04d", id), depth, 1, "a@example.com", "words",
        new Timestamp(at), null, null);
  }

  private long event(String title) throws Exception {
    admin.submitToAndFollow("/admin/calendar", Map.of("action", "save", "title", title,
        "starts_on", "2030-04-09", "published", "on"));
    return server.auth.forDomain("example.org").calendar.all(10).get(0).id();
  }

  private long place(String slug, String name) throws Exception {
    admin.submitToAndFollow("/admin/places/kinds", Map.of("action", "save", "slug", "venue",
        "label", "Venue", "plural", "Venues", "published", "on"));
    admin.submitToAndFollow("/admin/places", Map.of("action", "save", "type_slug", "venue",
        "slug", slug, "name", name, "published", "on"));
    return server.auth.forDomain("example.org").places.bySlug("venue", slug).id();
  }

  private void give(String email, String role, Permission... permissions) throws Exception {
    io.hearth.auth.Accounts accounts = server.auth.forDomain("example.org");
    accounts.roleDefs.save(role, role, "", EnumSet.copyOf(List.of(permissions)), "blue", null);
    accounts.roles.grant(accounts.users.byEmail(email).id(), role, null);
  }

  private Browser member(String email) throws Exception {
    Browser browser = signIn(email);
    admin.submitToAndFollow("/admin/people", Map.of("action", "approve",
        "user", Long.toString(server.auth.forDomain("example.org").users.byEmail(email).id())));
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
