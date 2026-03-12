package az.testup.util;

import java.util.Map;

/**
 * Simple recursive-descent math expression evaluator.
 * Supports: +, -, *, /, parentheses, decimal numbers, named variables.
 */
public class FormulaEvaluator {

    public static double evaluate(String expression, Map<String, Double> variables) {
        if (expression == null || expression.isBlank()) return 0.0;
        try {
            return new Parser(expression, variables).parseExpression();
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static class Parser {
        private final String expr;
        private final Map<String, Double> variables;
        private int pos = 0;

        Parser(String expr, Map<String, Double> vars) {
            this.expr = expr.replaceAll("\\s+", "");
            this.variables = vars;
        }

        double parseExpression() {
            double result = parseTerm();
            while (pos < expr.length()) {
                char c = expr.charAt(pos);
                if (c == '+') { pos++; result += parseTerm(); }
                else if (c == '-') { pos++; result -= parseTerm(); }
                else break;
            }
            return result;
        }

        double parseTerm() {
            double result = parseFactor();
            while (pos < expr.length()) {
                char c = expr.charAt(pos);
                if (c == '*') { pos++; result *= parseFactor(); }
                else if (c == '/') { pos++; double d = parseFactor(); result = d != 0 ? result / d : 0; }
                else break;
            }
            return result;
        }

        double parseFactor() {
            if (pos >= expr.length()) return 0.0;
            char c = expr.charAt(pos);
            if (c == '(') {
                pos++;
                double result = parseExpression();
                if (pos < expr.length() && expr.charAt(pos) == ')') pos++;
                return result;
            }
            if (c == '-') { pos++; return -parseFactor(); }
            if (Character.isDigit(c) || c == '.') return parseNumber();
            if (Character.isLetter(c)) return parseVariable();
            pos++;
            return 0.0;
        }

        double parseNumber() {
            int start = pos;
            while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) pos++;
            try { return Double.parseDouble(expr.substring(start, pos)); }
            catch (NumberFormatException e) { return 0.0; }
        }

        double parseVariable() {
            int start = pos;
            while (pos < expr.length() && Character.isLetterOrDigit(expr.charAt(pos))) pos++;
            return variables.getOrDefault(expr.substring(start, pos), 0.0);
        }
    }
}
