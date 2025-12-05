package com.xammer.cloud.dto.autospotting;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class EventsResponse {

    @JsonProperty("start")
    private String start;

    @JsonProperty("end")
    private String end;

    @JsonProperty("count")
    private Integer count;

    @JsonProperty("summary")
    private EventsSummary summary;

    @JsonProperty("events")
    private List<EventRecord> events;

    @Data
    public static class EventsSummary {
        @JsonProperty("total_replacements")
        private Integer totalReplacements;

        @JsonProperty("total_interruptions")
        private Integer totalInterruptions;

        @JsonProperty("total_estimated_savings")
        private Double totalEstimatedSavings;
    }
}
