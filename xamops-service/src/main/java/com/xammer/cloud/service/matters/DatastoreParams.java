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
    public static final int MAX_TEXT_LENGTH = 128;

    /** The SaaS records views; the first is the default. */
    public static final String VIEW_DATA_ELEMENTS = "data_elements";
    public static final String VIEW_DOCUMENT_CLASSES = "document_classes";

    private static final Pattern PAGE = Pattern.compile("^\\d{1,6}$");
    private static final Pattern PAGE_SIZE = Pattern.compile("^\\d{1,3}$");
    private static final Pattern SKIP = Pattern.compile("^\\d{1,5}$");
    private static final Pattern LIMIT = Pattern.compile("^\\d{1,3}$");
    private static final Pattern SOURCE_ID = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    /** A misconfiguration policy id: {@code p-} plus 16 hex today, snake_case words on some rules. */
    private static final Pattern POLICY_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$");
    /** A keyset cursor: base64 or base64url, 1..1,024 characters, at most two {@code =} of padding. */
    private static final Pattern CURSOR = Pattern.compile("^[A-Za-z0-9+/_-]{1,1024}={0,2}$");

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

    /** A SaaS file's 24-hex id from the SaaS file list, lower-cased. */
    public static String fileId(String raw) {
        if (raw == null || !SOURCE_ID.matcher(raw).matches()) {
            throw new DspmBadRequestException("fileId", "fileId must be 24 hexadecimal characters");
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

    /** True when the text is a policy id {@link #policyId} accepts. */
    public static boolean isPolicyId(String raw) {
        return raw != null && POLICY_ID.matcher(raw).matches();
    }

    /** A misconfiguration's vendor {@code policy_id}: 1 to 128 letters, digits, '_', '-' or '.', as given. */
    public static String policyId(String raw) {
        if (!isPolicyId(raw)) {
            throw new DspmBadRequestException("policyId",
                    "policyId must be 1 to 128 letters, digits, '_', '-' or '.'");
        }
        return raw;
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
        return limit(raw, DEFAULT_LIMIT);
    }

    /** {@code limit}: 1 to 3 digits, 1..100, or {@code defaultValue} when absent. */
    public static int limit(String raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
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

    // ── SaaS drawer ──────────────────────────────────────────────────────────────────────────────

    /** {@code view}: exactly {@code data_elements} or {@code document_classes}. Default data_elements. */
    public static String saasView(String raw) {
        if (raw == null) {
            return VIEW_DATA_ELEMENTS;
        }
        if (!VIEW_DATA_ELEMENTS.equals(raw) && !VIEW_DOCUMENT_CLASSES.equals(raw)) {
            throw new DspmBadRequestException("view", "view must be data_elements or document_classes");
        }
        return raw;
    }

    /**
     * A required text value such as a data element or document class name: trimmed, 1..128 characters,
     * no control characters. Membership, where it applies, is checked by the service, after this.
     */
    public static String requiredText(String parameter, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > MAX_TEXT_LENGTH || hasControlCharacter(value)) {
            throw new DspmBadRequestException(parameter,
                    parameter + " is required and must be 1 to 128 characters without control characters");
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

    // ── Files page ───────────────────────────────────────────────────────────────────────────────

    /**
     * {@code cursor}: absent is the first page (null); present, it must be base64 or base64url text of 1 to
     * 1,024 characters with at most two {@code =} of padding. It is checked for format only and never
     * decoded: the vendor's keyset cursor is opaque.
     */
    public static String cursor(String raw) {
        if (raw == null) {
            return null;
        }
        if (!isCursor(raw)) {
            throw new DspmBadRequestException("cursor", "cursor must be a cursor returned by the previous page");
        }
        return raw;
    }

    /** True when {@code value} passes {@link #cursor}'s format rule. The mapper drops a next cursor that fails it. */
    public static boolean isCursor(String value) {
        return value != null && CURSOR.matcher(value).matches();
    }

    /**
     * A single-choice parameter such as {@code sharing} or {@code lastModified}: null when absent, else one
     * of {@code allowed}, compared exactly (case-sensitive).
     */
    public static String choice(String parameter, String raw, List<String> allowed) {
        if (raw == null) {
            return null;
        }
        if (!allowed.contains(raw)) {
            throw new DspmBadRequestException(parameter,
                    parameter + " must be one of " + String.join(", ", allowed));
        }
        return raw;
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
