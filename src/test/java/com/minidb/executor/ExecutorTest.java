package com.minidb.executor;

import com.minidb.catalog.Catalog;
import com.minidb.parser.Parser;
import com.minidb.parser.ast.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorTest {

    private ExecutionResult run(Executor exec, String sql) throws Exception {
        Statement stmt = Parser.parse(sql);
        return exec.execute(stmt);
    }

    private Executor newExecutor(Path dir) throws Exception {
        return new Executor(new Catalog(dir.toString()));
    }

    @Test
    void createInsertSelect(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE students (id INT PRIMARY KEY, name VARCHAR(50), gpa DOUBLE)");
        run(exec, "INSERT INTO students VALUES (1, 'Anuj', 3.9)");
        run(exec, "INSERT INTO students VALUES (2, 'Riya', 3.7)");

        ExecutionResult result = run(exec, "SELECT * FROM students");
        assertEquals(2, result.getResultSet().size());
        assertEquals(3, result.getResultSet().getColumnNames().size());
    }

    @Test
    void duplicatePrimaryKeyRejected(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE students (id INT PRIMARY KEY, name VARCHAR(50))");
        run(exec, "INSERT INTO students VALUES (1, 'Anuj')");
        assertThrows(ExecutionException.class, () -> run(exec, "INSERT INTO students VALUES (1, 'Dupe')"));
    }

    @Test
    void notNullConstraintEnforced(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE t (a INT PRIMARY KEY, b VARCHAR(10) NOT NULL)");
        assertThrows(ExecutionException.class, () -> run(exec, "INSERT INTO t (a) VALUES (1)"));
    }

    @Test
    void whereFiltersRows(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE students (id INT PRIMARY KEY, name VARCHAR(50), gpa DOUBLE)");
        run(exec, "INSERT INTO students VALUES (1, 'Anuj', 3.9)");
        run(exec, "INSERT INTO students VALUES (2, 'Riya', 3.2)");

        ExecutionResult result = run(exec, "SELECT name FROM students WHERE gpa > 3.5");
        assertEquals(1, result.getResultSet().size());
        assertEquals("Anuj", result.getResultSet().getRows().get(0)[0]);
    }

    @Test
    void orderByDescSortsCorrectly(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE students (id INT PRIMARY KEY, name VARCHAR(50), gpa DOUBLE)");
        run(exec, "INSERT INTO students VALUES (1, 'Anuj', 3.2)");
        run(exec, "INSERT INTO students VALUES (2, 'Zoe', 4.0)");

        ExecutionResult result = run(exec, "SELECT name FROM students ORDER BY gpa DESC");
        List<Object[]> rows = result.getResultSet().getRows();
        assertEquals("Zoe", rows.get(0)[0]);
        assertEquals("Anuj", rows.get(1)[0]);
    }

    @Test
    void updateChangesValues(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE students (id INT PRIMARY KEY, gpa DOUBLE)");
        run(exec, "INSERT INTO students VALUES (1, 3.0)");

        ExecutionResult update = run(exec, "UPDATE students SET gpa = 4.0 WHERE id = 1");
        assertEquals(1, update.getAffectedRows());

        ExecutionResult check = run(exec, "SELECT gpa FROM students WHERE id = 1");
        assertEquals(4.0, (Double) check.getResultSet().getRows().get(0)[0]);
    }

    @Test
    void deleteRemovesRows(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE students (id INT PRIMARY KEY)");
        run(exec, "INSERT INTO students VALUES (1)");
        run(exec, "INSERT INTO students VALUES (2)");

        ExecutionResult delete = run(exec, "DELETE FROM students WHERE id = 1");
        assertEquals(1, delete.getAffectedRows());
        assertEquals(1, run(exec, "SELECT * FROM students").getResultSet().size());
    }

    @Test
    void joinCombinesMatchingRows(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE students (id INT PRIMARY KEY, name VARCHAR(50))");
        run(exec, "CREATE TABLE courses (id INT PRIMARY KEY, student_id INT, title VARCHAR(50))");
        run(exec, "INSERT INTO students VALUES (1, 'Anuj')");
        run(exec, "INSERT INTO courses VALUES (1, 1, 'Databases')");
        run(exec, "INSERT INTO courses VALUES (2, 1, 'Algorithms')");

        ExecutionResult result = run(exec,
                "SELECT name, title FROM students JOIN courses ON students.id = courses.student_id");
        assertEquals(2, result.getResultSet().size());
    }

    @Test
    void dataAndSchemaSurviveRestart(@TempDir Path dir) throws Exception {
        Executor exec = newExecutor(dir);
        run(exec, "CREATE TABLE students (id INT PRIMARY KEY, name VARCHAR(50))");
        run(exec, "INSERT INTO students VALUES (1, 'Anuj')");

        Executor reopened = newExecutor(dir);
        ExecutionResult result = run(reopened, "SELECT * FROM students");
        assertEquals(1, result.getResultSet().size());
    }
}
