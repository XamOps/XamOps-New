package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.dto.matters.datastores.SaasDrawerHeader;
import com.xammer.cloud.dto.matters.datastores.SaasFilePage;
import com.xammer.cloud.dto.matters.datastores.SaasRecords;
import com.xammer.cloud.dto.matters.datastores.SaasScanSummary;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The SaaS datastore drawer (Gmail, Google Drive): its header strip, the Sensitives tab's two records
 * views, the files behind one record and the Scan Summary tab (design 5.8). {@code sourceId} is the
 * row's validated, lower-cased 24-hex {@code source_id}.
 *
 * <p>Every method resolves the credential first, then reads the cache, then makes the vendor calls
 * listed, then caches the whitelisted DTO. A file page is requested only for a data element or document
 * class the datastore's own records list, because the vendor answers any other value with zero rows
 * (GAP-21).
 */
@Service
@RequiredArgsConstructor
public class SaasDrawerService {

    static final int HEADER_CACHE_MINUTES = 5;
    static final int RECORDS_CACHE_MINUTES = 5;
    static final int FILES_CACHE_MINUTES = 2;
    static final int SCAN_SUMMARY_CACHE_MINUTES = 5;

    private static final TypeReference<SaasDrawerHeader> HEADER = new TypeReference<SaasDrawerHeader>() {
    };
    private static final TypeReference<SaasRecords> RECORDS = new TypeReference<SaasRecords>() {
    };
    private static final TypeReference<SaasFilePage> FILE_PAGE = new TypeReference<SaasFilePage>() {
    };
    private static final TypeReference<SaasScanSummary> SCAN_SUMMARY = new TypeReference<SaasScanSummary>() {
    };

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final SaasDrawerMapper mapper;
    private final RedisCacheService cache;

    /** The drive type, account and scan states of the header strip. */
    public SaasDrawerHeader header(Long clientId, String sourceId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.saasHeader(clientId, sourceId), HEADER, HEADER_CACHE_MINUTES,
                () -> mapper.toHeader(apiClient.saasAssetDetail(credential, sourceId)));
    }

    /**
     * One Sensitives view.
     *
     * @param view validated: {@code data_elements} or {@code document_classes}.
     */
    public SaasRecords records(Long clientId, String sourceId, String view) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return records(credential, clientId, sourceId, view);
    }

    /**
     * One page of the files behind a data element or a document class. The value must be the name of a
     * row of the matching records view, read from the cache or fetched (and cached) on a miss, before
     * the files route is called.
     *
     * @throws DspmBadRequestException on {@code dataElement} or {@code documentClass} when no row has
     *                                 that name.
     */
    public SaasFilePage files(Long clientId, String sourceId, SaasFileQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        boolean dataElement = query.kind() == SaasFileQuery.Kind.DATA_ELEMENT;
        SaasRecords snapshot = records(credential, clientId, sourceId,
                dataElement ? DatastoreParams.VIEW_DATA_ELEMENTS : DatastoreParams.VIEW_DOCUMENT_CLASSES);
        boolean known = snapshot.rows().stream().anyMatch(row -> query.value().equals(row.name()));
        if (!known) {
            String parameter = dataElement ? "dataElement" : "documentClass";
            throw new DspmBadRequestException(parameter, parameter + " is not a row of this datastore");
        }
        return cached(DatastoreCacheKeys.saasFiles(clientId, sourceId, query), FILE_PAGE, FILES_CACHE_MINUTES,
                () -> mapper.toFilePage(apiClient.saasFiles(credential, sourceId, query), query.skip(),
                        query.limit()));
    }

    /** The Scan Summary tab. */
    public SaasScanSummary scanSummary(Long clientId, String sourceId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.saasScanSummary(clientId, sourceId), SCAN_SUMMARY,
                SCAN_SUMMARY_CACHE_MINUTES,
                () -> mapper.toScanSummary(apiClient.saasScanSummary(credential, sourceId)));
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    /** The {@code dssaasrec} entry of one view, else one records call that writes it. */
    private SaasRecords records(MattersCredential credential, Long clientId, String sourceId, String view) {
        return cached(DatastoreCacheKeys.saasRecords(clientId, sourceId, view), RECORDS, RECORDS_CACHE_MINUTES,
                () -> mapper.toRecords(apiClient.saasRecords(credential, sourceId, view), view));
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
