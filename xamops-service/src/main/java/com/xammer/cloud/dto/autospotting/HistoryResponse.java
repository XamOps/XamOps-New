package com.xammer.cloud.dto.autospotting;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class HistoryResponse {

    @JsonProperty("data_points")
    private List<DataPoint> dataPoints;

    @JsonProperty("summary")
    private HistorySummary summary;

    @JsonProperty("interval")
    private String interval;

    @Data
    public static class DataPoint {
        @JsonProperty("timestamp")
        private String timestamp;

        @JsonProperty("current_hourly_cost")
        private Double currentHourlyCost;

        @JsonProperty("ondemand_hourly_cost")
        private Double ondemandHourlyCost;

        @JsonProperty("actual_hourly_savings")
        private Double actualHourlySavings;

        @JsonProperty("potential_hourly_savings")
        private Double potentialHourlySavings;

        @JsonProperty("spot_instance_count")
        private Integer spotInstanceCount;

        @JsonProperty("ondemand_instance_count")
        private Integer ondemandInstanceCount;
    }

    @Data
    public static class HistorySummary {
        @JsonProperty("total_actual_savings")
        private Double totalActualSavings;

        @JsonProperty("total_potential_savings")
        private Double totalPotentialSavings;

        @JsonProperty("average_hourly_cost")
        private Double averageHourlyCost;

        @JsonProperty("average_hourly_savings")
        private Double averageHourlySavings;
    }
}
