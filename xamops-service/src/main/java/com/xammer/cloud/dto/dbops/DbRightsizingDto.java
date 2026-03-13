package com.xammer.cloud.dto.dbops;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbRightsizingDto {
    private String instanceId;
    private String currentInstanceClass;
    private String recommendedInstanceClass;
    private String engine;
    private String region;
    private double avgCpuPercent;
    private double avgFreeableMemoryMb;
    private double currentMonthlyCost;
    private double recommendedMonthlyCost;
    private double estimatedSavings;
    private String reason;
}
