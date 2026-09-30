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
     * How the Cloud surface is scoped: by resource type, not by category.
     *
     * <p>The Cloud screen must cover cloud <em>and</em> on-prem datastores — Matters' own Cloud view
     * shows 68 rows where the API's {@code cloud} category alone counts 67, the extra row being a
     * self-hosted Postgres it lists with "self-hosted" in the account column.
     *
     * <p>This was originally expressed as {@code category=cloud,on_prem}, which silently returned
     * nothing. {@code category} is a <b>tab selector</b>, not one of the six multi-value filters
     * ({@code resource_type}, {@code provider}, {@code severity}, {@code scan_status},
     * {@code sensitive_type}, {@code integration_id}) — it accepts only {@code all}, {@code cloud},
     * {@code saas}, {@code endpoint} or {@code on_prem}. A comma-joined value is simply unrecognised,
     * and the API answers an unrecognised filter with an empty result set rather than a 4xx, so the
     * mistake surfaced as "this customer has no datastores" on a 200.
     *
     * <p>{@code resource_type=DATASTORE} is the correct expression of the same intent: both cloud and
     * on-prem datastores are {@code DATASTORE}, while SaaS is {@code SAAS_PRINCIPAL} and endpoints are
     * {@code ENDPOINT_HOST}. Measured against the live tenant it returns exactly 68 — one request, with
     * server-side paging, sorting and faceting intact.
     */
    public static final String CLOUD_RESOURCE_TYPE = "DATASTORE";

    /**
     * The three surfaces, each scoped by its {@code resource_type}.
     *
     * Scoped this way rather than by {@code category} for the reason above, and because it happens to be
     * exactly right for all three: every datastore (cloud and self-hosted) is {@code DATASTORE}, SaaS
     * accounts are {@code SAAS_PRINCIPAL}, managed devices are {@code ENDPOINT_HOST}. One parameter
     * covers all of them, with paging, sorting and faceting intact.
     */
    public enum Surface {
        CLOUD("cloud", CLOUD_RESOURCE_TYPE),
        SAAS("saas", "SAAS_PRINCIPAL"),
        ENDPOINT("endpoint", "ENDPOINT_HOST");

        private final String slug;
        private final String resourceType;

        Surface(String slug, String resourceType) {
            this.slug = slug;
            this.resourceType = resourceType;
        }

        public String slug() {
            return slug;
        }

        public String resourceType() {
            return resourceType;
        }

        /** Resolves a URL slug, or throws so an unknown surface is a 400 rather than an empty table. */
        public static Surface fromSlug(String value) {
            for (Surface surface : values()) {
                if (surface.slug.equalsIgnoreCase(value)) {
                    return surface;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown DSPM surface '" + value + "' — expected cloud, saas or endpoint");
        }
    }

    /** Sort fields the API honours. Anything else is ignored upstream, so it is rejected here. */
    private static final Set<String> SORTABLE = Set.of(
            "name", "risk_score", "sensitive_record_count", "last_modified_at", "severity");

    /**
     * Filter dimensions accepted from callers. Includes two the docs omit
     * ({@code data_element}, {@code lifecycle}).
     *
     * {@code resource_type} is deliberately absent: it is how the Cloud surface scopes itself
     * ({@link #CLOUD_RESOURCE_TYPE}), so letting a caller set it too would either be redundant
     * (selecting DATASTORE) or produce an empty table (selecting SAAS_PRINCIPAL on the Cloud tab). The
     * scope wins, and it is not offered as a filter.
     */
    private static final Set<String> FILTER_DIMENSIONS = Set.of(
            "provider", "severity", "scan_status", "sensitive_type",
            "integration_id", "data_element", "lifecycle");

    /** The vendor's ceiling. Larger values are clamped upstream; clamped here too so paging maths agree. */
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final String DEFAULT_SORT = "-risk_score";

    /**
     * Tab selector, or null. Single-valued only — see {@link #CLOUD_RESOURCE_TYPE} for why a
     * comma-joined value cannot be used here.
     */
    private final String category;

    /**
     * Fixed {@code resource_type} scope, or null. Set instead of {@link #category} for the Cloud
     * surface, and not overridable by a caller's filters.
     */
    private final String resourceTypeScope;

    private final String search;
    private final String sort;
    private final int page;
    private final int pageSize;
    private final Map<String, List<String>> filters;

    private DspmAssetQuery(String category, String resourceTypeScope, String search, String sort,
                           int page, int pageSize, Map<String, List<String>> filters) {
        this.category = category;
        this.resourceTypeScope = resourceTypeScope;
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
    public static DspmAssetQuery of(String category, String resourceTypeScope, String search, String sort,
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
        String resolvedCategory = (category == null || category.isBlank()) ? null : category.trim();

        return new DspmAssetQuery(resolvedCategory, resourceTypeScope, resolvedSearch, resolvedSort,
                resolvedPage, resolvedPageSize, resolvedFilters);
    }

    /**
     * A query for one surface.
     *
     * Scoped by {@code resource_type} rather than {@code category} — see {@link #CLOUD_RESOURCE_TYPE}.
     */
    public static DspmAssetQuery forSurface(Surface surface, String search, String sort,
                                            Integer page, Integer pageSize,
                                            Map<String, List<String>> rawFilters) {
        return of(null, surface.resourceType(), search, sort, page, pageSize, rawFilters);
    }

    /**
     * The Cloud surface: every datastore, cloud-hosted and self-hosted alike.
     *
     * Kept as a named convenience because it is the one surface with a non-obvious scope — "cloud" in the
     * UI covers self-hosted datastores too, matching the vendor's own Cloud screen.
     */
    public static DspmAssetQuery cloud(String search, String sort, Integer page, Integer pageSize,
                                       Map<String, List<String>> rawFilters) {
        return forSurface(Surface.CLOUD, search, sort, page, pageSize, rawFilters);
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
        // Both omitted when null. Sending category="" or an unrecognised value returns an empty result
        // set rather than an error, so an accidentally-blank scope would read as "no assets".
        if (category != null) {
            params.put("category", List.of(category));
        }
        if (resourceTypeScope != null) {
            params.put("resource_type", List.of(resourceTypeScope));
        }
        if (search != null) {
            params.put("q", List.of(search));
        }
        // Joined with commas rather than repeated: both forms are accepted for the six multi-value
        // dimensions, and one value per dimension keeps the URL shorter and the logs readable.
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
        key.append(scopeKey()).append('|')
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
        key.append(scopeKey()).append('|').append(search == null ? "" : search);
        filters.forEach((dimension, values) ->
                key.append('|').append(dimension).append('=').append(String.join(",", values)));
        return key.toString();
    }

    /**
     * The scope, as a cache-key fragment.
     *
     * Both parts are included so a category-scoped query and a resource-type-scoped one can never
     * collide on the same key — which would let the Cloud tab serve a SaaS tab's cached page.
     */
    private String scopeKey() {
        return (category == null ? "" : category) + ":" + (resourceTypeScope == null ? "" : resourceTypeScope);
    }

    /** The fixed {@code resource_type} scope, or null for a category-scoped query. */
    public String resourceTypeScope() {
        return resourceTypeScope;
    }

    public int page() {
        return page;
    }

    public int pageSize() {
        return pageSize;
    }
}
