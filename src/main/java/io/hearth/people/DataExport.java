package io.hearth.people;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
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



    ArrayNode roles = root.putArray("roles");
    for (String role : accounts.roles.of(person.id())) {
      roles.add(role);
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

    return root.toPrettyString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }


  private static String stamp(Timestamp when) {
    return when == null ? null : java.time.Instant.ofEpochMilli(when.getTime()).toString();
  }
}
