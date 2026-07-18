package com.minidb.parser;

/** Thrown for both lexing errors (bad characters) and parsing errors (unexpected tokens / bad grammar). */
public class ParseException extends RuntimeException {
    public ParseException(String message) {
        super(message);
    }
}
