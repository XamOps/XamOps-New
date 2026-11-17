package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.DatastoreAccessGraph;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilePage;
import com.xammer.cloud.dto.matters.datastores.DatastoreOccurrencePage;
import com.xammer.cloud.dto.matters.datastores.DatastoreOverview;
import com.xammer.cloud.dto.matters.datastores.DatastoreRisks;
import com.xammer.cloud.dto.matters.datastores.DatastoreScanHistory;
import com.xammer.cloud.dto.matters.datastores.DatastoreScanProgress;
import com.xammer.cloud.dto.matters.datastores.DatastoreSensitives;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The cloud / on-prem datastore drawer: one method per tab, each behind one Matters console route
 * (design 5.9). {@code sourceId} is the row's validated, lower-cased 24-hex {@code source_id}.
 *
 * <p>Every method resolves the credential first, then reads the cache, then makes at most one vendor
 * call. Only whitelisted DTOs are cached; no cached DTO has a field that could hold a sample.
 *
 * <p>The sensitives route is mapped once into two entries: the small {@code dssens} snapshot (entities
 * and database tables), which the Sensitive tab and the occurrence check read, and the large
 * {@code dsfiles} rows the File View pages through. Occurrences, which carry raw samples (decision D1),
 * are never read from or written to the cache; that method reads only the snapshot, to validate
 * {@code entityName}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DatastoreDrawerService {

    static final int OVERVIEW_CACHE_MINUTES = 2;
    /** The "cached briefly" scan progress; the cache API counts in whole minutes. */
    static final int SCAN_PROGRESS_CACHE_MINUTES = 1;
    static final int SENSITIVES_CACHE_MINUTES = 5;
    static final int RISKS_CACHE_MINUTES = 5;
    static final int ACCESS_GRAPH_CACHE_MINUTES = 5;
    static final int SCAN_HISTORY_CACHE_MINUTES = 2;

    private static final TypeReference<DatastoreOverview> OVERVIEW = new TypeReference<DatastoreOverview>() {
    };
    private static final TypeReference<DatastoreScanProgress> SCAN_PROGRESS =
            new TypeReference<DatastoreScanProgress>() {
            };
    private static final TypeReference<DatastoreSensitives> SENSITIVES = new TypeReference<DatastoreSensitives>() {
    };
    private static final TypeReference<List<DatastoreFilePage.FileRow>> FILE_ROWS =
            new TypeReference<List<DatastoreFilePage.FileRow>>() {
            };
    private static final TypeReference<DatastoreRisks> RISKS = new TypeReference<DatastoreRisks>() {
    };
    private static final TypeReference<DatastoreAccessGraph> ACCESS_GRAPH = new TypeReference<DatastoreAccessGraph>() {
    };
    private static final TypeReference<DatastoreScanHistory> SCAN_HISTORY = new TypeReference<DatastoreScanHistory>() {
    };

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final DatastoreDrawerMapper drawerMapper;
    private final AccessGraphGrouper accessGraphGrouper;
    private final RedisCacheService cache;

    /** The header, the Information tab and the tab set. */
    public DatastoreOverview overview(Long clientId, String sourceId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.overview(clientId, sourceId), OVERVIEW, OVERVIEW_CACHE_MINUTES,
                () -> drawerMapper.toOverview(apiClient.inventoryOverview(credential, sourceId)));
    }

    /** The % on the header chip. A failure only removes the % in the UI. */
    public DatastoreScanProgress scanProgress(Long clientId, String sourceId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.scanProgress(clientId, sourceId), SCAN_PROGRESS,
                SCAN_PROGRESS_CACHE_MINUTES, () -> drawerMapper.toScanProgress(apiClient.scanStatus(credential, sourceId)));
    }

    /** Entity View and Table View. */
    public DatastoreSensitives sensitives(Long clientId, String sourceId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return snapshot(credential, clientId, sourceId);
    }

    /**
     * One File View page, filtered by path when {@code q} is set.
     *
     * @param q        the validated search term, or null.
     * @param page     validated, 1-based.
     * @param pageSize validated, 1..100.
     */
    public DatastoreFilePage files(Long clientId, String sourceId, String q, int page, int pageSize) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        Optional<List<DatastoreFilePage.FileRow>> cached = cache.get(DatastoreCacheKeys.files(clientId, sourceId),
                FILE_ROWS);
        List<DatastoreFilePage.FileRow> rows = cached.isPresent()
                ? cached.get()
                : loadSensitives(credential, clientId, sourceId).fileRows();
        return drawerMapper.filePage(rows, q, page, pageSize);
    }

    /**
     * One page of an entity's occurrences, with raw samples. Never cached: the cache is read only for
     * the sensitives snapshot, and {@code entityName} must be a non-blank entity of that snapshot
     * before the occurrences route is called.
     *
     * @param entityName         format-checked by the caller.
     * @param showFalsePositives false sends {@code isFalsePositive=false}, hiding marked occurrences.
     */
    public DatastoreOccurrencePage occurrences(Long clientId, String sourceId, String entityName, int skip, int limit,
                                               boolean showFalsePositives) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        DatastoreSensitives snapshot = snapshot(credential, clientId, sourceId);
        boolean known = snapshot.entities().stream()
                .anyMatch(entity -> !entity.blank() && entityName.equals(entity.name()));
        if (!known) {
            throw new DspmBadRequestException("entityName", "entityName is not an entity of this datastore");
        }
        JsonNode body = apiClient.inventorySensitiveEntity(credential, sourceId, entityName, skip, limit,
                !showFalsePositives);
        return drawerMapper.toOccurrences(body, entityName, skip, limit);
    }

    /** The Risk tab's misconfigurations. */
    public DatastoreRisks risks(Long clientId, String sourceId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.risks(clientId, sourceId), RISKS, RISKS_CACHE_MINUTES,
                () -> drawerMapper.toRisks(apiClient.inventoryIssues(credential, sourceId)));
    }

    /** The Access Graph tab, already grouped for the UI. */
    public DatastoreAccessGraph accessGraph(Long clientId, String sourceId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.accessGraph(clientId, sourceId), ACCESS_GRAPH, ACCESS_GRAPH_CACHE_MINUTES,
                () -> accessGraphGrouper.group(apiClient.inventoryAccessGraph(credential, sourceId)));
    }

    /** The Scan History tab, newest run first. */
    public DatastoreScanHistory scanHistory(Long clientId, String sourceId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.scanHistory(clientId, sourceId), SCAN_HISTORY, SCAN_HISTORY_CACHE_MINUTES,
                () -> drawerMapper.toScanHistory(apiClient.scanHistory(credential, sourceId)));
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    /** The {@code dssens} entry, else one sensitives call that writes both entries. */
    private DatastoreSensitives snapshot(MattersCredential credential, Long clientId, String sourceId) {
        Optional<DatastoreSensitives> cached = cache.get(DatastoreCacheKeys.sensitives(clientId, sourceId), SENSITIVES);
        return cached.isPresent() ? cached.get() : loadSensitives(credential, clientId, sourceId).sensitives();
    }

    /** One vendor response, two entries: the snapshot and the File View rows (samples are never read). */
    private SensitivesLoad loadSensitives(MattersCredential credential, Long clientId, String sourceId) {
        JsonNode body = apiClient.inventorySensitives(credential, sourceId);
        DatastoreSensitives sensitives = drawerMapper.toSensitives(body);
        List<DatastoreFilePage.FileRow> fileRows = drawerMapper.toFileRows(body);
        if (fileRows.size() > DatastoreDrawerMapper.MAX_FILE_ROWS) {
            log.info("[DSPM] file view for client {} capped at {} rows", clientId, DatastoreDrawerMapper.MAX_FILE_ROWS);
        }
        cache.put(DatastoreCacheKeys.sensitives(clientId, sourceId), sensitives, SENSITIVES_CACHE_MINUTES);
        cache.put(DatastoreCacheKeys.files(clientId, sourceId), fileRows, SENSITIVES_CACHE_MINUTES);
        return new SensitivesLoad(sensitives, fileRows);
    }

    private record SensitivesLoad(DatastoreSensitives sensitives, List<DatastoreFilePage.FileRow> fileRows) {
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
