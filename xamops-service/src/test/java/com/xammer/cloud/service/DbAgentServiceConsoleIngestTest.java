package com.xammer.cloud.service;

import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.domain.DbAgentDataCache;
import com.xammer.cloud.dto.agent.AgentIngestPayload;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import com.xammer.cloud.repository.ExplainRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Item 3 — the four new console batches ingest correctly: the two composite documents
 * (db-access-grants, db-fk-graph) are stored per DSN as a single JSON OBJECT (saveDocPerDsn), the
 * two row batches (db-lock-waits, db-replication) as JSON ARRAYS, and every governance cache key
 * stays within the {@code varchar(50)} data_type bound (no owner-DDL widen).
 */
class DbAgentServiceConsoleIngestTest {

    private DbAgentConfigRepository dbAgentConfigRepository;
    private DbAgentDataCacheRepository dbAgentDataCacheRepository;
    private DbAgentService service;

    @BeforeEach
    void setUp() {
        dbAgentConfigRepository = mock(DbAgentConfigRepository.class);
        dbAgentDataCacheRepository = mock(DbAgentDataCacheRepository.class);
        when(dbAgentConfigRepository.save(any(DbAgentConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dbAgentDataCacheRepository.findByDbAgentIdAndDataType(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(dbAgentDataCacheRepository.save(any(DbAgentDataCache.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new DbAgentService(dbAgentConfigRepository, dbAgentDataCacheRepository,
                mock(DbAgentMetricsHistoryRepository.class), mock(CloudAccountRepository.class),
                mock(ExplainRequestRepository.class), new DbOpsFeatureFlags(false));
    }

    private AgentIngestPayload.DataBatch batch(String collector, List<Object> items) {
        AgentIngestPayload.DataBatch b = new AgentIngestPayload.DataBatch();
        b.setCollector(collector);
        b.setItems(items);
        b.setCount(items.size());
        return b;
    }

    @Test
    void storesCompositeDocsAsObjectsRowBatchesAsArraysWithinKeyBound() {
        DbAgentConfig cfg = new DbAgentConfig();
        cfg.setId(1L);
        cfg.setCloudAccountId(7L);
        cfg.setAgentName("prod-db");

        AgentIngestPayload payload = new AgentIngestPayload();
        payload.setAgentVersion("1.0.0");
        payload.setBatches(List.of(
                batch("db-lock-waits", List.of(Map.of(
                        "dsnAlias", "db-0", "pid", 123, "category", "blocked", "usename", "app"))),
                batch("db-replication", List.of(Map.of(
                        "dsnAlias", "db-0", "role", "primary"))),
                batch("db-access-grants", List.of(Map.of(
                        "dsnAlias", "db-0", "dbEngine", "postgres", "dbHost", "pg.internal",
                        "collectedAt", "2026-10-29T12:00:00Z",
                        "roles", List.of(Map.of("name", "app_read", "canLogin", false)),
                        "memberships", List.of(Map.of("member", "alice", "grantedRole", "app_read")),
                        "grants", List.of(Map.of("grantee", "app_read", "scope", "public",
                                "privilegeClass", "READ", "objectCount", 5))))),
                batch("db-fk-graph", List.of(Map.of(
                        "dsnAlias", "db-0", "collectedAt", "2026-10-29T12:00:00Z",
                        "tables", List.of(Map.of("schema", "public", "name", "a", "oid", 1,
                                "sizeBytes", 1000, "rowEstimate", 100)),
                        "fkEdges", List.of(),
                        "truncated", false, "totalFkTables", 1, "totalFkEdges", 0)))));

        service.processIngest(cfg, payload, "1.2.3.4");

        ArgumentCaptor<DbAgentDataCache> captor = ArgumentCaptor.forClass(DbAgentDataCache.class);
        verify(dbAgentDataCacheRepository, atLeastOnce()).save(captor.capture());
        List<DbAgentDataCache> saved = captor.getAllValues();

        // Composite docs stored as a single JSON OBJECT under the per-DSN key.
        DbAgentDataCache accessRow = rowByType(saved, "db-access-grants:7:db-0");
        assertThat(accessRow.getDataJson().trim()).startsWith("{");
        assertThat(accessRow.getDataJson()).contains("\"roles\"").contains("\"grants\"").contains("\"memberships\"");

        DbAgentDataCache fkRow = rowByType(saved, "db-fk-graph:7:db-0");
        assertThat(fkRow.getDataJson().trim()).startsWith("{");
        assertThat(fkRow.getDataJson()).contains("\"tables\"").contains("\"fkEdges\"");

        // Row batches stored as JSON ARRAYS.
        assertThat(rowByType(saved, "db-lock-waits:7:db-0").getDataJson().trim()).startsWith("[");
        assertThat(rowByType(saved, "db-replication:7:db-0").getDataJson().trim()).startsWith("[");

        // Every governance key stays within the varchar(50) data_type bound.
        assertThat(saved).allSatisfy(r ->
                assertThat(r.getDataType().length()).isLessThanOrEqualTo(DbAgentService.MAX_DATA_TYPE_LEN));
    }

    @Test
    void boundedCacheKeyTruncatesLongAliasToStayWithinFifty() {
        String key = DbAgentService.boundedCacheKey("db-access-grants", 123456L, "x".repeat(100));
        assertThat(key.length()).isLessThanOrEqualTo(DbAgentService.MAX_DATA_TYPE_LEN);
        assertThat(key).startsWith("db-access-grants:123456:");
    }

    private static DbAgentDataCache rowByType(List<DbAgentDataCache> saved, String dataType) {
        return saved.stream().filter(r -> dataType.equals(r.getDataType())).reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no cache row saved for " + dataType));
    }
}
