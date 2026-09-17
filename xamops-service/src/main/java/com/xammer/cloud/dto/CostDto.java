package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Data Transfer Object for cost breakdown information.
 * Used to transfer cost data between service and controller layers.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CostDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Name of the cost dimension (Service name, Region, Instance Type, etc.)
     */
    @JsonProperty("name")
    private String name;

    /**
     * Cost amount in USD
     */
    @JsonProperty("amount")
    private double amount;

    /**
     * Optional: Percentage of total cost
     */
    @JsonProperty("percentage")
    private Double percentage;

    /**
     * Optional: Cost trend compared to previous period (UP, DOWN, STABLE)
     */
    @JsonProperty("trend")
    private String trend;

    /**
     * Optional: Month-over-month change in percentage
     */
    @JsonProperty("changePercent")
    private Double changePercent;

    /**
     * True when {@link #amount} was reconstructed from a non-authoritative rate rather than read
     * from billing data.
     *
     * <p>Resource-level cost breakdown is partly reconstructed as {@code rate × hours}, because Cost
     * Explorer reports per service rather than per resource. When that rate comes from a live Price
     * List lookup the result is sound; when it falls back to a 2024 table or a size-pattern guess it
     * is a projection. Both used to arrive here as a bare number, indistinguishable from billed
     * spend.
     *
     * <p>Boxed so {@code NON_NULL} omits it entirely for rows that are genuinely billed data.
     */
    @JsonProperty("estimated")
    private Boolean estimated;

    /** Why {@link #amount} is an estimate, suitable for a UI disclaimer. Null when billed data. */
    @JsonProperty("estimateBasis")
    private String estimateBasis;

    /**
     * Constructor with only name and amount (backward compatible)
     */
    public CostDto(String name, double amount) {
        this.name = name;
        this.amount = amount;
    }

    /**
     * Marks this figure as an estimate and records why.
     *
     * @param basis provenance text, e.g. the note from an {@code AwsPricingService.RateQuote}
     * @return this, for chaining at the construction site
     */
    public CostDto markEstimated(String basis) {
        this.estimated = Boolean.TRUE;
        this.estimateBasis = basis;
        return this;
    }

    /**
     * Get formatted cost as currency string
     */
    public String getFormattedAmount() {
        return String.format("$%.2f", amount);
    }

    /**
     * Get formatted percentage string
     */
    public String getFormattedPercentage() {
        if (percentage == null) return "N/A";
        return String.format("%.1f%%", percentage);
    }

    /**
     * Check if this is a significant cost (> $100)
     */
    public boolean isSignificantCost() {
        return amount >= 100.0;
    }

    /**
     * Check if cost is negligible (< $1)
     */
    public boolean isNegligibleCost() {
        return amount < 1.0;
    }

    /**
     * Calculate percentage given total cost
     */
    public void calculatePercentage(double totalCost) {
        if (totalCost > 0) {
            this.percentage = (amount / totalCost) * 100.0;
        } else {
            this.percentage = 0.0;
        }
    }

    /**
     * Set trend based on change percentage
     */
    public void calculateTrend(double previousAmount) {
        if (previousAmount == 0) {
            this.trend = "NEW";
            this.changePercent = 100.0;
            return;
        }

        double change = ((amount - previousAmount) / previousAmount) * 100.0;
        this.changePercent = Math.round(change * 10.0) / 10.0; // Round to 1 decimal

        if (change > 5) {
            this.trend = "UP";
        } else if (change < -5) {
            this.trend = "DOWN";
        } else {
            this.trend = "STABLE";
        }
    }

    /**
     * Compare costs for sorting (descending by amount)
     */
    public int compareTo(CostDto other) {
        return Double.compare(other.amount, this.amount);
    }

    @Override
    public String toString() {
        return String.format("CostDto{name='%s', amount=%.2f, percentage=%.1f%%}",
                name, amount, percentage != null ? percentage : 0.0);
    }
}
