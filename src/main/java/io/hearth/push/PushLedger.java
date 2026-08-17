package io.hearth.push;

import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When a push went out, and when somebody did something about it.
 *
 * <b>What this measures is the only thing worth measuring about a notification: how long it takes
 * to work.</b> Not how many were sent -- that number goes up whether or not anybody looked -- but
 * the gap between a phone buzzing and a person arriving. A community whose median is four minutes
 * has a channel that works; one whose median is nine hours has a channel that is really a very slow
 * email, and should probably stop pretending otherwise.
 *
 * <b>In memory, flushed on a timer, and that is a deliberate trade.</b> Every push and every click
 * would otherwise be two writes on a path that fires whenever anything happens on the board -- and
 * the numbers being a few minutes stale costs nobody anything, while the writes would be among the
 * busiest in the server. The buffer also dedupes: a person with three devices generates three
 * pushes and one arrival, and what is written down is one send stamp and one acted-upon stamp per
 * person.
 *
 * <b>Losing a few minutes of it on a restart is fine.</b> This is a histogram for a screen, not a
 * record of anything. Nothing depends on a sample arriving, which is what makes buffering it
 * honest rather than a shortcut.
 */
public class PushLedger {
  /** how long a sample sits in memory before it is written down */
  public static final long FLUSH_MILLIS = 5 * 60 * 1000L;
  /** the buckets the delay histogram is drawn in, in minutes */
  private static final int[] BUCKETS = {1, 5, 15, 60, 240, 1440};

  /** one person's pending stamps */
  private record Pending(long sentAt, long actedAt) {
  }

  private final Store store;
  private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
  private volatile long lastFlush = System.currentTimeMillis();

  public PushLedger(Store store) {
    this.store = store;
  }

  /**
   * A push just went to this person.
   *
   * The *first* one wins for a person who is not already waiting: three devices buzzing is one
   * notification as far as anybody experiencing it is concerned, and taking the last would measure
   * the delay from whichever device the loop happened to reach last.
   */
  public void sent(long userId, long at) {
    pending.compute(userId, (id, had) -> had == null || had.sentAt() == 0
        ? new Pending(at, had == null ? 0 : had.actedAt())
        : had);
  }

  /**
   * They came back.
   *
   * Recorded whether or not a send is pending, because the useful pairing is "the last thing we
   * sent them" and that may have been flushed already -- the delay is computed against whatever the
   * row says, which is the same number either way.
   */
  public void acted(long userId, long at) {
    pending.compute(userId, (id, had) ->
        new Pending(had == null ? 0 : had.sentAt(), at));
  }

  /** is anything waiting, and has it waited long enough? */
  public boolean due(long now) {
    return !pending.isEmpty() && now - lastFlush >= FLUSH_MILLIS;
  }

  public int waiting() {
    return pending.size();
  }

  /**
   * Write the buffer down.
   *
   * Drained rather than copied: anything arriving during the write goes into the next pass, which
   * is the behaviour that makes "flush every five minutes" mean five minutes rather than "five
   * minutes plus however long the write took".
   */
  public int flush(long now) throws SQLException {
    lastFlush = now;
    if (pending.isEmpty()) {
      return 0;
    }
    ArrayList<Long> ids = new ArrayList<>(pending.keySet());
    int written = 0;
    for (Long id : ids) {
      Pending stamps = pending.remove(id);
      if (stamps == null) {
        continue;
      }
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "UPDATE " + Schema.EMAILS + " SET last_push_at = CASE WHEN ? > 0 THEN ?"
                   + " ELSE last_push_at END, last_push_acted_at = CASE WHEN ? > 0 THEN ?"
                   + " ELSE last_push_acted_at END WHERE id = ?")) {
        statement.setLong(1, stamps.sentAt());
        statement.setTimestamp(2, stamps.sentAt() == 0 ? null : new Timestamp(stamps.sentAt()));
        statement.setLong(3, stamps.actedAt());
        statement.setTimestamp(4, stamps.actedAt() == 0 ? null : new Timestamp(stamps.actedAt()));
        statement.setLong(5, id);
        written += statement.executeUpdate();
      }
    }
    return written;
  }

  /** one person's pair, for their own page */
  public record Delay(Timestamp sentAt, Timestamp actedAt) {
    /** how long they took, in minutes, or -1 when one half is missing */
    public long minutes() {
      if (sentAt == null || actedAt == null || actedAt.before(sentAt)) {
        return -1;
      }
      return (actedAt.getTime() - sentAt.getTime()) / 60000;
    }
  }

  public Delay delayFor(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT last_push_at, last_push_acted_at FROM " + Schema.EMAILS + " WHERE id = ?")) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next()
            ? new Delay(rows.getTimestamp("last_push_at"), rows.getTimestamp("last_push_acted_at"))
            : new Delay(null, null);
      }
    }
  }

  /**
   * How long people take, in buckets.
   *
   * Buckets rather than an average, because the average of "three people in a minute and one
   * person tomorrow" is six hours, which describes nobody. What an operator wants to see is the
   * shape: mostly-fast with a tail is a healthy channel, mostly-tail is a channel people have
   * turned off in their phone settings and nobody has noticed.
   */
  public List<Map<String, Object>> histogram() throws SQLException {
    long[] counts = new long[BUCKETS.length + 1];
    long answered = 0;
    long sentNeverActed = 0;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT last_push_at, last_push_acted_at FROM " + Schema.EMAILS
                 + " WHERE last_push_at IS NOT NULL");
         ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        Timestamp sent = rows.getTimestamp("last_push_at");
        Timestamp acted = rows.getTimestamp("last_push_acted_at");
        if (acted == null || acted.before(sent)) {
          sentNeverActed++;
          continue;
        }
        answered++;
        long minutes = (acted.getTime() - sent.getTime()) / 60000;
        int bucket = BUCKETS.length;
        for (int k = 0; k < BUCKETS.length; k++) {
          if (minutes <= BUCKETS[k]) {
            bucket = k;
            break;
          }
        }
        counts[bucket]++;
      }
    }
    ArrayList<Map<String, Object>> out = new ArrayList<>();
    long most = Math.max(1, java.util.Arrays.stream(counts).max().orElse(1));
    for (int k = 0; k < counts.length; k++) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("label", k < BUCKETS.length ? "within " + label(BUCKETS[k]) : "longer");
      row.put("count", counts[k]);
      row.put("width", Math.round(100.0 * counts[k] / most));
      out.add(row);
    }
    LinkedHashMap<String, Object> never = new LinkedHashMap<>();
    never.put("label", "no answer yet");
    never.put("count", sentNeverActed);
    never.put("width", Math.round(100.0 * sentNeverActed / Math.max(1, most)));
    never.put("unanswered", true);
    out.add(never);
    LinkedHashMap<String, Object> total = new LinkedHashMap<>();
    total.put("label", "answered");
    total.put("count", answered);
    total.put("total", true);
    out.add(total);
    return out;
  }

  private static String label(int minutes) {
    if (minutes < 60) {
      return minutes + " min";
    }
    return minutes < 1440 ? (minutes / 60) + "h" : (minutes / 1440) + "d";
  }
}
