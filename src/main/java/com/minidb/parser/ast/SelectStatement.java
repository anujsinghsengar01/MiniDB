package com.minidb.parser.ast;

import java.util.List;

/**
 * `SELECT columns FROM table [JOIN ... ON ...] [WHERE ...] [ORDER BY ...]`
 * An empty/null `columns` list with isSelectStar=true represents `SELECT *`.
 */
public final class SelectStatement implements Statement {
    public final boolean selectStar;
    public final List<String> columns; // empty if selectStar
    public final String fromTable;
    public final JoinClause join; // null if no JOIN
    public final Expression whereClause; // null if no WHERE
    public final List<OrderByItem> orderBy; // empty if no ORDER BY

    public SelectStatement(boolean selectStar, List<String> columns, String fromTable,
                            JoinClause join, Expression whereClause, List<OrderByItem> orderBy) {
        this.selectStar = selectStar;
        this.columns = columns;
        this.fromTable = fromTable;
        this.join = join;
        this.whereClause = whereClause;
        this.orderBy = orderBy;
    }
}
