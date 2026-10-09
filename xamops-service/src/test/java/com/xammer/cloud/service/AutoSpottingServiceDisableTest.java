package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.repository.AsgSpotConfigRepository;
import com.xammer.cloud.repository.AutoSpottingSettingsRepository;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.xammer.cloud.service.autospotting.AutoSpottingBlockedException;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DeleteTagsRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * AWS disable order (decision 14): describe, revert the distribution to 100% On-Demand,
 * and only then remove the marker tag. Never an instance refresh. AWS is mocked.
 */
class AutoSpottingServiceDisableTest {

    private static final String AWS_ID = "012345678901";
    private static final String REGION = "us-east-1";
    private static final String ASG = "web";

    private AwsClientProvider awsClientProvider;
    private SpotOptimizationEventRepository eventRepository;
    private RedisCacheService redisCacheService;
    private CachedDataRepository cachedDataRepository;
    private AutoScalingClient asgClient;
    private AutoSpottingService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        awsClientProvider = mock(AwsClientProvider.class);
        eventRepository = mock(SpotOptimizationEventRepository.class);
        redisCacheService = mock(RedisCacheService.class);
        cachedDataRepository = mock(CachedDataRepository.class);
        asgClient = mock(AutoScalingClient.class);

        service = new AutoSpottingService(awsClientProvider, mock(CloudAccountRepository.class),
                mock(SpotPricingService.class), eventRepository, mock(DailySpotSavingsRepository.class),
                mock(AutoSpottingSettingsRepository.class), mock(AsgSpotConfigRepository.class),
                redisCacheService, cachedDataRepository, new ObjectMapper(),
                new AutoSpottingFeatureFlags(false, false, false));
        ReflectionTestUtils.setField(service, "tagKey", "spot-enabled");
        ReflectionTestUtils.setField(service, "tagValue", "true");
        ReflectionTestUtils.setField(service, "tableName", "autospotting-CustomerAccounts");

        account = new CloudAccount();
        account.setId(7L);
        account.setProvider("AWS");
        account.setAwsAccountId(AWS_ID);
        account.setAccessType("read-write");
        account.setStatus("CONNECTED");
    }

    private void givenAsg(boolean withMixedPolicy) {
        AutoScalingGroup.Builder asg = AutoScalingGroup.builder().autoScalingGroupName(ASG).desiredCapacity(2);
        if (withMixedPolicy) {
            asg.mixedInstancesPolicy(MixedInstancesPolicy.builder()
                    .instancesDistribution(InstancesDistribution.builder()
                            .onDemandBaseCapacity(0)
                            .onDemandPercentageAboveBaseCapacity(0)
                            .build())
                    .build());
        }
        when(awsClientProvider.getAutoScalingWriteClient(account, REGION)).thenReturn(asgClient);
        when(asgClient.describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class)))
                .thenReturn(DescribeAutoScalingGroupsResponse.builder().autoScalingGroups(asg.build()).build());
    }

    private void verifyCachesEvicted() {
        verify(redisCacheService).evict("autospotting-asgs-" + AWS_ID);
        verify(redisCacheService).evict("autospotting-cost-" + AWS_ID);
        verify(cachedDataRepository).deleteById("AUTOSPOTTING_COST::" + AWS_ID);
    }

    @Test
    void mixedPolicy_isRevertedBeforeTheTagIsRemoved() {
        givenAsg(true);

        service.disableAutoSpotting(account, REGION, ASG);

        InOrder order = inOrder(asgClient, eventRepository);
        order.verify(asgClient).describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class));
        ArgumentCaptor<UpdateAutoScalingGroupRequest> update = ArgumentCaptor.forClass(UpdateAutoScalingGroupRequest.class);
        order.verify(asgClient).updateAutoScalingGroup(update.capture());
        ArgumentCaptor<DeleteTagsRequest> tags = ArgumentCaptor.forClass(DeleteTagsRequest.class);
        order.verify(asgClient).deleteTags(tags.capture());
        ArgumentCaptor<SpotOptimizationEvent> event = ArgumentCaptor.forClass(SpotOptimizationEvent.class);
        order.verify(eventRepository).save(event.capture());

        InstancesDistribution distribution = update.getValue().mixedInstancesPolicy().instancesDistribution();
        assertThat(update.getValue().autoScalingGroupName()).isEqualTo(ASG);
        assertThat(distribution.onDemandBaseCapacity()).isZero();
        assertThat(distribution.onDemandPercentageAboveBaseCapacity()).isEqualTo(100);
        assertThat(update.getValue().mixedInstancesPolicy().launchTemplate()).isNull();

        assertThat(tags.getValue().tags()).singleElement().satisfies(tag -> {
            assertThat(tag.key()).isEqualTo("spot-enabled");
            assertThat(tag.resourceId()).isEqualTo(ASG);
            assertThat(tag.resourceType()).isEqualTo("auto-scaling-group");
        });
        assertThat(event.getValue().getStatus()).isEqualTo("DISABLED");
        assertThat(event.getValue().getDetails()).contains("launching new instances before terminating old ones");

        // Nothing else, in particular no startInstanceRefresh (either overload).
        verifyNoMoreInteractions(asgClient);
        verifyCachesEvicted();
    }

    @Test
    void policyUpdateFailure_propagatesAndKeepsTheTag() {
        givenAsg(true);
        when(asgClient.updateAutoScalingGroup(any(UpdateAutoScalingGroupRequest.class)))
                .thenThrow(AutoScalingException.builder().message("ScalingActivityInProgress").build());

        assertThatThrownBy(() -> service.disableAutoSpotting(account, REGION, ASG))
                .isInstanceOf(AutoScalingException.class)
                .hasMessageContaining("ScalingActivityInProgress");

        verify(asgClient, never()).deleteTags(any(DeleteTagsRequest.class));
        verifyNoInteractions(eventRepository);
        verifyCachesEvicted();
    }

    @Test
    void tagRemovalFailure_afterPolicyRevert_saysRetryIsSafe() {
        givenAsg(true);
        when(asgClient.deleteTags(any(DeleteTagsRequest.class)))
                .thenThrow(AutoScalingException.builder().message("Throttling").build());

        assertThatThrownBy(() -> service.disableAutoSpotting(account, REGION, ASG))
                .hasMessageContaining("retrying Disable is safe")
                .hasMessageContaining("Throttling");

        verify(asgClient).updateAutoScalingGroup(any(UpdateAutoScalingGroupRequest.class));
        verifyNoInteractions(eventRepository);
        verifyCachesEvicted();
    }

    @Test
    void noMixedPolicy_onlyRemovesTheTag() {
        givenAsg(false);

        service.disableAutoSpotting(account, REGION, ASG);

        verify(asgClient, never()).updateAutoScalingGroup(any(UpdateAutoScalingGroupRequest.class));
        verify(asgClient).deleteTags(any(DeleteTagsRequest.class));
        ArgumentCaptor<SpotOptimizationEvent> event = ArgumentCaptor.forClass(SpotOptimizationEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getStatus()).isEqualTo("DISABLED");
        verifyCachesEvicted();
    }

    @Test
    void unknownAsg_isRejectedWithoutChanges() {
        when(awsClientProvider.getAutoScalingWriteClient(account, REGION)).thenReturn(asgClient);
        when(asgClient.describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class)))
                .thenReturn(DescribeAutoScalingGroupsResponse.builder().build());

        assertThatThrownBy(() -> service.disableAutoSpotting(account, REGION, ASG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ASG not found: web");

        verify(asgClient, never()).updateAutoScalingGroup(any(UpdateAutoScalingGroupRequest.class));
        verify(asgClient, never()).deleteTags(any(DeleteTagsRequest.class));
        verifyNoInteractions(eventRepository, redisCacheService, cachedDataRepository);
    }

    @Test
    void readOnlyConnection_isRefusedBeforeAnyAsgCall() {
        when(awsClientProvider.getAutoScalingWriteClient(account, REGION))
                .thenThrow(AutoSpottingBlockedException.readOnlyConnection());

        assertThatThrownBy(() -> service.disableAutoSpotting(account, REGION, ASG))
                .isInstanceOfSatisfying(AutoSpottingBlockedException.class,
                        e -> assertThat(e.getCode()).isEqualTo(AutoSpottingBlockedException.READ_ONLY_CONNECTION));

        verifyNoInteractions(asgClient, eventRepository, redisCacheService, cachedDataRepository);
    }
}
