package com.minidb.executor;

import com.minidb.parser.ast.Expression;

/**
 * Evaluates a parsed Expression (from a WHERE or JOIN...ON clause) against
 * a specific row via RowContext. Comparisons follow simplified SQL null
 * semantics: any comparison involving NULL evaluates to false (rather than
 * three-valued UNKNOWN) — close enough for a mini-db and avoids threading
 * a tri-state boolean through the whole executor.
 */
public final class ExpressionEvaluator {

    private ExpressionEvaluator() {}

    public static boolean evaluateBoolean(Expression expr, RowContext ctx) {
        Object result = evaluate(expr, ctx);
        if (!(result instanceof Boolean)) {
            throw new ExecutionException("Expression did not evaluate to a boolean: " + expr);
        }
        return (Boolean) result;
    }

    private static Object evaluate(Expression expr, RowContext ctx) {
        if (expr instanceof Expression.Literal) {
            return ((Expression.Literal) expr).value;
        }
        if (expr instanceof Expression.ColumnReference) {
            return ctx.resolve((Expression.ColumnReference) expr);
        }
        if (expr instanceof Expression.BinaryExpression) {
            Expression.BinaryExpression be = (Expression.BinaryExpression) expr;
            switch (be.operator) {
                case AND:
                    return evaluateBoolean(be.left, ctx) && evaluateBoolean(be.right, ctx);
                case OR:
                    return evaluateBoolean(be.left, ctx) || evaluateBoolean(be.right, ctx);
                default:
                    Object left = evaluate(be.left, ctx);
                    Object right = evaluate(be.right, ctx);
                    return compare(left, right, be.operator);
            }
        }
        throw new ExecutionException("Unsupported expression: " + expr);
    }

    private static boolean compare(Object left, Object right, Expression.Operator op) {
        if (left == null || right == null) {
            // NULL compared to anything is neither true nor false in real SQL;
            // treating it as false here means such rows are correctly excluded from WHERE results.
            return false;
        }

        int cmp;
        if (left instanceof Number && right instanceof Number) {
            cmp = Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
        } else if (left instanceof String && right instanceof String) {
            cmp = ((String) left).compareTo((String) right);
        } else if (left instanceof Boolean && right instanceof Boolean) {
            cmp = Boolean.compare((Boolean) left, (Boolean) right);
        } else {
            throw new ExecutionException("Cannot compare values of different types: " + left + " and " + right);
        }

        switch (op) {
            case EQ: return cmp == 0;
            case NEQ: return cmp != 0;
            case LT: return cmp < 0;
            case LTE: return cmp <= 0;
            case GT: return cmp > 0;
            case GTE: return cmp >= 0;
            default:
                throw new ExecutionException("Not a comparison operator: " + op);
        }
    }
}
