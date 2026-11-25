package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.pages.DspmDashboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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
 * Build 6 design 5.8 Dashboard flow with the real summary reader, collector and mapper, a mocked client,
 * a mocked labeler and an in-memory cache. Every value is invented.
 */
class DashboardServiceTest {

    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String INTEGRATION = "abcdef0123456789abcdef01";

    private static final String SUMMARY_BODY = """
            {"cards": {"total_assets": {"value": 9}},
             "tabs": {"cloud": {"value": 3, "total_sensitive_records": {"value": 70,
                         "trend": [{"date": "2026-01-01", "value": 60}, {"date": "2026-01-02", "value": 70}]}},
                      "saas": {"value": 2, "total_sensitive_records": {"value": 30}},
                      "endpoint": {"value": 1, "total_sensitive_records": {"value": 4}}}}
            """;

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> events = new ArrayList<>();

    private MattersCredentialResolver resolver;
    private MattersApiClient apiClient;
    private DatastoreAccountLabeler labeler;
    private InMemoryRedisCache cache;
    private DashboardService service;

    @BeforeEach
    void setUp() throws Exception {
        resolver = mock(MattersCredentialResolver.class);
        apiClient = mock(MattersApiClient.class);
        labeler = mock(DatastoreAccountLabeler.class);
        cache = new InMemoryRedisCache(events);
        when(resolver.resolve(10L)).thenAnswer(inv -> {
            events.add("resolve");
            return CREDENTIAL;
        });
        JsonNode summary = json.readTree(SUMMARY_BODY);
        when(apiClient.summary(CREDENTIAL, null)).thenReturn(summary);
        when(apiClient.listAssets(eq(CREDENTIAL), anyMap())).thenAnswer(inv -> {
            Map<String, List<String>> query = inv.getArgument(1);
            List<String> category = query.get("category");
            return assets(category == null ? "all" : category.get(0));
        });
        when(labeler.label(eq(CREDENTIAL), anyList())).thenAnswer(inv -> {
            List<DatastoreRowMapper.MappedRow> rows = inv.getArgument(1);
            return rows.stream().map(mapped -> labelled(mapped.row(), "acct-" + mapped.integrationId())).toList();
        });
        DatastoreRowMapper rowMapper = new DatastoreRowMapper();
        DatastoreListService listService = new DatastoreListService(resolver, apiClient, rowMapper, labeler, cache);
        service = new DashboardService(resolver, listService, new AssetRowCollector(apiClient, rowMapper, cache),
                new DashboardMapper(), labeler, cache);
    }

    private static DatastoreRow labelled(DatastoreRow row, String label) {
        DatastoreRow.Source source = row.source();
        return new DatastoreRow(row.id(), row.sourceId(), row.name(), row.category(), row.subtype(),
                row.subtypeLabel(), row.risk(), row.sensitives(),
                new DatastoreRow.Source(source.provider(), source.providerLabel(), label, source.region()),
                row.scan(), row.agent());
    }

    private static JsonNode assets(String category) {
        return switch (category) {
            case "cloud" -> AssetBodies.page(3, List.of(
                    AssetBodies.asset(1, "cloud", "S3", "S3", INTEGRATION, 40),
                    AssetBodies.asset(2, "cloud", "S3", "S3", INTEGRATION, 20),
                    AssetBodies.asset(3, "cloud", "RDS", "RDS", INTEGRATION, 0)));
            case "saas" -> AssetBodies.page(2, List.of(
                    AssetBodies.asset(4, "saas", "GOOGLE_WORKSPACE", "GOOGLE_WORKSPACE", INTEGRATION, 25),
                    AssetBodies.asset(5, "saas", "GMAIL", "GOOGLE_WORKSPACE", INTEGRATION, 5)));
            case "endpoint" -> AssetBodies.page(1, List.of(
                    AssetBodies.asset(6, "endpoint", "MAC", null, null, 4)));
            case "on_prem" -> AssetBodies.page(0, List.of());
            default -> AssetBodies.page(6, List.of(
                    AssetBodies.asset(1, "cloud", "S3", "S3", INTEGRATION, 40),
                    AssetBodies.asset(4, "saas", "GOOGLE_WORKSPACE", "GOOGLE_WORKSPACE", INTEGRATION, 25)));
        };
    }

    @Test
    @DisplayName("the credential is resolved before the dsdash read, also on a cache hit")
    void credentialFirst() {
        service.dashboard(10L);
        events.clear();
        service.dashboard(10L);

        assertThat(events).containsExactly("resolve", "get dspm:dsdash:10:all");
    }

    @Test
    @DisplayName("NOT_ENTITLED stops the Dashboard before the cache and the vendor")
    void notEntitled() {
        doThrow(new MattersApiException(MattersApiException.Reason.NOT_ENTITLED, "not entitled"))
                .when(resolver).resolve(10L);

        assertThatThrownBy(() -> service.dashboard(10L)).isInstanceOf(MattersApiException.class);
        verifyNoInteractions(apiClient, labeler);
        assertThat(cache.gets).isEmpty();
    }

    @Test
    @DisplayName("summary, five row snapshots and the dashboard are cached under the 5.8 keys; a hit calls nothing")
    void cachedUnderTheDesignKeys() {
        service.dashboard(10L);
        service.dashboard(10L);

        verify(apiClient, times(1)).summary(CREDENTIAL, null);
        verify(apiClient, times(5)).listAssets(eq(CREDENTIAL), anyMap());
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes)
                .containsExactly(
                        tuple("dspm:dssummary:10:all", 5),
                        tuple("dspm:dsrows:10:all", 2),
                        tuple("dspm:dsrows:10:cloud", 2),
                        tuple("dspm:dsrows:10:saas", 2),
                        tuple("dspm:dsrows:10:endpoint", 2),
                        tuple("dspm:dsrows:10:on_prem", 2),
                        tuple("dspm:dsdash:10:all", 5));
    }

    @Test
    @DisplayName("cards come from the summary (trend included), bars from the rows, top rows are labelled")
    void dashboardContent() {
        DspmDashboard dashboard = service.dashboard(10L);

        assertThat(dashboard.environments()).extracting(DspmDashboard.Environment::id,
                        DspmDashboard.Environment::datastores, DspmDashboard.Environment::sensitiveRecords,
                        DspmDashboard.Environment::connected, DspmDashboard.Environment::filter)
                .containsExactly(tuple("cloud", 3L, 70L, true, "provider"), tuple("saas", 2L, 30L, true, null),
                        tuple("endpoint", 1L, 4L, true, "os"), tuple("on_prem", 0L, 0L, false, "provider"));
        DspmDashboard.Environment cloud = dashboard.environments().get(0);
        assertThat(cloud.recordsTrend()).extracting(p -> p.value()).containsExactly(60L, 70L);
        assertThat(cloud.subCategories()).extracting(DspmDashboard.SubCategory::key,
                        DspmDashboard.SubCategory::sensitiveRecords, DspmDashboard.SubCategory::datastores)
                .containsExactly(tuple("S3", 60L, 2L), tuple("RDS", 0L, 1L));
        assertThat(dashboard.environments().get(1).subCategories()).extracting(DspmDashboard.SubCategory::key)
                .containsExactly("GOOGLE_WORKSPACE", "GMAIL");
        // Active Sensitive Regions from the cloud rows only (every synthetic row is CLASSIFIED in one region)
        assertThat(dashboard.regions()).containsExactly(
                new DspmDashboard.Region("test-region-1", 3, 3, List.of()));
        assertThat(service.dashboard(10L).regions()).isEqualTo(dashboard.regions());

        assertThat(dashboard.topRisky().keySet()).containsExactly("all", "cloud", "saas", "endpoint", "on_prem");
        assertThat(dashboard.topRisky().get("cloud")).extracting(DatastoreRow::name).hasSize(2);
        assertThat(dashboard.topRisky().get("cloud")).allSatisfy(row ->
                assertThat(row.source().accountLabel()).isEqualTo("acct-" + INTEGRATION));
        assertThat(dashboard.topRisky().get("on_prem")).isEmpty();
        // the labeler is not called for a tab without top rows (on_prem)
        verify(labeler, times(4)).label(eq(CREDENTIAL), anyList());
    }

    @Test
    @DisplayName("a failed asset page propagates and caches no dashboard")
    void failureCachesNothing() {
        when(apiClient.listAssets(eq(CREDENTIAL), anyMap())).thenThrow(
                new MattersApiException(MattersApiException.Reason.UPSTREAM_UNAVAILABLE, "unreachable"));

        assertThatThrownBy(() -> service.dashboard(10L)).isInstanceOf(MattersApiException.class);
        assertThat(cache.contains("dspm:dsdash:10:all")).isFalse();
        verify(labeler, never()).label(eq(CREDENTIAL), anyList());
    }
}
