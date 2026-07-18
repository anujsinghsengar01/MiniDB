package com.minidb.parser.ast;

/** Marker interface for anything that can appear inside a WHERE clause or a VALUES/SET expression. */
public interface Expression {

    /** A constant value: an int, double, string, boolean, or NULL. */
    final class Literal implements Expression {
        public final Object value; // Integer, Double, String, Boolean, or null
        public Literal(Object value) { this.value = value; }
        @Override public String toString() { return value == null ? "NULL" : value.toString(); }
    }

    /** A reference to a column, e.g. `gpa` or `students.gpa`. */
    final class ColumnReference implements Expression {
        public final String tableQualifier; // null if unqualified
        public final String columnName;
        public ColumnReference(String tableQualifier, String columnName) {
            this.tableQualifier = tableQualifier;
            this.columnName = columnName;
        }
        @Override public String toString() {
            return tableQualifier == null ? columnName : tableQualifier + "." + columnName;
        }
    }

    enum Operator { EQ, NEQ, LT, LTE, GT, GTE, AND, OR }

    /** A binary expression: `left OP right`, e.g. `gpa > 3.5` or `a = b AND c < d`. */
    final class BinaryExpression implements Expression {
        public final Expression left;
        public final Operator operator;
        public final Expression right;
        public BinaryExpression(Expression left, Operator operator, Expression right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
        }
        @Override public String toString() { return "(" + left + " " + operator + " " + right + ")"; }
    }
}
