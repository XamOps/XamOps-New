package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A datastore a schedule or scope selects (design 5.4). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ScanTarget(String id, String name, String subtype, String subtypeLabel) {
}
