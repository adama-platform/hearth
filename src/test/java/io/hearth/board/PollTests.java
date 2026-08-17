package io.hearth.board;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A group deciding something inside the argument where the reasons are.
 *
 * The arithmetic is what most of this checks, because it is what nobody can see going wrong. A vote
 * that quietly counts a removed option, or one that breaks a tie by picking the lower id, produces
 * a confident answer that is not the group's -- and it puts it in everybody's calendar at midnight.
 */
public class PollTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;
  private Browser ana;
  private Browser bo;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com");
    ana = member("ana@example.com");
    bo = member("bo@example.com");
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

  private Polls polls() {
    return server.auth.forDomain("example.org").polls;
  }

  private long postId() throws Exception {
    return server.auth.forDomain("example.org").board.feed(10).get(0).id();
  }

  private long startAConversation() throws Exception {
    ana.submitToAndFollow("/board", Map.of("action", "post", "title", "Where this month?",
        "body", "Somewhere with a back room."));
    return postId();
  }

  // ---- the arithmetic --------------------------------------------------------------------------

  @Test
  public void aStraightChoiceIsOneVoteEachAndVotingAgainMovesIt() throws Exception {
    long post = startAConversation();
    ana.submitToAndFollow("/board", Map.of("action", "poll_new", "post", Long.toString(post),
        "kind", "choice", "question", "Which pub?", "choices", "The Oak\nThe Bell"));
    Poll.Record poll = polls().forPost(post).get(0);
    List<Poll.Option> options = polls().options(poll.id());
    assertEquals(2, options.size());

    vote(ana, options.get(0).id(), "up");
    vote(bo, options.get(0).id(), "up");
    assertEquals(2, polls().result(poll.id(), Poll.Facet.choice).winner().score());

    // an either-or holds one opinion: moving is moving, not adding
    vote(bo, options.get(1).id(), "up");
    Poll.Result after = polls().result(poll.id(), Poll.Facet.choice);
    assertEquals("The Oak", after.winner().option().describe());
    assertEquals(1, after.winner().score());
    assertEquals("two people voted, not three", 2, after.voters());
  }

  @Test
  public void votingTheSameWayTwiceTakesItBack() throws Exception {
    long post = startAConversation();
    ana.submitToAndFollow("/board", Map.of("action", "poll_new", "post", Long.toString(post),
        "kind", "choice", "question", "Which pub?", "choices", "The Oak"));
    Poll.Record poll = polls().forPost(post).get(0);
    long option = polls().options(poll.id()).get(0).id();

    vote(ana, option, "up");
    assertEquals(1, polls().result(poll.id(), Poll.Facet.choice).winner().score());
    vote(ana, option, "up");
    assertNull("pressing it again means you changed your mind about pressing it",
        polls().result(poll.id(), Poll.Facet.choice).winner());
  }

  @Test
  public void daysTakeAnOpinionEachSoAHistogramComesOut() throws Exception {
    // a week has several evenings and somebody can be free on three of them; forcing one pick
    // throws away most of what they know, which is the whole reason this half counts differently
    long post = startAConversation();
    long poll = schedulePoll(post, "Supper", LocalDate.now().plusDays(20));
    long friday = addDay(admin, poll, LocalDate.now().plusDays(7), "from 7");
    long saturday = addDay(admin, poll, LocalDate.now().plusDays(8), "");
    long sunday = addDay(admin, poll, LocalDate.now().plusDays(9), "");

    vote(ana, friday, "up");
    vote(ana, saturday, "up");
    vote(ana, sunday, "down");
    vote(bo, saturday, "up");
    vote(bo, sunday, "down");

    Poll.Result when = polls().result(poll, Poll.Facet.time);
    assertEquals("Saturday, on two yeses and nothing against",
        saturday, when.winner().option().id());
    assertEquals(2, when.winner().score());
    assertEquals("two people, however many opinions they each held", 2, when.voters());

    // and a day everybody ruled out scores below nothing rather than merely fewer
    Poll.Tally worst = when.tallies().stream()
        .filter(tally -> tally.option().id() == sunday).findFirst().orElseThrow();
    assertEquals(-2, worst.score());
  }

  @Test
  public void anEitherOrHasNoAgainst() throws Exception {
    // there is no such thing as being against one option and not for another, so a down vote on a
    // choice is read as taking your vote back rather than as a vote for everybody else
    long post = startAConversation();
    ana.submitToAndFollow("/board", Map.of("action", "poll_new", "post", Long.toString(post),
        "kind", "choice", "question", "Which pub?", "choices", "The Oak\nThe Bell"));
    Poll.Record poll = polls().forPost(post).get(0);
    long oak = polls().options(poll.id()).get(0).id();

    vote(ana, oak, "up");
    vote(ana, oak, "down");
    assertNull(polls().result(poll.id(), Poll.Facet.choice).winner());
    assertEquals("and nothing was recorded against anything", 0,
        polls().votes(poll.id()).size());
  }

  @Test
  public void aTieIsReportedAndNeverBroken() {
    // breaking it would be this software deciding what the community has not, silently, at midnight
    Poll.Option oak = option(1, Poll.Facet.choice, "The Oak");
    Poll.Option bell = option(2, Poll.Facet.choice, "The Bell");
    Poll.Result result = Poll.count(Poll.Facet.choice, List.of(oak, bell),
        List.of(vote(1, 10), vote(2, 11)));
    assertTrue(result.tied());
    assertFalse(result.decided());
    assertEquals("two or more were level", result.problem());
  }

  @Test
  public void everythingVotedDownWinsNothing() {
    // a winner on a negative score is the group saying none of these, and turning that into an
    // evening would be the software insisting on one nobody wants
    Poll.Option friday = option(1, Poll.Facet.time, "Friday");
    Poll.Result result = Poll.count(Poll.Facet.time, List.of(friday),
        List.of(vote(1, 10, -1), vote(1, 11, -1)));
    assertNull(result.winner());
    assertFalse(result.decided());
  }

  @Test
  public void aRemovedOptionStopsCountingAndItsVotesAreKept() throws Exception {
    long post = startAConversation();
    long poll = schedulePoll(post, "Supper", LocalDate.now().plusDays(20));
    long friday = addDay(admin, poll, LocalDate.now().plusDays(7), "");
    long saturday = addDay(admin, poll, LocalDate.now().plusDays(8), "");
    vote(ana, friday, "up");
    vote(bo, friday, "up");
    vote(ana, saturday, "up");

    admin.submitToAndFollow("/board", Map.of("action", "poll_remove_option",
        "option", Long.toString(friday)));
    Poll.Result when = polls().result(poll, Poll.Facet.time);
    assertEquals("Saturday wins once Friday is off the table",
        saturday, when.winner().option().id());
    assertEquals("and Friday is not in the tallies at all", 1, when.tallies().size());
    assertEquals("but the votes are still on the row, so nothing silently changed share",
        3, polls().votes(poll).size());
  }

  // ---- becoming an event ------------------------------------------------------------------------

  @Test
  public void aScheduleVoteBecomesAnEventWithTheWinningDayAndPlace() throws Exception {
    admin.submitToAndFollow("/admin/places", Map.of("action", "save",
        "type_slug", io.hearth.places.Places.DEFAULT_TYPE, "name", "The Oak",
        "address", "1 High Street", "published", "on"));
    long place = server.auth.forDomain("example.org").places.all(10).get(0).id();

    long post = startAConversation();
    LocalDate saturday = LocalDate.now().plusDays(8);
    long poll = schedulePoll(post, "Supper club", LocalDate.now().plusDays(2));
    long friday = addDay(admin, poll, LocalDate.now().plusDays(7), "");
    long sat = addDay(admin, poll, saturday, "from 7");
    long thePlace = addPlace(admin, poll, place);

    vote(ana, sat, "up");
    vote(bo, sat, "up");
    vote(ana, friday, "down");
    vote(ana, thePlace, "up");

    admin.submitToAndFollow("/board", Map.of("action", "poll_close", "poll", Long.toString(poll)));

    Poll.Record settled = polls().byId(poll);
    assertEquals(Poll.State.converted, settled.state());
    assertNotNull(settled.eventId());
    io.hearth.calendar.Calendar.Event event =
        server.auth.forDomain("example.org").calendar.byId(settled.eventId());
    assertEquals(saturday, event.startsOn());
    assertEquals("from 7", event.startTime());
    assertEquals(Long.valueOf(place), event.placeId());
    assertFalse("a draft: an event nobody put their hand on should not appear by itself",
        event.published());

    // and the answer goes back where the question was asked, for the people already watching
    assertTrue(ana.get("/board/" + post).contains("The vote is in"));
  }

  @Test
  public void aTiedDayMakesNoEventAndSaysWhich() throws Exception {
    long post = startAConversation();
    long poll = schedulePoll(post, "Supper", LocalDate.now().plusDays(2));
    long friday = addDay(admin, poll, LocalDate.now().plusDays(7), "");
    long saturday = addDay(admin, poll, LocalDate.now().plusDays(8), "");
    vote(ana, friday, "up");
    vote(bo, saturday, "up");

    admin.submitToAndFollow("/board", Map.of("action", "poll_close", "poll", Long.toString(poll)));
    Poll.Record settled = polls().byId(poll);
    assertEquals(Poll.State.closed, settled.state());
    assertNull("nothing goes in the calendar on a tie", settled.eventId());
    assertTrue(settled.outcome(), settled.outcome().contains("level"));
    // it is not a failure -- it is the group not having decided, and it says so where they are
    assertTrue(ana.get("/board/" + post).contains("The vote is in"));
  }

  @Test
  public void aDayWinsWithoutAPlaceAndTheEventIsStillMade() throws Exception {
    // a group that settled the evening and not the venue should still get the evening written
    // down; a missing event is a thing somebody has to remember
    long post = startAConversation();
    long poll = schedulePoll(post, "Supper", LocalDate.now().plusDays(2));
    long saturday = addDay(admin, poll, LocalDate.now().plusDays(8), "");
    vote(ana, saturday, "up");

    admin.submitToAndFollow("/board", Map.of("action", "poll_close", "poll", Long.toString(poll)));
    Poll.Record settled = polls().byId(poll);
    assertEquals(Poll.State.converted, settled.state());
    assertNotNull(settled.eventId());
  }

  @Test
  public void theSweepClosesWhateverIsDueAndSurvivesARestart() throws Exception {
    long post = startAConversation();
    long poll = schedulePoll(post, "Supper", LocalDate.now().plusDays(1));
    long saturday = addDay(admin, poll, LocalDate.now().plusDays(8), "");
    vote(ana, saturday, "up");

    // nothing is due yet
    assertEquals(0, new PollClock(server.auth, server.tree.all(), io.hearth.common.Verbose.OFF)
        .sweep(System.currentTimeMillis()));
    // and the queue is a query, so a box that was off for a day closes yesterday's when it returns
    assertEquals(1, new PollClock(server.auth, server.tree.all(), io.hearth.common.Verbose.OFF)
        .sweep(System.currentTimeMillis() + 3L * 86_400_000L));
    assertEquals(Poll.State.converted, polls().byId(poll).state());
  }

  // ---- who may do what ---------------------------------------------------------------------------

  @Test
  public void anyMemberCanAskAStraightQuestionAndNobodyElseCanPlanAnEvening() throws Exception {
    long post = startAConversation();
    // a plain member's page offers the straight choice and not the one that makes an event
    Browser.Page page = ana.get("/board/" + post);
    assertTrue(page.body(), page.contains("put something to a vote"));
    assertTrue(page.contains("a straight choice"));
    assertFalse("and it is absent rather than drawn and refusing",
        page.contains("becomes an event"));
    assertTrue(page.contains("needs permission to create events"));

    // and the handler refuses it too, because a hidden control is not a check
    ana.submitToAndFollow("/board", Map.of("action", "poll_new", "post", Long.toString(post),
        "kind", "schedule", "question", "Supper?",
        "closes_on", LocalDate.now().plusDays(3).toString()));
    assertTrue("nothing was created", polls().forPost(post).isEmpty());
  }

  @Test
  public void anUnapprovedStrangerCannotVote() throws Exception {
    long post = startAConversation();
    ana.submitToAndFollow("/board", Map.of("action", "poll_new", "post", Long.toString(post),
        "kind", "choice", "question", "Which pub?", "choices", "The Oak"));
    long option = polls().options(polls().forPost(post).get(0).id()).get(0).id();

    Browser stranger = new Browser(server.port, "example.org");
    stranger.get("/register");
    stranger.submit(Map.of("email", "cal@example.com"));
    stranger.submit(Map.of("code", server.mail().lastCodeFor("cal@example.com")));
    stranger.submitToAndFollow("/board", Map.of("action", "poll_vote",
        "option", Long.toString(option), "weight", "up"));
    assertEquals("the approval gate is what a baseline permission rests on",
        0, polls().votes(polls().forPost(post).get(0).id()).size());
  }

  @Test
  public void takingSomebodyElsesOptionOffTheTableIsModerating() throws Exception {
    long post = startAConversation();
    long poll = schedulePoll(post, "Supper", LocalDate.now().plusDays(20));
    long friday = addDay(admin, poll, LocalDate.now().plusDays(7), "");

    // ana did not put it forward and does not run the poll
    ana.submitToAndFollow("/board", Map.of("action", "poll_remove_option",
        "option", Long.toString(friday)));
    assertFalse(polls().optionById(friday).removed());

    // her own she may take back
    long hers = addDay(ana, poll, LocalDate.now().plusDays(9), "");
    ana.submitToAndFollow("/board", Map.of("action", "poll_remove_option",
        "option", Long.toString(hers)));
    assertTrue(polls().optionById(hers).removed());

    // and whoever asked the question may tidy any of them
    admin.submitToAndFollow("/board", Map.of("action", "poll_remove_option",
        "option", Long.toString(friday)));
    assertTrue(polls().optionById(friday).removed());
  }

  @Test
  public void aClosedPollTakesNoMoreVotes() throws Exception {
    long post = startAConversation();
    ana.submitToAndFollow("/board", Map.of("action", "poll_new", "post", Long.toString(post),
        "kind", "choice", "question", "Which pub?", "choices", "The Oak"));
    Poll.Record poll = polls().forPost(post).get(0);
    long option = polls().options(poll.id()).get(0).id();
    ana.submitToAndFollow("/board", Map.of("action", "poll_close",
        "poll", Long.toString(poll.id())));

    vote(bo, option, "up");
    assertEquals(0, polls().votes(poll.id()).size());
  }

  @Test
  public void twoDaysTheSameAreOneDay() throws Exception {
    long post = startAConversation();
    long poll = schedulePoll(post, "Supper", LocalDate.now().plusDays(20));
    LocalDate day = LocalDate.now().plusDays(7);
    addDay(admin, poll, day, "from 7");
    addDay(ana, poll, day, "from 8");
    assertEquals("one row for one evening, or the votes for it are split in two",
        1, polls().liveOptionCount(poll, Poll.Facet.time));
  }

  @Test
  public void leavingErasesYourVotesAndKeepsWhatYouPutForward() throws Exception {
    long post = startAConversation();
    long poll = schedulePoll(post, "Supper", LocalDate.now().plusDays(20));
    long hers = addDay(ana, poll, LocalDate.now().plusDays(7), "");
    vote(ana, hers, "up");
    vote(bo, hers, "up");

    long anaId = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    admin.submitToAndFollow("/admin/people", Map.of("action", "erase",
        "user", Long.toString(anaId), "confirm", "delete"));

    assertEquals("a decision is a count of the people who are here", 1,
        polls().result(poll, Poll.Facet.time).winner().score());
    assertFalse("what she put forward is part of what the group discussed",
        polls().optionById(hers).removed());
    assertNull("with her name off it", polls().optionById(hers).addedBy());
  }

  // ---- helpers -----------------------------------------------------------------------------------

  private static Poll.Option option(long id, Poll.Facet facet, String label) {
    return new Poll.Option(id, 1, facet, label, null, "", null, 0, null, null, null);
  }

  private static Poll.Vote vote(long optionId, long userId) {
    return vote(optionId, userId, 1);
  }

  private static Poll.Vote vote(long optionId, long userId, int weight) {
    return new Poll.Vote(0, 1, optionId, Poll.Facet.choice, userId, weight);
  }

  private void vote(Browser who, long optionId, String weight) throws Exception {
    who.submitToAndFollow("/board", Map.of("action", "poll_vote",
        "option", Long.toString(optionId), "weight", weight));
  }

  private long schedulePoll(long post, String question, LocalDate closes) throws Exception {
    admin.submitToAndFollow("/board", Map.of("action", "poll_new", "post", Long.toString(post),
        "kind", "schedule", "question", question, "closes_on", closes.toString()));
    List<Poll.Record> all = polls().forPost(post);
    return all.get(all.size() - 1).id();
  }

  private long addDay(Browser who, long poll, LocalDate day, String at) throws Exception {
    who.submitToAndFollow("/board", Map.of("action", "poll_option",
        "poll", Long.toString(poll), "facet", "time", "day", day.toString(), "at", at));
    return newest(poll, Poll.Facet.time);
  }

  private long addPlace(Browser who, long poll, long place) throws Exception {
    who.submitToAndFollow("/board", Map.of("action", "poll_option",
        "poll", Long.toString(poll), "facet", "place", "place", Long.toString(place)));
    return newest(poll, Poll.Facet.place);
  }

  private long newest(long poll, Poll.Facet facet) throws Exception {
    long best = 0;
    for (Poll.Option option : polls().options(poll)) {
      if (option.facet() == facet && !option.removed()) {
        best = Math.max(best, option.id());
      }
    }
    return best;
  }

  private Browser member(String email) throws Exception {
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
