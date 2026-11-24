package com.xammer.cloud.service.matters;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The Data Lineage request parsers that {@link DatastoreParams} lacks (build 4 design 5.3). A separate
 * class, so build 3's additions to {@code DatastoreParams} cannot conflict with these.
 *
 * <p>Same contract as {@code DatastoreParams}: each parser takes the raw single value ({@code null} when
 * absent) and returns the parsed value or the default, or throws a {@link DspmBadRequestException} whose
 * message names the parameter and the rule, never the rejected value.
 */
public final class LineageParams {

    /** The two levels whose More filters the page asks for. */
    public static final String LEVEL_POLICY = "policy";
    public static final String LEVEL_DATASTORE = "datastore";

    /** Largest {@code limit} for the policy and datastore rows, and for the file row. */
    public static final int MAX_ROW_LIMIT = 50;
    public static final int MAX_FILE_LIMIT = 100;

    /** Nodes one row shows at a time. */
    public static final int DEFAULT_LIMIT = 7;

    private static final Pattern EXPOSURE_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern SOURCE_ID = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern DIGITS = Pattern.compile("^\\d{1,3}$");

    private LineageParams() {
    }

    /** {@code level}: required, exactly {@code policy} or {@code datastore}. */
    public static String level(String raw) {
        if (LEVEL_POLICY.equals(raw) || LEVEL_DATASTORE.equals(raw)) {
            return raw;
        }
        throw new DspmBadRequestException("level", "level must be policy or datastore");
    }

    /** {@code exposureId}: 1 to 64 letters, digits or hyphens (format unverified, design A2; path-safe). */
    public static String exposureId(String raw) {
        if (raw == null || !EXPOSURE_ID.matcher(raw).matches()) {
            throw new DspmBadRequestException("exposureId",
                    "exposureId must be 1 to 64 letters, digits or hyphens");
        }
        return raw;
    }

    /** {@code fileId}: the SaaS list's 24 hex or the endpoint list's UUID, lower-cased. */
    public static String fileId(String raw) {
        if (raw == null || !(SOURCE_ID.matcher(raw).matches() || UUID.matcher(raw).matches())) {
            throw new DspmBadRequestException("fileId", "fileId must be 24 hexadecimal characters or a UUID");
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    /** {@code limit}: 1 to 3 digits, 1..{@code max}, or {@code defaultValue} when absent. */
    public static int limit(String raw, int max, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (DIGITS.matcher(raw).matches()) {
            int value = Integer.parseInt(raw);
            if (value >= 1 && value <= max) {
                return value;
            }
        }
        throw new DspmBadRequestException("limit", "limit must be an integer from 1 to " + max);
    }
}
