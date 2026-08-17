package io.hearth.board;

/**
 * What a comment is a comment on.
 *
 * Three kinds and one table, because a comment is the same thing wherever it is written: somebody's
 * words, under something, in reading order, that other people can reply to. Three tables would be
 * three sets of threading, three notification paths and three moderation buttons, and the third one
 * would behave slightly differently from the first two.
 *
 * The id lives in the comments table's `post_id` column, whose name has stopped being literally
 * true. That is deliberate: it is the only column that could be backfilled correctly by an upgrader
 * that adds columns and never rewrites rows, and a name with a history is better than a rename that
 * only half happens. Nothing outside the DAO reads that column name.
 */
public record Subject(Kind kind, long id) {
  public enum Kind {
    /** a conversation on the board */
    post,
    /** something on the calendar */
    event,
    /** somewhere in the address book */
    place,
    /**
     * One comment, on its own.
     *
     * Only ever a *signal* subject, never a comment subject -- there are no comments on comments;
     * a reply is a comment with a parent. It is here because moderation needs this granularity and
     * voting wants it: the problem in a thread is almost never the whole thread, and the thing
     * worth agreeing with is usually one person's paragraph rather than the conversation.
     */
    comment;

    public static Kind of(String raw) {
      if (raw == null) {
        return post;
      }
      try {
        return valueOf(raw.trim().toLowerCase());
      } catch (IllegalArgumentException ex) {
        // an unreadable value is a row, not a crash; the board is what everything used to be
        return post;
      }
    }
  }

  public static Subject post(long id) {
    return new Subject(Kind.post, id);
  }

  public static Subject event(long id) {
    return new Subject(Kind.event, id);
  }

  public static Subject place(long id) {
    return new Subject(Kind.place, id);
  }

  /** what a live signal and a notification call this, e.g. "event:14" */
  public String key() {
    return kind.name() + ":" + id;
  }

  /**
   * Which permission takes somebody else's comment down here.
   *
   * Per section, because moderating is a job somebody was given for a place rather than a power
   * over all of somebody's words: whoever keeps the address book tidy is not automatically whoever
   * keeps the board civil.
   */
  public io.hearth.auth.Permission moderatedBy() {
    return switch (kind) {
      case post -> io.hearth.auth.Permission.board_moderate;
      case event -> io.hearth.auth.Permission.calendar_moderate;
      case place -> io.hearth.auth.Permission.places_moderate;
      // a comment lives inside something else, and taking it down is that section's business --
      // but a flag on one is triage, which is the board's job wherever the comment was written
      case comment -> io.hearth.auth.Permission.board_moderate;
    };
  }
}
