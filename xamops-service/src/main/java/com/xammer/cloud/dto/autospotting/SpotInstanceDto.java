package com.xammer.cloud.dto.autospotting;

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
}
