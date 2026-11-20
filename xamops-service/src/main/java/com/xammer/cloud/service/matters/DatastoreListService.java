package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.DatastoreKpis;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.datastores.DatastoreSummarySnapshot;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The list side of the XamLens Datastores page: rows, filter options and KPI cards (design 5.9).
 *
 * <p>Every method resolves the credential first, so the entitlement, mapping, disabled, decrypt and
 * expiry checks run on every request, cache hit or not, as in {@link MattersDspmService}. Only
 * whitelisted DTOs are cached, under {@link DatastoreCacheKeys}.
 *
 * <p>Facets are cached once, without account labels, and shared by the filter-options endpoint and
 * the KPI endpoint: a tab's KPI status counts reuse the entry its unfiltered filter-options load wrote.
 * Labels are applied on read, from the labeler's own 12-hour entry.
 */
@Service
@RequiredArgsConstructor
public class DatastoreListService {

    static final int LIST_CACHE_MINUTES = 2;
    static final int FACETS_CACHE_MINUTES = 5;
    static final int SUMMARY_CACHE_MINUTES = 5;

    private static final TypeReference<DspmPage<DatastoreRow>> PAGE =
            new TypeReference<DspmPage<DatastoreRow>>() {
            };
    private static final TypeReference<DatastoreFilterOptions> FACETS =
            new TypeReference<DatastoreFilterOptions>() {
            };
    private static final TypeReference<DatastoreSummarySnapshot> SUMMARY =
            new TypeReference<DatastoreSummarySnapshot>() {
            };

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final DatastoreRowMapper rowMapper;
    private final DatastoreAccountLabeler labeler;
    private final RedisCacheService cache;

    /** One page of rows with account labels. Paging comes from the validated request, not the vendor. */
    public DspmPage<DatastoreRow> list(Long clientId, DatastoreListQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        String key = DatastoreCacheKeys.list(clientId, query);
        Optional<DspmPage<DatastoreRow>> cached = cache.get(key, PAGE);
        if (cached.isPresent()) {
            return cached.get();
        }

        JsonNode body = apiClient.listAssets(credential, query.toVendorListParams());
        List<DatastoreRow> rows = labeler.label(credential, rowMapper.toMappedRows(body));
        DspmPage<DatastoreRow> page = rowMapper.toPage(rows, body, query.page(), query.pageSize());
        cache.put(key, page, LIST_CACHE_MINUTES);
        return page;
    }

    /** Filter options for the same filter terms as the list, with readable account labels. */
    public DatastoreFilterOptions filterOptions(Long clientId, DatastoreListQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return labeler.label(credential, facets(credential, clientId, query));
    }

    /**
     * The KPI cards of one tab. They ignore the page's filters: All reads the summary cards, any other
     * tab also reads its unfiltered facets for the status counts. Labels are never applied here.
     */
    public DatastoreKpis kpis(Long clientId, DatastoreTab tab) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        DatastoreSummarySnapshot summary = summary(credential, clientId);
        DatastoreFilterOptions facets = tab == DatastoreTab.ALL
                ? null
                : facets(credential, clientId, DatastoreListQuery.unfilteredFacets(tab));
        return rowMapper.toKpis(tab, summary, facets);
    }

    /**
     * The tab's facets from cache, else from {@code /api/v1/assets/filters}. Cached with raw
     * integration ids as labels. Package-private: callers resolve the credential first.
     */
    DatastoreFilterOptions facets(MattersCredential credential, Long clientId, DatastoreListQuery query) {
        String key = DatastoreCacheKeys.facets(clientId, query);
        Optional<DatastoreFilterOptions> cached = cache.get(key, FACETS);
        if (cached.isPresent()) {
            return cached.get();
        }
        DatastoreFilterOptions facets =
                rowMapper.toFacets(apiClient.listFilters(credential, query.toVendorFilterParams()), query.tab());
        cache.put(key, facets, FACETS_CACHE_MINUTES);
        return facets;
    }

    DatastoreSummarySnapshot summary(MattersCredential credential, Long clientId) {
        String key = DatastoreCacheKeys.summary(clientId);
        Optional<DatastoreSummarySnapshot> cached = cache.get(key, SUMMARY);
        if (cached.isPresent()) {
            return cached.get();
        }
        DatastoreSummarySnapshot summary = rowMapper.toSummary(apiClient.summary(credential, null));
        cache.put(key, summary, SUMMARY_CACHE_MINUTES);
        return summary;
    }
}
