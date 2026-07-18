package com.minidb.catalog;

import com.minidb.storage.HeapFile;
import com.minidb.storage.RecordId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CatalogTest {

    private List<Column> studentColumns() {
        return Arrays.asList(
                Column.primaryKey("id", ColumnType.INT),
                Column.varchar("name", 50),
                Column.of("gpa", ColumnType.DOUBLE)
        );
    }

    @Test
    void createTableAndLookupSchema(@TempDir Path dir) throws Exception {
        try (Catalog catalog = new Catalog(dir.toString())) {
            TableSchema schema = catalog.createTable("students", studentColumns());
            assertEquals(3, schema.getColumns().size());
            assertTrue(catalog.tableExists("students"));
            assertTrue(catalog.tableExists("STUDENTS"), "table lookups should be case-insensitive");
        }
    }

    @Test
    void duplicateTableNameThrows(@TempDir Path dir) throws Exception {
        try (Catalog catalog = new Catalog(dir.toString())) {
            catalog.createTable("students", studentColumns());
            assertThrows(CatalogException.class, () -> catalog.createTable("students", studentColumns()));
        }
    }

    @Test
    void columnLookupHelpers(@TempDir Path dir) throws Exception {
        try (Catalog catalog = new Catalog(dir.toString())) {
            TableSchema schema = catalog.createTable("students", studentColumns());
            assertEquals(1, schema.getColumnIndex("name"));
            assertEquals(-1, schema.getColumnIndex("nonexistent"));
            assertTrue(schema.getPrimaryKeyColumn().isPresent());
            assertEquals("id", schema.getPrimaryKeyColumn().get().getName());
        }
    }

    @Test
    void eachTableGetsIndependentDataFile(@TempDir Path dir) throws Exception {
        try (Catalog catalog = new Catalog(dir.toString())) {
            catalog.createTable("students", studentColumns());
            catalog.createTable("courses", Arrays.asList(
                    Column.primaryKey("id", ColumnType.INT), Column.varchar("title", 100)));

            HeapFile students = catalog.getHeapFile("students");
            HeapFile courses = catalog.getHeapFile("courses");
            assertNotSame(students, courses);

            RecordId rid = students.insert("row-in-students".getBytes());
            assertEquals(0, courses.scanAll().size(), "courses table should be unaffected by inserts into students");
            assertEquals("row-in-students", new String(students.read(rid)));
        }
    }

    @Test
    void schemasAndDataSurviveRestart(@TempDir Path dir) throws Exception {
        RecordId rid;
        try (Catalog catalog = new Catalog(dir.toString())) {
            catalog.createTable("students", studentColumns());
            HeapFile hf = catalog.getHeapFile("students");
            rid = hf.insert("persisted-row".getBytes());
        }

        try (Catalog reopened = new Catalog(dir.toString())) {
            assertTrue(reopened.tableExists("students"));
            TableSchema schema = reopened.getSchema("students");
            assertEquals(3, schema.getColumns().size());
            assertTrue(schema.getColumn("id").isPrimaryKey());
            assertEquals(50, schema.getColumn("name").getMaxLength());

            HeapFile hf = reopened.getHeapFile("students");
            assertEquals("persisted-row", new String(hf.read(rid)));
        }
    }

    @Test
    void dropTableRemovesSchemaAndDataFile(@TempDir Path dir) throws Exception {
        try (Catalog catalog = new Catalog(dir.toString())) {
            catalog.createTable("courses", Arrays.asList(
                    Column.primaryKey("id", ColumnType.INT), Column.varchar("title", 100)));
            catalog.dropTable("courses");

            assertFalse(catalog.tableExists("courses"));
            assertThrows(CatalogException.class, () -> catalog.getSchema("courses"));
            assertFalse(dir.resolve("courses.tbl").toFile().exists());
        }
    }
}
