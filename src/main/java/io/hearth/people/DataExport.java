package io.hearth.people;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.board.Board;
import io.hearth.calendar.Calendar;
import io.hearth.store.Schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Everything this community holds about one person, in one file.
 *
 * <b>Two of the rights the shipped privacy policy grants, and neither had a mechanism.</b> "Show you
 * what we hold about you" and "give you a copy in a portable form" -- the first is a subject access
 * request, the second is portability, both are answerable in a month by law in the UK and the EU,
 * and both were previously a job for somebody with a SQL client and a free afternoon. An operator
 * who cannot answer within the month is the one who is liable, not the software.
 *
 * JSON, because it is the portable form somebody can actually do something with, and because the
 * alternative -- a rendered page -- is a document about the data rather than the data.
 *
 * <b>It is deliberately generated on demand and never stored.</b> A file on disk holding a complete
 * dossier on a member is a worse thing to have than the database it came from: it outlives the
 * account, it is easy to email to the wrong person, and nothing invalidates it.
 *
 * What it does not carry: the session token (only a hash of it exists at all), the push keypair
 * (secret material, and useless to a person), or anything about anybody else. Somebody else's reply
 * in a thread is their words, not this person's data -- what is here is what they wrote, with the
 * address of the conversation so they can go and look.
 */
public final class DataExport {
  private static final ObjectMapper JSON = new ObjectMapper();

  private DataExport() {
  }

  /**
   * The version an administrator downloads, which is not quite the same file.
   *
   * One field is left out: the private address. Everything else here an administrator can already
   * see on a screen, so putting it in a file changes nothing -- and that address is the one thing
   * in this server nobody but its owner may read, which does not stop being true because the reader
   * pressed a different button. The file says the field was withheld rather than being silently
   * short, because a data export somebody is checking against the policy has to be honest about
   * what it is not showing.
   */
  public static byte[] of(Accounts accounts, UserRecord person, String community, String domain)
      throws SQLException {
    return of(accounts, person, community, domain, false);
  }

  public static byte[] of(Accounts accounts, UserRecord person, String community, String domain,
                          boolean forSelf)
      throws SQLException {
    ObjectNode root = JSON.createObjectNode();
    root.put("community", community);
    root.put("domain", domain);
    root.put("about", person.email());
    root.put("generated_at", java.time.Instant.now().toString());
    root.put("note", "Everything " + community + " holds about you. Nothing here is kept as a file"
        + " on the server; this was built when you asked for it.");

    ObjectNode account = root.putObject("account");
    account.put("email", person.email());
    account.put("created_at", stamp(person.createdAt()));
    account.put("verified_at", stamp(person.verifiedAt()));
    account.put("approved_at", stamp(person.approvedAt()));
    account.put("last_login_at", stamp(person.lastLoginAt()));
    account.put("disabled", person.disabled());
    account.put("has_password", person.hasPassword());

    ProfileRecord profile = accounts.people.profileOf(person.id());
    ObjectNode written = root.putObject("profile");
    written.put("display_name", profile.displayName());
    written.put("headline", profile.headline());
    written.put("about", profile.about());
    written.put("location", profile.location());
    written.put("links", profile.links());
    written.put("orientation_step", profile.orientationStep());

    // The private half, which is in here precisely because it is private. The policy promises to
    // show somebody everything held about them, and the one field nobody else can read is the one
    // field a person is most entitled to see a copy of.
    Home home = accounts.people.homeOf(person.id());
    ObjectNode where = root.putObject("your_address");
    if (forSelf) {
      where.put("note", "You are the only person who can read this. It is never shown on your"
          + " profile, in the members list, to an administrator, or to anything automated. The only"
          + " thing derived from it is your distance from a proposed venue, counted into a chart of"
          + " distances that carries no names.");
      where.put("address", home.address());
      where.put("latitude", home.latitude());
      where.put("longitude", home.longitude());
      where.put("how_exact", home.precision());
      where.put("how_it_went", home.placement().word());
      where.put("looked_up_at", stamp(home.triedAt()));
    } else {
      where.put("note", "Withheld. Only the person themselves can read their address, and this"
          + " copy was not made by them. They have " + (home.hasAddress() ? "given one." : "not"
          + " given one."));
      where.put("counted_in_travel_charts", home.hasPoint());
    }

    ArrayNode answers = root.putArray("answers");
    AnswerSheet sheet = accounts.people.answersOf(person.id());
    for (Question question : accounts.people.allQuestions()) {
      String answer = sheet.answerTo(question.id());
      if (answer == null || answer.isBlank()) {
        continue;
      }
      ObjectNode row = answers.addObject();
      row.put("question", question.prompt());
      row.put("answer", answer);
    }

    ArrayNode roles = root.putArray("roles");
    for (String role : accounts.roles.of(person.id())) {
      roles.add(role);
    }

    // Their log, in full, because it is theirs and nobody else can read it. This is the one part
    // of an export that cannot be reconstructed from anything on a screen, so leaving it out would
    // make the policy's "a copy you can take elsewhere" untrue for the thing people would most
    // want to take.
    ArrayNode logged = root.putArray("what_you_recorded");
    for (io.hearth.tasks.Records.Entry entry : accounts.tasks.recentFor(person.id(), 5000)) {
      ObjectNode row = logged.addObject();
      io.hearth.tasks.Records.Def def = entry.defId() == null ? null
          : accounts.tasks.def(entry.defId());
      row.put("what", def == null ? null : def.name());
      row.put("said", def == null ? null : entry.describe(def.measure()));
      row.put("weight", entry.weight());
      row.put("reps", entry.reps());
      row.put("seconds", entry.seconds());
      row.put("distance", entry.distance());
      row.put("difficulty", entry.difficulty());
      row.put("time_cost", entry.timeCost());
      row.put("impact", entry.impact());
      row.put("note", entry.note());
      row.put("at", entry.recordedAt().toInstant().toString());
    }

    ArrayNode sessions = root.putArray("sessions");
    for (SessionRecord session : accounts.sessions.activeFor(person.id())) {
      ObjectNode row = sessions.addObject();
      row.put("started", java.time.Instant.ofEpochMilli(session.createdAt()).toString());
      row.put("last_seen", java.time.Instant.ofEpochMilli(session.lastSeenAt()).toString());
      // the token is stored only as a hash and is not in here: a copy of somebody's own data
      // should not be a way to resume their session if it is forwarded to the wrong person
      row.put("is_a_connected_model", session.robot());
      row.put("connector", session.agent());
    }

    ArrayNode posts = root.putArray("posts");
    for (Board.Post post : accounts.board.all(2000)) {
      if (post.authorId() != person.id()) {
        continue;
      }
      ObjectNode row = posts.addObject();
      row.put("title", post.title());
      row.put("body", post.body());
      row.put("written", stamp(post.createdAt()));
      row.put("removed", post.removed());
    }

    ArrayNode comments = root.putArray("comments");
    commentsOf(accounts, person.id(), comments);

    ArrayNode events = root.putArray("events_you_answered");
    for (Calendar.Event event : accounts.calendar.all(2000)) {
      Calendar.Rsvp rsvp = accounts.calendar.rsvpFor(event.id(), person.id());
      if (rsvp == null) {
        continue;
      }
      ObjectNode row = events.addObject();
      row.put("event", event.title());
      row.put("day", String.valueOf(event.startsOn()));
      row.put("answer", rsvp.answer().name());
      row.put("bringing", rsvp.party() - 1);
      row.put("note", rsvp.note());
    }

    // and anything they said about an event before they had an account here, which is held
    // against their address rather than against them and is theirs all the same
    ArrayNode outside = root.putArray("events_you_answered_before_joining");
    for (Calendar.Event event : accounts.calendar.all(2000)) {
      Calendar.Outsider guest = accounts.calendar.outsiderFor(event.id(), person.email());
      if (guest == null) {
        continue;
      }
      ObjectNode row = outside.addObject();
      row.put("event", event.title());
      row.put("day", String.valueOf(event.startsOn()));
      row.put("answer", guest.answer().name());
      row.put("name_your_calendar_sent", guest.name());
      row.put("became_a_member_answer", guest.converted());
    }

    ArrayNode when = root.putArray("when_you_said_you_can_come");
    for (io.hearth.availability.Availability.Window window
        : accounts.availability.windowsFor(person.id())) {
      ObjectNode row = when.addObject();
      row.put("day", window.day().name());
      row.put("from", window.from());
      row.put("to", window.to());
      row.put("note", window.note());
    }
    // the addresses of their calendars, which are frequently secrets and are theirs. What those
    // calendars said is not exported, because it is not kept: this server reads busy times and
    // keeps two numbers per block, never a title.
    ArrayNode calendars = root.putArray("calendars_you_linked");
    for (io.hearth.availability.Availability.Link link
        : accounts.availability.linksFor(person.id())) {
      ObjectNode row = calendars.addObject();
      row.put("label", link.label());
      row.put("url", link.url());
      row.put("added", stamp(link.createdAt()));
    }

    ArrayNode uploads = root.putArray("files_you_uploaded");
    for (io.hearth.attach.Attachments.Attachment file
        : accounts.attachments.uploadedBy(person.id())) {
      ObjectNode row = uploads.addObject();
      row.put("filename", file.filename());
      row.put("url", file.url());
      row.put("what_it_is", file.description());
      row.put("size", file.size());
      row.put("uploaded", stamp(file.createdAt()));
    }

    ArrayNode invitations = root.putArray("invitations_you_sent");
    for (Invites.Invite invite : accounts.invites.all(2000)) {
      if (invite.createdBy() == null || invite.createdBy() != person.id()) {
        continue;
      }
      ObjectNode row = invitations.addObject();
      row.put("to", invite.email());
      row.put("sent", stamp(invite.sentAt()));
      row.put("stage", invite.stage());
    }

    ArrayNode notes = root.putArray("notifications");
    for (io.hearth.board.Inbox.Note note : accounts.inbox.forUser(person.id(), 1000)) {
      ObjectNode row = notes.addObject();
      row.put("text", note.text());
      row.put("when", stamp(note.createdAt()));
      row.put("unread", note.unread());
    }

    io.hearth.board.NotifyPrefs.Prefs prefs = accounts.notifyPrefs.forUser(person.id());
    ObjectNode settings = root.putObject("notification_settings");
    settings.put("replies_to_threads_you_watch", prefs.replyMode().name());
    settings.put("replies_to_you", prefs.responseMode().name());
    settings.put("by_email", prefs.email());
    settings.put("phone", prefs.phone());

    try {
      return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      // a tree we built ourselves out of strings and numbers; this cannot happen
      throw new IllegalStateException("could not write the export", ex);
    }
  }

  /**
   * Their comments, by a query rather than by walking every thread.
   *
   * The board's own reader is organised around a conversation -- give it a subject and it returns
   * the tree. Nothing needed "everything one person said" until somebody asked for their own copy,
   * and doing it by loading every thread would be one query per post on a page nobody looks at
   * twice.
   */
  private static void commentsOf(Accounts accounts, long userId, ArrayNode into)
      throws SQLException {
    try (Connection connection = accounts.store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT subject_kind, subject_id, body, created_at, removed_at FROM "
                 + Schema.COMMENTS + " WHERE author_id = ? ORDER BY created_at")) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          ObjectNode row = into.addObject();
          row.put("on", rows.getString("subject_kind") + " " + rows.getLong("subject_id"));
          row.put("body", rows.getString("body"));
          row.put("written", stamp(rows.getTimestamp("created_at")));
          row.put("removed", rows.getTimestamp("removed_at") != null);
        }
      }
    }
  }

  private static String stamp(Timestamp when) {
    return when == null ? null : java.time.Instant.ofEpochMilli(when.getTime()).toString();
  }
}
