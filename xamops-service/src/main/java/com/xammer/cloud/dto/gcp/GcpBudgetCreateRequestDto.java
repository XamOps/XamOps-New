package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for creating a GCP budget with alert thresholds.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpBudgetCreateRequestDto {

    private String budgetName;
    private double budgetLimit;
    private String currencyCode; // Default: USD
    private List<Double> alertThresholds; // e.g., [0.5, 0.8, 0.9, 1.0] for 50%, 80%, 90%, 100%
    private String notificationEmail;

    // Constructor for simple budget creation
    public GcpBudgetCreateRequestDto(String budgetName, double budgetLimit) {
        this.budgetName = budgetName;
        this.budgetLimit = budgetLimit;
        this.currencyCode = "USD";
        this.alertThresholds = List.of(0.5, 0.8, 0.9, 1.0);
    }
}
