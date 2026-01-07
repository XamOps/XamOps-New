package com.xammer.cloud.dto.cloudidr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryResponse {
    private Double totalSavings;
    private Double totalCost;
    private Integer activeCount;
    private Integer completedCount;
    private Integer cancelledCount;
    private Double averageSavingsPercentage;
    private Double totalHours;
    private Double remainingHours;
    private Double totalAwsPrice;
}
