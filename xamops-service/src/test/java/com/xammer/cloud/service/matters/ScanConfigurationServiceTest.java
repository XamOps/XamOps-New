package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.pages.AssistList;
import com.xammer.cloud.dto.matters.scans.DatastoreScanSettings;
import com.xammer.cloud.dto.matters.scans.SaasIntegrationList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Build 6 design 4.6 and 5.8 flows of Scan Configurations and the datastore drawer extras, with the real
 * mappers and collector, a mocked client and labeler, and an in-memory cache. Every value is invented;
 * settings bodies carry {@link #MARKER} in fields the whitelists drop.
 */
class ScanConfigurationServiceTest {

    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String MARKER = "MK_SYNTHETIC_93D1";
    private static final String GOOGLE = "aaaaaaaaaaaaaaaaaaaaaaa1";
    private static final String UNMAPPED = "bbbbbbbbbbbbbbbbbbbbbbb2";
    private static final String SLACK = "ccccccccccccccccccccccc3";
    private static final String SOURCE_ID = "0123456789abcdef01234567";
    private static final String EXPORT_ID = "feedfacefeedfacefeedface";
    private static final String DISCOVERY_ID = "0badc0de0badc0de0badc0de";

    private static final String SAAS_CONFIGURATION = """
            {"success": true, "password": "%1$s", "configuration": {"saas_integration_id": "%2$s",
              "is_realtime_enabled": true, "sampling_percentage": 30, "token": "%1$s",
              "scopes": [{"drive_type": "GMAIL", "scope_type": "ALL_USERS", "secret": "%1$s"}]}}
            """.formatted(MARKER, GOOGLE);
    private static final String USERS = """
            {"success": true, "count": 1, "assets": [{"id": "u1", "email": "ann@example.test", "status": "ACTIVE",
              "is_selected": true, "account_id": "%1$s"}]}
            """.formatted(MARKER);
    private static final String CHANNELS = """
            {"success": true, "count": 1, "channels": [{"id": "c1", "name": "general", "channel_id": "%1$s"}]}
            """.formatted(MARKER);
    private static final String ENDPOINT = """
            {"success": true, "data": {"scan_folder_paths": ["/Users"], "bucket_name": "%1$s",
              "file_scan_config": {"scan_mode": "LOCAL", "fileserver_url": "https://%1$s.example.test"}}}
            """.formatted(MARKER);
    private static final String DATABASES = """
            {"success": true, "databases_list": ["analytics", "finance"], "uri": "%1$s"}
            """.formatted(MARKER);
    private static final String SYNC = """
            {"success": true, "response": {"status": "IN_PROGRESS", "updated_at": 1700000000, "job": "%1$s"}}
            """.formatted(MARKER);

    private static final SaasUserQuery USER_QUERY = new SaasUserQuery("GMAIL", "ann", null, null, 1, 10);
    private static final SaasChannelQuery CHANNEL_QUERY =
            new SaasChannelQuery(null, "PUBLIC", null, null, true, 1, 10);

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> events = new ArrayList<>();

    private MattersCredentialResolver resolver;
    private MattersApiClient apiClient;
    private DatastoreAccountLabeler labeler;
    private InMemoryRedisCache cache;
    private ScanConfigurationService service;

    @BeforeEach
    void setUp() {
        resolver = mock(MattersCredentialResolver.class);
        apiClient = mock(MattersApiClient.class);
        labeler = mock(DatastoreAccountLabeler.class);
        cache = new InMemoryRedisCache(events);
        when(resolver.resolve(10L)).thenAnswer(inv -> {
            events.add("resolve");
            return CREDENTIAL;
        });
        when(apiClient.listAssets(eq(CREDENTIAL), anyMap())).thenReturn(AssetBodies.page(5, List.of(
                AssetBodies.asset(1, "saas", "GMAIL", "GOOGLE_WORKSPACE", GOOGLE, 5),
                AssetBodies.asset(2, "saas", "UNKNOWN_DRIVE", "UNKNOWN", UNMAPPED, 3),
                AssetBodies.asset(3, "saas", "GOOGLE_WORKSPACE", "GOOGLE_WORKSPACE", GOOGLE, 9),
                AssetBodies.asset(4, "saas", "SLACK", "SLACK", SLACK, 1),
                AssetBodies.asset(5, "saas", "GMAIL", "GOOGLE_WORKSPACE", GOOGLE, 2))));
        when(labeler.label(eq(CREDENTIAL), anyList())).thenAnswer(inv -> {
            List<DatastoreRowMapper.MappedRow> rows = inv.getArgument(1);
            return rows.stream().map(mapped -> labelled(mapped.row(), "acct-" + mapped.integrationId())).toList();
        });
        when(apiClient.saasScanConfiguration(CREDENTIAL, GOOGLE)).thenAnswer(inv -> parse(SAAS_CONFIGURATION));
        when(apiClient.saasScanUsers(CREDENTIAL, GOOGLE, USER_QUERY)).thenAnswer(inv -> parse(USERS));
        when(apiClient.saasChannels(CREDENTIAL, SLACK, CHANNEL_QUERY)).thenAnswer(inv -> parse(CHANNELS));
        when(apiClient.endpointAgentConfiguration(CREDENTIAL)).thenAnswer(inv -> parse(ENDPOINT));
        when(apiClient.databricksDatabases(CREDENTIAL, SOURCE_ID)).thenAnswer(inv -> parse(DATABASES));
        when(apiClient.discoveryJobStatus(CREDENTIAL, SOURCE_ID)).thenAnswer(inv -> parse(SYNC));
        when(apiClient.defaultScanConfiguration(CREDENTIAL, SOURCE_ID))
                .thenAnswer(inv -> parse(scanSettings(EXPORT_ID, DISCOVERY_ID)));
        when(apiClient.fetchAssists(CREDENTIAL)).thenAnswer(inv -> parse("""
                {"success": true, "assists": [{"id": "%s", "proxy_name": "Export connector", "health_status": "ACTIVE"}]}
                """.formatted(EXPORT_ID)));
        DatastoreRowMapper rowMapper = new DatastoreRowMapper();
        service = new ScanConfigurationService(resolver, apiClient, new AssetRowCollector(apiClient, rowMapper, cache),
                labeler, new ScanSettingsMapper(), cache);
    }

    private static String scanSettings(String exportId, String discoveryId) {
        StringBuilder ids = new StringBuilder();
        if (exportId != null) {
            ids.append("\"exportProxyId\": \"").append(exportId).append("\", ");
        }
        if (discoveryId != null) {
            ids.append("\"discoveryProxyId\": \"").append(discoveryId).append("\", ");
        }
        return """
                {"success": true, "default_scan_parameters": {%s"parallelism": 4, "isSaved": true,
                  "password": "%s", "userName": "%s", "uri": "postgres://u:%s@h/db"},
                 "selected_databases_list": "ALL"}
                """.formatted(ids, MARKER, MARKER, MARKER);
    }

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static DatastoreRow labelled(DatastoreRow row, String label) {
        DatastoreRow.Source source = row.source();
        return new DatastoreRow(row.id(), row.sourceId(), row.name(), row.category(), row.subtype(),
                row.subtypeLabel(), row.risk(), row.sensitives(),
                new DatastoreRow.Source(source.provider(), source.providerLabel(), label, source.region()),
                row.scan(), row.agent());
    }

    /** Every method, keyed by the cache key it reads first, with the TTL it writes. */
    private Map<String, Map.Entry<Integer, Runnable>> everyMethod() {
        Map<String, Map.Entry<Integer, Runnable>> calls = new LinkedHashMap<>();
        calls.put("dspm:dssaasint:10:all", Map.entry(5, () -> service.saasIntegrations(10L)));
        calls.put("dspm:dssaascfg:10:" + GOOGLE, Map.entry(2, () -> service.saasConfiguration(10L, GOOGLE)));
        calls.put(DatastoreCacheKeys.saasUsers(10L, GOOGLE, USER_QUERY),
                Map.entry(1, () -> service.saasUsers(10L, GOOGLE, USER_QUERY)));
        calls.put(DatastoreCacheKeys.saasChannels(10L, SLACK, CHANNEL_QUERY),
                Map.entry(1, () -> service.saasChannels(10L, SLACK, CHANNEL_QUERY)));
        calls.put("dspm:dsepcfg:10:all", Map.entry(5, () -> service.endpointConfiguration(10L)));
        calls.put("dspm:dsscanset:10:" + SOURCE_ID, Map.entry(2, () -> service.scanSettings(10L, SOURCE_ID)));
        calls.put("dspm:dsdbs:10:" + SOURCE_ID, Map.entry(5, () -> service.databases(10L, SOURCE_ID)));
        calls.put("dspm:dssync:10:" + SOURCE_ID, Map.entry(1, () -> service.syncStatus(10L, SOURCE_ID)));
        return calls;
    }

    // ── order of checks and caching ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the credential is resolved before the cache read, on a miss and on a hit")
    void credentialFirst() {
        for (int round = 0; round < 2; round++) {
            everyMethod().forEach((key, call) -> {
                events.clear();
                call.getValue().run();
                assertThat(events).as(key).startsWith("resolve", "get " + key);
            });
        }
    }

    @Test
    @DisplayName("NOT_ENTITLED stops every method before the cache and the vendor")
    void notEntitled() {
        doThrow(new MattersApiException(MattersApiException.Reason.NOT_ENTITLED, "not entitled"))
                .when(resolver).resolve(10L);

        everyMethod().forEach((key, call) -> assertThatThrownBy(call.getValue()::run).as(key)
                .isInstanceOf(MattersApiException.class));
        verifyNoInteractions(apiClient, labeler);
        assertThat(cache.gets).isEmpty();
    }

    @Test
    @DisplayName("each DTO is cached under its 5.8 key and TTL; a hit makes no vendor call; no marker is cached")
    void cached() {
        for (int round = 0; round < 2; round++) {
            everyMethod().values().forEach(call -> call.getValue().run());
        }

        verify(apiClient, times(1)).listAssets(eq(CREDENTIAL), anyMap());
        verify(apiClient, times(1)).saasScanConfiguration(CREDENTIAL, GOOGLE);
        verify(apiClient, times(1)).saasScanUsers(CREDENTIAL, GOOGLE, USER_QUERY);
        verify(apiClient, times(1)).saasChannels(CREDENTIAL, SLACK, CHANNEL_QUERY);
        verify(apiClient, times(1)).endpointAgentConfiguration(CREDENTIAL);
        verify(apiClient, times(1)).defaultScanConfiguration(CREDENTIAL, SOURCE_ID);
        verify(apiClient, times(1)).fetchAssists(CREDENTIAL);
        verify(apiClient, times(1)).databricksDatabases(CREDENTIAL, SOURCE_ID);
        verify(apiClient, times(1)).discoveryJobStatus(CREDENTIAL, SOURCE_ID);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes)
                .containsExactly(
                        tuple("dspm:dsrows:10:saas", 2),
                        tuple("dspm:dssaasint:10:all", 5),
                        tuple("dspm:dssaascfg:10:" + GOOGLE, 2),
                        tuple(DatastoreCacheKeys.saasUsers(10L, GOOGLE, USER_QUERY), 1),
                        tuple(DatastoreCacheKeys.saasChannels(10L, SLACK, CHANNEL_QUERY), 1),
                        tuple("dspm:dsepcfg:10:all", 5),
                        tuple("dspm:dsassists:10:all", 5),
                        tuple("dspm:dsscanset:10:" + SOURCE_ID, 2),
                        tuple("dspm:dsdbs:10:" + SOURCE_ID, 5),
                        tuple("dspm:dssync:10:" + SOURCE_ID, 1));
        assertThat(cache.puts).allSatisfy(put -> assertThat(put.json()).as(put.key()).doesNotContain(MARKER));
    }

    // ── SaaS integrations (design 4.6) ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("GMAIL and GOOGLE_WORKSPACE rows of one integration give one Google Workspace; an unmapped subtype is left out")
    void providerGrouping() {
        SaasIntegrationList list = service.saasIntegrations(10L);

        assertThat(list.integrations()).extracting(SaasIntegrationList.SaasIntegration::id,
                        SaasIntegrationList.SaasIntegration::provider, SaasIntegrationList.SaasIntegration::accountLabel,
                        SaasIntegrationList.SaasIntegration::driveTypes)
                .containsExactly(
                        tuple(GOOGLE, "GOOGLE_WORKSPACE", "acct-" + GOOGLE, List.of("GMAIL", "GOOGLE_WORKSPACE")),
                        tuple(SLACK, "SLACK", "acct-" + SLACK, List.of("SLACK")));
        assertThat(list.partial()).isFalse();
        // one sample row per listed integration is labelled, the unmapped one is not
        verify(labeler).label(eq(CREDENTIAL), org.mockito.ArgumentMatchers.argThat(
                (List<DatastoreRowMapper.MappedRow> rows) -> rows.stream().map(DatastoreRowMapper.MappedRow::integrationId)
                        .toList().equals(List.of(GOOGLE, SLACK))));
    }

    @Test
    @DisplayName("the SaaS rows come from the shared dsrows entry; a truncated snapshot makes the list partial")
    void sharedSnapshotAndPartial() {
        List<DatastoreRowMapper.MappedRow> rows = new DatastoreRowMapper().toMappedRows(AssetBodies.page(2_000, List.of(
                AssetBodies.asset(9, "saas", "SLACK", "SLACK", SLACK, 1))));
        cache.seed("dspm:dsrows:10:saas", new com.xammer.cloud.dto.matters.scans.AssetRowSnapshot(rows, 2_000, true));

        SaasIntegrationList list = service.saasIntegrations(10L);

        verify(apiClient, never()).listAssets(any(), anyMap());
        assertThat(list.partial()).isTrue();
        assertThat(list.integrations()).extracting(SaasIntegrationList.SaasIntegration::id).containsExactly(SLACK);
    }

    @Test
    @DisplayName("no SaaS integration of a known provider gives an empty list and no labeler call")
    void noIntegrations() {
        when(apiClient.listAssets(eq(CREDENTIAL), anyMap())).thenReturn(AssetBodies.page(1, List.of(
                AssetBodies.asset(1, "saas", "UNKNOWN_DRIVE", "UNKNOWN", UNMAPPED, 1))));

        assertThat(service.saasIntegrations(10L).integrations()).isEmpty();
        verifyNoInteractions(labeler);
    }

    // ── scan settings: assist names ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("assist names come from a cached Assists list; an id with no assist gives null; no id leaves the backend")
    void assistNamesFromTheCachedList() {
        cache.seed("dspm:dsassists:10:all", new AssistList(List.of(new AssistList.Assist(DISCOVERY_ID, "Discovery box",
                null, "ACTIVE", "Active", null, null, null, null))));

        DatastoreScanSettings settings = service.scanSettings(10L, SOURCE_ID);

        assertThat(settings.discoveryAssistName()).isEqualTo("Discovery box");
        assertThat(settings.exportAssistName()).isNull();
        verify(apiClient, never()).fetchAssists(any());
        assertThat(cache.putsFor("dspm:dsscanset:10:" + SOURCE_ID)).singleElement()
                .satisfies(put -> assertThat(put.json()).doesNotContain(EXPORT_ID, DISCOVERY_ID, MARKER));
    }

    @Test
    @DisplayName("on a miss the Assists list is fetched once and cached")
    void assistListFetchedOnAMiss() {
        DatastoreScanSettings settings = service.scanSettings(10L, SOURCE_ID);

        assertThat(settings.exportAssistName()).isEqualTo("Export connector");
        assertThat(settings.discoveryAssistName()).isNull();
        assertThat(settings.allDatabases()).isTrue();
        assertThat(cache.contains("dspm:dsassists:10:all")).isTrue();
    }

    @Test
    @DisplayName("settings without an assist id make no assist lookup")
    void noAssistIdNoLookup() {
        when(apiClient.defaultScanConfiguration(CREDENTIAL, SOURCE_ID)).thenAnswer(inv -> parse(scanSettings(null, null)));

        DatastoreScanSettings settings = service.scanSettings(10L, SOURCE_ID);

        assertThat(settings.exportAssistName()).isNull();
        assertThat(settings.discoveryAssistName()).isNull();
        verify(apiClient, never()).fetchAssists(any());
        assertThat(cache.gets).doesNotContain("dspm:dsassists:10:all");
    }

    @Test
    @DisplayName("a failed assist lookup gives null names; the settings are cached, the assist list is not")
    void assistLookupFailure() {
        when(apiClient.fetchAssists(CREDENTIAL)).thenThrow(
                new MattersApiException(MattersApiException.Reason.FORBIDDEN_SCOPE, "needs the PLATFORM scope"));

        DatastoreScanSettings settings = service.scanSettings(10L, SOURCE_ID);

        assertThat(settings.exportAssistName()).isNull();
        assertThat(settings.discoveryAssistName()).isNull();
        assertThat(settings.parameters().parallelism()).isEqualTo(4);
        assertThat(cache.contains("dspm:dsscanset:10:" + SOURCE_ID)).isTrue();
        assertThat(cache.contains("dspm:dsassists:10:all")).isFalse();
    }
}
