package com.minidb.executor;

/** Thrown for runtime query errors: type mismatches, missing columns, constraint violations, etc. */
public class ExecutionException extends RuntimeException {
    public ExecutionException(String message) {
        super(message);
    }
}
