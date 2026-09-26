package com.minidb.transaction;

import com.minidb.storage.RecordId;

/**
 * One entry in a transaction's in-memory undo log. Records what actually
 * happened (the forward action already applied to the HeapFile/indexes)
 * in just enough detail to reverse it on ROLLBACK.
 */
public final class UndoEntry {

    public enum Type { INSERTED, DELETED, UPDATED }

    public final Type type;
    public final String tableName;
    /** INSERTED: the RID that was inserted. DELETED: unused. UPDATED: the row's *new* RID after the update. */
    public final RecordId rid;
    /** INSERTED: the values that were inserted. DELETED: the values that were deleted. UPDATED: the row's values *before* the update. */
    public final Object[] values;

    public UndoEntry(Type type, String tableName, RecordId rid, Object[] values) {
        this.type = type;
        this.tableName = tableName;
        this.rid = rid;
        this.values = values;
    }
}
