package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.EndpointFilePage;
import com.xammer.cloud.dto.matters.datastores.EndpointOccurrencePage;
import com.xammer.cloud.dto.matters.datastores.EndpointOverview;
import com.xammer.cloud.dto.matters.datastores.EndpointScanPage;
import com.xammer.cloud.dto.matters.datastores.EndpointSensitiveMetrics;
import com.xammer.cloud.dto.matters.datastores.EndpointSummary;
import com.xammer.cloud.dto.matters.datastores.EndpointSummarySnapshot;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Endpoint drawer: one method per tab or view, each behind one endpoint-agent console route
 * (design 5.8). {@code agentId} is the row's validated, lower-cased 24-hex {@code source_id}.
 *
 * <p>Every method resolves the credential first, then reads the cache, then makes the vendor calls
 * listed, then caches the whitelisted DTO. The unfiltered sensitive-files answer is cached once as the
 * {@code dsepsum} snapshot: the Information tab's totals plus the agent's entity names, which validate
 * {@code entityName}. Occurrences carry raw samples (decision D1) and are never cached.
 */
@Service
@RequiredArgsConstructor
public class EndpointDrawerService {

    static final int OVERVIEW_CACHE_MINUTES = 2;
    static final int FILTER_OPTIONS_CACHE_MINUTES = 5;
    static final int SUMMARY_CACHE_MINUTES = 5;
    static final int METRICS_CACHE_MINUTES = 5;
    static final int FILES_CACHE_MINUTES = 2;
    static final int SCANS_CACHE_MINUTES = 2;

    private static final TypeReference<EndpointOverview> OVERVIEW = new TypeReference<EndpointOverview>() {
    };
    private static final TypeReference<DatastoreFilterOptions> FILTER_OPTIONS =
            new TypeReference<DatastoreFilterOptions>() {
            };
    private static final TypeReference<EndpointSummarySnapshot> SUMMARY =
            new TypeReference<EndpointSummarySnapshot>() {
            };
    private static final TypeReference<EndpointSensitiveMetrics> METRICS =
            new TypeReference<EndpointSensitiveMetrics>() {
            };
    private static final TypeReference<EndpointFilePage> FILE_PAGE = new TypeReference<EndpointFilePage>() {
    };
    private static final TypeReference<EndpointScanPage> SCAN_PAGE = new TypeReference<EndpointScanPage>() {
    };

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final EndpointDrawerMapper mapper;
    private final RedisCacheService cache;

    /** The header and the Information tab's device details. */
    public EndpointOverview overview(Long clientId, String agentId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.endpointOverview(clientId, agentId), OVERVIEW, OVERVIEW_CACHE_MINUTES,
                () -> mapper.toOverview(apiClient.agentOverview(credential, agentId)));
    }

    /** The "+ More filters" options: sensitives, document classes, categories, data elements. */
    public DatastoreFilterOptions filterOptions(Long clientId, String agentId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.endpointFilterOptions(clientId, agentId), FILTER_OPTIONS,
                FILTER_OPTIONS_CACHE_MINUTES,
                () -> mapper.toFilterOptions(apiClient.agentFilterOptions(credential, agentId)));
    }

    /** The Information tab's totals: the {@code summary()} of the cached snapshot. */
    public EndpointSummary summary(Long clientId, String agentId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return snapshot(credential, clientId, agentId).summary();
    }

    /**
     * The Entity View's metrics for one filter set.
     *
     * @param filters            format-checked by the caller.
     * @param showFalsePositives false sends {@code isFalsePositive=false}, hiding marked findings.
     */
    public EndpointSensitiveMetrics sensitives(Long clientId, String agentId, EndpointFilters filters,
                                               boolean showFalsePositives) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.endpointMetrics(clientId, agentId, filters, showFalsePositives), METRICS,
                METRICS_CACHE_MINUTES, () -> mapper.toMetrics(
                        apiClient.agentSensitiveFiles(credential, agentId, filters, !showFalsePositives)));
    }

    /**
     * One page of an entity's occurrences, with raw samples. Occurrences never store an occurrence page.
     * On a dsepsum miss they write that snapshot (no samples), as the summary endpoint would. The cache
     * is read only for that snapshot, and {@code entityName} must be one of its entity names before the
     * entity route is called (review fix R5).
     *
     * @param entityName         format-checked by the caller.
     * @param showFalsePositives false sends {@code isFalsePositive=false}, hiding marked occurrences.
     * @throws DspmBadRequestException on {@code entityName} when the agent has no such entity.
     */
    public EndpointOccurrencePage occurrences(Long clientId, String agentId, String entityName, int skip, int limit,
                                              boolean showFalsePositives) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        EndpointSummarySnapshot snapshot = snapshot(credential, clientId, agentId);
        if (!snapshot.entityNames().contains(entityName)) {
            throw new DspmBadRequestException("entityName", "entityName is not an entity of this agent");
        }
        JsonNode body = apiClient.agentEntity(credential, agentId, entityName, skip, limit, !showFalsePositives);
        return mapper.toOccurrences(body, entityName, skip, limit);
    }

    /** One File View page. */
    public EndpointFilePage files(Long clientId, String agentId, EndpointFileQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.endpointFiles(clientId, agentId, query), FILE_PAGE, FILES_CACHE_MINUTES,
                () -> mapper.toFilePage(apiClient.agentFiles(credential, agentId, query), query.skip(), query.limit()));
    }

    /** One Scan History page, in API order. */
    public EndpointScanPage scans(Long clientId, String agentId, int skip, int limit) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.endpointScans(clientId, agentId, skip, limit), SCAN_PAGE, SCANS_CACHE_MINUTES,
                () -> mapper.toScanPage(apiClient.agentScans(credential, agentId, skip, limit), skip, limit));
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    /** The {@code dsepsum} entry, else one unfiltered sensitive-files call that writes it. */
    private EndpointSummarySnapshot snapshot(MattersCredential credential, Long clientId, String agentId) {
        return cached(DatastoreCacheKeys.endpointSummary(clientId, agentId), SUMMARY, SUMMARY_CACHE_MINUTES,
                () -> mapper.toSummarySnapshot(
                        apiClient.agentSensitiveFiles(credential, agentId, EndpointFilters.NONE, false)));
    }

    private <T> T cached(String key, TypeReference<T> type, int minutes, Supplier<T> load) {
        Optional<T> hit = cache.get(key, type);
        if (hit.isPresent()) {
            return hit.get();
        }
        T value = load.get();
        cache.put(key, value, minutes);
        return value;
    }
}
