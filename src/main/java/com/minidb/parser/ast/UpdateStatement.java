package com.minidb.parser.ast;

import java.util.List;

public final class UpdateStatement implements Statement {

    /** One `column = expression` assignment in a SET clause. */
    public static final class SetClause {
        public final String columnName;
        public final Expression.Literal value;
        public SetClause(String columnName, Expression.Literal value) {
            this.columnName = columnName;
            this.value = value;
        }
    }

    public final String tableName;
    public final List<SetClause> assignments;
    public final Expression whereClause; // null if no WHERE (updates every row)

    public UpdateStatement(String tableName, List<SetClause> assignments, Expression whereClause) {
        this.tableName = tableName;
        this.assignments = assignments;
        this.whereClause = whereClause;
    }
}
