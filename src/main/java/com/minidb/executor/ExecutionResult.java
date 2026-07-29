package com.minidb.executor;

/**
 * What running any single statement produces. Exactly one of resultSet
 * (for SELECT) or affectedRows (for everything else) is meaningful —
 * message is always a short human-readable summary, handy for a CLI shell.
 */
public final class ExecutionResult {
    private final ResultSet resultSet; // null unless this was a SELECT
    private final int affectedRows;    // -1 for SELECT
    private final String message;

    private ExecutionResult(ResultSet resultSet, int affectedRows, String message) {
        this.resultSet = resultSet;
        this.affectedRows = affectedRows;
        this.message = message;
    }

    public static ExecutionResult query(ResultSet resultSet) {
        return new ExecutionResult(resultSet, -1, resultSet.size() + " row(s) returned");
    }

    public static ExecutionResult update(int affectedRows, String message) {
        return new ExecutionResult(null, affectedRows, message);
    }

    public boolean isQuery() { return resultSet != null; }
    public ResultSet getResultSet() { return resultSet; }
    public int getAffectedRows() { return affectedRows; }
    public String getMessage() { return message; }
}
