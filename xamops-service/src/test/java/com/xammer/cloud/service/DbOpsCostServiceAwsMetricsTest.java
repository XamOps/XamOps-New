package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.dbops.DbInstanceDto;
import com.xammer.cloud.dto.dbops.DbMetricsDto;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * COR-15: {@link DbOpsCostService#getDbMetrics} must chunk GetMetricData under the 500-query
 * cap (14 queries/instance → &gt;35 instances overflows), and isolate per-region failures so one
 * region's CloudWatch error does not blank out the metrics of the other regions.
 */
class DbOpsCostServiceAwsMetricsTest {

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

    private DbInstanceDto instance(String id, String region) {
        DbInstanceDto dto = new DbInstanceDto();
        dto.setInstanceId(id);
        dto.setRegion(region);
        dto.setEngine("mysql");
        return dto;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubInventory(List<DbInstanceDto> instances) {
        when(redisCache.get(eq(INVENTORY_KEY), any(TypeReference.class)))
                .thenReturn((Optional) Optional.of(instances));
    }

    @Test
    void chunksGetMetricDataForManyInstances() throws Exception {
        List<DbInstanceDto> instances = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            instances.add(instance("inst-" + i, "us-east-1"));
        }
        stubInventory(instances);

        CloudWatchClient cw = mock(CloudWatchClient.class);
        when(awsClientProvider.getCloudWatchClient(eq(account), eq("us-east-1"))).thenReturn(cw);
        when(cw.getMetricData(any(GetMetricDataRequest.class)))
                .thenReturn(GetMetricDataResponse.builder().build());

        Map<String, DbMetricsDto> result = service.getDbMetrics(AWS_ID, true).get();

        // 40 instances × 14 queries = 560 > 450 ⇒ at least two chunked calls
        verify(cw, atLeast(2)).getMetricData(any(GetMetricDataRequest.class));
        assertThat(result).hasSize(40);
        assertThat(result).containsKey("inst-0").containsKey("inst-39");
    }

    @Test
    void regionFailureDoesNotBlankOtherRegions() throws Exception {
        stubInventory(List.of(instance("a1", "us-east-1"), instance("b1", "eu-west-1")));

        CloudWatchClient cwA = mock(CloudWatchClient.class);
        when(awsClientProvider.getCloudWatchClient(eq(account), eq("us-east-1"))).thenReturn(cwA);
        when(cwA.getMetricData(any(GetMetricDataRequest.class)))
                .thenReturn(GetMetricDataResponse.builder().build());

        CloudWatchClient cwB = mock(CloudWatchClient.class);
        when(awsClientProvider.getCloudWatchClient(eq(account), eq("eu-west-1"))).thenReturn(cwB);
        when(cwB.getMetricData(any(GetMetricDataRequest.class)))
                .thenThrow(new RuntimeException("CloudWatch throttled"));

        Map<String, DbMetricsDto> result = service.getDbMetrics(AWS_ID, true).get();

        assertThat(result).containsKey("a1");
        assertThat(result).doesNotContainKey("b1");
    }
}
