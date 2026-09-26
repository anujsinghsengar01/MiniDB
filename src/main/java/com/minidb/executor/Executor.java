package com.minidb.executor;

import com.minidb.catalog.Catalog;
import com.minidb.catalog.CatalogException;
import com.minidb.catalog.Column;
import com.minidb.catalog.TableSchema;
import com.minidb.index.IndexManager;
import com.minidb.parser.ast.*;
import com.minidb.storage.HeapFile;
import com.minidb.storage.RecordId;
import com.minidb.transaction.TransactionManager;

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
 * nested-loop JOIN folded into the scan/filter stage. When an equality
 * WHERE targets an indexed column, the scan is replaced with a B+Tree
 * point lookup via IndexManager instead of reading every page. Every
 * mutation is wrapped by TransactionManager, which logs it to the WAL and
 * (inside an explicit BEGIN) records how to undo it on ROLLBACK.
 */
public class Executor {

    private final Catalog catalog;
    private final IndexManager indexManager;
    private final TransactionManager transactionManager;

    public Executor(Catalog catalog) throws IOException {
        this(catalog, new IndexManager());
    }

    public Executor(Catalog catalog, IndexManager indexManager) throws IOException {
        this(catalog, indexManager, new TransactionManager(catalog.getDataDirectoryPath()));
    }

    public Executor(Catalog catalog, IndexManager indexManager, TransactionManager transactionManager) {
        this.catalog = catalog;
        this.indexManager = indexManager;
        this.transactionManager = transactionManager;
    }

    public ExecutionResult execute(Statement statement) throws IOException {
        try {
            if (statement instanceof CreateTableStatement) return executeCreateTable((CreateTableStatement) statement);
            if (statement instanceof DropTableStatement) return executeDropTable((DropTableStatement) statement);
            if (statement instanceof CreateIndexStatement) return executeCreateIndex((CreateIndexStatement) statement);
            if (statement instanceof TransactionControlStatement) return executeTransactionControl((TransactionControlStatement) statement);
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

    // ---------- CREATE INDEX ----------

    private ExecutionResult executeCreateIndex(CreateIndexStatement stmt) throws IOException {
        TableSchema schema = catalog.getSchema(stmt.tableName);
        HeapFile heapFile = catalog.getHeapFile(stmt.tableName);
        indexManager.createIndex(stmt.indexName, stmt.tableName, stmt.columnName, schema, heapFile);
        return ExecutionResult.update(0, "Index '" + stmt.indexName + "' created on "
                + stmt.tableName + "(" + stmt.columnName + ")");
    }

    // ---------- BEGIN / COMMIT / ROLLBACK ----------

    private ExecutionResult executeTransactionControl(TransactionControlStatement stmt) throws IOException {
        switch (stmt.kind) {
            case BEGIN: {
                int id = transactionManager.begin();
                return ExecutionResult.update(0, "Transaction " + id + " started");
            }
            case COMMIT:
                transactionManager.commit();
                return ExecutionResult.update(0, "Transaction committed");
            case ROLLBACK:
                transactionManager.rollback(catalog, indexManager);
                return ExecutionResult.update(0, "Transaction rolled back");
            default:
                throw new ExecutionException("Unknown transaction control statement: " + stmt.kind);
        }
    }

    // ---------- INSERT ----------

    private ExecutionResult executeInsert(InsertStatement stmt) throws IOException {
        TableSchema schema = catalog.getSchema(stmt.tableName);
        transactionManager.lockTable(stmt.tableName);
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
        RecordId rid = heapFile.insert(encoded);
        indexManager.onInsert(stmt.tableName, schema, rowValues, rid);
        transactionManager.afterInsert(stmt.tableName, rowValues, rid);

        return ExecutionResult.update(1, "1 row inserted");
    }

    private void checkPrimaryKeyUnique(TableSchema schema, HeapFile heapFile, Object[] newValues, RecordId excludeRid) throws IOException {
        Optional<Column> pk = schema.getPrimaryKeyColumn();
        if (!pk.isPresent()) return;
        String pkColumn = pk.get().getName();
        int pkIndex = schema.getColumnIndex(pkColumn);
        Object pkValue = newValues[pkIndex];

        // Fast path: an index on the PK column turns this into an O(log n) lookup instead of a full scan.
        if (indexManager.hasIndex(schema.getTableName(), pkColumn)) {
            for (RecordId rid : indexManager.lookup(schema.getTableName(), pkColumn, pkValue)) {
                if (excludeRid == null || !rid.equals(excludeRid)) {
                    throw new ExecutionException("Duplicate value '" + pkValue + "' for PRIMARY KEY column '" + pkColumn + "'");
                }
            }
            return;
        }

        for (HeapFile.RecordEntry entry : heapFile.scanAll()) {
            if (excludeRid != null && entry.rid.equals(excludeRid)) continue;
            Object[] existing = RowSerializer.decode(entry.data, schema);
            if (Objects.equals(existing[pkIndex], pkValue)) {
                throw new ExecutionException("Duplicate value '" + pkValue + "' for PRIMARY KEY column '" + pkColumn + "'");
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
            List<HeapFile.RecordEntry> entries = tryIndexScan(stmt, mainSchema, mainHeap);
            if (entries == null) {
                entries = mainHeap.scanAll();
            }
            for (HeapFile.RecordEntry entry : entries) {
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

    /**
     * If the WHERE clause is a single equality on an indexed column of the
     * FROM table (no JOIN involved), fetch matching rows via the B+Tree
     * instead of a full scan. Returns null when no such shortcut applies —
     * the caller falls back to a normal scan. The WHERE clause is still
     * re-evaluated afterward either way, so this is purely a performance
     * path, never a correctness shortcut.
     */
    private List<HeapFile.RecordEntry> tryIndexScan(SelectStatement stmt, TableSchema mainSchema, HeapFile mainHeap) throws IOException {
        if (!(stmt.whereClause instanceof Expression.BinaryExpression)) return null;
        Expression.BinaryExpression eq = (Expression.BinaryExpression) stmt.whereClause;
        if (eq.operator != Expression.Operator.EQ) return null;

        Expression.ColumnReference colRef;
        Expression.Literal literal;
        if (eq.left instanceof Expression.ColumnReference && eq.right instanceof Expression.Literal) {
            colRef = (Expression.ColumnReference) eq.left;
            literal = (Expression.Literal) eq.right;
        } else if (eq.right instanceof Expression.ColumnReference && eq.left instanceof Expression.Literal) {
            colRef = (Expression.ColumnReference) eq.right;
            literal = (Expression.Literal) eq.left;
        } else {
            return null;
        }

        if (colRef.tableQualifier != null && !colRef.tableQualifier.equalsIgnoreCase(stmt.fromTable)) return null;
        if (!indexManager.hasIndex(stmt.fromTable, colRef.columnName)) return null;

        Column column = mainSchema.getColumn(colRef.columnName);
        Object key = RowSerializer.coerce(literal.value, column);
        if (key == null) return null; // an index has no entries for NULL values

        List<HeapFile.RecordEntry> results = new ArrayList<>();
        for (RecordId rid : indexManager.lookup(stmt.fromTable, colRef.columnName, key)) {
            byte[] data = mainHeap.read(rid);
            if (data != null) {
                results.add(new HeapFile.RecordEntry(rid, data));
            }
        }
        return results;
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
        transactionManager.lockTable(stmt.tableName);

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
            Object[] oldValues = RowSerializer.decode(entry.data, schema);
            Object[] newValues = oldValues.clone();
            for (UpdateStatement.SetClause set : stmt.assignments) {
                int idx = schema.getColumnIndex(set.columnName);
                if (idx == -1) {
                    throw new ExecutionException("Column '" + set.columnName + "' does not exist in table '" + stmt.tableName + "'");
                }
                newValues[idx] = RowSerializer.coerce(set.value.value, schema.getColumns().get(idx));
            }
            checkPrimaryKeyUnique(schema, heapFile, newValues, entry.rid);

            RecordId newRid = heapFile.update(entry.rid, RowSerializer.encode(newValues, schema));
            indexManager.onDelete(stmt.tableName, schema, oldValues, entry.rid);
            indexManager.onInsert(stmt.tableName, schema, newValues, newRid);
            transactionManager.afterUpdate(stmt.tableName, oldValues, entry.rid, newRid);
            updated++;
        }

        return ExecutionResult.update(updated, updated + " row(s) updated");
    }

    // ---------- DELETE ----------

    private ExecutionResult executeDelete(DeleteStatement stmt) throws IOException {
        TableSchema schema = catalog.getSchema(stmt.tableName);
        HeapFile heapFile = catalog.getHeapFile(stmt.tableName);
        transactionManager.lockTable(stmt.tableName);

        List<HeapFile.RecordEntry> toDelete = new ArrayList<>();
        for (HeapFile.RecordEntry entry : heapFile.scanAll()) {
            Object[] values = RowSerializer.decode(entry.data, schema);
            RowContext ctx = new RowContext();
            ctx.addTable(stmt.tableName, schema, values);
            if (stmt.whereClause == null || ExpressionEvaluator.evaluateBoolean(stmt.whereClause, ctx)) {
                toDelete.add(entry);
            }
        }

        for (HeapFile.RecordEntry entry : toDelete) {
            Object[] values = RowSerializer.decode(entry.data, schema);
            heapFile.delete(entry.rid);
            indexManager.onDelete(stmt.tableName, schema, values, entry.rid);
            transactionManager.afterDelete(stmt.tableName, values, entry.rid);
        }

        return ExecutionResult.update(toDelete.size(), toDelete.size() + " row(s) deleted");
    }
}
