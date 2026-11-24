package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one meaning of "text", "texts" and "epoch seconds" for the SaaS, endpoint and diagram mappers
 * (design 5.6).
 *
 * <p>Build 1's {@code DatastoreRowMapper.text} is not used for text: it returns {@code asText()} for
 * numbers and booleans and does not trim, so it would keep Gmail's numeric {@code sizeEstimate} as
 * metadata. Counts still use build 1's {@code longOrNull} and {@code longOr}.
 */
final class DrawerJson {

    /** Numbers above this are epoch milliseconds. */
    private static final long MILLISECONDS_THRESHOLD = 1_000_000_000_000L;

    private DrawerJson() {
    }

    /** The trimmed string when v is a JSON string with a non-blank value; null for numbers, booleans, null, containers. */
    static String stringValue(JsonNode v) {
        if (v == null || !v.isTextual()) {
            return null;
        }
        String text = v.textValue().trim();
        return text.isEmpty() ? null : text;
    }

    /** {@code stringValue(node.path(field))}. */
    static String string(JsonNode node, String field) {
        return node == null ? null : stringValue(node.path(field));
    }

    /**
     * The stringValue of each element of an array, nulls skipped, at most cap values, each at most
     * maxLength characters (longer values are dropped, not cut); empty for a non-array.
     */
    static List<String> strings(JsonNode array, int cap, int maxLength) {
        List<String> out = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return out;
        }
        for (JsonNode element : array) {
            if (out.size() >= cap) {
                break;
            }
            String value = stringValue(element);
            if (value != null && value.length() <= maxLength) {
                out.add(value);
            }
        }
        return out;
    }

    /**
     * Epoch seconds: a number is floored (1789725004.377114 → 1789725004); above 10^12 it is milliseconds
     * and divided by 1,000; ISO-8601 text goes through OffsetDateTime.parse, else Instant.parse; anything
     * else is null.
     */
    static Long epochSeconds(JsonNode v) {
        if (v == null) {
            return null;
        }
        if (v.isIntegralNumber()) {
            if (!v.canConvertToLong()) {
                return null;
            }
            long value = v.longValue();
            return value > MILLISECONDS_THRESHOLD ? Math.floorDiv(value, 1000L) : value;
        }
        if (v.isNumber()) {
            double value = v.doubleValue();
            if (!Double.isFinite(value)) {
                return null;
            }
            return (long) Math.floor(value > MILLISECONDS_THRESHOLD ? value / 1000d : value);
        }
        String text = stringValue(v);
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toEpochSecond();
        } catch (DateTimeParseException notOffset) {
            try {
                return Instant.parse(text).getEpochSecond();
            } catch (DateTimeParseException notInstant) {
                return null;
            }
        }
    }

    /**
     * Review fix R7, the rule of the frontend's {@code extensionOf} ({@code ds/datastoreFormat.ts}): the
     * text after the last {@code '.'} of the basename (after the last {@code '/'} or {@code '\'}),
     * lower-cased; null without a dot or with a trailing dot. {@code ".env"} gives {@code "env"}.
     */
    static String extensionOf(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) {
            return null;
        }
        return base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
