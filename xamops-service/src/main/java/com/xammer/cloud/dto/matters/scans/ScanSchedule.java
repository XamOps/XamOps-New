package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One Scan Management schedule (design 5.4).
 *
 * @param lastRunAt epoch seconds
 * @param nextRunAt epoch seconds
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ScanSchedule(String id, String name, String platform, String platformLabel, String coverage,
                           String coverageLabel, Long totalDatastores, boolean totalIsEstimate, String state,
                           boolean legacy, Timing timing, Long lastRunAt, String lastRunStatus,
                           String lastRunStatusLabel, Long nextRunAt) {

    /**
     * @param triggerTime epoch seconds
     * @param endDate     epoch seconds
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Timing(String type, Long triggerTime, String recurrenceType, List<String> repeatOn,
                         Integer dayOfMonth, Long endDate, String timezone) {
    }
}
