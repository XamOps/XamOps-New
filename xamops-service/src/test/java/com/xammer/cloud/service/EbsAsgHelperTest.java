package com.xammer.cloud.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EbsAsgHelperTest {

    @Mock Ec2Client ec2;
    @Mock AutoScalingClient asg;
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
}
