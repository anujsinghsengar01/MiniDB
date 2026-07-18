package com.minidb.catalog;

import java.util.Objects;

/**
 * Describes one column of a table: its name, type, and constraints.
 * Immutable — a schema change (future ALTER TABLE) creates a new
 * TableSchema rather than mutating columns in place.
 */
public final class Column {
    private final String name;
    private final ColumnType type;
    private final int maxLength; // only meaningful for VARCHAR; 0 otherwise
    private final boolean primaryKey;
    private final boolean nullable;

    public Column(String name, ColumnType type, int maxLength, boolean primaryKey, boolean nullable) {
        if (type == ColumnType.VARCHAR && maxLength <= 0) {
            throw new IllegalArgumentException("VARCHAR column '" + name + "' must have a positive maxLength");
        }
        this.name = name;
        this.type = type;
        this.maxLength = maxLength;
        // A primary key column can never be null — that's what makes it a valid row identifier.
        this.primaryKey = primaryKey;
        this.nullable = primaryKey ? false : nullable;
    }

    /** Convenience factory for non-VARCHAR, nullable, non-key columns. */
    public static Column of(String name, ColumnType type) {
        return new Column(name, type, 0, false, true);
    }

    /** Convenience factory for VARCHAR columns. */
    public static Column varchar(String name, int maxLength) {
        return new Column(name, ColumnType.VARCHAR, maxLength, false, true);
    }

    public static Column primaryKey(String name, ColumnType type) {
        return new Column(name, type, 0, true, false);
    }

    public String getName() { return name; }
    public ColumnType getType() { return type; }
    public int getMaxLength() { return maxLength; }
    public boolean isPrimaryKey() { return primaryKey; }
    public boolean isNullable() { return nullable; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Column)) return false;
        Column column = (Column) o;
        return maxLength == column.maxLength
                && primaryKey == column.primaryKey
                && nullable == column.nullable
                && name.equals(column.name)
                && type == column.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, maxLength, primaryKey, nullable);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name).append(" ").append(type);
        if (type == ColumnType.VARCHAR) sb.append("(").append(maxLength).append(")");
        if (primaryKey) sb.append(" PRIMARY KEY");
        else if (!nullable) sb.append(" NOT NULL");
        return sb.toString();
    }
}
