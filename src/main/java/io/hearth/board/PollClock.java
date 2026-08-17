package io.hearth.board;

import io.hearth.auth.Accounts;
import io.hearth.auth.AuthSystem;
import io.hearth.calendar.Calendar;
import io.hearth.common.Verbose;
import io.hearth.vhost.DomainConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;

/**
 * Polls whose moment has come: counted, closed, and -- when they were about a date -- turned into
 * an event.
 *
 * <b>Why this is a background pass and not a check on the page.</b> A poll that only closed when
 * somebody happened to look at it would stay open all weekend and convert on Monday, which is the
 * one time it is useless. It rides the notifier's pass like the invitation reminders do, for the
 * same reason: it is a thing somebody started that finishes itself later, and a second thread would
 * be a second place to get "has this already happened" wrong.
 *
 * <b>The queue is a query.</b> Open, with a closing time that has passed. That survives a restart,
 * cannot disagree with the rows it describes, and means a server that was switched off for a day
 * closes yesterday's polls when it comes back rather than losing them.
 *
 * <b>A tie makes no event.</b> Picking the earlier day or the lower id would be this software
 * deciding what a community has not, and it would do it silently, in the middle of the night, and
 * put it on everybody's calendar. So it closes, says which half tied and why, and posts that back
 * into the conversation -- where somebody can add a day, ask two people to vote, and open it again.
 *
 * <b>The event is a draft when nobody has said otherwise.</b> It lands unpublished, announced only
 * when the poll asked for that, because an event appearing on the calendar with nobody's hand on it
 * is the sort of thing that makes people distrust the whole feature.
 */
public class PollClock {
  private static final Logger LOG = LoggerFactory.getLogger(PollClock.class);
  /** how many to settle in one pass; a community will never have two */
  private static final int PER_PASS = 25;

  private final AuthSystem auth;
  private final Map<String, DomainConfig> domains;
  private final Verbose verbose;

  public PollClock(AuthSystem auth, Map<String, DomainConfig> domains, Verbose verbose) {
    this.auth = auth;
    this.domains = domains;
    this.verbose = verbose == null ? Verbose.OFF : verbose;
  }

  /**
   * The same thing, for a caller that already holds the community it is acting on.
   *
   * Closing a poll by hand and closing one because its moment came have to be the same code: two
   * would be two answers to what a tie means, and the second one would be discovered at midnight.
   * This constructor exists so the by-hand path can reach {@link #settle} without pretending to own
   * a sweep it will never run.
   */
  public static PollClock forOneCommunity(Verbose verbose) {
    return new PollClock(null, Map.of(), verbose);
  }

  /** one sweep of every community on the box */
  public int sweep(long now) {
    int settled = 0;
    for (DomainConfig config : domains.values()) {
      Accounts accounts = auth.forDomain(config.domain);
      if (accounts == null || !config.has(io.hearth.vhost.Surface.board)) {
        continue;
      }
      try {
        for (Poll.Record poll : accounts.polls.due(now, PER_PASS)) {
          settle(config, accounts, poll);
          settled++;
        }
      } catch (SQLException ex) {
        LOG.error("poll-sweep-failed domain={}", config.domain, ex);
      }
    }
    return settled;
  }

  /** count it, close it, and make what it decided */
  public Poll.Record settle(DomainConfig config, Accounts accounts, Poll.Record poll)
      throws SQLException {
    if (poll.kind() != Poll.Kind.schedule) {
      Poll.Result result = accounts.polls.result(poll.id(), Poll.Facet.choice);
      String outcome = result.decided()
          ? "Chosen: " + result.winner().option().describe()
              + " (" + result.winner().score() + " of " + result.voters() + ")"
          : "No decision -- " + result.problem() + ".";
      accounts.polls.finish(poll.id(), Poll.State.closed, null, outcome, null);
      say(accounts, poll, outcome);
      return accounts.polls.byId(poll.id());
    }

    Poll.Result when = accounts.polls.result(poll.id(), Poll.Facet.time);
    Poll.Result where = accounts.polls.result(poll.id(), Poll.Facet.place);
    if (!when.decided()) {
      String outcome = "No date -- " + when.problem() + ". Nothing has been put in the calendar.";
      accounts.polls.finish(poll.id(), Poll.State.closed, null, outcome, null);
      say(accounts, poll, outcome);
      return accounts.polls.byId(poll.id());
    }

    Poll.Option day = when.winner().option();
    // A place is optional even here. A group that voted on a day and has not settled where can
    // still have the evening put in the calendar with the location left blank -- which is a thing
    // somebody can fill in, whereas a missing event is a thing somebody has to remember.
    Poll.Option place = where.decided() ? where.winner().option() : null;
    String location = "";
    Long placeId = null;
    if (place != null && place.placeId() != null) {
      io.hearth.places.Places.Place known = accounts.places.byId(place.placeId());
      if (known != null) {
        placeId = known.id();
        location = "";
      }
    }

    Board.Post post = accounts.board.postById(poll.postId());
    String title = poll.question() == null || poll.question().isBlank()
        ? (post == null ? "An event" : post.title()) : poll.question();
    String body = "Decided by a vote on the board"
        + (post == null ? "" : ": " + post.title()) + ".";
    Calendar.Event event = accounts.calendar.create(title, body, location, placeId,
        // a one-day event, which is what a vote about an evening decides: the same day at both
        // ends, because the calendar stores a span and a null end is not one
        Calendar.State.accepted, day.onDay(), day.onDay(), day.atTime(), null, false,
        poll.createdBy(), emailOf(accounts, poll.createdBy()));

    String outcome = "Decided: " + day.describe()
        + (place == null
            ? (where.tallies().isEmpty() ? "" : ", but no place -- " + where.problem())
            : " at " + place.describe())
        + ". It is in the calendar as a draft.";
    accounts.polls.finish(poll.id(), Poll.State.converted, event.id(), outcome, poll.createdBy());
    say(accounts, poll, outcome);
    verbose.detail(() -> "poll " + poll.id() + " on " + config.domain + " became event "
        + event.id());
    return accounts.polls.byId(poll.id());
  }

  /**
   * Put the answer back where the question was asked.
   *
   * A vote that closed silently is one everybody has to remember to go and look at -- and the
   * people who need to know are exactly the people already watching this thread, who get told about
   * a comment for free. This is the whole reason a poll lives inside a conversation.
   */
  private void say(Accounts accounts, Poll.Record poll, String outcome) {
    try {
      Board.Post post = accounts.board.postById(poll.postId());
      if (post == null || post.removed() || post.locked()) {
        return;
      }
      Long author = poll.createdBy();
      if (author == null) {
        return;
      }
      accounts.board.comment(poll.postId(), null, author, emailOf(accounts, author),
          "**The vote is in.** " + outcome);
    } catch (SQLException ex) {
      // the poll is settled either way; losing the announcement is a bad day, and losing the
      // decision because the announcement failed would be a worse one
      LOG.warn("poll-announce-failed poll={}", poll.id(), ex);
    }
  }

  private static String emailOf(Accounts accounts, Long userId) {
    if (userId == null) {
      return "";
    }
    try {
      io.hearth.auth.UserRecord user = accounts.users.byId(userId);
      return user == null ? "" : user.email();
    } catch (SQLException ex) {
      return "";
    }
  }
}
