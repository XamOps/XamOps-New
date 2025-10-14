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
     * Constructor with only name and amount (backward compatible)
     */
    public CostDto(String name, double amount) {
        this.name = name;
        this.amount = amount;
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
