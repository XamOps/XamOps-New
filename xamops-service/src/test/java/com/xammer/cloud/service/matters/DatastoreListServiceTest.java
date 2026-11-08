package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.DatastoreKpis;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Design 5.9 list flows, with the real mapper, a mocked client and labeler, and an in-memory cache. */
class DatastoreListServiceTest {

    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String INTEGRATION = "000000000000000000000001";

    private static final String LIST_BODY = """
            {"data":[
              {"id":"asset-1","source_id":"500000000000000000000001","name":"store-a","category":"cloud",
               "subtype":"S3","risk":{"level":"HIGH","score":7.5},"sensitives":{"record_count":12,"types":["PII"]},
               "source":{"account":"000000000000000000000001","provider":"S3","region":"eu-west-1"},
               "scan_status":{"status":"CLASSIFIED","last_scanned_at":1700000000}}],
             "pagination":{"page":7,"page_size":50,"total":42}}
            """;

    private static final String FILTERS_BODY = """
            {"integration_id":[{"value":"000000000000000000000001","count":4}],
             "provider":[{"value":"S3","count":4}],
             "scan_status":[{"value":"CLASSIFIED","count":3},{"value":"FAILED","count":1},{"value":"STOPPED","count":2}],
             "lifecycle":[{"value":"ACTIVE","count":4}],
             "paging":{"integration_id":{"has_more":false}}}
            """;

    private static final String SUMMARY_BODY = """
            {"cards":{"by_status":{"CLASSIFIED":20,"FAILED":2,"NOT_SCANNED":5,"SCANNING":1,"STOPPED":3},
              "critical_high":{"value":4,"trend":[]},
              "total_assets":{"value":31,"trend":[{"date":"2026-01-01","value":30},{"date":"2026-01-02","value":31}]},
              "total_sensitive_records":{"value":900,"trend":[]}},
             "tabs":{
              "all":{"value":31,"trend":[],"total_sensitive_records":{"value":900,"trend":[]},"critical_high":{"value":4}},
              "cloud":{"value":12,"trend":[],"total_sensitive_records":{"value":500,"trend":[]},"critical_high":{"value":3}},
              "saas":{"value":10,"trend":[],"total_sensitive_records":{"value":300,"trend":[]},"critical_high":{"value":1}},
              "endpoint":{"value":4,"trend":[],"total_sensitive_records":{"value":50,"trend":[]},"critical_high":{"value":0}},
              "on_prem":{"value":5,"trend":[],"total_sensitive_records":{"value":50,"trend":[]},"critical_high":{"value":0}}}}
            """;

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> events = new ArrayList<>();

    private MattersCredentialResolver resolver;
    private MattersApiClient apiClient;
    private DatastoreAccountLabeler labeler;
    private InMemoryRedisCache cache;
    private DatastoreListService service;

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
        when(labeler.label(eq(CREDENTIAL), anyList())).thenAnswer(inv -> labelRows(inv.getArgument(1)));
        when(labeler.label(eq(CREDENTIAL), any(DatastoreFilterOptions.class)))
                .thenAnswer(inv -> labelOptions(inv.getArgument(1)));
        when(apiClient.listAssets(eq(CREDENTIAL), any())).thenAnswer(inv -> parse(LIST_BODY));
        when(apiClient.listFilters(eq(CREDENTIAL), any())).thenAnswer(inv -> parse(FILTERS_BODY));
        when(apiClient.summary(CREDENTIAL, null)).thenAnswer(inv -> parse(SUMMARY_BODY));
        service = new DatastoreListService(resolver, apiClient, new DatastoreRowMapper(), labeler, cache);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MultiValueMap<String, String> params(String... pairs) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.add(pairs[i], pairs[i + 1]);
        }
        return params;
    }

    private static List<DatastoreRow> labelRows(List<DatastoreRowMapper.MappedRow> rows) {
        return rows.stream().map(mapped -> {
            DatastoreRow row = mapped.row();
            DatastoreRow.Source source = row.source();
            return new DatastoreRow(row.id(), row.sourceId(), row.name(), row.category(), row.subtype(),
                    row.subtypeLabel(), row.risk(), row.sensitives(),
                    new DatastoreRow.Source(source.provider(), source.providerLabel(), "label-" + mapped.integrationId(),
                            source.region()), row.scan(), row.agent());
        }).toList();
    }

    private static DatastoreFilterOptions labelOptions(DatastoreFilterOptions options) {
        return new DatastoreFilterOptions(options.dimensions().stream()
                .map(dimension -> !"integration_id".equals(dimension.key()) ? dimension
                        : new DatastoreFilterOptions.Dimension(dimension.key(), dimension.options().stream()
                        .map(o -> new DatastoreFilterOptions.Option(o.value(), "label-" + o.value(), o.count()))
                        .toList(), dimension.hasMore()))
                .toList());
    }

    // ── order of checks ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the credential is resolved before any cache read, on every method")
    void credentialFirst() {
        service.list(10L, DatastoreListQuery.forList(params("tab", "cloud")));
        assertThat(events.get(0)).isEqualTo("resolve");
        assertThat(events.get(1)).startsWith("get dspm:dslist:10:cloud:");

        events.clear();
        service.filterOptions(10L, DatastoreListQuery.forFilters(params("tab", "cloud")));
        assertThat(events.get(0)).isEqualTo("resolve");
        assertThat(events.get(1)).startsWith("get dspm:dsfacets:10:cloud:");

        events.clear();
        service.kpis(10L, DatastoreTab.CLOUD);
        assertThat(events.get(0)).isEqualTo("resolve");
        assertThat(events.get(1)).isEqualTo("get dspm:dssummary:10:all");
    }

    @Test
    @DisplayName("NOT_ENTITLED stops every method before the cache and the vendor")
    void notEntitled() {
        doThrow(new MattersApiException(MattersApiException.Reason.NOT_ENTITLED, "not entitled"))
                .when(resolver).resolve(10L);

        assertThatThrownBy(() -> service.list(10L, DatastoreListQuery.forList(params())))
                .isInstanceOf(MattersApiException.class);
        assertThatThrownBy(() -> service.filterOptions(10L, DatastoreListQuery.forFilters(params())))
                .isInstanceOf(MattersApiException.class);
        assertThatThrownBy(() -> service.kpis(10L, DatastoreTab.SAAS)).isInstanceOf(MattersApiException.class);

        verifyNoInteractions(apiClient, labeler);
        assertThat(cache.gets).isEmpty();
    }

    // ── list ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a list page is cached for 2 minutes under the client's key; a hit still resolves the credential")
    void listIsCached() {
        DatastoreListQuery query = DatastoreListQuery.forList(params("tab", "cloud", "q", "store"));

        DspmPage<DatastoreRow> first = service.list(10L, query);
        DspmPage<DatastoreRow> second = service.list(10L, query);

        verify(apiClient, times(1)).listAssets(CREDENTIAL, query.toVendorListParams());
        verify(resolver, times(2)).resolve(10L);
        assertThat(cache.puts).hasSize(1);
        assertThat(cache.puts.get(0).key()).isEqualTo(DatastoreCacheKeys.list(10L, query))
                .startsWith("dspm:dslist:10:cloud:");
        assertThat(cache.puts.get(0).minutes()).isEqualTo(2);
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("rows carry the labeler's account labels, and paging comes from the request")
    void listPagingAndLabels() {
        DspmPage<DatastoreRow> page = service.list(10L,
                DatastoreListQuery.forList(params("tab", "cloud", "page", "2", "pageSize", "10")));

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.total()).isEqualTo(42);
        assertThat(page.totalPages()).isEqualTo(5);
        assertThat(page.data()).extracting(row -> row.source().accountLabel()).containsExactly("label-" + INTEGRATION);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> sent = ArgumentCaptor.forClass(Map.class);
        verify(apiClient).listAssets(eq(CREDENTIAL), sent.capture());
        assertThat(sent.getValue()).containsEntry("category", List.of("cloud"))
                .containsEntry("page", List.of("2")).containsEntry("page_size", List.of("10"))
                .containsEntry("sort", List.of("-severity"));
    }

    // ── filter options and KPIs ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("facets are cached for 5 minutes without labels; labels are applied on every read")
    void filterOptionsLabelOnRead() {
        DatastoreListQuery query = DatastoreListQuery.forFilters(params("tab", "cloud"));

        DatastoreFilterOptions first = service.filterOptions(10L, query);
        DatastoreFilterOptions second = service.filterOptions(10L, query);

        verify(apiClient, times(1)).listFilters(CREDENTIAL, query.toVendorFilterParams());
        verify(labeler, times(2)).label(eq(CREDENTIAL), any(DatastoreFilterOptions.class));
        assertThat(cache.puts).hasSize(1);
        InMemoryRedisCache.Put put = cache.puts.get(0);
        assertThat(put.key()).isEqualTo(DatastoreCacheKeys.facets(10L, query));
        assertThat(put.minutes()).isEqualTo(5);
        assertThat(put.json()).doesNotContain("label-").contains("\"label\":\"" + INTEGRATION + "\"");

        assertThat(first).isEqualTo(second);
        assertThat(first.dimensions()).extracting(DatastoreFilterOptions.Dimension::key)
                .containsExactly("integration_id", "provider", "scan_status");
        assertThat(first.dimensions().get(0).options().get(0).label()).isEqualTo("label-" + INTEGRATION);
    }

    @Test
    @DisplayName("a tab's KPIs reuse the facets entry of its unfiltered filter-options load")
    void kpisShareTheFacets() {
        service.filterOptions(10L, DatastoreListQuery.forFilters(params("tab", "cloud")));
        DatastoreKpis kpis = service.kpis(10L, DatastoreTab.CLOUD);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> sent = ArgumentCaptor.forClass(Map.class);
        verify(apiClient, times(1)).listFilters(eq(CREDENTIAL), sent.capture());
        assertThat(sent.getValue()).containsExactly(Map.entry("category", List.of("cloud")));
        // Labels are applied for the filter-options call only, never for the KPIs.
        verify(labeler, times(1)).label(eq(CREDENTIAL), any(DatastoreFilterOptions.class));

        assertThat(kpis.tab()).isEqualTo("cloud");
        assertThat(kpis.classified()).isEqualTo(3);
        assertThat(kpis.failed()).isEqualTo(1);
        assertThat(kpis.stopped()).isEqualTo(2);
        assertThat(kpis.scheduled()).isZero();
        assertThat(kpis.assetCount().value()).isEqualTo(12);
        assertThat(kpis.totalSensitiveRecords().value()).isEqualTo(500);
        assertThat(kpis.criticalHigh()).isEqualTo(3);
        assertThat(kpis.tabCounts()).containsEntry("all", 31L).containsEntry("on_prem", 5L);
    }

    @Test
    @DisplayName("All reads only the summary, cached for 5 minutes under dspm:dssummary:<c>:all")
    void allTabKpis() {
        DatastoreKpis kpis = service.kpis(10L, DatastoreTab.ALL);
        service.kpis(10L, DatastoreTab.ALL);

        verify(apiClient, times(1)).summary(CREDENTIAL, null);
        verify(apiClient, never()).listFilters(any(), any());
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key).containsExactly("dspm:dssummary:10:all");
        assertThat(cache.puts.get(0).minutes()).isEqualTo(5);

        assertThat(kpis.classified()).isEqualTo(20);
        assertThat(kpis.failed()).isEqualTo(2);
        assertThat(kpis.stopped()).isEqualTo(3);
        assertThat(kpis.scheduled()).isZero();
        assertThat(kpis.assetCount().value()).isEqualTo(31);
        assertThat(kpis.assetCount().trend()).hasSize(2);
        assertThat(kpis.criticalHigh()).isEqualTo(4);
    }
}
