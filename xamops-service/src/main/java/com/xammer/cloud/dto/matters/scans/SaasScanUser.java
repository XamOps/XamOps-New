package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One user of a SaaS scope (design 5.4). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SaasScanUser(String id, String email, String status, String statusLabel, boolean selected) {
}
