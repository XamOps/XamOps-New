package com.xammer.cloud.service.matters;

import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict parsers for every Datastores request parameter that is not a filter dimension (design 5.3.1).
 *
 * <p>Parameters arrive as a raw {@link MultiValueMap} and path variables as strings, and Spring's own
 * type conversion is never used, so every failure produces the same {@link DspmBadRequestException}
 * body. Messages name the parameter and the rule, never the rejected value. Each parser takes the raw
 * single value ({@code null} when absent) and returns the parsed value or the default.
 */
public final class DatastoreParams {

    /** Largest row offset for the list and the File View. */
    public static final int MAX_OFFSET = 10_000;

    /** Data Flow offsets must stay below this (vendor pages of 100, at most 100 of them; design 4.7). */
    public static final int MAX_DATA_FLOW_OFFSET_EXCLUSIVE = 10_000;

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_SEARCH_LENGTH = 200;
    public static final int MIN_DATA_FLOW_SEARCH_LENGTH = 3;
    public static final int MAX_ENTITY_NAME_LENGTH = 128;
    public static final int MAX_SKIP = 10_000;
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 100;

    private static final Pattern PAGE = Pattern.compile("^\\d{1,6}$");
    private static final Pattern PAGE_SIZE = Pattern.compile("^\\d{1,3}$");
    private static final Pattern SKIP = Pattern.compile("^\\d{1,5}$");
    private static final Pattern LIMIT = Pattern.compile("^\\d{1,3}$");
    private static final Pattern SOURCE_ID = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private DatastoreParams() {
    }

    /** A validated page request. */
    public record Paging(int page, int pageSize) {
        /** Zero-based row offset. */
        public long offset() {
            return (long) (page - 1) * pageSize;
        }
    }

    // ── parameter names ──────────────────────────────────────────────────────────────────────────

    /**
     * Rejects any parameter name outside {@code allowed}. {@code clientId} is never allowed anywhere,
     * so a caller cannot even try to pick a tenant.
     */
    public static void requireAllowed(MultiValueMap<String, String> params, Set<String> allowed) {
        for (String name : params.keySet()) {
            if (!allowed.contains(name)) {
                throw new DspmBadRequestException(name, name + " is not a supported parameter");
            }
        }
    }

    /**
     * The single value of a control parameter, or null when it is absent.
     *
     * @throws DspmBadRequestException when the parameter is given more than once; only filter
     *                                 dimensions may repeat.
     */
    public static String single(MultiValueMap<String, String> params, String name) {
        List<String> values = params.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new DspmBadRequestException(name, name + " must be given at most once");
        }
        return values.get(0);
    }

    // ── ids ──────────────────────────────────────────────────────────────────────────────────────

    /** The row's 24-hex {@code source_id}, lower-cased. */
    public static String sourceId(String raw) {
        if (raw == null || !SOURCE_ID.matcher(raw).matches()) {
            throw new DspmBadRequestException("sourceId", "sourceId must be 24 hexadecimal characters");
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    /** A Data Flow database, schema or table id in canonical UUID form, lower-cased. */
    public static String uuid(String parameter, String raw) {
        if (raw == null || !UUID.matcher(raw).matches()) {
            throw new DspmBadRequestException(parameter, parameter + " must be a UUID");
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    // ── paging ───────────────────────────────────────────────────────────────────────────────────

    /** {@code page}: 1 to 6 digits, at least 1. Default 1. */
    public static int page(String raw) {
        if (raw == null) {
            return DEFAULT_PAGE;
        }
        if (!PAGE.matcher(raw).matches() || Integer.parseInt(raw) < 1) {
            throw new DspmBadRequestException("page", "page must be an integer from 1 to 999999");
        }
        return Integer.parseInt(raw);
    }

    /** {@code pageSize}: 1 to 3 digits, 1..100. Default 10. */
    public static int pageSize(String raw) {
        if (raw == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (!PAGE_SIZE.matcher(raw).matches()) {
            throw pageSizeError();
        }
        int value = Integer.parseInt(raw);
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw pageSizeError();
        }
        return value;
    }

    /** Page and size for the list and the File View: offset at most 10,000. */
    public static Paging listPaging(String rawPage, String rawPageSize) {
        Paging paging = new Paging(page(rawPage), pageSize(rawPageSize));
        if (paging.offset() > MAX_OFFSET) {
            throw new DspmBadRequestException("page",
                    "page is too large: (page - 1) x pageSize must be at most 10000");
        }
        return paging;
    }

    /** Page and size for Data Flow tables and columns: offset below 10,000. */
    public static Paging dataFlowPaging(String rawPage, String rawPageSize) {
        Paging paging = new Paging(page(rawPage), pageSize(rawPageSize));
        if (paging.offset() >= MAX_DATA_FLOW_OFFSET_EXCLUSIVE) {
            throw new DspmBadRequestException("page",
                    "page is too large: (page - 1) x pageSize must be below 10000");
        }
        return paging;
    }

    // ── occurrences ──────────────────────────────────────────────────────────────────────────────

    /** {@code skip}: 1 to 5 digits, 0..10,000. Default 0. */
    public static int skip(String raw) {
        if (raw == null) {
            return 0;
        }
        if (!SKIP.matcher(raw).matches() || Integer.parseInt(raw) > MAX_SKIP) {
            throw new DspmBadRequestException("skip", "skip must be an integer from 0 to 10000");
        }
        return Integer.parseInt(raw);
    }

    /** {@code limit}: 1 to 3 digits, 1..100. Default 10. */
    public static int limit(String raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        if (!LIMIT.matcher(raw).matches()) {
            throw limitError();
        }
        int value = Integer.parseInt(raw);
        if (value < 1 || value > MAX_LIMIT) {
            throw limitError();
        }
        return value;
    }

    /** {@code showFalsePositives}: exactly {@code true} or {@code false}. Default false. */
    public static boolean showFalsePositives(String raw) {
        if (raw == null) {
            return false;
        }
        return switch (raw) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new DspmBadRequestException("showFalsePositives",
                    "showFalsePositives must be true or false");
        };
    }

    /**
     * {@code entityName} format: required, trimmed, 1..128 characters, no control characters.
     * Membership in the asset's own entity list is checked by the service, after this.
     */
    public static String entityName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > MAX_ENTITY_NAME_LENGTH || hasControlCharacter(value)) {
            throw new DspmBadRequestException("entityName",
                    "entityName is required and must be 1 to 128 characters without control characters");
        }
        return value;
    }

    // ── search ───────────────────────────────────────────────────────────────────────────────────

    /** {@code q}: trimmed; blank means absent (null); at most 200 characters; no control characters. */
    public static String search(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_SEARCH_LENGTH || hasControlCharacter(value)) {
            throw new DspmBadRequestException("q",
                    "q must be at most 200 characters without control characters");
        }
        return value;
    }

    /** Data Flow {@code q}: as {@link #search}, and fewer than 3 characters means absent (FR-DFL-3). */
    public static String dataFlowSearch(String raw) {
        String value = search(raw);
        return value == null || value.length() < MIN_DATA_FLOW_SEARCH_LENGTH ? null : value;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** True when the text holds an ISO control character. */
    static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static DspmBadRequestException pageSizeError() {
        return new DspmBadRequestException("pageSize", "pageSize must be an integer from 1 to 100");
    }

    private static DspmBadRequestException limitError() {
        return new DspmBadRequestException("limit", "limit must be an integer from 1 to 100");
    }
}
