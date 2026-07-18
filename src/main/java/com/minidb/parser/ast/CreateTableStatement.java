package com.minidb.parser.ast;

import com.minidb.catalog.Column;

import java.util.List;

/**
 * `CREATE TABLE name (col1 TYPE [PRIMARY KEY|NOT NULL], ...)`
 * Reuses com.minidb.catalog.Column directly rather than defining a
 * parallel "column definition" type — the parser's job is just to produce
 * the exact object the Catalog needs to hand to createTable().
 */
public final class CreateTableStatement implements Statement {
    public final String tableName;
    public final List<Column> columns;

    public CreateTableStatement(String tableName, List<Column> columns) {
        this.tableName = tableName;
        this.columns = columns;
    }
}
