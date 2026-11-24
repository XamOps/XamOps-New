package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.EndpointFilePage;
import com.xammer.cloud.dto.matters.datastores.SaasFileListPage;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The Files page lists and their filter options (design 5.8 as changed by plan C3, C5, C6). Every method
 * resolves the credential first, then reads the cache, then makes the vendor calls listed, then caches the
 * whitelisted DTO. A failing vendor call caches nothing and propagates.
 */
@Service
@RequiredArgsConstructor
public class FilesService {

    static final int SAAS_PAGE_CACHE_MINUTES = 2;
    static final int SAAS_OPTIONS_CACHE_MINUTES = 5;
    static final int ENDPOINT_PAGE_CACHE_MINUTES = 2;
    static final int ENDPOINT_OPTIONS_CACHE_MINUTES = 5;

    private static final TypeReference<SaasFileListPage> SAAS_PAGE = new TypeReference<SaasFileListPage>() {
    };
    private static final TypeReference<SaasFileOptionsEntry> SAAS_OPTIONS = new TypeReference<SaasFileOptionsEntry>() {
    };
    private static final TypeReference<EndpointFilePage> ENDPOINT_PAGE = new TypeReference<EndpointFilePage>() {
    };
    private static final TypeReference<DatastoreFilterOptions> OPTIONS = new TypeReference<DatastoreFilterOptions>() {
    };

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final FilesPageMapper filesPageMapper;
    private final EndpointDrawerMapper endpointDrawerMapper;
    private final RedisCacheService cache;

    /**
     * F2: the SaaS tab's filter options. When {@code categories} is non-empty, Document Class narrows to
     * those categories' classes (route order, de-duplicated) from the cached entry, with no vendor call.
     */
    public DatastoreFilterOptions saasFilterOptions(Long clientId, List<String> categories) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        SaasFileOptionsEntry entry = cached(DatastoreCacheKeys.filesSaasOptions(clientId), SAAS_OPTIONS,
                SAAS_OPTIONS_CACHE_MINUTES, () -> filesPageMapper.toSaasOptions(apiClient.saasCategories(credential),
                        apiClient.saasSensitivityUsers(credential)));
        if (categories == null || categories.isEmpty()) {
            return entry.options();
        }
        return narrowDocumentClasses(entry, new HashSet<>(categories));
    }

    /** F1: one keyset page of the SaaS tab. */
    public SaasFileListPage saasFiles(Long clientId, SaasFileListQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.filesSaas(clientId, query), SAAS_PAGE, SAAS_PAGE_CACHE_MINUTES,
                () -> filesPageMapper.toSaasPage(apiClient.saasSensitivities(credential, query), query.limit()));
    }

    /** F3: one page of the Endpoint tab, through build 2's File View mapping. */
    public EndpointFilePage endpointFiles(Long clientId, EndpointFileListQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.filesEndpoint(clientId, query), ENDPOINT_PAGE, ENDPOINT_PAGE_CACHE_MINUTES,
                () -> endpointDrawerMapper.toFilePage(apiClient.endpointFiles(credential, query), query.skip(),
                        query.limit()));
    }

    /** F4: the Endpoint tab's filter options. */
    public DatastoreFilterOptions endpointFilterOptions(Long clientId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.filesEndpointOptions(clientId), OPTIONS, ENDPOINT_OPTIONS_CACHE_MINUTES,
                () -> {
                    JsonNode body = apiClient.endpointAgentsFilterOptions(credential);
                    return filesPageMapper.toEndpointOptions(body);
                });
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    /** The entry's options with Document Class replaced by the chosen categories' classes. */
    private static DatastoreFilterOptions narrowDocumentClasses(SaasFileOptionsEntry entry, Set<String> categories) {
        Set<String> classes = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> category : entry.classesByCategory().entrySet()) {
            if (categories.contains(category.getKey())) {
                classes.addAll(category.getValue());
            }
        }
        List<DatastoreFilterOptions.Option> options = new ArrayList<>();
        for (String value : classes) {
            options.add(new DatastoreFilterOptions.Option(value, DatastoreLabels.categoryChipLabel(value), null));
        }
        boolean hasMore = options.size() > FilesPageMapper.MAX_OPTIONS;
        DatastoreFilterOptions.Dimension narrowed = new DatastoreFilterOptions.Dimension("documentClass",
                hasMore ? List.copyOf(options.subList(0, FilesPageMapper.MAX_OPTIONS)) : options, hasMore);
        List<DatastoreFilterOptions.Dimension> dimensions = new ArrayList<>();
        for (DatastoreFilterOptions.Dimension dimension : entry.options().dimensions()) {
            dimensions.add("documentClass".equals(dimension.key()) ? narrowed : dimension);
        }
        return new DatastoreFilterOptions(dimensions);
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
