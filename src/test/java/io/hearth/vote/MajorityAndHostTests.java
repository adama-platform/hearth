package io.hearth.vote;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Votes for a big group, and the person who has to have everybody round.
 *
 * Two things are proved here that the small-group case never exercises: that a veto stops removing
 * options once a group is large enough that somebody is always away, and that the host's veto is
 * the one exception to that — a majority cannot vote somebody into having twelve people in their
 * kitchen.
 */
public class MajorityAndHostTests {
  private Configs configs;
  private TestServer server;
  private Browser me;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Ranch\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    me = signIn("boss@example.com");
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

  private Votes votes() {
    return server.auth.forDomain("example.org").votes;
  }

  private long member(String name) throws Exception {
    signIn(name + "@example.com");
    long id = server.auth.forDomain("example.org").users.byEmail(name + "@example.com").id();
    server.auth.forDomain("example.org").users.approve(id, null);
    return id;
  }

  // ---- majority ---------------------------------------------------------------------------------

  /**
   * With twenty people somebody is always away, and consensus produces no evening at all.
   *
   * This is the case the mode exists for: under consensus every option collects a veto and the
   * group that wanted a party gets nothing. Majority asks the other question -- which evening can
   * the most people make -- and answers it.
   */
  @Test
  public void underMajorityABlockIsAStrongNoRatherThanAVeto() throws Exception {
    long ana = member("ana");
    long bo = member("bo");
    long cy = member("cy");

    votes().open("party", "The party", "Which evening?", List.of("Thu", "Fri"),
        Votes.Mode.majority, null, ana, "Ana");
    votes().cast("party", "Thu", Votes.Ballot.yes, null, ana, "Ana");
    votes().cast("party", "Thu", Votes.Ballot.yes, null, bo, "Bo");
    votes().cast("party", "Thu", Votes.Ballot.blocked, "away", cy, "Cy");
    votes().cast("party", "Fri", Votes.Ballot.fine, null, cy, "Cy");

    List<Votes.Tally> standing = votes().tally(votes().bySlug("party"));
    assertEquals("two can come on Thursday and one on Friday", "Thu", standing.get(0).label());
    assertEquals(2, standing.get(0).canCome());
    assertFalse("a block does not remove it under majority",
        standing.get(0).ruledOut(Votes.Mode.majority));
    assertTrue("and it would under consensus",
        standing.get(0).ruledOut(Votes.Mode.consensus));
  }

  @Test
  public void narrowingUnderMajorityKeepsTheEveningMostPeopleCanMake() throws Exception {
    long ana = member("ana");
    long bo = member("bo");
    long cy = member("cy");
    votes().open("party", "The party", "?", List.of("Thu", "Fri", "Sat"),
        Votes.Mode.majority, null, ana, "Ana");
    for (long who : new long[]{ana, bo, cy}) {
      votes().cast("party", "Thu", Votes.Ballot.fine, null, who, "member " + who);
    }
    votes().cast("party", "Thu", Votes.Ballot.blocked, "away", cy, "Cy");
    votes().cast("party", "Fri", Votes.Ballot.yes, null, ana, "Ana");

    Votes.Record narrowed = votes().narrow("party", 2, ana, "Ana");
    String kept = narrowed.optionsJson().toString();
    assertTrue("the blocked evening survives, because most people can still make it",
        kept.contains("Thu"));
    assertTrue(kept, kept.contains("Fri"));
    assertFalse("and the one nobody voted for is gone", kept.contains("Sat"));
    assertTrue("the reason says how many could come",
        narrowed.historyJson().toString().contains("could come"));
  }

  @Test
  public void consensusIsStillTheDefault() throws Exception {
    long ana = member("ana");
    votes().open("games", "Games", "?", List.of("Thu"), ana, "Ana");
    assertEquals(Votes.Mode.consensus, votes().bySlug("games").mode());
  }

  // ---- the host -----------------------------------------------------------------------------------

  /**
   * The host's block is final in either mode.
   *
   * A majority can outvote anybody about whether an evening is convenient. It cannot outvote the
   * person whose house it is, because attendance is a preference and hosting is a piece of work
   * somebody has to agree to do.
   */
  @Test
  public void theHostsBlockIsFinalEvenUnderMajority() throws Exception {
    long ana = member("ana");
    long bo = member("bo");
    long cy = member("cy");
    votes().open("party", "The party", "?", List.of("Thu", "Fri"),
        Votes.Mode.majority, ana, bo, "Bo");

    votes().cast("party", "Thu", Votes.Ballot.yes, null, bo, "Bo");
    votes().cast("party", "Thu", Votes.Ballot.yes, null, cy, "Cy");
    votes().cast("party", "Thu", Votes.Ballot.blocked, "I am not home", ana, "Ana");
    votes().cast("party", "Fri", Votes.Ballot.fine, null, ana, "Ana");

    List<Votes.Tally> standing = votes().tally(votes().bySlug("party"));
    assertEquals("the host cannot, so it is not the evening", "Fri", standing.get(0).label());
    assertTrue(standing.get(1).hostBlocked());
    assertTrue("ruled out in either mode", standing.get(1).ruledOut(Votes.Mode.majority));

    Votes.Record narrowed = votes().narrow("party", 3, bo, "Bo");
    assertTrue("and the history says why",
        narrowed.historyJson().toString().contains("the host cannot"));
  }

  /**
   * Nobody is told until the host has said yes.
   *
   * "Will you have people round on the 9th" is a question one person can answer no to. "We are
   * meeting at Ana's on the 9th" is not. The gate is what keeps those two in the right order.
   */
  @Test
  public void anInvitationWaitsForTheHost() throws Exception {
    long ana = member("ana");
    long bo = member("bo");
    votes().open("party", "The party", "?", List.of("Thu"), Votes.Mode.consensus, ana, bo, "Bo");
    votes().decide("party", "Thu", bo, "Bo");

    assertFalse("decided, but nobody may be told yet",
        votes().readyToInvite(votes().bySlug("party")));

    votes().hostAnswer("party", true, "happy to", ana, "Ana");
    assertTrue(votes().readyToInvite(votes().bySlug("party")));
  }

  @Test
  public void aHostSayingNoPutsTheVoteBackRatherThanEndingIt() throws Exception {
    long ana = member("ana");
    long bo = member("bo");
    votes().open("party", "The party", "?", List.of("Thu", "Fri"),
        Votes.Mode.consensus, ana, bo, "Bo");
    votes().decide("party", "Thu", bo, "Bo");
    votes().hostAnswer("party", false, "the kitchen is being done", ana, "Ana");

    Votes.Record vote = votes().bySlug("party");
    assertEquals("the group still wants an evening", Votes.State.narrowed, vote.state());
    assertEquals("", vote.outcome());
    assertFalse(vote.hostAccepted());
    assertTrue(vote.historyJson().toString().contains("the kitchen is being done"));
  }

  @Test
  public void onlyTheHostMayAnswerForTheHost() throws Exception {
    long ana = member("ana");
    long bo = member("bo");
    votes().open("party", "The party", "?", List.of("Thu"), Votes.Mode.consensus, ana, bo, "Bo");
    try {
      votes().hostAnswer("party", true, null, bo, "Bo");
      org.junit.Assert.fail("should have refused");
    } catch (Votes.Refused refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("only the host"));
    }
  }

  @Test
  public void anInvitationGoesOutOnce() throws Exception {
    long ana = member("ana");
    votes().open("party", "The party", "?", List.of("Thu"), Votes.Mode.consensus, null, ana, "Ana");
    votes().decide("party", "Thu", ana, "Ana");
    assertTrue("no host, so nothing to wait for", votes().readyToInvite(votes().bySlug("party")));

    votes().markInvited("party", ana, "Ana");
    assertFalse("and not twice", votes().readyToInvite(votes().bySlug("party")));
  }

  // ---- the calendar ------------------------------------------------------------------------------

  @Test
  public void anIcsFileBecomesBusyWindowsAndNothingElse() {
    String ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"
        + "BEGIN:VEVENT\r\nDTSTART:20261008T180000Z\r\nDTEND:20261008T200000Z\r\n"
        + "SUMMARY:Dinner with the accountant\r\nLOCATION:somewhere private\r\nEND:VEVENT\r\n"
        + "END:VCALENDAR\r\n";
    List<Ics.Busy> busy = Ics.busy(ics, ZoneOffset.UTC);
    assertEquals(1, busy.size());
    assertEquals(2 * 60 * 60 * 1000L, busy.get(0).end() - busy.get(0).start());
    assertFalse("the summary is never carried anywhere",
        busy.toString().contains("accountant"));
  }

  /**
   * A folded line is unfolded before anything reads it.
   *
   * RFC 5545 wraps at 75 octets and continues with a leading space. A parser that reads line by
   * line sees a truncated DTSTART and a line starting with a space -- and both parse to something,
   * which is how it gets the wrong day without ever failing.
   */
  @Test
  public void aFoldedLineIsReadAsOneLine() {
    List<String> lines = Ics.unfold("DTSTART:2026100\r\n 8T180000Z\r\nEND:VEVENT");
    assertEquals(2, lines.size());
    assertEquals("DTSTART:20261008T180000Z", lines.get(0));
  }

  @Test
  public void anAllDayEventCoversTheDay() {
    String ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nDTSTART;VALUE=DATE:20261008\nEND:VEVENT\n"
        + "END:VCALENDAR";
    List<Ics.Busy> busy = Ics.busy(ics, ZoneOffset.UTC);
    assertEquals(1, busy.size());
    assertEquals(24 * 60 * 60 * 1000L, busy.get(0).end() - busy.get(0).start());
  }

  @Test
  public void aTimestampThisCannotReadIsDroppedRatherThanGuessed() {
    String ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nDTSTART:next tuesday\nEND:VEVENT\nEND:VCALENDAR";
    assertTrue("a guessed date silently removes a good evening",
        Ics.busy(ics, ZoneOffset.UTC).isEmpty());
  }

  @Test
  public void busyWindowsAnswerOverlapRatherThanContainment() {
    List<Ics.Busy> busy = List.of(new Ics.Busy(100, 200));
    assertTrue("an evening that starts during a meeting is not free",
        Ics.busyBetween(busy, 150, 300));
    assertFalse(Ics.busyBetween(busy, 200, 300));
  }
}
