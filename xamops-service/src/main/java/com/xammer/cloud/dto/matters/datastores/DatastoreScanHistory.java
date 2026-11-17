package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The Scan History tab (design 5.4 item 11, rules in 5.6.9).
 *
 * @param runs newest first, as returned.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreScanHistory(List<Run> runs) {

    /** @param startTime epoch seconds. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Run(Integer executionNumber, Long startTime, Long sensitiveRecords, String sensitivityLevel,
                      String severityLabel, String triggeredBy, String status, String statusLabel) {
    }
}
