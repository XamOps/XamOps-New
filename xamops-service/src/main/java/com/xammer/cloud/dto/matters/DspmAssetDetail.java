package com.xammer.cloud.dto.matters;

import java.util.List;
import java.util.Map;

/**
 * One asset in full: everything on the list row, plus the type-specific {@code facet} and the asset's
 * discovered objects.
 *
 * @param facet              type-specific attributes, passed through as a map rather than typed.
 *                           Deliberate: the keys differ per asset type and <b>keys with no value are
 *                           omitted</b>, so the live shapes range from 2 keys on an on-prem datastore
 *                           to 16 on an endpoint, and the vendor adds fields over time. A record would
 *                           have to be revised for every new attribute and would report absent keys as
 *                           null, which reads identically to "present and null". A map lets the UI
 *                           render exactly what is there.
 * @param objects            discovered objects, ordered by sensitive count descending.
 * @param objectTotal        true object count. Compare against {@code objects.size()} to detect
 *                           truncation.
 * @param objectsTruncated   precomputed {@code objects.size() < objectTotal}. The vendor caps
 *                           {@code objects} at 50 and offers no object-level pagination in v1, so a
 *                           5,211-object bucket returns 50 rows. Presenting those 50 as the whole
 *                           contents would materially misrepresent the asset, and the cap is not
 *                           visible from the array alone.
 * @param entityBreakdown    sensitive findings aggregated by detected entity type across the returned
 *                           objects. See {@link EntityCount} for why this can undercount.
 * @param breakdownPartial   true when {@link #objectsTruncated} is true, meaning
 *                           {@link #entityBreakdown} was computed from a subset and its counts are
 *                           lower bounds rather than totals.
 */
public record DspmAssetDetail(
        String id,
        String name,
        String subtype,
        String subtypeLabel,
        String category,
        String resourceType,
        String externalId,
        String lifecycle,
        DspmAssetRow.Risk risk,
        DspmAssetRow.Sensitives sensitives,
        DspmAssetRow.Source source,
        DspmAssetRow.Scan scan,
        Map<String, Object> facet,
        List<DspmObject> objects,
        long objectTotal,
        boolean objectsTruncated,
        List<EntityCount> entityBreakdown,
        boolean breakdownPartial
) {

    /**
     * A detected entity type and how many instances were found.
     *
     * <p>Aggregated from {@code objects[].findings[]}, which means it is bounded by the same 50-object
     * cap as {@link DspmAssetDetail#objects}. The vendor console shows true per-asset totals (738
     * PERSON NAME across 4,272 objects on one live bucket) because it is not reading this API; the
     * All-Assets API exposes no per-asset entity rollup, so anything computed here from a capped array
     * is a lower bound whenever {@link DspmAssetDetail#breakdownPartial} is set.
     *
     * <p>Surfaced rather than suppressed because the ranking is still useful — which detectors fired,
     * and roughly in what proportion — but the UI has to state that it is partial. The alternative,
     * printing a confident 40 where the console says 738, is worse than printing nothing.
     *
     * @param entityType    e.g. {@code PERSON NAME}, {@code ACCESS KEY AWS}.
     * @param category      raw category code, e.g. {@code PII}.
     * @param categoryLabel console wording, e.g. {@code Personal}.
     * @param count         instances found across the returned objects.
     */
    public record EntityCount(String entityType, String category, String categoryLabel, long count) {
    }
}
