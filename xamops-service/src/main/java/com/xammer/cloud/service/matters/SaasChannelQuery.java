package com.xammer.cloud.service.matters;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A validated Slack channels query (design 5.3). The client adds {@code saasIntegrationId}.
 *
 * <p>{@code skip} is a page index ({@code page - 1}), as the console sends it (A11).
 *
 * @param search        the trimmed search term, or null when absent
 * @param channelType   a channel type code, or null
 * @param channelStatus a channel status code, or null
 * @param scanState     a scan state code, or null
 * @param isNew         the New filter, or null
 * @param page          1-based page
 * @param pageSize      1..100
 */
public record SaasChannelQuery(String search, String channelType, String channelStatus, String scanState,
                               Boolean isNew, int page, int pageSize) {

    /** {@code limit}, {@code skip}, then the set filters: search, channelType, channelStatus, scanState, isNew. */
    public Map<String, List<String>> toVendorParams() {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("limit", List.of(String.valueOf(pageSize)));
        params.put("skip", List.of(String.valueOf(page - 1)));
        QueryFragments.putIfSet(params, "search", search);
        QueryFragments.putIfSet(params, "channelType", channelType);
        QueryFragments.putIfSet(params, "channelStatus", channelStatus);
        QueryFragments.putIfSet(params, "scanState", scanState);
        QueryFragments.putIfSet(params, "isNew", isNew);
        return params;
    }

    /** {@code name=value&…} of the vendor params, values URL-encoded. Hashed into the cache key, never logged. */
    public String canonicalFragment() {
        return QueryFragments.canonical(toVendorParams());
    }
}
