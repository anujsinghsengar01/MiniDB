package com.minidb.index;

/** Thrown for index errors: duplicate index name, index on a nonexistent column, etc. */
public class IndexException extends RuntimeException {
    public IndexException(String message) {
        super(message);
    }
}
