package com.xammer.cloud.service.azure;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.resourcemanager.AzureResourceManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.dto.dbops.DbCostDto;
import com.xammer.cloud.dto.dbops.DbInstanceDto;
import com.xammer.cloud.dto.dbops.DbMetricsDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the Azure DbOps page shows has to match what Azure reports: the TLS floor, the billed
 * database spend, and no zero-filled metrics for an instance Azure Monitor did not answer for.
 */
class AzureDbOpsAccuracyTest {

    private static final String SUB = "sub-1";

    private AzureClientProvider azureClientProvider;
    private RedisCacheService redisCache;
    private CachedDataRepository cachedDataRepository;
    private AzureArmClient armClient;
    private AzureDbOpsService service;

    @BeforeEach
    void setUp() {
        azureClientProvider = mock(AzureClientProvider.class);
        redisCache = mock(RedisCacheService.class);
        cachedDataRepository = mock(CachedDataRepository.class);
        armClient = mock(AzureArmClient.class);
        service = new AzureDbOpsService(
                azureClientProvider,
                mock(CloudAccountRepository.class),
                redisCache,
                cachedDataRepository,
                new ObjectMapper(),
                mock(DbAgentConfigRepository.class),
                mock(DbAgentDataCacheRepository.class),
                mock(DbAgentMetricsHistoryRepository.class),
                armClient,
                new DbOpsFeatureFlags(false),
                Runnable::run);
    }

    @Test
    @DisplayName("the TLS check accepts 1.2+ in every Azure spelling and rejects older or unknown floors")
    void tlsFloor() {
        assertThat(AzureDbOpsService.tlsAtLeast12("1.2")).isTrue();
        assertThat(AzureDbOpsService.tlsAtLeast12("TLSv1.2")).isTrue();
        assertThat(AzureDbOpsService.tlsAtLeast12("TLSv1.2,TLSv1.3")).isTrue();
        assertThat(AzureDbOpsService.tlsAtLeast12("1.3")).isTrue();
        assertThat(AzureDbOpsService.tlsAtLeast12("1.0")).isFalse();
        assertThat(AzureDbOpsService.tlsAtLeast12("TLSv1.1,TLSv1.2")).isFalse();
        assertThat(AzureDbOpsService.tlsAtLeast12("None")).isFalse();
        assertThat(AzureDbOpsService.tlsAtLeast12(null)).isFalse();
    }

    @Test
    @DisplayName("only the relational database services count as DB spend")
    void databaseServices() {
        assertThat(AzureDbOpsService.isDatabaseService("SQL Database")).isTrue();
        assertThat(AzureDbOpsService.isDatabaseService("SQL Managed Instance")).isTrue();
        assertThat(AzureDbOpsService.isDatabaseService("Azure Database for PostgreSQL")).isTrue();
        assertThat(AzureDbOpsService.isDatabaseService("Azure Database for MySQL")).isTrue();
        assertThat(AzureDbOpsService.isDatabaseService("Virtual Machines")).isFalse();
        assertThat(AzureDbOpsService.isDatabaseService(null)).isFalse();
    }

    @Test
    @DisplayName("billed database spend comes from Cost Management, stored in USD at Azure's rate")
    void billedSpendFromCostManagement() {
        AzureBillingDataIngestionService ingestion = mock(AzureBillingDataIngestionService.class);
        when(ingestion.readBillingSummary(SUB)).thenReturn(Optional.of(List.of(
                new AzureDashboardData.BillingSummary("Azure Database for PostgreSQL", 9500.0),
                new AzureDashboardData.BillingSummary("Virtual Machines", 1000.0))));
        AzureBillingCurrencyService currency = mock(AzureBillingCurrencyService.class);
        when(currency.forSubscription(SUB)).thenReturn(new AzureBillingCurrencyService.BillingCurrency("INR", 95.0));
        ReflectionTestUtils.setField(service, "billingIngestionService", ingestion);
        ReflectionTestUtils.setField(service, "billingCurrencyService", currency);

        List<DbCostDto> costs = service.billedDbCosts(SUB);

        assertThat(costs).hasSize(1);
        assertThat(costs.get(0).getService()).isEqualTo("Azure Database for PostgreSQL");
        assertThat(costs.get(0).getCost()).isCloseTo(100.0, within(1e-9));
        assertThat(costs.get(0).getPercentOfTotal()).isCloseTo(100.0, within(1e-9));
    }

    @Test
    @DisplayName("an instance Azure Monitor did not answer for gets no zero-filled entry, and nothing is cached")
    void noZeroFilledMetrics() throws Exception {
        DbInstanceDto pg = new DbInstanceDto();
        pg.setInstanceId("pg/mypg");
        pg.setEngine("postgres");
        doReturn(Optional.of(List.of(pg))).when(redisCache).get(eq("dbops:azure:inventory:" + SUB), any(TypeReference.class));
        TokenCredential credential = mock(TokenCredential.class);
        when(credential.getToken(any())).thenReturn(Mono.just(new AccessToken("t", OffsetDateTime.now().plusHours(1))));
        when(azureClientProvider.getCredential(SUB)).thenReturn(credential);
        when(azureClientProvider.getAzureClient(SUB)).thenReturn(mock(AzureResourceManager.class));
        when(armClient.getQuietly(anyString(), any())).thenReturn(Optional.empty());

        Map<String, DbMetricsDto> metrics = service.getDbMetrics(SUB, true).get();

        assertThat(metrics).doesNotContainKey("pg/mypg");
        verify(redisCache, never()).put(eq("dbops:azure:metrics:" + SUB), any(), anyInt());
    }

    @Test
    @DisplayName("a DB snapshot older than the cache TTL is not served as current")
    void staleDbSnapshotIgnored() throws Exception {
        CachedData old = new CachedData();
        old.setCacheKey("dbops:azure:costs:" + SUB);
        old.setJsonData("[{\"service\":\"SQL Database\",\"region\":\"\",\"cost\":1.0,\"percentOfTotal\":100.0}]");
        old.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusDays(3));
        when(cachedDataRepository.findById("dbops:azure:costs:" + SUB)).thenReturn(Optional.of(old));
        doReturn(Optional.empty()).when(redisCache).get(eq("dbops:azure:costs:" + SUB), any(TypeReference.class));
        // The live path then runs off a cached empty inventory and no billing data.
        doReturn(Optional.of(List.of())).when(redisCache).get(eq("dbops:azure:inventory:" + SUB), any(TypeReference.class));

        List<DbCostDto> costs = service.getDbCosts(SUB, false).get();

        assertThat(costs).isEmpty();
    }
}
