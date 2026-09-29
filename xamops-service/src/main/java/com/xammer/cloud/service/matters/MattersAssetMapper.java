package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.DspmAssetDetail;
import com.xammer.cloud.dto.matters.DspmAssetRow;
import com.xammer.cloud.dto.matters.DspmFilters;
import com.xammer.cloud.dto.matters.DspmObject;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.DspmSummary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates Matters' JSON into the DSPM DTOs.
 *
 * <p>Kept separate from {@link MattersDspmService} so the vendor-shape knowledge lives in one reviewable
 * place: every documented/actual discrepancy found against the live API is handled here, and nothing
 * downstream needs to know the vendor's field names or its null conventions.
 *
 * <p>Everything reads defensively — {@code path()} rather than {@code get()}, and explicit null checks
 * where a field is genuinely optional. The docs describe the enums as open sets and the live response
 * already carries fields the docs omit, so a mapper that assumes presence would break on the next
 * vendor addition rather than ignoring it.
 */
@Component
public class MattersAssetMapper {

    /** The undocumented pseudo-dimension in {@code /assets/filters}; lifted into {@code hasMore}. */
    private static final String PAGING_KEY = "paging";

    // ── list ─────────────────────────────────────────────────────────────────────────────────────

    public DspmPage<DspmAssetRow> toPage(JsonNode body) {
        List<DspmAssetRow> rows = new ArrayList<>();
        for (JsonNode node : body.path("data")) {
            rows.add(toRow(node));
        }
        JsonNode pagination = body.path("pagination");
        return new DspmPage<>(
                rows,
                pagination.path("page").asInt(1),
                pagination.path("page_size").asInt(rows.size()),
                pagination.path("total").asLong(0L),
                pagination.path("total_pages").asInt(0));
    }

    public DspmAssetRow toRow(JsonNode node) {
        String scanStatus = text(node.path("scan_status"), "status");
        String subtype = text(node, "subtype");
        return new DspmAssetRow(
                text(node, "id"),
                text(node, "name"),
                subtype,
                DspmLabels.prettify(subtype),
                text(node, "category"),
                text(node, "resource_type"),
                // Undocumented but present on list rows, which is what lets the table show an ARN or an
                // email without a detail fetch per row.
                text(node, "source_id"),
                text(node.path("lifecycle"), "status"),
                toRisk(node.path("risk")),
                toSensitives(node.path("sensitives"), scanStatus),
                toSource(node.path("source")),
                toScan(node.path("scan_status")));
    }

    private DspmAssetRow.Risk toRisk(JsonNode risk) {
        // Folded to the NONE sentinel so a row's severity matches the value the filter dimension uses.
        // The vendor reports null here but "NONE" in /assets/filters for the same state.
        String level = DspmLabels.normaliseSeverity(text(risk, "level"));
        return new DspmAssetRow.Risk(
                risk.hasNonNull("score") ? risk.get("score").asDouble() : null,
                level,
                risk.hasNonNull("policy_violation_count")
                        ? risk.get("policy_violation_count").asInt() : null,
                DspmLabels.isRealSeverity(level));
    }

    private DspmAssetRow.Sensitives toSensitives(JsonNode sensitives, String scanStatus) {
        List<String> types = new ArrayList<>();
        for (JsonNode type : sensitives.path("types")) {
            String value = type.asText(null);
            if (value != null && !value.isBlank()) {
                types.add(value);
            }
        }
        List<String> labels = types.stream().map(DspmLabels::sensitiveCategoryLabel).toList();
        return new DspmAssetRow.Sensitives(
                sensitives.path("record_count").asLong(0L),
                // The vendor's total object count, not the sensitive subset. Passed through under a name
                // that says so.
                sensitives.path("object_count").asLong(0L),
                types,
                labels,
                text(sensitives, "level"),
                // Computed once, here, so no consumer has to re-derive "is this zero meaningful".
                DspmLabels.sensitivesKnown(scanStatus));
    }

    private DspmAssetRow.Source toSource(JsonNode source) {
        String provider = text(source, "provider");
        return new DspmAssetRow.Source(
                provider,
                DspmLabels.prettify(provider),
                // The Matters integration id. The console shows a friendly name for this; the API does
                // not expose the mapping, so the id is all we have.
                text(source, "account"),
                text(source, "region"));
    }

    private DspmAssetRow.Scan toScan(JsonNode scan) {
        String status = text(scan, "status");
        return new DspmAssetRow.Scan(
                status,
                DspmLabels.scanStatusLabel(status),
                text(scan, "stage"),
                scan.hasNonNull("last_scanned_at") ? scan.get("last_scanned_at").asLong() : null);
    }

    // ── detail ───────────────────────────────────────────────────────────────────────────────────

    public DspmAssetDetail toDetail(JsonNode node) {
        String scanStatus = text(node.path("scan_status"), "status");
        String subtype = text(node, "subtype");

        List<DspmObject> objects = new ArrayList<>();
        for (JsonNode objectNode : node.path("objects")) {
            objects.add(toObject(objectNode));
        }

        long objectTotal = node.path("object_total").asLong(objects.size());
        boolean truncated = objects.size() < objectTotal;

        return new DspmAssetDetail(
                text(node, "id"),
                text(node, "name"),
                subtype,
                DspmLabels.prettify(subtype),
                text(node, "category"),
                text(node, "resource_type"),
                text(node, "external_id"),
                text(node.path("lifecycle"), "status"),
                toRisk(node.path("risk")),
                toSensitives(node.path("sensitives"), scanStatus),
                toSource(node.path("source")),
                toScan(node.path("scan_status")),
                toFacet(node.path("facet")),
                objects,
                objectTotal,
                truncated,
                aggregateEntities(objects),
                // The breakdown is only ever as complete as the object array it was built from, and the
                // vendor caps that at 50 with no way to page further in v1.
                truncated);
    }

    /**
     * Passes {@code facet} through as a plain map.
     *
     * Untyped on purpose — see {@link DspmAssetDetail#facet()}. Keys with no value are omitted upstream,
     * so absent means absent; the UI drives rendering off what is present rather than off a fixed
     * schema. Ordering is preserved so the vendor's own field order survives to the screen.
     */
    private Map<String, Object> toFacet(JsonNode facet) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (facet == null || facet.isMissingNode() || !facet.isObject()) {
            return out;
        }
        facet.fields().forEachRemaining(entry -> out.put(entry.getKey(), scalar(entry.getValue())));
        return out;
    }

    /** Converts a facet value to a plain Java type, keeping arrays (e.g. the endpoint's device_users). */
    private Object scalar(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isArray()) {
            List<Object> items = new ArrayList<>();
            value.forEach(item -> items.add(scalar(item)));
            return items;
        }
        return value.asText();
    }

    private DspmObject toObject(JsonNode node) {
        List<DspmObject.Finding> findings = new ArrayList<>();
        for (JsonNode finding : node.path("findings")) {
            String category = text(finding, "category");
            findings.add(new DspmObject.Finding(
                    text(finding, "entity_type"),
                    category,
                    DspmLabels.sensitiveCategoryLabel(category),
                    finding.path("count").asLong(0L)));
        }
        return new DspmObject(
                text(node, "id"),
                text(node, "name"),
                text(node, "object_type"),
                text(node, "source"),
                text(node, "path"),
                text(node, "extension"),
                node.hasNonNull("size_bytes") ? node.get("size_bytes").asLong() : null,
                node.path("is_sensitive").asBoolean(false),
                node.path("sensitive_count").asLong(0L),
                findings);
    }

    /**
     * Aggregates findings by entity type across the returned objects, highest count first.
     *
     * The All-Assets API exposes no per-asset entity rollup, so this is the only way to produce the
     * breakdown the vendor console shows — and it inherits the 50-object cap, which is why
     * {@link DspmAssetDetail#breakdownPartial()} exists to say so.
     *
     * Each entity also carries how many of the returned objects contain it, which is the "Total Files"
     * figure the console's entity view leads with. Counted per object rather than per finding, because
     * one file can report the same detector more than once.
     */
    private List<DspmAssetDetail.EntityCount> aggregateEntities(List<DspmObject> objects) {
        Map<String, long[]> totals = new LinkedHashMap<>();
        Map<String, int[]> files = new LinkedHashMap<>();
        Map<String, String> categories = new LinkedHashMap<>();

        for (DspmObject object : objects) {
            Set<String> seenInObject = new HashSet<>();
            for (DspmObject.Finding finding : object.findings()) {
                String entity = finding.entityType();
                if (entity == null || entity.isBlank()) {
                    continue;
                }
                totals.computeIfAbsent(entity, k -> new long[1])[0] += finding.count();
                if (seenInObject.add(entity)) {
                    files.computeIfAbsent(entity, k -> new int[1])[0]++;
                }
                categories.putIfAbsent(entity, finding.category());
            }
        }

        List<DspmAssetDetail.EntityCount> out = new ArrayList<>();
        totals.forEach((entity, sum) -> {
            String category = categories.get(entity);
            out.add(new DspmAssetDetail.EntityCount(
                    entity, category, DspmLabels.sensitiveCategoryLabel(category), sum[0],
                    files.get(entity)[0], true));
        });
        out.sort(Comparator.comparingLong(DspmAssetDetail.EntityCount::count).reversed());
        return out;
    }

    /**
     * Adds the entities the asset's facet names but its 50 sampled objects never mention.
     *
     * <p>The sample is the 50 <em>most sensitive</em> objects, which on a large asset is dominated by
     * whichever detector fires most often. On the live {@code xamops-document-bucket} all 50 are CSVs
     * full of person names: the sample yields PERSON NAME and ADDRESS, while the console lists five
     * entities including a single ACCESS KEY AWS. Dropping that one would hide the only credential in the
     * bucket, which is the finding a DSPM customer most needs to see.
     *
     * <p>{@code elements} comes from {@code /assets/filters} scoped to this one asset, so its
     * {@code data_element} dimension is exactly the set of detectors that fired anywhere in it —
     * verified against every truncated asset in the test project: the sampled set was a subset of the
     * facet set in all 17, never the other way round.
     *
     * <p>Additions are appended after the counted entities with {@code sampled=false} and a zero count,
     * which the UI renders as "detected, not counted" rather than as zero. The detail is returned
     * unchanged when nothing is missing, or when the facet could not be read.
     *
     * @param elements facet values for the asset; blank values (the vendor emits one with a non-zero
     *                 count) are ignored, since there is no entity to name.
     */
    public DspmAssetDetail withDetectedEntities(DspmAssetDetail detail, List<String> elements) {
        if (elements == null || elements.isEmpty()) {
            return detail;
        }

        Set<String> present = new HashSet<>();
        for (DspmAssetDetail.EntityCount entity : detail.entityBreakdown()) {
            present.add(entity.entityType());
        }

        List<DspmAssetDetail.EntityCount> merged = new ArrayList<>(detail.entityBreakdown());
        boolean added = false;
        for (String element : elements) {
            if (element == null || element.isBlank() || !present.add(element)) {
                continue;
            }
            String category = DspmLabels.entityCategory(element);
            merged.add(new DspmAssetDetail.EntityCount(
                    element, category, DspmLabels.sensitiveCategoryLabel(category), 0L, 0, false));
            added = true;
        }
        if (!added) {
            return detail;
        }

        return new DspmAssetDetail(
                detail.id(), detail.name(), detail.subtype(), detail.subtypeLabel(), detail.category(),
                detail.resourceType(), detail.externalId(), detail.lifecycle(), detail.risk(),
                detail.sensitives(), detail.source(), detail.scan(), detail.facet(), detail.objects(),
                detail.objectTotal(), detail.objectsTruncated(), merged, detail.breakdownPartial());
    }

    // ── filters ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Maps the filter response, lifting the undocumented {@code paging} object out of the dimension map.
     *
     * {@code paging} sits alongside the real dimensions but is an object of
     * {@code {cursor, has_more}} rather than an array of values, so anything iterating the response as
     * "dimension → options" has to exclude it explicitly or it becomes an empty pseudo-dimension in the
     * rail.
     */
    public DspmFilters toFilters(JsonNode body) {
        Map<String, List<DspmFilters.Option>> dimensions = new LinkedHashMap<>();
        Map<String, Boolean> hasMore = new LinkedHashMap<>();

        body.fields().forEachRemaining(entry -> {
            String dimension = entry.getKey();
            JsonNode value = entry.getValue();

            if (PAGING_KEY.equals(dimension)) {
                value.fields().forEachRemaining(paging ->
                        hasMore.put(paging.getKey(), paging.getValue().path("has_more").asBoolean(false)));
                return;
            }
            if (!value.isArray()) {
                return;
            }

            List<DspmFilters.Option> options = new ArrayList<>();
            for (JsonNode option : value) {
                // asText("") not asText(null): the data_element dimension really does return an
                // empty-string value with a non-zero count, and it should render as a named option
                // rather than a blank row.
                String raw = option.path("value").asText("");
                options.add(new DspmFilters.Option(
                        raw,
                        DspmLabels.filterOptionLabel(dimension, raw),
                        option.path("count").asLong(0L)));
            }
            dimensions.put(dimension, options);
        });

        return new DspmFilters(dimensions, hasMore);
    }

    // ── summary ──────────────────────────────────────────────────────────────────────────────────

    public DspmSummary toSummary(JsonNode body) {
        JsonNode cards = body.path("cards");

        Map<String, Long> byStatus = new LinkedHashMap<>();
        cards.path("by_status").fields().forEachRemaining(entry ->
                byStatus.put(entry.getKey(), entry.getValue().asLong(0L)));

        Map<String, DspmSummary.Tab> tabs = new LinkedHashMap<>();
        body.path("tabs").fields().forEachRemaining(entry -> {
            JsonNode tab = entry.getValue();
            tabs.put(entry.getKey(), new DspmSummary.Tab(
                    tab.path("value").asLong(0L),
                    toTrend(tab.path("trend")),
                    // Per-tab metrics are undocumented but present, which is what makes the category
                    // strip free rather than one request per category.
                    tab.path("critical_high").path("value").asLong(0L),
                    tab.path("total_sensitive_records").path("value").asLong(0L)));
        });

        return new DspmSummary(
                toMetric(cards.path("total_assets")),
                toMetric(cards.path("critical_high")),
                toMetric(cards.path("total_sensitive_records")),
                byStatus,
                tabs);
    }

    private DspmSummary.Metric toMetric(JsonNode node) {
        return new DspmSummary.Metric(node.path("value").asLong(0L), toTrend(node.path("trend")));
    }

    /**
     * Maps a trend array, which may legitimately be empty.
     *
     * An empty array means no snapshot history exists, not a history of zeroes — the distinction the UI
     * has to honour so a new project shows "no history yet" rather than a flat line implying nothing was
     * ever found.
     */
    private List<DspmSummary.Point> toTrend(JsonNode trend) {
        List<DspmSummary.Point> points = new ArrayList<>();
        for (JsonNode point : trend) {
            points.add(new DspmSummary.Point(
                    text(point, "date"),
                    point.path("value").asLong(0L)));
        }
        return points;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Null-safe text read. Returns null for a missing or JSON-null field rather than "null". */
    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return (value == null || value.isEmpty()) ? null : value;
    }
}
