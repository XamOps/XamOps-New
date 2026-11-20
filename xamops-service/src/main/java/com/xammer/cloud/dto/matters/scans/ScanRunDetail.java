package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Scan History Details (design 5.4).
 *
 * @param accounts the run's account facet, for the datastores table's Account filter
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ScanRunDetail(ScanRun run, List<String> scopeNames, List<String> skippedExamples,
                            List<FacetValue> accounts) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FacetValue(String value, Long count) {
    }
}
