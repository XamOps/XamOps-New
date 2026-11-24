package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One Scan Scope (design 5.4).
 *
 * @param updatedAt epoch seconds
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ScanScope(String id, String name, String platform, String platformLabel, String scopeMode,
                        Long totalDatastores, Long updatedAt) {
}
