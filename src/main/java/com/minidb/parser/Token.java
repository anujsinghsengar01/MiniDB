package com.minidb.parser;

/** A single lexical token: its type, the raw text it came from, and where it sits in the input (for error messages). */
public final class Token {
    public final TokenType type;
    public final String text;
    public final int position;

    public Token(TokenType type, String text, int position) {
        this.type = type;
        this.text = text;
        this.position = position;
    }

    @Override
    public String toString() {
        return type + "('" + text + "')";
    }
}
