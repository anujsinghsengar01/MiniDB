package com.minidb.executor;

import com.minidb.catalog.TableSchema;
import com.minidb.parser.ast.Expression;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves a (possibly table-qualified) column reference to a value for
 * the "current row" being evaluated. Holds one entry per table involved —
 * one for a plain SELECT, two when a JOIN is in play — so WHERE/ON
 * expressions can be evaluated identically in both cases.
 */
public final class RowContext {

    private final Map<String, TableSchema> schemasByTable = new LinkedHashMap<>();
    private final Map<String, Object[]> valuesByTable = new LinkedHashMap<>();

    public void addTable(String tableName, TableSchema schema, Object[] values) {
        schemasByTable.put(tableName.toLowerCase(), schema);
        valuesByTable.put(tableName.toLowerCase(), values);
    }

    public Object resolve(Expression.ColumnReference ref) {
        if (ref.tableQualifier != null) {
            String key = ref.tableQualifier.toLowerCase();
            TableSchema schema = schemasByTable.get(key);
            if (schema == null) {
                throw new ExecutionException("Unknown table qualifier '" + ref.tableQualifier + "'");
            }
            return valueFor(schema, valuesByTable.get(key), ref.columnName);
        }

        // Unqualified: search every table in scope, error on ambiguity.
        Object found = null;
        boolean seen = false;
        for (Map.Entry<String, TableSchema> entry : schemasByTable.entrySet()) {
            TableSchema schema = entry.getValue();
            if (schema.getColumnIndex(ref.columnName) != -1) {
                if (seen) {
                    throw new ExecutionException("Column '" + ref.columnName + "' is ambiguous — qualify it with a table name");
                }
                found = valueFor(schema, valuesByTable.get(entry.getKey()), ref.columnName);
                seen = true;
            }
        }
        if (!seen) {
            throw new ExecutionException("Column '" + ref.columnName + "' does not exist");
        }
        return found;
    }

    private Object valueFor(TableSchema schema, Object[] values, String columnName) {
        int idx = schema.getColumnIndex(columnName);
        if (idx == -1) {
            throw new ExecutionException("Column '" + columnName + "' does not exist in table '" + schema.getTableName() + "'");
        }
        return values[idx];
    }
}
