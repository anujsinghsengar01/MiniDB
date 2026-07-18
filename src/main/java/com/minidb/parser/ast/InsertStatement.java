package com.minidb.parser.ast;

import java.util.List;

/**
 * `INSERT INTO table [(col1, col2, ...)] VALUES (val1, val2, ...)`
 * If `columns` is empty, values are positional against the table's full schema, in order.
 */
public final class InsertStatement implements Statement {
    public final String tableName;
    public final List<String> columns; // empty if not specified (positional insert)
    public final List<Expression.Literal> values;

    public InsertStatement(String tableName, List<String> columns, List<Expression.Literal> values) {
        this.tableName = tableName;
        this.columns = columns;
        this.values = values;
    }
}
