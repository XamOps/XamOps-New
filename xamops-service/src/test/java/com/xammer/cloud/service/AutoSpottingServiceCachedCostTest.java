package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.autospotting.CostResponse;
import com.xammer.cloud.repository.AsgSpotConfigRepository;
import com.xammer.cloud.repository.AutoSpottingSettingsRepository;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * getCachedCostData is the dashboard's and the startup seed's view of AutoSpotting cost:
 * Redis, then the DB snapshot, never AWS. Keys use the 12-digit AWS account number.
 */
class AutoSpottingServiceCachedCostTest {

    private static final String AWS_ID = "012345678901";
    private static final String REDIS_KEY = "autospotting-cost-" + AWS_ID;
    private static final String DB_KEY = "AUTOSPOTTING_COST::" + AWS_ID;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AwsClientProvider awsClientProvider;
    private RedisCacheService redisCacheService;
    private CachedDataRepository cachedDataRepository;
    private AutoSpottingService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        awsClientProvider = mock(AwsClientProvider.class);
        redisCacheService = mock(RedisCacheService.class);
        cachedDataRepository = mock(CachedDataRepository.class);
        service = new AutoSpottingService(awsClientProvider, mock(CloudAccountRepository.class),
                mock(SpotPricingService.class), mock(SpotOptimizationEventRepository.class),
                mock(DailySpotSavingsRepository.class), mock(AutoSpottingSettingsRepository.class),
                mock(AsgSpotConfigRepository.class), redisCacheService, cachedDataRepository, objectMapper,
                new AutoSpottingFeatureFlags(false, false, false));

        account = new CloudAccount();
        account.setId(7L);
        account.setProvider("AWS");
        account.setAwsAccountId(AWS_ID);
    }

    private static CostResponse cost(double potentialHourly) {
        CostResponse.CostSummary summary = new CostResponse.CostSummary();
        summary.setTotalPotentialSavings(potentialHourly);
        CostResponse response = new CostResponse();
        response.setSummary(summary);
        return response;
    }

    @Test
    void redisHit_isReturned() {
        CostResponse cached = cost(1.25);
        when(redisCacheService.get(REDIS_KEY, CostResponse.class)).thenReturn(Optional.of(cached));

        assertThat(service.getCachedCostData(account)).containsSame(cached);
        verifyNoInteractions(cachedDataRepository, awsClientProvider);
    }

    @Test
    void dbSnapshotHit_rewarmsRedis() throws Exception {
        when(redisCacheService.get(REDIS_KEY, CostResponse.class)).thenReturn(Optional.empty());
        CachedData snapshot = new CachedData();
        snapshot.setCacheKey(DB_KEY);
        snapshot.setJsonData(objectMapper.writeValueAsString(cost(2.5)));
        when(cachedDataRepository.findById(DB_KEY)).thenReturn(Optional.of(snapshot));

        Optional<CostResponse> result = service.getCachedCostData(account);

        assertThat(result).isPresent();
        assertThat(result.get().getSummary().getTotalPotentialSavings()).isEqualTo(2.5);
        ArgumentCaptor<CostResponse> warmed = ArgumentCaptor.forClass(CostResponse.class);
        verify(redisCacheService).put(eq(REDIS_KEY), warmed.capture(), eq(240));
        assertThat(warmed.getValue().getSummary().getTotalPotentialSavings()).isEqualTo(2.5);
        verifyNoInteractions(awsClientProvider);
    }

    @Test
    void miss_returnsEmptyWithoutCallingAws() {
        when(redisCacheService.get(REDIS_KEY, CostResponse.class)).thenReturn(Optional.empty());
        when(cachedDataRepository.findById(DB_KEY)).thenReturn(Optional.empty());

        assertThat(service.getCachedCostData(account)).isEmpty();
        verifyNoInteractions(awsClientProvider);
    }

    @Test
    void getCostDataWithoutForce_usesTheCacheFirst() {
        CostResponse cached = cost(0.75);
        when(redisCacheService.get(REDIS_KEY, CostResponse.class)).thenReturn(Optional.of(cached));

        assertThat(service.getCostData(account, false)).isSameAs(cached);
        verifyNoInteractions(awsClientProvider);
    }
}
