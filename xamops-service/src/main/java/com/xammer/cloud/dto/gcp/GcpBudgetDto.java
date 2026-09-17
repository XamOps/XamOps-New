package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpBudgetDto {
    private String budgetName;
    private double budgetLimit;

    /**
     * Spend to date against this budget, or null when it could not be read.
     *
     * <p>Nullable because the Cloud Billing Budgets API returns a budget's configuration but not its
     * consumption — that has to come from the billing export. Both this and
     * {@link #forecastedSpend} were previously hardcoded to {@code 0.0} for every budget the API
     * returned, so every progress bar read 0% and a budget already over its limit looked untouched.
     */
    private Double actualSpend;

    /** Projected end-of-month spend against this budget, or null when it could not be derived. */
    private Double forecastedSpend;

    /** Why {@link #actualSpend} and {@link #forecastedSpend} are null, or null when both are set. */
    private String spendUnavailableReason;

    public GcpBudgetDto(String budgetName, double budgetLimit, Double actualSpend, Double forecastedSpend) {
        this.budgetName = budgetName;
        this.budgetLimit = budgetLimit;
        this.actualSpend = actualSpend;
        this.forecastedSpend = forecastedSpend;
    }
}