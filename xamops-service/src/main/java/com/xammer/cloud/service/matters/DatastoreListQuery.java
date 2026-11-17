package com.xammer.cloud.service.matters;

import org.springframework.util.MultiValueMap;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A validated Datastores list or filter-options query (design 5.3.3).
 *
 * <p>Unlike the older {@link DspmAssetQuery}, which drops what it does not recognise, this one rejects:
 * an unknown parameter, a dimension outside the tab's allow-list, a malformed value or a repeated
 * control parameter is a 400. The vendor answers an unknown filter with "200 and zero rows", so
 * silently passing one through would show an empty table instead of an error.
 *
 * <p>The list and its facets are built from the same {@link #toVendorFilterParams()}, so both always
 * carry identical filter terms.
 */
public final class DatastoreListQuery {

    public static final String DEFAULT_SORT = "-severity";

    public static final Set<String> SORTS = Set.of(
            "severity", "-severity", "name", "-name", "sensitive_record_count", "-sensitive_record_count");

    /** Most distinct values one dimension may carry. */
    public static final int MAX_VALUES = 50;

    private static final Set<String> LIST_CONTROLS = Set.of("tab", "page", "pageSize", "sort", "q");
    private static final Set<String> FILTER_CONTROLS = Set.of("tab", "q");

    /** One dimension's selection: include or exclude the listed values (sorted, de-duplicated). */
    public record Selection(boolean exclude, List<String> values) {
    }

    private final DatastoreTab tab;
    private final int page;
    private final int pageSize;
    private final String sort;
    private final String search;
    private final SortedMap<String, Selection> filters;

    private DatastoreListQuery(DatastoreTab tab, int page, int pageSize, String sort,
                               String search, SortedMap<String, Selection> filters) {
        this.tab = tab;
        this.page = page;
        this.pageSize = pageSize;
        this.sort = sort;
        this.search = search;
        this.filters = Collections.unmodifiableSortedMap(filters);
    }

    /** {@code GET /api/dspm/datastores}: {@code tab, page, pageSize, sort, q} and the tab's dimensions. */
    public static DatastoreListQuery forList(MultiValueMap<String, String> params) {
        DatastoreTab tab = parseTab(params);
        SortedMap<String, Selection> filters = parseFilters(params, tab, LIST_CONTROLS);
        DatastoreParams.Paging paging = DatastoreParams.listPaging(
                DatastoreParams.single(params, "page"), DatastoreParams.single(params, "pageSize"));
        String sort = DatastoreParams.single(params, "sort");
        if (sort == null) {
            sort = DEFAULT_SORT;
        } else if (!SORTS.contains(sort)) {
            throw new DspmBadRequestException("sort", "sort must be one of severity, -severity, name, -name, "
                    + "sensitive_record_count, -sensitive_record_count");
        }
        String search = DatastoreParams.search(DatastoreParams.single(params, "q"));
        return new DatastoreListQuery(tab, paging.page(), paging.pageSize(), sort, search, filters);
    }

    /** {@code GET /api/dspm/datastores/filter-options}: {@code tab, q} and the tab's dimensions. */
    public static DatastoreListQuery forFilters(MultiValueMap<String, String> params) {
        DatastoreTab tab = parseTab(params);
        SortedMap<String, Selection> filters = parseFilters(params, tab, FILTER_CONTROLS);
        String search = DatastoreParams.search(DatastoreParams.single(params, "q"));
        return new DatastoreListQuery(tab, DatastoreParams.DEFAULT_PAGE,
                DatastoreParams.DEFAULT_PAGE_SIZE, DEFAULT_SORT, search, filters);
    }

    /**
     * The unfiltered facets of a tab, for the KPI endpoint. Its fragment equals that of an unfiltered
     * filter-options request, so both share one cache entry.
     */
    public static DatastoreListQuery unfilteredFacets(DatastoreTab tab) {
        return new DatastoreListQuery(tab, DatastoreParams.DEFAULT_PAGE,
                DatastoreParams.DEFAULT_PAGE_SIZE, DEFAULT_SORT, null, new TreeMap<>());
    }

    // ── parsing ──────────────────────────────────────────────────────────────────────────────────

    private static DatastoreTab parseTab(MultiValueMap<String, String> params) {
        String raw = DatastoreParams.single(params, "tab");
        return raw == null ? DatastoreTab.ALL : DatastoreTab.fromSlug(raw);
    }

    private static SortedMap<String, Selection> parseFilters(MultiValueMap<String, String> params,
                                                             DatastoreTab tab, Set<String> controls) {
        // First pass: every name must be a control parameter or a dimension of this tab.
        for (String name : params.keySet()) {
            if (controls.contains(name)) {
                continue;
            }
            boolean exclude = isExcludeName(name);
            DatastoreFilterDimension dimension = DatastoreFilterDimension.fromKey(baseKey(name));
            if (dimension == null || !dimension.allowedOn(tab)) {
                throw new DspmBadRequestException(name, name + " is not a supported parameter for this tab");
            }
            if (exclude && !dimension.excludable()) {
                throw new DspmBadRequestException(name,
                        dimension.key() + " cannot be excluded");
            }
        }

        SortedMap<String, Selection> filters = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            String name = entry.getKey();
            if (controls.contains(name)) {
                continue;
            }
            boolean exclude = isExcludeName(name);
            DatastoreFilterDimension dimension = DatastoreFilterDimension.fromKey(baseKey(name));
            if (exclude && params.containsKey(dimension.key())) {
                throw new DspmBadRequestException(name,
                        name + " cannot be combined with " + dimension.key());
            }
            filters.put(dimension.key(), new Selection(exclude, parseValues(name, dimension, entry.getValue())));
        }
        return filters;
    }

    private static List<String> parseValues(String name, DatastoreFilterDimension dimension, List<String> raw) {
        TreeSet<String> values = new TreeSet<>();
        if (raw != null) {
            for (String joined : raw) {
                if (joined == null) {
                    continue;
                }
                for (String part : joined.split(",", -1)) {
                    String value = part.trim();
                    if (!value.isEmpty()) {
                        values.add(dimension.normalise(name, value));
                    }
                }
            }
        }
        if (values.isEmpty() || values.size() > MAX_VALUES) {
            throw new DspmBadRequestException(name, name + " needs 1 to 50 values");
        }
        return List.copyOf(values);
    }

    private static boolean isExcludeName(String name) {
        return name.endsWith(DatastoreFilterDimension.EXCLUDE_SUFFIX);
    }

    private static String baseKey(String name) {
        return isExcludeName(name)
                ? name.substring(0, name.length() - DatastoreFilterDimension.EXCLUDE_SUFFIX.length())
                : name;
    }

    // ── accessors ────────────────────────────────────────────────────────────────────────────────

    public DatastoreTab tab() {
        return tab;
    }

    /** The validated page (list only; 1 otherwise). */
    public int page() {
        return page;
    }

    /** The validated page size (list only; 10 otherwise). */
    public int pageSize() {
        return pageSize;
    }

    public String sort() {
        return sort;
    }

    /** The trimmed search term, or null when absent. */
    public String search() {
        return search;
    }

    /** Dimension key → selection, sorted by key. */
    public SortedMap<String, Selection> filters() {
        return filters;
    }

    // ── vendor params ────────────────────────────────────────────────────────────────────────────

    /** {@code /api/v1/assets}: the filter params plus {@code page}, {@code page_size} and {@code sort}. */
    public Map<String, List<String>> toVendorListParams() {
        Map<String, List<String>> params = toVendorFilterParams();
        params.put("page", List.of(String.valueOf(page)));
        params.put("page_size", List.of(String.valueOf(pageSize)));
        params.put("sort", List.of(sort));
        return params;
    }

    /**
     * {@code /api/v1/assets/filters}: {@code category} (omitted for all), {@code q}, then one
     * {@code <dim>} or {@code <dim>_exclude} per selection with its values joined by commas.
     */
    public Map<String, List<String>> toVendorFilterParams() {
        Map<String, List<String>> params = new LinkedHashMap<>();
        if (tab.vendorCategory() != null) {
            params.put("category", List.of(tab.vendorCategory()));
        }
        if (search != null) {
            params.put("q", List.of(search));
        }
        filters.forEach((key, selection) ->
                params.put(paramName(key, selection), List.of(String.join(",", selection.values()))));
        return params;
    }

    // ── cache identity ───────────────────────────────────────────────────────────────────────────

    /** {@code q=..&sort=..&page=..&pageSize=..&<dims>}. Hashed into the cache key, never logged. */
    public String canonicalListFragment() {
        StringBuilder out = new StringBuilder();
        out.append("q=").append(encode(search == null ? "" : search))
                .append("&sort=").append(encode(sort))
                .append("&page=").append(page)
                .append("&pageSize=").append(pageSize);
        appendDimensions(out);
        return out.toString();
    }

    /** {@code q=..&<dims>}. Hashed into the cache key, never logged. */
    public String canonicalFacetsFragment() {
        StringBuilder out = new StringBuilder();
        out.append("q=").append(encode(search == null ? "" : search));
        appendDimensions(out);
        return out.toString();
    }

    private void appendDimensions(StringBuilder out) {
        filters.forEach((key, selection) -> out.append('&').append(paramName(key, selection)).append('=')
                .append(selection.values().stream().map(DatastoreListQuery::encode)
                        .collect(Collectors.joining(","))));
    }

    private static String paramName(String key, Selection selection) {
        return selection.exclude() ? key + DatastoreFilterDimension.EXCLUDE_SUFFIX : key;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
