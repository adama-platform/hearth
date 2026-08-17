package io.hearth.board;

import io.hearth.auth.Accounts;
import io.hearth.auth.UserRecord;
import io.hearth.content.Markdown;
import io.hearth.web.Forms;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A comment thread, wherever it is.
 *
 * The board, an event and a place all want the same thing under them: what people said, in reading
 * order, with a box to add to it, the author able to edit their own, and somebody with the
 * permission able to take one down. Writing that three times would have produced three subtly
 * different threads, and the third would have been the one that forgot to filter markdown.
 *
 * So this builds the model and handles the POST, and the three pages differ only in what they hand
 * it: a {@link Subject}, a path to post to, and which permission moderates here.
 *
 * The board's own thread page is deliberately *not* routed through this. It has the parts nothing
 * else has -- nested reply forms, watchers, locking, expiry -- and folding those in would mean a
 * helper with four flags for the two callers that do not use them.
 */
public final class CommentBox {
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault());
  /** the most a page shows before it starts folding the old ones away */
  private static final int MAX = 2000;

  private CommentBox() {
  }

  /**
   * Everything a page needs to draw a thread under something.
   *
   * Keys are prefixed with nothing on purpose: a template says `{{#commentGroups}}` and
   * `{{commentCount}}`, and the three pages that use it read identically.
   */
  public static void render(Map<String, Object> model, Accounts accounts, Subject subject,
                            UserRecord me, String action, boolean canModerate)
      throws SQLException {
    render(model, accounts, subject, me, action, canModerate, null);
  }

  /**
   * @param event when the subject is an event, so each comment can say whether it was written
   *     before it, on the day, or afterwards. Three different conversations, and reading them as
   *     one loses the useful part -- see {@link CommentPhase}.
   */
  public static void render(Map<String, Object> model, Accounts accounts, Subject subject,
                            UserRecord me, String action, boolean canModerate,
                            io.hearth.calendar.Calendar.Event event)
      throws SQLException {
    List<Board.Comment> all = accounts.board.thread(subject);
    long now = System.currentTimeMillis();
    // people are named, never addressed: the same rule the members directory follows, applied to
    // the place where a community actually talks
    io.hearth.people.Names names = io.hearth.people.Names.of(accounts);

    ArrayList<Map<String, Object>> groups = new ArrayList<>();
    int shown = 0;
    for (CommentGroups.Group group : CommentGroups.of(all, now)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("label", group.label());
      row.put("anyLabel", !group.label().isBlank());
      row.put("collapsed", group.collapsed());
      row.put("open", !group.collapsed());
      row.put("count", group.count());
      ArrayList<Map<String, Object>> comments = new ArrayList<>();
      for (Board.Comment comment : group.comments()) {
        Map<String, Object> each = one(comment, me, canModerate, names);
        if (event != null) {
          CommentPhase phase = CommentPhase.of(event, comment.createdAt());
          each.put("phase", phase.label());
          each.put("beforeIt", phase == CommentPhase.before);
          each.put("onTheDay", phase == CommentPhase.during);
          each.put("afterwards", phase == CommentPhase.after);
        }
        comments.add(each);
        shown++;
        if (shown >= MAX) {
          break;
        }
      }
      row.put("comments", comments);
      groups.add(row);
    }

    model.put("commentGroups", groups);
    model.put("anyComments", !all.isEmpty());
    model.put("commentCount", all.size());
    model.put("commentAction", action);
    model.put("subject", subject.key());
  }

  private static Map<String, Object> one(Board.Comment comment, UserRecord me,
                                         boolean canModerate, io.hearth.people.Names names) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("id", comment.id());
    row.put("author", names.of(comment.authorId()));
    row.put("when", comment.createdAt() == null ? ""
        : WHEN.format(Instant.ofEpochMilli(comment.createdAt().getTime())));
    row.put("removed", comment.removed());
    // somebody else's markdown, so the filtered renderer -- the same rule the board follows, and
    // the reason there is one of these rather than three
    row.put("bodyHtml", comment.removed()
        ? "<p class=\"gone\">removed</p>" : Markdown.toSafeHtml(comment.body()));
    row.put("body", comment.body());
    row.put("edited", comment.edited());
    row.put("mine", me != null && comment.authorId() == me.id());
    // an author may always take back their own; a moderator may take down anybody's here and
    // nowhere else, because the permission is per section
    row.put("canRemove", !comment.removed()
        && (canModerate || (me != null && comment.authorId() == me.id())));
    return row;
  }

  /** what happened, so the caller can say it or say nothing */
  public enum Outcome {
    posted,
    edited,
    removed,
    refused
  }

  /**
   * One POST against a thread.
   *
   * Every refusal is silent and lands back on the page, because there is nothing here worth an
   * error screen: a comment that was too long, a reply to something that has gone, a form that
   * expired. The one thing this must never do is act on somebody else's words, and that check is
   * the `WHERE` clause in the DAO rather than a condition here.
   */
  public static Outcome act(Accounts accounts, Subject subject, UserRecord me, Forms form,
                            boolean canModerate) throws SQLException {
    String action = String.valueOf(form.get("action"));
    switch (action) {
      case "comment" -> {
        String body = form.text("body");
        if (body == null || body.isBlank() || form.tooLong() != null) {
          return Outcome.refused;
        }
        accounts.board.comment(subject, null, me.id(), me.email(), body);
        return Outcome.posted;
      }
      case "edit_comment" -> {
        long id = idOf(form.get("comment"));
        String body = form.text("body");
        if (id <= 0 || body == null || body.isBlank() || form.tooLong() != null) {
          return Outcome.refused;
        }
        Board.Comment comment = accounts.board.commentById(id);
        if (comment == null || !comment.subject().equals(subject)) {
          // a comment id from another page is not this page's to edit
          return Outcome.refused;
        }
        // the author check lives in the DAO's WHERE clause: rewriting what somebody said while
        // leaving their name on it is the one moderation power nobody has anywhere here
        accounts.board.editComment(id, body, me.id());
        return Outcome.edited;
      }
      case "remove_comment" -> {
        long id = idOf(form.get("comment"));
        Board.Comment comment = id <= 0 ? null : accounts.board.commentById(id);
        if (comment == null || !comment.subject().equals(subject)) {
          return Outcome.refused;
        }
        if (!canModerate && comment.authorId() != me.id()) {
          return Outcome.refused;
        }
        accounts.board.removeComment(id, me.id());
        return Outcome.removed;
      }
      default -> {
        return Outcome.refused;
      }
    }
  }

  private static long idOf(String raw) {
    try {
      return Long.parseLong(String.valueOf(raw).trim());
    } catch (NumberFormatException ex) {
      return -1;
    }
  }
}
