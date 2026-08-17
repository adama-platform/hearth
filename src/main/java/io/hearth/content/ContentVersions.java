package io.hearth.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * What every page used to say, and who changed it.
 *
 * Meant to replace keeping a website in git, which means two things it would be easy to get wrong.
 * First, a version is the *whole* page -- body, title, template, folder, field values, published
 * and human-only -- because "what did this look like in March" is not answerable if the answer
 * omits the part that changed. Second, the history is worth something only if you can trust it, so
 * reconstruction is checked against what was actually stored and a version that cannot be rebuilt
 * says so rather than showing a plausible wrong page.
 *
 * Storage is a snapshot every {@link #SNAPSHOT_EVERY} versions, patches in between, and a snapshot
 * any time a patch would not be smaller. Fixing a typo on a long page costs a few dozen bytes;
 * rewriting the page costs the page. The snapshots are also what bound the damage from a bad patch:
 * losing one loses the versions after it, not the history.
 */
public class ContentVersions {
  private static final Logger LOG = LoggerFactory.getLogger(ContentVersions.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String COLUMNS =
      "id, content_id, version, kind, payload, summary, created_at, created_by, created_by_email";

  /** how often to anchor the chain; ten replays is nothing, a thousand is a page that loads slowly */
  public static final int SNAPSHOT_EVERY = 10;

  private final Store store;

  public ContentVersions(Store store) {
    this.store = store;
  }

  /** one entry in the history, without its payload */
  public record Entry(long id, long contentId, int version, boolean snapshot, String summary,
                      Timestamp createdAt, Long createdBy, String email, int bytes) {
    public String who() {
      return email == null || email.isBlank() ? "somebody" : email;
    }
  }

  // ---- writing --------------------------------------------------------------------------------

  /**
   * Record what a page now says.
   *
   * Called from {@link ContentStore} on every save, so a version exists for anything that ever
   * happened -- including the save somebody is about to regret. Failing to record must never fail
   * the save itself: losing a history entry is a bad day, losing the edit is a worse one.
   */
  public void record(ContentRecord page, Long actor, String email) {
    try {
      String document = documentOf(page);
      int next = latestVersion(page.id()) + 1;
      String previous = next == 1 ? null : reconstruct(page.id(), next - 1);

      String kind = "snapshot";
      String payload = document;
      if (previous != null && TextPatch.canDiff(previous, document)) {
        String patch = TextPatch.diff(previous, document);
        // a patch bigger than the thing it describes is a worse snapshot
        boolean anchor = next % SNAPSHOT_EVERY == 0 || patch.length() >= document.length();
        if (!anchor) {
          kind = "patch";
          payload = patch;
        }
      }
      String summary = previous == null ? "created" : summarize(previous, document);
      insert(page.id(), next, kind, payload, summary, actor, email);
    } catch (Exception ex) {
      // deliberately swallowed: the edit is already saved, and a broken history is not worth
      // refusing somebody's work over. It is logged loudly because a silent gap is worse.
      LOG.error("content-version-failed for {}", page.id(), ex);
    }
  }

  private void insert(long contentId, int version, String kind, String payload, String summary,
                      Long actor, String email) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.CONTENT_VERSIONS
                 + " (content_id, version, kind, payload, summary, created_by, created_by_email)"
                 + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
      statement.setLong(1, contentId);
      statement.setInt(2, version);
      statement.setString(3, kind);
      statement.setString(4, payload);
      statement.setString(5, cap(summary, 512));
      if (actor == null) {
        statement.setNull(6, java.sql.Types.BIGINT);
      } else {
        statement.setLong(6, actor);
      }
      statement.setString(7, cap(email == null ? "" : email, 320));
      statement.executeUpdate();
    }
    store.changed(Schema.CONTENT_VERSIONS, contentId, MutationEvent.Kind.insert, actor);
  }

  /** forget a page's history; called when the page itself is deleted */
  public void forget(long contentId, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.CONTENT_VERSIONS + " WHERE content_id = ?")) {
      statement.setLong(1, contentId);
      statement.executeUpdate();
    }
    store.changed(Schema.CONTENT_VERSIONS, contentId, MutationEvent.Kind.delete, actor);
  }

  // ---- reading --------------------------------------------------------------------------------

  /** the history of a page, newest first */
  public List<Entry> history(long contentId) throws SQLException {
    ArrayList<Entry> entries = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.CONTENT_VERSIONS
                 + " WHERE content_id = ? ORDER BY version DESC")) {
      statement.setLong(1, contentId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          entries.add(read(rows));
        }
      }
    }
    return entries;
  }

  private boolean exists(long contentId, int version) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT 1 FROM " + Schema.CONTENT_VERSIONS + " WHERE content_id = ? AND version = ?")) {
      statement.setLong(1, contentId);
      statement.setInt(2, version);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  public int latestVersion(long contentId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COALESCE(MAX(version), 0) FROM " + Schema.CONTENT_VERSIONS
                 + " WHERE content_id = ?")) {
      statement.setLong(1, contentId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  public long count(long contentId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.CONTENT_VERSIONS + " WHERE content_id = ?")) {
      statement.setLong(1, contentId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  /**
   * Rebuild one version, as the canonical document.
   *
   * Walks back to the nearest snapshot and replays forward. Returns null when there is no such
   * version, and throws when the chain is broken -- which the caller shows as an error, because a
   * history that quietly returns a plausible wrong page is worse than one that admits a gap.
   */
  public String reconstruct(long contentId, int version) throws SQLException {
    // Asked for a version that does not exist, the walk below would happily return the newest one
    // at or below it -- an older page, presented as the one that was asked for. That is exactly the
    // plausible-wrong-answer this class promises not to give.
    if (!exists(contentId, version)) {
      return null;
    }
    ArrayList<String> chain = new ArrayList<>();
    String base = null;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT version, kind, payload FROM " + Schema.CONTENT_VERSIONS
                 + " WHERE content_id = ? AND version <= ? ORDER BY version DESC")) {
      statement.setLong(1, contentId);
      statement.setInt(2, version);
      try (ResultSet rows = statement.executeQuery()) {
        boolean found = false;
        while (rows.next()) {
          found = true;
          String kind = rows.getString("kind");
          String payload = rows.getString("payload");
          if ("snapshot".equals(kind)) {
            base = payload;
            break;
          }
          chain.add(payload);
        }
        if (!found) {
          return null;
        }
      }
    }
    if (base == null) {
      throw new TextPatch.PatchException("version " + version + " of content " + contentId
          + " has no snapshot to rebuild from");
    }
    // the patches came back newest first, so replay them the other way
    for (int k = chain.size() - 1; k >= 0; k--) {
      base = TextPatch.apply(base, chain.get(k));
    }
    return base;
  }

  /** one version as a page again, ready to render or compare */
  public ContentRecord versionOf(long contentId, int version) throws SQLException {
    String document = reconstruct(contentId, version);
    return document == null ? null : recordFrom(contentId, document);
  }

  // ---- the canonical document -------------------------------------------------------------------

  /**
   * A page as one stable text.
   *
   * Every field on its own line and always in the same order, so a diff of two of these is a diff
   * of what actually changed -- and so that a change to the template or the folder shows up in the
   * history at all, rather than only the body doing so.
   *
   * The body goes last and unquoted because it is the part that is many lines and the part a person
   * reads in a patch; putting it in a JSON string would make every diff one enormous line.
   */
  public static String documentOf(ContentRecord page) {
    ObjectNode head = JSON.createObjectNode();
    head.put("uri", page.uri());
    head.put("title", page.title());
    head.put("kind", page.kind().name());
    head.put("template", page.templateName() == null ? "" : page.templateName());
    head.put("folder", page.navFolder() == null ? "" : page.navFolder());
    head.put("fields", page.fields() == null ? "{}" : page.fields());
    head.put("published", page.published());
    head.put("human_only", page.humanOnly());
    StringBuilder document = new StringBuilder();
    head.fields().forEachRemaining(entry ->
        document.append(entry.getKey()).append(": ").append(entry.getValue().asText()).append('\n'));
    document.append("---\n");
    document.append(page.body() == null ? "" : page.body());
    return document.toString();
  }

  /** the inverse; anything unreadable comes back as far as it could be understood */
  public static ContentRecord recordFrom(long contentId, String document) {
    int split = document.indexOf("\n---\n");
    String head = split < 0 ? document : document.substring(0, split);
    String body = split < 0 ? "" : document.substring(split + 5);
    ObjectNode fields = JSON.createObjectNode();
    for (String line : head.split("\n")) {
      int colon = line.indexOf(": ");
      if (colon > 0) {
        fields.put(line.substring(0, colon), line.substring(colon + 2));
      } else if (line.endsWith(":")) {
        fields.put(line.substring(0, line.length() - 1), "");
      }
    }
    return new ContentRecord(contentId,
        fields.path("uri").asText(""),
        fields.path("title").asText(""),
        ContentRecord.Kind.of(fields.path("kind").asText("markdown")),
        blankToNull(fields.path("template").asText("")),
        fields.path("folder").asText(""),
        fields.path("fields").asText("{}"),
        body,
        Boolean.parseBoolean(fields.path("published").asText("true")),
        Boolean.parseBoolean(fields.path("human_only").asText("false")),
        null, null, null);
  }

  /**
   * What changed, in words.
   *
   * Computed once when the version is written because a history page shows dozens of these and
   * recomputing them would mean rebuilding every version to render a list.
   */
  static String summarize(String before, String after) {
    ContentRecord was = recordFrom(0, before);
    ContentRecord now = recordFrom(0, after);
    ArrayList<String> changed = new ArrayList<>();
    if (!was.uri().equals(now.uri())) {
      changed.add("moved to " + now.uri());
    }
    if (!was.title().equals(now.title())) {
      changed.add("title");
    }
    if (was.kind() != now.kind()) {
      changed.add("kind to " + now.kind().name());
    }
    if (!String.valueOf(was.templateName()).equals(String.valueOf(now.templateName()))) {
      changed.add("template");
    }
    if (!was.navFolder().equals(now.navFolder())) {
      changed.add("folder");
    }
    if (!was.fields().equals(now.fields())) {
      changed.add("template fields");
    }
    if (was.published() != now.published()) {
      changed.add(now.published() ? "published" : "unpublished");
    }
    if (was.humanOnly() != now.humanOnly()) {
      changed.add(now.humanOnly() ? "locked to humans" : "unlocked");
    }
    if (!was.body().equals(now.body())) {
      int wasLines = was.body().isEmpty() ? 0 : was.body().split("\n", -1).length;
      int nowLines = now.body().isEmpty() ? 0 : now.body().split("\n", -1).length;
      int delta = nowLines - wasLines;
      changed.add("body" + (delta == 0 ? "" : delta > 0 ? " +" + delta : " " + delta));
    }
    return changed.isEmpty() ? "no visible change" : String.join(", ", changed);
  }

  private static Entry read(ResultSet rows) throws SQLException {
    long createdBy = rows.getLong("created_by");
    boolean wasNull = rows.wasNull();
    String payload = rows.getString("payload");
    return new Entry(rows.getLong("id"), rows.getLong("content_id"), rows.getInt("version"),
        "snapshot".equals(rows.getString("kind")), rows.getString("summary"),
        rows.getTimestamp("created_at"), wasNull ? null : createdBy,
        rows.getString("created_by_email"), payload == null ? 0 : payload.length());
  }

  private static String blankToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private static String cap(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
