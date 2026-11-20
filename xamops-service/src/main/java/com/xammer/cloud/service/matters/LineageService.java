package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.LineageCategories;
import com.xammer.cloud.dto.matters.datastores.LineageDatastorePage;
import com.xammer.cloud.dto.matters.datastores.LineageFilePage;
import com.xammer.cloud.dto.matters.datastores.LineagePolicyPage;
import com.xammer.cloud.dto.matters.datastores.SaasFileLineage;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.matters.MattersApiClient.ExposureListParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The Data Lineage page (build 4 design 5.8): the category cards, the More filters options, the policy,
 * datastore and file rows, and a file's lineage.
 *
 * <h2>The switch</h2>
 * The exposure routes are closed to project API keys (GAP-62), so every public method first checks
 * {@code xamlens.lineage.exposure-routes-enabled} (default false). Off, it returns its unavailable answer
 * without resolving a credential, reading the cache or calling Matters, so no audit row is written either.
 *
 * <h2>Switched on</h2>
 * Each method resolves the credential, then reads the cache, then makes the vendor calls, then caches the
 * whitelisted record. A failing vendor call propagates and nothing is cached. Filter values must be
 * options of the project's snapshot; an Exclude is sent as the include list of every other option, and an
 * Exclude of every option answers an empty page without a list call (design 4.5).
 */
@Service
public class LineageService {

    static final int OPTIONS_CACHE_MINUTES = 5;
    static final int ROWS_CACHE_MINUTES = 2;

    private static final TypeReference<LineageFilterSnapshot> SNAPSHOT = new TypeReference<LineageFilterSnapshot>() {
    };
    private static final TypeReference<LineageCategories> CATEGORIES = new TypeReference<LineageCategories>() {
    };
    private static final TypeReference<LineagePolicyPage> POLICIES = new TypeReference<LineagePolicyPage>() {
    };
    private static final TypeReference<LineageDatastorePage> DATASTORES = new TypeReference<LineageDatastorePage>() {
    };
    private static final TypeReference<LineageFilePage> FILES = new TypeReference<LineageFilePage>() {
    };

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final LineageMapper mapper;
    private final RedisCacheService cache;
    private final SaasFileService saasFileService;
    private final boolean exposureRoutesEnabled;

    public LineageService(MattersCredentialResolver credentialResolver, MattersApiClient apiClient,
                          LineageMapper mapper, RedisCacheService cache, SaasFileService saasFileService,
                          @Value("${xamlens.lineage.exposure-routes-enabled:false}") boolean exposureRoutesEnabled) {
        this.credentialResolver = credentialResolver;
        this.apiClient = apiClient;
        this.mapper = mapper;
        this.cache = cache;
        this.saasFileService = saasFileService;
        this.exposureRoutesEnabled = exposureRoutesEnabled;
    }

    /** Step 0: the known categories with their exposure counts. */
    public LineageCategories categories(Long clientId) {
        if (!exposureRoutesEnabled) {
            return LineageCategories.unavailable();
        }
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.lineageCategories(clientId), CATEGORIES, ROWS_CACHE_MINUTES, () -> {
            LineageFilterSnapshot snapshot = snapshot(clientId, credential);
            Map<String, JsonNode> summaries = new LinkedHashMap<>();
            for (String id : snapshot.categoryIds()) {
                summaries.put(id, apiClient.exposureSummary(credential, id, ExposureListParams.count()));
            }
            return mapper.toCategories(snapshot, summaries);
        });
    }

    /**
     * The More filters options of a level: {@code severity}, {@code datastoreName} and {@code label} for
     * {@code policy}, {@code severity} for {@code datastore}.
     */
    public DatastoreFilterOptions filterOptions(Long clientId, String level) {
        if (!exposureRoutesEnabled) {
            return new DatastoreFilterOptions(List.of());
        }
        MattersCredential credential = credentialResolver.resolve(clientId);
        LineageFilterSnapshot snapshot = snapshot(clientId, credential);
        List<DatastoreFilterOptions.Dimension> dimensions = new ArrayList<>();
        for (String dimension : dimensionsOf(level)) {
            List<DatastoreFilterOptions.Option> options = snapshot.options(dimension).stream()
                    .map(option -> new DatastoreFilterOptions.Option(option.value(), option.label(), null))
                    .toList();
            dimensions.add(new DatastoreFilterOptions.Dimension(dimension, options, false));
        }
        return new DatastoreFilterOptions(List.copyOf(dimensions));
    }

    /** Step 1: one page of a category's policies. */
    public LineagePolicyPage policies(Long clientId, ExposureCategory category, LineageRowQuery query) {
        if (!exposureRoutesEnabled) {
            return LineagePolicyPage.unavailable(query.skip(), query.limit());
        }
        MattersCredential credential = credentialResolver.resolve(clientId);
        Optional<Map<String, List<String>>> vendor = vendorFilters(clientId, credential, query.filters(),
                LineageFilters.POLICY_DIMENSIONS);
        if (vendor.isEmpty()) {
            return LineagePolicyPage.empty(query.skip(), query.limit());
        }
        Map<String, List<String>> ids = vendor.get();
        ExposureListParams params = new ExposureListParams(query.skip(), query.limit(), query.search(),
                ids.get(LineageFilters.SEVERITY), ids.get(LineageFilters.DATASTORE_NAME), ids.get(LineageFilters.LABEL));
        return cached(DatastoreCacheKeys.lineagePolicies(clientId, category, query), POLICIES, ROWS_CACHE_MINUTES,
                () -> mapper.toPolicyPage(apiClient.exposureSummary(credential, category.name(), params),
                        query.skip(), query.limit()));
    }

    /** Step 2: one page of a policy's affected datastores. */
    public LineageDatastorePage datastores(Long clientId, String policyId, LineageRowQuery query) {
        if (!exposureRoutesEnabled) {
            return LineageDatastorePage.unavailable(query.skip(), query.limit());
        }
        MattersCredential credential = credentialResolver.resolve(clientId);
        Optional<Map<String, List<String>>> vendor = vendorFilters(clientId, credential, query.filters(),
                LineageFilters.DATASTORE_DIMENSIONS);
        if (vendor.isEmpty()) {
            return LineageDatastorePage.empty(query.skip(), query.limit());
        }
        ExposureListParams params = new ExposureListParams(query.skip(), query.limit(), query.search(),
                vendor.get().get(LineageFilters.SEVERITY), List.of(), List.of());
        return cached(DatastoreCacheKeys.lineageDatastores(clientId, policyId, query), DATASTORES, ROWS_CACHE_MINUTES,
                () -> mapper.toDatastorePage(apiClient.exposurePolicyExposures(credential, policyId, params),
                        query.skip(), query.limit()));
    }

    /** Step 3: one page of an exposure's affected files. */
    public LineageFilePage files(Long clientId, String exposureId, int skip, int limit) {
        if (!exposureRoutesEnabled) {
            return LineageFilePage.unavailable(skip, limit);
        }
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.lineageFiles(clientId, exposureId, skip, limit), FILES, ROWS_CACHE_MINUTES,
                () -> mapper.toFilePage(apiClient.exposureFiles(credential, exposureId, skip, limit), skip, limit));
    }

    /** Step 4: the file's lanes and events, from build 2's SaaS file lineage. */
    public SaasFileLineage fileLineage(Long clientId, String fileId) {
        if (!exposureRoutesEnabled) {
            return SaasFileLineage.unavailable();
        }
        return saasFileService.lineage(clientId, fileId);
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private LineageFilterSnapshot snapshot(Long clientId, MattersCredential credential) {
        return cached(DatastoreCacheKeys.lineageOptions(clientId), SNAPSHOT, OPTIONS_CACHE_MINUTES,
                () -> mapper.toSnapshot(apiClient.exposureFilterOptions(credential)));
    }

    private static List<String> dimensionsOf(String level) {
        return LineageParams.LEVEL_DATASTORE.equals(level)
                ? LineageFilters.DATASTORE_DIMENSIONS
                : LineageFilters.POLICY_DIMENSIONS;
    }

    /**
     * The vendor ids per dimension of {@code dimensions} ({@link Optional#empty()} when an Exclude leaves no
     * option, so no row can match). The snapshot is read only when a filter is set.
     *
     * @throws DspmBadRequestException on {@code <dim>} or {@code <dim>_exclude} for a value that is not an
     *                                 option of the project
     */
    private Optional<Map<String, List<String>>> vendorFilters(Long clientId, MattersCredential credential,
                                                              LineageFilters filters, List<String> dimensions) {
        Map<String, List<String>> ids = new LinkedHashMap<>();
        if (filters.isEmpty()) {
            return Optional.of(ids);
        }
        LineageFilterSnapshot snapshot = snapshot(clientId, credential);
        boolean noOptionLeft = false;
        for (String dimension : dimensions) {
            LineageFilters.Selection selection = selectionOf(filters, dimension);
            if (selection.isEmpty()) {
                continue;
            }
            List<LineageFilterSnapshot.Option> options = snapshot.options(dimension);
            Set<String> known = new HashSet<>();
            options.forEach(option -> known.add(option.value()));
            String parameter = selection.exclude() ? dimension + LineageFilters.EXCLUDE_SUFFIX : dimension;
            for (String value : selection.values()) {
                if (!known.contains(value)) {
                    throw new DspmBadRequestException(parameter, parameter + " is not an option of this project");
                }
            }
            Set<String> chosen = new HashSet<>(selection.values());
            List<String> vendorIds = options.stream()
                    .filter(option -> chosen.contains(option.value()) != selection.exclude())
                    .map(LineageFilterSnapshot.Option::vendorId)
                    .toList();
            noOptionLeft |= vendorIds.isEmpty();
            ids.put(dimension, vendorIds);
        }
        return noOptionLeft ? Optional.empty() : Optional.of(ids);
    }

    private static LineageFilters.Selection selectionOf(LineageFilters filters, String dimension) {
        return switch (dimension) {
            case LineageFilters.SEVERITY -> filters.severity();
            case LineageFilters.DATASTORE_NAME -> filters.datastoreName();
            case LineageFilters.LABEL -> filters.label();
            default -> throw new IllegalArgumentException("unknown lineage filter dimension");
        };
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
