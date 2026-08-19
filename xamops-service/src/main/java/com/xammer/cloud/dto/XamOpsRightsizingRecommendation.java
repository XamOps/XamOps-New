package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class XamOpsRightsizingRecommendation {
    private String currentInstance;         // Instance type (e.g., "t3.medium")
    private String instanceId;              // Actual instance ID
    private String instanceName;            // Instance name tag
    private String currentUtilization;      //Actual CPU utilization
    private String loadRange;               // Load range (e.g., "0-50%")
    private String intelRecommendation;     // Intel-based recommendation
    private String amdRecommendation;       // AMD-based recommendation
    private String projectedMaxUtil;        // Projected max utilization
    private String approxCostSavings;       // Cost savings estimate
    private String reason;                  // Recommendation reason
    private String region;                  // AWS region where the instance runs

    /**
     * Live monthly cost of the current instance type in {@link #region}, in USD.
     * Null when the Pricing API lookup failed.
     */
    private Double currentMonthlyCost;

    /** Live monthly cost of the Intel option in {@link #region}, in USD. */
    private Double intelMonthlyCost;

    /** Live monthly cost of the AMD option in {@link #region}, in USD. */
    private Double amdMonthlyCost;

    /**
     * Best realizable monthly saving across the Intel and AMD options, in USD.
     *
     * <p>Distinct from {@link #approxCostSavings}, which is the source spreadsheet's
     * percentage-range string (e.g. {@code "~87% / ~88%"}) and cannot be summed. This field
     * is a real currency amount derived from live regional prices.
     */
    private double estimatedMonthlySavings;

    /** ISO-4217 code for the monetary fields above. */
    private String currency;

    /**
     * True when no monthly saving could be priced, so {@link #estimatedMonthlySavings} is a
     * placeholder zero. Consumers must exclude these from savings totals.
     */
    private boolean savingsUnknown;
}
