package com.xammer.cloud.service.autospotting.ledger.calc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;

/**
 * Canonical JSON for the ledger (plan-phase-16.md task 16.1; design-ledger.md §14.1, FR-LED-12): object
 * keys sorted, decimals written as plain strings with their scale kept (never as JSON numbers, so no
 * reader can turn one into a binary float), instants as ISO-8601 UTC strings, SHA-256 over the compact
 * form. Golden fixtures are compared byte for byte in the pretty form of the same canonical tree.
 */
public final class CanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private CanonicalJson() {}

    /** The shared mapper for ledger documents (UTC instants, exact decimals, unknown properties refused). */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Parses a document into a record, exactly. */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The canonical tree of any value: sorted keys, decimals as plain strings. */
    public static JsonNode tree(Object value) {
        JsonNode raw = value instanceof JsonNode n ? n : MAPPER.valueToTree(value);
        return canonicalize(raw);
    }

    /** Compact canonical text: the form that is hashed and stored as {@code inputs}. */
    public static String compact(Object value) {
        return write(tree(value), false);
    }

    /** Pretty canonical text (2-space indent, LF, no trailing newline): the golden-file form. */
    public static String pretty(Object value) {
        return write(tree(value), true);
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 of the compact canonical form. */
    public static String hash(Object value) {
        return sha256(compact(value));
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            Iterator<String> it = node.fieldNames();
            it.forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                out.set(name, canonicalize(node.get(name)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : node) {
                out.add(canonicalize(child));
            }
            return out;
        }
        if (node.isBigDecimal()) {
            return JsonNodeFactory.instance.textNode(node.decimalValue().toPlainString());
        }
        if (node.isFloatingPointNumber()) {
            throw new IllegalArgumentException("floating point number in a ledger document: " + node);
        }
        if (node.isIntegralNumber() && node.isBigInteger()) {
            return JsonNodeFactory.instance.textNode(node.bigIntegerValue().toString());
        }
        return node;
    }

    private static String write(JsonNode canonical, boolean pretty) {
        try {
            if (!pretty) {
                return MAPPER.writeValueAsString(canonical);
            }
            return MAPPER.writer().with(new com.fasterxml.jackson.core.util.DefaultPrettyPrinter()
                    .withObjectIndenter(new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n"))
                    .withArrayIndenter(new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n")))
                    .writeValueAsString(canonical);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Helper for tests and tools: a decimal string to a BigDecimal, never through a binary float. */
    public static BigDecimal decimal(String text) {
        return new BigDecimal(text);
    }
}
