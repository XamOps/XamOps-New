package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.MultiValueMap;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** The sensitives are upper-case codes; the other three any text (FilterValues, build 2's rule unchanged). */
    private static List<String> values(MultiValueMap<String, String> params, String name) {
        return FilterValues.parse(params, name,
                "sensitives".equals(name) ? FilterValues.Rule.CODE : FilterValues.Rule.TEXT);
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

    /** Puts {@code values} as one JSON array text under {@code name}, unless empty. The Files queries use it too. */
    static void putArray(Map<String, List<String>> params, String name, List<String> values,
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

    /** The values URL-encoded and comma-joined, for canonical fragments. The Files queries use it too. */
    static String join(List<String> values) {
        return values.stream()
                .map(value -> URLEncoder.encode(value, StandardCharsets.UTF_8))
                .collect(Collectors.joining(","));
    }
}
