package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The endpoint agents' scan configuration (design 5.4), whitelisted field by field.
 *
 * @param configured          the vendor returned a non-empty configuration
 * @param reconciliationStart HH:MM, else null
 * @param reconciliationEnd   HH:MM, else null
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EndpointScanConfiguration(boolean configured, String scanMode, String reconciliationStart,
                                        String reconciliationEnd, List<String> includePaths,
                                        List<String> excludePaths, List<String> fileTypes,
                                        List<String> allFileTypes) {
}
