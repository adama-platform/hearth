package io.hearth.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One table as the code declares it: ordered columns, plus the indexes that make it fast. */
public class Table {
  public final String name;
  public final List<Column> columns;
  public final List<Index> indexes;
  public final List<Unique> uniques;

  private Table(String name, List<Column> columns, List<Index> indexes, List<Unique> uniques) {
    this.name = name;
    this.columns = List.copyOf(columns);
    this.indexes = List.copyOf(indexes);
    this.uniques = List.copyOf(uniques);
  }

  public static Builder named(String name) {
    return new Builder(name);
  }

  /** column definitions in declared order, keyed by uppercase name for comparison against H2 */
  public Map<String, Column> byName() {
    LinkedHashMap<String, Column> map = new LinkedHashMap<>();
    for (Column column : columns) {
      map.put(column.name.toUpperCase(java.util.Locale.ROOT), column);
    }
    return map;
  }

  public String createDdl() {
    StringBuilder sb = new StringBuilder("CREATE TABLE ").append(name).append(" (");
    for (int k = 0; k < columns.size(); k++) {
      if (k > 0) {
        sb.append(", ");
      }
      sb.append(columns.get(k).ddl());
    }
    for (Column column : columns) {
      if (column.unique) {
        sb.append(", CONSTRAINT uq_").append(name).append('_').append(column.name)
            .append(" UNIQUE (").append(column.name).append(')');
      }
    }
    for (Unique unique : uniques) {
      sb.append(", CONSTRAINT ").append(unique.name())
          .append(" UNIQUE (").append(String.join(", ", unique.columns())).append(')');
    }
    return sb.append(')').toString();
  }

  public static class Builder {
    private final String name;
    private final List<Column> columns = new ArrayList<>();
    private final List<Index> indexes = new ArrayList<>();
    private final List<Unique> uniques = new ArrayList<>();

    Builder(String name) {
      this.name = name;
    }

    public Builder column(Column column) {
      columns.add(column);
      return this;
    }

    public Builder index(String name, String... columns) {
      indexes.add(new Index(name, Arrays.asList(columns)));
      return this;
    }

    /** a uniqueness rule across more than one column, e.g. one row per (user, role) */
    public Builder unique(String name, String... columns) {
      uniques.add(new Unique(name, Arrays.asList(columns)));
      return this;
    }

    public Table build() {
      return new Table(name, columns, indexes, uniques);
    }
  }

  public record Index(String name, List<String> columns) {
    public String createDdl(String table) {
      return "CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + String.join(", ", columns) + ")";
    }
  }

  /** a composite uniqueness constraint; added after the fact on an upgrade */
  public record Unique(String name, List<String> columns) {
    public String alterDdl(String table) {
      return "ALTER TABLE " + table + " ADD CONSTRAINT IF NOT EXISTS " + name
          + " UNIQUE (" + String.join(", ", columns) + ")";
    }
  }
}
