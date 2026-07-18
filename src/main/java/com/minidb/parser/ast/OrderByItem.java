package com.minidb.parser.ast;

/** One `column ASC|DESC` entry in an ORDER BY clause. */
public final class OrderByItem {
    public final String columnName;
    public final boolean ascending;

    public OrderByItem(String columnName, boolean ascending) {
        this.columnName = columnName;
        this.ascending = ascending;
    }
}
