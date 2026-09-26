package com.minidb.transaction;

import com.minidb.catalog.Catalog;
import com.minidb.catalog.TableSchema;
import com.minidb.executor.RowSerializer;
import com.minidb.index.IndexManager;
import com.minidb.storage.HeapFile;
import com.minidb.storage.RecordId;

import java.io.IOException;

/**
 * Coordinates transactions: BEGIN/COMMIT/ROLLBACK, the WAL, and table
 * locking. Only one transaction is active at a time (a single-connection
 * mini-db has no need for a real scheduler), but the shape of the API —
 * begin/lock/log/commit/rollback — is exactly what a concurrent version
 * would need, just with the "only one at a time" restriction lifted.
 *
 * Two modes, mirroring real databases:
 *   - Explicit transaction: BEGIN ... (statements) ... COMMIT/ROLLBACK.
 *     Every mutation's undo information is remembered; ROLLBACK replays
 *     it in reverse.
 *   - Auto-commit: no BEGIN was issued, so every single statement is
 *     wrapped in its own implicit transaction (logged BEGIN, the
 *     statement's effect, then immediately COMMIT) — the default mode
 *     every SQL database starts a connection in.
 *
 * The Executor performs the actual mutation (HeapFile + index updates)
 * itself; this class only logs what happened and remembers how to
 * reverse it — it never re-derives or re-applies the forward action.
 */
public class TransactionManager implements AutoCloseable {

    private final WriteAheadLog wal;
    private final LockManager lockManager = new LockManager();
    private Transaction active;
    private int nextTxnId = 1;

    public TransactionManager(String dataDirectoryPath) throws IOException {
        this.wal = new WriteAheadLog(dataDirectoryPath + "/wal.log");
    }

    public boolean hasActiveTransaction() {
        return active != null;
    }

    public int begin() throws IOException {
        if (active != null) {
            throw new TransactionException("A transaction is already active — nested transactions are not supported");
        }
        active = new Transaction(nextTxnId++);
        wal.logBegin(active.id);
        return active.id;
    }

    public void commit() throws IOException {
        requireActive();
        wal.logCommit(active.id);
        lockManager.releaseAll(active.id);
        active = null;
    }

    public void rollback(Catalog catalog, IndexManager indexManager) throws IOException {
        requireActive();
        for (int i = active.undoLog.size() - 1; i >= 0; i--) {
            undo(active.undoLog.get(i), catalog, indexManager);
        }
        wal.logRollback(active.id);
        lockManager.releaseAll(active.id);
        active = null;
    }

    private void requireActive() {
        if (active == null) {
            throw new TransactionException("No active transaction — start one with BEGIN first");
        }
    }

    /** Acquires a table-level write lock for the current statement. A no-op in auto-commit mode. */
    public void lockTable(String table) {
        if (active != null) {
            lockManager.acquire(active.id, table);
        }
    }

    // ---------- hooks the Executor calls right after each mutation is actually applied ----------

    public void afterInsert(String table, Object[] values, RecordId rid) throws IOException {
        int txnId = currentOrAutoCommitBegin();
        wal.logInsert(txnId, table, rid);
        if (active != null) {
            active.undoLog.add(new UndoEntry(UndoEntry.Type.INSERTED, table, rid, values));
        } else {
            wal.logCommit(txnId);
        }
    }

    public void afterDelete(String table, Object[] oldValues, RecordId rid) throws IOException {
        int txnId = currentOrAutoCommitBegin();
        wal.logDelete(txnId, table, rid);
        if (active != null) {
            active.undoLog.add(new UndoEntry(UndoEntry.Type.DELETED, table, rid, oldValues));
        } else {
            wal.logCommit(txnId);
        }
    }

    public void afterUpdate(String table, Object[] oldValues, RecordId oldRid, RecordId newRid) throws IOException {
        int txnId = currentOrAutoCommitBegin();
        wal.logUpdate(txnId, table, oldRid, newRid);
        if (active != null) {
            active.undoLog.add(new UndoEntry(UndoEntry.Type.UPDATED, table, newRid, oldValues));
        } else {
            wal.logCommit(txnId);
        }
    }

    private int currentOrAutoCommitBegin() throws IOException {
        if (active != null) return active.id;
        int txnId = nextTxnId++;
        wal.logBegin(txnId);
        return txnId;
    }

    private void undo(UndoEntry entry, Catalog catalog, IndexManager indexManager) throws IOException {
        TableSchema schema = catalog.getSchema(entry.tableName);
        HeapFile heapFile = catalog.getHeapFile(entry.tableName);

        switch (entry.type) {
            case INSERTED:
                // Undo an insert: delete the row it created.
                heapFile.delete(entry.rid);
                indexManager.onDelete(entry.tableName, schema, entry.values, entry.rid);
                break;
            case DELETED: {
                // Undo a delete: reinsert it. It will land at a new physical RID -- fine,
                // since rollback happens within the same live session, before anything
                // outside this transaction could have captured the old RID.
                RecordId newRid = heapFile.insert(RowSerializer.encode(entry.values, schema));
                indexManager.onInsert(entry.tableName, schema, entry.values, newRid);
                break;
            }
            case UPDATED: {
                // Undo an update: first remove the index entries for the *current* (post-update)
                // row -- read them fresh rather than trusting entry.values, since entry.values
                // holds the *old* row and an indexed column may have changed value in the update.
                byte[] currentBytes = heapFile.read(entry.rid);
                if (currentBytes != null) {
                    Object[] currentValues = RowSerializer.decode(currentBytes, schema);
                    indexManager.onDelete(entry.tableName, schema, currentValues, entry.rid);
                }
                heapFile.delete(entry.rid); // entry.rid is the row's *new* RID for an UPDATED entry
                RecordId restoredRid = heapFile.insert(RowSerializer.encode(entry.values, schema)); // entry.values is the *old* row
                indexManager.onInsert(entry.tableName, schema, entry.values, restoredRid);
                break;
            }
        }
    }

    @Override
    public void close() throws IOException {
        wal.close();
    }
}
