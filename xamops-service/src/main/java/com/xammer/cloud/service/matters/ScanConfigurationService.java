package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.pages.AssistList;
import com.xammer.cloud.dto.matters.scans.AssetRowSnapshot;
import com.xammer.cloud.dto.matters.scans.DatastoreDatabases;
import com.xammer.cloud.dto.matters.scans.DatastoreScanSettings;
import com.xammer.cloud.dto.matters.scans.DatastoreSyncStatus;
import com.xammer.cloud.dto.matters.scans.EndpointScanConfiguration;
import com.xammer.cloud.dto.matters.scans.SaasChannel;
import com.xammer.cloud.dto.matters.scans.SaasIntegrationList;
import com.xammer.cloud.dto.matters.scans.SaasScanConfiguration;
import com.xammer.cloud.dto.matters.scans.SaasScanUser;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Scan Configurations (the SaaS integrations, their scan configuration, users and channels, the endpoint
 * agent configuration) and the datastore drawer's Scan Settings, databases and sync status (build 6
 * design 4.4, 4.6, 5.8). Every configuration body is mapped by the {@link ScanSettingsMapper} whitelists.
 *
 * <p>Every method resolves the credential first, then reads the cache, then makes the vendor calls and
 * caches the whitelisted DTO. The assist ids of a datastore's scan settings never leave this class: they
 * are resolved to assist names from the Assists list.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScanConfigurationService {

    static final int SAAS_INTEGRATIONS_CACHE_MINUTES = 5;
    static final int SAAS_CONFIGURATION_CACHE_MINUTES = 2;
    static final int SAAS_USERS_CACHE_MINUTES = 1;
    static final int SAAS_CHANNELS_CACHE_MINUTES = 1;
    static final int ENDPOINT_CONFIGURATION_CACHE_MINUTES = 5;
    static final int SCAN_SETTINGS_CACHE_MINUTES = 2;
    static final int DATABASES_CACHE_MINUTES = 5;
    static final int SYNC_STATUS_CACHE_MINUTES = 1;

    private static final TypeReference<SaasIntegrationList> SAAS_INTEGRATIONS =
            new TypeReference<SaasIntegrationList>() {
            };
    private static final TypeReference<SaasScanConfiguration> SAAS_CONFIGURATION =
            new TypeReference<SaasScanConfiguration>() {
            };
    private static final TypeReference<DspmPage<SaasScanUser>> SAAS_USERS =
            new TypeReference<DspmPage<SaasScanUser>>() {
            };
    private static final TypeReference<DspmPage<SaasChannel>> SAAS_CHANNELS =
            new TypeReference<DspmPage<SaasChannel>>() {
            };
    private static final TypeReference<EndpointScanConfiguration> ENDPOINT_CONFIGURATION =
            new TypeReference<EndpointScanConfiguration>() {
            };
    private static final TypeReference<DatastoreScanSettings> SCAN_SETTINGS =
            new TypeReference<DatastoreScanSettings>() {
            };
    private static final TypeReference<DatastoreDatabases> DATABASES = new TypeReference<DatastoreDatabases>() {
    };
    private static final TypeReference<DatastoreSyncStatus> SYNC_STATUS = new TypeReference<DatastoreSyncStatus>() {
    };

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final AssetRowCollector collector;
    private final DatastoreAccountLabeler labeler;
    private final ScanSettingsMapper mapper;
    private final RedisCacheService cache;

    // ── Scan Configurations ──────────────────────────────────────────────────────────────────────

    /**
     * The SaaS integrations, from the SaaS rows (design 4.6): grouped by integration id in first-seen
     * order, each with the subtypes of a known console provider; an integration with none is left out. One
     * sample row per integration is labelled for its account label.
     */
    public SaasIntegrationList saasIntegrations(Long clientId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.saasIntegrations(clientId), SAAS_INTEGRATIONS,
                SAAS_INTEGRATIONS_CACHE_MINUTES, () -> integrations(credential, clientId));
    }

    public SaasScanConfiguration saasConfiguration(Long clientId, String integrationId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.saasConfiguration(clientId, integrationId), SAAS_CONFIGURATION,
                SAAS_CONFIGURATION_CACHE_MINUTES,
                () -> mapper.toSaasConfiguration(apiClient.saasScanConfiguration(credential, integrationId)));
    }

    public DspmPage<SaasScanUser> saasUsers(Long clientId, String integrationId, SaasUserQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.saasUsers(clientId, integrationId, query), SAAS_USERS,
                SAAS_USERS_CACHE_MINUTES,
                () -> mapper.toSaasUsers(apiClient.saasScanUsers(credential, integrationId, query), query.page(),
                        query.pageSize()));
    }

    public DspmPage<SaasChannel> saasChannels(Long clientId, String integrationId, SaasChannelQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.saasChannels(clientId, integrationId, query), SAAS_CHANNELS,
                SAAS_CHANNELS_CACHE_MINUTES,
                () -> mapper.toSaasChannels(apiClient.saasChannels(credential, integrationId, query), query.page(),
                        query.pageSize()));
    }

    public EndpointScanConfiguration endpointConfiguration(Long clientId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.endpointConfiguration(clientId), ENDPOINT_CONFIGURATION,
                ENDPOINT_CONFIGURATION_CACHE_MINUTES,
                () -> mapper.toEndpointConfiguration(apiClient.endpointAgentConfiguration(credential)));
    }

    // ── datastore drawer ─────────────────────────────────────────────────────────────────────────

    /**
     * A datastore's scan settings, with its export and discovery assists named. The names come from the
     * Assists list ({@code dsassists}, else one {@code /fetch/assist} call that writes it), read only when
     * the settings name an assist. An id with no matching assist gives a null name; a failed lookup gives
     * null names and caches no Assists list, while the settings are still cached.
     */
    public DatastoreScanSettings scanSettings(Long clientId, String sourceId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.scanSettings(clientId, sourceId), SCAN_SETTINGS, SCAN_SETTINGS_CACHE_MINUTES,
                () -> withAssistNames(credential, clientId,
                        mapper.toScanSettings(apiClient.defaultScanConfiguration(credential, sourceId))));
    }

    public DatastoreDatabases databases(Long clientId, String sourceId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.databases(clientId, sourceId), DATABASES, DATABASES_CACHE_MINUTES,
                () -> mapper.toDatabases(apiClient.databricksDatabases(credential, sourceId)));
    }

    public DatastoreSyncStatus syncStatus(Long clientId, String sourceId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.syncStatus(clientId, sourceId), SYNC_STATUS, SYNC_STATUS_CACHE_MINUTES,
                () -> mapper.toSyncStatus(apiClient.discoveryJobStatus(credential, sourceId)));
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    /** One SaaS integration while grouping: its first row, provider and known subtypes. */
    private static final class Group {
        private final DatastoreRowMapper.MappedRow sample;
        private String provider;
        private final List<String> driveTypes = new ArrayList<>();

        private Group(DatastoreRowMapper.MappedRow sample) {
            this.sample = sample;
        }
    }

    private SaasIntegrationList integrations(MattersCredential credential, Long clientId) {
        AssetRowSnapshot snapshot = collector.rows(credential, clientId, DatastoreTab.SAAS);
        Map<String, Group> groups = new LinkedHashMap<>();
        if (snapshot.rows() != null) {
            for (DatastoreRowMapper.MappedRow mapped : snapshot.rows()) {
                if (mapped == null || mapped.row() == null || mapped.integrationId() == null
                        || mapped.integrationId().isBlank()) {
                    continue;
                }
                Group group = groups.computeIfAbsent(mapped.integrationId(), id -> new Group(mapped));
                String subtype = mapped.row().subtype();
                String provider = PageLabels.saasProvider(subtype);
                if (provider == null) {
                    continue;
                }
                if (group.provider == null) {
                    group.provider = provider;
                }
                if (!group.driveTypes.contains(subtype)) {
                    group.driveTypes.add(subtype);
                }
            }
        }

        List<Map.Entry<String, Group>> listed = groups.entrySet().stream()
                .filter(entry -> entry.getValue().provider != null).toList();
        List<DatastoreRowMapper.MappedRow> samples = listed.stream().map(entry -> entry.getValue().sample).toList();
        List<DatastoreRow> labelled = samples.isEmpty() ? List.of() : labeler.label(credential, samples);

        List<SaasIntegrationList.SaasIntegration> integrations = new ArrayList<>();
        for (int i = 0; i < listed.size(); i++) {
            Group group = listed.get(i).getValue();
            DatastoreRow row = i < labelled.size() ? labelled.get(i) : null;
            String accountLabel = row == null || row.source() == null ? null : row.source().accountLabel();
            integrations.add(new SaasIntegrationList.SaasIntegration(listed.get(i).getKey(), group.provider,
                    accountLabel, List.copyOf(group.driveTypes)));
        }
        return new SaasIntegrationList(integrations, snapshot.truncated());
    }

    private DatastoreScanSettings withAssistNames(MattersCredential credential, Long clientId,
                                                  ScanSettingsMapper.Mapped mapped) {
        if (mapped.exportProxyId() == null && mapped.discoveryProxyId() == null) {
            return mapped.settings();
        }
        AssistList assists;
        try {
            assists = cached(DatastoreCacheKeys.assists(clientId), DspmPagesService.ASSISTS,
                    DspmPagesService.ASSISTS_CACHE_MINUTES, () -> mapper.toAssists(apiClient.fetchAssists(credential)));
        } catch (RuntimeException e) {
            String reason = e instanceof MattersApiException api ? String.valueOf(api.getReason())
                    : e.getClass().getSimpleName();
            log.debug("[DSPM] scan settings: assist names unavailable: {}", reason);
            return mapped.settings().withAssistNames(null, null);
        }
        return mapped.settings().withAssistNames(assistName(assists, mapped.exportProxyId()),
                assistName(assists, mapped.discoveryProxyId()));
    }

    private static String assistName(AssistList assists, String id) {
        if (id == null || assists == null || assists.assists() == null) {
            return null;
        }
        for (AssistList.Assist assist : assists.assists()) {
            if (assist != null && id.equalsIgnoreCase(assist.id())) {
                return assist.name();
            }
        }
        return null;
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
