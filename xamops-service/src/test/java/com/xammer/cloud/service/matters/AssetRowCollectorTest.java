package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.scans.AssetRowSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Build 6 design 4.2: the collector's stop rules, its page cap, its query and its one cache entry per tab. */
class AssetRowCollectorTest {

    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");

    private MattersApiClient apiClient;
    private InMemoryRedisCache cache;
    private AssetRowCollector collector;

    @BeforeEach
    void setUp() {
        apiClient = mock(MattersApiClient.class);
        cache = new InMemoryRedisCache();
        collector = new AssetRowCollector(apiClient, new DatastoreRowMapper(), cache);
    }

    /** Every page holds {@code perPage} rows up to {@code rowsAvailable}, and reports {@code total}. */
    @SuppressWarnings("unchecked")
    private void vendor(long total, int rowsAvailable) {
        when(apiClient.listAssets(eq(CREDENTIAL), anyMap())).thenAnswer(inv -> {
            Map<String, List<String>> query = inv.getArgument(1);
            int page = Integer.parseInt(query.get("page").get(0));
            int from = (page - 1) * 100;
            int to = Math.min(rowsAvailable, from + 100);
            List<AssetBodies.Asset> assets = new ArrayList<>();
            for (int i = from; i < to; i++) {
                assets.add(AssetBodies.asset(i, "cloud", "S3", "S3", "abcdef0123456789abcdef01", i));
            }
            return AssetBodies.page(total, assets);
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, List<String>>> queries(int calls) {
        ArgumentCaptor<Map<String, List<String>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(apiClient, times(calls)).listAssets(eq(CREDENTIAL), captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("stops once pagination.total rows are read; not truncated")
    void stopsOnTotal() {
        vendor(150, 150);

        AssetRowSnapshot snapshot = collector.rows(CREDENTIAL, 10L, DatastoreTab.CLOUD);

        assertThat(snapshot.rows()).hasSize(150);
        assertThat(snapshot.vendorTotal()).isEqualTo(150);
        assertThat(snapshot.truncated()).isFalse();
        List<Map<String, List<String>>> queries = queries(2);
        assertThat(queries.get(0)).containsExactly(
                Map.entry("category", List.of("cloud")), Map.entry("sort", List.of("-severity")),
                Map.entry("page", List.of("1")), Map.entry("page_size", List.of("100")));
        assertThat(queries.get(1).get("page")).containsExactly("2");
    }

    @Test
    @DisplayName("stops on an empty page, even when the total promises more; not truncated")
    void stopsOnEmptyPage() {
        vendor(500, 100);

        AssetRowSnapshot snapshot = collector.rows(CREDENTIAL, 10L, DatastoreTab.SAAS);

        assertThat(snapshot.rows()).hasSize(100);
        assertThat(snapshot.truncated()).isFalse();
        assertThat(queries(2).get(1)).containsEntry("category", List.of("saas")).containsEntry("page", List.of("2"));
    }

    @Test
    @DisplayName("an environment stops at 10 pages and is truncated when rows remain")
    void stopsAtTheCap() {
        vendor(5_000, 5_000);

        AssetRowSnapshot snapshot = collector.rows(CREDENTIAL, 10L, DatastoreTab.ON_PREM);

        assertThat(snapshot.rows()).hasSize(1_000);
        assertThat(snapshot.vendorTotal()).isEqualTo(5_000);
        assertThat(snapshot.truncated()).isTrue();
        assertThat(queries(10).get(9)).containsEntry("category", List.of("on_prem")).containsEntry("page", List.of("10"));
    }

    @Test
    @DisplayName("exactly 10 full pages that reach the total are not truncated")
    void capReachedWithEveryRow() {
        vendor(1_000, 1_000);

        AssetRowSnapshot snapshot = collector.rows(CREDENTIAL, 10L, DatastoreTab.ENDPOINT);

        assertThat(snapshot.rows()).hasSize(1_000);
        assertThat(snapshot.truncated()).isFalse();
        queries(10);
    }

    @Test
    @DisplayName("All reads one page, without a category")
    void allReadsOnePage() {
        vendor(300, 300);

        AssetRowSnapshot snapshot = collector.rows(CREDENTIAL, 10L, DatastoreTab.ALL);

        assertThat(snapshot.rows()).hasSize(100);
        assertThat(snapshot.truncated()).isTrue();
        assertThat(queries(1).get(0)).doesNotContainKey("category").containsEntry("sort", List.of("-severity"));
    }

    @Test
    @DisplayName("one cache entry per tab, 2 minutes; a hit makes no vendor call; the integration id round-trips")
    void oneEntryPerTab() {
        vendor(50, 50);

        collector.rows(CREDENTIAL, 10L, DatastoreTab.CLOUD);
        AssetRowSnapshot hit = collector.rows(CREDENTIAL, 10L, DatastoreTab.CLOUD);
        collector.rows(CREDENTIAL, 10L, DatastoreTab.SAAS);

        verify(apiClient, times(2)).listAssets(any(), anyMap());
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes)
                .containsExactly(tuple("dspm:dsrows:10:cloud", 2), tuple("dspm:dsrows:10:saas", 2));
        assertThat(hit.rows()).hasSize(50);
        assertThat(hit.rows().get(0).integrationId()).isEqualTo("abcdef0123456789abcdef01");
        assertThat(hit.rows().get(0).row().source().provider()).isEqualTo("S3");
    }

    @Test
    @DisplayName("a page without pagination is read as total 0 and stops after one page")
    void missingPaginationStops() {
        when(apiClient.listAssets(eq(CREDENTIAL), anyMap())).thenAnswer(inv -> {
            JsonNode body = AssetBodies.page(0, List.of(AssetBodies.asset(1, "cloud", "S3", "S3", null, 1)));
            ((com.fasterxml.jackson.databind.node.ObjectNode) body).remove("pagination");
            return body;
        });

        AssetRowSnapshot snapshot = collector.rows(CREDENTIAL, 10L, DatastoreTab.CLOUD);

        assertThat(snapshot.rows()).hasSize(1);
        assertThat(snapshot.truncated()).isFalse();
        queries(1);
    }
}
