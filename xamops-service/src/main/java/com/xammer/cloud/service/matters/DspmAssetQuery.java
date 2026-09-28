package com.xammer.cloud.service.matters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A validated asset query, and the one place request parameters are turned into vendor parameters.
 *
 * <h2>Why validate at all, given the API does not</h2>
 * The upstream is forgiving in ways that hide mistakes rather than surfacing them: an unknown
 * {@code sort} value is ignored and the default ordering applied, an unknown {@code provider} yields an
 * empty result set, and a {@code page_size} over the maximum is clamped. All three return {@code 200}.
 * Verified live.
 *
 * So a typo in a sort field looks like "the sort button does nothing" and a typo in a filter looks like
 * "this customer has no assets". Rejecting unknown sort fields here and clamping page size ourselves
 * keeps our own pagination arithmetic honest and turns those silent behaviours into predictable ones.
 *
 * <h2>Dimension allow-list</h2>
 * Filters are restricted to the dimensions the live API actually accepts. Passing an arbitrary
 * caller-supplied parameter name straight through would let a request reach undocumented vendor
 * behaviour we have not tested, and the allow-list is also what keeps the cache key bounded.
 */
public final class DspmAssetQuery {

    /**
     * Categories that make up the Cloud surface.
     *
     * Both, not just {@code cloud}. Matters' own Cloud screen shows 68 rows against an API
     * {@code cloud} count of 67 — the difference is the single self-hosted Postgres datastore, which the
     * vendor lists under Cloud with "self-hosted" in the account column. Sending only {@code cloud}
     * would quietly drop on-prem datastores that a customer can see in the vendor console.
     */
    public static final String CATEGORY_CLOUD = "cloud,on_prem";

    /** Sort fields the API honours. Anything else is ignored upstream, so it is rejected here. */
    private static final Set<String> SORTABLE = Set.of(
            "name", "risk_score", "sensitive_record_count", "last_modified_at", "severity");

    /** Filter dimensions accepted from callers. Includes the three the docs omit. */
    private static final Set<String> FILTER_DIMENSIONS = Set.of(
            "provider", "resource_type", "severity", "scan_status", "sensitive_type",
            "integration_id", "data_element", "lifecycle");

    /** The vendor's ceiling. Larger values are clamped upstream; clamped here too so paging maths agree. */
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final String DEFAULT_SORT = "-risk_score";

    private final String category;
    private final String search;
    private final String sort;
    private final int page;
    private final int pageSize;
    private final Map<String, List<String>> filters;

    private DspmAssetQuery(String category, String search, String sort, int page, int pageSize,
                           Map<String, List<String>> filters) {
        this.category = category;
        this.search = search;
        this.sort = sort;
        this.page = page;
        this.pageSize = pageSize;
        this.filters = filters;
    }

    /**
     * Builds a query from raw request input.
     *
     * @param rawFilters dimension → values, as supplied by the caller. Unknown dimensions are dropped
     *                   rather than rejected, so a stale bookmark carrying a removed filter still
     *                   returns results instead of a 400.
     */
    public static DspmAssetQuery of(String category, String search, String sort,
                                    Integer page, Integer pageSize,
                                    Map<String, List<String>> rawFilters) {

        // Default descending risk: the highest-risk datastores are what a DSPM user opens the page for,
        // and the vendor's default (name ascending) puts a bucket called "12345" first.
        String resolvedSort = normaliseSort(sort);

        int resolvedPage = (page == null || page < 1) ? 1 : page;
        int resolvedPageSize = (pageSize == null || pageSize < 1)
                ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);

        Map<String, List<String>> resolvedFilters = new TreeMap<>();
        if (rawFilters != null) {
            rawFilters.forEach((dimension, values) -> {
                if (dimension == null || !FILTER_DIMENSIONS.contains(dimension) || values == null) {
                    return;
                }
                List<String> cleaned = new ArrayList<>();
                for (String value : values) {
                    if (value == null) {
                        continue;
                    }
                    // Values may arrive either repeated or comma-joined; both are valid upstream, so
                    // both are accepted and normalised to one list.
                    for (String part : value.split(",")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty() && !cleaned.contains(trimmed)) {
                            cleaned.add(trimmed);
                        }
                    }
                }
                if (!cleaned.isEmpty()) {
                    // Sorted so two requests selecting the same values in a different order produce the
                    // same cache key instead of two entries holding identical data.
                    cleaned.sort(String::compareTo);
                    resolvedFilters.put(dimension, cleaned);
                }
            });
        }

        String resolvedSearch = (search == null || search.isBlank()) ? null : search.trim();
        String resolvedCategory = (category == null || category.isBlank()) ? CATEGORY_CLOUD : category;

        return new DspmAssetQuery(resolvedCategory, resolvedSearch, resolvedSort,
                resolvedPage, resolvedPageSize, resolvedFilters);
    }

    /** Convenience for the Cloud surface. */
    public static DspmAssetQuery cloud(String search, String sort, Integer page, Integer pageSize,
                                       Map<String, List<String>> rawFilters) {
        return of(CATEGORY_CLOUD, search, sort, page, pageSize, rawFilters);
    }

    /**
     * Validates a sort expression, falling back to the default.
     *
     * A leading {@code -} means descending and is preserved; the field itself must be one the API
     * actually orders by.
     */
    private static String normaliseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return DEFAULT_SORT;
        }
        String value = sort.trim();
        boolean descending = value.startsWith("-");
        String field = descending ? value.substring(1) : value;
        if (!SORTABLE.contains(field.toLowerCase(Locale.ROOT))) {
            return DEFAULT_SORT;
        }
        return descending ? "-" + field : field;
    }

    /** Vendor query parameters for {@code /assets}. */
    public Map<String, List<String>> toListParams() {
        Map<String, List<String>> params = toFilterParams();
        params.put("page", List.of(String.valueOf(page)));
        params.put("page_size", List.of(String.valueOf(pageSize)));
        params.put("sort", List.of(sort));
        return params;
    }

    /**
     * Vendor query parameters for {@code /assets/filters}.
     *
     * The same filters, without paging or sort — those are ignored upstream. Sending identical filters
     * to both endpoints is what makes the disjunctive faceting work: the rail keeps showing every option
     * in a dimension the user has already selected from, instead of collapsing to that selection.
     */
    public Map<String, List<String>> toFilterParams() {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("category", List.of(category));
        if (search != null) {
            params.put("q", List.of(search));
        }
        // Joined with commas rather than repeated: both forms are accepted, and one value per dimension
        // keeps the URL shorter and the logs readable.
        filters.forEach((dimension, values) -> params.put(dimension, List.of(String.join(",", values))));
        return params;
    }

    /**
     * Deterministic cache-key fragment.
     *
     * Built from the normalised values rather than the raw request, so equivalent requests that differ
     * only in filter order, casing of a sort field, or an out-of-range page size share one entry. Filters
     * are held in a {@link TreeMap} and their values sorted for exactly this reason.
     */
    public String cacheFragment() {
        StringBuilder key = new StringBuilder();
        key.append(category).append('|')
           .append(search == null ? "" : search).append('|')
           .append(sort).append('|')
           .append(page).append('|')
           .append(pageSize);
        filters.forEach((dimension, values) ->
                key.append('|').append(dimension).append('=').append(String.join(",", values)));
        return key.toString();
    }

    /** Cache-key fragment for the filter call, which ignores paging and sort. */
    public String filterCacheFragment() {
        StringBuilder key = new StringBuilder();
        key.append(category).append('|').append(search == null ? "" : search);
        filters.forEach((dimension, values) ->
                key.append('|').append(dimension).append('=').append(String.join(",", values)));
        return key.toString();
    }

    public int page() {
        return page;
    }

    public int pageSize() {
        return pageSize;
    }
}
