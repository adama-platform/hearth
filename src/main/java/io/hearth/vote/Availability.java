package io.hearth.vote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * When somebody is free, without handing anybody their calendar.
 *
 * <b>Two answers, and which one you are getting is part of the answer.</b> Somebody who trusts an
 * agent with their calendar publishes an ICS url and the agent can read real engagements. Somebody
 * who does not writes down a weekly shape -- "most evenings, never Wednesday, Sundays are for the
 * ranch" -- and the agent is told plainly that is what it is holding.
 *
 * <b>Telling the two apart is the whole point.</b> An agent handed a rough weekly shape and left to
 * assume it is a calendar will confidently propose a night somebody has had booked for a month, and
 * the person it is acting for will look careless. So {@link Kind} rides on every answer and the
 * tool descriptions say what to do with each: a calendar can be checked, a shape has to be
 * confirmed with the human before anything is promised.
 *
 * <b>This server does not fetch the ICS.</b> It hands the url to the agent that asked. Fetching it
 * here would mean this server holding a copy of somebody's calendar -- exactly the thing the person
 * who chose the weekly shape was avoiding -- and it would be a member-supplied url turned into an
 * outbound request on a schedule, which invariant 150 is about. The agent already has network
 * access and a relationship with its person; this does not need one.
 */
public class Availability {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Store store;

  public Availability(Store store) {
    this.store = store;
  }

  /** what kind of answer this is, which an agent has to be told */
  public enum Kind {
    /** nothing recorded; the agent has to ask */
    unknown,
    /** a weekly shape in the person's own words: a proxy, not a calendar */
    weekly,
    /** an ICS url the agent may read for real engagements */
    calendar,
    /** both: read the calendar, and the weekly shape says what they *prefer* */
    both
  }

  /**
   * How movable somebody's week is, which a free/busy grid cannot say.
   *
   * <b>This is the seed, and it is the difference between a useful first proposal and a round of
   * guessing.</b> One person hosts and has a calendar full of things that could shift; another is
   * free most evenings and immovable on three. An agent told only "busy Tuesday" proposes the same
   * things to both.
   */
  public enum Flexibility {
    /** free most of the time; the calendar is the exception rather than the rule */
    mostly_free("Free most evenings. Propose freely; the calendar shows the exceptions."),
    /** it depends on the week */
    it_depends("It varies. Check the calendar and treat anything unconfirmed as a proposal."),
    /** a full calendar; assume a conflict unless the calendar says otherwise */
    tightly_booked("A full week. Do not assume an evening is free -- check, and expect that"
        + " something may have to move.");

    public final String advice;

    Flexibility(String advice) {
      this.advice = advice;
    }

    public static Flexibility of(String raw) {
      if (raw == null) {
        return it_depends;
      }
      try {
        return valueOf(raw.trim().toLowerCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return it_depends;
      }
    }
  }

  public record Record(long userId, String weekly, String notes, String icsUrl,
                       boolean hosts, Flexibility flexibility, Timestamp updatedAt) {
    public boolean hasWeekly() {
      return weekly != null && !weekly.isBlank() && !weekly.trim().equals("{}");
    }

    public boolean hasCalendar() {
      return icsUrl != null && !icsUrl.isBlank();
    }

    public Kind kind() {
      if (hasCalendar() && hasWeekly()) {
        return Kind.both;
      }
      if (hasCalendar()) {
        return Kind.calendar;
      }
      return hasWeekly() ? Kind.weekly : Kind.unknown;
    }

    /** what an agent should do with what it just got, in one sentence */
    public String advice() {
      return switch (kind()) {
        case unknown -> "Nothing is recorded for this person. Do not guess when they are free --"
            + " propose options and let them vote.";
        case weekly -> "This is a rough weekly shape they typed, NOT their calendar. It says what"
            + " they usually can do, not what they have already agreed to. Treat anything you"
            + " build on it as a proposal to be confirmed, never as a commitment.";
        case calendar -> "An ICS url they are willing to share. Fetch it yourself and read real"
            + " engagements from it; this server does not hold a copy.";
        case both -> "An ICS url for what they have already agreed to, and a weekly shape for what"
            + " they would prefer. The calendar says what is impossible; the shape says what is"
            + " welcome.";
      };
    }

    public JsonNode weeklyJson() {
      try {
        return weekly == null || weekly.isBlank()
            ? JSON.createObjectNode() : JSON.readTree(weekly);
      } catch (Exception ex) {
        return JSON.createObjectNode();
      }
    }
  }

  public Record of(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.AVAILABILITY + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      try (ResultSet found = statement.executeQuery()) {
        if (!found.next()) {
          return new Record(userId, "{}", "", "", false, Flexibility.it_depends, null);
        }
        return new Record(found.getLong("user_id"), found.getString("weekly"),
            found.getString("notes"), found.getString("ics_url"),
            found.getBoolean("hosts"), Flexibility.of(found.getString("flexibility")),
            found.getTimestamp("updated_at"));
      }
    }
  }

  /**
   * Save what somebody is willing to say.
   *
   * The url is checked to be https, because an http one is a calendar travelling in the clear and
   * this is the one field here that is somebody's whole schedule.
   */
  public void save(long userId, String weekly, String notes, String icsUrl)
      throws SQLException, Votes.Refused {
    Record held = of(userId);
    save(userId, weekly, notes, icsUrl, held.hosts(), held.flexibility());
  }

  public void save(long userId, String weekly, String notes, String icsUrl, boolean hosts,
                   Flexibility flexibility) throws SQLException, Votes.Refused {
    String url = icsUrl == null ? "" : icsUrl.trim();
    if (!url.isEmpty()) {
      if (!url.startsWith("https://") || url.length() > 1024) {
        throw new Votes.Refused("a calendar link has to be an https url. An http one is your whole"
            + " schedule travelling in the clear.");
      }
    }
    String shape = weekly == null || weekly.isBlank() ? "{}" : weekly.trim();
    try {
      JSON.readTree(shape);
    } catch (Exception ex) {
      throw new Votes.Refused("the weekly shape has to be a JSON object, e.g."
          + " {\"tuesday\":\"after 6\",\"wednesday\":\"never\"}");
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "MERGE INTO " + Schema.AVAILABILITY
                 + " (user_id, weekly, notes, ics_url, hosts, flexibility, updated_at)"
                 + " KEY (user_id) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
      statement.setLong(1, userId);
      statement.setString(2, shape);
      statement.setString(3, notes == null ? "" : notes.trim());
      statement.setString(4, url);
      statement.setBoolean(5, hosts);
      statement.setString(6, (flexibility == null ? Flexibility.it_depends : flexibility).name());
      statement.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
      statement.executeUpdate();
    }
    store.changed(Schema.AVAILABILITY, userId, MutationEvent.Kind.update, userId);
  }

  public void forget(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.AVAILABILITY + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
  }
}
