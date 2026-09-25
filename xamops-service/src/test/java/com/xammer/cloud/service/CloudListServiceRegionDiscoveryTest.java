package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.model.StsException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Region discovery when AWS cannot be reached — here, with the expired credentials that made the
 * Alerts page scan zero regions and report every regional check as clean.
 */
class CloudListServiceRegionDiscoveryTest {

    private static final String ACCOUNT_ID = "605134457560";

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private AwsClientProvider awsClientProvider;
    @Mock private CachedDataRepository cachedDataRepository;
    @Mock private RedisCacheService redisCache;
    @Mock private S3Client s3;
    @Mock private Ec2Client ec2;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CloudListService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CloudListService(cloudAccountRepository, awsClientProvider, cachedDataRepository, objectMapper);
        Executor direct = Runnable::run;
        ReflectionTestUtils.setField(service, "redisCache", redisCache);
        ReflectionTestUtils.setField(service, "awsTaskExecutor", direct);
        ReflectionTestUtils.setField(service, "backgroundTaskExecutor", direct);
        ReflectionTestUtils.setField(service, "regionalCheckExecutor", direct);

        account = mock(CloudAccount.class);
        when(account.getAwsAccountId()).thenReturn(ACCOUNT_ID);

        StsException expired = (StsException) StsException.builder()
                .message("The security token included in the request is expired")
                .statusCode(403)
                .build();
        when(awsClientProvider.getS3Client(any(), anyString())).thenReturn(s3);
        when(awsClientProvider.getEc2Client(any(), anyString())).thenReturn(ec2);
        when(s3.listBuckets()).thenThrow(expired);
        when(ec2.describeRegions()).thenThrow(expired);
    }

    @Test
    void strict_failsRatherThanReportingNoRegions() {
        when(cachedDataRepository.findById(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRegionStatusForAccountOrFail(account, true).join())
                .isInstanceOf(CompletionException.class)
                .hasMessageContaining("security token included in the request is expired");
    }

    @Test
    void strict_fallsBackToTheRegionsFoundLastTime() throws Exception {
        // So the regional checks still run and each reports the real error.
        DashboardData.RegionStatus mumbai = new DashboardData.RegionStatus();
        mumbai.setRegionId("ap-south-1");
        CachedData saved = new CachedData();
        saved.setJsonData(objectMapper.writeValueAsString(List.of(mumbai)));
        when(cachedDataRepository.findById("REGION_STATUS::" + ACCOUNT_ID)).thenReturn(Optional.of(saved));

        List<DashboardData.RegionStatus> regions = service.getRegionStatusForAccountOrFail(account, true).join();

        assertThat(regions).extracting(DashboardData.RegionStatus::getRegionId).containsExactly("ap-south-1");
    }

    @Test
    void lenient_stillAnswersWithAnEmptyList() {
        // Unchanged for the many other callers that rely on it.
        assertThat(service.getRegionStatusForAccount(account, true).join()).isEmpty();
    }
}
