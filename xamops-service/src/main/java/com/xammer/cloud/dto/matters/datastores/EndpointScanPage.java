package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One page of the Endpoint drawer's Scan History (design 5.4 item 14).
 *
 * @param rows  newest first, as returned.
 * @param skip  echoes the request.
 * @param limit echoes the request.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EndpointScanPage(List<Scan> rows, int skip, int limit, long total) {

    /**
     * @param startTime  epoch seconds.
     * @param endTime    epoch seconds; null while running.
     * @param status     upper-cased: the status icon.
     * @param totalFiles null when the vendor sends a negative count (GAP-50).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Scan(Long startTime, Long endTime, String status, String statusLabel, Long sensitiveFiles,
                       Long totalFiles) {
    }
}
