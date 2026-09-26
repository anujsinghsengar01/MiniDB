package com.minidb.transaction;

import java.util.HashMap;
import java.util.Map;

/**
 * Table-level exclusive locking. A transaction that touches a table holds
 * that lock until COMMIT/ROLLBACK — classic two-phase locking (2PL): all
 * locks acquired before any are released. With only one active transaction
 * allowed at a time (see TransactionManager), a second transaction can
 * never actually exist yet to contend for a lock — this class exists as
 * the correct extension point for real concurrency later, not as a
 * decoration: the acquire/release protocol is exactly what a future
 * multi-transaction scheduler would call unchanged.
 */
public class LockManager {

    private final Map<String, Integer> tableOwner = new HashMap<>();

    public synchronized void acquire(int txnId, String table) {
        String key = table.toLowerCase();
        Integer owner = tableOwner.get(key);
        if (owner != null && owner.intValue() != txnId) {
            throw new TransactionException("Table '" + table + "' is locked by transaction " + owner);
        }
        tableOwner.put(key, txnId);
    }

    public synchronized void releaseAll(int txnId) {
        tableOwner.values().removeIf(owner -> owner.intValue() == txnId);
    }
}
