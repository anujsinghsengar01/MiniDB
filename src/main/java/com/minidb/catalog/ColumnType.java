package com.minidb.catalog;

/**
 * The set of column data types MiniDB supports. Kept deliberately small —
 * this covers the vast majority of real schemas without the complexity of
 * a full SQL type system (no DECIMAL precision/scale, no DATE/TIMESTAMP
 * yet, etc). Easy to extend later since every type here maps to a fixed
 * or simply-encoded byte representation (see Column.encode/decode in the
 * executor's row (de)serializer, built in a later phase).
 */
public enum ColumnType {
    /** 4-byte signed integer. */
    INT,
    /** 8-byte double-precision float. */
    DOUBLE,
    /** Variable-length UTF-8 text, up to Column.maxLength characters. */
    VARCHAR,
    /** Single byte, 0 or 1. */
    BOOLEAN
}
