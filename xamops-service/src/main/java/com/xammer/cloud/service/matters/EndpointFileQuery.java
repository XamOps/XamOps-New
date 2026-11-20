package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A validated Endpoint File View query (design 5.3): the four filters, a search term and a page.
 *
 * @param filters the parsed {@link EndpointFilters}
 * @param search  the trimmed search term, or null when absent
 * @param skip    row offset, 0..10,000
 * @param limit   page size, 1..100
 */
public record EndpointFileQuery(EndpointFilters filters, String search, int skip, int limit) {

    /** {@code …/agent/{id}/files}: {@code skip}, {@code limit}, {@code search} when set, then the filters. */
    public Map<String, List<String>> toVendorParams(ObjectMapper objectMapper) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("skip", List.of(String.valueOf(skip)));
        params.put("limit", List.of(String.valueOf(limit)));
        if (search != null) {
            params.put("search", List.of(search));
        }
        params.putAll(filters.toVendorParams(objectMapper));
        return params;
    }

    /** The filters' fragment, then {@code &q=..&skip=..&limit=..}. Hashed into the cache key, never logged. */
    public String canonicalFragment() {
        return filters.canonicalFragment()
                + "&q=" + URLEncoder.encode(search == null ? "" : search, StandardCharsets.UTF_8)
                + "&skip=" + skip
                + "&limit=" + limit;
    }
}
