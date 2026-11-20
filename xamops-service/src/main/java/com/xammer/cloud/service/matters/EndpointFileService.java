package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.dto.matters.datastores.EndpointFileActivity;
import com.xammer.cloud.dto.matters.datastores.EndpointFileLineage;
import com.xammer.cloud.dto.matters.datastores.EndpointFileOverview;
import com.xammer.cloud.dto.matters.datastores.SaasFileCopies;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Endpoint file drawer (design 5.8 as changed by plan C18 to C20). {@code fileId} is the validated,
 * lower-cased UUID of an endpoint file.
 *
 * <ul>
 *   <li>Overview: an unknown file is {@link MattersApiException.Reason#ASSET_NOT_FOUND}, a 404.</li>
 *   <li>File Activity: the file's MIP tag history; {@code ASSET_NOT_FOUND} is an empty list.</li>
 *   <li>Lineage: the file's own lane from the endpoint-lineage route ({@code ASSET_NOT_FOUND} is a null
 *       asset; any other failure propagates and nothing is cached), plus build 2's hash-lineage lanes. Only
 *       the asset is cached here; the lanes are read through {@link SaasFileService#lineage} on every request,
 *       so they are exactly as old as File Copies' build 2 entry.</li>
 *   <li>File Copies and Related Files: build 2's {@link SaasFileService#copies}, whose copies route takes an
 *       endpoint file id too. The service keeps its build 2 name; the two id formats cannot collide in its
 *       cache entries (24 hex against 36-character UUIDs).</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EndpointFileService {

    static final int OVERVIEW_CACHE_MINUTES = 2;
    static final int ACTIVITY_CACHE_MINUTES = 2;
    static final int LINEAGE_CACHE_MINUTES = 5;

    private static final TypeReference<EndpointFileOverview> OVERVIEW = new TypeReference<EndpointFileOverview>() {
    };
    private static final TypeReference<EndpointFileActivity> ACTIVITY = new TypeReference<EndpointFileActivity>() {
    };
    private static final TypeReference<LineageAssetEntry> LINEAGE_ASSET = new TypeReference<LineageAssetEntry>() {
    };

    /** F7's cache entry: the lineage asset, null when the vendor has none for the file. */
    public record LineageAssetEntry(EndpointFileLineage.Asset asset) {
    }

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final FilesPageMapper filesPageMapper;
    private final SaasFileService saasFileService;
    private final RedisCacheService cache;

    /** F5: header, card, Sensitives and Details. */
    public EndpointFileOverview overview(Long clientId, String fileId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.endpointFileOverview(clientId, fileId), OVERVIEW, OVERVIEW_CACHE_MINUTES,
                () -> filesPageMapper.toEndpointFileOverview(apiClient.endpointFileOverview(credential, fileId)));
    }

    /** F6: File Activity, in API order. */
    public EndpointFileActivity activity(Long clientId, String fileId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.endpointFileActivity(clientId, fileId), ACTIVITY, ACTIVITY_CACHE_MINUTES,
                () -> {
                    try {
                        return filesPageMapper.toEndpointActivity(apiClient.mipFileAuditLogs(credential, fileId));
                    } catch (MattersApiException e) {
                        if (e.getReason() != MattersApiException.Reason.ASSET_NOT_FOUND) {
                            throw e;
                        }
                        log.debug("[DSPM] endpoint file activity: {}", e.getReason());
                        return new EndpointFileActivity(List.of());
                    }
                });
    }

    /**
     * F7: the file's own lane (its asset cached here), then build 2's hash-lineage lanes and events for the
     * same id, from build 2's own cache entry.
     */
    public EndpointFileLineage lineage(Long clientId, String fileId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        LineageAssetEntry entry = cached(DatastoreCacheKeys.endpointFileLineage(clientId, fileId), LINEAGE_ASSET,
                LINEAGE_CACHE_MINUTES, () -> {
                    try {
                        return new LineageAssetEntry(
                                filesPageMapper.toEndpointLineageAsset(apiClient.endpointLineageAsset(credential, fileId)));
                    } catch (MattersApiException e) {
                        if (e.getReason() != MattersApiException.Reason.ASSET_NOT_FOUND) {
                            throw e;
                        }
                        log.debug("[DSPM] endpoint lineage asset: {}", e.getReason());
                        return new LineageAssetEntry(null);
                    }
                });
        return new EndpointFileLineage(entry.asset(), saasFileService.lineage(clientId, fileId));
    }

    /** F8: File Copies and Related Files, from build 2's service. */
    public SaasFileCopies copies(Long clientId, String fileId) {
        return saasFileService.copies(clientId, fileId);
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
