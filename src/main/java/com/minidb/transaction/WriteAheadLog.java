package com.minidb.transaction;

import com.minidb.storage.RecordId;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * An append-only Write-Ahead Log: every mutation is recorded here, and
 * every write is fsync'd immediately — the same "log the intent, then
 * fsync, before you rely on it" rule real WALs (Postgres's pg_wal,
 * InnoDB's redo log) follow.
 *
 * Deliberately plain text (pipe-delimited), not a binary format — a real
 * database wouldn't do this for performance reasons, but a human-readable
 * log is easy to open and inspect while explaining how the system works,
 * which matters more here than raw throughput.
 *
 * Scope note: this log gives you a durable, ordered audit trail and is
 * what ROLLBACK's in-memory undo log is cross-checked against
 * conceptually, but MiniDB does not yet replay this log to REDO
 * committed-but-unflushed work after a crash — only explicit, in-session
 * ROLLBACK is implemented. Crash recovery (scanning the WAL on startup
 * and redoing/undoing accordingly) is the natural next step, and would
 * read exactly this file.
 */
public class WriteAheadLog implements AutoCloseable {

    private final RandomAccessFile file;

    public WriteAheadLog(String path) throws IOException {
        this.file = new RandomAccessFile(path, "rw");
        this.file.seek(file.length()); // always append
    }

    private synchronized void writeLine(String line) throws IOException {
        file.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        file.getFD().sync(); // durability: this record is safe on disk before the caller proceeds
    }

    public void logBegin(int txnId) throws IOException {
        writeLine(txnId + "|BEGIN");
    }

    public void logCommit(int txnId) throws IOException {
        writeLine(txnId + "|COMMIT");
    }

    public void logRollback(int txnId) throws IOException {
        writeLine(txnId + "|ROLLBACK");
    }

    public void logInsert(int txnId, String table, RecordId rid) throws IOException {
        writeLine(txnId + "|INSERT|" + table + "|" + ridText(rid));
    }

    public void logDelete(int txnId, String table, RecordId rid) throws IOException {
        writeLine(txnId + "|DELETE|" + table + "|" + ridText(rid));
    }

    public void logUpdate(int txnId, String table, RecordId oldRid, RecordId newRid) throws IOException {
        writeLine(txnId + "|UPDATE|" + table + "|" + ridText(oldRid) + "->" + ridText(newRid));
    }

    private String ridText(RecordId rid) {
        return rid.getPageId() + "," + rid.getSlotIndex();
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
