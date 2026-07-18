package com.minidb.parser.ast;

/** `JOIN otherTable ON leftCol = rightCol` — MiniDB supports a single inner equi-join for now. */
public final class JoinClause {
    public final String joinTable;
    public final Expression onCondition;

    public JoinClause(String joinTable, Expression onCondition) {
        this.joinTable = joinTable;
        this.onCondition = onCondition;
    }
}
