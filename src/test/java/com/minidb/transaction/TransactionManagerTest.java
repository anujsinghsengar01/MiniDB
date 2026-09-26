package com.minidb.transaction;

import com.minidb.catalog.Catalog;
import com.minidb.executor.ExecutionResult;
import com.minidb.executor.Executor;
import com.minidb.index.IndexManager;
import com.minidb.parser.Parser;
import com.minidb.parser.ast.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TransactionManagerTest {

    private ExecutionResult run(Executor exec, String sql) throws Exception {
        return exec.execute(Parser.parse(sql));
    }

    private Executor newExecutor(Path dir) throws Exception {
        return new Executor(new Catalog(dir.toString()), new IndexManager());
    }

    @Test
    void autoCommitModeUnaffectedByTransactionSupport(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE t (id INT PRIMARY KEY)");
        run(exec, "INSERT INTO t VALUES (1)");
        assertEquals(1, run(exec, "SELECT * FROM t").getResultSet().size());
    }

    @Test
    void rollbackUndoesInsert(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE t (id INT PRIMARY KEY)");
        run(exec, "BEGIN");
        run(exec, "INSERT INTO t VALUES (1)");
        assertEquals(1, run(exec, "SELECT * FROM t").getResultSet().size());
        run(exec, "ROLLBACK");
        assertEquals(0, run(exec, "SELECT * FROM t").getResultSet().size());
    }

    @Test
    void commitPersistsChanges(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE t (id INT PRIMARY KEY)");
        run(exec, "BEGIN");
        run(exec, "INSERT INTO t VALUES (1)");
        run(exec, "COMMIT");
        assertEquals(1, run(exec, "SELECT * FROM t").getResultSet().size());
    }

    @Test
    void rollbackUndoesUpdate(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE t (id INT PRIMARY KEY, val DOUBLE)");
        run(exec, "INSERT INTO t VALUES (1, 3.9)");

        run(exec, "BEGIN");
        run(exec, "UPDATE t SET val = 9.0 WHERE id = 1");
        run(exec, "ROLLBACK");

        ExecutionResult result = run(exec, "SELECT val FROM t WHERE id = 1");
        assertEquals(3.9, (Double) result.getResultSet().getRows().get(0)[0]);
    }

    @Test
    void rollbackUndoesDelete(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE t (id INT PRIMARY KEY)");
        run(exec, "INSERT INTO t VALUES (1)");

        run(exec, "BEGIN");
        run(exec, "DELETE FROM t WHERE id = 1");
        assertEquals(0, run(exec, "SELECT * FROM t").getResultSet().size());
        run(exec, "ROLLBACK");
        assertEquals(1, run(exec, "SELECT * FROM t").getResultSet().size());
    }

    @Test
    void rollbackKeepsIndexConsistentAfterUpdate(@TempDir Path dir) throws Exception {
        Catalog catalog = new Catalog(dir.toString());
        IndexManager indexManager = new IndexManager();
        Executor exec = new Executor(catalog, indexManager);

        run(exec, "CREATE TABLE t (id INT PRIMARY KEY, val DOUBLE)");
        run(exec, "CREATE INDEX idx_id ON t (id)");
        run(exec, "INSERT INTO t VALUES (1, 3.9)");

        run(exec, "BEGIN");
        run(exec, "UPDATE t SET val = 9.0 WHERE id = 1");
        run(exec, "ROLLBACK");

        // A regression test for a real bug found during development: the rolled-back row's
        // OLD index entry must not be left dangling alongside a duplicate restored entry.
        ExecutionResult afterRollback = run(exec, "SELECT * FROM t WHERE id = 1");
        assertEquals(1, afterRollback.getResultSet().size());

        // Would previously throw a false "duplicate primary key" because the index held two
        // stale entries for id=1 after a rolled-back UPDATE.
        assertThrows(com.minidb.executor.ExecutionException.class,
                () -> run(exec, "INSERT INTO t VALUES (1, 5.0)"));
    }

    @Test
    void multiStatementTransactionFullyRolledBack(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE t (id INT PRIMARY KEY, val DOUBLE)");
        run(exec, "INSERT INTO t VALUES (1, 3.9)");

        run(exec, "BEGIN");
        run(exec, "INSERT INTO t VALUES (2, 1.0)");
        run(exec, "INSERT INTO t VALUES (3, 1.0)");
        run(exec, "UPDATE t SET val = 0.0 WHERE id = 1");
        run(exec, "ROLLBACK");

        assertEquals(1, run(exec, "SELECT * FROM t").getResultSet().size());
        assertEquals(3.9, (Double) run(exec, "SELECT val FROM t WHERE id = 1").getResultSet().getRows().get(0)[0]);
    }

    @Test
    void nestedBeginRejected(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE t (id INT PRIMARY KEY)");
        run(exec, "BEGIN");
        assertThrows(TransactionException.class, () -> run(exec, "BEGIN"));
    }

    @Test
    void commitWithNoActiveTransactionRejected(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE t (id INT PRIMARY KEY)");
        assertThrows(TransactionException.class, () -> run(exec, "COMMIT"));
    }

    @Test
    void walFileRecordsTransactionBoundaries(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE t (id INT PRIMARY KEY)");
        run(exec, "BEGIN");
        run(exec, "INSERT INTO t VALUES (1)");
        run(exec, "COMMIT");

        Path walFile = dir.resolve("wal.log");
        assertTrue(Files.exists(walFile));
        String content = Files.readString(walFile);
        assertTrue(content.contains("BEGIN"));
        assertTrue(content.contains("COMMIT"));
    }
}
