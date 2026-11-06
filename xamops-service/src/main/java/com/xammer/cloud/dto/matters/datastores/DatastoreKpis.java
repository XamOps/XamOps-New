package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xammer.cloud.dto.matters.DspmSummary;

import java.util.List;
import java.util.Map;

/**
 * KPI cards of one Datastores tab (design 5.4 item 3, rules in 5.6.3).
 *
 * @param tabCounts asset count per tab slug: all, cloud, saas, endpoint, on_prem.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreKpis(String tab, Map<String, Long> tabCounts, Metric totalSensitiveRecords,
                            Metric assetCount, long classified, long scheduled, long failed, long stopped,
                            long criticalHigh) {

    /** @param trend daily points, oldest first; empty means no history. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Metric(long value, List<DspmSummary.Point> trend) {
    }
}
