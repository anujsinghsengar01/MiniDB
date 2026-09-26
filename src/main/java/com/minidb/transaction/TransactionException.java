package com.minidb.transaction;

/** Thrown for transaction errors: nested BEGIN, COMMIT/ROLLBACK with no active transaction, lock conflicts. */
public class TransactionException extends RuntimeException {
    public TransactionException(String message) {
        super(message);
    }
}
