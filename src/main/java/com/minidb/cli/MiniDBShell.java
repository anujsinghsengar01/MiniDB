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
 */
public class MiniDBShell {

    // ANSI escape codes for styling
    private static final String RESET   = "\u001B[0m";
    private static final String CYAN    = "\u001B[36m";
    private static final String YELLOW  = "\u001B[33m";
    private static final String GRAY    = "\u001B[90m";
    private static final String BOLD    = "\u001B[1m";
    private static final String GREEN   = "\u001B[32m";
    private static final String RED     = "\u001B[31m";

    public static void main(String[] args) throws IOException {
        String dataDir = args.length > 0 ? args[0] : "minidb_data";

        printBanner(dataDir);

        try (MiniDBConnection connection = new MiniDBConnection(dataDir)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            StringBuilder buffer = new StringBuilder();

            prompt(buffer);
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
                    prompt(buffer);
                } else {
                    prompt(buffer);
                }
            }
        }

        System.out.println(GRAY + "Goodbye." + RESET);
    }

    private static void printBanner(String dataDir) {
        String art = 
            "   __  ____       _ ____  ____  \n" +
            "  /  |/  (_)___  (_) __ \\/ __ ) \n" +
            " / /|_/ / / __ \\/ / / / / __  | \n" +
            "/ /  / / / / / / / /_/ / /_/ /  \n" +
            "/_/  /_/_/_/ /_/_/_____/_____/   ";

        String divider = " ═══════════════════════════════";

        System.out.println(CYAN + art + RESET);
        System.out.println(YELLOW + divider + RESET);
        System.out.println(BOLD + YELLOW + "  :: RELATIONAL ENGINE v1.0 ::  " + RESET);
        System.out.println(YELLOW + divider + RESET);
        System.out.println(GRAY + "Data Directory : " + RESET + BOLD + dataDir + RESET);
        System.out.println(GRAY + "Commands       : " + GREEN + "help" + GRAY + ", " + GREEN + "tables" + GRAY + ", " + GREEN + "exit" + RESET);
        System.out.println(GRAY + "Query Syntax   : Terminate SQL statements with ';'\n" + RESET);
    }

    private static void prompt(StringBuilder buffer) {
        if (buffer.length() == 0) {
            System.out.print(CYAN + "minidb> " + RESET);
        } else {
            System.out.print(GRAY + "     -> " + RESET);
        }
    }

    private static void runStatement(MiniDBConnection connection, String sql) {
        long start = System.nanoTime();
        try {
            ExecutionResult result = connection.execute(sql);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            if (result.isQuery()) {
                printResultSet(result.getResultSet());
                System.out.println(GRAY + "(" + result.getResultSet().size() + " row(s) in " + elapsedMs + " ms)" + RESET);
            } else {
                System.out.println(result.getMessage() + GRAY + " (" + elapsedMs + " ms)" + RESET);
            }
        } catch (Exception e) {
            System.out.println(RED + "Error: " + e.getMessage() + RESET);
        }
    }

    private static void printResultSet(ResultSet rs) {
        List<String> columns = rs.getColumnNames();
        List<Object[]> rows = rs.getRows();

        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = columns.get(i).length();
        }
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
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static void printTables(MiniDBConnection connection) {
        Set<String> tables = connection.listTables();
        if (tables.isEmpty()) {
            System.out.println(GRAY + "(no tables)" + RESET);
        } else {
            tables.forEach(System.out::println);
        }
    }

    private static void printHelp() {
        System.out.println(BOLD + "MiniDB supported statements:" + RESET);
        System.out.println("  " + GREEN + "CREATE TABLE" + RESET + " name (col TYPE [PRIMARY KEY|NOT NULL], ...)");
        System.out.println("  " + GREEN + "DROP TABLE" + RESET + " name");
        System.out.println("  " + GREEN + "CREATE INDEX" + RESET + " name ON table (column)");
        System.out.println("  " + GREEN + "INSERT INTO" + RESET + " table [(cols)] VALUES (...)");
        System.out.println("  " + GREEN + "SELECT" + RESET + " cols|* FROM table [JOIN t2 ON ...] [WHERE ...] [ORDER BY ... ASC|DESC]");
        System.out.println("  " + GREEN + "UPDATE" + RESET + " table SET col = val, ... [WHERE ...]");
        System.out.println("  " + GREEN + "DELETE FROM" + RESET + " table [WHERE ...]");
        System.out.println("  " + GREEN + "BEGIN" + RESET + " | " + GREEN + "COMMIT" + RESET + " | " + GREEN + "ROLLBACK" + RESET);
        System.out.println(BOLD + "Shell commands:" + RESET + " tables, help, exit / quit");
    }
}