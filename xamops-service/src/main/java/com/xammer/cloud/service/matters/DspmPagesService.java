package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.dto.matters.pages.AssistList;
import com.xammer.cloud.dto.matters.pages.DocumentClassList;
import com.xammer.cloud.dto.matters.pages.RemediationActivity;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The single-resource XamLens pages (build 6 design 5.8): Document Classes, Assists and Remediation.
 *
 * <p>Every method resolves the credential first, then reads the cache, then makes the one vendor call and
 * caches the whitelisted DTO.
 */
@Service
@RequiredArgsConstructor
public class DspmPagesService {

    static final int DOCUMENT_CLASSES_CACHE_MINUTES = 10;
    static final int ASSISTS_CACHE_MINUTES = 5;
    static final int REMEDIATION_CACHE_MINUTES = 1;

    static final TypeReference<AssistList> ASSISTS = new TypeReference<AssistList>() {
    };
    private static final TypeReference<DocumentClassList> DOCUMENT_CLASSES = new TypeReference<DocumentClassList>() {
    };
    private static final TypeReference<RemediationActivity> REMEDIATION = new TypeReference<RemediationActivity>() {
    };

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final PagesMapper pagesMapper;
    private final ScanSettingsMapper settingsMapper;
    private final RedisCacheService cache;

    public DocumentClassList documentClasses(Long clientId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.documentClasses(clientId), DOCUMENT_CLASSES, DOCUMENT_CLASSES_CACHE_MINUTES,
                () -> pagesMapper.toDocumentClasses(apiClient.saasCategories(credential)));
    }

    /** The on-prem connectors; their identities pass the settings whitelist ({@link ScanSettingsMapper}). */
    public AssistList assists(Long clientId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.assists(clientId), ASSISTS, ASSISTS_CACHE_MINUTES,
                () -> settingsMapper.toAssists(apiClient.fetchAssists(credential)));
    }

    public RemediationActivity remediation(Long clientId, RemediationQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.remediation(clientId, query), REMEDIATION, REMEDIATION_CACHE_MINUTES,
                () -> pagesMapper.toRemediation(apiClient.remediationActivity(credential, query), query.skip(),
                        query.limit()));
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
