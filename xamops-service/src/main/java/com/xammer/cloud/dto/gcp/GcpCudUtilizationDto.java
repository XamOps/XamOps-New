package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpCudUtilizationDto {
    private String cudName;
    private double utilizationPercentage;
    private double realizedSavings;
    private String analysisPeriod; // e.g., "LAST_30_DAYS"
}