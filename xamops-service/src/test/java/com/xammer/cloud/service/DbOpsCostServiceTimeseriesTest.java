package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * COR-17: {@link DbOpsCostService#getAgentTimeseries} computes its lookback cutoff in UTC
 * (agent rows are stamped UTC), independent of the JVM's default zone.
 */
class DbOpsCostServiceTimeseriesTest {

    private static final String AWS_ID = "111111111111";
    private static TimeZone originalZone;

    private CloudAccountRepository cloudAccountRepository;
    private DbAgentConfigRepository dbAgentConfigRepository;
    private DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository;
    private DbOpsCostService service;

    @BeforeAll
    static void forceNonUtcZone() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    @AfterAll
    static void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    @BeforeEach
    void setUp() {
        cloudAccountRepository = mock(CloudAccountRepository.class);
        dbAgentConfigRepository = mock(DbAgentConfigRepository.class);
        dbAgentMetricsHistoryRepository = mock(DbAgentMetricsHistoryRepository.class);
        service = new DbOpsCostService(cloudAccountRepository, mock(AwsClientProvider.class),
                mock(RedisCacheService.class), mock(CachedDataRepository.class), new ObjectMapper(),
                mock(AgentDataCacheRepository.class), mock(KubernetesClusterConfigRepository.class),
                dbAgentConfigRepository, mock(DbAgentDataCacheRepository.class),
                dbAgentMetricsHistoryRepository, mock(SpotPricingService.class), mock(DbOpsFeatureFlags.class));
    }

    @Test
    void timeseriesWindowUsesUtc() throws Exception {
        CloudAccount account = new CloudAccount();
        account.setId(1L);
        account.setAwsAccountId(AWS_ID);
        when(cloudAccountRepository.findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(AWS_ID, AWS_ID, AWS_ID))
                .thenReturn(Optional.of(account));

        DbAgentConfig agent = new DbAgentConfig();
        agent.setId(5L);
        agent.setAgentName("agent-1");
        when(dbAgentConfigRepository.findByCloudAccountId(1L)).thenReturn(List.of(agent));

        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(dbAgentMetricsHistoryRepository.findByDbAgentIdAndDataTypeAndCollectedAtAfterOrderByCollectedAtAsc(
                eq(5L), eq("db-instance-metrics"), sinceCaptor.capture()))
                .thenReturn(Collections.emptyList());

        service.getAgentTimeseries(AWS_ID, 1).get();

        LocalDateTime captured = sinceCaptor.getValue();
        LocalDateTime expectedUtc = LocalDateTime.now(ZoneOffset.UTC).minusHours(1);
        long driftSeconds = Math.abs(Duration.between(captured, expectedUtc).getSeconds());
        assertThat(driftSeconds)
                .as("cutoff should be UTC now-1h, not local now-1h")
                .isLessThan(60);
    }
}
