package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Schedule Details (design 5.4).
 *
 * @param targets      the first page of selected datastores
 * @param targetsTotal all selected datastores
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ScanScheduleDetail(ScanSchedule schedule, String scopeMode, List<String> scopeNames,
                                 List<ScanTarget> targets, long targetsTotal) {
}
