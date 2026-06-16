package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class EbsAsgHelper {

    private static final Logger logger = LoggerFactory.getLogger(EbsAsgHelper.class);

    public void updateLaunchTemplateVolumeType(Ec2Client ec2, AutoScalingClient asg,
                                               String asgName, String deviceName, String newType) {
        String ltId = getLaunchTemplateId(asg, asgName);
        LaunchTemplateVersion current = getCurrentLaunchTemplateVersion(ec2, ltId);

        List<LaunchTemplateBlockDeviceMappingRequest> newBdms = current.launchTemplateData()
                .blockDeviceMappings().stream()
                .map(bdm -> {
                    if (deviceName.equals(bdm.deviceName()) && bdm.ebs() != null) {
                        return LaunchTemplateBlockDeviceMappingRequest.builder()
                                .deviceName(bdm.deviceName())
                                .ebs(LaunchTemplateEbsBlockDeviceRequest.builder()
                                        .volumeType(VolumeType.fromValue(newType))
                                        .volumeSize(bdm.ebs().volumeSize())
                                        .deleteOnTermination(bdm.ebs().deleteOnTermination())
                                        .encrypted(bdm.ebs().encrypted())
                                        .build())
                                .build();
                    }
                    return LaunchTemplateBlockDeviceMappingRequest.builder()
                            .deviceName(bdm.deviceName()).build();
                })
                .collect(Collectors.toList());

        String newVersion = createAndSetDefaultVersion(ec2, ltId, newBdms);
        logger.info("[ASG] Launch template {} updated to version {} (volumeType={})", ltId, newVersion, newType);
    }

    public void updateLaunchTemplateVolumeSize(Ec2Client ec2, AutoScalingClient asg,
                                               String asgName, String deviceName, int newSizeGb) {
        String ltId = getLaunchTemplateId(asg, asgName);
        LaunchTemplateVersion current = getCurrentLaunchTemplateVersion(ec2, ltId);

        List<LaunchTemplateBlockDeviceMappingRequest> newBdms = current.launchTemplateData()
                .blockDeviceMappings().stream()
                .map(bdm -> {
                    if (deviceName.equals(bdm.deviceName()) && bdm.ebs() != null) {
                        return LaunchTemplateBlockDeviceMappingRequest.builder()
                                .deviceName(bdm.deviceName())
                                .ebs(LaunchTemplateEbsBlockDeviceRequest.builder()
                                        .volumeType(bdm.ebs().volumeType())
                                        .volumeSize(newSizeGb)
                                        .deleteOnTermination(bdm.ebs().deleteOnTermination())
                                        .encrypted(bdm.ebs().encrypted())
                                        .build())
                                .build();
                    }
                    return LaunchTemplateBlockDeviceMappingRequest.builder()
                            .deviceName(bdm.deviceName()).build();
                })
                .collect(Collectors.toList());

        String newVersion = createAndSetDefaultVersion(ec2, ltId, newBdms);
        logger.info("[ASG] Launch template {} updated to version {} (volumeSize={}GB)", ltId, newVersion, newSizeGb);
    }

    public void startInstanceRefresh(AutoScalingClient asg, String asgName) {
        asg.startInstanceRefresh(StartInstanceRefreshRequest.builder()
                .autoScalingGroupName(asgName)
                .preferences(RefreshPreferences.builder()
                        .minHealthyPercentage(90)
                        .build())
                .build());
        logger.info("[ASG] Instance refresh started for {}", asgName);
    }

    private String getLaunchTemplateId(AutoScalingClient asg, String asgName) {
        DescribeAutoScalingGroupsResponse resp = asg.describeAutoScalingGroups(
                DescribeAutoScalingGroupsRequest.builder()
                        .autoScalingGroupNames(asgName)
                        .build());
        if (resp.autoScalingGroups().isEmpty()) {
            throw new RuntimeException("ASG not found: " + asgName);
        }
        AutoScalingGroup group = resp.autoScalingGroups().get(0);
        if (group.launchTemplate() == null) {
            throw new RuntimeException("ASG " + asgName + " does not use a launch template");
        }
        return group.launchTemplate().launchTemplateId();
    }

    private LaunchTemplateVersion getCurrentLaunchTemplateVersion(Ec2Client ec2, String ltId) {
        DescribeLaunchTemplateVersionsResponse resp = ec2.describeLaunchTemplateVersions(
                DescribeLaunchTemplateVersionsRequest.builder()
                        .launchTemplateId(ltId)
                        .versions("$Default")
                        .build());
        if (resp.launchTemplateVersions().isEmpty()) {
            throw new RuntimeException("No default version found for launch template " + ltId);
        }
        return resp.launchTemplateVersions().get(0);
    }

    private String createAndSetDefaultVersion(Ec2Client ec2, String ltId,
                                              List<LaunchTemplateBlockDeviceMappingRequest> bdms) {
        CreateLaunchTemplateVersionResponse newVersionResp = ec2.createLaunchTemplateVersion(
                CreateLaunchTemplateVersionRequest.builder()
                        .launchTemplateId(ltId)
                        .sourceVersion("$Default")
                        .launchTemplateData(RequestLaunchTemplateData.builder()
                                .blockDeviceMappings(bdms)
                                .build())
                        .build());
        String newVersion = String.valueOf(newVersionResp.launchTemplateVersion().versionNumber());
        ec2.modifyLaunchTemplate(ModifyLaunchTemplateRequest.builder()
                .launchTemplateId(ltId)
                .defaultVersion(newVersion)
                .build());
        return newVersion;
    }
}
