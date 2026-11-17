package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.DspmSummary;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.DatastoreKpis;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.datastores.DatastoreSummarySnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Datastores list rows, facets, summary and KPIs from {@code /api/v1/assets}, {@code /assets/filters}
 * and {@code /assets/summary} (design 5.6.1 to 5.6.3).
 *
 * <p>Dependency-free and defensive, like {@link MattersAssetMapper}: {@code path()} rather than
 * {@code get()}, and a missing section yields an empty or null field, never an exception.
 */
@Component
public class DatastoreRowMapper {

    private static final String PAGING_KEY = "paging";
    private static final String ENDPOINT = "endpoint";

    /**
     * A mapped row and its integration id ({@code source.account}). The id stays server-side for the
     * account labeler and is never serialized on its own (plan P5).
     */
    public record MappedRow(DatastoreRow row, String integrationId) {
    }

    // ── rows ─────────────────────────────────────────────────────────────────────────────────────

    /** Every {@code data[]} row of an {@code /api/v1/assets} body, in API order. */
    public List<MappedRow> toMappedRows(JsonNode body) {
        List<MappedRow> rows = new ArrayList<>();
        for (JsonNode node : body.path("data")) {
            rows.add(new MappedRow(toRow(node), text(node.path("source"), "account")));
        }
        return rows;
    }

    private DatastoreRow toRow(JsonNode node) {
        String category = text(node, "category");
        String subtype = text(node, "subtype");
        JsonNode risk = node.path("risk");
        String level = text(risk, "level");
        level = level == null ? null : level.toUpperCase(Locale.ROOT);
        JsonNode source = node.path("source");
        String provider = text(source, "provider");
        JsonNode scan = node.path("scan_status");
        String status = text(scan, "status");

        List<String> typeLabels = new ArrayList<>();
        for (JsonNode type : node.path("sensitives").path("types")) {
            String label = DspmLabels.sensitiveCategoryLabel(type.isTextual() ? type.asText() : null);
            if (label != null) {
                typeLabels.add(label);
            }
        }

        DatastoreRow.Agent agent = null;
        if (ENDPOINT.equals(category)) {
            String agentStatus = text(node, "agent_status");
            agent = new DatastoreRow.Agent(text(node, "serial_number"), agentStatus,
                    DatastoreLabels.agentStatusLabel(agentStatus), text(node, "agent_version"));
        }

        return new DatastoreRow(
                text(node, "id"),
                text(node, "source_id"),
                text(node, "name"),
                category,
                subtype,
                DatastoreLabels.subtypeLabel(subtype),
                new DatastoreRow.Risk(number(risk, "score"), level, DatastoreLabels.riskLevelLabel(level)),
                new DatastoreRow.Sensitives(longOr(node.path("sensitives"), "record_count", 0L), typeLabels),
                new DatastoreRow.Source(provider, DatastoreLabels.providerLabel(provider), null,
                        text(source, "region")),
                new DatastoreRow.Scan(status, DatastoreLabels.scanStatusLabel(status), scanDisplayAt(status, scan)),
                agent);
    }

    /**
     * FR-TBL-7: which timestamp the Scan Status cell shows.
     * <ul>
     *   <li>CLASSIFIED: {@code last_scanned_at} or null; {@code status_at} is ignored.</li>
     *   <li>STOPPED, FAILED, NOT_SCANNED, SCANNING: {@code status_at} or null.</li>
     *   <li>anything else, or null: {@code last_scanned_at}, falling back to {@code status_at}.</li>
     * </ul>
     */
    public static Long scanDisplayAt(String status, JsonNode scanStatus) {
        Long lastScanned = longOrNull(scanStatus, "last_scanned_at");
        Long statusAt = longOrNull(scanStatus, "status_at");
        String key = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "CLASSIFIED" -> lastScanned;
            case "STOPPED", "FAILED", "NOT_SCANNED", "SCANNING" -> statusAt;
            default -> lastScanned != null ? lastScanned : statusAt;
        };
    }

    /**
     * A page built from the validated request values; only {@code pagination.total} is read from the
     * vendor, whose echoed paging is not trusted (FR-SEN-3).
     */
    public <T> DspmPage<T> toPage(List<T> rows, JsonNode body, int page, int pageSize) {
        long total = longOr(body.path("pagination"), "total", 0L);
        return new DspmPage<>(rows, page, pageSize, total, totalPages(total, pageSize));
    }

    static int totalPages(long total, int pageSize) {
        return total <= 0 || pageSize <= 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
    }

    // ── facets ───────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code /assets/filters} → options in vendor order. {@code paging} is lifted into {@code hasMore};
     * non-array fields and dimensions outside the tab's allow-list (which includes {@code lifecycle}
     * and {@code resource_type}) are dropped. {@code integration_id} labels are the raw id until the
     * account labeler runs.
     */
    public DatastoreFilterOptions toFacets(JsonNode body, DatastoreTab tab) {
        List<DatastoreFilterOptions.Dimension> dimensions = new ArrayList<>();
        JsonNode paging = body.path(PAGING_KEY);
        Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if (PAGING_KEY.equals(key) || !field.getValue().isArray()) {
                continue;
            }
            DatastoreFilterDimension dimension = DatastoreFilterDimension.fromKey(key);
            if (dimension == null || !dimension.allowedOn(tab)) {
                continue;
            }
            List<DatastoreFilterOptions.Option> options = new ArrayList<>();
            for (JsonNode option : field.getValue()) {
                String value = text(option, "value");
                value = value == null ? "" : value;
                options.add(new DatastoreFilterOptions.Option(value, DatastoreLabels.optionLabel(key, value),
                        longOrNull(option, "count")));
            }
            dimensions.add(new DatastoreFilterOptions.Dimension(key, options,
                    paging.path(key).path("has_more").asBoolean(false)));
        }
        return new DatastoreFilterOptions(dimensions);
    }

    // ── summary and KPIs ─────────────────────────────────────────────────────────────────────────

    /** {@code /assets/summary} → the parts the KPI cards use. Missing numbers are 0, missing trends empty. */
    public DatastoreSummarySnapshot toSummary(JsonNode body) {
        JsonNode cards = body.path("cards");
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> statuses = cards.path("by_status").fields();
        while (statuses.hasNext()) {
            Map.Entry<String, JsonNode> status = statuses.next();
            byStatus.put(status.getKey(), status.getValue().isNumber() ? status.getValue().asLong() : 0L);
        }
        Map<String, DatastoreSummarySnapshot.TabSnapshot> tabs = new LinkedHashMap<>();
        for (DatastoreTab tab : DatastoreTab.values()) {
            JsonNode node = body.path("tabs").path(tab.slug());
            tabs.put(tab.slug(), new DatastoreSummarySnapshot.TabSnapshot(
                    new DatastoreKpis.Metric(longOr(node, "value", 0L), trend(node.path("trend"))),
                    metric(node.path("total_sensitive_records")),
                    longOr(node.path("critical_high"), "value", 0L)));
        }
        return new DatastoreSummarySnapshot(byStatus, metric(cards.path("total_sensitive_records")),
                metric(cards.path("total_assets")), longOr(cards.path("critical_high"), "value", 0L), tabs);
    }

    /**
     * The KPI cards of one tab (design 5.6.3). All reads {@code cards}; other tabs read
     * {@code tabs.<t>}, and take their status counts from the tab's unfiltered {@code scan_status}
     * facet. A missing count is 0.
     *
     * @param facets the tab's unfiltered facets; ignored (and may be null) for {@link DatastoreTab#ALL}.
     */
    public DatastoreKpis toKpis(DatastoreTab tab, DatastoreSummarySnapshot summary, DatastoreFilterOptions facets) {
        Map<String, Long> tabCounts = new LinkedHashMap<>();
        for (DatastoreTab t : DatastoreTab.values()) {
            DatastoreSummarySnapshot.TabSnapshot snapshot = summary.tabs().get(t.slug());
            tabCounts.put(t.slug(), snapshot == null ? 0L : snapshot.assets().value());
        }
        if (tab == DatastoreTab.ALL) {
            Map<String, Long> byStatus = summary.byStatus();
            return new DatastoreKpis(tab.slug(), tabCounts, summary.totalSensitiveRecords(), summary.totalAssets(),
                    byStatus.getOrDefault("CLASSIFIED", 0L), byStatus.getOrDefault("SCHEDULED", 0L),
                    byStatus.getOrDefault("FAILED", 0L), byStatus.getOrDefault("STOPPED", 0L),
                    summary.criticalHigh());
        }
        DatastoreSummarySnapshot.TabSnapshot snapshot = summary.tabs().get(tab.slug());
        DatastoreKpis.Metric empty = new DatastoreKpis.Metric(0L, List.of());
        Map<String, Long> statusCounts = scanStatusCounts(facets);
        return new DatastoreKpis(tab.slug(), tabCounts,
                snapshot == null ? empty : snapshot.sensitiveRecords(),
                snapshot == null ? empty : snapshot.assets(),
                statusCounts.getOrDefault("CLASSIFIED", 0L), statusCounts.getOrDefault("SCHEDULED", 0L),
                statusCounts.getOrDefault("FAILED", 0L), statusCounts.getOrDefault("STOPPED", 0L),
                snapshot == null ? 0L : snapshot.criticalHigh());
    }

    private static Map<String, Long> scanStatusCounts(DatastoreFilterOptions facets) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (facets == null || facets.dimensions() == null) {
            return counts;
        }
        for (DatastoreFilterOptions.Dimension dimension : facets.dimensions()) {
            if (!DatastoreFilterDimension.SCAN_STATUS.key().equals(dimension.key())) {
                continue;
            }
            for (DatastoreFilterOptions.Option option : dimension.options()) {
                counts.merge(option.value(), option.count() == null ? 0L : option.count(), Long::sum);
            }
        }
        return counts;
    }

    private static DatastoreKpis.Metric metric(JsonNode node) {
        return new DatastoreKpis.Metric(longOr(node, "value", 0L), trend(node.path("trend")));
    }

    private static List<DspmSummary.Point> trend(JsonNode trend) {
        List<DspmSummary.Point> points = new ArrayList<>();
        for (JsonNode point : trend) {
            points.add(new DspmSummary.Point(text(point, "date"), longOr(point, "value", 0L)));
        }
        return points;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Text, or null for missing, JSON-null or empty. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String text = value.asText();
        return text.isEmpty() ? null : text;
    }

    static Double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    static long longOr(JsonNode node, String field, long fallback) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : fallback;
    }
}
