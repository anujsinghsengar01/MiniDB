package com.minidb.index;

import com.minidb.catalog.TableSchema;
import com.minidb.executor.RowSerializer;
import com.minidb.storage.HeapFile;
import com.minidb.storage.RecordId;

import java.io.IOException;
import java.util.*;

/**
 * Owns every B+Tree index in the database, keyed by "table.column".
 * Responsible for:
 *   - CREATE INDEX: build a tree from a full table scan
 *   - keeping every index on a table in sync as the Executor performs
 *     INSERT/UPDATE/DELETE (the Executor calls onInsert/onDelete;
 *     HeapFile itself stays completely unaware indexes exist)
 *   - answering "is there an index I can use for this lookup" for the
 *     Executor's SELECT and PRIMARY KEY–uniqueness fast paths
 */
public class IndexManager {

    @SuppressWarnings("rawtypes")
    private final Map<String, BPlusTree> indexes = new LinkedHashMap<>();
    private final Map<String, String> indexNameToKey = new LinkedHashMap<>();
    private final Map<String, List<String>> indexedColumnsByTable = new LinkedHashMap<>();

    private static String key(String table, String column) {
        return table.toLowerCase() + "." + column.toLowerCase();
    }

    @SuppressWarnings("unchecked")
    public void createIndex(String indexName, String tableName, String columnName,
                             TableSchema schema, HeapFile heapFile) throws IOException {
        String key = key(tableName, columnName);
        if (indexes.containsKey(key)) {
            throw new IndexException("An index already exists on " + tableName + "(" + columnName + ")");
        }
        if (indexNameToKey.containsKey(indexName.toLowerCase())) {
            throw new IndexException("Index name '" + indexName + "' is already in use");
        }
        int colIdx = schema.getColumnIndex(columnName);
        if (colIdx == -1) {
            throw new IndexException("Column '" + columnName + "' does not exist in table '" + tableName + "'");
        }

        BPlusTree tree = new BPlusTree();
        for (HeapFile.RecordEntry entry : heapFile.scanAll()) {
            Object[] values = RowSerializer.decode(entry.data, schema);
            Object value = values[colIdx];
            if (value != null) {
                tree.insert((Comparable) value, entry.rid);
            }
        }

        indexes.put(key, tree);
        indexNameToKey.put(indexName.toLowerCase(), key);
        indexedColumnsByTable.computeIfAbsent(tableName.toLowerCase(), k -> new ArrayList<>()).add(columnName.toLowerCase());
    }

    public boolean hasIndex(String tableName, String columnName) {
        return indexes.containsKey(key(tableName, columnName));
    }

    public List<String> getIndexedColumns(String tableName) {
        return indexedColumnsByTable.getOrDefault(tableName.toLowerCase(), Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    public List<RecordId> lookup(String tableName, String columnName, Object value) {
        BPlusTree tree = indexes.get(key(tableName, columnName));
        if (tree == null) return Collections.emptyList();
        return tree.search((Comparable) value);
    }

    /** Called by the Executor after a row is inserted, so every index on this table stays current. */
    @SuppressWarnings("unchecked")
    public void onInsert(String tableName, TableSchema schema, Object[] values, RecordId rid) {
        for (String col : getIndexedColumns(tableName)) {
            int idx = schema.getColumnIndex(col);
            Object value = values[idx];
            if (value != null) {
                indexes.get(key(tableName, col)).insert((Comparable) value, rid);
            }
        }
    }

    /** Called by the Executor after a row is deleted (or before it's re-inserted during an UPDATE). */
    @SuppressWarnings("unchecked")
    public void onDelete(String tableName, TableSchema schema, Object[] values, RecordId rid) {
        for (String col : getIndexedColumns(tableName)) {
            int idx = schema.getColumnIndex(col);
            Object value = values[idx];
            if (value != null) {
                indexes.get(key(tableName, col)).delete((Comparable) value, rid);
            }
        }
    }
}
