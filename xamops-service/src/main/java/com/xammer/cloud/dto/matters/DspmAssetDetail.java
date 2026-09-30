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
 * @param entityBreakdown    every entity type detected in the asset, with counts aggregated across the
 *                           returned objects. See {@link EntityCount} for why the counts can undercount
 *                           while the list itself does not.
 * @param breakdownPartial   true when some sensitive objects fell outside the returned list, meaning
 *                           {@link #entityBreakdown} counts are lower bounds rather than totals. False
 *                           when the list was cut only after the last sensitive object (it is ordered
 *                           most sensitive first), even though {@link #objectsTruncated} is true.
 * @param duplicateObjects   listings removed from {@link #objects} because they repeat an earlier object
 *                           (same type, path and name). The endpoint agent reports some files more than
 *                           once, and {@link #objectTotal} still counts every listing, so when this is
 *                           non-zero the total overstates the distinct files.
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
        boolean breakdownPartial,
        int duplicateObjects
) {

    /**
     * A detected entity type and how many instances were found.
     *
     * <p>Counts are aggregated from {@code objects[].findings[]}, which means they are bounded by the
     * same 50-object cap as {@link DspmAssetDetail#objects}. The vendor console shows true per-asset
     * totals (738 PERSON NAME across 4,272 objects on one live bucket) because it is not reading this
     * API; the All-Assets API exposes no per-asset entity rollup, so a count computed here from a capped
     * array is a lower bound whenever {@link DspmAssetDetail#breakdownPartial} is set.
     *
     * <p><b>The list of entities, by contrast, is complete</b> even when the counts are not. The 50
     * returned objects are the most sensitive ones, and on a truncated asset they routinely miss whole
     * detectors — the same bucket's sample holds only PERSON NAME and ADDRESS while the console lists
     * five. The missing ones are recovered from a filter call scoped to the single asset, whose
     * {@code data_element} facet names every detector that fired anywhere in it. Those arrive with
     * {@link #sampled} false: detected, but with no count available.
     *
     * @param entityType    e.g. {@code PERSON NAME}, {@code ACCESS KEY AWS}.
     * @param category      raw category code, e.g. {@code PII}. Null when the entity was only seen in
     *                      the facet and its detector is not one whose category has been observed.
     * @param categoryLabel console wording, e.g. {@code Personal}.
     * @param count         instances found across the returned objects. Zero when {@link #sampled} is
     *                      false — which means "not counted", never "none found".
     * @param fileCount     returned objects containing at least one instance. A lower bound on the
     *                      asset's true figure under the same conditions as {@link #count}.
     * @param sampled       whether the entity appears in the returned objects at all. False for an
     *                      entity known only from the asset's facet.
     */
    public record EntityCount(String entityType, String category, String categoryLabel,
                              long count, int fileCount, boolean sampled) {
    }
}
