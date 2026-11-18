package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.MultiValueMap;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The four Endpoint drawer filters (design 5.3): {@code sensitives}, {@code documentClass},
 * {@code category} and {@code dataElement}, each a sorted, de-duplicated list.
 *
 * <p>Values are checked for format only, as build 1's list filters are: a well-formed value with no
 * match returns zero rows. The vendor takes each filter as a JSON array of strings.
 */
public record EndpointFilters(List<String> sensitives, List<String> documentClass, List<String> category,
                              List<String> dataElement) {

    /** No filter at all: the drawer's unfiltered calls. */
    public static final EndpointFilters NONE = new EndpointFilters(List.of(), List.of(), List.of(), List.of());

    /** Most distinct values one filter may carry. */
    public static final int MAX_VALUES = 50;

    /** Longest single value. */
    public static final int MAX_VALUE_LENGTH = 128;

    /** A sensitive-category code such as {@code PII} or {@code FINANCIAL_DATA}. */
    private static final Pattern SENSITIVE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /**
     * Reads the four filters from the request. Every occurrence of a parameter is split on {@code ','},
     * each value trimmed, empties dropped, the rest de-duplicated and sorted. A parameter that is present
     * must leave 1..50 values, so {@code documentClass=,} is a 400; an absent one is an empty list. Other
     * parameter names are ignored here: the controller's {@code requireAllowed} has checked them.
     *
     * @throws DspmBadRequestException naming the parameter, never the value
     */
    public static EndpointFilters parse(MultiValueMap<String, String> params) {
        return new EndpointFilters(
                values(params, "sensitives"),
                values(params, "documentClass"),
                values(params, "category"),
                values(params, "dataElement"));
    }

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
                if ("sensitives".equals(name) && !SENSITIVE_CODE.matcher(value).matches()) {
                    throw new DspmBadRequestException(name,
                            name + " values must be upper-case codes of letters, digits and underscores");
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

    /**
     * The vendor params, for each non-empty filter in this order: {@code sensitives},
     * {@code subcategory} (from documentClass), {@code category}, {@code entity_name} (from
     * dataElement), each one JSON array text such as {@code ["it & technical"]}.
     */
    public Map<String, List<String>> toVendorParams(ObjectMapper objectMapper) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        putArray(params, "sensitives", sensitives, objectMapper);
        putArray(params, "subcategory", documentClass, objectMapper);
        putArray(params, "category", category, objectMapper);
        putArray(params, "entity_name", dataElement, objectMapper);
        return params;
    }

    private static void putArray(Map<String, List<String>> params, String name, List<String> values,
                                 ObjectMapper objectMapper) {
        if (values.isEmpty()) {
            return;
        }
        try {
            params.put(name, List.of(objectMapper.writeValueAsString(values)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a filter as a JSON array", e);
        }
    }

    /**
     * {@code sensitives=..&documentClass=..&category=..&dataElement=..}, each list URL-encoded and
     * comma-joined. The lists are sorted, so the fragment depends on neither value nor parameter order.
     * Hashed into the cache key, never logged.
     */
    public String canonicalFragment() {
        return "sensitives=" + join(sensitives)
                + "&documentClass=" + join(documentClass)
                + "&category=" + join(category)
                + "&dataElement=" + join(dataElement);
    }

    private static String join(List<String> values) {
        return values.stream()
                .map(value -> URLEncoder.encode(value, StandardCharsets.UTF_8))
                .collect(Collectors.joining(","));
    }
}
