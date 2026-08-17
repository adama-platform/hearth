package io.hearth.board;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Votes, flags, and the queue a person reads.
 *
 * <b>The rule under all of it: a signal is never a verdict.</b> Nothing here hides anything, sorts
 * anything, or removes anything. A community where votes bury things has handed its judgement to
 * whoever votes most, and a flag that auto-hides is a heckler's veto with a nice icon. What the
 * numbers do is tell a person where to look, and the person decides -- which is the only division
 * of labour that works for the job this exists for.
 */
public class TriageTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;
  private Browser ana;
  private Browser bo;
  private long postId;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = member("boss@example.com", "The Boss");
    ana = member("ana@example.com", "Ana Rivera");
    bo = member("bo@example.com", "Bo Chen");
    ana.get("/board");
    ana.submitTo("/board", Map.of("action", "post", "title", "Bread day", "body", "Who is baking?"));
    postId = board().feed(10).get(0).id();
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

  private Signals signals() {
    return server.auth.forDomain("example.org").signals;
  }

  private long idOf(String email) throws Exception {
    return server.auth.forDomain("example.org").users.byEmail(email).id();
  }

  private Browser member(String email, String name) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    browser.get("/welcome");
    browser.submitTo("/welcome",
        Map.of("action", "name", "display_name", name, "location", "", "about", ""));
    browser.get("/welcome?step=3");
    if (!email.startsWith("boss")) {
      admin.get("/admin/people");
      admin.submitTo("/admin/people",
          Map.of("action", "approve", "user", Long.toString(idOf(email))));
    }
    return browser;
  }

  private void vote(Browser who, String direction, String kind, long id) throws Exception {
    who.get("/board/" + postId);
    who.submitTo("/board", Map.of("action", "vote", "vote", direction,
        "subject_kind", kind, "subject_id", Long.toString(id), "post", Long.toString(postId)));
  }

  private long comment(Browser who, String body) throws Exception {
    who.get("/board/" + postId);
    who.submitTo("/board", Map.of("action", "reply", "post", Long.toString(postId), "body", body));
    java.util.List<Board.Comment> all = board().thread(new Subject(Subject.Kind.post, postId));
    return all.get(all.size() - 1).id();
  }

  // ---- voting -------------------------------------------------------------------------------------

  @Test
  public void oneVoteEachAndPressingItAgainTakesItBack() throws Exception {
    Subject subject = new Subject(Subject.Kind.post, postId);
    vote(bo, "up", "post", postId);
    assertEquals(1, signals().tally(subject, idOf("bo@example.com")).up());

    // pressing it twice is what everybody expects to undo it
    vote(bo, "up", "post", postId);
    assertEquals(0, signals().tally(subject, idOf("bo@example.com")).up());

    // and nobody holds both opinions at once
    vote(bo, "up", "post", postId);
    vote(bo, "down", "post", postId);
    Signals.Tally tally = signals().tally(subject, idOf("bo@example.com"));
    assertEquals(0, tally.up());
    assertEquals(1, tally.down());
    assertTrue(tally.iVotedDown());
  }

  @Test
  public void aCommentIsVotedOnByItself() throws Exception {
    long commentId = comment(bo, "I will bring the flour.");
    vote(ana, "up", "comment", commentId);
    vote(admin, "up", "comment", commentId);
    assertEquals("the thing worth agreeing with is usually one paragraph, not the thread",
        2, signals().tally(new Subject(Subject.Kind.comment, commentId), idOf("ana@example.com"))
            .up());
    assertEquals("and the thread itself is untouched by it", 0,
        signals().tally(new Subject(Subject.Kind.post, postId), idOf("ana@example.com")).up());
  }

  @Test
  public void aScoreChangesNothingAboutWhatIsShown() throws Exception {
    // six downs and nothing happens to it: no hiding, no reordering, no removal
    for (Browser who : new Browser[]{ana, bo, admin}) {
      vote(who, "down", "post", postId);
    }
    Browser.Page feed = ana.get("/board");
    assertTrue("still there, in the same place", feed.contains("Bread day"));
    assertFalse(board().postById(postId).removed());
  }

  @Test
  public void theCountsAreOnThePageAndSoIsWhatYouSaid() throws Exception {
    vote(bo, "up", "post", postId);
    Browser.Page page = bo.get("/board/" + postId);
    assertTrue(page.contains("class=\"vote on\""));
    Browser.Page theirs = ana.get("/board/" + postId);
    assertFalse("somebody else's page does not say they voted",
        theirs.contains("class=\"vote on\""));
  }

  // ---- flagging -----------------------------------------------------------------------------------

  @Test
  public void aFlagAsksForAPersonAndDoesNothingElse() throws Exception {
    long commentId = comment(bo, "Something somebody objected to.");
    ana.get("/board/" + postId);
    ana.submitTo("/board", Map.of("action", "flag", "subject_kind", "comment",
        "subject_id", Long.toString(commentId), "post", Long.toString(postId),
        "reason", "this reads as a personal attack"));

    assertFalse("nothing was hidden", board().commentById(commentId).removed());
    assertEquals(1, signals().openFlagCount());

    Browser.Page still = bo.get("/board/" + postId);
    assertTrue("and the person who wrote it sees no difference",
        still.contains("Something somebody objected to."));
  }

  @Test
  public void fourFlagsOnOneThingIsOneThingToLookAt() throws Exception {
    long commentId = comment(bo, "Contested.");
    for (Browser who : new Browser[]{ana, admin}) {
      who.get("/board/" + postId);
      who.submitTo("/board", Map.of("action", "flag", "subject_kind", "comment",
          "subject_id", Long.toString(commentId), "post", Long.toString(postId),
          "reason", "not on"));
    }
    Browser.Page queue = admin.get("/admin/board/flagged/list");
    assertEquals(200, queue.status());
    assertTrue(queue.contains("Contested."));
    assertTrue("how many people, because one person with three tabs is a different fact",
        queue.contains("2 people"));
    assertTrue(queue.contains("not on"));
  }

  @Test
  public void lookingAtSomethingAndLeavingItIsARealOutcome() throws Exception {
    long commentId = comment(bo, "Fine, actually.");
    ana.get("/board/" + postId);
    ana.submitTo("/board", Map.of("action", "flag", "subject_kind", "comment",
        "subject_id", Long.toString(commentId), "post", Long.toString(postId), "reason", ""));

    admin.get("/admin/board/flagged");
    admin.submitToAndFollow("/admin/board/flagged", Map.of("action", "clear",
        "subject_kind", "comment", "subject_id", Long.toString(commentId)));
    assertEquals("out of the queue", 0, signals().openFlagCount());
    assertFalse("and the words are still there", board().commentById(commentId).removed());
    assertEquals("but the record of what was reported stays", 1,
        signals().flagsOn(new Subject(Subject.Kind.comment, commentId)).size());
  }

  @Test
  public void takingSomethingDownFromTheQueueIsTheSectionsOwnPermission() throws Exception {
    long commentId = comment(bo, "Genuinely bad.");
    ana.get("/board/" + postId);
    ana.submitTo("/board", Map.of("action", "flag", "subject_kind", "comment",
        "subject_id", Long.toString(commentId), "post", Long.toString(postId), "reason", "no"));

    admin.get("/admin/board/flagged");
    admin.submitToAndFollow("/admin/board/flagged", Map.of("action", "remove",
        "subject_kind", "comment", "subject_id", Long.toString(commentId)));
    assertTrue(board().commentById(commentId).removed());
    assertEquals("and the flags go with it", 0, signals().openFlagCount());
  }

  @Test
  public void theQueueIsForModeratorsOnly() throws Exception {
    assertEquals(404, ana.get("/admin/board/flagged").status());
    assertEquals(404, ana.get("/admin/board/flagged/list").status());
  }

  // ---- what a model can see -----------------------------------------------------------------------

  @Test
  public void aModelCanReadTheQueueAndCannotActOnIt() throws Exception {
    long commentId = comment(bo, "Worth a look.");
    ana.get("/board/" + postId);
    ana.submitTo("/board", Map.of("action", "flag", "subject_kind", "comment",
        "subject_id", Long.toString(commentId), "post", Long.toString(postId),
        "reason", "off topic"));

    io.hearth.mcp.AiSurface surface = new io.hearth.mcp.AiSurface(
        server.auth.forDomain("example.org"), false)
        .actingAs(idOf("boss@example.com"), "boss@example.com");
    java.util.List<Map<String, Object>> flagged = surface.flagged();
    assertEquals(1, flagged.size());
    assertEquals("Worth a look.", flagged.get(0).get("said"));
    assertEquals(java.util.List.of("off topic"), flagged.get(0).get("reasons"));
    assertEquals("Bo Chen", flagged.get(0).get("who"));

    // ...and there is deliberately no tool for acting on it. That is a stronger rule than a
    // permission check, because there is nothing to route around: pinning, locking and removing
    // are powers a community gave a person, and reading a queue is not one of them.
    java.util.List<String> names = new io.hearth.mcp.McpTools(surface).all().stream()
        .map(io.hearth.mcp.McpTools.Tool::name).toList();
    for (String forbidden : java.util.List.of("board_remove", "board_moderate", "board_pin",
        "board_lock", "board_clear_flag", "board_unflag")) {
      assertFalse(forbidden + " must not exist", names.contains(forbidden));
    }
    assertTrue("but reading the queue does", names.contains("board_flagged"));
  }
}
