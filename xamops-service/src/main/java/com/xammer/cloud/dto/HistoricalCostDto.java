package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * DTO for carrying historical cost data for time-series charts and analysis.
 * Used for displaying cost trends over time periods.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class HistoricalCostDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Time period labels (e.g., ["2025-10-01", "2025-10-02"] or ["Jan 2025", "Feb 2025"])
     * ✅ FIXED: Initialized with mutable ArrayList for Redis deserialization
     */
    @JsonProperty("labels")
    private List<String> labels = new ArrayList<>();

    /**
     * Cost values corresponding to each label
     * ✅ FIXED: Initialized with mutable ArrayList for Redis deserialization
     */
    @JsonProperty("costs")
    private List<Double> costs = new ArrayList<>();

    /**
     * Optional: Metadata about the data source
     */
    @JsonProperty("metadata")
    private HistoricalMetadata metadata;

    /**
     * Constructor with just labels and costs (backward compatible)
     * ✅ FIXED: Creates defensive mutable copies
     */
    public HistoricalCostDto(List<String> labels, List<Double> costs) {
        this.labels = labels != null ? new ArrayList<>(labels) : new ArrayList<>();
        this.costs = costs != null ? new ArrayList<>(costs) : new ArrayList<>();
    }

    /**
     * Get total cost across all periods
     */
    public double getTotalCost() {
        if (costs == null || costs.isEmpty()) return 0.0;
        return costs.stream()
                .filter(cost -> cost != null && !cost.isNaN() && !cost.isInfinite())
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    /**
     * Get average cost across all periods
     */
    public double getAverageCost() {
        if (costs == null || costs.isEmpty()) return 0.0;
        return getTotalCost() / costs.size();
    }

    /**
     * Get maximum cost value
     */
    public double getMaxCost() {
        if (costs == null || costs.isEmpty()) return 0.0;
        return costs.stream()
                .filter(cost -> cost != null && !cost.isNaN() && !cost.isInfinite())
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);
    }

    /**
     * Get minimum cost value
     */
    public double getMinCost() {
        if (costs == null || costs.isEmpty()) return 0.0;
        return costs.stream()
                .filter(cost -> cost != null && !cost.isNaN() && !cost.isInfinite())
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(0.0);
    }

    /**
     * Get the index of the period with maximum cost
     */
    public int getMaxCostIndex() {
        if (costs == null || costs.isEmpty()) return -1;
        double max = getMaxCost();
        return IntStream.range(0, costs.size())
                .filter(i -> costs.get(i) != null && costs.get(i) == max)
                .findFirst()
                .orElse(-1);
    }

    /**
     * Get the label of the period with maximum cost
     */
    public String getMaxCostPeriod() {
        int index = getMaxCostIndex();
        if (index >= 0 && labels != null && index < labels.size()) {
            return labels.get(index);
        }
        return "N/A";
    }

    /**
     * Calculate cost trend (percentage change from first to last period)
     */
    public double getTrendPercentage() {
        if (costs == null || costs.size() < 2) return 0.0;

        double firstCost = costs.get(0);
        double lastCost = costs.get(costs.size() - 1);

        if (firstCost == 0) return lastCost > 0 ? 100.0 : 0.0;

        return ((lastCost - firstCost) / firstCost) * 100.0;
    }

    /**
     * Get trend direction (UP, DOWN, STABLE)
     */
    public String getTrendDirection() {
        double trend = getTrendPercentage();
        if (trend > 5.0) return "UP";
        if (trend < -5.0) return "DOWN";
        return "STABLE";
    }

    /**
     * Calculate period-over-period growth rates
     * ✅ FIXED: Returns mutable ArrayList instead of Collections.emptyList()
     */
    public List<Double> getGrowthRates() {
        if (costs == null || costs.size() < 2) {
            return new ArrayList<>(); // ✅ Mutable collection for Redis
        }

        List<Double> growthRates = new ArrayList<>();
        for (int i = 1; i < costs.size(); i++) {
            double previous = costs.get(i - 1);
            double current = costs.get(i);

            if (previous == 0) {
                growthRates.add(current > 0 ? 100.0 : 0.0);
            } else {
                growthRates.add(((current - previous) / previous) * 100.0);
            }
        }
        return growthRates;
    }

    /**
     * Get data size (number of periods)
     */
    public int getDataSize() {
        return costs != null ? costs.size() : 0;
    }

    /**
     * Check if data is empty
     */
    public boolean isEmpty() {
        return costs == null || costs.isEmpty();
    }

    /**
     * Check if data is valid (labels and costs match)
     */
    public boolean isValid() {
        return labels != null && costs != null && labels.size() == costs.size();
    }

    /**
     * Get formatted summary statistics
     */
    public String getSummary() {
        return String.format(
                "Historical Cost Summary: %d periods, Total: $%.2f, Avg: $%.2f, Max: $%.2f, Min: $%.2f, Trend: %s (%.1f%%)",
                getDataSize(),
                getTotalCost(),
                getAverageCost(),
                getMaxCost(),
                getMinCost(),
                getTrendDirection(),
                getTrendPercentage()
        );
    }

    /**
     * Metadata class for additional information
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoricalMetadata implements Serializable {
        private static final long serialVersionUID = 1L;

        @JsonProperty("accountId")
        private String accountId;

        @JsonProperty("serviceName")
        private String serviceName;

        @JsonProperty("regionName")
        private String regionName;

        @JsonProperty("startDate")
        private String startDate;

        @JsonProperty("endDate")
        private String endDate;

        @JsonProperty("granularity")
        private String granularity; // DAILY, MONTHLY, etc.
    }
}
