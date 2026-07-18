package com.minidb.parser.ast;

/** `DELETE FROM table [WHERE ...]` — a null whereClause deletes every row. */
public final class DeleteStatement implements Statement {
    public final String tableName;
    public final Expression whereClause; // null if no WHERE

    public DeleteStatement(String tableName, Expression whereClause) {
        this.tableName = tableName;
        this.whereClause = whereClause;
    }
}
