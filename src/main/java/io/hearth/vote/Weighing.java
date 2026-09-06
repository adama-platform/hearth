package io.hearth.vote;

import io.hearth.auth.Accounts;
import io.hearth.auth.UserRecord;

import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each proposed time actually costs the group, in enough detail to be argued with.
 *
 * <b>There is always an imperfect night, so the job is not to find the free one.</b> A scheduler
 * that answers "no evening works" for five adults is technically right and useless. What a group
 * needs is the evening that costs the least, and *what it costs* — so the person it costs something
 * can decide whether they mind.
 *
 * <b>Three states, not two, and the middle one is the whole point.</b> `free` is nothing in the way.
 * `firm` is a flight on the 9th. `maybe` is a standing Tuesday call — a real commitment, and exactly
 * the kind of thing somebody moves for a friend's fortieth. Collapsing `maybe` into `busy` is what
 * produces the useless answer: everybody has a standing something, so every evening is blocked.
 *
 * <b>A ballot beats a calendar, always.</b> A calendar is an inference and a vote is a person
 * speaking. If somebody says `blocked` on an evening their calendar shows free, they are right and
 * the calendar is stale; if they say `yes` on an evening with a firm clash, they have already
 * decided to move it. The calendar exists to make the first proposal good, not to overrule anybody.
 *
 * <b>The score is a means of comparison and nothing more.</b> Every number that goes into it comes
 * back out beside it, because a scheduler that hands a group a ranking they cannot interrogate gets
 * exactly one wrong answer before nobody trusts it again.
 */
public final class Weighing {
  /** somebody who has not voted and has no calendar; the honest answer is that we do not know */
  public static final String UNKNOWN = "unknown";

  private Weighing() {
  }

  /** what one person means for one option */
  public record Person(String who, String stance, String from, boolean host, String note) {
  }

  /**
   * One option, weighed.
   *
   * `score` orders; everything else explains. `cost` is what somebody would have to move, which is
   * the number a group actually argues about.
   */
  public record Weighed(String option, String when, int score, int canCome, int wouldMove,
                        int cannotCome, int unknown, boolean hostCanHost, boolean hostMightMove,
                        String verdict, List<Person> people) {
  }

  /**
   * Weigh every option in a vote.
   *
   * Calendars are only consulted for options that carry a real time. An option labelled "sometime
   * in October" is weighed on its ballots alone, which is correct -- there is nothing to check it
   * against, and inventing a time to check would be worse than admitting that.
   */
  public static List<Weighed> weigh(Votes.Record vote, Accounts accounts, ZoneId zone)
      throws SQLException {
    ArrayList<Weighed> out = new ArrayList<>();
    List<UserRecord> members = accounts.users.recent(500);
    String host = vote.hasHost() ? String.valueOf(vote.hostId()) : null;

    for (com.fasterxml.jackson.databind.JsonNode option : vote.optionsJson()) {
      String label = option.path("label").asText();
      long starts = option.path("starts_at").asLong(0);
      long ends = option.path("ends_at").asLong(0);
      if (starts > 0 && ends <= starts) {
        // an option with a start and no end is an evening; three hours is the least surprising
        // guess and is only used to ask a calendar a question
        ends = starts + 3L * 60 * 60 * 1000;
      }
      com.fasterxml.jackson.databind.JsonNode ballots = option.path("ballots");

      ArrayList<Person> people = new ArrayList<>();
      int canCome = 0;
      int wouldMove = 0;
      int cannotCome = 0;
      int unknown = 0;
      boolean hostCanHost = host == null;
      boolean hostMightMove = false;

      for (UserRecord person : members) {
        if (!person.isApproved() || person.disabled()) {
          continue;
        }
        String id = String.valueOf(person.id());
        String name = accounts.people.profileOf(person.id()).nameOr("member " + person.id());
        boolean isHost = id.equals(host);
        Availability.Record said = accounts.availability.of(person.id());

        Votes.Ballot ballot = ballots.has(id)
            ? Votes.Ballot.of(ballots.path(id).path("vote").asText()) : null;
        String stance;
        String from;
        String note = null;

        if (ballot != null) {
          // A ballot beats a calendar, always. See the class note: a calendar is an inference and a
          // vote is a person speaking.
          stance = switch (ballot) {
            case yes, fine -> "can_come";
            case no -> "would_rather_not";
            case blocked -> "cannot_come";
          };
          from = "they voted";
          if (ballots.path(id).hasNonNull("because")) {
            note = ballots.path(id).path("because").asText();
          }
        } else if (starts <= 0 || !said.hasCalendar()) {
          stance = UNKNOWN;
          from = said.hasCalendar() ? "no time to check against" : "no calendar shared";
        } else {
          Ics.Clash clash = Ics.clash(
              accounts.calendars.of(person.id(), said.icsUrl(), zone).busy(), starts, ends);
          stance = switch (clash) {
            case free -> "can_come";
            case maybe -> "would_have_to_move_something";
            case firm -> "cannot_come";
          };
          from = switch (clash) {
            case free -> "their calendar is clear";
            case maybe -> "a repeating commitment -- the kind of thing that can often be moved";
            case firm -> "a one-off in their calendar";
          };
          if (clash == Ics.Clash.free && said.flexibility() == Availability.Flexibility.tightly_booked) {
            // a clear-looking evening in a full week is worth less confidence than a clear evening
            // in an empty one, and saying so is cheaper than being wrong about it
            note = "their week is usually full, so this is worth confirming";
          }
        }

        switch (stance) {
          case "can_come" -> canCome++;
          case "would_have_to_move_something" -> wouldMove++;
          case "cannot_come" -> cannotCome++;
          case "would_rather_not" -> canCome++;
          default -> unknown++;
        }
        if (isHost) {
          hostCanHost = !stance.equals("cannot_come");
          hostMightMove = stance.equals("would_have_to_move_something");
        }
        people.add(new Person(name, stance, from, isHost, note));
      }

      // The weights, and why each is what it is.
      //
      // Somebody who can come is the thing being maximised. Somebody who would have to move
      // something counts for most of that, because usually they will -- and the fraction is what
      // stops an evening that needs three people to rearrange outranking one that needs none.
      // Somebody who cannot is a real cost and is subtracted rather than merely not added, so an
      // evening excluding two people loses to one excluding none even when more people voted on it.
      // Unknown counts for nothing in either direction: it is not evidence.
      int score = canCome * 10 + wouldMove * 6 - cannotCome * 12;
      if (!hostCanHost) {
        // not a weight: hosting is not a preference to be traded off
        score = Integer.MIN_VALUE / 2;
      } else if (hostMightMove) {
        score -= 4;
      }
      out.add(new Weighed(label, option.path("when").asText(""), score, canCome, wouldMove,
          cannotCome, unknown, hostCanHost, hostMightMove, verdict(canCome, wouldMove, cannotCome,
              unknown, hostCanHost, hostMightMove), people));
    }
    out.sort((left, right) -> Integer.compare(right.score(), left.score()));
    return out;
  }

  /**
   * One sentence a person can act on.
   *
   * Written for the reader rather than the ranking, because the number is only useful next to a
   * plain statement of what it means -- and because an agent will repeat this sentence to somebody
   * rather than reading out a score.
   */
  private static String verdict(int canCome, int wouldMove, int cannotCome, int unknown,
                                boolean hostCanHost, boolean hostMightMove) {
    if (!hostCanHost) {
      return "The host cannot do this one, so it is out however well it scores.";
    }
    StringBuilder out = new StringBuilder();
    out.append(canCome).append(" can come");
    if (wouldMove > 0) {
      out.append(", ").append(wouldMove).append(" would have to move something they could"
          + " probably move");
    }
    if (cannotCome > 0) {
      out.append(", ").append(cannotCome).append(" cannot");
    }
    if (unknown > 0) {
      out.append(", ").append(unknown).append(" have not said and have no calendar shared");
    }
    out.append('.');
    if (hostMightMove) {
      out.append(" The host has a repeating commitment then -- worth asking before proposing it.");
    }
    if (cannotCome == 0 && wouldMove == 0 && unknown == 0) {
      out.append(" Nothing is in the way of this one.");
    }
    return out.toString();
  }

  /** the whole weighing as a model or a tool answer reads it */
  public static Map<String, Object> asMap(Weighed weighed) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("option", weighed.option());
    if (!weighed.when().isBlank()) {
      row.put("when", weighed.when());
    }
    row.put("verdict", weighed.verdict());
    row.put("score", weighed.score());
    row.put("can_come", weighed.canCome());
    row.put("would_have_to_move_something", weighed.wouldMove());
    row.put("cannot_come", weighed.cannotCome());
    row.put("not_heard_from", weighed.unknown());
    row.put("host_can_host", weighed.hostCanHost());
    ArrayList<Map<String, Object>> people = new ArrayList<>();
    for (Person person : weighed.people()) {
      LinkedHashMap<String, Object> one = new LinkedHashMap<>();
      one.put("who", person.who());
      one.put("stance", person.stance());
      one.put("because", person.from());
      if (person.host()) {
        one.put("is_host", true);
      }
      if (person.note() != null) {
        one.put("note", person.note());
      }
      people.add(one);
    }
    row.put("people", people);
    return row;
  }
}
