package com.xammer.cloud.service;

import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.dto.devops.FacetValue;
import com.xammer.cloud.dto.devops.SearchQuery;
import com.xammer.cloud.dto.devops.SearchResult;
import com.xammer.cloud.dto.devops.SearchSuggestion;
import com.xammer.cloud.repository.DevOpsScriptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PostgreSQL-backed implementation of {@link IScriptSearchService}.
 *
 * <p>All search, autocomplete and faceting is computed directly against the
 * primary datastore ({@link DevOpsScriptRepository}); there is no external
 * search engine. The script catalogue is small, so candidate rows are loaded
 * (filtered by status at the database) and the text match, structured filters,
 * sorting and pagination are applied in memory.</p>
 *
 * <p>The index-maintenance methods ({@code indexScript}, {@code bulkIndexScripts},
 * {@code deleteFromIndex}, {@code reindexAll}) are retained as no-ops so existing
 * callers keep working — there is no separate index to maintain now that data
 * lives only in PostgreSQL.</p>
 *
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.3.2 (Advanced Filtering),
 *               REQ-2.6.1 (Search Performance)
 */
@Service
public class ScriptSearchService implements IScriptSearchService {

    private static final Logger logger = LoggerFactory.getLogger(ScriptSearchService.class);

    private static final int MAX_AUTOCOMPLETE = 10;

    @Autowired
    private DevOpsScriptRepository devOpsScriptRepository;

    // ----- Search operations -------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public SearchResult<DevOpsScript> search(SearchQuery query) {
        if (query == null) {
            return SearchResult.empty(0, 20);
        }

        long start = System.currentTimeMillis();
        try {
            List<DevOpsScript> matched = filteredCandidates(query);
            sortInPlace(matched, query.getSortBy());

            long total = matched.size();
            int from = Math.min(query.getPage() * query.getSize(), matched.size());
            int to = Math.min(from + query.getSize(), matched.size());
            List<DevOpsScript> pageItems = new ArrayList<>(matched.subList(from, to));

            long took = System.currentTimeMillis() - start;
            return new SearchResult<>(pageItems, total, query.getPage(), query.getSize(), took);
        } catch (Exception e) {
            logger.error("Script search failed for query '{}'", query.getText(), e);
            return SearchResult.empty(query.getPage(), query.getSize());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> autocomplete(String prefix, int limit) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return new ArrayList<>();
        }

        int cappedLimit = Math.min(limit <= 0 ? MAX_AUTOCOMPLETE : limit, MAX_AUTOCOMPLETE);
        final String needle = prefix.trim().toLowerCase(Locale.ROOT);

        try {
            return devOpsScriptRepository.findByStatus("published").stream()
                    .map(DevOpsScript::getName)
                    .filter(name -> name != null && name.toLowerCase(Locale.ROOT).startsWith(needle))
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .limit(cappedLimit)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Autocomplete failed for prefix '{}'", prefix, e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<SearchSuggestion> getSuggestions(String query) {
        return autocomplete(query, MAX_AUTOCOMPLETE).stream()
                .map(name -> new SearchSuggestion(name, "script"))
                .collect(Collectors.toList());
    }

    // ----- Index maintenance (no-ops; data lives only in PostgreSQL) ---------

    @Override
    public void indexScript(DevOpsScript script) {
        // No external search index to maintain.
    }

    @Override
    public void bulkIndexScripts(List<DevOpsScript> scripts) {
        // No external search index to maintain.
    }

    @Override
    public void deleteFromIndex(Long scriptId) {
        // No external search index to maintain.
    }

    @Override
    public void reindexAll() {
        // No external search index to maintain.
    }

    // ----- Faceted search ----------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Map<String, List<FacetValue>> getFacets(SearchQuery query) {
        Map<String, List<FacetValue>> facets = new LinkedHashMap<>();
        if (query == null) {
            return facets;
        }

        try {
            List<DevOpsScript> matched = filteredCandidates(query);
            facets.put("category", countSingle(matched, DevOpsScript::getCategory));
            facets.put("difficulty", countSingle(matched, DevOpsScript::getDifficulty));
            facets.put("cloudProvider", countMulti(matched, DevOpsScript::getCloudProviders));
            facets.put("tag", countMulti(matched, DevOpsScript::getTags));
            return facets;
        } catch (Exception e) {
            logger.error("Facet computation failed for query '{}'", query.getText(), e);
            return facets;
        }
    }

    // ----- Filtering ---------------------------------------------------------

    /** Load candidate scripts (filtered by status at the DB) and apply all filters in memory. */
    private List<DevOpsScript> filteredCandidates(SearchQuery query) {
        List<DevOpsScript> candidates =
                (query.getStatus() != null && !query.getStatus().isEmpty())
                        ? devOpsScriptRepository.findByStatus(query.getStatus())
                        : devOpsScriptRepository.findAll();

        return candidates.stream()
                .filter(s -> matchesText(s, query))
                .filter(s -> matchesAny(s.getCategory(), query.getCategories()))
                .filter(s -> matchesAnyInList(s.getCloudProviders(), query.getCloudProviders()))
                .filter(s -> matchesAny(s.getDifficulty(), query.getDifficulties()))
                .filter(s -> matchesAnyInList(s.getTags(), query.getTags()))
                .filter(s -> matchesMinRating(s, query.getMinRating()))
                .collect(Collectors.toList());
    }

    private boolean matchesText(DevOpsScript s, SearchQuery query) {
        if (query.isEmptyText()) {
            return true;
        }
        final String needle = query.getText().trim().toLowerCase(Locale.ROOT);
        if (contains(s.getName(), needle) || contains(s.getDescription(), needle)) {
            return true;
        }
        if (s.getTags() != null) {
            for (String tag : s.getTags()) {
                if (contains(tag, needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesMinRating(DevOpsScript s, Double minRating) {
        if (minRating == null) {
            return true;
        }
        return s.getAverageRating() != null && s.getAverageRating() >= minRating;
    }

    /** True when no filter values are supplied, or the single field value matches one of them (case-insensitive). */
    private boolean matchesAny(String fieldValue, List<String> filterValues) {
        if (filterValues == null || filterValues.isEmpty()) {
            return true;
        }
        if (fieldValue == null) {
            return false;
        }
        return filterValues.stream().anyMatch(v -> v != null && v.equalsIgnoreCase(fieldValue));
    }

    /** True when no filter values are supplied, or the multi-valued field shares at least one value (case-insensitive). */
    private boolean matchesAnyInList(List<String> fieldValues, List<String> filterValues) {
        if (filterValues == null || filterValues.isEmpty()) {
            return true;
        }
        if (fieldValues == null || fieldValues.isEmpty()) {
            return false;
        }
        for (String fv : fieldValues) {
            if (fv != null && filterValues.stream().anyMatch(v -> v != null && v.equalsIgnoreCase(fv))) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    // ----- Sorting -----------------------------------------------------------

    private void sortInPlace(List<DevOpsScript> scripts, String sortBy) {
        String key = sortBy == null ? "recent" : sortBy.toLowerCase(Locale.ROOT);
        switch (key) {
            case "popular":
                scripts.sort(Comparator.comparingInt(
                        (DevOpsScript s) -> s.getDownloadCount() == null ? 0 : s.getDownloadCount()).reversed());
                break;
            case "rating":
                scripts.sort(Comparator.comparingDouble(
                        (DevOpsScript s) -> s.getAverageRating() == null ? 0.0 : s.getAverageRating()).reversed());
                break;
            case "name":
                scripts.sort(Comparator.comparing(
                        s -> s.getName() == null ? "" : s.getName().toLowerCase(Locale.ROOT)));
                break;
            case "recent":
            default:
                // Most recently updated first; nulls sort last.
                scripts.sort(Comparator.comparing(DevOpsScript::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed());
                break;
        }
    }

    // ----- Facet counting ----------------------------------------------------

    private List<FacetValue> countSingle(List<DevOpsScript> scripts, Function<DevOpsScript, String> extractor) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DevOpsScript s : scripts) {
            String value = extractor.apply(s);
            if (value != null && !value.isEmpty()) {
                counts.merge(value, 1L, Long::sum);
            }
        }
        return toSortedFacets(counts);
    }

    private List<FacetValue> countMulti(List<DevOpsScript> scripts, Function<DevOpsScript, List<String>> extractor) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DevOpsScript s : scripts) {
            List<String> values = extractor.apply(s);
            if (values == null) {
                continue;
            }
            for (String value : values) {
                if (value != null && !value.isEmpty()) {
                    counts.merge(value, 1L, Long::sum);
                }
            }
        }
        return toSortedFacets(counts);
    }

    private List<FacetValue> toSortedFacets(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .map(e -> new FacetValue(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(FacetValue::getCount).reversed()
                        .thenComparing(FacetValue::getValue))
                .collect(Collectors.toList());
    }
}
