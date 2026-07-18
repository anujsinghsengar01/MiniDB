package com.minidb.parser.ast;

/** `DROP TABLE name` */
public final class DropTableStatement implements Statement {
    public final String tableName;

    public DropTableStatement(String tableName) {
        this.tableName = tableName;
    }
}
