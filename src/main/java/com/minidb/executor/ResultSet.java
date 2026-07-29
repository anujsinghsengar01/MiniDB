package com.minidb.executor;

import java.util.List;

/** The output of a SELECT: column names plus the matching rows, in final (filtered/sorted) order. */
public final class ResultSet {
    private final List<String> columnNames;
    private final List<Object[]> rows;

    public ResultSet(List<String> columnNames, List<Object[]> rows) {
        this.columnNames = columnNames;
        this.rows = rows;
    }

    public List<String> getColumnNames() { return columnNames; }
    public List<Object[]> getRows() { return rows; }
    public int size() { return rows.size(); }
}
