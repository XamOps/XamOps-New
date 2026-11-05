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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PERF-5: {@link DbOpsCostService#getAgentTimeseries} clamps the requested window to the retention
 * horizon, so a read can never scan older than the scheduled purge keeps.
 */
class DbOpsMetricsHistoryRetentionTest {

    private static final String AWS_ID = "111111111111";
    private static final int RETENTION_DAYS = 30;

    private CloudAccountRepository cloudAccountRepository;
    private DbAgentConfigRepository dbAgentConfigRepository;
    private DbAgentMetricsHistoryRepository historyRepository;
    private DbOpsCostService service;

    @BeforeEach
    void setUp() {
        cloudAccountRepository = mock(CloudAccountRepository.class);
        dbAgentConfigRepository = mock(DbAgentConfigRepository.class);
        historyRepository = mock(DbAgentMetricsHistoryRepository.class);

        CloudAccount account = new CloudAccount();
        account.setId(1L);
        account.setAwsAccountId(AWS_ID);
        when(cloudAccountRepository.findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(AWS_ID, AWS_ID, AWS_ID))
                .thenReturn(Optional.of(account));

        DbAgentConfig agent = mock(DbAgentConfig.class);
        when(agent.getId()).thenReturn(7L);
        when(dbAgentConfigRepository.findByCloudAccountId(1L)).thenReturn(List.of(agent));

        service = new DbOpsCostService(cloudAccountRepository, mock(AwsClientProvider.class),
                mock(RedisCacheService.class), mock(CachedDataRepository.class), new ObjectMapper(),
                mock(AgentDataCacheRepository.class), mock(KubernetesClusterConfigRepository.class),
                dbAgentConfigRepository, mock(DbAgentDataCacheRepository.class), historyRepository,
                mock(SpotPricingService.class), mock(DbOpsFeatureFlags.class), Runnable::run);
        // @Value is not injected for a directly-constructed instance; set the retention explicitly.
        ReflectionTestUtils.setField(service, "metricsHistoryRetentionDays", RETENTION_DAYS);
    }

    @Test
    void readWindowIsClampedToRetentionHorizon() throws Exception {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        // Ask for 3650 days — far beyond retention.
        service.getAgentTimeseries(AWS_ID, 24 * 3650).get();

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(historyRepository)
                .findByDbAgentIdAndDataTypeAndCollectedAtAfterOrderByCollectedAtAsc(
                        eq(7L), eq("db-instance-metrics"), since.capture());

        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);
        // The cutoff must be ~RETENTION_DAYS in the past (clamped), never 3650 days.
        assertThat(since.getValue()).isAfter(before.minusDays(RETENTION_DAYS).minusMinutes(1));
        assertThat(since.getValue()).isBeforeOrEqualTo(after.minusDays(RETENTION_DAYS).plusMinutes(1));
    }
}
