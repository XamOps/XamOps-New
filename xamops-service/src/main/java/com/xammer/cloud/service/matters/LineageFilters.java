package com.xammer.cloud.service.matters;

import org.springframework.util.MultiValueMap;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The Data Lineage page's More filters (build 4 design 5.3): {@code severity}, {@code datastoreName} and
 * {@code label}, each an Include or an Exclude of a sorted, de-duplicated list.
 *
 * <p>Values are checked for format here. Membership in the project's options, and turning an Exclude into
 * the include list the vendor takes, belong to {@code LineageService}.
 *
 * @param severity      the Severity pane
 * @param datastoreName the Data Store pane (policy level only)
 * @param label         the Labels pane (policy level only)
 */
public record LineageFilters(Selection severity, Selection datastoreName, Selection label) {

    public static final String SEVERITY = "severity";
    public static final String DATASTORE_NAME = "datastoreName";
    public static final String LABEL = "label";

    /** The suffix of an Exclude parameter: {@code severity_exclude}. */
    public static final String EXCLUDE_SUFFIX = "_exclude";

    /** The policy level's dimensions, and the datastore level's. */
    public static final List<String> POLICY_DIMENSIONS = List.of(SEVERITY, DATASTORE_NAME, LABEL);
    public static final List<String> DATASTORE_DIMENSIONS = List.of(SEVERITY);

    /** No filter at all. */
    public static final LineageFilters NONE = new LineageFilters(Selection.NONE, Selection.NONE, Selection.NONE);

    /** Most distinct values one filter may carry. */
    public static final int MAX_VALUES = 50;

    /** Longest single value. */
    public static final int MAX_VALUE_LENGTH = 128;

    /**
     * One pane's choice.
     *
     * @param exclude true: every option except {@code values}
     * @param values  sorted, de-duplicated; empty means no filter
     */
    public record Selection(boolean exclude, List<String> values) {

        /** Include, nothing chosen: no filter. */
        public static final Selection NONE = new Selection(false, List.of());

        public Selection {
            values = values == null ? List.of() : List.copyOf(values);
        }

        public boolean isEmpty() {
            return values.isEmpty();
        }

        /** {@code i:} or {@code e:}, then the values URL-encoded and comma-joined. */
        String fragment() {
            return (exclude ? "e:" : "i:") + values.stream()
                    .map(value -> URLEncoder.encode(value, StandardCharsets.UTF_8))
                    .collect(Collectors.joining(","));
        }
    }

    public LineageFilters {
        severity = severity == null ? Selection.NONE : severity;
        datastoreName = datastoreName == null ? Selection.NONE : datastoreName;
        label = label == null ? Selection.NONE : label;
    }

    /**
     * Reads the filters of {@code dimensions} from the request. Every occurrence of {@code <dim>} or
     * {@code <dim>_exclude} is split on {@code ','}, each value trimmed, empties dropped, the rest
     * de-duplicated and sorted. A parameter that is present must leave 1..50 values of 1..128 characters
     * without control characters. Other parameter names are left to the controller's {@code requireAllowed},
     * except a filter of a dimension this level does not have.
     *
     * @throws DspmBadRequestException naming the parameter, never the value
     */
    public static LineageFilters parse(MultiValueMap<String, String> params, Collection<String> dimensions) {
        for (String dimension : POLICY_DIMENSIONS) {
            if (!dimensions.contains(dimension)) {
                rejectIfPresent(params, dimension);
                rejectIfPresent(params, dimension + EXCLUDE_SUFFIX);
            }
        }
        return new LineageFilters(
                dimensions.contains(SEVERITY) ? selection(params, SEVERITY) : Selection.NONE,
                dimensions.contains(DATASTORE_NAME) ? selection(params, DATASTORE_NAME) : Selection.NONE,
                dimensions.contains(LABEL) ? selection(params, LABEL) : Selection.NONE);
    }

    /** True when no dimension has a value. */
    public boolean isEmpty() {
        return severity.isEmpty() && datastoreName.isEmpty() && label.isEmpty();
    }

    /**
     * {@code severity=<i|e>:..&datastoreName=<i|e>:..&label=<i|e>:..}, each list URL-encoded and comma-joined.
     * The lists are sorted, so the fragment depends on neither value nor parameter order. Hashed into the
     * cache key, never logged.
     */
    public String canonicalFragment() {
        return SEVERITY + "=" + severity.fragment()
                + "&" + DATASTORE_NAME + "=" + datastoreName.fragment()
                + "&" + LABEL + "=" + label.fragment();
    }

    private static void rejectIfPresent(MultiValueMap<String, String> params, String name) {
        if (params.containsKey(name)) {
            throw new DspmBadRequestException(name, name + " is not a supported parameter");
        }
    }

    private static Selection selection(MultiValueMap<String, String> params, String dimension) {
        String excludeName = dimension + EXCLUDE_SUFFIX;
        boolean included = params.containsKey(dimension);
        boolean excluded = params.containsKey(excludeName);
        if (included && excluded) {
            throw new DspmBadRequestException(excludeName, dimension + " cannot be included and excluded at once");
        }
        if (included) {
            return new Selection(false, values(params, dimension));
        }
        if (excluded) {
            return new Selection(true, values(params, excludeName));
        }
        return Selection.NONE;
    }

    // A private copy of EndpointFilters.values without its sensitives rule: build 3's FilterValues, which
    // would hold this loop once, is not on main (build 4 design 5.3 and 13).
    private static List<String> values(MultiValueMap<String, String> params, String name) {
        List<String> raw = params.get(name);
        if (raw == null) {
            return List.of();
        }
        TreeSet<String> values = new TreeSet<>();
        for (String joined : raw) {
            if (joined == null) {
                continue;
            }
            for (String part : joined.split(",", -1)) {
                String value = part.trim();
                if (value.isEmpty()) {
                    continue;
                }
                if (value.length() > MAX_VALUE_LENGTH || DatastoreParams.hasControlCharacter(value)) {
                    throw new DspmBadRequestException(name,
                            name + " values must be 1 to 128 characters without control characters");
                }
                values.add(value);
                if (values.size() > MAX_VALUES) {
                    throw tooManyOrNone(name);
                }
            }
        }
        if (values.isEmpty()) {
            throw tooManyOrNone(name);
        }
        return List.copyOf(values);
    }

    private static DspmBadRequestException tooManyOrNone(String name) {
        return new DspmBadRequestException(name, name + " needs 1 to 50 values");
    }
}
