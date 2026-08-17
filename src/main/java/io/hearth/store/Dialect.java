package io.hearth.store;

/**
 * The parts of SQL that differ between databases.
 *
 * This is the whole portability surface. Everything else in this project writes plain SQL that H2,
 * MySQL and PostgreSQL all accept; the handful of places where they genuinely disagree -- how you
 * declare an auto-incrementing key, how you limit a result set, how you upsert -- come through here.
 *
 * Keeping the list short is the point. A dialect with thirty methods is a sign the code above it is
 * writing database-specific SQL and pretending not to.
 */
public interface Dialect {
  /** a name for the boot report and error messages */
  String name();

  /** the column fragment for a generated primary key */
  String identityColumn();

  /** limit a result set; H2 in strict mode refuses LIMIT, MySQL refuses FETCH FIRST */
  String limit(int rows);

  /** insert-or-update on the given key columns */
  String upsert(String table, String[] columns, String[] keyColumns);

  /** true when this database understands "ALTER TABLE ... ADD COLUMN x BEFORE y" */
  boolean supportsColumnPositioning();

  /** the schema our tables live in, for metadata lookups */
  String schemaName();

  /** the text type for a page of content; sizes differ enough to matter */
  String longTextType();
}
