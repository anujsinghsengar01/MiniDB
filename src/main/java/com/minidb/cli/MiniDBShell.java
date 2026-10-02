package com.minidb.cli;

import com.minidb.api.MiniDBConnection;
import com.minidb.executor.ExecutionResult;
import com.minidb.executor.ResultSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Set;

/**
 * A simple REPL for MiniDB. Reads SQL statements terminated by ';', runs
 * them through MiniDBConnection, and prints the result as a formatted
 * table (for SELECT) or a one-line status message (for everything else).
 * This is intentionally thin — all it does is read a line, forward it to
 * MiniDBConnection, and print what comes back. If a TCP server mode is
 * added later, it would be a second, equally thin front end calling the
 * exact same MiniDBConnection.execute(sql) method.
 */
public class MiniDBShell {

    public static void main(String[] args) throws IOException {
        String dataDir = args.length > 0 ? args[0] : "minidb_data";

        System.out.println("MiniDB — a relational database built from scratch in Java");
        System.out.println("Data directory: " + dataDir);
        System.out.println("Type SQL statements ending in ';'. Type 'help' for commands, 'exit' to quit.\n");

        try (MiniDBConnection connection = new MiniDBConnection(dataDir)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            StringBuilder buffer = new StringBuilder();

            System.out.print("minidb> ");
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();

                if (buffer.length() == 0) {
                    String lower = trimmed.toLowerCase();
                    if (lower.equals("exit") || lower.equals("quit")) {
                        break;
                    }
                    if (lower.equals("help")) {
                        printHelp();
                        prompt(buffer);
                        continue;
                    }
                    if (lower.equals("tables")) {
                        printTables(connection);
                        prompt(buffer);
                        continue;
                    }
                    if (trimmed.isEmpty()) {
                        prompt(buffer);
                        continue;
                    }
                }

                buffer.append(line).append(' ');

                if (trimmed.endsWith(";")) {
                    String sql = buffer.toString().trim();
                    buffer.setLength(0);
                    runStatement(connection, sql);
                    System.out.print("minidb> ");
                } else {
                    System.out.print("     -> ");
                }
            }
        }

        System.out.println("Goodbye.");
    }

    private static void prompt(StringBuilder buffer) {
        System.out.print(buffer.length() == 0 ? "minidb> " : "     -> ");
    }

    private static void runStatement(MiniDBConnection connection, String sql) {
        long start = System.nanoTime();
        try {
            ExecutionResult result = connection.execute(sql);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            if (result.isQuery()) {
                printResultSet(result.getResultSet());
                System.out.println("(" + result.getResultSet().size() + " row(s) in " + elapsedMs + " ms)");
            } else {
                System.out.println(result.getMessage() + " (" + elapsedMs + " ms)");
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private static void printResultSet(ResultSet rs) {
        List<String> columns = rs.getColumnNames();
        List<Object[]> rows = rs.getRows();

        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) widths[i] = columns.get(i).length();
        for (Object[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                String s = row[i] == null ? "NULL" : row[i].toString();
                widths[i] = Math.max(widths[i], s.length());
            }
        }

        printRow(columns.toArray(), widths);
        printSeparator(widths);
        for (Object[] row : rows) {
            printRow(row, widths);
        }
    }

    private static void printRow(Object[] values, int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < values.length; i++) {
            String s = values[i] == null ? "NULL" : values[i].toString();
            sb.append(' ').append(pad(s, widths[i])).append(" |");
        }
        System.out.println(sb);
    }

    private static void printSeparator(int[] widths) {
        StringBuilder sb = new StringBuilder("+");
        for (int w : widths) {
            sb.append("-".repeat(w + 2)).append("+");
        }
        System.out.println(sb);
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private static void printTables(MiniDBConnection connection) {
        Set<String> tables = connection.listTables();
        if (tables.isEmpty()) {
            System.out.println("(no tables)");
        } else {
            tables.forEach(System.out::println);
        }
    }

    private static void printHelp() {
        System.out.println("MiniDB supported statements:");
        System.out.println("  CREATE TABLE name (col TYPE [PRIMARY KEY|NOT NULL], ...)");
        System.out.println("  DROP TABLE name");
        System.out.println("  CREATE INDEX name ON table (column)");
        System.out.println("  INSERT INTO table [(cols)] VALUES (...)");
        System.out.println("  SELECT cols|* FROM table [JOIN t2 ON ...] [WHERE ...] [ORDER BY ... ASC|DESC]");
        System.out.println("  UPDATE table SET col = val, ... [WHERE ...]");
        System.out.println("  DELETE FROM table [WHERE ...]");
        System.out.println("  BEGIN | COMMIT | ROLLBACK");
        System.out.println("Shell commands: tables, help, exit / quit");
    }
}
