package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.CostOptimizationHubRecommendationDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.costoptimizationhub.CostOptimizationHubClient;
import software.amazon.awssdk.services.costoptimizationhub.model.GetPreferencesRequest;
import software.amazon.awssdk.services.costoptimizationhub.model.GetPreferencesResponse;
import software.amazon.awssdk.services.costoptimizationhub.model.ListRecommendationsRequest;
import software.amazon.awssdk.services.costoptimizationhub.model.ListRecommendationsResponse;
import software.amazon.awssdk.services.costoptimizationhub.model.Recommendation;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link CostOptimizationHubService}: list mapping + savings-desc ordering,
 * graceful degradation to an empty list on an SDK exception, and the GetPreferences-based
 * enablement probe. The SDK client is mocked; nothing reaches AWS.
 */
class CostOptimizationHubServiceTest {

    private static final String ACCOUNT_ID = "012345678901";

    private CloudAccountRepository cloudAccountRepository;
    private AwsClientProvider awsClientProvider;
    private RedisCacheService redisCache;
    private CachedDataRepository cachedDataRepository;
    private SnapshotFreshnessService snapshotFreshnessService;
    private CostOptimizationHubClient hubClient;

    private CostOptimizationHubService service;

    @BeforeEach
    void setUp() {
        cloudAccountRepository = mock(CloudAccountRepository.class);
        awsClientProvider = mock(AwsClientProvider.class);
        redisCache = mock(RedisCacheService.class);
        cachedDataRepository = mock(CachedDataRepository.class);
        snapshotFreshnessService = mock(SnapshotFreshnessService.class);
        hubClient = mock(CostOptimizationHubClient.class);

        CloudAccount account = new CloudAccount();
        account.setAwsAccountId(ACCOUNT_ID);
        when(cloudAccountRepository.findByAwsAccountId(ACCOUNT_ID)).thenReturn(List.of(account));
        when(awsClientProvider.getCostOptimizationHubClient(any())).thenReturn(hubClient);
        // Caches cold: force the API path. Redis returns empty, DB snapshot absent.
        when(redisCache.get(any(String.class), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(Optional.empty());
        when(cachedDataRepository.findById(any())).thenReturn(Optional.empty());
        when(snapshotFreshnessService.ifFresh(any(), any())).thenReturn(Optional.empty());

        service = new CostOptimizationHubService(cloudAccountRepository, awsClientProvider, redisCache,
                cachedDataRepository, new ObjectMapper(), snapshotFreshnessService);
    }

    @Test
    void getRecommendations_mapsAndSortsBySavingsDescending() {
        Recommendation small = Recommendation.builder()
                .recommendationId("rec-small")
                .actionType("Rightsize")
                .currentResourceType("Ec2Instance")
                .region("us-east-1")
                .estimatedMonthlySavings(12.5)
                .implementationEffort("Low")
                .build();
        Recommendation large = Recommendation.builder()
                .recommendationId("rec-large")
                .actionType("Stop")
                .currentResourceType("Ec2Instance")
                .region("us-west-2")
                .estimatedMonthlySavings(340.0)
                .implementationEffort("VeryLow")
                .build();

        when(hubClient.listRecommendations(any(ListRecommendationsRequest.class)))
                .thenReturn(ListRecommendationsResponse.builder()
                        .items(small, large)
                        .nextToken(null)
                        .build());

        List<CostOptimizationHubRecommendationDto> result = service.getRecommendations(ACCOUNT_ID, true);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getRecommendationId()).isEqualTo("rec-large");
        assertThat(result.get(0).getEstimatedMonthlySavings()).isEqualTo(340.0);
        assertThat(result.get(1).getRecommendationId()).isEqualTo("rec-small");
    }

    @Test
    void getRecommendations_returnsEmptyListWhenApiThrows() {
        when(hubClient.listRecommendations(any(ListRecommendationsRequest.class)))
                .thenThrow(AwsServiceException.builder().message("access denied").build());

        List<CostOptimizationHubRecommendationDto> result = service.getRecommendations(ACCOUNT_ID, true);

        assertThat(result).isEmpty();
    }

    @Test
    void isEnabled_trueWhenGetPreferencesSucceeds() {
        when(hubClient.getPreferences(any(GetPreferencesRequest.class)))
                .thenReturn(GetPreferencesResponse.builder().build());

        assertThat(service.isEnabled(ACCOUNT_ID)).isTrue();
    }

    @Test
    void isEnabled_falseWhenGetPreferencesThrows() {
        when(hubClient.getPreferences(any(GetPreferencesRequest.class)))
                .thenThrow(AwsServiceException.builder().message("not enrolled").build());

        assertThat(service.isEnabled(ACCOUNT_ID)).isFalse();
    }
}
