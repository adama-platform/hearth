package io.hearth.vote;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.McpClient;
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
 * Agents converging on a date, and the availability they are given to do it with.
 *
 * The interesting behaviour is social rather than technical, so that is what is asserted: that a
 * veto beats a majority, that a narrowing says what it dropped, that changing your mind is kept
 * rather than overwritten, and above all that an agent handed a rough weekly shape is told in as
 * many words that it is not a calendar.
 */
public class VoteTests {
  private Configs configs;
  private TestServer server;
  private Browser me;
  private Browser ana;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Ranch\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"mcp\":{\"enabled\":true,\"vendors\":[\"grok\"]}}");
    server = TestServer.ofConfigs(configs.file());
    me = signIn("boss@example.com");
    ana = signIn("ana@example.com");
    approve("ana@example.com");
    // A friend needs `agent_connect` before their agent can do anything here, and that is the
    // operator's one-time step rather than something membership grants. It stays a permission on
    // purpose: an agent acting as somebody is the sharpest thing this server hands out, and
    // "everybody who is approved" is not a decision anybody made about any particular person.
    letThemConnectAnAgent("ana@example.com");
  }

  private void letThemConnectAnAgent(String email) throws Exception {
    io.hearth.auth.Accounts accounts = server.auth.forDomain("example.org");
    accounts.roleDefs.save("friend", "Friend", "",
        java.util.EnumSet.of(io.hearth.auth.Permission.agent_connect), "blue", null);
    accounts.roles.grant(accounts.users.byEmail(email).id(), "friend", null);
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

  private void approve(String email) throws Exception {
    long id = server.auth.forDomain("example.org").users.byEmail(email).id();
    me.get("/admin/people");
    me.submitTo("/admin/people", Map.of("action", "approve", "user", Long.toString(id)));
  }

  private Votes votes() {
    return server.auth.forDomain("example.org").votes;
  }

  private long idOf(String email) throws Exception {
    return server.auth.forDomain("example.org").users.byEmail(email).id();
  }

  private McpClient agentFor(Browser person) throws Exception {
    return new McpClient(server.port, "example.org")
        .connect(person, "https://grok.com/connectors/callback");
  }

  // ---- the pool --------------------------------------------------------------------------------

  @Test
  public void aVoteStartsWithOptionsAndGrows() throws Exception {
    votes().open("games", "Board game night", "Which evening?",
        List.of("Thursday", "Friday"), idOf("boss@example.com"), "Boss");
    votes().propose("games", "Sunday afternoon", "if the weather turns",
        idOf("ana@example.com"), "Ana");

    Votes.Record vote = votes().bySlug("games");
    assertEquals(3, vote.optionsJson().size());
    assertEquals(Votes.State.open, vote.state());
  }

  @Test
  public void anOptionProposedTwiceIsRefusedRatherThanSplittingTheVote() throws Exception {
    votes().open("games", "Games", "?", List.of("Thursday"), idOf("boss@example.com"), "Boss");
    try {
      votes().propose("games", "thursday", null, idOf("ana@example.com"), "Ana");
      org.junit.Assert.fail("should have refused");
    } catch (Votes.Refused refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("already an option"));
    }
  }

  // ---- the counting ----------------------------------------------------------------------------

  /**
   * One veto beats three yes votes, and that is the whole scheduling design.
   *
   * A date somebody cannot attend is worse than no date, so `blocked` is not a low score -- it
   * removes the option. Without this the arithmetic produces evenings that read as popular and
   * that somebody is out of the country for.
   */
  @Test
  public void aBlockBeatsAMajority() throws Exception {
    votes().open("games", "Games", "?", List.of("Thursday", "Friday"),
        idOf("boss@example.com"), "Boss");
    votes().cast("games", "Thursday", Votes.Ballot.yes, "perfect", idOf("boss@example.com"), "Boss");
    votes().cast("games", "Thursday", Votes.Ballot.blocked, "I am away",
        idOf("ana@example.com"), "Ana");
    votes().cast("games", "Friday", Votes.Ballot.fine, null, idOf("ana@example.com"), "Ana");

    List<Votes.Tally> standing = votes().tally(votes().bySlug("games"));
    assertEquals("a blocked option sorts below everything viable", "Friday",
        standing.get(0).label());
    assertEquals("Thursday", standing.get(1).label());
    assertEquals(1, standing.get(1).blocked());
    assertEquals("and says who", "Ana", standing.get(1).blockedBy());
  }

  @Test
  public void changingYourMindReplacesTheBallotAndKeepsBoth() throws Exception {
    votes().open("games", "Games", "?", List.of("Thursday"), idOf("boss@example.com"), "Boss");
    votes().cast("games", "Thursday", Votes.Ballot.no, "tired", idOf("ana@example.com"), "Ana");
    votes().cast("games", "Thursday", Votes.Ballot.yes, "found a sitter",
        idOf("ana@example.com"), "Ana");

    Votes.Record vote = votes().bySlug("games");
    assertEquals("one ballot per person, the last one", 1, votes().tally(vote).get(0).voters());
    assertEquals(1, votes().tally(vote).get(0).yes());
    String history = vote.historyJson().toString();
    assertTrue("both are in the history", history.contains("tired"));
    assertTrue("so a late change is visible rather than silent", history.contains("found a sitter"));
  }

  // ---- converging ------------------------------------------------------------------------------

  @Test
  public void narrowingKeepsTheBestAndSaysWhatWent() throws Exception {
    votes().open("games", "Games", "?",
        List.of("Mon", "Tue", "Wed", "Thu", "Fri"), idOf("boss@example.com"), "Boss");
    votes().cast("games", "Thu", Votes.Ballot.yes, null, idOf("boss@example.com"), "Boss");
    votes().cast("games", "Thu", Votes.Ballot.yes, null, idOf("ana@example.com"), "Ana");
    votes().cast("games", "Fri", Votes.Ballot.fine, null, idOf("boss@example.com"), "Boss");
    votes().cast("games", "Wed", Votes.Ballot.blocked, "never Wednesdays",
        idOf("ana@example.com"), "Ana");

    Votes.Record vote = votes().narrow("games", 2, idOf("boss@example.com"), "Boss");
    assertEquals(2, vote.optionsJson().size());
    assertEquals(Votes.State.narrowed, vote.state());

    String kept = vote.optionsJson().toString();
    assertTrue(kept, kept.contains("Thu"));
    assertTrue(kept, kept.contains("Fri"));
    assertFalse("a blocked option goes whatever its score", kept.contains("Wed"));

    String history = vote.historyJson().toString();
    assertTrue("what went is recorded", history.contains("dropped"));
    assertTrue("with the reason", history.contains("blocked by Ana"));
  }

  @Test
  public void narrowingRefusesWhenEverythingIsBlocked() throws Exception {
    votes().open("games", "Games", "?", List.of("Thursday"), idOf("boss@example.com"), "Boss");
    votes().cast("games", "Thursday", Votes.Ballot.blocked, "away", idOf("ana@example.com"), "Ana");
    try {
      votes().narrow("games", 3, idOf("boss@example.com"), "Boss");
      org.junit.Assert.fail("should have refused");
    } catch (Votes.Refused refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("every option is blocked"));
    }
  }

  @Test
  public void aDecidedVoteStopsTakingBallots() throws Exception {
    votes().open("games", "Games", "?", List.of("Thursday"), idOf("boss@example.com"), "Boss");
    votes().decide("games", "Thursday", idOf("boss@example.com"), "Boss");
    assertEquals("Thursday", votes().bySlug("games").outcome());
    try {
      votes().cast("games", "Thursday", Votes.Ballot.no, null, idOf("ana@example.com"), "Ana");
      org.junit.Assert.fail("should have refused");
    } catch (Votes.Refused refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("not taking votes"));
    }
  }

  // ---- availability ----------------------------------------------------------------------------

  /**
   * An agent is told which kind of answer it is holding.
   *
   * This is the difference between a proposal and an embarrassment: handed a rough weekly shape and
   * left to assume it is a calendar, an agent will confidently offer a night its person has had
   * booked for a month.
   */
  @Test
  public void aWeeklyShapeIsLabelledAsNotACalendar() throws Exception {
    ana.get("/self?tab=keys");
    ana.submitTo("/self", Map.of("action", "availability",
        "weekly", "{\"tuesday\":\"after 6\",\"wednesday\":\"never\"}",
        "notes", "no long drives on a school night", "ics_url", ""));

    McpClient agent = agentFor(me);
    String answer = agent.call("when_free").toolResult().toString();
    assertTrue(answer, answer.contains("\"kind\":\"weekly\""));
    assertTrue("and told in as many words", answer.contains("NOT their calendar"));
    assertTrue(answer, answer.contains("after 6"));
    assertFalse("never an address", answer.contains("ana@example.com"));
  }

  @Test
  public void aSharedCalendarIsHandedOverAsALinkAndNotFetched() throws Exception {
    ana.get("/self?tab=keys");
    ana.submitTo("/self", Map.of("action", "availability", "weekly", "",
        "notes", "", "ics_url", "https://calendar.example.org/ana.ics"));

    String answer = agentFor(me).call("when_free").toolResult().toString();
    assertTrue(answer, answer.contains("\"kind\":\"calendar\""));
    assertTrue(answer, answer.contains("calendar.example.org/ana.ics"));
    assertTrue("the agent fetches it, not this server", answer.contains("Fetch it yourself"));
  }

  @Test
  public void anHttpCalendarLinkIsRefused() throws Exception {
    ana.get("/self?tab=keys");
    assertTrue(ana.submitTo("/self", Map.of("action", "availability", "weekly", "",
            "notes", "", "ics_url", "http://calendar.example.org/ana.ics"))
        .contains("travelling in the clear"));
  }

  @Test
  public void somebodyWhoHasSaidNothingIsAbsentRatherThanGuessedAt() throws Exception {
    String answer = agentFor(me).call("when_free").toolResult().toString();
    assertTrue(answer, answer.contains("\"count\":0"));
  }

  // ---- agents driving it ------------------------------------------------------------------------

  @Test
  public void twoAgentsConvergeOnADateWithoutEitherDeciding() throws Exception {
    McpClient mine = agentFor(me);
    McpClient hers = agentFor(ana);

    mine.call("vote_open", "vote", "board-games", "title", "Board game night",
        "question", "Which evening in October?",
        "options", List.of("Thu 9th", "Fri 10th"));
    hers.call("vote_propose", "vote", "board-games", "option", "Sun 12th",
        "detail", "afternoon, better for the drive");

    mine.call("vote_cast", "vote", "board-games", "option", "Thu 9th",
        "ballot", "yes", "because", "closest to home");
    hers.call("vote_cast", "vote", "board-games", "option", "Thu 9th",
        "ballot", "blocked", "because", "away that week");
    hers.call("vote_cast", "vote", "board-games", "option", "Sun 12th",
        "ballot", "yes", "because", "free all day");

    mine.call("vote_narrow", "vote", "board-games", "keep", 2);

    // asserted on the options rather than the whole answer: the dropped evening is still in the
    // history, on purpose, and searching the response for its name would find it there
    String options = votes().bySlug("board-games").optionsJson().toString();
    assertTrue(options, options.contains("Sun 12th"));
    assertTrue(options, options.contains("Fri 10th"));
    assertFalse("the blocked evening is gone", options.contains("Thu 9th"));
    assertTrue("but why it went is not", votes().bySlug("board-games").historyJson().toString()
        .contains("away that week"));
    assertEquals("and it waits for a person", Votes.State.narrowed,
        votes().bySlug("board-games").state());
  }

  @Test
  public void anAgentVotesAsItsOwnPersonAndNobodyElse() throws Exception {
    votes().open("games", "Games", "?", List.of("Thursday"), idOf("boss@example.com"), "Boss");
    agentFor(ana).call("vote_cast", "vote", "games", "option", "Thursday",
        "ballot", "yes", "because", "sure");

    String history = votes().bySlug("games").historyJson().toString();
    assertTrue("the ballot is attributed to the person who connected the agent",
        history.contains("\"by\":\"" + nameOf("ana@example.com") + "\""));
    assertEquals("and it is one ballot", 1, votes().tally(votes().bySlug("games")).get(0).voters());
  }

  private String nameOf(String email) throws Exception {
    long id = idOf(email);
    String name = server.auth.forDomain("example.org").people.profileOf(id).nameOr("");
    return name.isBlank() ? "member " + id : name;
  }

  @Test
  public void theToolsTellAnAgentWhenToStopAndAskAHuman() throws Exception {
    String tools = agentFor(me).listTools().body();
    assertTrue("deciding is the humans' job", tools.contains("DO NOT DO THIS ON YOUR OWN"));
    assertTrue("and a veto is explained", tools.contains("veto"));
    assertTrue("and reading first is asked for", tools.contains("READ THIS BEFORE VOTING"));
  }
}
