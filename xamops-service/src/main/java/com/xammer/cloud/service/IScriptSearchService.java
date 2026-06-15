package com.xammer.cloud.service;

import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.dto.devops.FacetValue;
import com.xammer.cloud.dto.devops.SearchQuery;
import com.xammer.cloud.dto.devops.SearchResult;
import com.xammer.cloud.dto.devops.SearchSuggestion;

import java.util.List;
import java.util.Map;

/**
 * Advanced search functionality for DevOps scripts, backed by PostgreSQL.
 * Provides text search, faceted filtering, autocomplete and suggestions.
 * The index-maintenance methods are retained as no-ops for backward
 * compatibility (there is no separate search index to maintain).
 *
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.3.2 (Advanced Filtering),
 *               REQ-2.6.1 (Search Performance)
 */
public interface IScriptSearchService {

    // ----- Search operations -------------------------------------------------

    /**
     * Execute a full-text + filtered search and return the matching scripts in
     * relevance/sort order, hydrated from the primary datastore.
     *
     * @param query the search request (text, filters, sort, pagination)
     * @return paginated search result of full {@link DevOpsScript} entities
     */
    SearchResult<DevOpsScript> search(SearchQuery query);

    /**
     * Return up to {@code limit} script-name suggestions matching the given
     * prefix, for type-ahead autocomplete.
     *
     * @param prefix the text typed so far
     * @param limit  maximum number of suggestions (capped at 10)
     * @return ordered list of matching script names
     */
    List<String> autocomplete(String prefix, int limit);

    /**
     * Return richer search suggestions (with a source type) for the given query.
     *
     * @param query the partial query text
     * @return list of suggestions
     */
    List<SearchSuggestion> getSuggestions(String query);

    // ----- Indexing operations -----------------------------------------------

    /**
     * Index (or re-index) a single script document.
     *
     * @param script the script to index
     */
    void indexScript(DevOpsScript script);

    /**
     * Bulk index a collection of scripts in a single operation.
     *
     * @param scripts the scripts to index
     */
    void bulkIndexScripts(List<DevOpsScript> scripts);

    /**
     * Remove a script from the search index.
     *
     * @param scriptId the id of the script to delete
     */
    void deleteFromIndex(Long scriptId);

    /**
     * Rebuild the entire search index from the primary datastore.
     */
    void reindexAll();

    // ----- Faceted search ----------------------------------------------------

    /**
     * Compute facet counts (category, cloudProvider, difficulty, tag) for the
     * documents matching the supplied query, to drive filter UIs.
     *
     * @param query the search request whose result set is faceted
     * @return map of facet name to its list of value/count buckets
     */
    Map<String, List<FacetValue>> getFacets(SearchQuery query);
}
