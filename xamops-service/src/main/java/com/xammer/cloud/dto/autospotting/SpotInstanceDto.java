package com.xammer.cloud.dto.autospotting;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpotInstanceDto {
    private String instanceId;
    private String instanceType;
    private String availabilityZone;
    private String lifecycle; // "spot" or "on-demand"
    private String state; // "running", "terminated", etc.
    private String asgName;
    private String region;
    private Double estimatedHourlySavings;
    private String launchTime;
    private String vpcId;
    private String subnetId;
    private String imageId; // AMI ID
    private String privateIpAddress;
    private String publicIpAddress;
    private List<SecurityGroupInfo> securityGroups;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SecurityGroupInfo {
        private String groupId;
        private String groupName;
    }
}
