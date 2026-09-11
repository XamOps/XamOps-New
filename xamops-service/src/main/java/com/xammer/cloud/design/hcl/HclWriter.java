package com.xammer.cloud.design.hcl;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Low-level HCL text builder: blocks, attributes, and value literals.
 *
 * <p>
 * Deliberately not a general HCL library. Generating configuration needs only a
 * narrow slice of the language — blocks, {@code key = value}, scalars, lists,
 * maps and unquoted references — and that slice is small enough to write and test
 * exhaustively. Parsing arbitrary HCL is the genuinely hard direction and would
 * need a Go sidecar, which is why importing existing Terraform is a later step.
 *
 * <p>
 * The one rule everything else follows from: <strong>quoting decides
 * meaning</strong>. {@code vpc_id = "aws_vpc.main.id"} is a literal string that
 * will never resolve; {@code vpc_id = aws_vpc.main.id} is a reference Terraform
 * uses to order the graph. Callers say which they mean via {@link #raw(String)}
 * rather than the writer guessing from content.
 */
public final class HclWriter {

    /** Terraform identifiers: letter or underscore, then letters, digits, dash, underscore. */
    private static final Pattern LEGAL_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_-]*$");

    private final StringBuilder out = new StringBuilder();
    private final Deque<String> openBlocks = new ArrayDeque<>();
    private int indent;

    // ─── Structure ───────────────────────────────────────────────────────────

    /**
     * Open a block such as {@code resource "aws_vpc" "main" {}}.
     *
     * @param type   block keyword, e.g. {@code resource}, {@code variable}
     * @param labels quoted labels following the keyword
     */
    public HclWriter openBlock(String type, String... labels) {
        pad();
        out.append(type);
        for (String label : labels) {
            out.append(' ').append('"').append(escape(label)).append('"');
        }
        out.append(" {\n");
        openBlocks.push(type);
        indent++;
        return this;
    }

    /** Open a nested block with no labels, e.g. {@code ingress {}}. */
    public HclWriter openNested(String name) {
        pad();
        out.append(name).append(" {\n");
        openBlocks.push(name);
        indent++;
        return this;
    }

    public HclWriter closeBlock() {
        if (openBlocks.isEmpty()) {
            throw new IllegalStateException("closeBlock() with no block open");
        }
        openBlocks.pop();
        indent--;
        pad();
        out.append("}\n");
        return this;
    }

    public HclWriter blankLine() {
        out.append('\n');
        return this;
    }

    public HclWriter comment(String text) {
        for (String line : text.split("\n", -1)) {
            pad();
            out.append("# ").append(line).append('\n');
        }
        return this;
    }

    // ─── Attributes ──────────────────────────────────────────────────────────

    /** {@code name = <literal>}, quoting chosen from the runtime type. */
    public HclWriter attribute(String name, Object value) {
        pad();
        out.append(name).append(" = ").append(literal(value)).append('\n');
        return this;
    }

    /**
     * {@code name = <expression>} with the value written verbatim.
     *
     * <p>
     * For references ({@code aws_vpc.main.id}), variables ({@code var.region}) and
     * function calls — anything that must not be quoted.
     */
    public HclWriter rawAttribute(String name, String expression) {
        pad();
        out.append(name).append(" = ").append(expression).append('\n');
        return this;
    }

    /**
     * A {@code tags}-style map, written as a block of {@code key = value} pairs.
     * Keys are sorted so output does not depend on map iteration order.
     */
    public HclWriter mapAttribute(String name, Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return this;
        }
        pad();
        out.append(name).append(" = {\n");
        indent++;
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    pad();
                    out.append(quoteKeyIfNeeded(entry.getKey()))
                            .append(" = ")
                            .append(literal(entry.getValue()))
                            .append('\n');
                });
        indent--;
        pad();
        out.append("}\n");
        return this;
    }

    // ─── Literals ────────────────────────────────────────────────────────────

    /**
     * Render a value as an HCL literal.
     *
     * <p>
     * Numbers and booleans are bare; everything else is a quoted string. Lists
     * recurse. A {@link Raw} passes through untouched, which is how references
     * stay unquoted.
     */
    public String literal(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Raw raw) {
            return raw.expression();
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Number n) {
            return numberLiteral(n);
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return "[]";
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(literal(list.get(i)));
            }
            return sb.append(']').toString();
        }
        return '"' + escape(String.valueOf(value)) + '"';
    }

    /**
     * Whole doubles print as integers.
     *
     * <p>
     * Jackson deserialises JSON {@code 443} into a Double when the target is
     * {@code Object}, and {@code from_port = 443.0} is not valid for a port. This
     * is the kind of detail that only shows up at {@code terraform validate}.
     */
    private String numberLiteral(Number n) {
        if (n instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new IllegalArgumentException("Cannot emit non-finite number: " + d);
            }
            if (d == Math.floor(d)) {
                return String.valueOf(d.longValue());
            }
            return String.valueOf(d);
        }
        if (n instanceof Float f) {
            if (f == Math.floor(f)) {
                return String.valueOf(f.longValue());
            }
            return String.valueOf(f);
        }
        return n.toString();
    }

    /**
     * Escape a value for a quoted HCL string.
     *
     * <p>
     * {@code ${} is escaped to {@code $${} so a tag value containing it is treated
     * as text rather than silently becoming a template interpolation. Callers that
     * genuinely want interpolation use {@link #raw(String)}.
     */
    public static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '$' -> {
                    if (i + 1 < value.length() && value.charAt(i + 1) == '{') {
                        sb.append("$${");
                        i++;
                    } else {
                        sb.append('$');
                    }
                }
                case '%' -> {
                    if (i + 1 < value.length() && value.charAt(i + 1) == '{') {
                        sb.append("%%{");
                        i++;
                    } else {
                        sb.append('%');
                    }
                }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String quoteKeyIfNeeded(String key) {
        return LEGAL_IDENTIFIER.matcher(key).matches() ? key : '"' + escape(key) + '"';
    }

    /**
     * Turn arbitrary text into a legal, stable Terraform identifier.
     *
     * <p>
     * Resource names come from whatever the author typed on the canvas — and, when
     * a design is imported from a live account, from customer {@code Name} tags,
     * which routinely contain spaces, dots and slashes. An illegal identifier is a
     * hard Terraform parse error, so this normalises rather than trusting input.
     */
    public static String identifier(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unnamed";
        }
        // Dashes are legal in Terraform identifiers but collapsed to underscores
        // anyway: snake_case is the convention, and without this "web-tier" and
        // "Web Tier" become two distinct resources that read as the same thing.
        String slug = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        slug = slug.replaceAll("_{2,}", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            return "unnamed";
        }
        // Must not start with a digit or a dash.
        if (!Character.isLetter(slug.charAt(0)) && slug.charAt(0) != '_') {
            slug = "r_" + slug;
        }
        return slug;
    }

    public static boolean isLegalIdentifier(String candidate) {
        return candidate != null && LEGAL_IDENTIFIER.matcher(candidate).matches();
    }

    // ─── Output ──────────────────────────────────────────────────────────────

    public String build() {
        if (!openBlocks.isEmpty()) {
            throw new IllegalStateException("Unclosed HCL blocks: " + openBlocks);
        }
        return out.toString();
    }

    private void pad() {
        out.append("  ".repeat(indent));
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** An expression to emit verbatim, never quoted. */
    public record Raw(String expression) {
        public Raw {
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("Raw expression must not be blank");
            }
        }
    }

    public static Raw raw(String expression) {
        return new Raw(expression);
    }
}
