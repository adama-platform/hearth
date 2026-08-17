package io.hearth.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * H2 as a file on disk, pooled by Hikari.
 *
 * AUTO_SERVER is off and the URL is file-local: this is an embedded database owned by exactly one
 * process, which is the operational premise of a single jar. The day that stops being true is the
 * day a different {@link Database} implementation gets written, not the day this one grows a flag.
 */
public class H2Database implements Database {
  private static final Dialect DIALECT = new H2Dialect();
  private final HikariDataSource pool;
  private final File file;

  public H2Database(File file, String poolName) {
    this.file = file;
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:" + file.getAbsolutePath() + ";MODE=STRICT;DEFRAG_ALWAYS=FALSE");
    config.setDriverClassName("org.h2.Driver");
    config.setUsername("sa");
    config.setPassword("");
    config.setPoolName(poolName);
    config.setMaximumPoolSize(8);
    config.setMinimumIdle(1);
    config.setAutoCommit(true);
    this.pool = new HikariDataSource(config);
  }

  @Override
  public Connection connection() throws SQLException {
    return pool.getConnection();
  }

  @Override
  public Dialect dialect() {
    return DIALECT;
  }

  @Override
  public String describe() {
    return "h2 " + file.getName() + ".mv.db";
  }

  @Override
  public void close() {
    pool.close();
  }
}
