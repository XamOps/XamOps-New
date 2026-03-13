package com.xammer.cloud.dto.dbops;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbSavingsSummaryDto {
    private double totalDbMonthlyCost;
    private int totalInstances;
    private int idleInstances;
    private double idleSavingsPotential;
    private int rightsizingCandidates;
    private double rightsizingSavingsPotential;
    private double snapshotWasteCost;
    private double totalPotentialSavings;
    private double savingsPercentage;
}
