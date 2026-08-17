package io.hearth.calendar;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Things that are happening, and who said they would come.
 *
 * Days, not instants. A community event is "Saturday the 14th", sometimes "the 14th to the 16th",
 * and a timestamp would force a clock time onto everything and a timezone question onto every
 * reader -- for a group that meets in one town, both are ceremony. The time of day is free text
 * shown as written, because "doors at 7, music at 8" is a real answer and no time column holds it.
 *
 * Only admins create events. That is the ask, and it is also what keeps a calendar a calendar
 * rather than a second discussion board with dates on it.
 *
 * Capacity is optional and means what it says. When it is set, the seats are counted by party size
 * rather than by heads, because somebody bringing three people takes four chairs -- counting rows
 * would overfill the room by exactly the number of guests. Past capacity, an answer becomes a
 * waitlist entry rather than a refusal, and somebody dropping out promotes whoever has been waiting
 * longest and still fits.
 */
public class Calendar {
  private static final String COLUMNS =
      "id, title, body, location, place_id, state, decided_by, decided_at, decided_note,"
          + " starts_on, ends_on, start_time, capacity, published, open_to_public,"
          + " going_count, maybe_count, waitlist_count, created_at, updated_at, created_by,"
          + " created_by_email, cancelled_at, uid, sequence, invited_at";
  private static final String RSVP_COLUMNS =
      "id, event_id, user_id, user_email, answer, party, note, source, proposed_on,"
          + " proposed_time, no_show, created_at, updated_at";
  /** the most people one person can bring; a community event is not a coach party */
  public static final int MAX_PARTY = 20;

  private final Store store;

  public Calendar(Store store) {
    this.store = store;
  }

  /** what somebody said they would do */
  public enum Answer {
    /** coming, and bringing `party` people counting themselves */
    going,
    /** probably; counted separately and never against capacity, because a maybe is not a seat */
    maybe,
    /** not coming, kept as a row so the answer is on the record rather than an absence */
    no,
    /** wants to come, and the room is full */
    waitlist;

    public static Answer of(String raw) {
      if (raw == null) {
        return no;
      }
      try {
        return valueOf(raw.trim().toLowerCase());
      } catch (IllegalArgumentException ex) {
        return no;
      }
    }
  }

  /**
   * Where something is happening.
   *
   * Two fields rather than one, and the pair is the point. `placeId` is an entry in the address
   * book -- the pub the community always uses, with its address and its page and everything else
   * anybody wrote down about it -- and `location` is free text for the times it is somebody's
   * garden, a car park, or a room number inside the place. A place with a line of text beside it
   * reads "The Oak, back room", which is what a person would have written anyway.
   */
  public enum State {
    /** somebody proposed it; it is not on the calendar yet */
    suggested,
    /** it is on the calendar */
    accepted,
    /** it is not going to happen, and the person who suggested it can see why */
    declined;

    public static State of(String raw) {
      if (raw == null) {
        return accepted;
      }
      try {
        return valueOf(raw.trim().toLowerCase());
      } catch (IllegalArgumentException ex) {
        return accepted;
      }
    }
  }

  public record Event(long id, String title, String body, String location, Long placeId,
                      State state, Long decidedBy, Timestamp decidedAt, String decidedNote,
                      LocalDate startsOn,
                      LocalDate endsOn, String startTime, Integer capacity, boolean published,
                      boolean openToPublic,
                      int goingCount, int maybeCount, int waitlistCount, Timestamp createdAt,
                      Timestamp updatedAt, Long createdBy, String createdByEmail,
                      Timestamp cancelledAt, String uid, int sequence,
                      Timestamp invitedAt) {
    /** has everybody been told about the shape it is in now? */
    public boolean invitesAreCurrent() {
      return invitedAt != null && updatedAt != null && !invitedAt.before(updatedAt);
    }
    public boolean suggested() {
      return state == State.suggested;
    }

    public boolean declined() {
      return state == State.declined;
    }

    /** on the calendar: accepted, published, and not called off */
    public boolean live() {
      return state == State.accepted && published && cancelledAt == null;
    }

    public boolean cancelled() {
      return cancelledAt != null;
    }

    public boolean spansDays() {
      return !startsOn.equals(endsOn);
    }

    public boolean limited() {
      return capacity != null && capacity > 0;
    }

    /** how many seats are left, or -1 when there is no limit */
    public int seatsLeft() {
      return limited() ? Math.max(0, capacity - goingCount) : -1;
    }

    public boolean full() {
      return limited() && goingCount >= capacity;
    }

    public boolean over(LocalDate today) {
      return endsOn.isBefore(today);
    }

    public boolean today(LocalDate today) {
      return !startsOn.isAfter(today) && !endsOn.isBefore(today);
    }
  }

  public record Rsvp(long id, long eventId, long userId, String userEmail, Answer answer, int party,
                     String note, String source, LocalDate proposedOn, String proposedTime,
                     boolean noShow, Timestamp createdAt, Timestamp updatedAt) {
    /** did this come back from a calendar program rather than from a button on the site? */
    public boolean fromEmail() {
      return "email".equals(source);
    }

    /** have they suggested a different day? */
    public boolean proposesATime() {
      return proposedOn != null;
    }
  }

  // ---- the events ------------------------------------------------------------------------------

  public Event create(String title, String body, String location, LocalDate startsOn,
                      LocalDate endsOn, String startTime, Integer capacity, boolean published,
                      Long actor, String actorEmail) throws SQLException {
    return create(title, body, location, null, State.accepted, startsOn, endsOn, startTime,
        capacity, published, actor, actorEmail);
  }

  public Event create(String title, String body, String location, Long placeId, State state,
                      LocalDate startsOn, LocalDate endsOn, String startTime, Integer capacity,
                      boolean published, Long actor, String actorEmail) throws SQLException {
    long id;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.CALENDAR + " (title, body, location, starts_on, ends_on,"
                 + " start_time, capacity, published, created_by, created_by_email, place_id, state)"
                 + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
      bind(statement, title, body, location, startsOn, endsOn, startTime, capacity, published);
      if (actor == null) {
        statement.setNull(9, java.sql.Types.BIGINT);
      } else {
        statement.setLong(9, actor);
      }
      statement.setString(10, cap(actorEmail, 320));
      if (placeId == null) {
        statement.setNull(11, java.sql.Types.BIGINT);
      } else {
        statement.setLong(11, placeId);
      }
      statement.setString(12, (state == null ? State.accepted : state).name());
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        id = keys.getLong(1);
      }
    }
    store.changed(Schema.CALENDAR, id, MutationEvent.Kind.insert, actor);
    return byId(id);
  }

  public Event update(long id, String title, String body, String location, LocalDate startsOn,
                      LocalDate endsOn, String startTime, Integer capacity, boolean published,
                      Long actor) throws SQLException {
    return update(id, title, body, location, null, startsOn, endsOn, startTime, capacity,
        published, actor);
  }

  public Event update(long id, String title, String body, String location, Long placeId,
                      LocalDate startsOn, LocalDate endsOn, String startTime, Integer capacity,
                      boolean published, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.CALENDAR + " SET title = ?, body = ?, location = ?, starts_on = ?,"
                 + " ends_on = ?, start_time = ?, capacity = ?, published = ?, place_id = ?,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      bind(statement, title, body, location, startsOn, endsOn, startTime, capacity, published);
      if (placeId == null) {
        statement.setNull(9, java.sql.Types.BIGINT);
      } else {
        statement.setLong(9, placeId);
      }
      statement.setLong(10, id);
      statement.executeUpdate();
    }
    store.changed(Schema.CALENDAR, id, MutationEvent.Kind.update, actor);
    // capacity may have moved, so who is seated and who is waiting may have changed with it
    reseat(id, actor);
    return byId(id);
  }

  /**
   * Call it off without deleting it.
   *
   * A cancelled event keeps its page and its guest list, because the people who said they were
   * coming are exactly the people who need to see that it is not happening -- deleting it would
   * make it vanish from the calendar of everybody who had already planned around it.
   */
  public void cancel(long id, boolean cancelled, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.CALENDAR + " SET cancelled_at = " + (cancelled ? "CURRENT_TIMESTAMP" : "NULL")
                 + ", updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.CALENDAR, id, MutationEvent.Kind.update, actor);
  }

  /**
   * Anybody may come to this one.
   *
   * Its own action rather than a field on the form, for the same reason cancelling is: it changes
   * who can read the event and where its answers may come from, and a checkbox in the middle of a
   * form full of times and capacities is a decision made by accident. Turning it off leaves the
   * answers that already arrived -- those people still said they were coming, and deleting the
   * record of it would lose the only list of them anybody has.
   */
  public void openToPublic(long id, boolean open, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.CALENDAR + " SET open_to_public = ?,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setBoolean(1, open);
      statement.setLong(2, id);
      statement.executeUpdate();
    }
    store.changed(Schema.CALENDAR, id, MutationEvent.Kind.update, actor);
  }

  /**
   * Give this event its calendar identity, once.
   *
   * A UID is generated the first time somebody is invited rather than at creation, because a
   * suggestion that was never accepted should not be occupying an identity in anybody's calendar.
   * Once it exists it never changes: a REPLY arriving six weeks later carries only this string.
   */
  public Event stampUid(long id, String uid) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.CALENDAR + " SET uid = ? WHERE id = ? AND uid = ''")) {
      statement.setString(1, cap(uid, 190));
      statement.setLong(2, id);
      statement.executeUpdate();
    }
    return byId(id);
  }

  /**
   * The details changed, so every calendar holding this event needs to hear about it.
   *
   * The sequence is what makes an update land: a client ignores a REQUEST whose sequence is not
   * higher than the one it already has, which is exactly the behaviour you want when a message is
   * delivered twice and exactly the trap you fall into if you never raise it.
   */
  public Event bumpSequence(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.CALENDAR + " SET sequence = sequence + 1,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.CALENDAR, id, MutationEvent.Kind.update, actor);
    return byId(id);
  }

  /** everybody has been told about the shape it is in now */
  public void markInvited(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.CALENDAR + " SET invited_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
  }

  /** the event a calendar reply is about, found by the only thing the reply carries */
  public Event byUid(String uid) throws SQLException {
    if (uid == null || uid.isBlank()) {
      return null;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.CALENDAR + " WHERE uid = ?")) {
      statement.setString(1, uid.trim());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  public void delete(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement rsvps = connection.prepareStatement(
             "DELETE FROM " + Schema.RSVPS + " WHERE event_id = ?");
         PreparedStatement outsiders = connection.prepareStatement(
             "DELETE FROM " + Schema.PUBLIC_RSVPS + " WHERE event_id = ?");
         PreparedStatement event = connection.prepareStatement(
             "DELETE FROM " + Schema.CALENDAR + " WHERE id = ?")) {
      rsvps.setLong(1, id);
      rsvps.executeUpdate();
      outsiders.setLong(1, id);
      outsiders.executeUpdate();
      event.setLong(1, id);
      event.executeUpdate();
    }
    store.changed(Schema.CALENDAR, id, MutationEvent.Kind.delete, actor);
  }

  public Event byId(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.CALENDAR + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  /** what is still to come, soonest first; the front page of a calendar */
  public List<Event> upcoming(LocalDate today, int limit) throws SQLException {
    return query("WHERE published = TRUE AND state = 'accepted' AND ends_on >= ?"
        + " ORDER BY starts_on ASC", today, limit);
  }

  /** what already happened, most recent first */
  public List<Event> past(LocalDate today, int limit) throws SQLException {
    return query("WHERE published = TRUE AND state = 'accepted' AND ends_on < ?"
        + " ORDER BY starts_on DESC", today, limit);
  }

  /**
   * What members have put forward, oldest first.
   *
   * Oldest first on purpose: a queue people are waiting in is answered in the order they joined it,
   * and a suggestion that keeps sinking under newer ones is a member learning that suggesting
   * things does nothing.
   */
  public List<Event> suggestions(int limit) throws SQLException {
    ArrayList<Event> events = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.CALENDAR + " WHERE state = 'suggested'"
                 + " ORDER BY created_at ASC " + store.dialect().limit(limit));
         ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        events.add(read(rows));
      }
    }
    return events;
  }

  public int openSuggestions() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.CALENDAR + " WHERE state = 'suggested'");
         ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getInt(1) : 0;
    }
  }

  /**
   * Say yes or no to a suggestion.
   *
   * Accepting publishes it, because a suggestion that was accepted and then sat unpublished is the
   * worst of both -- the person who suggested it sees "accepted" and nobody else sees anything.
   * Declining keeps the row and the reason: a queue where work quietly disappears is one nobody
   * uses twice.
   */
  public void decide(long id, State state, String note, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.CALENDAR + " SET state = ?, decided_by = ?,"
                 + " decided_at = CURRENT_TIMESTAMP, decided_note = ?,"
                 + " published = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setString(1, state.name());
      if (actor == null) {
        statement.setNull(2, java.sql.Types.BIGINT);
      } else {
        statement.setLong(2, actor);
      }
      statement.setString(3, cap(note, 512));
      statement.setBoolean(4, state == State.accepted);
      statement.setLong(5, id);
      statement.executeUpdate();
    }
    store.changed(Schema.CALENDAR, id, MutationEvent.Kind.update, actor);
  }

  /** everything, drafts and all; the admin listing */
  public List<Event> all(int limit) throws SQLException {
    ArrayList<Event> events = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.CALENDAR + " ORDER BY starts_on DESC "
                 + store.dialect().limit(limit))) {
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          events.add(read(rows));
        }
      }
    }
    return events;
  }

  private List<Event> query(String where, LocalDate today, int limit) throws SQLException {
    ArrayList<Event> events = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.CALENDAR + " " + where + " "
                 + store.dialect().limit(limit))) {
      statement.setDate(1, Date.valueOf(today));
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          events.add(read(rows));
        }
      }
    }
    return events;
  }

  // ---- the answers -----------------------------------------------------------------------------

  /**
   * Say what you are doing, or change your mind.
   *
   * One row per person per event, updated in place, so changing an answer is a change rather than a
   * second opinion. Answering "going" when the room is full lands on the waitlist and says so; the
   * caller reads the answer back off the returned row rather than assuming it got what it asked
   * for, which is the difference between a waitlist and a lie.
   */
  public Rsvp answer(long eventId, long userId, String userEmail, Answer wanted, int party,
                     String note) throws SQLException {
    return answer(eventId, userId, userEmail, wanted, party, note, "web");
  }

  /**
   * @param source `web` when somebody pressed a button here, `email` when their calendar program
   *     replied to the invitation. Kept because the two are different people to follow up with:
   *     somebody who answers from their calendar may never have opened this site at all.
   */
  public Rsvp answer(long eventId, long userId, String userEmail, Answer wanted, int party,
                     String note, String source) throws SQLException {
    Event event = byId(eventId);
    if (event == null) {
      return null;
    }
    int size = Math.max(1, Math.min(party, MAX_PARTY));
    Rsvp existing = rsvpFor(eventId, userId);
    Answer settled = wanted;
    if (wanted == Answer.going && event.limited()) {
      int seatedWithoutThem = event.goingCount()
          - (existing != null && existing.answer() == Answer.going ? existing.party() : 0);
      if (seatedWithoutThem + size > event.capacity()) {
        settled = Answer.waitlist;
      }
    }

    if (existing == null) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.RSVPS + " (event_id, user_id, user_email, answer, party,"
                   + " note, source) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
        statement.setLong(1, eventId);
        statement.setLong(2, userId);
        statement.setString(3, cap(userEmail, 320));
        statement.setString(4, settled.name());
        statement.setInt(5, size);
        statement.setString(6, cap(note, 512));
        statement.setString(7, "email".equals(source) ? "email" : "web");
        statement.executeUpdate();
      }
    } else {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "UPDATE " + Schema.RSVPS + " SET answer = ?, party = ?, note = ?, source = ?,"
                   + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
        statement.setString(1, settled.name());
        statement.setInt(2, size);
        statement.setString(3, cap(note, 512));
        statement.setString(4, "email".equals(source) ? "email" : "web");
        statement.setLong(5, existing.id());
        statement.executeUpdate();
      }
    }
    store.changed(Schema.RSVPS, eventId, MutationEvent.Kind.update, userId);
    reseat(eventId, userId);
    return rsvpFor(eventId, userId);
  }

  /**
   * Somebody's calendar suggested a different day.
   *
   * <b>Recorded, never applied.</b> An iCalendar COUNTER is a request, and a calendar where any
   * attendee can move the event is not a calendar anybody can plan around -- so it sits on their
   * row as a suggestion the organiser can see, take, or leave. Taking it is a reschedule like any
   * other, which is what makes it safe: everybody gets a fresh invitation with a higher sequence.
   */
  public void propose(long eventId, long userId, LocalDate day, String time) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.RSVPS + " SET proposed_on = ?, proposed_time = ?,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE event_id = ? AND user_id = ?")) {
      if (day == null) {
        statement.setNull(1, java.sql.Types.DATE);
      } else {
        statement.setDate(1, Date.valueOf(day));
      }
      statement.setString(2, cap(time, 64));
      statement.setLong(3, eventId);
      statement.setLong(4, userId);
      statement.executeUpdate();
    }
    store.changed(Schema.RSVPS, eventId, MutationEvent.Kind.update, userId);
  }

  /**
   * Said they were coming and was not there.
   *
   * Recorded by a person who was there, never inferred from anything. A community that guesses at
   * this from a lack of activity will eventually mark somebody absent who was standing in the
   * kitchen all evening, and the number exists to be talked about rather than to be enforced.
   */
  public void markNoShow(long eventId, long userId, boolean noShow, Long actor)
      throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.RSVPS + " SET no_show = ?, updated_at = CURRENT_TIMESTAMP"
                 + " WHERE event_id = ? AND user_id = ?")) {
      statement.setBoolean(1, noShow);
      statement.setLong(2, eventId);
      statement.setLong(3, userId);
      statement.executeUpdate();
    }
    store.changed(Schema.RSVPS, eventId, MutationEvent.Kind.update, actor);
  }

  /**
   * Move an event, and decide what happens to the answers.
   *
   * <b>This is the tricky one, and the trickiness is real rather than technical.</b> Forty people
   * said yes to a Tuesday. It is now a Thursday. Keeping their answers means the guest list claims
   * forty people are coming to an evening none of them agreed to; clearing them means starting from
   * nothing and probably ending up with twelve. Neither is right in general, because which one is
   * right depends on how far it moved and what kind of community it is -- so this does not choose.
   * The screen asks, with the consequence spelt out, and whoever moves the event decides.
   *
   * <b>Clearing keeps the "no"s.</b> Somebody who said they could not come to a thing has told you
   * something that is probably still true, and asking them again because the day shifted by two is
   * how a community teaches people that answering does not stick. It is also the answer that costs
   * nothing to be wrong about: they can change it.
   *
   * Either way the sequence goes up and everybody needs a fresh invitation, because their calendar
   * is holding a day that no longer exists.
   */
  public int reschedule(long id, LocalDate startsOn, LocalDate endsOn, boolean keepAnswers,
                        Long actor) throws SQLException {
    Event event = byId(id);
    if (event == null) {
      return 0;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.CALENDAR + " SET starts_on = ?, ends_on = ?,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setDate(1, Date.valueOf(startsOn));
      statement.setDate(2, Date.valueOf(endsOn == null || endsOn.isBefore(startsOn)
          ? startsOn : endsOn));
      statement.setLong(3, id);
      statement.executeUpdate();
    }
    int cleared = 0;
    if (!keepAnswers) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "DELETE FROM " + Schema.RSVPS + " WHERE event_id = ? AND answer <> 'no'")) {
        statement.setLong(1, id);
        cleared = statement.executeUpdate();
      }
      reseat(id, actor);
    }
    store.changed(Schema.CALENDAR, id, MutationEvent.Kind.update, actor);
    return cleared;
  }

  /** take an answer back entirely, which is different from saying no */
  public void withdraw(long eventId, long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.RSVPS + " WHERE event_id = ? AND user_id = ?")) {
      statement.setLong(1, eventId);
      statement.setLong(2, userId);
      statement.executeUpdate();
    }
    store.changed(Schema.RSVPS, eventId, MutationEvent.Kind.delete, userId);
    reseat(eventId, userId);
  }

  public Rsvp rsvpFor(long eventId, long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + RSVP_COLUMNS + " FROM " + Schema.RSVPS
                 + " WHERE event_id = ? AND user_id = ?")) {
      statement.setLong(1, eventId);
      statement.setLong(2, userId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readRsvp(rows) : null;
      }
    }
  }

  /** everybody who answered, in the order they answered */
  public List<Rsvp> guestList(long eventId) throws SQLException {
    ArrayList<Rsvp> list = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + RSVP_COLUMNS + " FROM " + Schema.RSVPS + " WHERE event_id = ?"
                 + " ORDER BY created_at ASC, id ASC")) {
      statement.setLong(1, eventId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          list.add(readRsvp(rows));
        }
      }
    }
    return list;
  }

  /** what one person has said yes to, soonest first */
  public List<Event> forUser(long userId, LocalDate today, int limit) throws SQLException {
    ArrayList<Event> events = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + prefixed(COLUMNS, "c") + " FROM " + Schema.CALENDAR + " c JOIN "
                 + Schema.RSVPS + " r ON r.event_id = c.id WHERE r.user_id = ?"
                 + " AND r.answer IN ('going', 'waitlist') AND c.ends_on >= ?"
                 + " AND c.published = TRUE ORDER BY c.starts_on ASC "
                 + store.dialect().limit(limit))) {
      statement.setLong(1, userId);
      statement.setDate(2, Date.valueOf(today));
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          events.add(read(rows));
        }
      }
    }
    return events;
  }

  /**
   * Recount, and promote anybody the room now has space for.
   *
   * Run after every change rather than maintained incrementally, because the counts are a cache of
   * the rows and an increment that drifts is a room that is full when it is not. For an event with
   * a few dozen answers this is one query and a handful of updates.
   *
   * Promotion is by how long somebody has been waiting, and it skips a party that does not fit
   * rather than stopping -- so one person waiting for six seats does not block five people waiting
   * for one each. It never demotes: somebody already seated stays seated even if the capacity
   * shrinks under them, because taking back a yes is a thing a person should do, not a sweep.
   */
  public void reseat(long eventId, Long actor) throws SQLException {
    Event event = byId(eventId);
    if (event == null) {
      return;
    }
    List<Rsvp> list = guestList(eventId);
    int seated = 0;
    for (Rsvp rsvp : list) {
      if (rsvp.answer() == Answer.going) {
        seated += rsvp.party();
      }
    }
    if (event.limited()) {
      for (Rsvp rsvp : list) {
        if (rsvp.answer() != Answer.waitlist) {
          continue;
        }
        if (seated + rsvp.party() <= event.capacity()) {
          setAnswer(rsvp.id(), Answer.going);
          seated += rsvp.party();
        }
      }
    } else {
      // the limit was lifted, so nobody is waiting for anything any more
      for (Rsvp rsvp : list) {
        if (rsvp.answer() == Answer.waitlist) {
          setAnswer(rsvp.id(), Answer.going);
          seated += rsvp.party();
        }
      }
    }

    int going = 0;
    int maybe = 0;
    int waiting = 0;
    for (Rsvp rsvp : guestList(eventId)) {
      switch (rsvp.answer()) {
        case going -> going += rsvp.party();
        case maybe -> maybe += rsvp.party();
        case waitlist -> waiting += rsvp.party();
        default -> {
        }
      }
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.CALENDAR + " SET going_count = ?, maybe_count = ?,"
                 + " waitlist_count = ? WHERE id = ?")) {
      statement.setInt(1, going);
      statement.setInt(2, maybe);
      statement.setInt(3, waiting);
      statement.setLong(4, eventId);
      statement.executeUpdate();
    }
    store.changed(Schema.CALENDAR, eventId, MutationEvent.Kind.update, actor);
  }

  /** everything belonging to somebody who is leaving */
  public void forget(long userId) throws SQLException {
    List<Long> touched = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT event_id FROM " + Schema.RSVPS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          touched.add(rows.getLong(1));
        }
      }
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.RSVPS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
    for (Long eventId : touched) {
      reseat(eventId, null);
    }
  }

  // ---- the people who are not members yet --------------------------------------------------------

  /**
   * Somebody with no account here who said they were coming.
   *
   * Kept apart from the guest list on purpose. Everything in `rsvps` is keyed on a member -- the
   * seat counting, the export, the erasure, the no-show marking -- and a row in there standing for
   * somebody with no account would mean every one of those queries growing a clause that the first
   * person to forget would get wrong.
   *
   * What it is for is the invitation chain. Somebody who found out about a thing, said they were
   * coming, and has never been asked to join is the strongest lead a small community gets.
   */
  public record Outsider(long id, long eventId, String email, String name, Answer answer, int party,
                         String note, String source, Timestamp invitedAt, Timestamp convertedAt,
                         Long convertedUserId, Timestamp createdAt) {
    public boolean invited() {
      return invitedAt != null;
    }

    public boolean converted() {
      return convertedAt != null;
    }

    /** what to call them: what their calendar said, or their address if it said nothing */
    public String display() {
      return name == null || name.isBlank() ? email : name;
    }
  }

  private static final String OUTSIDER_COLUMNS =
      "id, event_id, email, name, answer, party, note, source, invited_at, converted_at,"
          + " converted_user_id, created_at";

  /**
   * Write down an answer from outside, or change one already there.
   *
   * <b>It counts nobody into the room.</b> The seat arithmetic is deliberately untouched: capacity
   * is a promise to the people a community can actually reach, and a stranger's answer is a fact
   * about interest rather than a claim on a chair. An administrator who wants them counted invites
   * them, and then they are counted like anybody else.
   */
  public Outsider answerPublicly(long eventId, String email, String name, Answer answer, int party,
                                 String note, String source) throws SQLException {
    String address = email == null ? "" : email.trim().toLowerCase();
    if (address.isBlank()) {
      return null;
    }
    Outsider existing = outsiderFor(eventId, address);
    int size = Math.max(1, Math.min(party, MAX_PARTY));
    // a waitlist is a thing that happens to members; from outside there are three answers
    Answer settled = answer == Answer.waitlist ? Answer.maybe : answer;
    if (existing == null) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.PUBLIC_RSVPS + " (event_id, email, name, answer, party,"
                   + " note, source) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
        statement.setLong(1, eventId);
        statement.setString(2, cap(address, 320));
        statement.setString(3, cap(name, 190));
        statement.setString(4, settled.name());
        statement.setInt(5, size);
        statement.setString(6, cap(note, 512));
        statement.setString(7, "web".equals(source) ? "web" : "email");
        statement.executeUpdate();
      }
    } else {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "UPDATE " + Schema.PUBLIC_RSVPS + " SET name = ?, answer = ?, party = ?, note = ?,"
                   + " source = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
        // a name arriving blank on the second message does not erase the one the first one carried
        statement.setString(1, cap(name == null || name.isBlank() ? existing.name() : name, 190));
        statement.setString(2, settled.name());
        statement.setInt(3, size);
        statement.setString(4, cap(note, 512));
        statement.setString(5, "web".equals(source) ? "web" : "email");
        statement.setLong(6, existing.id());
        statement.executeUpdate();
      }
    }
    store.changed(Schema.PUBLIC_RSVPS, eventId, MutationEvent.Kind.update, null);
    return outsiderFor(eventId, address);
  }

  public Outsider outsiderFor(long eventId, String email) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + OUTSIDER_COLUMNS + " FROM " + Schema.PUBLIC_RSVPS
                 + " WHERE event_id = ? AND email = ?")) {
      statement.setLong(1, eventId);
      statement.setString(2, email == null ? "" : email.trim().toLowerCase());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readOutsider(rows) : null;
      }
    }
  }

  /** everybody from outside who answered about one event, oldest first */
  public List<Outsider> outsiders(long eventId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + OUTSIDER_COLUMNS + " FROM " + Schema.PUBLIC_RSVPS
                 + " WHERE event_id = ? ORDER BY created_at")) {
      statement.setLong(1, eventId);
      return readOutsiders(statement);
    }
  }

  /**
   * Everybody from outside who has not joined yet, newest first.
   *
   * The audit list. Somebody who answered about three events is three rows, deliberately: the
   * question an administrator is answering is "has this person been asked", and the number of times
   * they turned up to something is most of the answer.
   */
  public List<Outsider> outsidersWaiting(int limit) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + OUTSIDER_COLUMNS + " FROM " + Schema.PUBLIC_RSVPS
                 + " WHERE converted_at IS NULL ORDER BY created_at DESC"
                 + store.dialect().limit(Math.max(1, Math.min(limit, 500))))) {
      return readOutsiders(statement);
    }
  }

  public void markOutsiderInvited(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PUBLIC_RSVPS + " SET invited_at = CURRENT_TIMESTAMP,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.PUBLIC_RSVPS, id, MutationEvent.Kind.update, actor);
  }

  /**
   * They joined, so what they said from outside becomes what they said.
   *
   * Every outstanding answer at this address turns into an ordinary RSVP -- which is the moment the
   * seat arithmetic starts counting them, because now they are somebody this community can reach.
   * An event that has already happened is skipped: turning up in a guest list for last March would
   * be inventing a history rather than keeping one.
   *
   * @return how many answers came across
   */
  public int adopt(long userId, String email, LocalDate today) throws SQLException {
    String address = email == null ? "" : email.trim().toLowerCase();
    if (address.isBlank()) {
      return 0;
    }
    List<Outsider> waiting = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + OUTSIDER_COLUMNS + " FROM " + Schema.PUBLIC_RSVPS
                 + " WHERE email = ? AND converted_at IS NULL")) {
      statement.setString(1, address);
      waiting.addAll(readOutsiders(statement));
    }
    int moved = 0;
    for (Outsider outsider : waiting) {
      Event event = byId(outsider.eventId());
      if (event == null || event.over(today) || event.cancelled()) {
        continue;
      }
      answer(outsider.eventId(), userId, address, outsider.answer(), outsider.party(),
          outsider.note(), outsider.source());
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "UPDATE " + Schema.PUBLIC_RSVPS + " SET converted_at = CURRENT_TIMESTAMP,"
                   + " converted_user_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
        statement.setLong(1, userId);
        statement.setLong(2, outsider.id());
        statement.executeUpdate();
      }
      store.changed(Schema.PUBLIC_RSVPS, outsider.id(), MutationEvent.Kind.update, userId);
      moved++;
    }
    return moved;
  }

  /** every answer from outside carrying one address, which erasure has to take with it */
  public void forgetOutsider(String email) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.PUBLIC_RSVPS + " WHERE email = ?")) {
      statement.setString(1, email == null ? "" : email.trim().toLowerCase());
      statement.executeUpdate();
    }
  }

  private List<Outsider> readOutsiders(PreparedStatement statement) throws SQLException {
    ArrayList<Outsider> out = new ArrayList<>();
    try (ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        out.add(readOutsider(rows));
      }
    }
    return out;
  }

  private static Outsider readOutsider(ResultSet rows) throws SQLException {
    Long converted = rows.getLong("converted_user_id");
    if (rows.wasNull()) {
      converted = null;
    }
    return new Outsider(rows.getLong("id"), rows.getLong("event_id"), rows.getString("email"),
        rows.getString("name"), Answer.of(rows.getString("answer")), rows.getInt("party"),
        rows.getString("note"), rows.getString("source"), rows.getTimestamp("invited_at"),
        rows.getTimestamp("converted_at"), converted, rows.getTimestamp("created_at"));
  }

  public long count() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.CALENDAR)) {
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private void setAnswer(long rsvpId, Answer answer) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.RSVPS + " SET answer = ?, updated_at = CURRENT_TIMESTAMP"
                 + " WHERE id = ?")) {
      statement.setString(1, answer.name());
      statement.setLong(2, rsvpId);
      statement.executeUpdate();
    }
  }

  private static void bind(PreparedStatement statement, String title, String body, String location,
                           LocalDate startsOn, LocalDate endsOn, String startTime, Integer capacity,
                           boolean published) throws SQLException {
    statement.setString(1, cap(title, 256));
    statement.setString(2, body == null ? "" : body);
    statement.setString(3, cap(location, 512));
    statement.setDate(4, Date.valueOf(startsOn));
    // an end before a start is a typo, not a span; the shorter reading is the safe one
    statement.setDate(5, Date.valueOf(endsOn.isBefore(startsOn) ? startsOn : endsOn));
    statement.setString(6, cap(startTime, 64));
    if (capacity == null || capacity <= 0) {
      statement.setNull(7, java.sql.Types.INTEGER);
    } else {
      statement.setInt(7, capacity);
    }
    statement.setBoolean(8, published);
  }

  private static String prefixed(String columns, String alias) {
    StringBuilder sb = new StringBuilder();
    for (String column : columns.split(",")) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(alias).append('.').append(column.trim());
    }
    return sb.toString();
  }

  private static Event read(ResultSet rows) throws SQLException {
    Integer capacity = rows.getInt("capacity");
    if (rows.wasNull()) {
      capacity = null;
    }
    Long createdBy = rows.getLong("created_by");
    if (rows.wasNull()) {
      createdBy = null;
    }
    Long placeId = rows.getLong("place_id");
    if (rows.wasNull()) {
      placeId = null;
    }
    Long decidedBy = rows.getLong("decided_by");
    if (rows.wasNull()) {
      decidedBy = null;
    }
    return new Event(rows.getLong("id"), rows.getString("title"), rows.getString("body"),
        rows.getString("location"), placeId, State.of(rows.getString("state")), decidedBy,
        rows.getTimestamp("decided_at"), rows.getString("decided_note"),
        rows.getDate("starts_on").toLocalDate(),
        rows.getDate("ends_on").toLocalDate(), rows.getString("start_time"), capacity,
        rows.getBoolean("published"), rows.getBoolean("open_to_public"),
        rows.getInt("going_count"), rows.getInt("maybe_count"),
        rows.getInt("waitlist_count"), rows.getTimestamp("created_at"),
        rows.getTimestamp("updated_at"), createdBy, rows.getString("created_by_email"),
        rows.getTimestamp("cancelled_at"), rows.getString("uid"), rows.getInt("sequence"),
        rows.getTimestamp("invited_at"));
  }

  private static Rsvp readRsvp(ResultSet rows) throws SQLException {
    Date proposed = rows.getDate("proposed_on");
    return new Rsvp(rows.getLong("id"), rows.getLong("event_id"), rows.getLong("user_id"),
        rows.getString("user_email"), Answer.of(rows.getString("answer")), rows.getInt("party"),
        rows.getString("note"), rows.getString("source"),
        proposed == null ? null : proposed.toLocalDate(), rows.getString("proposed_time"),
        rows.getBoolean("no_show"), rows.getTimestamp("created_at"),
        rows.getTimestamp("updated_at"));
  }

  private static String cap(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}
