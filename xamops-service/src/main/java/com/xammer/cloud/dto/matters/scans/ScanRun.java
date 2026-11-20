package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * One Scan History run (design 5.4). Times are epoch seconds.
 *
 * @param statusCounts datastore status code → count
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ScanRun(String id, String name, String coverage, String coverageLabel, String platform,
                      String platformLabel, String trigger, String triggerLabel, Long scheduledTime, Long startedAt,
                      Long endedAt, Long durationSeconds, String status, String statusLabel, String phase,
                      String phaseLabel, Map<String, Long> statusCounts, long completedDatastores,
                      long totalDatastores, long skippedCount) {
}
