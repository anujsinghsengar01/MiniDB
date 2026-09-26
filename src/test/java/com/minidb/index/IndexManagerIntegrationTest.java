package com.minidb.index;

import com.minidb.catalog.Catalog;
import com.minidb.executor.ExecutionException;
import com.minidb.executor.ExecutionResult;
import com.minidb.executor.Executor;
import com.minidb.parser.Parser;
import com.minidb.parser.ast.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IndexManagerIntegrationTest {

    private ExecutionResult run(Executor exec, String sql) throws Exception {
        Statement stmt = Parser.parse(sql);
        return exec.execute(stmt);
    }

    @Test
    void createIndexBackfillsFromExistingRows(@TempDir Path dir) throws Exception {
        Catalog catalog = new Catalog(dir.toString());
        IndexManager indexManager = new IndexManager();
        Executor exec = new Executor(catalog, indexManager);

        run(exec, "CREATE TABLE students (id INT PRIMARY KEY, name VARCHAR(50))");
        run(exec, "INSERT INTO students VALUES (1, 'Anuj')");
        run(exec, "INSERT INTO students VALUES (2, 'Riya')");

        run(exec, "CREATE INDEX idx_id ON students (id)");
        assertTrue(indexManager.hasIndex("students", "id"));

        ExecutionResult result = run(exec, "SELECT name FROM students WHERE id = 2");
        assertEquals(1, result.getResultSet().size());
        assertEquals("Riya", result.getResultSet().getRows().get(0)[0]);
    }

    @Test
    void indexStaysCurrentAfterInsertUpdateDelete(@TempDir Path dir) throws Exception {
        Catalog catalog = new Catalog(dir.toString());
        IndexManager indexManager = new IndexManager();
        Executor exec = new Executor(catalog, indexManager);

        run(exec, "CREATE TABLE students (id INT PRIMARY KEY, name VARCHAR(50))");
        run(exec, "CREATE INDEX idx_id ON students (id)");

        run(exec, "INSERT INTO students VALUES (1, 'Anuj')");
        assertEquals(1, run(exec, "SELECT * FROM students WHERE id = 1").getResultSet().size());

        run(exec, "UPDATE students SET id = 99 WHERE id = 1");
        assertEquals(0, run(exec, "SELECT * FROM students WHERE id = 1").getResultSet().size());
        assertEquals(1, run(exec, "SELECT * FROM students WHERE id = 99").getResultSet().size());

        run(exec, "DELETE FROM students WHERE id = 99");
        assertEquals(0, run(exec, "SELECT * FROM students WHERE id = 99").getResultSet().size());
    }

    @Test
    void primaryKeyUniquenessEnforcedViaIndexFastPath(@TempDir Path dir) throws Exception {
        Catalog catalog = new Catalog(dir.toString());
        IndexManager indexManager = new IndexManager();
        Executor exec = new Executor(catalog, indexManager);

        run(exec, "CREATE TABLE students (id INT PRIMARY KEY, name VARCHAR(50))");
        run(exec, "CREATE INDEX idx_id ON students (id)");
        run(exec, "INSERT INTO students VALUES (1, 'Anuj')");

        assertThrows(ExecutionException.class, () -> run(exec, "INSERT INTO students VALUES (1, 'Dupe')"));
    }

    @Test
    void duplicateIndexOnSameColumnRejected(@TempDir Path dir) throws Exception {
        Catalog catalog = new Catalog(dir.toString());
        IndexManager indexManager = new IndexManager();
        Executor exec = new Executor(catalog, indexManager);

        run(exec, "CREATE TABLE students (id INT PRIMARY KEY, name VARCHAR(50))");
        run(exec, "CREATE INDEX idx_id ON students (id)");

        assertThrows(IndexException.class, () -> run(exec, "CREATE INDEX idx_id_2 ON students (id)"));
    }
}
