package com.minidb.catalog;

import com.minidb.storage.HeapFile;
import com.minidb.storage.PageManager;
import com.minidb.storage.RecordId;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The Catalog is MiniDB's source of truth for "what tables exist and what
 * do they look like." It owns:
 *
 *   1. The system catalog itself — a HeapFile ("catalog.sys") holding one
 *      serialized TableSchema record per table. This means table
 *      definitions are durable across restarts using the exact same
 *      storage engine as user data (no separate "schema file format"
 *      to design).
 *   2. A per-table data file — each table gets its own on-disk HeapFile
 *      ("<tableName>.tbl") that the executor will read/write rows through.
 *
 * All directory/file bookkeeping (open PageManagers, catalog RIDs) lives
 * here so the executor and parser never need to know about file paths.
 */
public class Catalog implements AutoCloseable {

    private static final String CATALOG_FILE_NAME = "catalog.sys";

    private final File dataDirectory;
    private final PageManager catalogPageManager;
    private final HeapFile catalogHeapFile;

    // In-memory cache, rebuilt from catalogHeapFile on startup.
    private final Map<String, TableSchema> schemas = new LinkedHashMap<>();
    private final Map<String, RecordId> schemaRecordIds = new LinkedHashMap<>();

    // Lazily-opened data files for each table, keyed by lowercase table name.
    private final Map<String, PageManager> tablePageManagers = new LinkedHashMap<>();
    private final Map<String, HeapFile> tableHeapFiles = new LinkedHashMap<>();

    public Catalog(String dataDirectoryPath) throws IOException {
        this.dataDirectory = new File(dataDirectoryPath);
        if (!dataDirectory.exists() && !dataDirectory.mkdirs()) {
            throw new IOException("Could not create data directory: " + dataDirectoryPath);
        }

        File catalogFile = new File(dataDirectory, CATALOG_FILE_NAME);
        this.catalogPageManager = new PageManager(catalogFile.getPath());
        this.catalogHeapFile = new HeapFile(catalogPageManager);

        loadExistingSchemas();
    }

    /** Reads every schema record out of catalog.sys on startup and rebuilds the in-memory cache. */
    private void loadExistingSchemas() {
        Iterator<HeapFile.RecordEntry> it = catalogHeapFile.scan();
        while (it.hasNext()) {
            HeapFile.RecordEntry entry = it.next();
            TableSchema schema = TableSchema.deserialize(entry.data);
            String key = key(schema.getTableName());
            schemas.put(key, schema);
            schemaRecordIds.put(key, entry.rid);
        }
    }

    private String key(String tableName) {
        return tableName.toLowerCase();
    }

    // ---------- DDL ----------

    /** Handles CREATE TABLE. Persists the schema and allocates the table's data file. */
    public synchronized TableSchema createTable(String tableName, java.util.List<Column> columns) throws IOException {
        String key = key(tableName);
        if (schemas.containsKey(key)) {
            throw new CatalogException("Table '" + tableName + "' already exists");
        }

        TableSchema schema = new TableSchema(tableName, columns);
        RecordId rid = catalogHeapFile.insert(schema.serialize());

        schemas.put(key, schema);
        schemaRecordIds.put(key, rid);

        // Eagerly create the (empty) data file so it exists on disk right away.
        openTableHeapFile(key);

        return schema;
    }

    /** Handles DROP TABLE. Removes the schema and deletes the table's data file. */
    public synchronized void dropTable(String tableName) throws IOException {
        String key = key(tableName);
        if (!schemas.containsKey(key)) {
            throw new CatalogException("Table '" + tableName + "' does not exist");
        }

        catalogHeapFile.delete(schemaRecordIds.get(key));
        schemas.remove(key);
        schemaRecordIds.remove(key);

        PageManager pm = tablePageManagers.remove(key);
        tableHeapFiles.remove(key);
        if (pm != null) {
            pm.close();
        }
        File dataFile = new File(dataDirectory, key + ".tbl");
        if (dataFile.exists() && !dataFile.delete()) {
            throw new IOException("Failed to delete data file for table '" + tableName + "'");
        }
    }

    // ---------- lookups ----------

    public synchronized TableSchema getSchema(String tableName) {
        TableSchema schema = schemas.get(key(tableName));
        if (schema == null) {
            throw new CatalogException("Table '" + tableName + "' does not exist");
        }
        return schema;
    }

    public synchronized boolean tableExists(String tableName) {
        return schemas.containsKey(key(tableName));
    }

    public synchronized Set<String> listTableNames() {
        // Return the original-case names as stored in each schema, not the lowercase keys.
        Set<String> names = new java.util.LinkedHashSet<>();
        for (TableSchema schema : schemas.values()) {
            names.add(schema.getTableName());
        }
        return names;
    }

    /** The directory this catalog and all its tables' data files live in — used to place the WAL alongside them. */
    public String getDataDirectoryPath() {
        return dataDirectory.getPath();
    }

    /** Returns the HeapFile backing a table's row data, opening its data file on first access. */
    public synchronized HeapFile getHeapFile(String tableName) throws IOException {
        String key = key(tableName);
        if (!schemas.containsKey(key)) {
            throw new CatalogException("Table '" + tableName + "' does not exist");
        }
        return openTableHeapFile(key);
    }

    private HeapFile openTableHeapFile(String key) throws IOException {
        HeapFile existing = tableHeapFiles.get(key);
        if (existing != null) {
            return existing;
        }
        File dataFile = new File(dataDirectory, key + ".tbl");
        PageManager pm = new PageManager(dataFile.getPath());
        HeapFile hf = new HeapFile(pm);
        tablePageManagers.put(key, pm);
        tableHeapFiles.put(key, hf);
        return hf;
    }

    @Override
    public synchronized void close() throws IOException {
        for (PageManager pm : tablePageManagers.values()) {
            pm.close();
        }
        catalogPageManager.close();
    }
}
