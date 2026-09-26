package com.minidb.parser.ast;

/** `BEGIN [TRANSACTION]`, `COMMIT [TRANSACTION]`, or `ROLLBACK [TRANSACTION]`. */
public final class TransactionControlStatement implements Statement {
    public enum Kind { BEGIN, COMMIT, ROLLBACK }

    public final Kind kind;

    public TransactionControlStatement(Kind kind) {
        this.kind = kind;
    }
}
