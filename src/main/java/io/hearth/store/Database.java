package io.hearth.store;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A place to put rows.
 *
 * Exists so that H2 is a choice rather than an assumption. Nothing above this interface knows what
 * is behind it: a MySQL or PostgreSQL implementation is this interface plus a {@link Dialect}, and
 * the DAOs, the schema, and the upgrader keep working.
 *
 * That matters for the scaling story. One jar on one box is the design point, but the escape route
 * -- several processes behind a sticky load balancer sharing one database -- only exists if the
 * database was never welded in. The other half of that route is the event bus, so that caches in
 * different processes can agree; both are interfaces for the same reason.
 */
public interface Database extends AutoCloseable {
  /** a connection from the pool; the caller closes it */
  Connection connection() throws SQLException;

  Dialect dialect();

  /** what this database is called, for the boot report */
  String describe();

  @Override
  void close();
}
