package com.xammer.cloud.dto.autospotting;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class LaunchAnalyticsResponse {

    @JsonProperty("total_attempts")
    private Integer totalAttempts;

    @JsonProperty("total_successes")
    private Integer totalSuccesses;

    @JsonProperty("total_failures")
    private Integer totalFailures;

    @JsonProperty("success_rate")
    private Double successRate;

    @JsonProperty("by_instance_type")
    private Map<String, LaunchStats> byInstanceType;

    @JsonProperty("by_az")
    private Map<String, LaunchStats> byAz;

    @JsonProperty("by_region")
    private Map<String, LaunchStats> byRegion;

    @JsonProperty("top_failure_reasons")
    private List<FailureReason> topFailureReasons;

    @JsonProperty("recommended_types")
    private List<RecommendedType> recommendedTypes;

    @Data
    public static class RecommendedType {
        @JsonProperty("instance_type")
        private String instanceType;

        @JsonProperty("success_rate")
        private Double successRate;

        @JsonProperty("total_launches")
        private Integer totalLaunches;

        @JsonProperty("score")
        private Double score;
    }

    @Data
    public static class LaunchStats {
        @JsonProperty("attempts")
        private Integer attempts;

        @JsonProperty("successes")
        private Integer successes;

        @JsonProperty("failures")
        private Integer failures;

        @JsonProperty("success_rate")
        private Double successRate;
    }

    @Data
    public static class FailureReason {
        @JsonProperty("reason")
        private String reason;

        @JsonProperty("count")
        private Integer count;

        @JsonProperty("percentage")
        private Double percentage;
    }
}
