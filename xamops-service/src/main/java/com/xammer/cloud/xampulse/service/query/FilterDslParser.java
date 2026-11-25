package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.xampulse.dto.XpSeverity;
import com.xammer.cloud.xampulse.exception.XamPulseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Hand-written recursive-descent parser for the log filter DSL (contract 2.5).
 *
 * <pre>
 * expr  := or
 * or    := and ('OR' and)*
 * and   := unary (('AND')? unary)*          implicit AND
 * unary := ('NOT' | '-') unary | primary
 * primary := '(' or ')' | term
 * term  := field ':' value | quoted | word
 * value := word | quoted | '(' v ('OR' v)* ')'
 * </pre>
 *
 * Fields come from the closed {@link LogField} set plus {@code attr.<key>}. Unknown identifiers (including
 * {@code tenant_id}) are rejected, never passed through. Limits: 2000 characters, nesting depth 8, 40 terms.
 * Nothing here builds SQL; the parser only produces an AST.
 */
public final class FilterDslParser {

    public static final int MAX_LENGTH = 2000;
    public static final int MAX_DEPTH = 8;
    public static final int MAX_TERMS = 40;
    public static final int MAX_VALUE_LENGTH = 256;

    private static final Pattern FIELD_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.\\-]*$");
    private static final Pattern ATTR_KEY = Pattern.compile("^[A-Za-z0-9_.\\-]{1,64}$");

    private enum T { LPAREN, RPAREN, WORD, QUOTED, FIELD, AND, OR, NOT }

    private record Tok(T type, String text) {}

    private List<Tok> toks;
    private int pos;
    private int terms;

    private FilterDslParser() {}

    /** Parses a query. Blank input yields {@link FilterAst.MatchAll}. */
    public static FilterAst parse(String input) {
        if (input == null || input.isBlank()) {
            return new FilterAst.MatchAll();
        }
        if (input.length() > MAX_LENGTH) {
            throw XamPulseException.badFilter("The filter is too long (max " + MAX_LENGTH + " characters).");
        }
        FilterDslParser p = new FilterDslParser();
        p.toks = tokenize(input);
        p.pos = 0;
        FilterAst ast = p.parseOr(0);
        if (p.pos < p.toks.size()) {
            throw XamPulseException.badFilter("Unexpected '" + describe(p.toks.get(p.pos)) + "' in filter.");
        }
        return ast;
    }

    // ---- tokenizer ----

    private static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(') {
                out.add(new Tok(T.LPAREN, "("));
                i++;
            } else if (c == ')') {
                out.add(new Tok(T.RPAREN, ")"));
                i++;
            } else if (c == '"') {
                int[] end = new int[1];
                out.add(new Tok(T.QUOTED, readQuoted(s, i, end)));
                i = end[0];
            } else if (c == '-' && i + 1 < n && !Character.isWhitespace(s.charAt(i + 1))) {
                out.add(new Tok(T.NOT, "-"));
                i++;
            } else {
                int start = i;
                while (i < n) {
                    char d = s.charAt(i);
                    if (Character.isWhitespace(d) || d == '(' || d == ')' || d == '"') {
                        break;
                    }
                    i++;
                }
                String word = s.substring(start, i);
                switch (word) {
                    case "AND" -> out.add(new Tok(T.AND, word));
                    case "OR" -> out.add(new Tok(T.OR, word));
                    case "NOT" -> out.add(new Tok(T.NOT, word));
                    default -> splitField(word, i < n ? s.charAt(i) : ' ', out);
                }
            }
        }
        return out;
    }

    /** A word with a leading identifier and ':' becomes FIELD [WORD]; anything else (10:30, http://) stays text. */
    private static void splitField(String word, char next, List<Tok> out) {
        int colon = word.indexOf(':');
        if (colon <= 0 || !FIELD_NAME.matcher(word.substring(0, colon)).matches()) {
            out.add(new Tok(T.WORD, word));
            return;
        }
        String name = word.substring(0, colon);
        String rest = word.substring(colon + 1);
        if (rest.isEmpty()) {
            if (next == '"' || next == '(') {
                out.add(new Tok(T.FIELD, name));
            } else {
                out.add(new Tok(T.WORD, word));
            }
            return;
        }
        out.add(new Tok(T.FIELD, name));
        out.add(new Tok(T.WORD, rest));
    }

    private static String readQuoted(String s, int start, int[] endOut) {
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                sb.append(s.charAt(i + 1));
                i += 2;
            } else if (c == '"') {
                endOut[0] = i + 1;
                return sb.toString();
            } else {
                sb.append(c);
                i++;
            }
        }
        throw XamPulseException.badFilter("Unterminated quote in filter.");
    }

    // ---- parser ----

    private FilterAst parseOr(int depth) {
        checkDepth(depth);
        List<FilterAst> parts = new ArrayList<>();
        parts.add(parseAnd(depth));
        while (peek(T.OR)) {
            pos++;
            parts.add(parseAnd(depth));
        }
        return parts.size() == 1 ? parts.get(0) : new FilterAst.Or(parts);
    }

    private FilterAst parseAnd(int depth) {
        List<FilterAst> parts = new ArrayList<>();
        parts.add(parseUnary(depth));
        while (pos < toks.size() && !peek(T.OR) && !peek(T.RPAREN)) {
            if (peek(T.AND)) {
                pos++;
            }
            parts.add(parseUnary(depth));
        }
        return parts.size() == 1 ? parts.get(0) : new FilterAst.And(parts);
    }

    private FilterAst parseUnary(int depth) {
        checkDepth(depth);
        if (peek(T.NOT)) {
            pos++;
            return new FilterAst.Not(parseUnary(depth + 1));
        }
        return parsePrimary(depth);
    }

    private FilterAst parsePrimary(int depth) {
        if (pos >= toks.size()) {
            throw XamPulseException.badFilter("The filter ends unexpectedly.");
        }
        Tok t = toks.get(pos);
        switch (t.type) {
            case LPAREN -> {
                pos++;
                FilterAst inner = parseOr(depth + 1);
                if (!peek(T.RPAREN)) {
                    throw XamPulseException.badFilter("Missing ')' in filter.");
                }
                pos++;
                return inner;
            }
            case FIELD -> {
                pos++;
                return parseFieldTerm(t.text);
            }
            case WORD, QUOTED -> {
                pos++;
                countTerm();
                return new FilterAst.Text(checkValue(t.text));
            }
            default -> throw XamPulseException.badFilter("Unexpected '" + describe(t) + "' in filter.");
        }
    }

    private FilterAst parseFieldTerm(String name) {
        countTerm();
        LogField field;
        String attrKey = null;
        if (name.toLowerCase(Locale.ROOT).startsWith("attr.")) {
            attrKey = name.substring(5);
            if (!ATTR_KEY.matcher(attrKey).matches()) {
                throw XamPulseException.badFilter("Invalid attribute name in filter.");
            }
            field = LogField.ATTR;
        } else {
            field = LogField.fromName(name)
                    .orElseThrow(() -> XamPulseException.badFilter("Unknown filter field '" + safe(name) + "'."));
        }
        if (pos >= toks.size()) {
            throw XamPulseException.badFilter("Missing value for '" + safe(name) + "'.");
        }
        List<String> values = new ArrayList<>();
        String cmp = "=";
        Tok t = toks.get(pos);
        if (t.type == T.LPAREN) {
            pos++;
            while (true) {
                if (pos >= toks.size()) {
                    throw XamPulseException.badFilter("Missing ')' in filter.");
                }
                Tok v = toks.get(pos);
                if (v.type != T.WORD && v.type != T.QUOTED) {
                    throw XamPulseException.badFilter("Expected a value in the list for '" + safe(name) + "'.");
                }
                values.add(checkValue(v.text));
                pos++;
                if (peek(T.OR)) {
                    pos++;
                } else {
                    break;
                }
            }
            if (!peek(T.RPAREN)) {
                throw XamPulseException.badFilter("Missing ')' in filter.");
            }
            pos++;
            if (values.size() > 20) {
                throw XamPulseException.badFilter("Too many values in list.");
            }
        } else if (t.type == T.WORD || t.type == T.QUOTED) {
            pos++;
            String v = t.text;
            if (t.type == T.WORD) {
                for (String op : new String[] {">=", "<=", ">", "<"}) {
                    if (v.startsWith(op)) {
                        cmp = op;
                        v = v.substring(op.length());
                        break;
                    }
                }
            }
            values.add(checkValue(v));
        } else {
            throw XamPulseException.badFilter("Missing value for '" + safe(name) + "'.");
        }
        if (field == LogField.SEVERITY) {
            List<String> norm = new ArrayList<>();
            for (String v : values) {
                norm.add(normaliseSeverity(v));
            }
            values = norm;
        } else if (!"=".equals(cmp)) {
            throw XamPulseException.badFilter("Comparison operators are only valid for severity.");
        }
        return new FilterAst.Field(field, attrKey, cmp, List.copyOf(values));
    }

    private static String normaliseSeverity(String v) {
        String u = v.toUpperCase(Locale.ROOT);
        if (u.equals("WARNING")) {
            u = "WARN";
        } else if (u.equals("ERR")) {
            u = "ERROR";
        } else if (u.equals("CRITICAL")) {
            u = "FATAL";
        }
        try {
            return XpSeverity.valueOf(u).name();
        } catch (IllegalArgumentException e) {
            throw XamPulseException.badFilter("Unknown severity '" + safe(v) + "'.");
        }
    }

    private String checkValue(String v) {
        if (v.isEmpty()) {
            throw XamPulseException.badFilter("Empty value in filter.");
        }
        if (v.length() > MAX_VALUE_LENGTH) {
            throw XamPulseException.badFilter("A filter value is too long.");
        }
        return v;
    }

    private void countTerm() {
        if (++terms > MAX_TERMS) {
            throw XamPulseException.badFilter("Too many terms in filter (max " + MAX_TERMS + ").");
        }
    }

    private static void checkDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw XamPulseException.badFilter("Filter is nested too deeply (max " + MAX_DEPTH + ").");
        }
    }

    private boolean peek(T type) {
        return pos < toks.size() && toks.get(pos).type == type;
    }

    private static String describe(Tok t) {
        return safe(t.text);
    }

    private static String safe(String s) {
        String cleaned = s.replaceAll("[^A-Za-z0-9_.:*\\-]", "?");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) + "..." : cleaned;
    }

    // ---- normalised form, echoed to the UI ----

    public static String normalise(FilterAst ast) {
        return switch (ast) {
            case FilterAst.MatchAll m -> "";
            case FilterAst.Text t -> quoteIfNeeded(t.value());
            case FilterAst.Not n -> "NOT " + wrap(n.child());
            case FilterAst.And a -> String.join(" AND ", a.children().stream().map(FilterDslParser::wrap).toList());
            case FilterAst.Or o -> String.join(" OR ", o.children().stream().map(FilterDslParser::wrap).toList());
            case FilterAst.Field f -> {
                String name = f.field() == LogField.ATTR ? "attr." + f.attrKey() : f.field().name().toLowerCase(Locale.ROOT);
                String cmp = "=".equals(f.cmp()) ? "" : f.cmp();
                if (f.values().size() == 1) {
                    yield name + ":" + cmp + quoteIfNeeded(f.values().get(0));
                }
                yield name + ":(" + String.join(" OR ", f.values().stream().map(FilterDslParser::quoteIfNeeded).toList()) + ")";
            }
        };
    }

    private static String wrap(FilterAst a) {
        String s = normalise(a);
        return (a instanceof FilterAst.And || a instanceof FilterAst.Or) ? "(" + s + ")" : s;
    }

    private static String quoteIfNeeded(String v) {
        boolean plain = !v.isEmpty() && v.chars().noneMatch(c -> Character.isWhitespace(c) || c == '(' || c == ')' || c == '"');
        return plain ? v : "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
