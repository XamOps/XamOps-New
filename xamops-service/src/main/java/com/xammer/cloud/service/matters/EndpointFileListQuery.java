package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.MultiValueMap;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A validated Files page Endpoint query (F3; design 5.3 as changed by plan C6). There is no user filter.
 *
 * @param skip         row offset, 0..10,000
 * @param limit        page size, 1..100
 * @param search       the trimmed search term, or null
 * @param filters      build 2's four filters: sensitives, documentClass, category, dataElement
 * @param tags         Matters tags, sent as {@code tags}
 * @param uids         device UIDs, sent as {@code uids}
 * @param hostnames    host names, sent as {@code hostname}
 * @param lastModified {@code 48h}, {@code 7d} or {@code 30d}, or null
 */
public record EndpointFileListQuery(int skip, int limit, String search, EndpointFilters filters, List<String> tags,
                                    List<String> uids, List<String> hostnames, String lastModified) {

    /** The F3 parameter names. */
    public static final Set<String> PARAMETERS = Set.of("skip", "limit", "q", "sensitives", "documentClass",
            "category", "dataElement", "tag", "uid", "hostname", "lastModified");

    /** The Last Modified choices, in the filter's order. */
    public static final List<String> LAST_MODIFIED = List.of("48h", "7d", "30d");

    /**
     * Reads the query. {@code skip}, {@code limit}, {@code q} and {@code lastModified} may be given once; the
     * list filters follow {@link FilterValues}. Other names are left to the controller's {@code requireAllowed}.
     *
     * @throws DspmBadRequestException naming the parameter, never the value
     */
    public static EndpointFileListQuery parse(MultiValueMap<String, String> params) {
        return new EndpointFileListQuery(
                DatastoreParams.skip(DatastoreParams.single(params, "skip")),
                DatastoreParams.limit(DatastoreParams.single(params, "limit")),
                DatastoreParams.search(DatastoreParams.single(params, "q")),
                EndpointFilters.parse(params),
                FilterValues.parse(params, "tag", FilterValues.Rule.TEXT),
                FilterValues.parse(params, "uid", FilterValues.Rule.TEXT),
                FilterValues.parse(params, "hostname", FilterValues.Rule.TEXT),
                DatastoreParams.choice("lastModified", DatastoreParams.single(params, "lastModified"),
                        LAST_MODIFIED));
    }

    /**
     * {@code /agent/files}: {@code skip}, {@code limit}, {@code search} when set, build 2's filters
     * ({@code sensitives}, {@code subcategory}, {@code category}, {@code entity_name}), then {@code tags},
     * {@code uids} and {@code hostname} as JSON arrays when non-empty, then {@code last_modified} when set.
     */
    public Map<String, List<String>> toVendorParams(ObjectMapper objectMapper) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("skip", List.of(String.valueOf(skip)));
        params.put("limit", List.of(String.valueOf(limit)));
        if (search != null) {
            params.put("search", List.of(search));
        }
        params.putAll(filters.toVendorParams(objectMapper));
        EndpointFilters.putArray(params, "tags", tags, objectMapper);
        EndpointFilters.putArray(params, "uids", uids, objectMapper);
        EndpointFilters.putArray(params, "hostname", hostnames, objectMapper);
        if (lastModified != null) {
            params.put("last_modified", List.of(lastModified));
        }
        return params;
    }

    /**
     * Build 2's filters fragment, then {@code &tag=..&uid=..&hostname=..&lastModified=..&q=..&skip=..&limit=..}.
     * Hashed into the cache key, never logged.
     */
    public String canonicalFragment() {
        return filters.canonicalFragment()
                + "&tag=" + EndpointFilters.join(tags)
                + "&uid=" + EndpointFilters.join(uids)
                + "&hostname=" + EndpointFilters.join(hostnames)
                + "&lastModified=" + (lastModified == null ? "" : lastModified)
                + "&q=" + URLEncoder.encode(search == null ? "" : search, StandardCharsets.UTF_8)
                + "&skip=" + skip
                + "&limit=" + limit;
    }
}
