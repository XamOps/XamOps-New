package com.xammer.cloud.service.matters;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A validated Remediation activity query (design 5.3): one page of runs, by row offset.
 *
 * <p>Built from values already checked by {@link DatastoreParams}.
 *
 * @param search     the trimmed search term, or null when absent
 * @param status     an upper-snake remediation status code, or null
 * @param actionType an upper-snake action code, or null
 * @param skip       row offset, 0..10,000
 * @param limit      page size, 1..100
 */
public record RemediationQuery(String search, String status, String actionType, int skip, int limit) {

    /** {@code skip}, {@code limit}, then {@code status}, {@code q} and {@code actionType} when set. */
    public Map<String, List<String>> toVendorParams() {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("skip", List.of(String.valueOf(skip)));
        params.put("limit", List.of(String.valueOf(limit)));
        QueryFragments.putIfSet(params, "status", status);
        QueryFragments.putIfSet(params, "q", search);
        QueryFragments.putIfSet(params, "actionType", actionType);
        return params;
    }

    /** {@code name=value&…} of the vendor params, values URL-encoded. Hashed into the cache key, never logged. */
    public String canonicalFragment() {
        return QueryFragments.canonical(toVendorParams());
    }
}
