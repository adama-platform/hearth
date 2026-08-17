package io.hearth.people;

import io.hearth.events.MutationEvent;
import io.hearth.places.Placement;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Profiles, questions and answers.
 *
 * Every write announces itself on the event bus naming the table and the row, which is what the
 * survey indexer listens to: a question changing means everybody's remaining count is wrong, and an
 * answer changing means one person's is. Neither this class nor the indexer knows about the other.
 */
public class PeopleStore {
  /** the private half, which PROFILE_COLUMNS deliberately does not name */
  private static final String HOME_COLUMNS =
      "user_id, address, latitude, longitude, geo_precision, geo_state, geo_service, geo_tries,"
          + " geo_tried_at, geo_next_at, geo_note";
  private static final String PROFILE_COLUMNS =
      "id, user_id, display_name, headline, about, location, links, orientation_step,"
          + " created_at, updated_at";
  private static final String QUESTION_COLUMNS =
      "id, definition, position, published, updated_at, deleted_at";
  private static final String ANSWER_COLUMNS = "id, user_id, blob, answered, remaining";

  private final Store store;

  public PeopleStore(Store store) {
    this.store = store;
  }

  // ---- profiles --------------------------------------------------------------------------------

  /** somebody's profile, or a blank one; never null, because everybody has a profile conceptually */
  public ProfileRecord profileOf(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + PROFILE_COLUMNS + " FROM " + Schema.PROFILES + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readProfile(rows) : ProfileRecord.blank(userId);
      }
    }
  }

  /**
   * How far through the welcome somebody got, recorded when they got there.
   *
   * Only ever forwards. A step is written when somebody *finishes* one, so re-opening the welcome,
   * or typing a step into the address bar, cannot make the record say they did something they did
   * not -- and the number is only useful because it is true. There is no event: nothing caches it,
   * and the dashboard reads it directly.
   */
  public void markOrientation(long userId, int step) throws SQLException {
    ProfileRecord existing = profileOf(userId);
    if (step <= existing.orientationStep()) {
      return;
    }
    if (existing.id() == 0) {
      // somebody who reached the welcome without ever saving anything; the row has to exist for
      // the number to live on it
      saveProfile(userId, "", "", "", "", "");
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PROFILES + " SET orientation_step = ? WHERE user_id = ?")) {
      statement.setInt(1, step);
      statement.setLong(2, userId);
      statement.executeUpdate();
    }
  }

  public ProfileRecord saveProfile(long userId, String displayName, String headline, String about,
                                   String location, String links) throws SQLException {
    ProfileRecord existing = profileOf(userId);
    boolean isNew = existing.id() == 0;
    String sql = isNew
        ? "INSERT INTO " + Schema.PROFILES + " (display_name, headline, about, location, links, user_id)"
            + " VALUES (?, ?, ?, ?, ?, ?)"
        : "UPDATE " + Schema.PROFILES + " SET display_name = ?, headline = ?, about = ?, location = ?,"
            + " links = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?";
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, cap(displayName, 128));
      statement.setString(2, cap(headline, 256));
      statement.setString(3, cap(about, 8192));
      statement.setString(4, cap(location, 128));
      statement.setString(5, cap(links, 1024));
      statement.setLong(6, userId);
      statement.executeUpdate();
    }
    ProfileRecord saved = profileOf(userId);
    store.changed(Schema.PROFILES, saved.id(),
        isNew ? MutationEvent.Kind.insert : MutationEvent.Kind.update, userId);
    return saved;
  }

  /**
   * Drop everything one person told us about themselves.
   *
   * Called when an account is rejected or banned. A profile and a sheet of answers about somebody
   * who is no longer here is data with no reader, and the only thing it can still do is leak.
   */
  /**
   * Everybody's profile, in one query.
   *
   * The members directory is the one screen that needs all of them at once, and a profile per row
   * would be five hundred queries to draw one page. Keyed by user id so the caller can join it
   * against whoever it decided is a member -- this store knows what people wrote, not who is
   * allowed to be seen.
   */
  public java.util.Map<Long, ProfileRecord> allProfiles() throws SQLException {
    java.util.LinkedHashMap<Long, ProfileRecord> found = new java.util.LinkedHashMap<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + PROFILE_COLUMNS + " FROM " + Schema.PROFILES);
         ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        ProfileRecord profile = readProfile(rows);
        found.put(profile.userId(), profile);
      }
    }
    return found;
  }

  // ---- where somebody lives --------------------------------------------------------------------

  /**
   * The private half of a profile, read on its own.
   *
   * Its own method reading its own columns, because the alternative -- a field on ProfileRecord and
   * a rule about not rendering it -- is a rule that gets broken by the next person to write a
   * listing. `PROFILE_COLUMNS` does not name any of these, so a profile physically cannot carry
   * them.
   */
  public Home homeOf(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + HOME_COLUMNS + " FROM " + Schema.PROFILES + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readHome(rows) : Home.blank(userId);
      }
    }
  }

  /**
   * Somebody typing their address in.
   *
   * The point is cleared rather than kept: an old coordinate beside a new address is a distance
   * calculated from where somebody used to live, which is worse than no distance at all because
   * nothing about the screen says it is stale. The whole placement record goes with it, including
   * a previous "no such address" -- this is a different address, and it deserves its own answer.
   */
  public void saveAddress(long userId, String address) throws SQLException {
    ProfileRecord existing = profileOf(userId);
    if (existing.id() == 0) {
      saveProfile(userId, "", "", "", "", "");
    }
    String clean = cap(address, 256);
    Home before = homeOf(userId);
    if (before.address().equals(clean)) {
      return;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PROFILES + " SET address = ?, latitude = NULL, longitude = NULL,"
                 + " geo_precision = '', geo_state = '', geo_service = '', geo_tries = 0,"
                 + " geo_next_at = NULL, geo_tried_at = NULL, geo_note = '',"
                 + " updated_at = CURRENT_TIMESTAMP WHERE user_id = ?")) {
      statement.setString(1, clean);
      statement.setLong(2, userId);
      statement.executeUpdate();
    }
    store.changed(Schema.PROFILES, profileOf(userId).id(), MutationEvent.Kind.update, userId);
  }

  /** it worked: the point, how exact it is, and who said so */
  public void placed(long userId, double latitude, double longitude, String precision,
                     String service) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PROFILES + " SET latitude = ?, longitude = ?, geo_precision = ?,"
                 + " geo_state = ?, geo_service = ?, geo_tries = 0, geo_next_at = NULL,"
                 + " geo_tried_at = CURRENT_TIMESTAMP, geo_note = '' WHERE user_id = ?")) {
      statement.setDouble(1, latitude);
      statement.setDouble(2, longitude);
      statement.setString(3, cap(precision, 16));
      statement.setString(4, Placement.PLACED);
      statement.setString(5, cap(service, 32));
      statement.setLong(6, userId);
      statement.executeUpdate();
    }
  }

  /**
   * The service answered, and has never heard of it.
   *
   * The point is cleared because there is none, and nothing will ask again on its own: the same
   * question to the same service tomorrow gets the same answer, and a queue that kept asking would
   * spend a slot a minute on a typo forever. What re-opens it is the address changing, the service
   * changing, or somebody pressing the button.
   */
  public void notFound(long userId, String service, String note) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PROFILES + " SET latitude = NULL, longitude = NULL,"
                 + " geo_precision = '', geo_state = ?, geo_service = ?, geo_tries = 0,"
                 + " geo_next_at = NULL, geo_tried_at = CURRENT_TIMESTAMP, geo_note = ?"
                 + " WHERE user_id = ?")) {
      statement.setString(1, Placement.NOT_FOUND);
      statement.setString(2, cap(service, 32));
      statement.setString(3, cap(note, 256));
      statement.setLong(4, userId);
      statement.executeUpdate();
    }
  }

  /**
   * The service could not be asked.
   *
   * Whatever point is already there is left alone -- this says nothing about the address -- and a
   * time is written down for when to try again, widening with each failure.
   */
  public void unreachable(long userId, String service, String note, long now) throws SQLException {
    int tries = homeOf(userId).placement().tries() + 1;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PROFILES + " SET geo_state = ?, geo_service = ?, geo_tries = ?,"
                 + " geo_next_at = ?, geo_tried_at = CURRENT_TIMESTAMP, geo_note = ?"
                 + " WHERE user_id = ?")) {
      statement.setString(1, Placement.UNREACHABLE);
      statement.setString(2, cap(service, 32));
      statement.setInt(3, tries);
      statement.setTimestamp(4, Placement.scheduleAfter(tries, now));
      statement.setString(5, cap(note, 256));
      statement.setLong(6, userId);
      statement.executeUpdate();
    }
  }

  /**
   * Everybody's point, and nothing else about them.
   *
   * Coordinates only -- no address, no name, not even a row for somebody who has given nothing. It
   * is what a distance histogram is built from, and it is the widest view of this data that exists
   * anywhere in the server.
   */
  public java.util.Map<Long, double[]> points() throws SQLException {
    java.util.LinkedHashMap<Long, double[]> found = new java.util.LinkedHashMap<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT user_id, latitude, longitude, geo_precision FROM " + Schema.PROFILES
                 + " WHERE latitude IS NOT NULL AND longitude IS NOT NULL");
         ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        found.put(rows.getLong("user_id"), new double[]{rows.getDouble("latitude"),
            rows.getDouble("longitude"),
            Home.PRECISE.equals(rows.getString("geo_precision")) ? 1 : 0});
      }
    }
    return found;
  }

  /**
   * Everybody worth asking about right now.
   *
   * The query *is* the queue -- the same rule the notifier follows. Three things make a row due:
   * it has never been asked about; it could not be reached and its wait has passed; or it was not
   * found by a service that is no longer the one configured, which is what makes switching
   * geocoder re-open every address the old one could not place.
   */
  public java.util.List<Long> dueForPlacement(String service, long now, int limit)
      throws SQLException {
    java.util.ArrayList<Long> found = new java.util.ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT user_id FROM " + Schema.PROFILES
                 + " WHERE (address <> '' OR location <> '') AND geo_state <> ?"
                 + " AND (geo_state = '' "
                 + "      OR (geo_state = ? AND (geo_next_at IS NULL OR geo_next_at <= ?))"
                 + "      OR (geo_state = ? AND geo_service <> ?))"
                 + " ORDER BY user_id " + store.dialect().limit(limit))) {
      statement.setString(1, Placement.PLACED);
      statement.setString(2, Placement.UNREACHABLE);
      statement.setTimestamp(3, new java.sql.Timestamp(now));
      statement.setString(4, Placement.NOT_FOUND);
      statement.setString(5, service == null ? "" : service);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(rows.getLong("user_id"));
        }
      }
    }
    return found;
  }

  /**
   * Forget every failure, so everything is due again.
   *
   * The button for after a service was down all day, or for after somebody fixed whatever was
   * wrong at the other end. It deliberately does not touch rows that worked: re-asking about an
   * address that already has a point is spending somebody else's rate limit on an answer we hold.
   *
   * @return how many are now waiting.
   */
  public int reopenPlacements() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PROFILES + " SET geo_state = '', geo_tries = 0,"
                 + " geo_next_at = NULL WHERE geo_state <> '' AND geo_state <> ?")) {
      statement.setString(1, Placement.PLACED);
      return statement.executeUpdate();
    }
  }

  /** how many are placed, unfindable, waiting to be retried, or have never been asked about */
  public java.util.Map<String, Integer> placementCounts() throws SQLException {
    java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT geo_state, COUNT(*) AS how_many FROM " + Schema.PROFILES
                 + " WHERE address <> '' OR location <> '' GROUP BY geo_state");
         ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        String state = rows.getString("geo_state");
        counts.put(state == null || state.isBlank() ? Placement.UNKNOWN : state,
            rows.getInt("how_many"));
      }
    }
    return counts;
  }

  public void forget(long userId, Long actor) throws SQLException {
    try (Connection connection = store.connection()) {
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.PROFILES + " WHERE user_id = ?")) {
        statement.setLong(1, userId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.ANSWERS + " WHERE user_id = ?")) {
        statement.setLong(1, userId);
        statement.executeUpdate();
      }
    }
    store.changed(Schema.PROFILES, userId, MutationEvent.Kind.delete, actor);
    store.changed(Schema.ANSWERS, userId, MutationEvent.Kind.delete, actor);
  }

  // ---- questions -------------------------------------------------------------------------------

  /** every question, in order; unpublished included so an admin can see their own drafts */
  /** every live question; a deleted one is gone from here the moment it is deleted */
  public List<Question> allQuestions() throws SQLException {
    return questionsWhere("deleted_at IS NULL");
  }

  /**
   * The questions waiting to be cleaned up.
   *
   * Deleting hides a question; it does not touch anybody's answers. Purging does, and that is a
   * cascade across every answer sheet in the community -- so it happens on its own page, when an
   * admin says so, rather than inside the click that meant "stop asking this".
   */
  public List<Question> deletedQuestions() throws SQLException {
    return questionsWhere("deleted_at IS NOT NULL");
  }

  private List<Question> questionsWhere(String predicate) throws SQLException {
    ArrayList<Question> questions = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + QUESTION_COLUMNS + " FROM " + Schema.QUESTIONS + " WHERE " + predicate
                 + " ORDER BY position, id " + store.dialect().limit(500));
         ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        questions.add(readQuestion(rows));
      }
    }
    return questions;
  }

  /** the questions people are actually asked */
  public List<Question> publishedQuestions() throws SQLException {
    ArrayList<Question> questions = new ArrayList<>();
    for (Question question : allQuestions()) {
      if (question.published()) {
        questions.add(question);
      }
    }
    return questions;
  }

  /** one question, deleted or not; callers that must not see deleted ones check {@link Question#deleted} */
  public Question questionById(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + QUESTION_COLUMNS + " FROM " + Schema.QUESTIONS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readQuestion(rows) : null;
      }
    }
  }

  public Question askQuestion(String definition, int position, boolean published, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.QUESTIONS + " (definition, position, published, created_by)"
                 + " VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, definition);
      statement.setInt(2, position);
      statement.setBoolean(3, published);
      setActor(statement, 4, actor);
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        long id = keys.getLong(1);
        store.changed(Schema.QUESTIONS, id, MutationEvent.Kind.insert, actor);
        return questionById(id);
      }
    }
  }

  public Question updateQuestion(long id, String definition, int position, boolean published, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.QUESTIONS + " SET definition = ?, position = ?, published = ?,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setString(1, definition);
      statement.setInt(2, position);
      statement.setBoolean(3, published);
      statement.setLong(4, id);
      statement.executeUpdate();
    }
    store.changed(Schema.QUESTIONS, id, MutationEvent.Kind.update, actor);
    return questionById(id);
  }

  /**
   * Stop asking a question.
   *
   * A soft delete, because the alternative is a cascade: the question's answers live inside every
   * respondent's blob, and tearing them out is a rewrite of every answer sheet in the community
   * performed inside somebody's click. Worse, it is irreversible, and "delete" is the button people
   * press by accident. So this hides the question and stops it counting, and {@link #purgeQuestion}
   * does the rewrite later, deliberately, from a page that says what it is about to do.
   */
  public void deleteQuestion(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.QUESTIONS + " SET deleted_at = CURRENT_TIMESTAMP, deleted_by = ?,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND deleted_at IS NULL")) {
      setActor(statement, 1, actor);
      statement.setLong(2, id);
      statement.executeUpdate();
    }
    store.changed(Schema.QUESTIONS, id, MutationEvent.Kind.update, actor);
  }

  /** put a deleted question back; the answers never went anywhere, so they simply count again */
  public void restoreQuestion(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.QUESTIONS + " SET deleted_at = NULL, deleted_by = NULL,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.QUESTIONS, id, MutationEvent.Kind.update, actor);
  }

  /**
   * Commit the deletion: drop the question and strip its answer from every sheet.
   *
   * This is the cascade, done in one place, once, on purpose. It returns how many sheets it
   * rewrote so the page that ran it can say what it did rather than claiming success silently.
   */
  public int purgeQuestion(long id, Long actor) throws SQLException {
    int rewritten = 0;
    for (long userId : everybodyWithAnswers()) {
      AnswerSheet sheet = answersOf(userId);
      if (sheet.answerTo(id) == null) {
        continue;
      }
      saveAnswers(sheet.with(id, null));
      rewritten++;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.QUESTIONS + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.QUESTIONS, id, MutationEvent.Kind.delete, actor);
    return rewritten;
  }

  public long questionCount() throws SQLException {
    return count(Schema.QUESTIONS);
  }

  // ---- answers ---------------------------------------------------------------------------------

  public AnswerSheet answersOf(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + ANSWER_COLUMNS + " FROM " + Schema.ANSWERS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next()
            ? AnswerSheet.parse(userId, rows.getString("blob"), rows.getInt("answered"), rows.getInt("remaining"))
            : AnswerSheet.empty(userId);
      }
    }
  }

  /** store a sheet; the counts are left to the indexer, which is the only thing that owns them */
  public void saveAnswers(AnswerSheet sheet) throws SQLException {
    boolean exists;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT 1 FROM " + Schema.ANSWERS + " WHERE user_id = ?")) {
      statement.setLong(1, sheet.userId());
      try (ResultSet rows = statement.executeQuery()) {
        exists = rows.next();
      }
    }
    String sql = exists
        ? "UPDATE " + Schema.ANSWERS + " SET blob = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?"
        : "INSERT INTO " + Schema.ANSWERS + " (blob, user_id) VALUES (?, ?)";
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, sheet.toBlob());
      statement.setLong(2, sheet.userId());
      statement.executeUpdate();
    }
    store.changed(Schema.ANSWERS, sheet.userId(),
        exists ? MutationEvent.Kind.update : MutationEvent.Kind.insert, sheet.userId());
  }

  /**
   * Apply a set of changes to somebody's answers without touching the rest.
   *
   * A merge, not a replace, and it has to be a merge for a reason that only shows up once the survey
   * is being used the way it is meant to be: questions keep arriving. Somebody who answered five
   * questions last month and is now shown the two new ones submits a form that mentions two
   * questions. If that submission were the new state of their sheet, answering the new ones would
   * silently erase the old ones -- and the page would look like it worked.
   *
   * So the caller sends only what changed, this reads the current sheet and lays the changes over
   * it, and a key that is absent is left exactly as it was. A key mapped to null is an erasure,
   * which is a thing somebody can legitimately want and cannot otherwise express.
   *
   * The read and the write happen here rather than in the handler so that "what the sheet was" and
   * "what it becomes" cannot drift apart across a request boundary.
   */
  public AnswerSheet mergeAnswers(long userId, Map<Long, String> changes) throws SQLException {
    AnswerSheet sheet = answersOf(userId);
    if (changes.isEmpty()) {
      return sheet;
    }
    for (Map.Entry<Long, String> change : changes.entrySet()) {
      sheet = sheet.with(change.getKey(), change.getValue());
    }
    saveAnswers(sheet);
    return sheet;
  }

  /**
   * Write the counts back.
   *
   * Deliberately does NOT emit an event: the indexer is what reacts to answer events, and an event
   * here would have it wake itself up forever.
   */
  public void recordCounts(long userId, int answered, int remaining) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.ANSWERS + " SET answered = ?, remaining = ?,"
                 + " indexed_at = CURRENT_TIMESTAMP WHERE user_id = ?")) {
      statement.setInt(1, answered);
      statement.setInt(2, remaining);
      statement.setLong(3, userId);
      statement.executeUpdate();
    }
  }

  /** everybody who has an answers row, for a full re-index */
  public List<Long> everybodyWithAnswers() throws SQLException {
    ArrayList<Long> users = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT user_id FROM " + Schema.ANSWERS);
         ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        users.add(rows.getLong(1));
      }
    }
    return users;
  }

  public long answerCount() throws SQLException {
    return count(Schema.ANSWERS);
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private long count(String table) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static void setActor(PreparedStatement statement, int index, Long actor) throws SQLException {
    if (actor == null) {
      statement.setNull(index, java.sql.Types.BIGINT);
    } else {
      statement.setLong(index, actor);
    }
  }

  private static String cap(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }

  private static Home readHome(ResultSet rows) throws SQLException {
    double latitude = rows.getDouble("latitude");
    boolean noLatitude = rows.wasNull();
    double longitude = rows.getDouble("longitude");
    boolean noLongitude = rows.wasNull();
    return new Home(rows.getLong("user_id"),
        rows.getString("address"),
        noLatitude ? null : latitude,
        noLongitude ? null : longitude,
        rows.getString("geo_precision"),
        new Placement(rows.getString("geo_state"), rows.getString("geo_service"),
            rows.getInt("geo_tries"), rows.getTimestamp("geo_tried_at"),
            rows.getTimestamp("geo_next_at"), rows.getString("geo_note")));
  }

  private static ProfileRecord readProfile(ResultSet rows) throws SQLException {
    return new ProfileRecord(
        rows.getLong("id"),
        rows.getLong("user_id"),
        rows.getString("display_name"),
        rows.getString("headline"),
        rows.getString("about"),
        rows.getString("location"),
        rows.getString("links"),
        rows.getInt("orientation_step"),
        rows.getTimestamp("created_at"),
        rows.getTimestamp("updated_at"));
  }

  private static Question readQuestion(ResultSet rows) throws SQLException {
    return Question.parse(rows.getLong("id"), rows.getString("definition"),
        rows.getInt("position"), rows.getBoolean("published"), rows.getTimestamp("updated_at"),
        rows.getTimestamp("deleted_at") != null);
  }
}
