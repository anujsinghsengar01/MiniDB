package com.minidb.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a raw SQL string into a List<Token>. A hand-rolled lexer rather than
 * a generated one (ANTLR etc) — for a project this size it's a few hundred
 * lines and it means there's no build-time code generation step to explain
 * away in an interview; every token rule is visible right here.
 */
public class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("SELECT", TokenType.SELECT),
            Map.entry("FROM", TokenType.FROM),
            Map.entry("WHERE", TokenType.WHERE),
            Map.entry("INSERT", TokenType.INSERT),
            Map.entry("INTO", TokenType.INTO),
            Map.entry("VALUES", TokenType.VALUES),
            Map.entry("UPDATE", TokenType.UPDATE),
            Map.entry("SET", TokenType.SET),
            Map.entry("DELETE", TokenType.DELETE),
            Map.entry("CREATE", TokenType.CREATE),
            Map.entry("TABLE", TokenType.TABLE),
            Map.entry("DROP", TokenType.DROP),
            Map.entry("PRIMARY", TokenType.PRIMARY),
            Map.entry("KEY", TokenType.KEY),
            Map.entry("NOT", TokenType.NOT),
            Map.entry("NULL", TokenType.NULL),
            Map.entry("AND", TokenType.AND),
            Map.entry("OR", TokenType.OR),
            Map.entry("JOIN", TokenType.JOIN),
            Map.entry("ON", TokenType.ON),
            Map.entry("ORDER", TokenType.ORDER),
            Map.entry("BY", TokenType.BY),
            Map.entry("ASC", TokenType.ASC),
            Map.entry("DESC", TokenType.DESC),
            Map.entry("TRUE", TokenType.TRUE),
            Map.entry("FALSE", TokenType.FALSE),
            Map.entry("INT", TokenType.INT),
            Map.entry("VARCHAR", TokenType.VARCHAR),
            Map.entry("DOUBLE", TokenType.DOUBLE),
            Map.entry("BOOLEAN", TokenType.BOOLEAN)
    );

    private final String input;
    private int pos = 0;

    public Lexer(String input) {
        this.input = input;
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        Token t;
        do {
            t = nextToken();
            tokens.add(t);
        } while (t.type != TokenType.EOF);
        return tokens;
    }

    private Token nextToken() {
        skipWhitespace();
        if (pos >= input.length()) {
            return new Token(TokenType.EOF, "", pos);
        }

        int start = pos;
        char c = input.charAt(pos);

        // string literal: 'text with '' escaped quotes'
        if (c == '\'') {
            return readStringLiteral();
        }

        // number: 123 or 123.45
        if (Character.isDigit(c)) {
            return readNumber();
        }

        // identifier or keyword
        if (Character.isLetter(c) || c == '_') {
            return readIdentifierOrKeyword();
        }

        // symbols / operators
        switch (c) {
            case '*': pos++; return new Token(TokenType.STAR, "*", start);
            case ',': pos++; return new Token(TokenType.COMMA, ",", start);
            case '.': pos++; return new Token(TokenType.DOT, ".", start);
            case '(': pos++; return new Token(TokenType.LPAREN, "(", start);
            case ')': pos++; return new Token(TokenType.RPAREN, ")", start);
            case ';': pos++; return new Token(TokenType.SEMICOLON, ";", start);
            case '=': pos++; return new Token(TokenType.EQ, "=", start);
            case '<':
                pos++;
                if (peekChar() == '=') { pos++; return new Token(TokenType.LTE, "<=", start); }
                if (peekChar() == '>') { pos++; return new Token(TokenType.NEQ, "<>", start); }
                return new Token(TokenType.LT, "<", start);
            case '>':
                pos++;
                if (peekChar() == '=') { pos++; return new Token(TokenType.GTE, ">=", start); }
                return new Token(TokenType.GT, ">", start);
            case '!':
                pos++;
                if (peekChar() == '=') { pos++; return new Token(TokenType.NEQ, "!=", start); }
                throw new ParseException("Unexpected character '!' at position " + start);
            default:
                throw new ParseException("Unexpected character '" + c + "' at position " + start);
        }
    }

    private char peekChar() {
        return pos < input.length() ? input.charAt(pos) : '\0';
    }

    private void skipWhitespace() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '-' && pos + 1 < input.length() && input.charAt(pos + 1) == '-') {
                // -- line comment
                while (pos < input.length() && input.charAt(pos) != '\n') pos++;
            } else {
                break;
            }
        }
    }

    private Token readStringLiteral() {
        int start = pos;
        pos++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= input.length()) {
                throw new ParseException("Unterminated string literal starting at position " + start);
            }
            char c = input.charAt(pos);
            if (c == '\'') {
                if (pos + 1 < input.length() && input.charAt(pos + 1) == '\'') {
                    sb.append('\''); // escaped quote
                    pos += 2;
                } else {
                    pos++; // closing quote
                    break;
                }
            } else {
                sb.append(c);
                pos++;
            }
        }
        return new Token(TokenType.STRING_LITERAL, sb.toString(), start);
    }

    private Token readNumber() {
        int start = pos;
        boolean isDouble = false;
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        if (pos < input.length() && input.charAt(pos) == '.'
                && pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1))) {
            isDouble = true;
            pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        }
        String text = input.substring(start, pos);
        return new Token(isDouble ? TokenType.DOUBLE_LITERAL : TokenType.INT_LITERAL, text, start);
    }

    private Token readIdentifierOrKeyword() {
        int start = pos;
        while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
            pos++;
        }
        String text = input.substring(start, pos);
        TokenType keyword = KEYWORDS.get(text.toUpperCase());
        return new Token(keyword != null ? keyword : TokenType.IDENTIFIER, text, start);
    }
}
