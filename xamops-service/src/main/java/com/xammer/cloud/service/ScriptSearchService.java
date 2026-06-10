package com.xammer.cloud.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.domain.elasticsearch.DevOpsScriptDocument;
import com.xammer.cloud.dto.devops.FacetValue;
import com.xammer.cloud.dto.devops.SearchQuery;
import com.xammer.cloud.dto.devops.SearchResult;
import com.xammer.cloud.dto.devops.SearchSuggestion;
import com.xammer.cloud.repository.DevOpsScriptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Elasticsearch-backed implementation of {@link IScriptSearchService}.
 *
 * <p>Search combines a fuzzy multi-match full-text query (name, description,
 * tags) with structured term/range filters, then hydrates the matched
 * documents into full {@link DevOpsScript} entities from the primary datastore
 * while preserving the Elasticsearch sort order.</p>
 *
 * <p>Index maintenance (index / bulk index / delete / reindex) is delegated to
 * the existing {@link DevOpsScriptIndexingService} to avoid duplicating the
 * indexing logic.</p>
 *
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.3.2 (Advanced Filtering),
 *               REQ-2.6.1 (Search Performance)
 */
@Service
public class ScriptSearchService implements IScriptSearchService {

    private static final Logger logger = LoggerFactory.getLogger(ScriptSearchService.class);

    /** Upper bound on documents scanned when computing in-memory facet counts. */
    private static final int FACET_SCAN_LIMIT = 10000;
    private static final int MAX_AUTOCOMPLETE = 10;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private DevOpsScriptRepository devOpsScriptRepository;

    @Autowired
    private DevOpsScriptIndexingService indexingService;

    // ----- Search operations -------------------------------------------------

    @Override
    public SearchResult<DevOpsScript> search(SearchQuery query) {
        if (query == null) {
            return SearchResult.empty(0, 20);
        }

        long start = System.currentTimeMillis();
        try {
            NativeQuery nativeQuery = new NativeQueryBuilder()
                    .withQuery(buildBaseQuery(query))
                    .withPageable(PageRequest.of(query.getPage(), query.getSize()))
                    .withSort(buildSort(query.getSortBy()))
                    .build();

            SearchHits<DevOpsScriptDocument> hits =
                    elasticsearchOperations.search(nativeQuery, DevOpsScriptDocument.class);

            // Extract matched ids preserving Elasticsearch sort order.
            List<Long> orderedIds = hits.getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .map(DevOpsScriptDocument::getId)
                    .collect(Collectors.toList());

            List<DevOpsScript> ordered = hydrateInOrder(orderedIds);

            // "name" cannot be sorted on the analyzed text field, so order the
            // returned page in memory for that option.
            if ("name".equalsIgnoreCase(query.getSortBy())) {
                ordered.sort(Comparator.comparing(
                        s -> s.getName() == null ? "" : s.getName().toLowerCase()));
            }

            long took = System.currentTimeMillis() - start;
            return new SearchResult<>(ordered, hits.getTotalHits(),
                    query.getPage(), query.getSize(), took);
        } catch (Exception e) {
            logger.error("Elasticsearch search failed for query '{}'", query.getText(), e);
            // Graceful degradation: return an empty result rather than failing the request.
            return SearchResult.empty(query.getPage(), query.getSize());
        }
    }

    @Override
    public List<String> autocomplete(String prefix, int limit) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return new ArrayList<>();
        }

        int cappedLimit = Math.min(limit <= 0 ? MAX_AUTOCOMPLETE : limit, MAX_AUTOCOMPLETE);
        final String text = prefix.trim();

        try {
            Query prefixQuery = Query.of(q -> q.bool(b -> b
                    .must(m -> m.matchPhrasePrefix(mpp -> mpp.field("name").query(text)))
                    .filter(f -> f.term(t -> t.field("status").value("published")))));

            NativeQuery nativeQuery = new NativeQueryBuilder()
                    .withQuery(prefixQuery)
                    .withPageable(PageRequest.of(0, cappedLimit))
                    .build();

            SearchHits<DevOpsScriptDocument> hits =
                    elasticsearchOperations.search(nativeQuery, DevOpsScriptDocument.class);

            return hits.getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .map(DevOpsScriptDocument::getName)
                    .filter(name -> name != null && !name.isEmpty())
                    .distinct()
                    .limit(cappedLimit)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Autocomplete failed for prefix '{}'", text, e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<SearchSuggestion> getSuggestions(String query) {
        return autocomplete(query, MAX_AUTOCOMPLETE).stream()
                .map(name -> new SearchSuggestion(name, "script"))
                .collect(Collectors.toList());
    }

    // ----- Indexing operations (delegated) -----------------------------------

    @Override
    public void indexScript(DevOpsScript script) {
        indexingService.indexScript(script);
    }

    @Override
    public void bulkIndexScripts(List<DevOpsScript> scripts) {
        indexingService.bulkIndexScripts(scripts);
    }

    @Override
    public void deleteFromIndex(Long scriptId) {
        indexingService.deleteFromIndex(scriptId);
    }

    @Override
    public void reindexAll() {
        indexingService.reindexAll();
    }

    // ----- Faceted search ----------------------------------------------------

    @Override
    public Map<String, List<FacetValue>> getFacets(SearchQuery query) {
        Map<String, List<FacetValue>> facets = new LinkedHashMap<>();
        if (query == null) {
            return facets;
        }

        try {
            NativeQuery nativeQuery = new NativeQueryBuilder()
                    .withQuery(buildBaseQuery(query))
                    .withMaxResults(FACET_SCAN_LIMIT)
                    .withPageable(PageRequest.of(0, FACET_SCAN_LIMIT))
                    .build();

            SearchHits<DevOpsScriptDocument> hits =
                    elasticsearchOperations.search(nativeQuery, DevOpsScriptDocument.class);

            List<DevOpsScriptDocument> docs = hits.getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .collect(Collectors.toList());

            facets.put("category", countSingle(docs, DevOpsScriptDocument::getCategory));
            facets.put("difficulty", countSingle(docs, DevOpsScriptDocument::getDifficulty));
            facets.put("cloudProvider", countMulti(docs, DevOpsScriptDocument::getCloudProviders));
            facets.put("tag", countMulti(docs, DevOpsScriptDocument::getTags));

            return facets;
        } catch (Exception e) {
            logger.error("Facet computation failed for query '{}'", query.getText(), e);
            return facets;
        }
    }

    // ----- Query building helpers --------------------------------------------

    /**
     * Build the combined full-text + filter query shared by search and facets.
     * Uses a bool query: a fuzzy multi-match (or match_all when no text) in the
     * {@code must} clause and term/range filters in the {@code filter} clause.
     */
    private Query buildBaseQuery(SearchQuery query) {
        return Query.of(q -> q.bool(b -> {
            // Full-text component with AUTO fuzziness for typo tolerance.
            if (query.isEmptyText()) {
                b.must(m -> m.matchAll(ma -> ma));
            } else {
                final String text = query.getText().trim();
                b.must(m -> m.multiMatch(mm -> mm
                        .query(text)
                        .fields("name^3", "description^2", "tags^2")
                        .fuzziness("AUTO")));
            }

            // Status filter (only published scripts surface to users by default).
            if (query.getStatus() != null && !query.getStatus().isEmpty()) {
                final String status = query.getStatus();
                b.filter(f -> f.term(t -> t.field("status").value(status)));
            }

            // Multi-value structured filters (AND across fields, OR within a field).
            if (!query.getCategories().isEmpty()) {
                b.filter(anyOf("category", query.getCategories()));
            }
            if (!query.getCloudProviders().isEmpty()) {
                b.filter(anyOf("cloudProviders", query.getCloudProviders()));
            }
            if (!query.getDifficulties().isEmpty()) {
                b.filter(anyOf("difficulty", query.getDifficulties()));
            }
            if (!query.getTags().isEmpty()) {
                b.filter(anyOf("tags", query.getTags()));
            }

            // Minimum rating range filter.
            if (query.getMinRating() != null) {
                final double min = query.getMinRating();
                b.filter(f -> f.range(r -> r.field("averageRating").gte(JsonData.of(min))));
            }

            return b;
        }));
    }

    /**
     * Build an OR (should) query matching any of the supplied keyword values for
     * a single field, suitable for use as a bool filter clause.
     */
    private Query anyOf(String field, List<String> values) {
        List<Query> termQueries = values.stream()
                .filter(v -> v != null && !v.isEmpty())
                .map(v -> Query.of(q -> q.term(t -> t.field(field).value(v))))
                .collect(Collectors.toList());
        return Query.of(q -> q.bool(b -> b.should(termQueries).minimumShouldMatch("1")));
    }

    /** Map the sort key to a Spring Data {@link Sort} over indexed numeric/date fields. */
    private Sort buildSort(String sortBy) {
        if (sortBy == null) {
            return Sort.by(Sort.Direction.DESC, "updatedAt");
        }
        switch (sortBy.toLowerCase()) {
            case "popular":
                return Sort.by(Sort.Direction.DESC, "downloadCount");
            case "rating":
                return Sort.by(Sort.Direction.DESC, "averageRating");
            case "name":
                // Sorted in memory; the analyzed text field is not sortable in ES.
                return Sort.unsorted();
            case "recent":
            default:
                return Sort.by(Sort.Direction.DESC, "updatedAt");
        }
    }

    // ----- Result hydration & facet counting helpers -------------------------

    /**
     * Fetch full entities for the given ids from the primary datastore and
     * return them in the exact order of {@code orderedIds} (the Elasticsearch
     * relevance/sort order).
     */
    private List<DevOpsScript> hydrateInOrder(List<Long> orderedIds) {
        if (orderedIds.isEmpty()) {
            return new ArrayList<>();
        }

        Map<Long, DevOpsScript> byId = devOpsScriptRepository.findAllById(orderedIds).stream()
                .collect(Collectors.toMap(DevOpsScript::getId, s -> s));

        List<DevOpsScript> ordered = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            DevOpsScript script = byId.get(id);
            if (script != null) {
                ordered.add(script);
            }
        }
        return ordered;
    }

    private List<FacetValue> countSingle(List<DevOpsScriptDocument> docs,
                                         java.util.function.Function<DevOpsScriptDocument, String> extractor) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DevOpsScriptDocument doc : docs) {
            String value = extractor.apply(doc);
            if (value != null && !value.isEmpty()) {
                counts.merge(value, 1L, (a, b) -> a + b);
            }
        }
        return toSortedFacets(counts);
    }

    private List<FacetValue> countMulti(List<DevOpsScriptDocument> docs,
                                        java.util.function.Function<DevOpsScriptDocument, List<String>> extractor) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DevOpsScriptDocument doc : docs) {
            List<String> values = extractor.apply(doc);
            if (values == null) {
                continue;
            }
            for (String value : values) {
                if (value != null && !value.isEmpty()) {
                    counts.merge(value, 1L, (a, b) -> a + b);
                }
            }
        }
        return toSortedFacets(counts);
    }

    /** Convert raw counts into facet buckets sorted by descending count then value. */
    private List<FacetValue> toSortedFacets(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .map(e -> new FacetValue(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(FacetValue::getCount).reversed()
                        .thenComparing(FacetValue::getValue))
                .collect(Collectors.toList());
    }
}
