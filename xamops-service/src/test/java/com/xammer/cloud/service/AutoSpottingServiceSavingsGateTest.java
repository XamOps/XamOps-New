package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.repository.AsgSpotConfigRepository;
import com.xammer.cloud.repository.AutoSpottingSettingsRepository;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.xammer.cloud.service.autospotting.DefaultAutoSpottingFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FR-LED-29 / AC-LED-19: the AWS event-time writer {@code AutoSpottingService.updateDailySavings}
 * writes nothing with no {@code autospotting.aws.savings-ledger.enabled} line. AWS clients are
 * mocks; nothing reaches AWS.
 */
class AutoSpottingServiceSavingsGateTest {

    private AwsClientProvider awsClientProvider;
    private SpotPricingService spotPricingService;
    private SpotOptimizationEventRepository eventRepository;
    private DailySpotSavingsRepository savingsRepository;
    private AutoSpottingService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        awsClientProvider = mock(AwsClientProvider.class);
        spotPricingService = mock(SpotPricingService.class);
        eventRepository = mock(SpotOptimizationEventRepository.class);
        savingsRepository = mock(DailySpotSavingsRepository.class);
        service = new AutoSpottingService(awsClientProvider, mock(CloudAccountRepository.class),
                spotPricingService, eventRepository, savingsRepository,
                mock(AutoSpottingSettingsRepository.class), mock(AsgSpotConfigRepository.class),
                mock(RedisCacheService.class), mock(CachedDataRepository.class), new ObjectMapper(),
                DefaultAutoSpottingFlags.load());

        account = new CloudAccount();
        account.setId(7L);
        account.setProvider("AWS");
        account.setAwsAccountId("012345678901");
    }

    @Test
    void defaultConfig_updateDailySavingsWritesNothing() {
        ReflectionTestUtils.invokeMethod(service, "updateDailySavings", account, 0.42);

        verifyNoInteractions(savingsRepository);
    }

    /** The real caller: a Spot launch still records its event, but adds nothing to daily_spot_savings. */
    @Test
    void defaultConfig_spotLaunchEventRecordsTheEventButNoDailyRow() {
        Ec2Client ec2 = mock(Ec2Client.class);
        when(awsClientProvider.getEc2Client(account, "us-east-1")).thenReturn(ec2);
        when(ec2.describeInstances(ArgumentMatchers.<Consumer<DescribeInstancesRequest.Builder>>any()))
                .thenReturn(DescribeInstancesResponse.builder()
                        .reservations(Reservation.builder()
                                .instances(Instance.builder()
                                        .instanceId("i-0abc")
                                        .instanceType("m5.large")
                                        .instanceLifecycle("spot")
                                        .build())
                                .build())
                        .build());
        when(spotPricingService.calculateHourlySavings(any(), any(), any(), any())).thenReturn(0.05);

        ObjectNode detail = new ObjectMapper().createObjectNode()
                .put("state", "running")
                .put("instance-id", "i-0abc");
        service.handleScalingEvent(account, "us-east-1", detail);

        ArgumentCaptor<SpotOptimizationEvent> event = ArgumentCaptor.forClass(SpotOptimizationEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getStatus()).isEqualTo("SUCCESS");
        verifyNoInteractions(savingsRepository);
    }
}
