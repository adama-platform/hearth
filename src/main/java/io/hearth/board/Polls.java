package io.hearth.board;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Polls, their options and everybody's votes.
 *
 * <b>Every write emits an event on the posts table, not on its own.</b> That is deliberate and it
 * is the same trick the comment count uses: a poll is part of a conversation, the board's cache is
 * keyed by post, and a vote changing what a thread looks like has to drop the thread's cached HTML.
 * A listener on a `polls` table would be a second invalidation path that agrees with the first
 * right up until somebody adds a third.
 *
 * <b>Voting is a replace for either-or and a toggle for approval.</b> One method, because the
 * difference is one clause and two methods would be two answers to "what happens when somebody
 * votes twice". For a choice or a place, the previous vote in that facet is deleted first: there is
 * one winner, so there is one vote. For a day, only that day's vote is replaced, and voting the way
 * you already voted takes it back -- pressing up twice means you changed your mind about pressing
 * it, which is the same rule the board's own votes follow.
 */
public class Polls {
  private static final String POLL_COLUMNS =
      "id, post_id, kind, question, state, closes_at, open_options, created_by, created_at,"
          + " closed_at, event_id, outcome";
  private static final String OPTION_COLUMNS =
      "id, poll_id, facet, label, on_day, at_time, place_id, position, added_by, created_at,"
          + " removed_at";

  /** enough options to decide something; past this it is a survey with a deadline */
  public static final int MAX_OPTIONS = 30;

  private final Store store;

  public Polls(Store store) {
    this.store = store;
  }

  // ---- polls -----------------------------------------------------------------------------------

  public Poll.Record create(long postId, Poll.Kind kind, String question, Timestamp closesAt,
                            boolean openOptions, Long actor) throws SQLException {
    long id;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.POLLS + " (post_id, kind, question, state, closes_at,"
                 + " open_options, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
             Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, postId);
      statement.setString(2, kind.name());
      statement.setString(3, cap(question, 512));
      statement.setString(4, Poll.State.open.name());
      statement.setTimestamp(5, closesAt);
      statement.setBoolean(6, openOptions);
      if (actor == null) {
        statement.setNull(7, java.sql.Types.BIGINT);
      } else {
        statement.setLong(7, actor);
      }
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        id = keys.getLong(1);
      }
    }
    touched(postId, actor);
    return byId(id);
  }

  public Poll.Record byId(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + POLL_COLUMNS + " FROM " + Schema.POLLS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readPoll(rows) : null;
      }
    }
  }

  /** every poll in one conversation, oldest first, because that is the order they were asked in */
  public List<Poll.Record> forPost(long postId) throws SQLException {
    ArrayList<Poll.Record> found = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + POLL_COLUMNS + " FROM " + Schema.POLLS
                 + " WHERE post_id = ? ORDER BY id")) {
      statement.setLong(1, postId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(readPoll(rows));
        }
      }
    }
    return found;
  }

  /** open, and past its moment: the query is the queue, so a restart forgets nothing */
  public List<Poll.Record> due(long now, int limit) throws SQLException {
    ArrayList<Poll.Record> found = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + POLL_COLUMNS + " FROM " + Schema.POLLS + " WHERE state = ?"
                 + " AND closes_at IS NOT NULL AND closes_at <= ? ORDER BY closes_at "
                 + store.dialect().limit(limit))) {
      statement.setString(1, Poll.State.open.name());
      statement.setTimestamp(2, new Timestamp(now));
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(readPoll(rows));
        }
      }
    }
    return found;
  }

  /** how it ended, and what it became */
  public void finish(long id, Poll.State state, Long eventId, String outcome, Long actor)
      throws SQLException {
    Poll.Record poll = byId(id);
    if (poll == null) {
      return;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.POLLS + " SET state = ?, closed_at = CURRENT_TIMESTAMP,"
                 + " event_id = ?, outcome = ? WHERE id = ?")) {
      statement.setString(1, state.name());
      if (eventId == null) {
        statement.setNull(2, java.sql.Types.BIGINT);
      } else {
        statement.setLong(2, eventId);
      }
      statement.setString(3, cap(outcome, 512));
      statement.setLong(4, id);
      statement.executeUpdate();
    }
    touched(poll.postId(), actor);
  }

  /** put it back to taking votes, for a deadline somebody wants to extend */
  public void reopen(long id, Timestamp closesAt, Long actor) throws SQLException {
    Poll.Record poll = byId(id);
    if (poll == null) {
      return;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.POLLS + " SET state = ?, closed_at = NULL, closes_at = ?,"
                 + " outcome = '' WHERE id = ?")) {
      statement.setString(1, Poll.State.open.name());
      statement.setTimestamp(2, closesAt);
      statement.setLong(3, id);
      statement.executeUpdate();
    }
    touched(poll.postId(), actor);
  }

  // ---- options ---------------------------------------------------------------------------------

  public Poll.Option addOption(long pollId, Poll.Facet facet, String label, LocalDate onDay,
                               String atTime, Long placeId, Long actor) throws SQLException {
    Poll.Record poll = byId(pollId);
    if (poll == null) {
      return null;
    }
    long id;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.POLL_OPTIONS + " (poll_id, facet, label, on_day, at_time,"
                 + " place_id, position, added_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
             Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, pollId);
      statement.setString(2, facet.name());
      statement.setString(3, cap(label, 256));
      statement.setDate(4, onDay == null ? null : java.sql.Date.valueOf(onDay));
      statement.setString(5, cap(atTime, 64));
      if (placeId == null) {
        statement.setNull(6, java.sql.Types.BIGINT);
      } else {
        statement.setLong(6, placeId);
      }
      statement.setInt(7, nextPosition(pollId, facet));
      if (actor == null) {
        statement.setNull(8, java.sql.Types.BIGINT);
      } else {
        statement.setLong(8, actor);
      }
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        id = keys.getLong(1);
      }
    }
    touched(poll.postId(), actor);
    return optionById(id);
  }

  public Poll.Option optionById(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + OPTION_COLUMNS + " FROM " + Schema.POLL_OPTIONS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readOption(rows) : null;
      }
    }
  }

  /** everything ever put forward, removed ones included, in the order they were added */
  public List<Poll.Option> options(long pollId) throws SQLException {
    ArrayList<Poll.Option> found = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + OPTION_COLUMNS + " FROM " + Schema.POLL_OPTIONS
                 + " WHERE poll_id = ? ORDER BY facet, position, id")) {
      statement.setLong(1, pollId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(readOption(rows));
        }
      }
    }
    return found;
  }

  /**
   * Take an option off the table without taking the votes with it.
   *
   * The row stays because somebody voted for it, and a decision whose losing options quietly
   * disappeared is one nobody can read afterwards.
   */
  public boolean removeOption(long id, Long actor) throws SQLException {
    Poll.Option option = optionById(id);
    if (option == null || option.removed()) {
      return false;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.POLL_OPTIONS + " SET removed_at = CURRENT_TIMESTAMP"
                 + " WHERE id = ? AND removed_at IS NULL")) {
      statement.setLong(1, id);
      if (statement.executeUpdate() == 0) {
        return false;
      }
    }
    Poll.Record poll = byId(option.pollId());
    if (poll != null) {
      touched(poll.postId(), actor);
    }
    return true;
  }

  private int nextPosition(long pollId, Poll.Facet facet) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COALESCE(MAX(position), -1) + 1 FROM " + Schema.POLL_OPTIONS
                 + " WHERE poll_id = ? AND facet = ?")) {
      statement.setLong(1, pollId);
      statement.setString(2, facet.name());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getInt(1) : 0;
      }
    }
  }

  public int liveOptionCount(long pollId, Poll.Facet facet) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.POLL_OPTIONS + " WHERE poll_id = ? AND facet = ?"
                 + " AND removed_at IS NULL")) {
      statement.setLong(1, pollId);
      statement.setString(2, facet.name());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getInt(1) : 0;
      }
    }
  }

  // ---- votes -----------------------------------------------------------------------------------

  public List<Poll.Vote> votes(long pollId) throws SQLException {
    ArrayList<Poll.Vote> found = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT id, poll_id, option_id, facet, user_id, weight FROM " + Schema.POLL_VOTES
                 + " WHERE poll_id = ?")) {
      statement.setLong(1, pollId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(new Poll.Vote(rows.getLong("id"), rows.getLong("poll_id"),
              rows.getLong("option_id"), Poll.Facet.of(rows.getString("facet")),
              rows.getLong("user_id"), rows.getInt("weight")));
        }
      }
    }
    return found;
  }

  /** what one person said, so a screen can draw their own buttons as pressed */
  public List<Poll.Vote> votesBy(long pollId, long userId) throws SQLException {
    ArrayList<Poll.Vote> found = new ArrayList<>();
    for (Poll.Vote vote : votes(pollId)) {
      if (vote.userId() == userId) {
        found.add(vote);
      }
    }
    return found;
  }

  /**
   * Record an opinion.
   *
   * @param weight +1 or -1. A down vote is only meaningful on a day; for a choice or a place there
   *     is nothing for it to mean, so it is treated as a withdrawal.
   * @return what the vote now is: +1, -1, or 0 for "nothing, because that took it back".
   */
  public int vote(long pollId, long optionId, long userId, int weight) throws SQLException {
    Poll.Record poll = byId(pollId);
    Poll.Option option = optionById(optionId);
    if (poll == null || option == null || option.pollId() != pollId || option.removed()) {
      return 0;
    }
    Poll.Facet facet = option.facet();
    int wanted = weight >= 0 ? 1 : -1;
    if (!facet.isApproval() && weight < 0) {
      // there is no "against" for an either-or; the honest reading of a down vote is taking your
      // vote back rather than voting against everybody else
      clear(pollId, userId, facet, null);
      touched(poll.postId(), userId);
      return 0;
    }

    Integer already = weightOf(optionId, userId);
    if (already != null && already == wanted) {
      // pressing the same button twice takes it back -- the rule the board's own up and down votes
      // already follow, so there is one thing to learn rather than two
      clear(pollId, userId, facet, optionId);
      touched(poll.postId(), userId);
      return 0;
    }

    // Either-or: the previous vote anywhere in this facet goes. Approval: only this option's.
    clear(pollId, userId, facet, facet.isApproval() ? optionId : null);
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.POLL_VOTES + " (poll_id, option_id, facet, user_id, weight)"
                 + " VALUES (?, ?, ?, ?, ?)")) {
      statement.setLong(1, pollId);
      statement.setLong(2, optionId);
      statement.setString(3, facet.name());
      statement.setLong(4, userId);
      statement.setInt(5, wanted);
      statement.executeUpdate();
    }
    touched(poll.postId(), userId);
    return wanted;
  }

  private Integer weightOf(long optionId, long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT weight FROM " + Schema.POLL_VOTES + " WHERE option_id = ? AND user_id = ?")) {
      statement.setLong(1, optionId);
      statement.setLong(2, userId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getInt(1) : null;
      }
    }
  }

  /** @param onlyOption null to clear this person's whole opinion of the facet */
  private void clear(long pollId, long userId, Poll.Facet facet, Long onlyOption)
      throws SQLException {
    String sql = "DELETE FROM " + Schema.POLL_VOTES + " WHERE poll_id = ? AND user_id = ?"
        + " AND facet = ?" + (onlyOption == null ? "" : " AND option_id = ?");
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, pollId);
      statement.setLong(2, userId);
      statement.setString(3, facet.name());
      if (onlyOption != null) {
        statement.setLong(4, onlyOption);
      }
      statement.executeUpdate();
    }
  }

  // ---- counting --------------------------------------------------------------------------------

  public Poll.Result result(long pollId, Poll.Facet facet) throws SQLException {
    return Poll.count(facet, options(pollId), votes(pollId));
  }

  /**
   * Everything one person has said, and everybody's numbers, in one read.
   *
   * One query for the options and one for the votes, whatever the poll's shape -- a poll has tens
   * of votes, and per-option counting would be a query per option on the busiest page here.
   */
  public Standing standing(long pollId, Long viewer) throws SQLException {
    List<Poll.Option> options = options(pollId);
    List<Poll.Vote> votes = votes(pollId);
    java.util.LinkedHashMap<Long, Integer> mine = new java.util.LinkedHashMap<>();
    if (viewer != null) {
      for (Poll.Vote vote : votes) {
        if (vote.userId() == viewer) {
          mine.put(vote.optionId(), vote.weight());
        }
      }
    }
    return new Standing(options, votes, mine);
  }

  public record Standing(List<Poll.Option> options, List<Poll.Vote> votes,
                         java.util.Map<Long, Integer> mine) {
    public Poll.Result count(Poll.Facet facet) {
      return Poll.count(facet, options, votes);
    }
  }

  // ---- erasure ---------------------------------------------------------------------------------

  /**
   * Somebody leaving takes their votes with them.
   *
   * The counts move, and that is correct: a decision is a count of the people who are here. The
   * options they put forward stay, because those are part of what the group discussed -- and
   * `added_by` is nulled rather than the row being deleted, which is the same rule the board
   * follows for their words.
   */
  public int forget(long userId) throws SQLException {
    int gone;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.POLL_VOTES + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      gone = statement.executeUpdate();
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.POLL_OPTIONS + " SET added_by = NULL WHERE added_by = ?")) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.POLLS + " SET created_by = NULL WHERE created_by = ?")) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
    return gone;
  }

  /** a conversation being deleted takes its polls with it */
  public void forgetPost(long postId) throws SQLException {
    try (Connection connection = store.connection()) {
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.POLL_VOTES + " WHERE poll_id IN (SELECT id FROM " + Schema.POLLS
              + " WHERE post_id = ?)")) {
        statement.setLong(1, postId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.POLL_OPTIONS + " WHERE poll_id IN (SELECT id FROM "
              + Schema.POLLS + " WHERE post_id = ?)")) {
        statement.setLong(1, postId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.POLLS + " WHERE post_id = ?")) {
        statement.setLong(1, postId);
        statement.executeUpdate();
      }
    }
  }

  /**
   * Say the conversation changed, rather than saying a poll did.
   *
   * The board's cache is keyed by post and every listener that matters watches the posts table.
   * Emitting on `polls` instead would be a second invalidation path -- correct today, and wrong the
   * first time somebody adds a third thing that changes what a thread looks like.
   */
  private void touched(long postId, Long actor) {
    store.changed(Schema.POSTS, postId, MutationEvent.Kind.update, actor);
  }

  private static Poll.Record readPoll(ResultSet rows) throws SQLException {
    long eventId = rows.getLong("event_id");
    boolean noEvent = rows.wasNull();
    long createdBy = rows.getLong("created_by");
    boolean noCreator = rows.wasNull();
    return new Poll.Record(rows.getLong("id"), rows.getLong("post_id"),
        Poll.Kind.of(rows.getString("kind")), rows.getString("question"),
        Poll.State.of(rows.getString("state")), rows.getTimestamp("closes_at"),
        rows.getBoolean("open_options"), noCreator ? null : createdBy,
        rows.getTimestamp("created_at"), rows.getTimestamp("closed_at"),
        noEvent ? null : eventId, rows.getString("outcome"));
  }

  private static Poll.Option readOption(ResultSet rows) throws SQLException {
    long placeId = rows.getLong("place_id");
    boolean noPlace = rows.wasNull();
    long addedBy = rows.getLong("added_by");
    boolean noAdder = rows.wasNull();
    java.sql.Date day = rows.getDate("on_day");
    return new Poll.Option(rows.getLong("id"), rows.getLong("poll_id"),
        Poll.Facet.of(rows.getString("facet")), rows.getString("label"),
        day == null ? null : day.toLocalDate(), rows.getString("at_time"),
        noPlace ? null : placeId, rows.getInt("position"), noAdder ? null : addedBy,
        rows.getTimestamp("created_at"), rows.getTimestamp("removed_at"));
  }

  private static String cap(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}
