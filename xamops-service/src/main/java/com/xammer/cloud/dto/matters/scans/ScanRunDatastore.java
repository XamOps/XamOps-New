package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One datastore of a scan run (design 5.4). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ScanRunDatastore(String id, String name, String subtype, String subtypeLabel, String accountName,
                               String region, String status, String statusLabel, String failureReason) {
}
