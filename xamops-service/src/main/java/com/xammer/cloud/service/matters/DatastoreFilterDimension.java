package com.xammer.cloud.service.matters;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The filter dimensions the Datastores endpoints accept, with the value rule, whether an
 * {@code <key>_exclude} twin is allowed, and the tabs each one belongs to (design 5.3.2).
 *
 * <p>This is the allow-list both ways: a dimension not listed for a tab is rejected on input, and it is
 * dropped from the vendor's facet output. {@code lifecycle} and {@code resource_type} are in no
 * allow-list, so they can neither be sent nor shown.
 */
public enum DatastoreFilterDimension {

    INTEGRATION_ID("integration_id", Rule.HEX24, true, Scope.ALL),
    PROVIDER("provider", Rule.CODE, true, Scope.ALL),
    REGION("region", Rule.FREE_TEXT, true, Scope.ALL),
    SCAN_STATUS("scan_status", Rule.SCAN_STATUS, true, Scope.ALL),
    SEVERITY("severity", Rule.SEVERITY, true, Scope.ALL),
    SENSITIVE_TYPE("sensitive_type", Rule.CODE, true, Scope.ALL),
    DATA_ELEMENT("data_element", Rule.FREE_TEXT, true, Scope.ALL),
    SELECTED("selected", Rule.FREE_TEXT, false, Scope.SAAS),
    OS("os", Rule.CODE, false, Scope.ENDPOINT),
    AGENT_STATUS("agent_status", Rule.CODE, true, Scope.ENDPOINT),
    DEVICE_TYPE("device_type", Rule.FREE_TEXT, false, Scope.ENDPOINT),
    SERIAL_NUMBER("serial_number", Rule.FREE_TEXT, false, Scope.ENDPOINT),
    HOSTNAME("hostname", Rule.FREE_TEXT, false, Scope.ENDPOINT),
    USER("user", Rule.FREE_TEXT, false, Scope.ENDPOINT);

    /** Suffix of the exclude twin, e.g. {@code provider_exclude}. No dimension key ends with it. */
    public static final String EXCLUDE_SUFFIX = "_exclude";

    /** Longest free-text value. Mirrored by the frontend's {@code datastoreFilters.ts}. */
    public static final int MAX_FREE_TEXT_LENGTH = 128;

    private static final Pattern HEX24 = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Set<String> SCAN_STATUSES =
            Set.of("NOT_SCANNED", "CLASSIFIED", "FAILED", "STOPPED", "SCANNING", "SCHEDULED");
    private static final Set<String> SEVERITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "NONE");

    private enum Rule { HEX24, CODE, FREE_TEXT, SCAN_STATUS, SEVERITY }

    private enum Scope { ALL, SAAS, ENDPOINT }

    private final String key;
    private final Rule rule;
    private final boolean excludable;
    private final Scope scope;

    DatastoreFilterDimension(String key, Rule rule, boolean excludable, Scope scope) {
        this.key = key;
        this.rule = rule;
        this.excludable = excludable;
        this.scope = scope;
    }

    /** The query-parameter and vendor key, e.g. {@code scan_status}. */
    public String key() {
        return key;
    }

    /** Whether {@code <key>_exclude} is accepted (A6). */
    public boolean excludable() {
        return excludable;
    }

    /** Whether the dimension is in the allow-list of this tab. */
    public boolean allowedOn(DatastoreTab tab) {
        return switch (scope) {
            case ALL -> true;
            case SAAS -> tab == DatastoreTab.SAAS;
            case ENDPOINT -> tab == DatastoreTab.ENDPOINT;
        };
    }

    /** The dimension with this key, or null when there is none. */
    public static DatastoreFilterDimension fromKey(String key) {
        if (key == null) {
            return null;
        }
        for (DatastoreFilterDimension dimension : values()) {
            if (dimension.key.equals(key)) {
                return dimension;
            }
        }
        return null;
    }

    /**
     * Checks one already-trimmed, non-empty value against the dimension's rule.
     *
     * @param parameter the parameter name to report, {@code key} or {@code key_exclude}.
     * @return the value in its canonical form (integration ids lower-cased; everything else as given).
     * @throws DspmBadRequestException naming {@code parameter} and the rule, never the value.
     */
    public String normalise(String parameter, String value) {
        switch (rule) {
            case HEX24 -> {
                if (!HEX24.matcher(value).matches()) {
                    throw new DspmBadRequestException(parameter,
                            parameter + " values must be 24 hexadecimal characters");
                }
                return value.toLowerCase(Locale.ROOT);
            }
            case CODE -> {
                if (!CODE.matcher(value).matches()) {
                    throw new DspmBadRequestException(parameter, parameter
                            + " values must be upper-case codes of letters, digits and underscores, at most 64 characters");
                }
                return value;
            }
            case SCAN_STATUS -> {
                if (!SCAN_STATUSES.contains(value)) {
                    throw new DspmBadRequestException(parameter, parameter
                            + " values must be NOT_SCANNED, CLASSIFIED, FAILED, STOPPED, SCANNING or SCHEDULED");
                }
                return value;
            }
            case SEVERITY -> {
                if (!SEVERITIES.contains(value)) {
                    throw new DspmBadRequestException(parameter,
                            parameter + " values must be CRITICAL, HIGH, MEDIUM, LOW or NONE");
                }
                return value;
            }
            default -> {
                if (value.length() > MAX_FREE_TEXT_LENGTH || DatastoreParams.hasControlCharacter(value)) {
                    throw new DspmBadRequestException(parameter, parameter
                            + " values must be 1 to 128 characters without control characters");
                }
                return value;
            }
        }
    }
}
