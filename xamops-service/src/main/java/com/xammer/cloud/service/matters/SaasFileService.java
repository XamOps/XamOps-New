package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.SaasFileActivity;
import com.xammer.cloud.dto.matters.datastores.SaasFileCopies;
import com.xammer.cloud.dto.matters.datastores.SaasFileLineage;
import com.xammer.cloud.dto.matters.datastores.SaasFileOverview;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The SaaS file drawer: File Overview, File Activity, Lineage, and the copies behind File Copies and
 * Related Files (design 5.8 and 4.10). {@code fileId} is the validated, lower-cased 24-hex id from the
 * SaaS file list; it also decides which lineage copy is the opened file.
 *
 * <p>Every method resolves the credential first, then reads the cache, then makes the vendor calls
 * listed, then caches the whitelisted DTO. Lineage and copies read the same copies route, each with its
 * own cache entry, so a timeline failure fails the Lineage tab only (design 4.10).
 *
 * <h2>Lineage failures (review fix R6)</h2>
 * Only {@link MattersApiException.Reason#ASSET_NOT_FOUND} is caught. On the copies route it means the
 * file is unknown or lineage is off for the account: the answer is {@link SaasFileLineage#unavailable()},
 * cached like any other. On the timeline it means no events: the lanes are kept. Any other failure, on
 * either route, propagates (endpoint 7 answers 502 and the tab offers Retry) and nothing is cached.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SaasFileService {

    static final int OVERVIEW_CACHE_MINUTES = 2;
    static final int ACTIVITY_CACHE_MINUTES = 2;
    static final int LINEAGE_CACHE_MINUTES = 5;
    static final int COPIES_CACHE_MINUTES = 5;
    /** Events asked of the timeline route. */
    static final int TIMELINE_LIMIT = 100;

    private static final Pattern DATA_HASH = Pattern.compile("^[0-9a-fA-F]{64}$");

    private static final TypeReference<SaasFileOverview> OVERVIEW = new TypeReference<SaasFileOverview>() {
    };
    private static final TypeReference<SaasFileActivity> ACTIVITY = new TypeReference<SaasFileActivity>() {
    };
    private static final TypeReference<SaasFileLineage> LINEAGE = new TypeReference<SaasFileLineage>() {
    };
    private static final TypeReference<SaasFileCopies> COPIES = new TypeReference<SaasFileCopies>() {
    };

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final SaasDrawerMapper mapper;
    private final RedisCacheService cache;

    /** The File Overview tab. */
    public SaasFileOverview overview(Long clientId, String fileId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.saasFileOverview(clientId, fileId), OVERVIEW, OVERVIEW_CACHE_MINUTES,
                () -> mapper.toFileOverview(apiClient.saasFileOverview(credential, fileId)));
    }

    /** The File Activity tab, newest first. */
    public SaasFileActivity activity(Long clientId, String fileId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.saasFileActivity(clientId, fileId), ACTIVITY, ACTIVITY_CACHE_MINUTES,
                () -> mapper.toActivity(apiClient.remediationLogs(credential, fileId)));
    }

    /**
     * The Lineage tab: the copies, then the timeline of their content hash when the copies list is
     * non-empty and the hash is 64 hex characters. See the class doc for the failures.
     */
    public SaasFileLineage lineage(Long clientId, String fileId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.saasFileLineage(clientId, fileId), LINEAGE, LINEAGE_CACHE_MINUTES,
                () -> loadLineage(credential, fileId));
    }

    /**
     * File Copies and Related Files: every copy but the opened file. An unknown file, or lineage off for
     * the account, gives {@link SaasFileCopies#unavailable()}, cached like any other answer.
     */
    public SaasFileCopies copies(Long clientId, String fileId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.saasFileCopies(clientId, fileId), COPIES, COPIES_CACHE_MINUTES,
                () -> {
                    JsonNode copies = copiesOrNull(credential, fileId);
                    return copies == null ? SaasFileCopies.unavailable() : mapper.toCopies(copies, fileId);
                });
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private SaasFileLineage loadLineage(MattersCredential credential, String fileId) {
        JsonNode copies = copiesOrNull(credential, fileId);
        if (copies == null) {
            return SaasFileLineage.unavailable();
        }
        JsonNode data = copies.path("data");
        JsonNode copyList = data.path("copies");
        String dataHash = DrawerJson.string(data, "data_hash");
        JsonNode timeline = null;
        if (copyList.isArray() && copyList.size() > 0 && dataHash != null && DATA_HASH.matcher(dataHash).matches()) {
            try {
                timeline = apiClient.hashLineageTimeline(credential, dataHash, TIMELINE_LIMIT);
            } catch (MattersApiException e) {
                if (e.getReason() != MattersApiException.Reason.ASSET_NOT_FOUND) {
                    throw e;
                }
                log.debug("[DSPM] lineage timeline: {}, lanes kept without events", e.getReason());
            }
        }
        return mapper.toLineage(copies, timeline, fileId);
    }

    /** The copies answer, or null for {@code ASSET_NOT_FOUND}; every other failure propagates. */
    private JsonNode copiesOrNull(MattersCredential credential, String fileId) {
        try {
            return apiClient.hashLineageCopies(credential, fileId);
        } catch (MattersApiException e) {
            if (e.getReason() != MattersApiException.Reason.ASSET_NOT_FOUND) {
                throw e;
            }
            log.debug("[DSPM] lineage copies: {}", e.getReason());
            return null;
        }
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
