package com.xammer.cloud.service.matters;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * One validated request for a policy or datastore row of the Data Lineage page (build 4 design 5.3).
 *
 * @param search  trimmed search text, or null for none
 * @param skip    0..10,000
 * @param limit   1..50
 * @param filters the More filters; {@link LineageFilters#NONE} for none
 */
public record LineageRowQuery(String search, int skip, int limit, LineageFilters filters) {

    public LineageRowQuery {
        filters = filters == null ? LineageFilters.NONE : filters;
    }

    /**
     * {@code q=<URL-encoded search or empty>&skip=<n>&limit=<n>&} and the filters' fragment. Holds customer
     * text: hashed into the cache key, never logged.
     */
    public String canonicalFragment() {
        return "q=" + (search == null ? "" : URLEncoder.encode(search, StandardCharsets.UTF_8))
                + "&skip=" + skip
                + "&limit=" + limit
                + "&" + filters.canonicalFragment();
    }
}
