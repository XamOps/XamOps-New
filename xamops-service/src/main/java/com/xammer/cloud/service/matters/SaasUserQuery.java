package com.xammer.cloud.service.matters;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A validated SaaS scan-configuration users query (design 5.3). The client adds {@code saasIntegrationId}.
 *
 * <p>{@code skip} is a page index ({@code page - 1}), as the console sends it.
 *
 * @param driveType the scope's drive type code; required
 * @param search    the trimmed search term, or null when absent
 * @param status    ACTIVE or SUSPENDED, or null
 * @param domain    a domain name, or null
 * @param page      1-based page
 * @param pageSize  1..100
 */
public record SaasUserQuery(String driveType, String search, String status, String domain, int page, int pageSize) {

    public SaasUserQuery {
        if (driveType == null) {
            throw new IllegalArgumentException("driveType is required");
        }
    }

    /** {@code driveType}, {@code limit}, {@code skip}, then {@code search}, {@code status}, {@code domain} when set. */
    public Map<String, List<String>> toVendorParams() {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("driveType", List.of(driveType));
        params.put("limit", List.of(String.valueOf(pageSize)));
        params.put("skip", List.of(String.valueOf(page - 1)));
        QueryFragments.putIfSet(params, "search", search);
        QueryFragments.putIfSet(params, "status", status);
        QueryFragments.putIfSet(params, "domain", domain);
        return params;
    }

    /** {@code name=value&…} of the vendor params, values URL-encoded. Hashed into the cache key, never logged. */
    public String canonicalFragment() {
        return QueryFragments.canonical(toVendorParams());
    }
}
