package com.minidb.parser;

import com.minidb.catalog.ColumnType;
import com.minidb.parser.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    @Test
    void parsesCreateTable() {
        Statement s = Parser.parse("CREATE TABLE students (id INT PRIMARY KEY, name VARCHAR(50), gpa DOUBLE)");
        assertTrue(s instanceof CreateTableStatement);
        CreateTableStatement ct = (CreateTableStatement) s;
        assertEquals("students", ct.tableName);
        assertEquals(3, ct.columns.size());
        assertTrue(ct.columns.get(0).isPrimaryKey());
        assertEquals(ColumnType.VARCHAR, ct.columns.get(1).getType());
        assertEquals(50, ct.columns.get(1).getMaxLength());
    }

    @Test
    void parsesSelectStar() {
        SelectStatement sel = (SelectStatement) Parser.parse("SELECT * FROM students");
        assertTrue(sel.selectStar);
        assertEquals("students", sel.fromTable);
    }

    @Test
    void parsesSelectWithWhereAndAnd() {
        SelectStatement sel = (SelectStatement) Parser.parse(
                "SELECT id, name FROM students WHERE gpa > 3.5 AND id = 10");
        assertEquals(List.of("id", "name"), sel.columns);
        assertNotNull(sel.whereClause);
        Expression.BinaryExpression where = (Expression.BinaryExpression) sel.whereClause;
        assertEquals(Expression.Operator.AND, where.operator);
    }

    @Test
    void parsesJoinAndOrderBy() {
        SelectStatement sel = (SelectStatement) Parser.parse(
                "SELECT * FROM students JOIN courses ON students.id = courses.student_id ORDER BY gpa DESC");
        assertNotNull(sel.join);
        assertEquals("courses", sel.join.joinTable);
        assertEquals(1, sel.orderBy.size());
        assertFalse(sel.orderBy.get(0).ascending);
    }

    @Test
    void parsesInsertWithExplicitColumns() {
        InsertStatement ins = (InsertStatement) Parser.parse(
                "INSERT INTO students (id, name, gpa) VALUES (1, 'Anuj', 3.9)");
        assertEquals(3, ins.values.size());
        assertEquals("Anuj", ins.values.get(1).value);
    }

    @Test
    void parsesPositionalInsert() {
        InsertStatement ins = (InsertStatement) Parser.parse("INSERT INTO students VALUES (2, 'Riya', 3.7)");
        assertTrue(ins.columns.isEmpty());
    }

    @Test
    void parsesUpdate() {
        UpdateStatement upd = (UpdateStatement) Parser.parse(
                "UPDATE students SET gpa = 4.0, name = 'New Name' WHERE id = 1");
        assertEquals(2, upd.assignments.size());
        assertNotNull(upd.whereClause);
    }

    @Test
    void parsesDelete() {
        assertTrue(Parser.parse("DELETE FROM students WHERE id = 1") instanceof DeleteStatement);
    }

    @Test
    void parsesDropTable() {
        assertTrue(Parser.parse("DROP TABLE students") instanceof DropTableStatement);
    }

    @Test
    void toleratesTrailingSemicolon() {
        assertTrue(Parser.parse("SELECT * FROM students;") instanceof SelectStatement);
    }

    @Test
    void malformedSqlThrows() {
        assertThrows(ParseException.class, () -> Parser.parse("SELECT FROM WHERE"));
    }

    @Test
    void handlesEscapedQuoteInStringLiteral() {
        InsertStatement ins = (InsertStatement) Parser.parse("INSERT INTO students VALUES (3, 'O''Brien', 3.2)");
        assertEquals("O'Brien", ins.values.get(1).value);
    }

    @Test
    void parsesNotNullConstraint() {
        CreateTableStatement ct = (CreateTableStatement) Parser.parse(
                "CREATE TABLE t (a INT PRIMARY KEY, b VARCHAR(10) NOT NULL)");
        assertFalse(ct.columns.get(1).isNullable());
    }
}
