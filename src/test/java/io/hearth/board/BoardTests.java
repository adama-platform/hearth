package io.hearth.board;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The discussion board.
 *
 * The three things that make it work rather than merely exist: threads read in the right order
 * however deep they go, posts age out so the feed stays a conversation, and joining a conversation
 * is what makes you a watcher -- because a board where you have to remember to subscribe is one
 * where people miss the reply to their own comment.
 */
public class BoardTests {
  private Configs configs;
  private TestServer server;
  private Browser ana;
  private Browser ben;
  private Browser cass;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"ana@example.com\",\"ben@example.com\","
            + "\"cass@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    ana = signIn("ana@example.com");
    ben = signIn("ben@example.com");
    cass = signIn("cass@example.com");
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
    // and a name, because that is what the board prints. A member never sees another member's
    // address here, which is the same rule the directory has always followed.
    String name = Character.toUpperCase(email.charAt(0)) + email.substring(1, email.indexOf('@'));
    browser.get("/self");
    browser.submitTo("/self", Map.of("action", "profile", "display_name", name,
        "headline", "", "about", "", "location", "", "links", ""));
    return browser;
  }

  private Board board() {
    return server.auth.forDomain("example.org").board;
  }

  private Inbox inbox() {
    return server.auth.forDomain("example.org").inbox;
  }

  private long idOf(String email) throws Exception {
    return server.auth.forDomain("example.org").users.byEmail(email).id();
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
    who.submitTo("/board", form);
  }

  // ---- posting and reading -----------------------------------------------------------------------

  @Test
  public void aPostShowsUpInTheFeedAndOnItsOwnPage() throws Exception {
    long id = post(ana, "Where should we meet?", "The back room is small.");

    Browser.Page feed = ben.get("/board");
    assertEquals(200, feed.status());
    assertTrue(feed.contains("Where should we meet?"));
    assertTrue("and links to the thread", feed.contains("/board/" + id));

    Browser.Page thread = ben.get("/board/" + id);
    assertEquals(200, thread.status());
    assertTrue("the body is rendered markdown", thread.contains("The back room is small."));
  }

  @Test
  public void theBoardNeedsYouSignedIn() throws Exception {
    post(ana, "Private conversation", "for members");
    Browser stranger = new Browser(server.port, "example.org");
    Browser.Page page = stranger.get("/board");
    assertEquals(303, page.status());
    assertTrue(page.location(), page.location().startsWith("/login"));
    assertTrue("and comes back afterwards", page.location().contains("next="));
  }

  @Test
  public void theFeedSortsByWhatMovedMostRecently() throws Exception {
    long first = post(ana, "First post", "one");
    long second = post(ana, "Second post", "two");
    reply(ben, first, null, "bumping the first one");

    List<Board.Post> feed = board().feed(10);
    assertEquals("a reply moves a thread back to the top", first, feed.get(0).id());
    assertEquals(second, feed.get(1).id());
  }

  // ---- threading -----------------------------------------------------------------------------------

  @Test
  public void repliesReadInTheRightOrderHoweverDeepTheyGo() throws Exception {
    // the whole point of the path column: one ordered query returns the tree already in reading
    // order, rather than a query per level
    long postId = post(ana, "A question", "what do you think?");
    reply(ben, postId, null, "top level one");
    long topOne = board().thread(postId).get(0).id();
    reply(cass, postId, topOne, "under one");
    long underOne = board().thread(postId).get(1).id();
    reply(ana, postId, underOne, "under under one");
    reply(ben, postId, null, "top level two");

    List<Board.Comment> thread = board().thread(postId);
    assertEquals(4, thread.size());
    assertEquals("top level one", thread.get(0).body());
    assertEquals("under one", thread.get(1).body());
    assertEquals("under under one", thread.get(2).body());
    assertEquals("and a later top level reply comes after the whole branch above it",
        "top level two", thread.get(3).body());

    assertEquals(0, thread.get(0).depth());
    assertEquals(1, thread.get(1).depth());
    assertEquals(2, thread.get(2).depth());
    assertEquals(0, thread.get(3).depth());
  }

  @Test
  public void aStaircaseIsCappedRatherThanAllowedToGoOnForever() throws Exception {
    long postId = post(ana, "Deep", "start");
    Long parent = null;
    for (int k = 0; k <= Board.MAX_DEPTH + 3; k++) {
      reply(ben, postId, parent, "level " + k);
      List<Board.Comment> thread = board().thread(postId);
      parent = thread.get(thread.size() - 1).id();
    }
    int deepest = board().thread(postId).stream().mapToInt(Board.Comment::depth).max().orElse(0);
    assertEquals("past the cap a reply attaches at the cap rather than nesting further",
        Board.MAX_DEPTH, deepest);
    assertEquals("and nothing is lost", Board.MAX_DEPTH + 4, board().thread(postId).size());
  }

  @Test
  public void aRemovedCommentKeepsItsPlaceSoRepliesAreNotOrphaned() throws Exception {
    long postId = post(ana, "A question", "?");
    reply(ben, postId, null, "the parent");
    long parentId = board().thread(postId).get(0).id();
    reply(cass, postId, parentId, "the child");

    board().removeComment(parentId, idOf("ana@example.com"));

    List<Board.Comment> thread = board().thread(postId);
    assertEquals("the row stays", 2, thread.size());
    assertTrue(thread.get(0).removed());
    assertEquals("and the child is still under it", "the child", thread.get(1).body());

    Browser.Page page = ana.get("/board/" + postId);
    assertTrue("the page says so rather than showing the words", page.contains("removed"));
    assertFalse(page.contains("the parent"));
  }

  @Test
  public void theCommentCountFollowsTheThread() throws Exception {
    long postId = post(ana, "Counting", "how many");
    assertEquals(0, board().postById(postId).commentCount());
    reply(ben, postId, null, "one");
    reply(cass, postId, null, "two");
    assertEquals(2, board().postById(postId).commentCount());
  }

  // ---- expiry ---------------------------------------------------------------------------------------

  @Test
  public void postsExpireByDefault() throws Exception {
    long postId = post(ana, "Temporary", "this ages out");
    Board.Post post = board().postById(postId);
    assertTrue("the default is that a thread has a life", post.expires());
    assertTrue(post.daysLeft(System.currentTimeMillis()) > 50);
  }

  @Test
  public void anExpiredPostLeavesTheFeedAndItsPageSaysWhy() throws Exception {
    long postId = post(ana, "Old news", "this has aged out");
    // push it into the past the way time would
    server.auth.forDomain("example.org").board.setExpiry(postId, -1, null);

    assertTrue("gone from the feed", board().feed(10).stream().noneMatch(p -> p.id() == postId));
    assertFalse(ana.get("/board").contains("Old news"));

    Browser.Page page = ana.get("/board/" + postId);
    assertEquals(404, page.status());
    assertTrue("and says what happened rather than pretending it never existed",
        page.contains("aged out"));
  }

  @Test
  public void aBoardCanBeToldToKeepEverything() throws Exception {
    Configs forever = Configs.dir().domain("keep.test",
        "{\"name\":\"Keep\",\"admin_emails\":[\"boss@example.com\"],\"board\":{\"expiry-days\":0}}");
    try (TestServer other = TestServer.ofConfigs(forever.file())) {
      Browser boss = new Browser(other.port, "keep.test");
      boss.get("/register");
      boss.submit(Map.of("email", "boss@example.com"));
      boss.submit(Map.of("code", other.mail().lastCodeFor("boss@example.com")));
      boss.get("/board");
      Browser.Page done = boss.submitTo("/board",
          Map.of("action", "post", "title", "Permanent", "body", "kept"));
      long id = Long.parseLong(done.location().substring("/board/".length()));
      assertFalse("nothing expires here",
          other.auth.forDomain("keep.test").board.postById(id).expires());
    } finally {
      forever.delete();
    }
  }

  @Test
  public void aRemovedPostIsGoneFromTheFeedButTheRepliesSurvive() throws Exception {
    long postId = post(ana, "Regrettable", "oops");
    reply(ben, postId, null, "somebody else's words");
    board().removePost(postId, idOf("ana@example.com"));

    assertTrue(board().feed(10).stream().noneMatch(p -> p.id() == postId));
    assertEquals("the replies are other people's, and they stay",
        1, board().thread(postId).size());
  }

  // ---- watchers -------------------------------------------------------------------------------------

  @Test
  public void postingMakesYouAWatcher() throws Exception {
    long postId = post(ana, "Mine", "hello");
    assertTrue("somebody who posts and is not told about replies has been given a worse megaphone",
        board().postById(postId).isWatchedBy(idOf("ana@example.com")));
  }

  @Test
  public void replyingMakesYouAWatcher() throws Exception {
    long postId = post(ana, "A question", "?");
    assertFalse(board().postById(postId).isWatchedBy(idOf("ben@example.com")));
    reply(ben, postId, null, "an answer");
    assertTrue("there is no subscribe button, because people forget to press it",
        board().postById(postId).isWatchedBy(idOf("ben@example.com")));
  }

  @Test
  public void watchingCanBeStopped() throws Exception {
    long postId = post(ana, "Noisy", "lots of replies coming");
    reply(ben, postId, null, "one");
    assertTrue(board().postById(postId).isWatchedBy(idOf("ben@example.com")));

    ben.get("/board/" + postId);
    ben.submitTo("/board", Map.of("action", "unwatch", "post", Long.toString(postId)));
    assertFalse(board().postById(postId).isWatchedBy(idOf("ben@example.com")));
  }

  @Test
  public void theWatcherListPacksAndUnpacksExactly() {
    assertEquals("[]", Board.packWatchers(java.util.Set.of()));
    java.util.Set<Long> watchers = new java.util.LinkedHashSet<>(List.of(3L, 1L, 2L));
    assertEquals(watchers, Board.unpackWatchers(Board.packWatchers(watchers)));
    assertTrue("garbage is an empty list, not a broken thread",
        Board.unpackWatchers("not json").isEmpty());
    assertTrue(Board.unpackWatchers(null).isEmpty());
  }

  // ---- the inbox ------------------------------------------------------------------------------------

  @Test
  public void everybodyWatchingIsToldExceptThePersonWhoDidIt() throws Exception {
    // a board that tells you about your own comment is one whose unread count means nothing
    long postId = post(ana, "A question", "?");
    reply(ben, postId, null, "an answer");
    reply(cass, postId, null, "another answer");

    assertEquals("ana hears about both replies", 2, inbox().unreadCount(idOf("ana@example.com")));
    assertEquals("ben hears about cass, not himself", 1, inbox().unreadCount(idOf("ben@example.com")));
    assertEquals("cass arrived last and hears nothing yet",
        0, inbox().unreadCount(idOf("cass@example.com")));
  }

  @Test
  public void aNotificationSaysWhatHappenedAtTheTime() throws Exception {
    long postId = post(ana, "Where should we meet?", "?");
    reply(ben, postId, null, "the back room");

    List<Inbox.Note> notes = inbox().forUser(idOf("ana@example.com"), 10);
    assertEquals(1, notes.size());
    assertTrue(notes.get(0).text(), notes.get(0).text().contains("Ben"));
    assertTrue(notes.get(0).text().contains("Where should we meet?"));
    assertEquals("what it calls them is a name; a notification is not a way to learn an address",
        "Ben", notes.get(0).actorName());
    assertFalse(notes.get(0).text().contains("@example.com"));
    assertTrue(notes.get(0).unread());
  }

  @Test
  public void aNotificationOutlivesTheCommentItIsAbout() throws Exception {
    // resolving the text on read would leave a blank line where a removed comment used to be
    long postId = post(ana, "A question", "?");
    reply(ben, postId, null, "an answer");
    long commentId = board().thread(postId).get(0).id();
    board().removeComment(commentId, idOf("ana@example.com"));

    List<Inbox.Note> notes = inbox().forUser(idOf("ana@example.com"), 10);
    assertEquals(1, notes.size());
    assertTrue(notes.get(0).text().contains("replied"));
  }

  @Test
  public void openingTheInboxMarksItRead() throws Exception {
    long postId = post(ana, "A question", "?");
    reply(ben, postId, null, "an answer");
    assertEquals(1, inbox().unreadCount(idOf("ana@example.com")));

    assertEquals(1, inbox().markAllRead(idOf("ana@example.com")));
    assertEquals(0, inbox().unreadCount(idOf("ana@example.com")));
    assertEquals("and the notification is still there to read",
        1, inbox().forUser(idOf("ana@example.com"), 10).size());
  }

  @Test
  public void notificationsExpireWithTheThreadTheyAreAbout() throws Exception {
    // an inbox that accumulates forever is one nobody opens
    long userId = idOf("ana@example.com");
    inbox().add(userId, Inbox.Kind.reply, null, null, "ben@example.com", "old news",
        new Timestamp(System.currentTimeMillis() - 1000));
    inbox().add(userId, Inbox.Kind.reply, null, null, "ben@example.com", "still current",
        new Timestamp(System.currentTimeMillis() + 86_400_000L));

    assertEquals("an expired one is not in the inbox", 1, inbox().forUser(userId, 10).size());
    assertEquals(1, inbox().unreadCount(userId));
    assertEquals("and the sweep clears it out", 1, inbox().sweep());
  }

  @Test
  public void aNotificationInheritsThePostsExpiry() throws Exception {
    long postId = post(ana, "Temporary", "ages out");
    reply(ben, postId, null, "a reply");
    Inbox.Note note = inbox().forUser(idOf("ana@example.com"), 10).get(0);
    assertNotNull("it should not outlive the conversation it is about", note.expiresAt());
    assertEquals(board().postById(postId).expiresAt(), note.expiresAt());
  }

  // ---- locking ---------------------------------------------------------------------------------------

  @Test
  public void alockedThreadTakesNoMoreReplies() throws Exception {
    long postId = post(ana, "Settled", "we decided");
    board().setFlags(postId, false, true, idOf("ana@example.com"));

    reply(ben, postId, null, "actually...");
    assertEquals("a locked thread is locked", 0, board().thread(postId).size());
    assertTrue(ana.get("/board/" + postId).contains("locked"));
  }

  @Test
  public void aPinnedPostLeadsTheFeed() throws Exception {
    long old = post(ana, "Pinned announcement", "read this");
    post(ana, "Newer chatter", "later");
    board().setFlags(old, true, false, idOf("ana@example.com"));
    assertEquals("pinned beats recent", old, board().feed(10).get(0).id());
  }

  // ---- fixing and taking back ------------------------------------------------------------------

  @Test
  public void theNestedReplyFormCarriesThePostRatherThanTheComment() throws Exception {
    // two posts first, so the comment id and the post id are different numbers -- with one of
    // each they are both 1, and the assertion below would pass against the bug it exists to catch
    post(ana, "Something else", "so the ids diverge");
    long id = post(ana, "Where should we meet?", "The back room is small.");
    reply(ben, id, null, "The library has a room.");
    long commentId = board().thread(id).get(0).id();
    assertTrue("the fixture only means something if the ids differ", commentId != id);

    String html = ben.get("/board/" + id).body();
    assertTrue("the reply-to-a-reply form has to name the post, or it looks up nothing",
        html.contains("name=\"post\" value=\"" + id + "\""));
    assertFalse("and must never carry the comment id in the post field",
        html.contains("name=\"post\" value=\"" + commentId + "\""));
    assertTrue(html.contains("name=\"parent\" value=\"" + commentId + "\""));
  }

  @Test
  public void repliesToAReplyLandUnderIt() throws Exception {
    long id = post(ana, "Where should we meet?", "The back room is small.");
    reply(ben, id, null, "The library has a room.");
    long commentId = board().thread(id).get(0).id();
    reply(cass, id, commentId, "Is it free?");

    List<Board.Comment> thread = board().thread(id);
    assertEquals(2, thread.size());
    assertEquals("it nested rather than landing at the top level", 1, thread.get(1).depth());
    assertEquals(Long.valueOf(commentId), thread.get(1).parentId());
  }

  @Test
  public void anAuthorCanFixTheirOwnPostAndItSaysItWasEdited() throws Exception {
    long id = post(ana, "Where should we meet?", "The back rom is small.");
    ana.get("/board/" + id);
    ana.submitTo("/board", Map.of("action", "edit_post", "post", Long.toString(id),
        "title", "Where should we meet?", "body", "The back room is small."));

    assertTrue(board().postById(id).body().contains("back room is small"));
    assertTrue("people replied to what it said before", board().postById(id).edited());
    assertTrue(ben.get("/board/" + id).contains("edited"));
  }

  @Test
  public void nobodyElseCanEditYourPost() throws Exception {
    long id = post(ana, "Where should we meet?", "The back room is small.");
    ben.get("/board/" + id);
    ben.submitTo("/board", Map.of("action", "edit_post", "post", Long.toString(id),
        "title", "Something else entirely", "body", "words ana did not write"));

    assertEquals("Where should we meet?", board().postById(id).title());
    assertFalse(board().postById(id).edited());
  }

  @Test
  public void anAuthorCanFixTheirOwnComment() throws Exception {
    long id = post(ana, "Where should we meet?", "The back room is small.");
    reply(ben, id, null, "The libary has a room.");
    long commentId = board().thread(id).get(0).id();

    ben.get("/board/" + id);
    ben.submitTo("/board", Map.of("action", "edit_comment", "comment", Long.toString(commentId),
        "body", "The library has a room."));
    assertTrue(board().commentById(commentId).body().contains("library"));
    assertTrue(board().commentById(commentId).edited());
    assertTrue("and the cached thread shows the fix at once",
        ana.get("/board/" + id).contains("The library has a room."));
  }

  @Test
  public void nobodyElseCanEditYourComment() throws Exception {
    long id = post(ana, "Where should we meet?", "The back room is small.");
    reply(ben, id, null, "The library has a room.");
    long commentId = board().thread(id).get(0).id();

    cass.get("/board/" + id);
    cass.submitTo("/board", Map.of("action", "edit_comment", "comment", Long.toString(commentId),
        "body", "words ben did not write"));
    assertTrue(board().commentById(commentId).body().contains("library"));
  }

  @Test
  public void anAuthorCanTakeBackTheirOwnComment() throws Exception {
    long id = post(ana, "Where should we meet?", "The back room is small.");
    reply(ben, id, null, "Something regrettable.");
    long commentId = board().thread(id).get(0).id();
    reply(cass, id, commentId, "Answering that.");

    ben.get("/board/" + id);
    ben.submitTo("/board", Map.of("action", "remove_comment", "comment", Long.toString(commentId)));

    assertTrue(board().commentById(commentId).removed());
    assertEquals("and the reply underneath is still there", 2, board().thread(id).size());
    assertFalse(ana.get("/board/" + id).contains("Something regrettable."));
    assertTrue(ana.get("/board/" + id).contains("Answering that."));
  }

  // ---- moderation ------------------------------------------------------------------------------

  @Test
  public void anAdminCanPinAndUnpinFromTheAdminScreen() throws Exception {
    long id = post(ana, "Read this first", "House rules.");

    ben.submitToAndFollow("/admin/board", Map.of("action", "pin", "id", Long.toString(id)));
    assertTrue(board().postById(id).pinned());
    ben.submitToAndFollow("/admin/board", Map.of("action", "pin", "id", Long.toString(id)));
    assertFalse(board().postById(id).pinned());
  }

  @Test
  public void aLockedThreadStaysReadableAndTakesNoMoreReplies() throws Exception {
    long id = post(ana, "Settled", "We agreed.");
    ben.submitToAndFollow("/admin/board", Map.of("action", "lock", "id", Long.toString(id)));

    reply(cass, id, null, "Actually...");
    assertEquals("locked means locked", 0, board().thread(id).size());
    Browser.Page page = cass.get("/board/" + id);
    assertEquals("and still readable", 200, page.status());
    assertTrue(page.contains("We agreed."));
    assertTrue(page.contains("locked"));
  }

  @Test
  public void anAdminCanRemoveAPostAndTheThreadSurvives() throws Exception {
    long id = post(ana, "Off topic", "Not for here.");
    reply(ben, id, null, "Agreed.");

    cass.submitToAndFollow("/admin/board", Map.of("action", "remove", "id", Long.toString(id)));
    assertTrue(board().postById(id).removed());
    assertFalse("gone from the feed", ana.get("/board").contains("Off topic"));
    assertEquals("the replies are not deleted", 1, board().thread(id).size());
  }

  @Test
  public void theAdminBoardListingShowsWhatTheFeedHides() throws Exception {
    long id = post(ana, "Off topic", "Not for here.");
    cass.submitToAndFollow("/admin/board", Map.of("action", "remove", "id", Long.toString(id)));

    Browser.Page page = ben.get("/admin/board");
    assertEquals(200, page.status());
    assertTrue("an admin has to be able to see what they removed", page.contains("Off topic"));
    assertTrue(page.contains("removed"));
  }

  @Test
  public void anAdminCanChangeHowLongOneThreadLives() throws Exception {
    long id = post(ana, "Keep this", "The constitution.");
    assertNotNull("it expires by default", board().postById(id).expiresAt());

    ben.submitToAndFollow("/admin/board",
        Map.of("action", "expiry", "id", Long.toString(id), "days", "0"));
    assertNull("and now it does not", board().postById(id).expiresAt());
  }
}
