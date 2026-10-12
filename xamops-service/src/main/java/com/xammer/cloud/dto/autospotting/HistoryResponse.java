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
        @JsonProperty("total_current_cost")
        private Double currentHourlyCost;
        @JsonProperty("total_ondemand_cost") // inferred, but usually pairs with current
        private Double ondemandHourlyCost;
        @JsonProperty("total_actual_savings")
        private Double actualHourlySavings;
        @JsonProperty("total_potential_savings")
        private Double potentialHourlySavings;
        @JsonProperty("spot_instance_count")
        private Integer spotInstanceCount;
        @JsonProperty("ondemand_instance_count")
        private Integer ondemandInstanceCount;
        /**
         * True when the point's amount comes from a legacy source (a {@code daily_spot_savings}
         * row or the {@code spot_optimization_event} fallback). Shown only as "Legacy
         * (unverified)" and left out of every summary total (D-12, AC-LED-11).
         */
        @JsonProperty("legacy")
        private Boolean legacy;
    }
    @Data
    public static class HistorySummary {
        /** Non-legacy points only. */
        @JsonProperty("total_savings")
        private Double totalActualSavings;
        @JsonProperty("total_potential_savings")
        private Double totalPotentialSavings;
        @JsonProperty("average_hourly_cost")
        private Double averageHourlyCost;
        @JsonProperty("average_hourly_savings")
        private Double averageHourlySavings;
        @JsonProperty("peak_savings")
        private Double peakSavings;
        /** Sum of the legacy points' amounts, reported apart from the totals above. */
        @JsonProperty("legacy_total_savings")
        private Double legacyTotalSavings;
    }
}
