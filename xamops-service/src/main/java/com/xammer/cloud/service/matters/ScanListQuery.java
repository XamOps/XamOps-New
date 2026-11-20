package com.xammer.cloud.service.matters;

import org.springframework.util.MultiValueMap;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A validated Scan Management list query (design 5.3): schedules, runs, a run's datastores or scopes.
 *
 * <p>Each {@link Kind} parses only its own filters; which parameter names a request may carry is checked
 * by the controller ({@code DatastoreParams.requireAllowed}) before {@link #from}.
 *
 * @param kind     which list
 * @param search   the trimmed search term, or null when absent
 * @param filters  the kind's set filters, in the kind's order (absent ones left out)
 * @param page     1-based page
 * @param pageSize 1..100
 */
public record ScanListQuery(Kind kind, String search, Map<String, String> filters, int page, int pageSize) {

    /** The platforms a schedule, run or scope can belong to. */
    public static final Set<String> PLATFORMS = Set.of("cloud", "on_prem", "endpoint");

    /** The lists and their filters, in vendor order. */
    public enum Kind {
        SCHEDULES(List.of("platform", "status", "coverage")),
        RUNS(List.of("coverage", "platform", "status", "trigger")),
        RUN_DATASTORES(List.of("account", "status")),
        SCOPES(List.of("platform"));

        private final List<String> filterNames;

        Kind(List<String> filterNames) {
            this.filterNames = filterNames;
        }

        /** The kind's filter parameter names, in vendor order. */
        public List<String> filterNames() {
            return filterNames;
        }
    }

    public ScanListQuery {
        filters = Collections.unmodifiableMap(new LinkedHashMap<>(filters == null ? Map.of() : filters));
    }

    /**
     * Parses {@code q}, {@code page}, {@code pageSize} and the kind's own filters: {@code platform} one of
     * cloud, on_prem, endpoint; {@code account} free text; {@code status}, {@code coverage}, {@code trigger}
     * upper-snake codes. A filter of another kind is not read.
     */
    public static ScanListQuery from(Kind kind, MultiValueMap<String, String> params) {
        String search = DatastoreParams.search(DatastoreParams.single(params, "q"));
        DatastoreParams.Paging paging = DatastoreParams.listPaging(
                DatastoreParams.single(params, "page"), DatastoreParams.single(params, "pageSize"));
        Map<String, String> filters = new LinkedHashMap<>();
        for (String name : kind.filterNames()) {
            String raw = DatastoreParams.single(params, name);
            String value = switch (name) {
                case "platform" -> DatastoreParams.oneOf(name, raw, PLATFORMS);
                case "account" -> DatastoreParams.freeText(name, raw);
                default -> DatastoreParams.code(name, raw);
            };
            if (value != null) {
                filters.put(name, value);
            }
        }
        return new ScanListQuery(kind, search, filters, paging.page(), paging.pageSize());
    }

    /** {@code page}, {@code page_size}, {@code q} when set, then the filters in the kind's order. */
    public Map<String, List<String>> toVendorParams() {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("page", List.of(String.valueOf(page)));
        params.put("page_size", List.of(String.valueOf(pageSize)));
        QueryFragments.putIfSet(params, "q", search);
        for (String name : kind.filterNames()) {
            QueryFragments.putIfSet(params, name, filters.get(name));
        }
        return params;
    }

    /** {@code name=value&…} of the vendor params, values URL-encoded. Hashed into the cache key, never logged. */
    public String canonicalFragment() {
        return QueryFragments.canonical(toVendorParams());
    }
}
