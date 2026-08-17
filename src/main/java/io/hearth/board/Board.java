package io.hearth.board;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The discussion board: posts, the threads under them, and who is watching.
 *
 * Three decisions shape everything here.
 *
 * **Posts expire by default.** A board that keeps everything forever becomes an archive nobody
 * reads and a liability somebody eventually has to think about. One where threads age out stays a
 * conversation. Expiry is set when the post is written and can be extended or removed for the few
 * posts worth keeping, but the default is that a thread has a life.
 *
 * **Threading is a sort key, not a recursion.** Every comment carries a dotted path of zero-padded
 * positions, so one ordered query returns the whole tree already in reading order and depth falls
 * out of the path. Fetching a level at a time would be a query per level, and the box would get
 * slower exactly as a conversation got interesting.
 *
 * **The watcher list is packed into the post.** A JSON array of user ids in one column rather than a
 * join table: a thread has tens of watchers, the list is read on every comment, and a row per
 * watcher turns one update into a query, a diff and a batch. It arrives with the post for free.
 */
public class Board {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String POST_COLUMNS =
      "id, author_id, author_email, title, body, created_at, updated_at, last_activity_at,"
          + " expires_at, comment_count, watchers, pinned, locked, removed_at, edited_at";
  private static final String COMMENT_COLUMNS =
      "id, subject_kind, subject_id, parent_id, path, depth, author_id, author_email, body, created_at,"
          + " removed_at, edited_at";

  /** how deep a reply can go before it stops being a thread and starts being a staircase */
  public static final int MAX_DEPTH = 6;
  /** a cap on the packed watcher list, so one popular thread cannot grow an unbounded column */
  public static final int MAX_WATCHERS = 500;

  private final Store store;

  public Board(Store store) {
    this.store = store;
  }

  // ---- records ---------------------------------------------------------------------------------

  public record Post(long id, long authorId, String authorEmail, String title, String body,
                     Timestamp createdAt, Timestamp updatedAt, Timestamp lastActivityAt,
                     Timestamp expiresAt, int commentCount, Set<Long> watchers, boolean pinned,
                     boolean locked, Timestamp removedAt, Timestamp editedAt) {
    public boolean removed() {
      return removedAt != null;
    }

    public boolean edited() {
      return editedAt != null;
    }

    public boolean expired(long now) {
      return expiresAt != null && expiresAt.getTime() <= now;
    }

    public boolean expires() {
      return expiresAt != null;
    }

    /** how long is left, in days, for the line that says so */
    public long daysLeft(long now) {
      return expiresAt == null ? -1 : Math.max(0, (expiresAt.getTime() - now) / 86_400_000L);
    }

    public boolean isWatchedBy(long userId) {
      return watchers.contains(userId);
    }
  }

  public record Comment(long id, Subject subject, Long parentId, String path, int depth,
                        long authorId, String authorEmail, String body, Timestamp createdAt,
                        Timestamp removedAt, Timestamp editedAt) {
    /** the board's name for the same thing, for the code that only ever deals with posts */
    public long postId() {
      return subject.id();
    }

    public boolean removed() {
      return removedAt != null;
    }

    public boolean edited() {
      return editedAt != null;
    }
  }

  // ---- posts -----------------------------------------------------------------------------------

  /**
   * Start a conversation.
   *
   * The author watches it from the moment it exists, because somebody who posts and is not told
   * about the replies has been given a worse version of shouting into a room.
   */
  public Post post(long authorId, String authorEmail, String title, String body, int expiryDays)
      throws SQLException {
    long id;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.POSTS + " (author_id, author_email, title, body, expires_at,"
                 + " watchers) VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, authorId);
      statement.setString(2, cap(authorEmail, 320));
      statement.setString(3, cap(title, 256));
      statement.setString(4, body);
      if (expiryDays <= 0) {
        statement.setNull(5, java.sql.Types.TIMESTAMP);
      } else {
        statement.setTimestamp(5, new Timestamp(
            System.currentTimeMillis() + expiryDays * 86_400_000L));
      }
      statement.setString(6, packWatchers(Set.of(authorId)));
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        id = keys.getLong(1);
      }
    }
    store.changed(Schema.POSTS, id, MutationEvent.Kind.insert, authorId);
    return postById(id);
  }

  public Post postById(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + POST_COLUMNS + " FROM " + Schema.POSTS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readPost(rows) : null;
      }
    }
  }

  /**
   * The feed: what is live, most recently active first, pinned at the top.
   *
   * Expired and removed posts are filtered in SQL rather than after, so a board with years of dead
   * threads costs the same to read as a fresh one.
   */
  public List<Post> feed(int limit) throws SQLException {
    ArrayList<Post> posts = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + POST_COLUMNS + " FROM " + Schema.POSTS
                 + " WHERE removed_at IS NULL AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)"
                 + " ORDER BY pinned DESC, last_activity_at DESC " + store.dialect().limit(limit))) {
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          posts.add(readPost(rows));
        }
      }
    }
    return posts;
  }

  /** everything, including what has aged out; the admin view */
  public List<Post> all(int limit) throws SQLException {
    ArrayList<Post> posts = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + POST_COLUMNS + " FROM " + Schema.POSTS
                 + " ORDER BY last_activity_at DESC " + store.dialect().limit(limit))) {
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          posts.add(readPost(rows));
        }
      }
    }
    return posts;
  }

  public void setExpiry(long postId, Integer days, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.POSTS + " SET expires_at = ? WHERE id = ?")) {
      if (days == null) {
        statement.setNull(1, java.sql.Types.TIMESTAMP);
      } else {
        statement.setTimestamp(1, new Timestamp(System.currentTimeMillis() + days * 86_400_000L));
      }
      statement.setLong(2, postId);
      statement.executeUpdate();
    }
    store.changed(Schema.POSTS, postId, MutationEvent.Kind.update, actor);
  }

  public void setFlags(long postId, boolean pinned, boolean locked, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.POSTS + " SET pinned = ?, locked = ? WHERE id = ?")) {
      statement.setBoolean(1, pinned);
      statement.setBoolean(2, locked);
      statement.setLong(3, postId);
      statement.executeUpdate();
    }
    store.changed(Schema.POSTS, postId, MutationEvent.Kind.update, actor);
  }

  /**
   * Take a post out of the feed.
   *
   * Soft, and the comments stay: a thread somebody was part of vanishing entirely is worse than one
   * marked as removed, and the replies are other people's words.
   */
  public void removePost(long postId, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.POSTS + " SET removed_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setLong(1, postId);
      statement.executeUpdate();
    }
    store.changed(Schema.POSTS, postId, MutationEvent.Kind.update, actor);
  }

  // ---- comments --------------------------------------------------------------------------------

  /**
   * Reply, to a post or to another comment.
   *
   * Everything that makes a thread work happens here: the path is computed from the parent, the
   * author starts watching, the post's activity moves, and the count is kept current -- in one
   * place, because a comment that landed but did not do the other four is a thread that looks
   * broken in four different ways.
   */
  public Comment comment(long postId, Long parentId, long authorId, String authorEmail, String body)
      throws SQLException {
    return comment(Subject.post(postId), parentId, authorId, authorEmail, body);
  }

  /**
   * Say something under anything.
   *
   * The board, an event and a place all reach this, and the only thing that differs is the subject.
   * Threading, the reading-order path, the depth cap and the removal rule are the same everywhere,
   * which is the whole reason there is one table rather than three.
   */
  public Comment comment(Subject subject, Long parentId, long authorId, String authorEmail,
                         String body) throws SQLException {
    long postId = subject.id();
    Comment parent = parentId == null ? null : commentById(parentId);
    int depth = parent == null ? 0 : Math.min(parent.depth() + 1, MAX_DEPTH);
    String parentPath = parent == null ? "" : parent.path();
    // deeper than the cap and the reply attaches to the deepest ancestor that is inside it, which
    // keeps a staircase from forming without losing the reply
    if (parent != null && parent.depth() + 1 > MAX_DEPTH) {
      parentPath = parent.path();
      depth = MAX_DEPTH;
    }
    String path = nextPath(subject, parentPath);

    long id;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.COMMENTS + " (subject_kind, subject_id, parent_id, path, depth,"
                 + " author_id, author_email, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
             Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, subject.kind().name());
      statement.setLong(2, postId);
      if (parentId == null) {
        statement.setNull(3, java.sql.Types.BIGINT);
      } else {
        statement.setLong(3, parentId);
      }
      statement.setString(4, path);
      statement.setInt(5, depth);
      statement.setLong(6, authorId);
      statement.setString(7, cap(authorEmail, 320));
      statement.setString(8, body);
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        id = keys.getLong(1);
      }
    }
    if (subject.kind() == Subject.Kind.post) {
      // the comment count and the last-activity stamp live on the post, which is what the feed
      // sorts by. An event and a place have no such row and need none: their pages count what is
      // there when they render.
      touch(postId, authorId);
    }
    store.changed(Schema.COMMENTS, id, MutationEvent.Kind.insert, authorId);
    return commentById(id);
  }

  public Comment commentById(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COMMENT_COLUMNS + " FROM " + Schema.COMMENTS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readComment(rows) : null;
      }
    }
  }

  /** the whole thread, in reading order, in one query */
  public List<Comment> thread(long postId) throws SQLException {
    return thread(Subject.post(postId));
  }

  public List<Comment> thread(Subject subject) throws SQLException {
    ArrayList<Comment> comments = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COMMENT_COLUMNS + " FROM " + Schema.COMMENTS
                 + " WHERE subject_kind = ? AND subject_id = ? ORDER BY path")) {
      statement.setString(1, subject.kind().name());
      statement.setLong(2, subject.id());
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          comments.add(readComment(rows));
        }
      }
    }
    return comments;
  }

  /**
   * Everybody who has said something here, which is who hears about the next one.
   *
   * The board keeps an explicit watcher list on the post, because somebody can join a thread by
   * reading it and choose to keep watching after they stop replying. An event and a place have
   * nowhere to keep one and need nowhere: having commented is the whole of having joined, and
   * deriving it is one indexed query rather than a column that can disagree with the comments.
   */
  public java.util.Set<Long> commenters(Subject subject) throws SQLException {
    java.util.LinkedHashSet<Long> found = new java.util.LinkedHashSet<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT DISTINCT author_id FROM " + Schema.COMMENTS
                 + " WHERE subject_kind = ? AND subject_id = ? AND removed_at IS NULL")) {
      statement.setString(1, subject.kind().name());
      statement.setLong(2, subject.id());
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(rows.getLong(1));
        }
      }
    }
    return found;
  }

  /** how many are on something, for a listing that wants to say "3 comments" */
  public int commentCount(Subject subject) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.COMMENTS
                 + " WHERE subject_kind = ? AND subject_id = ? AND removed_at IS NULL")) {
      statement.setString(1, subject.kind().name());
      statement.setLong(2, subject.id());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getInt(1) : 0;
      }
    }
  }

  /** a comment goes but its row stays, so the replies underneath do not become orphans */
  public void removeComment(long id, Long actor) throws SQLException {
    Comment comment = commentById(id);
    if (comment == null) {
      return;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.COMMENTS + " SET removed_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.COMMENTS, id, MutationEvent.Kind.update, actor);
    store.changed(Schema.POSTS, comment.postId(), MutationEvent.Kind.update, actor);
  }

  /**
   * The next sibling path under a parent.
   *
   * Zero padded to four digits so string ordering is numeric ordering. Four is a thousand siblings
   * at one level; past that the padding would break and the sort would go wrong, so the count is
   * clamped rather than allowed to overflow into a wrong order.
   */
  private String nextPath(Subject subject, String parentPath) throws SQLException {
    String prefix = parentPath.isEmpty() ? "" : parentPath + ".";
    int siblings;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.COMMENTS
                 + " WHERE subject_kind = ? AND subject_id = ? AND parent_id IS "
                 + (parentPath.isEmpty() ? "NULL" : "NOT NULL")
                 + (parentPath.isEmpty() ? "" : " AND path LIKE ?"))) {
      statement.setString(1, subject.kind().name());
      statement.setLong(2, subject.id());
      if (!parentPath.isEmpty()) {
        statement.setString(3, prefix + "____");
      }
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        siblings = rows.getInt(1);
      }
    }
    return prefix + String.format("%04d", Math.min(siblings + 1, 9999));
  }

  // ---- watchers --------------------------------------------------------------------------------

  /**
   * Everybody watching a thread, after somebody joins it.
   *
   * Joining a conversation is what makes you a watcher -- there is no button, because a board where
   * you have to remember to subscribe is one where people miss the reply to their own comment.
   * Returns the watchers *before* this person joined, which is exactly the set that should be told
   * about the new comment.
   */
  public Set<Long> watchAndReturnOthers(long postId, long userId) throws SQLException {
    Post post = postById(postId);
    if (post == null) {
      return Set.of();
    }
    LinkedHashSet<Long> before = new LinkedHashSet<>(post.watchers());
    if (before.contains(userId) || before.size() >= MAX_WATCHERS) {
      LinkedHashSet<Long> others = new LinkedHashSet<>(before);
      others.remove(userId);
      return others;
    }
    LinkedHashSet<Long> now = new LinkedHashSet<>(before);
    now.add(userId);
    writeWatchers(postId, now, userId);
    LinkedHashSet<Long> others = new LinkedHashSet<>(before);
    others.remove(userId);
    return others;
  }

  /**
   * Fix your own words.
   *
   * Stamped as edited rather than changed quietly. A post that shifts under the people who already
   * read it and replied to it is a small lie to all of them, and the stamp costs one column.
   *
   * Only the author edits; an admin's powers here are pin, lock and remove. Rewriting what somebody
   * said while leaving their name on it is the one moderation action they cannot undo, so it is not
   * on offer -- see AdminRoutes.
   */
  public void editPost(long postId, String title, String body, long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.POSTS + " SET title = ?, body = ?, edited_at = CURRENT_TIMESTAMP,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND author_id = ?")) {
      statement.setString(1, cap(title, 256));
      statement.setString(2, body);
      statement.setLong(3, postId);
      statement.setLong(4, actor);
      if (statement.executeUpdate() == 0) {
        return;
      }
    }
    store.changed(Schema.POSTS, postId, MutationEvent.Kind.update, actor);
  }

  /** the same, for a comment */
  public void editComment(long commentId, String body, long actor) throws SQLException {
    Comment comment = commentById(commentId);
    if (comment == null || comment.authorId() != actor || comment.removed()) {
      return;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.COMMENTS + " SET body = ?, edited_at = CURRENT_TIMESTAMP"
                 + " WHERE id = ? AND author_id = ?")) {
      statement.setString(1, body);
      statement.setLong(2, commentId);
      statement.setLong(3, actor);
      statement.executeUpdate();
    }
    store.changed(Schema.COMMENTS, commentId, MutationEvent.Kind.update, actor);
    // the thread's rendered cache hangs off the post, so the post is what has to be seen to change
    store.changed(Schema.POSTS, comment.postId(), MutationEvent.Kind.update, actor);
  }

  /** stop being told about a thread; the one thing that is a button */
  public void unwatch(long postId, long userId) throws SQLException {
    Post post = postById(postId);
    if (post == null || !post.watchers().contains(userId)) {
      return;
    }
    LinkedHashSet<Long> now = new LinkedHashSet<>(post.watchers());
    now.remove(userId);
    writeWatchers(postId, now, userId);
  }

  private void writeWatchers(long postId, Set<Long> watchers, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.POSTS + " SET watchers = ? WHERE id = ?")) {
      statement.setString(1, packWatchers(watchers));
      statement.setLong(2, postId);
      statement.executeUpdate();
    }
    store.changed(Schema.POSTS, postId, MutationEvent.Kind.update, actor);
  }

  static String packWatchers(Set<Long> watchers) {
    ArrayNode array = JSON.createArrayNode();
    for (Long id : watchers) {
      array.add(id);
    }
    return array.toString();
  }

  static Set<Long> unpackWatchers(String blob) {
    LinkedHashSet<Long> watchers = new LinkedHashSet<>();
    if (blob == null || blob.isBlank()) {
      return watchers;
    }
    try {
      JsonNode node = JSON.readTree(blob);
      if (node.isArray()) {
        for (JsonNode item : node) {
          if (item.canConvertToLong()) {
            watchers.add(item.asLong());
          }
        }
      }
    } catch (Exception ex) {
      // a watcher list that will not parse is an empty one: nobody is told, which is a small loss
      // next to refusing to render the thread
    }
    return watchers;
  }

  // ---- housekeeping ----------------------------------------------------------------------------

  /** move the post's activity and count after a comment lands */
  private void touch(long postId, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.POSTS + " SET last_activity_at = CURRENT_TIMESTAMP,"
                 + " comment_count = (SELECT COUNT(*) FROM " + Schema.COMMENTS
                 + " WHERE subject_id = ? AND removed_at IS NULL) WHERE id = ?")) {
      statement.setLong(1, postId);
      statement.setLong(2, postId);
      statement.executeUpdate();
    }
    store.changed(Schema.POSTS, postId, MutationEvent.Kind.update, actor);
  }

  /** how many posts are live right now, for the boot report and the admin */
  public long liveCount() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.POSTS + " WHERE removed_at IS NULL"
                 + " AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)");
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  public long count() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.POSTS);
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  // ---- mapping ---------------------------------------------------------------------------------

  private static Post readPost(ResultSet rows) throws SQLException {
    return new Post(rows.getLong("id"), rows.getLong("author_id"), rows.getString("author_email"),
        rows.getString("title"), rows.getString("body"), rows.getTimestamp("created_at"),
        rows.getTimestamp("updated_at"), rows.getTimestamp("last_activity_at"),
        rows.getTimestamp("expires_at"), rows.getInt("comment_count"),
        unpackWatchers(rows.getString("watchers")), rows.getBoolean("pinned"),
        rows.getBoolean("locked"), rows.getTimestamp("removed_at"),
        rows.getTimestamp("edited_at"));
  }

  private static Comment readComment(ResultSet rows) throws SQLException {
    Long parentId = rows.getLong("parent_id");
    if (rows.wasNull()) {
      parentId = null;
    }
    return new Comment(rows.getLong("id"),
        new Subject(Subject.Kind.of(rows.getString("subject_kind")), rows.getLong("subject_id")),
        parentId,
        rows.getString("path"), rows.getInt("depth"), rows.getLong("author_id"),
        rows.getString("author_email"), rows.getString("body"), rows.getTimestamp("created_at"),
        rows.getTimestamp("removed_at"), rows.getTimestamp("edited_at"));
  }

  private static String cap(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}
