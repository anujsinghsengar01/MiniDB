package com.minidb.parser;

import com.minidb.catalog.Column;
import com.minidb.catalog.ColumnType;
import com.minidb.parser.ast.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written recursive-descent parser. Each grammar rule below has a
 * matching parse method, so the grammar (in the comment above each method)
 * and the code that implements it sit right next to each other -- this is
 * the same style javac's own parser and most textbook parsers use.
 *
 * Supported grammar (simplified EBNF):
 *
 *   statement      := selectStmt | insertStmt | updateStmt | deleteStmt
 *                    | createTableStmt | dropTableStmt
 *
 *   selectStmt     := SELECT selectList FROM IDENT joinClause? whereClause? orderByClause? ';'?
 *   selectList     := '*' | IDENT (',' IDENT)*
 *   joinClause     := JOIN IDENT ON expression
 *   whereClause    := WHERE expression
 *   orderByClause  := ORDER BY orderItem (',' orderItem)*
 *   orderItem      := IDENT (ASC | DESC)?
 *
 *   insertStmt     := INSERT INTO IDENT ('(' IDENT (',' IDENT)* ')')?
 *                      VALUES '(' literal (',' literal)* ')' ';'?
 *
 *   updateStmt     := UPDATE IDENT SET assignment (',' assignment)* whereClause? ';'?
 *   assignment     := IDENT '=' literal
 *
 *   deleteStmt     := DELETE FROM IDENT whereClause? ';'?
 *
 *   createTableStmt:= CREATE TABLE IDENT '(' columnDef (',' columnDef)* ')' ';'?
 *   columnDef      := IDENT typeName ('(' INT_LITERAL ')')? (PRIMARY KEY | NOT NULL)?
 *   typeName       := INT | DOUBLE | VARCHAR | BOOLEAN
 *
 *   dropTableStmt  := DROP TABLE IDENT ';'?
 *
 *   expression     := andExpr (OR andExpr)*
 *   andExpr        := comparison (AND comparison)*
 *   comparison     := operand compareOp operand
 *   compareOp      := '=' | '<>' | '!=' | '<' | '<=' | '>' | '>='
 *   operand        := literal | columnRef
 *   columnRef      := IDENT ('.' IDENT)?
 *   literal        := INT_LITERAL | DOUBLE_LITERAL | STRING_LITERAL | TRUE | FALSE | NULL
 */
public class Parser {

    private final List<Token> tokens;
    private int pos = 0;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** Convenience: tokenize + parse a single SQL statement in one call. */
    public static Statement parse(String sql) {
        List<Token> tokens = new Lexer(sql).tokenize();
        return new Parser(tokens).parseStatement();
    }

    public Statement parseStatement() {
        Token t = peek();
        Statement stmt;
        switch (t.type) {
            case SELECT: stmt = parseSelect(); break;
            case INSERT: stmt = parseInsert(); break;
            case UPDATE: stmt = parseUpdate(); break;
            case DELETE: stmt = parseDelete(); break;
            case CREATE:
                stmt = (peekNext().type == TokenType.INDEX) ? parseCreateIndex() : parseCreateTable();
                break;
            case DROP:   stmt = parseDropTable(); break;
            case BEGIN:
            case COMMIT:
            case ROLLBACK:
                stmt = parseTransactionControl();
                break;
            default:
                throw error("Expected a statement (SELECT/INSERT/UPDATE/DELETE/CREATE TABLE/DROP TABLE)", t);
        }
        match(TokenType.SEMICOLON); // optional trailing ';'
        expect(TokenType.EOF, "end of statement (unexpected extra input)");
        return stmt;
    }

    // ---------- SELECT ----------

    private SelectStatement parseSelect() {
        expect(TokenType.SELECT);

        boolean star = false;
        List<String> columns = new ArrayList<>();
        if (check(TokenType.STAR)) {
            advance();
            star = true;
        } else {
            columns.add(expect(TokenType.IDENTIFIER, "column name").text);
            while (match(TokenType.COMMA)) {
                columns.add(expect(TokenType.IDENTIFIER, "column name").text);
            }
        }

        expect(TokenType.FROM);
        String fromTable = expect(TokenType.IDENTIFIER, "table name").text;

        JoinClause join = null;
        if (check(TokenType.JOIN)) {
            advance();
            String joinTable = expect(TokenType.IDENTIFIER, "table name").text;
            expect(TokenType.ON);
            Expression onCond = parseExpression();
            join = new JoinClause(joinTable, onCond);
        }

        Expression where = null;
        if (check(TokenType.WHERE)) {
            advance();
            where = parseExpression();
        }

        List<OrderByItem> orderBy = new ArrayList<>();
        if (check(TokenType.ORDER)) {
            advance();
            expect(TokenType.BY);
            orderBy.add(parseOrderItem());
            while (match(TokenType.COMMA)) {
                orderBy.add(parseOrderItem());
            }
        }

        return new SelectStatement(star, columns, fromTable, join, where, orderBy);
    }

    private OrderByItem parseOrderItem() {
        String col = expect(TokenType.IDENTIFIER, "column name").text;
        boolean ascending = true;
        if (check(TokenType.ASC)) { advance(); }
        else if (check(TokenType.DESC)) { advance(); ascending = false; }
        return new OrderByItem(col, ascending);
    }

    // ---------- INSERT ----------

    private InsertStatement parseInsert() {
        expect(TokenType.INSERT);
        expect(TokenType.INTO);
        String table = expect(TokenType.IDENTIFIER, "table name").text;

        List<String> columns = new ArrayList<>();
        if (check(TokenType.LPAREN)) {
            advance();
            columns.add(expect(TokenType.IDENTIFIER, "column name").text);
            while (match(TokenType.COMMA)) {
                columns.add(expect(TokenType.IDENTIFIER, "column name").text);
            }
            expect(TokenType.RPAREN);
        }

        expect(TokenType.VALUES);
        expect(TokenType.LPAREN);
        List<Expression.Literal> values = new ArrayList<>();
        values.add(parseLiteral());
        while (match(TokenType.COMMA)) {
            values.add(parseLiteral());
        }
        expect(TokenType.RPAREN);

        if (!columns.isEmpty() && columns.size() != values.size()) {
            throw new ParseException("INSERT column count (" + columns.size()
                    + ") does not match value count (" + values.size() + ")");
        }

        return new InsertStatement(table, columns, values);
    }

    // ---------- UPDATE ----------

    private UpdateStatement parseUpdate() {
        expect(TokenType.UPDATE);
        String table = expect(TokenType.IDENTIFIER, "table name").text;
        expect(TokenType.SET);

        List<UpdateStatement.SetClause> assignments = new ArrayList<>();
        assignments.add(parseAssignment());
        while (match(TokenType.COMMA)) {
            assignments.add(parseAssignment());
        }

        Expression where = null;
        if (check(TokenType.WHERE)) {
            advance();
            where = parseExpression();
        }

        return new UpdateStatement(table, assignments, where);
    }

    private UpdateStatement.SetClause parseAssignment() {
        String col = expect(TokenType.IDENTIFIER, "column name").text;
        expect(TokenType.EQ);
        Expression.Literal value = parseLiteral();
        return new UpdateStatement.SetClause(col, value);
    }

    // ---------- DELETE ----------

    private DeleteStatement parseDelete() {
        expect(TokenType.DELETE);
        expect(TokenType.FROM);
        String table = expect(TokenType.IDENTIFIER, "table name").text;

        Expression where = null;
        if (check(TokenType.WHERE)) {
            advance();
            where = parseExpression();
        }

        return new DeleteStatement(table, where);
    }

    // ---------- CREATE TABLE / DROP TABLE ----------

    private CreateTableStatement parseCreateTable() {
        expect(TokenType.CREATE);
        expect(TokenType.TABLE);
        String table = expect(TokenType.IDENTIFIER, "table name").text;

        expect(TokenType.LPAREN);
        List<Column> columns = new ArrayList<>();
        columns.add(parseColumnDef());
        while (match(TokenType.COMMA)) {
            columns.add(parseColumnDef());
        }
        expect(TokenType.RPAREN);

        return new CreateTableStatement(table, columns);
    }

    private Column parseColumnDef() {
        String name = expect(TokenType.IDENTIFIER, "column name").text;

        ColumnType type;
        int maxLength = 0;
        Token typeToken = advance();
        switch (typeToken.type) {
            case INT: type = ColumnType.INT; break;
            case DOUBLE: type = ColumnType.DOUBLE; break;
            case BOOLEAN: type = ColumnType.BOOLEAN; break;
            case VARCHAR:
                type = ColumnType.VARCHAR;
                expect(TokenType.LPAREN);
                maxLength = Integer.parseInt(expect(TokenType.INT_LITERAL, "VARCHAR length").text);
                expect(TokenType.RPAREN);
                break;
            default:
                throw error("Expected a column type (INT/DOUBLE/VARCHAR/BOOLEAN)", typeToken);
        }

        boolean primaryKey = false;
        boolean nullable = true;
        if (check(TokenType.PRIMARY)) {
            advance();
            expect(TokenType.KEY);
            primaryKey = true;
        } else if (check(TokenType.NOT)) {
            advance();
            expect(TokenType.NULL);
            nullable = false;
        }

        return new Column(name, type, maxLength, primaryKey, nullable);
    }

    private DropTableStatement parseDropTable() {
        expect(TokenType.DROP);
        expect(TokenType.TABLE);
        String table = expect(TokenType.IDENTIFIER, "table name").text;
        return new DropTableStatement(table);
    }

    private CreateIndexStatement parseCreateIndex() {
        expect(TokenType.CREATE);
        expect(TokenType.INDEX);
        String indexName = expect(TokenType.IDENTIFIER, "index name").text;
        expect(TokenType.ON);
        String table = expect(TokenType.IDENTIFIER, "table name").text;
        expect(TokenType.LPAREN);
        String column = expect(TokenType.IDENTIFIER, "column name").text;
        expect(TokenType.RPAREN);
        return new CreateIndexStatement(indexName, table, column);
    }

    private TransactionControlStatement parseTransactionControl() {
        Token t = advance();
        TransactionControlStatement.Kind kind;
        switch (t.type) {
            case BEGIN: kind = TransactionControlStatement.Kind.BEGIN; break;
            case COMMIT: kind = TransactionControlStatement.Kind.COMMIT; break;
            case ROLLBACK: kind = TransactionControlStatement.Kind.ROLLBACK; break;
            default:
                throw error("Expected BEGIN, COMMIT, or ROLLBACK", t);
        }
        match(TokenType.TRANSACTION); // optional trailing keyword, e.g. "COMMIT TRANSACTION"
        return new TransactionControlStatement(kind);
    }

    // ---------- expressions (used in WHERE and JOIN...ON) ----------

    private Expression parseExpression() {
        Expression left = parseAndExpr();
        while (check(TokenType.OR)) {
            advance();
            Expression right = parseAndExpr();
            left = new Expression.BinaryExpression(left, Expression.Operator.OR, right);
        }
        return left;
    }

    private Expression parseAndExpr() {
        Expression left = parseComparison();
        while (check(TokenType.AND)) {
            advance();
            Expression right = parseComparison();
            left = new Expression.BinaryExpression(left, Expression.Operator.AND, right);
        }
        return left;
    }

    private Expression parseComparison() {
        Expression left = parseOperand();
        Expression.Operator op = parseCompareOp();
        Expression right = parseOperand();
        return new Expression.BinaryExpression(left, op, right);
    }

    private Expression.Operator parseCompareOp() {
        Token t = advance();
        switch (t.type) {
            case EQ: return Expression.Operator.EQ;
            case NEQ: return Expression.Operator.NEQ;
            case LT: return Expression.Operator.LT;
            case LTE: return Expression.Operator.LTE;
            case GT: return Expression.Operator.GT;
            case GTE: return Expression.Operator.GTE;
            default:
                throw error("Expected a comparison operator (=, <>, !=, <, <=, >, >=)", t);
        }
    }

    private Expression parseOperand() {
        Token t = peek();
        if (t.type == TokenType.IDENTIFIER) {
            advance();
            String first = t.text;
            if (check(TokenType.DOT)) {
                advance();
                String col = expect(TokenType.IDENTIFIER, "column name").text;
                return new Expression.ColumnReference(first, col);
            }
            return new Expression.ColumnReference(null, first);
        }
        return parseLiteral();
    }

    private Expression.Literal parseLiteral() {
        Token t = advance();
        switch (t.type) {
            case INT_LITERAL: return new Expression.Literal(Integer.parseInt(t.text));
            case DOUBLE_LITERAL: return new Expression.Literal(Double.parseDouble(t.text));
            case STRING_LITERAL: return new Expression.Literal(t.text);
            case TRUE: return new Expression.Literal(Boolean.TRUE);
            case FALSE: return new Expression.Literal(Boolean.FALSE);
            case NULL: return new Expression.Literal(null);
            default:
                throw error("Expected a literal value (number, string, TRUE/FALSE, or NULL)", t);
        }
    }

    // ---------- token stream helpers ----------

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peekNext() {
        return tokens.get(Math.min(pos + 1, tokens.size() - 1));
    }

    private boolean check(TokenType type) {
        return peek().type == type;
    }

    private Token advance() {
        Token t = tokens.get(pos);
        if (pos < tokens.size() - 1) pos++;
        return t;
    }

    private boolean match(TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(TokenType type) {
        return expect(type, type.toString());
    }

    private Token expect(TokenType type, String description) {
        if (check(type)) {
            return advance();
        }
        throw error("Expected " + description, peek());
    }

    private ParseException error(String message, Token found) {
        return new ParseException(message + " but found " + found + " at position " + found.position);
    }
}
