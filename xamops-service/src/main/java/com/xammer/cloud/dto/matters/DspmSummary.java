package com.xammer.cloud.dto.matters;

import java.util.List;
import java.util.Map;

/**
 * Headline counts for the DSPM surface, from {@code GET /api/v1/assets/summary}.
 *
 * <p>The live response is richer than the documentation describes, in two ways worth exploiting. Each
 * entry under {@code tabs} carries its own {@code critical_high} and {@code total_sensitive_records}
 * alongside the asset count — so per-category figures come from the same single call rather than one
 * request per category. And the trends for all three metrics are fully populated (14 points each),
 * despite the docs stating that {@code critical_high} and {@code total_sensitive_records} are
 * forward-only and likely empty on a new environment.
 *
 * <p>The trend arrays are still treated as possibly empty. The documented behaviour is what a freshly
 * provisioned project will exhibit, and a sparkline that renders an empty array as a flat line at zero
 * would invent a history of "no findings" for an environment nobody has scanned yet.
 *
 * @param totalAssets           assets in scope.
 * @param criticalHigh          assets at CRITICAL or HIGH severity.
 * @param totalSensitiveRecords sensitive records summed across the scope.
 * @param byStatus              scan status → asset count. The live keys are {@code NOT_SCANNED},
 *                              {@code CLASSIFIED}, {@code FAILED}, {@code STOPPED} and
 *                              {@code SCANNING}; {@code NOT_SCANNED} is undocumented and dominant, and
 *                              the documented {@code SCHEDULED} never appears. This map is what lets the
 *                              UI lead with scan coverage — on the live project only 36 of 132 assets
 *                              are classified, with 17 failed and 4 stopped, which is the most
 *                              actionable fact available.
 * @param tabs                  category → its counts, so the category strip needs no extra requests.
 */
public record DspmSummary(
        Metric totalAssets,
        Metric criticalHigh,
        Metric totalSensitiveRecords,
        Map<String, Long> byStatus,
        Map<String, Tab> tabs
) {

    /**
     * @param value live figure. Always authoritative; the trend is a best-effort sparkline.
     * @param trend up to 14 daily points, oldest first. Empty when no snapshot history exists.
     */
    public record Metric(long value, List<Point> trend) {
    }

    /** @param date {@code YYYY-MM-DD}. */
    public record Point(String date, long value) {
    }

    /** Per-category counts, each with its own trend. */
    public record Tab(long value, List<Point> trend, long criticalHigh, long sensitiveRecords) {
    }
}
