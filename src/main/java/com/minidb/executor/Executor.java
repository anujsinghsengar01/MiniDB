package com.minidb.executor;

import com.minidb.catalog.Catalog;
import com.minidb.catalog.CatalogException;
import com.minidb.catalog.Column;
import com.minidb.catalog.TableSchema;
import com.minidb.parser.ast.*;
import com.minidb.storage.HeapFile;
import com.minidb.storage.RecordId;

import java.io.IOException;
import java.util.*;

/**
 * The Query Executor: takes a parsed Statement (AST) and actually runs it
 * against the Catalog and the underlying HeapFiles. This is the layer that
 * finally makes SQL do something — everything below (parser, catalog,
 * storage) exists to support this.
 *
 * One public entry point, execute(), dispatches to a private method per
 * statement type. SELECT is by far the most involved: scan -> filter
 * (WHERE) -> sort (ORDER BY) -> project (column list), with an optional
 * nested-loop JOIN folded into the scan/filter stage.
 */
public class Executor {

    private final Catalog catalog;

    public Executor(Catalog catalog) {
        this.catalog = catalog;
    }

    public ExecutionResult execute(Statement statement) throws IOException {
        try {
            if (statement instanceof CreateTableStatement) return executeCreateTable((CreateTableStatement) statement);
            if (statement instanceof DropTableStatement) return executeDropTable((DropTableStatement) statement);
            if (statement instanceof InsertStatement) return executeInsert((InsertStatement) statement);
            if (statement instanceof SelectStatement) return executeSelect((SelectStatement) statement);
            if (statement instanceof UpdateStatement) return executeUpdate((UpdateStatement) statement);
            if (statement instanceof DeleteStatement) return executeDelete((DeleteStatement) statement);
            throw new ExecutionException("Unsupported statement type: " + statement.getClass().getSimpleName());
        } catch (CatalogException e) {
            // Surface catalog errors (table not found, etc.) as execution errors so callers only catch one type.
            throw new ExecutionException(e.getMessage());
        }
    }

    // ---------- CREATE TABLE / DROP TABLE ----------

    private ExecutionResult executeCreateTable(CreateTableStatement stmt) throws IOException {
        catalog.createTable(stmt.tableName, stmt.columns);
        return ExecutionResult.update(0, "Table '" + stmt.tableName + "' created");
    }

    private ExecutionResult executeDropTable(DropTableStatement stmt) throws IOException {
        catalog.dropTable(stmt.tableName);
        return ExecutionResult.update(0, "Table '" + stmt.tableName + "' dropped");
    }

    // ---------- INSERT ----------

    private ExecutionResult executeInsert(InsertStatement stmt) throws IOException {
        TableSchema schema = catalog.getSchema(stmt.tableName);
        List<Column> schemaColumns = schema.getColumns();
        Object[] rowValues = new Object[schemaColumns.size()];

        if (stmt.columns.isEmpty()) {
            if (stmt.values.size() != schemaColumns.size()) {
                throw new ExecutionException("INSERT provides " + stmt.values.size()
                        + " value(s) but table '" + stmt.tableName + "' has " + schemaColumns.size() + " column(s)");
            }
            for (int i = 0; i < schemaColumns.size(); i++) {
                rowValues[i] = RowSerializer.coerce(stmt.values.get(i).value, schemaColumns.get(i));
            }
        } else {
            boolean[] provided = new boolean[schemaColumns.size()];
            for (int i = 0; i < stmt.columns.size(); i++) {
                int idx = schema.getColumnIndex(stmt.columns.get(i));
                if (idx == -1) {
                    throw new ExecutionException("Column '" + stmt.columns.get(i) + "' does not exist in table '" + stmt.tableName + "'");
                }
                rowValues[idx] = RowSerializer.coerce(stmt.values.get(i).value, schemaColumns.get(idx));
                provided[idx] = true;
            }
            // Any column not explicitly provided is implicitly NULL — enforce its constraint now.
            for (int i = 0; i < schemaColumns.size(); i++) {
                if (!provided[i]) {
                    rowValues[i] = RowSerializer.coerce(null, schemaColumns.get(i));
                }
            }
        }

        HeapFile heapFile = catalog.getHeapFile(stmt.tableName);
        checkPrimaryKeyUnique(schema, heapFile, rowValues, null);

        byte[] encoded = RowSerializer.encode(rowValues, schema);
        heapFile.insert(encoded);

        return ExecutionResult.update(1, "1 row inserted");
    }

    private void checkPrimaryKeyUnique(TableSchema schema, HeapFile heapFile, Object[] newValues, RecordId excludeRid) throws IOException {
        Optional<Column> pk = schema.getPrimaryKeyColumn();
        if (!pk.isPresent()) return;
        int pkIndex = schema.getColumnIndex(pk.get().getName());
        Object pkValue = newValues[pkIndex];

        for (HeapFile.RecordEntry entry : heapFile.scanAll()) {
            if (excludeRid != null && entry.rid.equals(excludeRid)) continue;
            Object[] existing = RowSerializer.decode(entry.data, schema);
            if (Objects.equals(existing[pkIndex], pkValue)) {
                throw new ExecutionException("Duplicate value '" + pkValue + "' for PRIMARY KEY column '" + pk.get().getName() + "'");
            }
        }
    }

    // ---------- SELECT ----------

    /** Tracks which (table, column) each slot of a combined row corresponds to, for name resolution. */
    private static final class ColumnInfo {
        final String tableName;
        final String columnName;
        ColumnInfo(String tableName, String columnName) { this.tableName = tableName; this.columnName = columnName; }
    }

    private ExecutionResult executeSelect(SelectStatement stmt) throws IOException {
        TableSchema mainSchema = catalog.getSchema(stmt.fromTable);
        HeapFile mainHeap = catalog.getHeapFile(stmt.fromTable);

        List<ColumnInfo> combinedColumns = new ArrayList<>();
        for (Column c : mainSchema.getColumns()) combinedColumns.add(new ColumnInfo(stmt.fromTable, c.getName()));

        List<Object[]> candidateRows = new ArrayList<>();

        if (stmt.join == null) {
            for (HeapFile.RecordEntry entry : mainHeap.scanAll()) {
                Object[] values = RowSerializer.decode(entry.data, mainSchema);
                RowContext ctx = new RowContext();
                ctx.addTable(stmt.fromTable, mainSchema, values);
                if (stmt.whereClause == null || ExpressionEvaluator.evaluateBoolean(stmt.whereClause, ctx)) {
                    candidateRows.add(values);
                }
            }
        } else {
            TableSchema joinSchema = catalog.getSchema(stmt.join.joinTable);
            HeapFile joinHeap = catalog.getHeapFile(stmt.join.joinTable);
            for (Column c : joinSchema.getColumns()) combinedColumns.add(new ColumnInfo(stmt.join.joinTable, c.getName()));

            List<HeapFile.RecordEntry> leftEntries = mainHeap.scanAll();
            List<HeapFile.RecordEntry> rightEntries = joinHeap.scanAll();

            // Classic nested-loop join: correct and simple, O(n*m). An index-based join is future work.
            for (HeapFile.RecordEntry leftEntry : leftEntries) {
                Object[] leftValues = RowSerializer.decode(leftEntry.data, mainSchema);
                for (HeapFile.RecordEntry rightEntry : rightEntries) {
                    Object[] rightValues = RowSerializer.decode(rightEntry.data, joinSchema);

                    RowContext ctx = new RowContext();
                    ctx.addTable(stmt.fromTable, mainSchema, leftValues);
                    ctx.addTable(stmt.join.joinTable, joinSchema, rightValues);

                    if (!ExpressionEvaluator.evaluateBoolean(stmt.join.onCondition, ctx)) continue;
                    if (stmt.whereClause != null && !ExpressionEvaluator.evaluateBoolean(stmt.whereClause, ctx)) continue;

                    Object[] combined = new Object[leftValues.length + rightValues.length];
                    System.arraycopy(leftValues, 0, combined, 0, leftValues.length);
                    System.arraycopy(rightValues, 0, combined, leftValues.length, rightValues.length);
                    candidateRows.add(combined);
                }
            }
        }

        if (!stmt.orderBy.isEmpty()) {
            Comparator<Object[]> comparator = null;
            for (OrderByItem item : stmt.orderBy) {
                int idx = resolveColumnIndex(combinedColumns, null, item.columnName);
                Comparator<Object[]> itemComparator = Comparator.comparing(row -> row[idx], Executor::compareForSort);
                if (!item.ascending) itemComparator = itemComparator.reversed();
                comparator = (comparator == null) ? itemComparator : comparator.thenComparing(itemComparator);
            }
            candidateRows.sort(comparator);
        }

        List<String> outputNames = new ArrayList<>();
        List<Object[]> outputRows = new ArrayList<>();
        boolean qualifyNames = stmt.join != null;

        int[] projectionIndices;
        if (stmt.selectStar) {
            projectionIndices = new int[combinedColumns.size()];
            for (int i = 0; i < combinedColumns.size(); i++) {
                projectionIndices[i] = i;
                ColumnInfo ci = combinedColumns.get(i);
                outputNames.add(qualifyNames ? ci.tableName + "." + ci.columnName : ci.columnName);
            }
        } else {
            projectionIndices = new int[stmt.columns.size()];
            for (int i = 0; i < stmt.columns.size(); i++) {
                projectionIndices[i] = resolveColumnIndex(combinedColumns, null, stmt.columns.get(i));
                outputNames.add(stmt.columns.get(i));
            }
        }

        for (Object[] full : candidateRows) {
            Object[] projected = new Object[projectionIndices.length];
            for (int i = 0; i < projectionIndices.length; i++) {
                projected[i] = full[projectionIndices[i]];
            }
            outputRows.add(projected);
        }

        return ExecutionResult.query(new ResultSet(outputNames, outputRows));
    }

    private int resolveColumnIndex(List<ColumnInfo> combinedColumns, String qualifier, String columnName) {
        int found = -1;
        for (int i = 0; i < combinedColumns.size(); i++) {
            ColumnInfo ci = combinedColumns.get(i);
            boolean tableMatches = qualifier == null || ci.tableName.equalsIgnoreCase(qualifier);
            if (tableMatches && ci.columnName.equalsIgnoreCase(columnName)) {
                if (found != -1) {
                    throw new ExecutionException("Column '" + columnName + "' is ambiguous — qualify it with a table name");
                }
                found = i;
            }
        }
        if (found == -1) {
            throw new ExecutionException("Column '" + columnName + "' does not exist");
        }
        return found;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareForSort(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;  // nulls sort last
        if (b == null) return -1;
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        return ((Comparable) a).compareTo(b);
    }

    // ---------- UPDATE ----------

    private ExecutionResult executeUpdate(UpdateStatement stmt) throws IOException {
        TableSchema schema = catalog.getSchema(stmt.tableName);
        HeapFile heapFile = catalog.getHeapFile(stmt.tableName);

        // Materialize matches first so we're not mutating the heap file while scanning it.
        List<HeapFile.RecordEntry> matches = new ArrayList<>();
        for (HeapFile.RecordEntry entry : heapFile.scanAll()) {
            Object[] values = RowSerializer.decode(entry.data, schema);
            RowContext ctx = new RowContext();
            ctx.addTable(stmt.tableName, schema, values);
            if (stmt.whereClause == null || ExpressionEvaluator.evaluateBoolean(stmt.whereClause, ctx)) {
                matches.add(entry);
            }
        }

        int updated = 0;
        for (HeapFile.RecordEntry entry : matches) {
            Object[] newValues = RowSerializer.decode(entry.data, schema);
            for (UpdateStatement.SetClause set : stmt.assignments) {
                int idx = schema.getColumnIndex(set.columnName);
                if (idx == -1) {
                    throw new ExecutionException("Column '" + set.columnName + "' does not exist in table '" + stmt.tableName + "'");
                }
                newValues[idx] = RowSerializer.coerce(set.value.value, schema.getColumns().get(idx));
            }
            checkPrimaryKeyUnique(schema, heapFile, newValues, entry.rid);
            heapFile.update(entry.rid, RowSerializer.encode(newValues, schema));
            updated++;
        }

        return ExecutionResult.update(updated, updated + " row(s) updated");
    }

    // ---------- DELETE ----------

    private ExecutionResult executeDelete(DeleteStatement stmt) throws IOException {
        TableSchema schema = catalog.getSchema(stmt.tableName);
        HeapFile heapFile = catalog.getHeapFile(stmt.tableName);

        List<RecordId> toDelete = new ArrayList<>();
        for (HeapFile.RecordEntry entry : heapFile.scanAll()) {
            Object[] values = RowSerializer.decode(entry.data, schema);
            RowContext ctx = new RowContext();
            ctx.addTable(stmt.tableName, schema, values);
            if (stmt.whereClause == null || ExpressionEvaluator.evaluateBoolean(stmt.whereClause, ctx)) {
                toDelete.add(entry.rid);
            }
        }

        for (RecordId rid : toDelete) {
            heapFile.delete(rid);
        }

        return ExecutionResult.update(toDelete.size(), toDelete.size() + " row(s) deleted");
    }
}
