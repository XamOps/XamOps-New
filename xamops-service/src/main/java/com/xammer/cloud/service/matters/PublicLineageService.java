package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.EndpointFileLineage;
import com.xammer.cloud.dto.matters.datastores.PublicFileLineage;
import com.xammer.cloud.dto.matters.datastores.PublicLineageDatastorePage;
import com.xammer.cloud.dto.matters.datastores.PublicLineageDatastorePage.Datastore;
import com.xammer.cloud.dto.matters.datastores.PublicLineageFilePage;
import com.xammer.cloud.dto.matters.datastores.PublicLineageFilePage.File;
import com.xammer.cloud.dto.matters.datastores.SaasFileLineage;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The Data Lineage public path (build 7 design 5.6): datastores, their files and a file's lineage, from the
 * routes a project key reaches. It calls no exposure route and does not read the exposure switch (4.7).
 *
 * <h2>Datastores</h2>
 * No route lists "datastores with lineage", so the whole list is built once per client and cached, then
 * searched and paged in memory: the project's endpoints (at most {@value #MAX_ENDPOINTS}) that have files,
 * then, per SaaS integration (at most {@value #MAX_INTEGRATIONS}), its drive-lineage people with files.
 * List-level failures propagate; one endpoint's or one integration's failure degrades that item (4.5) and
 * shortens the list's life to {@value #PARTIAL_CACHE_MINUTES} minute. Concurrent callers of a client share one
 * build.
 *
 * <h2>Logging</h2>
 * Debug lines carry only 24-hex agent or integration ids and failure reasons: no name, e-mail, title, doc id,
 * resource id or search text.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PublicLineageService {

    static final int MAX_ENDPOINTS = 25;
    static final int MAX_INTEGRATIONS = 10;
    static final int DATASTORES_CACHE_MINUTES = 5;
    static final int PARTIAL_CACHE_MINUTES = 1;
    static final int ENDPOINT_FILES_CACHE_MINUTES = 2;
    static final int DRIVE_FILES_CACHE_MINUTES = 5;
    static final int DRIVE_LINEAGE_CACHE_MINUTES = 5;

    /** The endpoint asset list call: one page of the largest size. */
    static final int ENDPOINT_PAGE_SIZE = 100;

    /** Failures of an integration's sample call that are not degraded to "no service": they apply to every call. */
    private static final Set<MattersApiException.Reason> PROPAGATING = Set.of(
            MattersApiException.Reason.RATE_LIMITED,
            MattersApiException.Reason.INVALID_KEY,
            MattersApiException.Reason.KEY_EXPIRED,
            MattersApiException.Reason.WRONG_ENVIRONMENT,
            MattersApiException.Reason.FORBIDDEN_SCOPE);

    private static final TypeReference<PublicDatastoreSnapshot> SNAPSHOT = new TypeReference<PublicDatastoreSnapshot>() {
    };
    private static final TypeReference<PublicLineageFilePage> FILE_PAGE = new TypeReference<PublicLineageFilePage>() {
    };
    private static final TypeReference<PublicDriveFiles> DRIVE_FILES = new TypeReference<PublicDriveFiles>() {
    };
    private static final TypeReference<PublicDriveAssetEntry> DRIVE_ASSET = new TypeReference<PublicDriveAssetEntry>() {
    };

    /** The whole datastore list of a client, in display order. Cache only. */
    record PublicDatastoreSnapshot(List<Datastore> rows) {
        PublicDatastoreSnapshot {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /** A drive person's whole file list. Cache only. */
    record PublicDriveFiles(List<File> rows) {
        PublicDriveFiles {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /** A drive file's lineage asset and owner; both null when the vendor has no asset for it. Cache only. */
    record PublicDriveAssetEntry(EndpointFileLineage.Asset asset, String ownerEmail) {
    }

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final PublicLineageMapper mapper;
    private final FilesPageMapper filesPageMapper;
    private final EndpointFileService endpointFileService;
    private final RedisCacheService cache;

    /** The datastore build in progress per client; the leader removes its own entry when it ends. */
    private final ConcurrentHashMap<Long, CompletableFuture<PublicDatastoreSnapshot>> inFlight =
            new ConcurrentHashMap<>();

    /**
     * P1: one page of the datastores whose name contains {@code q} (case-insensitive), {@code total} counted
     * after the search.
     *
     * @param q null for no search
     */
    public PublicLineageDatastorePage datastores(Long clientId, String q, int skip, int limit) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        String key = DatastoreCacheKeys.lineagePublicDatastores(clientId);
        PublicDatastoreSnapshot snapshot = cache.get(key, SNAPSHOT).orElse(null);
        if (snapshot == null) {
            snapshot = sharedBuild(credential, clientId, key);
        }
        List<Datastore> matching = q == null
                ? snapshot.rows()
                : snapshot.rows().stream().filter(row -> nameContains(row.name(), q)).toList();
        return new PublicLineageDatastorePage(slice(matching, skip, limit), matching.size(), skip, limit);
    }

    /** P2: one page of the datastore's files. */
    public PublicLineageFilePage files(Long clientId, PublicLineageKey key, int skip, int limit) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        if (key.kind() == PublicLineageKey.Kind.ENDPOINT) {
            String agentId = key.agentId();
            return cached(DatastoreCacheKeys.lineagePublicEndpointFiles(clientId, agentId, skip, limit), FILE_PAGE,
                    ENDPOINT_FILES_CACHE_MINUTES, () -> mapper.toEndpointFiles(
                            apiClient.agentFiles(credential, agentId,
                                    new EndpointFileQuery(EndpointFilters.NONE, null, skip, limit)),
                            skip, limit));
        }
        String cacheKey = DatastoreCacheKeys.lineagePublicDriveFiles(clientId, key);
        PublicDriveFiles files = cache.get(cacheKey, DRIVE_FILES).orElse(null);
        if (files == null) {
            try {
                files = new PublicDriveFiles(mapper.toDriveFiles(
                        apiClient.driveLineageUserFiles(credential, key.integrationId(), key.resourceId())));
            } catch (MattersApiException e) {
                if (e.getReason() != MattersApiException.Reason.ASSET_NOT_FOUND) {
                    throw e;
                }
                log.debug("[DSPM] public lineage drive files of integration {}: {}", key.integrationId(), e.getReason());
                return new PublicLineageFilePage(List.of(), 0, skip, limit);
            }
            cache.put(cacheKey, files, DRIVE_FILES_CACHE_MINUTES);
        }
        return new PublicLineageFilePage(slice(files.rows(), skip, limit), files.rows().size(), skip, limit);
    }

    /** P3: the file's lineage, build 3's asset shape for both kinds. */
    public PublicFileLineage fileLineage(Long clientId, PublicLineageKey key, String fileId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        if (key.kind() == PublicLineageKey.Kind.ENDPOINT) {
            EndpointFileLineage lineage = endpointFileService.lineage(clientId, fileId);
            return new PublicFileLineage(PublicLineageMapper.KIND_ENDPOINT, lineage.asset(), lineage.copies(), null);
        }
        String integrationId = key.integrationId();
        PublicDriveAssetEntry entry = cached(DatastoreCacheKeys.lineagePublicDriveAsset(clientId, integrationId, fileId),
                DRIVE_ASSET, DRIVE_LINEAGE_CACHE_MINUTES, () -> {
                    try {
                        JsonNode body = apiClient.driveLineageAsset(credential, integrationId, fileId);
                        return new PublicDriveAssetEntry(filesPageMapper.toEndpointLineageAsset(body),
                                mapper.driveOwner(body));
                    } catch (MattersApiException e) {
                        if (e.getReason() != MattersApiException.Reason.ASSET_NOT_FOUND) {
                            throw e;
                        }
                        log.debug("[DSPM] public lineage drive asset of integration {}: {}", integrationId,
                                e.getReason());
                        return new PublicDriveAssetEntry(null, null);
                    }
                });
        return new PublicFileLineage(PublicLineageMapper.KIND_DRIVE, entry.asset(), SaasFileLineage.unavailable(),
                entry.ownerEmail());
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    /** The rows and whether a count was unknown, which shortens the entry's life. */
    private record Snapshot(List<Datastore> rows, boolean partial) {
    }

    /**
     * One datastore build shared by every concurrent caller of the client, as {@link MisconfigurationService}
     * does: the first caller builds and caches the list, the others wait for its answer or its failure.
     */
    private PublicDatastoreSnapshot sharedBuild(MattersCredential credential, Long clientId, String key) {
        CompletableFuture<PublicDatastoreSnapshot> mine = new CompletableFuture<>();
        CompletableFuture<PublicDatastoreSnapshot> leader = inFlight.putIfAbsent(clientId, mine);
        if (leader != null) {
            return await(leader);
        }
        try {
            Snapshot built = buildSnapshot(credential);
            PublicDatastoreSnapshot snapshot = new PublicDatastoreSnapshot(built.rows());
            cache.put(key, snapshot, built.partial() ? PARTIAL_CACHE_MINUTES : DATASTORES_CACHE_MINUTES);
            mine.complete(snapshot);
            return snapshot;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(clientId, mine);
        }
    }

    private static PublicDatastoreSnapshot await(CompletableFuture<PublicDatastoreSnapshot> leader) {
        try {
            return leader.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private Snapshot buildSnapshot(MattersCredential credential) {
        List<Datastore> rows = new ArrayList<>();
        boolean partial = false;

        Map<String, List<String>> endpointQuery = new LinkedHashMap<>();
        endpointQuery.put("category", List.of("endpoint"));
        endpointQuery.put("page", List.of("1"));
        endpointQuery.put("page_size", List.of(String.valueOf(ENDPOINT_PAGE_SIZE)));
        List<PublicLineageMapper.EndpointRef> endpoints = mapper.endpointRows(apiClient.listAssets(credential, endpointQuery));
        if (endpoints.size() > MAX_ENDPOINTS) {
            log.debug("[DSPM] public lineage: {} endpoints past the first {} left out", endpoints.size() - MAX_ENDPOINTS,
                    MAX_ENDPOINTS);
            endpoints = endpoints.subList(0, MAX_ENDPOINTS);
        }
        for (PublicLineageMapper.EndpointRef endpoint : endpoints) {
            String agentId = endpoint.agentId();
            Long files;
            try {
                files = mapper.fileTotal(apiClient.agentFiles(credential, agentId,
                        new EndpointFileQuery(EndpointFilters.NONE, null, 0, 1)));
            } catch (MattersApiException e) {
                switch (e.getReason()) {
                    case ASSET_NOT_FOUND -> {
                        log.debug("[DSPM] public lineage: endpoint {} left out: {}", agentId, e.getReason());
                        continue;
                    }
                    case UPSTREAM_UNAVAILABLE, BAD_UPSTREAM_RESPONSE -> {
                        log.debug("[DSPM] public lineage: endpoint {} count unknown: {}", agentId, e.getReason());
                        files = null;
                        partial = true;
                    }
                    default -> throw e;
                }
            }
            if (files != null && files <= 0) {
                continue;
            }
            rows.add(new Datastore(PublicLineageKey.endpoint(agentId).value(), PublicLineageMapper.KIND_ENDPOINT,
                    endpoint.name(), PublicLineageMapper.KIND_ENDPOINT, files));
        }

        Map<String, List<String>> filterQuery = new LinkedHashMap<>();
        filterQuery.put("category", List.of("saas"));
        List<String> integrations = mapper.integrationIds(apiClient.listFilters(credential, filterQuery));
        for (String integrationId : integrations.subList(0, Math.min(integrations.size(), MAX_INTEGRATIONS))) {
            String service = serviceOf(credential, integrationId);
            try {
                rows.addAll(mapper.people(apiClient.driveLineageUsers(credential, integrationId), integrationId, service));
            } catch (MattersApiException e) {
                switch (e.getReason()) {
                    case ASSET_NOT_FOUND -> log.debug("[DSPM] public lineage: integration {} has no drive lineage: {}",
                            integrationId, e.getReason());
                    case UPSTREAM_UNAVAILABLE, BAD_UPSTREAM_RESPONSE -> {
                        // As an endpoint's unknown count: the other datastores stay, the list lives 1 minute.
                        log.debug("[DSPM] public lineage: integration {} left out: {}", integrationId, e.getReason());
                        partial = true;
                    }
                    default -> throw e;
                }
            }
        }
        return new Snapshot(rows, partial);
    }

    /** The integration's service from one sample asset; null when the sample fails for a non-key reason. */
    private String serviceOf(MattersCredential credential, String integrationId) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("integration_id", List.of(integrationId));
        query.put("page_size", List.of("1"));
        try {
            return mapper.service(apiClient.listAssets(credential, query));
        } catch (MattersApiException e) {
            if (PROPAGATING.contains(e.getReason())) {
                throw e;
            }
            log.debug("[DSPM] public lineage: integration {} service unknown: {}", integrationId, e.getReason());
            return null;
        }
    }

    private static boolean nameContains(String name, String q) {
        return name != null && name.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));
    }

    private static <T> List<T> slice(List<T> rows, int skip, int limit) {
        if (skip >= rows.size()) {
            return List.of();
        }
        return List.copyOf(rows.subList(skip, Math.min(rows.size(), skip + limit)));
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
