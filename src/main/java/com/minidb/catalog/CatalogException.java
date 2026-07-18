package com.minidb.catalog;

/** Thrown for schema/catalog errors: table already exists, table not found, invalid schema, etc. */
public class CatalogException extends RuntimeException {
    public CatalogException(String message) {
        super(message);
    }

    public CatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
