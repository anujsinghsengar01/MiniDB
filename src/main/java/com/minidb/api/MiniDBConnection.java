package com.minidb.api;

import com.minidb.catalog.Catalog;
import com.minidb.executor.ExecutionResult;
import com.minidb.executor.Executor;
import com.minidb.index.IndexManager;
import com.minidb.parser.Parser;
import com.minidb.parser.ast.Statement;

import java.io.IOException;
import java.util.Set;

/**
 * The public entry point for embedding MiniDB in a Java program: open a
 * connection to a data directory, call execute(sql) with any supported
 * statement, get back a typed ExecutionResult. This is the one class an
 * external caller — the CLI shell below, or any other Java application —
 * needs to know about. The parser, catalog, executor, index manager, and
 * transaction manager are all wired together here and stay implementation
 * detail behind this one method call — exactly the seam a future TCP
 * server would sit behind instead.
 */
public class MiniDBConnection implements AutoCloseable {

    private final Catalog catalog;
    private final Executor executor;

    public MiniDBConnection(String dataDirectoryPath) throws IOException {
        this.catalog = new Catalog(dataDirectoryPath);
        this.executor = new Executor(catalog, new IndexManager());
    }

    /** Parses and executes a single SQL statement. */
    public ExecutionResult execute(String sql) throws IOException {
        Statement statement = Parser.parse(sql);
        return executor.execute(statement);
    }

    public Set<String> listTables() {
        return catalog.listTableNames();
    }

    @Override
    public void close() throws IOException {
        catalog.close();
    }
}
