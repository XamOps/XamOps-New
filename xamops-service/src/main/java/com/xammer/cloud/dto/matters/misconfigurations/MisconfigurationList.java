package com.xammer.cloud.dto.matters.misconfigurations;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The XamLens Misconfigurations page: the two analytics cards and one row per policy, aggregated from
 * every cloud and on-prem datastore's {@code issues/overview} (build 5, plan 1.3 and 1.4).
 *
 * @param totalAffectedResources distinct datastores with at least one policy.
 * @param open                   policies, in total and by severity.
 * @param datastoresRead         datastores whose issues were read.
 * @param capped                 true when the project has more datastores than were read.
 * @param rows                   severity, then affected resources descending, then title, then policy id.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MisconfigurationList(long totalAffectedResources, SeverityCounts open, int datastoresRead,
                                   boolean capped, List<Row> rows) {

    /** @param total every policy; a severity outside the four counts here only. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SeverityCounts(long total, long critical, long high, long medium, long low) {
    }

    /**
     * @param source           MATTERS or UPWIND, upper-cased.
     * @param severity         upper-cased, as the vendor sent it.
     * @param sensitiveRecords sum of the affected datastores' sensitive record counts.
     * @param labels           union of the affected datastores' sensitive type labels, first seen first.
     * @param frameworks       distinct compliance framework names, first seen first.
     * @param cloudAccounts    distinct account labels of the affected cloud datastores.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Row(String policyId, String title, String source, String severity, String severityLabel,
                      int affectedResources, long sensitiveRecords, List<String> labels, List<String> frameworks,
                      List<String> cloudAccounts) {
    }
}
