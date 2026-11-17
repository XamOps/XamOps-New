package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The parts of {@code /api/v1/assets/summary} the KPI cards use (design 5.6.3). Cache-only: stored as
 * {@code dssummary}, never returned.
 *
 * @param byStatus every {@code cards.by_status} key with its count.
 * @param tabs     tab slug → its snapshot.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreSummarySnapshot(Map<String, Long> byStatus, DatastoreKpis.Metric totalSensitiveRecords,
                                       DatastoreKpis.Metric totalAssets, long criticalHigh,
                                       Map<String, TabSnapshot> tabs) {

    /** {@code tabs.<t>}: asset count with its trend, sensitive records with theirs, critical/high. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TabSnapshot(DatastoreKpis.Metric assets, DatastoreKpis.Metric sensitiveRecords, long criticalHigh) {
    }
}
