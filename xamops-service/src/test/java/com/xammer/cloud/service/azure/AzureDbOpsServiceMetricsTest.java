package com.xammer.cloud.service.azure;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.resourcemanager.AzureResourceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.dto.dbops.AgentTimeseriesDto;
import com.xammer.cloud.dto.dbops.DbInstanceDto;
import com.xammer.cloud.dto.dbops.DbMetricsDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FEAT-003 — Azure DbOps metric correctness (COR-4/9/13/17).
 *
 * All Azure HTTP is funnelled through the injected {@link AzureArmClient}; its
 * {@code getQuietly(url, token)} is stubbed per-URL to supply JSON bodies. The
 * package-private {@code resolveResourceId} and {@code applyAzureMetric} are exercised
 * directly.
 */
class AzureDbOpsServiceMetricsTest {

    private static final String SUB = "sub-123";
    private static final String TOKEN = "test-token";

    private AzureClientProvider azureClientProvider;
    private CloudAccountRepository cloudAccountRepository;
    private RedisCacheService redisCache;
    private CachedDataRepository cachedDataRepository;
    private DbAgentConfigRepository dbAgentConfigRepository;
    private DbAgentDataCacheRepository dbAgentDataCacheRepository;
    private DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository;
    private AzureArmClient armClient;

    private AzureDbOpsService service;

    @BeforeEach
    void setUp() {
        azureClientProvider = mock(AzureClientProvider.class);
        cloudAccountRepository = mock(CloudAccountRepository.class);
        redisCache = mock(RedisCacheService.class);
        cachedDataRepository = mock(CachedDataRepository.class);
        dbAgentConfigRepository = mock(DbAgentConfigRepository.class);
        dbAgentDataCacheRepository = mock(DbAgentDataCacheRepository.class);
        dbAgentMetricsHistoryRepository = mock(DbAgentMetricsHistoryRepository.class);
        armClient = mock(AzureArmClient.class);

        service = new AzureDbOpsService(
                azureClientProvider,
                cloudAccountRepository,
                redisCache,
                cachedDataRepository,
                new ObjectMapper(),
                dbAgentConfigRepository,
                dbAgentDataCacheRepository,
                dbAgentMetricsHistoryRepository,
                armClient,
                new DbOpsFeatureFlags(false));
    }

    private DbInstanceDto dto(String instanceId) {
        DbInstanceDto d = new DbInstanceDto();
        d.setInstanceId(instanceId);
        return d;
    }

    /** Makes getBearerToken() succeed with a usable ARM token. */
    private void stubBearerToken() {
        TokenCredential credential = mock(TokenCredential.class);
        when(credential.getToken(any())).thenReturn(
                Mono.just(new AccessToken(TOKEN, OffsetDateTime.now().plusHours(1))));
        when(azureClientProvider.getCredential(SUB)).thenReturn(credential);
    }

    // ── COR-4: Flexible-server ARM id resolution ────────────────────────────

    @Test
    void resolvesFlexibleServerIds() {
        String pgJson = "{\"value\":[{\"name\":\"mypg\",\"id\":\"/subscriptions/" + SUB
                + "/resourceGroups/rg/providers/Microsoft.DBforPostgreSQL/flexibleServers/mypg\"}]}";
        String mysqlJson = "{\"value\":[{\"name\":\"mymysql\",\"id\":\"/subscriptions/" + SUB
                + "/resourceGroups/rg/providers/Microsoft.DBforMySQL/flexibleServers/mymysql\"}]}";

        when(armClient.getQuietly(anyString(), any())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            if (url.contains("Microsoft.DBforPostgreSQL")) return Optional.of(pgJson);
            if (url.contains("Microsoft.DBforMySQL")) return Optional.of(mysqlJson);
            return Optional.empty();
        });

        String pgId = service.resolveResourceId(dto("pg/mypg"), SUB, null, TOKEN);
        assertNotNull(pgId, "PostgreSQL Flexible server must resolve an ARM id (not null)");
        assertTrue(pgId.endsWith("/flexibleServers/mypg"), "got: " + pgId);

        String mysqlId = service.resolveResourceId(dto("mysql/mymysql"), SUB, null, TOKEN);
        assertNotNull(mysqlId, "MySQL Flexible server must resolve an ARM id (not null)");
        assertTrue(mysqlId.endsWith("/flexibleServers/mymysql"), "got: " + mysqlId);
    }

    // ── COR-9: used storage becomes free; percentages are not IOPS ──────────

    @Test
    void storageUsedBecomesFree() {
        DbMetricsDto dto = new DbMetricsDto();
        double usedBytes = 40.0 * 1024.0 * 1024.0 * 1024.0; // 40 GB used
        service.applyAzureMetric(dto, "storage_used", usedBytes, 100); // 100 GB allocated
        assertEquals(60.0, dto.getFreeStorageSpaceGb(), 0.001,
                "free must be allocated(100) - used(40) = 60, not the used figure");
    }

    @Test
    void percentNotStoredAsIops() {
        DbMetricsDto dto = new DbMetricsDto();
        service.applyAzureMetric(dto, "physical_data_read_percent", 55.0, 100);
        assertEquals(0.0, dto.getAvgReadIops(), 0.001,
                "a percentage must never be written into an IOPS field");

        service.applyAzureMetric(dto, "iops", 120.0, 100);
        assertEquals(120.0, dto.getAvgReadIops(), 0.001,
                "the real Flexible-server IOPS metric populates IOPS");
    }

    // ── COR-13: failure-vs-empty contract ───────────────────────────────────

    @Test
    void failedScanNotCached() throws Exception {
        stubBearerToken();
        AzureResourceManager azure = mock(AzureResourceManager.class);
        when(azureClientProvider.getAzureClient(SUB)).thenReturn(azure);
        when(azure.sqlServers()).thenThrow(new RuntimeException("SQL list failed"));
        // Every REST list endpoint fails (null body).
        when(armClient.getQuietly(anyString(), any())).thenReturn(Optional.empty());

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> service.getDbInventory(SUB, true).get());
        assertTrue(ex.getCause() instanceof IllegalStateException,
                "a wholly-failed scan must complete exceptionally");

        verify(redisCache, never()).put(anyString(), any(), anyInt());
        verify(cachedDataRepository, never()).save(any());
    }

    @Test
    void succeededEmptyScanIsCached() throws Exception {
        stubBearerToken();
        AzureResourceManager azure = mock(AzureResourceManager.class);
        when(azureClientProvider.getAzureClient(SUB)).thenReturn(azure);
        // SQL SDK path errors, but the REST list endpoints answer 200 with an empty value array —
        // at least one source succeeded, so an empty result is a valid (cacheable) answer.
        when(azure.sqlServers()).thenThrow(new RuntimeException("SQL list failed"));
        when(armClient.getQuietly(anyString(), any())).thenReturn(Optional.of("{\"value\":[]}"));

        List<DbInstanceDto> result = service.getDbInventory(SUB, true).get();
        assertTrue(result.isEmpty(), "a succeeded scan with no servers yields an empty list");
        verify(redisCache).put(eq("dbops:azure:inventory:" + SUB), any(), anyInt());
    }

    // ── COR-17: agent timeseries window uses UTC ────────────────────────────

    @Test
    void timeseriesWindowUsesUtc() throws Exception {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        try {
            CloudAccount account = new CloudAccount();
            account.setId(7L);
            when(cloudAccountRepository.findByAzureSubscriptionId(SUB)).thenReturn(Optional.of(account));

            DbAgentConfig agent = mock(DbAgentConfig.class);
            when(agent.getId()).thenReturn(10L);
            when(agent.getDbHost()).thenReturn("db-host");
            when(dbAgentConfigRepository.findByCloudAccountId(7L)).thenReturn(List.of(agent));

            ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            when(dbAgentMetricsHistoryRepository
                    .findByDbAgentIdAndDataTypeAndCollectedAtAfterOrderByCollectedAtAsc(
                            eq(10L), eq("db-instance-metrics"), sinceCaptor.capture()))
                    .thenReturn(Collections.emptyList());

            List<AgentTimeseriesDto> result = service.getAgentTimeseries(SUB, 1).get();
            assertTrue(result.isEmpty());

            LocalDateTime captured = sinceCaptor.getValue();
            LocalDateTime expectedUtc = LocalDateTime.now(ZoneOffset.UTC).minusHours(1);
            long driftSeconds = Math.abs(ChronoUnit.SECONDS.between(captured, expectedUtc));
            assertTrue(driftSeconds < 30,
                    "window must be UTC now-1h; drift from UTC was " + driftSeconds + "s (would be ~19800s if local)");
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
