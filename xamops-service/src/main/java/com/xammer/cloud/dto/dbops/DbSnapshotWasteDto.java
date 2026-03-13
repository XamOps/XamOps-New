package com.xammer.cloud.dto.dbops;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbSnapshotWasteDto {
    private String snapshotId;
    private String instanceId;
    private String region;
    private int ageDays;
    private int sizeGb;
    private double estimatedMonthlyCost;
    private String recommendation;
}
