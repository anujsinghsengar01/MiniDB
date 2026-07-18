package com.minidb.catalog;

import java.io.*;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The schema of a single table: its name and ordered list of columns.
 *
 * TableSchema knows how to turn itself into bytes and back
 * (serialize/deserialize) so the Catalog can store schemas using the same
 * HeapFile storage engine that stores actual row data — MiniDB's metadata
 * is itself just rows in a (system) table, the same trick Postgres uses
 * with pg_catalog.
 */
public final class TableSchema {
    private final String tableName;
    private final List<Column> columns;

    public TableSchema(String tableName, List<Column> columns) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Table '" + tableName + "' must have at least one column");
        }
        this.tableName = tableName;
        this.columns = Collections.unmodifiableList(columns);
    }

    public String getTableName() { return tableName; }
    public List<Column> getColumns() { return columns; }

    /** Index of a column by name, or -1 if it doesn't exist. Used heavily by the executor. */
    public int getColumnIndex(String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        return -1;
    }

    public Column getColumn(String columnName) {
        int idx = getColumnIndex(columnName);
        if (idx == -1) {
            throw new CatalogException("Column '" + columnName + "' does not exist in table '" + tableName + "'");
        }
        return columns.get(idx);
    }

    public Optional<Column> getPrimaryKeyColumn() {
        return columns.stream().filter(Column::isPrimaryKey).findFirst();
    }

    // ---------- binary (de)serialization ----------
    //
    // Format:
    //   UTF  tableName
    //   int  columnCount
    //   for each column:
    //     UTF     name
    //     byte    type ordinal
    //     int     maxLength
    //     boolean primaryKey
    //     boolean nullable

    public byte[] serialize() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);

            out.writeUTF(tableName);
            out.writeInt(columns.size());
            for (Column col : columns) {
                out.writeUTF(col.getName());
                out.writeByte(col.getType().ordinal());
                out.writeInt(col.getMaxLength());
                out.writeBoolean(col.isPrimaryKey());
                out.writeBoolean(col.isNullable());
            }
            return bos.toByteArray();
        } catch (IOException e) {
            // Writing to an in-memory ByteArrayOutputStream cannot actually fail.
            throw new RuntimeException("Unexpected error serializing schema", e);
        }
    }

    public static TableSchema deserialize(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            String tableName = in.readUTF();
            int columnCount = in.readInt();
            List<Column> columns = new java.util.ArrayList<>(columnCount);
            ColumnType[] types = ColumnType.values();
            for (int i = 0; i < columnCount; i++) {
                String name = in.readUTF();
                ColumnType type = types[in.readByte()];
                int maxLength = in.readInt();
                boolean primaryKey = in.readBoolean();
                boolean nullable = in.readBoolean();
                columns.add(new Column(name, type, maxLength, primaryKey, nullable));
            }
            return new TableSchema(tableName, columns);
        } catch (IOException e) {
            throw new RuntimeException("Corrupt schema record", e);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TABLE ").append(tableName).append(" (\n");
        for (Column c : columns) {
            sb.append("  ").append(c).append("\n");
        }
        sb.append(")");
        return sb.toString();
    }
}
