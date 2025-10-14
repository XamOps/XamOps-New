package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CostForecastDto {

    @JsonProperty("dates")
    private List<String> dates;

    @JsonProperty("predictions")
    private List<Double> predictions;

    @JsonProperty("lowerBounds")
    private List<Double> lowerBounds;

    @JsonProperty("upperBounds")
    private List<Double> upperBounds;

    /**
     * Get total forecasted cost
     */
    public double getTotalForecast() {
        return predictions.stream()
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    /**
     * Get average daily forecast
     */
    public double getAverageDailyForecast() {
        if (predictions.isEmpty()) return 0.0;
        return getTotalForecast() / predictions.size();
    }

    /**
     * Get confidence range (difference between upper and lower bounds)
     */
    public double getConfidenceRange() {
        double totalUpper = upperBounds.stream().mapToDouble(Double::doubleValue).sum();
        double totalLower = lowerBounds.stream().mapToDouble(Double::doubleValue).sum();
        return totalUpper - totalLower;
    }

    /**
     * Check if forecast is trending up or down
     */
    public String getTrend() {
        if (predictions.size() < 2) return "STABLE";
        double first = predictions.get(0);
        double last = predictions.get(predictions.size() - 1);
        if (last > first * 1.05) return "UP";
        if (last < first * 0.95) return "DOWN";
        return "STABLE";
    }
}
