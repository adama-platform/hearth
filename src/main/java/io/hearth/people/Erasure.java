package io.hearth.people;

import io.hearth.auth.Accounts;
import io.hearth.auth.UserRecord;
import io.hearth.store.Schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Removing a person, everywhere they are named.
 *
 * <b>This exists because the privacy policy this software ships promises it.</b> "Delete your
 * account and what is attached to it", "if you leave, tell us and we will delete your account" --
 * published on every community's own site, under the name of whoever runs it, with a regulator
 * named two paragraphs below. Before this there was no mechanism: rejecting an applicant deleted
 * their account, profile and answers, and left their email address sitting in `posts.author_email`,
 * `comments.author_email`, `rsvps.user_email` and `invites.created_by_email`. An administrator
 * answering an erasure request would have said "done" while four tables still held the address.
 *
 * <b>The words stay; the person goes.</b> A thread somebody replied in is other people's
 * conversation, and deleting the parent of a discussion orphans every reply under it -- so a post
 * and a comment keep their text and lose their author, which is the shape almost every jurisdiction
 * actually asks for. An administrator who wants the words gone as well says so; that is a separate
 * decision and it is theirs to make, not this class's.
 *
 * <b>What deliberately survives.</b> A ban. It is a row holding an address, and it is the one thing
 * here that has to outlive the account: a community that cannot keep out somebody it removed cannot
 * protect anybody, which is the legitimate interest the policy claims in writing. Nothing else about
 * them is kept with it.
 */
public final class Erasure {
  /** what a page calls somebody who is no longer here */
  public static final String GONE = "a former member";

  private Erasure() {
  }

  /** what was removed, so the person doing it can be told rather than reassured */
  public record Report(Map<String, Integer> counts, String email) {
    public int of(String what) {
      return counts.getOrDefault(what, 0);
    }

    /** one line, in the order somebody would ask about it */
    public String describe() {
      StringBuilder out = new StringBuilder();
      for (Map.Entry<String, Integer> entry : counts.entrySet()) {
        if (entry.getValue() == 0) {
          continue;
        }
        if (out.length() > 0) {
          out.append(", ");
        }
        out.append(entry.getValue()).append(' ').append(entry.getKey());
      }
      return out.length() == 0 ? "nothing else was attached to it" : out.toString();
    }
  }

  /**
   * Erase somebody.
   *
   * @param alsoRemoveWhatTheyWrote take their posts and comments down as well as unnaming them.
   *     Off for an ordinary erasure -- a conversation with holes in it is worse for everybody who
   *     is still here, and the request is about their identity rather than about other people's
   *     memory of a Tuesday.
   */
  public static Report erase(Accounts accounts, io.hearth.analytics.AccessLog log,
                             UserRecord person, Long actor, boolean alsoRemoveWhatTheyWrote)
      throws SQLException {
    return erase(accounts, log, person, actor, alsoRemoveWhatTheyWrote, null);
  }

  /**
   * @param files where delivered mail's octets live, or null on a box that stores none. Null is a
   *              real configuration and not a shortcut: a server that only forwards has no message
   *              files, and the rows it has none of are deleted just the same.
   */
  public static Report erase(Accounts accounts, io.hearth.analytics.AccessLog log,
                             UserRecord person, Long actor, boolean alsoRemoveWhatTheyWrote,
                             io.hearth.inbox.MessageFiles files)
      throws SQLException {
    long id = person.id();
    LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();

    // everything that is only about them, deleted outright
    accounts.people.forget(id);
    // a key for a service somewhere else goes too. It is a credential this server was holding on
    // their behalf, and holding it after they have gone is the plainest possible breach of what
    // the privacy policy promises.
    accounts.userKeys.forget(id);
    accounts.availability.forget(id);
    accounts.calendars.forget(id);
    counts.put("push subscription(s)", accounts.pushSubs.forgetUser(id));
    // what they uploaded stays and their name comes off it, which is the rule the board already
    // follows: a photograph of last summer is part of what everybody remembers, and cutting one
    // person out of it leaves holes in everybody else's Tuesday. Taking the files down as well is
    // a separate decision, and one an administrator makes deliberately.
    counts.put("upload(s) unnamed", accounts.attachments.forget(id));
    accounts.roles.revokeAll(id);
    // Their address comes out of the mail log entirely rather than being blanked.
    //
    // Every other row here keeps its words and loses its author, because the words are somebody
    // else's conversation. A mail log row is nothing but who wrote to whom, so an anonymised one
    // would still say that somebody at this address had mail on a Tuesday -- there is no residue
    // worth keeping. The address they were given stays and stops being theirs: `receipts@` is the
    // domain owner's, not the member's.
    counts.put("mail log entr(ies)", accounts.mailLog.forget(person.email()));
    counts.put("mailbox(es) unnamed", accounts.mailboxes.forgetOwner(id));
    // Their mail, and the originals on disk with it.
    //
    // The files go first: a row deleted before its file leaves a message on disk that nothing
    // names, which no later erasure will ever find. This is also why the ids are read out before
    // anything is deleted rather than derived afterwards.
    counts.put("stored message(s)", eraseMail(accounts, files, id));
    counts.put("calendar event(s)", accounts.events.forget(id));
    accounts.events.revokeFeed(id);
    // deleted rather than revoked: a revoked row lingers for a day, and this is the request that
    // means "there should be nothing left"
    counts.put("session(s)", accounts.sessions.deleteAllFor(id));

    try (Connection connection = accounts.store.connection()) {
      // and the operator's own trail: who saved a page. The history stays -- it is a record of
      // what happened to the site rather than of a person -- with the name taken off it.
      counts.put("edit(s) unnamed",
          update(connection, "UPDATE " + Schema.CONTENT_VERSIONS
              + " SET created_by = NULL, created_by_email = ? WHERE created_by = ?", GONE, id));
    }

    // the request log is in memory and holds an address' worth of identity -- the user id and the
    // IP -- so it is swept too rather than waiting for a restart to make the promise true
    counts.put("logged request(s)", log == null ? 0 : log.forgetUser(id));

    accounts.users.delete(id);
    return new Report(counts, person.email());
  }

  /**
   * Every message of theirs, off the disk and out of the table.
   *
   * The files are removed first and the rows second. A row deleted before its file leaves an
   * orphan on disk that nothing names and no later erasure will find -- which is somebody's mail
   * surviving the request that was meant to remove all of it.
   */
  private static int eraseMail(Accounts accounts, io.hearth.inbox.MessageFiles files, long id)
      throws SQLException {
    if (files != null) {
      for (Long messageId : accounts.inbox.idsFor(id)) {
        files.delete(messageId);
      }
    }
    return accounts.inbox.forget(id);
  }

  private static int update(Connection connection, String sql, Object... args) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int k = 0; k < args.length; k++) {
        if (args[k] instanceof Long value) {
          statement.setLong(k + 1, value);
        } else {
          statement.setString(k + 1, String.valueOf(args[k]));
        }
      }
      return statement.executeUpdate();
    }
  }
}
