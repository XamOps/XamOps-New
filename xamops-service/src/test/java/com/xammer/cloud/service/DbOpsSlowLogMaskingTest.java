package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.dbops.DbInstanceDto;
import com.xammer.cloud.dto.dbops.SlowQueriesResponseDto;
import com.xammer.cloud.dto.dbops.SlowQueryDto;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SEC-8: query text parsed from CloudWatch slow logs must have its literal values masked
 * (string + numeric → {@code ?}) before it is stored or displayed, for both the PostgreSQL
 * and the MySQL slow-log formats.
 */
class DbOpsSlowLogMaskingTest {

    private static final String AWS_ID = "111111111111";
    private static final String INVENTORY_KEY = "dbops:aws:inventory:" + AWS_ID;

    private CloudAccountRepository cloudAccountRepository;
    private AwsClientProvider awsClientProvider;
    private RedisCacheService redisCache;
    private DbOpsCostService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        cloudAccountRepository = mock(CloudAccountRepository.class);
        awsClientProvider = mock(AwsClientProvider.class);
        redisCache = mock(RedisCacheService.class);
        account = new CloudAccount();
        account.setId(1L);
        account.setAwsAccountId(AWS_ID);
        account.setProvider("AWS");
        when(cloudAccountRepository.findByAwsAccountId(AWS_ID)).thenReturn(List.of(account));
        service = new DbOpsCostService(cloudAccountRepository, awsClientProvider, redisCache,
                mock(CachedDataRepository.class), new ObjectMapper(), mock(AgentDataCacheRepository.class),
                mock(KubernetesClusterConfigRepository.class), mock(DbAgentConfigRepository.class),
                mock(DbAgentDataCacheRepository.class), mock(DbAgentMetricsHistoryRepository.class),
                mock(SpotPricingService.class), mock(DbOpsFeatureFlags.class));
    }

    private DbInstanceDto instance(String id, String engine) {
        DbInstanceDto dto = new DbInstanceDto();
        dto.setInstanceId(id);
        dto.setRegion("us-east-1");
        dto.setEngine(engine);
        return dto;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubInventory(DbInstanceDto instance) {
        when(redisCache.get(eq(INVENTORY_KEY), any(TypeReference.class)))
                .thenReturn((Optional) Optional.of(List.of(instance)));
    }

    private void stubLogEvent(String message) {
        CloudWatchLogsClient logs = mock(CloudWatchLogsClient.class);
        when(awsClientProvider.getCloudWatchLogsClient(eq(account), eq("us-east-1"))).thenReturn(logs);
        when(logs.filterLogEvents(any(FilterLogEventsRequest.class)))
                .thenReturn(FilterLogEventsResponse.builder()
                        .events(FilteredLogEvent.builder().message(message).build())
                        .build());
    }

    @Test
    void postgresLiteralsAreMasked() throws Exception {
        stubInventory(instance("pg1", "postgres"));
        stubLogEvent("2026-01-01 00:00:00 UTC duration: 12.3 ms  statement: "
                + "SELECT * FROM users WHERE email = 'a@b.com' AND id = 42");

        SlowQueriesResponseDto result = service.getSlowQueries(AWS_ID, true).get();

        assertThat(result.getQueries()).hasSize(1);
        SlowQueryDto q = result.getQueries().get(0);
        assertThat(q.getQuery()).doesNotContain("a@b.com").doesNotContain("42");
        assertThat(q.getQuery()).isEqualTo("SELECT * FROM users WHERE email = ? AND id = ?");
    }

    @Test
    void mysqlLiteralsAreMasked() throws Exception {
        stubInventory(instance("my1", "mysql"));
        stubLogEvent(String.join("\n",
                "# Time: 2026-01-01T00:00:00.000000Z",
                "# User@Host: app[app] @ localhost []",
                "# Query_time: 2.500000  Lock_time: 0.000100 Rows_sent: 1  Rows_examined: 100",
                "SET timestamp=1767225600;",
                "SELECT * FROM orders WHERE customer = 'bob' AND total = 99;"));

        SlowQueriesResponseDto result = service.getSlowQueries(AWS_ID, true).get();

        assertThat(result.getQueries()).hasSize(1);
        SlowQueryDto q = result.getQueries().get(0);
        assertThat(q.getQuery()).doesNotContain("'bob'").doesNotContain("99").doesNotContain("1767225600");
        assertThat(q.getQuery()).contains("customer = ?").contains("total = ?");
    }
}
