package com.minidb.parser.ast;

/** `CREATE INDEX indexName ON tableName (columnName)` */
public final class CreateIndexStatement implements Statement {
    public final String indexName;
    public final String tableName;
    public final String columnName;

    public CreateIndexStatement(String indexName, String tableName, String columnName) {
        this.indexName = indexName;
        this.tableName = tableName;
        this.columnName = columnName;
    }
}
