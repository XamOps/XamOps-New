package com.xammer.cloud.dto.dbops;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbIdleInstanceDto {
    private String instanceId;
    private String instanceClass;
    private String engine;
    private String region;
    private double avgCpuPercent;
    private double avgConnections;
    private double estimatedMonthlyCost;
    private double potentialSavings;
    private String recommendation;
}
