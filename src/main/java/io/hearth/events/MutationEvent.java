package io.hearth.events;

/**
 * Something changed: which domain, which table, which row.
 *
 * Granular on purpose. "the content table changed" would force every cache to throw everything
 * away; "content row 41 on example.org was updated" lets a cache drop one entry and lets a future
 * external subscriber decide for itself what it cares about.
 *
 * The shape is deliberately primitive -- strings and longs, no object graph -- because this is the
 * thing that has to survive being serialized onto a wire when the bus stops being in-process. A
 * record that referenced a UserRecord would be a record that could not leave the JVM.
 *
 * @param seq       monotonic within this process; the ordering an inspector shows
 * @param atMillis  wall clock, for the operator reading it
 * @param domain    the database domain the row lives in, which is the sharing boundary
 * @param table     the table name, as declared in Schema
 * @param key       the primary key, as text so composite and non-numeric keys need no new shape
 * @param kind      insert, update or delete
 * @param actor     the user id behind the change, or null for the system
 */
public record MutationEvent(long seq, long atMillis, String domain, String table, String key,
                            Kind kind, Long actor) {
  public enum Kind {
    insert,
    update,
    delete
  }

  /** does this event touch a specific row of a specific table? */
  public boolean touches(String otherTable, String otherKey) {
    return table.equals(otherTable) && key.equals(otherKey);
  }

  public boolean touches(String otherTable) {
    return table.equals(otherTable);
  }

  @Override
  public String toString() {
    return "#" + seq + " " + domain + " " + table + "/" + key + " " + kind
        + (actor == null ? "" : " by " + actor);
  }
}
