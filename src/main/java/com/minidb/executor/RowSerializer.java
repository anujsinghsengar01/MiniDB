package com.minidb.executor;

import com.minidb.catalog.Column;
import com.minidb.catalog.ColumnType;
import com.minidb.catalog.TableSchema;

import java.io.*;

/**
 * Converts a row (an Object[] of column values, in schema order) to and
 * from the byte[] that actually gets stored in a HeapFile page.
 *
 * This is the layer that keeps Page/HeapFile completely schema-agnostic
 * (they just move byte[] around) while giving the executor a typed view
 * of each row.
 *
 * Format: for every column, in schema order:
 *   boolean  isNull
 *   <value>          -- omitted entirely if isNull is true
 *
 * where <value> is:
 *   INT      -> int
 *   DOUBLE   -> double
 *   BOOLEAN  -> boolean
 *   VARCHAR  -> UTF string (length-prefixed)
 */
public final class RowSerializer {

    private RowSerializer() {}

    public static byte[] encode(Object[] values, TableSchema schema) {
        if (values.length != schema.getColumns().size()) {
            throw new ExecutionException("Row has " + values.length + " values but table '"
                    + schema.getTableName() + "' has " + schema.getColumns().size() + " columns");
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);

            for (int i = 0; i < values.length; i++) {
                Column col = schema.getColumns().get(i);
                Object value = values[i];

                if (value == null) {
                    out.writeBoolean(true);
                    continue;
                }
                out.writeBoolean(false);

                switch (col.getType()) {
                    case INT:
                        out.writeInt((Integer) value);
                        break;
                    case DOUBLE:
                        out.writeDouble((Double) value);
                        break;
                    case BOOLEAN:
                        out.writeBoolean((Boolean) value);
                        break;
                    case VARCHAR:
                        out.writeUTF((String) value);
                        break;
                    default:
                        throw new ExecutionException("Unsupported column type: " + col.getType());
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Unexpected error encoding row", e);
        }
    }

    public static Object[] decode(byte[] data, TableSchema schema) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            Object[] values = new Object[schema.getColumns().size()];

            for (int i = 0; i < values.length; i++) {
                boolean isNull = in.readBoolean();
                if (isNull) {
                    values[i] = null;
                    continue;
                }
                Column col = schema.getColumns().get(i);
                switch (col.getType()) {
                    case INT:
                        values[i] = in.readInt();
                        break;
                    case DOUBLE:
                        values[i] = in.readDouble();
                        break;
                    case BOOLEAN:
                        values[i] = in.readBoolean();
                        break;
                    case VARCHAR:
                        values[i] = in.readUTF();
                        break;
                    default:
                        throw new ExecutionException("Unsupported column type: " + col.getType());
                }
            }
            return values;
        } catch (IOException e) {
            throw new RuntimeException("Corrupt row record", e);
        }
    }

    /**
     * Coerces a parsed literal (Integer/Double/String/Boolean/null from the parser)
     * into the exact type a column expects — e.g. an INT literal assigned to a
     * DOUBLE column becomes a Double. Also enforces VARCHAR length and
     * NOT NULL / PRIMARY KEY constraints.
     */
    public static Object coerce(Object literalValue, Column column) {
        if (literalValue == null) {
            if (!column.isNullable()) {
                throw new ExecutionException("Column '" + column.getName() + "' cannot be NULL");
            }
            return null;
        }

        switch (column.getType()) {
            case INT:
                if (literalValue instanceof Integer) return literalValue;
                throw new ExecutionException("Expected INT value for column '" + column.getName() + "'");
            case DOUBLE:
                if (literalValue instanceof Double) return literalValue;
                if (literalValue instanceof Integer) return ((Integer) literalValue).doubleValue();
                throw new ExecutionException("Expected DOUBLE value for column '" + column.getName() + "'");
            case BOOLEAN:
                if (literalValue instanceof Boolean) return literalValue;
                throw new ExecutionException("Expected BOOLEAN value for column '" + column.getName() + "'");
            case VARCHAR:
                if (literalValue instanceof String) {
                    String s = (String) literalValue;
                    if (s.length() > column.getMaxLength()) {
                        throw new ExecutionException("Value for column '" + column.getName()
                                + "' exceeds VARCHAR(" + column.getMaxLength() + ")");
                    }
                    return s;
                }
                throw new ExecutionException("Expected VARCHAR value for column '" + column.getName() + "'");
            default:
                throw new ExecutionException("Unsupported column type: " + column.getType());
        }
    }
}
