package com.xammer.cloud.service;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.GroupLookup;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EbsAsgHelperTest {

    @Mock Ec2Client ec2;
    @Mock AutoScalingClient asg;
    @Mock GroupLookup groupLookup;
    @Mock LeaseService leaseService;
    @Mock FencedTx fencedTx;
    @Mock AutoSpottingAuditWriter auditWriter;
    @InjectMocks EbsAsgHelper helper;

    @Test
    void updateLaunchTemplateVolumeType_createsNewVersionAndSetsDefault() {
        when(asg.describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class)))
                .thenReturn(DescribeAutoScalingGroupsResponse.builder()
                        .autoScalingGroups(AutoScalingGroup.builder()
                                .autoScalingGroupName("my-asg")
                                .launchTemplate(software.amazon.awssdk.services.autoscaling.model
                                        .LaunchTemplateSpecification.builder()
                                        .launchTemplateId("lt-0abc123")
                                        .version("$Default")
                                        .build())
                                .build())
                        .build());

        when(ec2.describeLaunchTemplateVersions(any(DescribeLaunchTemplateVersionsRequest.class)))
                .thenReturn(DescribeLaunchTemplateVersionsResponse.builder()
                        .launchTemplateVersions(LaunchTemplateVersion.builder()
                                .launchTemplateData(ResponseLaunchTemplateData.builder()
                                        .blockDeviceMappings(LaunchTemplateBlockDeviceMapping.builder()
                                                .deviceName("/dev/xvda")
                                                .ebs(LaunchTemplateEbsBlockDevice.builder()
                                                        .volumeType(VolumeType.GP2)
                                                        .volumeSize(100)
                                                        .deleteOnTermination(true)
                                                        .encrypted(false)
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .build());

        when(ec2.createLaunchTemplateVersion(any(CreateLaunchTemplateVersionRequest.class)))
                .thenReturn(CreateLaunchTemplateVersionResponse.builder()
                        .launchTemplateVersion(LaunchTemplateVersion.builder()
                                .versionNumber(2L)
                                .build())
                        .build());

        helper.updateLaunchTemplateVolumeType(ec2, asg, "my-asg", "/dev/xvda", "gp3");

        verify(ec2).createLaunchTemplateVersion(any(CreateLaunchTemplateVersionRequest.class));
        verify(ec2).modifyLaunchTemplate(argThat((ModifyLaunchTemplateRequest req) ->
                "lt-0abc123".equals(req.launchTemplateId()) && "2".equals(req.defaultVersion())));
    }

    @Test
    void updateLaunchTemplateVolumeSize_updatesEbsSizeInBdm() {
        when(asg.describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class)))
                .thenReturn(DescribeAutoScalingGroupsResponse.builder()
                        .autoScalingGroups(AutoScalingGroup.builder()
                                .autoScalingGroupName("my-asg")
                                .launchTemplate(software.amazon.awssdk.services.autoscaling.model
                                        .LaunchTemplateSpecification.builder()
                                        .launchTemplateId("lt-0abc123")
                                        .version("$Default")
                                        .build())
                                .build())
                        .build());

        when(ec2.describeLaunchTemplateVersions(any(DescribeLaunchTemplateVersionsRequest.class)))
                .thenReturn(DescribeLaunchTemplateVersionsResponse.builder()
                        .launchTemplateVersions(LaunchTemplateVersion.builder()
                                .launchTemplateData(ResponseLaunchTemplateData.builder()
                                        .blockDeviceMappings(LaunchTemplateBlockDeviceMapping.builder()
                                                .deviceName("/dev/xvda")
                                                .ebs(LaunchTemplateEbsBlockDevice.builder()
                                                        .volumeType(VolumeType.GP3)
                                                        .volumeSize(40)
                                                        .deleteOnTermination(true)
                                                        .encrypted(false)
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .build());

        when(ec2.createLaunchTemplateVersion(any(CreateLaunchTemplateVersionRequest.class)))
                .thenReturn(CreateLaunchTemplateVersionResponse.builder()
                        .launchTemplateVersion(LaunchTemplateVersion.builder().versionNumber(2L).build())
                        .build());

        helper.updateLaunchTemplateVolumeSize(ec2, asg, "my-asg", "/dev/xvda", 60);

        verify(ec2).createLaunchTemplateVersion(argThat((CreateLaunchTemplateVersionRequest req) ->
                req.launchTemplateData().blockDeviceMappings().stream()
                        .anyMatch(bdm -> "/dev/xvda".equals(bdm.deviceName())
                                && bdm.ebs().volumeSize() == 60)));
        verify(ec2).modifyLaunchTemplate(argThat((ModifyLaunchTemplateRequest req) -> "2".equals(req.defaultVersion())));
    }

    @Test
    void startInstanceRefresh_callsAsgApiWithMinHealthy90() {
        helper.startInstanceRefresh(asg, "my-asg");

        verify(asg).startInstanceRefresh(argThat((StartInstanceRefreshRequest req) ->
                "my-asg".equals(req.autoScalingGroupName())
                && req.preferences().minHealthyPercentage() == 90));
    }

    @Test
    void updateLaunchTemplateVolumeType_preservesEphemeralDevicesInPassThrough() {
        when(asg.describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class)))
                .thenReturn(DescribeAutoScalingGroupsResponse.builder()
                        .autoScalingGroups(AutoScalingGroup.builder()
                                .autoScalingGroupName("my-asg")
                                .launchTemplate(software.amazon.awssdk.services.autoscaling.model
                                        .LaunchTemplateSpecification.builder()
                                        .launchTemplateId("lt-0abc123")
                                        .version("$Default")
                                        .build())
                                .build())
                        .build());

        when(ec2.describeLaunchTemplateVersions(any(DescribeLaunchTemplateVersionsRequest.class)))
                .thenReturn(DescribeLaunchTemplateVersionsResponse.builder()
                        .launchTemplateVersions(LaunchTemplateVersion.builder()
                                .launchTemplateData(ResponseLaunchTemplateData.builder()
                                        .blockDeviceMappings(
                                                // Target device: gp2 root
                                                LaunchTemplateBlockDeviceMapping.builder()
                                                        .deviceName("/dev/xvda")
                                                        .ebs(LaunchTemplateEbsBlockDevice.builder()
                                                                .volumeType(VolumeType.GP2)
                                                                .volumeSize(100)
                                                                .deleteOnTermination(true)
                                                                .encrypted(false)
                                                                .build())
                                                        .build(),
                                                // Pass-through: ephemeral (instance-store)
                                                LaunchTemplateBlockDeviceMapping.builder()
                                                        .deviceName("/dev/xvdb")
                                                        .virtualName("ephemeral0")
                                                        .build()
                                        )
                                        .build())
                                .build())
                        .build());

        when(ec2.createLaunchTemplateVersion(any(CreateLaunchTemplateVersionRequest.class)))
                .thenReturn(CreateLaunchTemplateVersionResponse.builder()
                        .launchTemplateVersion(LaunchTemplateVersion.builder().versionNumber(2L).build())
                        .build());

        helper.updateLaunchTemplateVolumeType(ec2, asg, "my-asg", "/dev/xvda", "gp3");

        // The new BDM list must include the ephemeral device with virtualName preserved
        verify(ec2).createLaunchTemplateVersion(argThat((CreateLaunchTemplateVersionRequest req) ->
                req.launchTemplateData().blockDeviceMappings().stream()
                        .anyMatch(bdm -> "/dev/xvdb".equals(bdm.deviceName())
                                && "ephemeral0".equals(bdm.virtualName()))));
    }
    // ---- B5: EBS automation on managed groups (design-aws.md §5.5, AC-CMP-01) ----

    private static final String REGION = "us-east-1";

    private static software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification spec() {
        return software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification.builder()
                .launchTemplateId("lt-0abc123").version("$Default").build();
    }

    private static AutoScalingGroup mixedGroup(boolean perTypeTemplates) {
        software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides.Builder o = software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides.builder().instanceType("m5.large");
        if (perTypeTemplates) {
            o.launchTemplateSpecification(spec());
        }
        return AutoScalingGroup.builder().autoScalingGroupName("my-asg")
                .mixedInstancesPolicy(MixedInstancesPolicy.builder()
                        .launchTemplate(software.amazon.awssdk.services.autoscaling.model.LaunchTemplate.builder().launchTemplateSpecification(spec())
                                .overrides(o.build()).build())
                        .build())
                .build();
    }

    private void describes(AutoScalingGroup g) {
        when(asg.describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class)))
                .thenReturn(DescribeAutoScalingGroupsResponse.builder().autoScalingGroups(g).build());
    }

    private void template() {
        when(ec2.describeLaunchTemplateVersions(any(DescribeLaunchTemplateVersionsRequest.class)))
                .thenReturn(DescribeLaunchTemplateVersionsResponse.builder()
                        .launchTemplateVersions(LaunchTemplateVersion.builder()
                                .launchTemplateData(ResponseLaunchTemplateData.builder()
                                        .blockDeviceMappings(LaunchTemplateBlockDeviceMapping.builder()
                                                .deviceName("/dev/xvda")
                                                .ebs(LaunchTemplateEbsBlockDevice.builder().volumeType(VolumeType.GP2)
                                                        .volumeSize(100).build()).build())
                                        .build()).build()).build());
        when(ec2.createLaunchTemplateVersion(any(CreateLaunchTemplateVersionRequest.class)))
                .thenReturn(CreateLaunchTemplateVersionResponse.builder()
                        .launchTemplateVersion(LaunchTemplateVersion.builder().versionNumber(5L).build()).build());
    }

    private SpotGroup row(GroupState state) {
        SpotGroup g = new SpotGroup();
        g.setId(UUID.randomUUID());
        g.setProvider(Provider.AWS);
        g.setCloudAccountId(7L);
        g.setGroupKey(REGION + "/my-asg");
        g.setState(state);
        when(groupLookup.live(Provider.AWS, 7L, REGION + "/my-asg")).thenReturn(Optional.of(g));
        return g;
    }

    private LeaseHandle lease(SpotGroup g) {
        LeaseHandle handle = mock(LeaseHandle.class);
        when(leaseService.acquireGroup(g.getId(), LeaseService.Component.EBS)).thenReturn(Optional.of(handle));
        return handle;
    }

    @Test
    void aMixedInstancesPolicyGroupResolvesItsTemplate() {
        describes(mixedGroup(false));
        template();
        helper.updateLaunchTemplateVolumeType(7L, REGION, ec2, asg, "my-asg", "/dev/xvda", "gp3");
        verify(ec2).createLaunchTemplateVersion(argThat((CreateLaunchTemplateVersionRequest r) ->
                "lt-0abc123".equals(r.launchTemplateId())));
        verify(ec2).modifyLaunchTemplate(argThat((ModifyLaunchTemplateRequest r) ->
                "lt-0abc123".equals(r.launchTemplateId()) && "5".equals(r.defaultVersion())));
    }

    @Test
    void perTypeLaunchTemplatesAreRefusedWithNoChange() {
        describes(mixedGroup(true));
        assertThatThrownBy(() -> helper.updateLaunchTemplateVolumeType(7L, REGION, ec2, asg, "my-asg",
                "/dev/xvda", "gp3"))
                .isInstanceOf(IllegalStateException.class).hasMessage(EbsAsgHelper.PER_TYPE_TEMPLATES);
        verify(ec2, never()).createLaunchTemplateVersion(any(CreateLaunchTemplateVersionRequest.class));
        verify(ec2, never()).modifyLaunchTemplate(any(ModifyLaunchTemplateRequest.class));
    }

    @Test
    void aHeldLeaseRefusesWithZeroAwsCalls() {
        SpotGroup g = row(GroupState.ACTIVE);
        when(leaseService.acquireGroup(g.getId(), LeaseService.Component.EBS)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> helper.startInstanceRefresh(7L, REGION, asg, "my-asg"))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("AUTOSPOTTING_OPERATION_IN_PROGRESS"));
        assertThatThrownBy(() -> helper.updateLaunchTemplateVolumeSize(7L, REGION, ec2, asg, "my-asg", "/dev/xvda", 60))
                .isInstanceOf(AutoSpottingApiException.class);
        verifyNoInteractions(asg, ec2);
    }

    @Test
    void anApplyingOrRevertingGroupRefusesWithZeroAwsCallsAndNoLease() {
        for (GroupState busy : new GroupState[] {GroupState.APPLYING, GroupState.REVERTING}) {
            row(busy);
            assertThatThrownBy(() -> helper.startInstanceRefresh(7L, REGION, asg, "my-asg"))
                    .isInstanceOfSatisfying(AutoSpottingApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("AUTOSPOTTING_OPERATION_IN_PROGRESS"));
        }
        verifyNoInteractions(asg, ec2, leaseService);
    }

    @Test
    void aManagedGroupRefreshesLaunchBeforeTerminateUnderTheLeaseAndAuditsIt() {
        SpotGroup g = row(GroupState.ACTIVE);
        LeaseHandle handle = lease(g);
        when(asg.startInstanceRefresh(any(StartInstanceRefreshRequest.class)))
                .thenReturn(StartInstanceRefreshResponse.builder().instanceRefreshId("ir-1").build());
        helper.startInstanceRefresh(7L, REGION, asg, "my-asg");
        verify(asg).startInstanceRefresh(argThat((StartInstanceRefreshRequest r) ->
                "my-asg".equals(r.autoScalingGroupName())
                        && r.preferences().minHealthyPercentage() == 100
                        && r.preferences().maxHealthyPercentage() == 110
                        && Boolean.TRUE.equals(r.preferences().skipMatching())));
        verify(handle).assertHeld(any());
        verify(fencedTx).run(eq(handle), any(FencedTx.Work.class));
        verify(leaseService).releaseGroup(handle);
    }

    @Test
    void aManagedGroupsTemplateVersionIsWrittenUnderTheLeaseAndAudited() {
        SpotGroup g = row(GroupState.FALLBACK);
        LeaseHandle handle = lease(g);
        describes(mixedGroup(false));
        template();
        helper.updateLaunchTemplateVolumeType(7L, REGION, ec2, asg, "my-asg", "/dev/xvda", "gp3");
        verify(fencedTx).run(eq(handle), any(FencedTx.Work.class));
        verify(leaseService).releaseGroup(handle);
    }

    @Test
    void anUnmanagedGroupsRefreshIsByteIdenticalToToday() {
        // No spot_group row: no lease, and the request is the one the helper always sent (AC-CMP-01, FR-CMP-05).
        helper.startInstanceRefresh(7L, REGION, asg, "my-asg");
        StartInstanceRefreshRequest today = StartInstanceRefreshRequest.builder().autoScalingGroupName("my-asg")
                .preferences(RefreshPreferences.builder().minHealthyPercentage(90).build()).build();
        verify(asg).startInstanceRefresh(today);
        verifyNoInteractions(leaseService, fencedTx, auditWriter);
    }
}
