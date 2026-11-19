package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.EndpointFilePage;
import com.xammer.cloud.dto.matters.datastores.EndpointMetric;
import com.xammer.cloud.dto.matters.datastores.EndpointOccurrencePage;
import com.xammer.cloud.dto.matters.datastores.EndpointOverview;
import com.xammer.cloud.dto.matters.datastores.EndpointScanPage;
import com.xammer.cloud.dto.matters.datastores.EndpointSensitiveMetrics;
import com.xammer.cloud.dto.matters.datastores.EndpointSummary;
import com.xammer.cloud.dto.matters.datastores.EndpointSummarySnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static com.xammer.cloud.service.matters.DatastoreRowMapper.longOr;
import static com.xammer.cloud.service.matters.DatastoreRowMapper.longOrNull;

/**
 * Field mapping for the Endpoint drawer's {@code /api/v1/endpoint-agent/agent/{id}/…} routes (design
 * 5.6.2). Every DTO is a whitelist; reads are defensive. Samples are read in one place only,
 * {@link #toOccurrences}, whose page is never cached (design 5.6.3).
 */
@Component
public class EndpointDrawerMapper {

    public static final String KIND_ENTITY = "ENTITY";
    public static final String KIND_DOCUMENT_CLASS = "DOCUMENT_CLASS";

    static final int MAX_USERS = 50;
    static final int MAX_USER_LENGTH = 256;
    static final int MAX_FILTER_OPTIONS = 500;
    static final int MAX_EXTENSIONS = 300;
    static final int MAX_GROUP_ENTRIES = 200;
    static final int MAX_METRICS = 1_000;
    static final int MAX_ROWS = 100;
    static final int MAX_TAGS = 20;
    static final int MAX_TAG_LENGTH = 128;
    static final int MAX_SAMPLES = 20;

    /** The vendor's placeholder category and class for structured files, shown as no value (plan C6). */
    static final String NOT_SUPPORTED_DOCUMENT_CLASS = "Doc Cls Not Supported for Structured Data";
    /** The vendor's catch-all category and class, shown as no value in the endpoint file tables (plan item 3). */
    private static final String OTHER = "Other";

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * {@code …/overview}. {@code id}, {@code os} and {@code os_type} (the header icon comes from the list
     * row), {@code user}, {@code email_id}, {@code labels}, {@code count_of_data_at_risk}, {@code local_ip}
     * and {@code category_subcategory_counts} are not read.
     */
    public EndpointOverview toOverview(JsonNode body) {
        JsonNode data = body.path("data");
        String serialNumber = DrawerJson.string(data, "device_serial_number");
        if (serialNumber == null) {
            serialNumber = DrawerJson.string(data, "device_number");
        }
        Long lastAccessAt = DrawerJson.epochSeconds(data.path("last_active"));
        if (lastAccessAt == null) {
            lastAccessAt = DrawerJson.epochSeconds(data.path("last_ping_time"));
        }
        String sensitivity = upper(DrawerJson.string(data, "sensitivity"));
        String agentStatus = upper(DrawerJson.string(data, "agent_status"));
        return new EndpointOverview(serialNumber,
                DrawerJson.epochSeconds(data.path("last_scan_time")),
                lastAccessAt,
                DatastoreLabels.riskLevelLabel(sensitivity),
                DatastoreLabels.endpointAgentStatusLabel(agentStatus),
                DatastoreLabels.endpointAgentOnline(agentStatus),
                DrawerJson.strings(data.path("users"), MAX_USERS, MAX_USER_LENGTH),
                DrawerJson.string(data, "hostname"),
                DrawerJson.string(data, "public_ip"),
                DrawerJson.string(data, "mac_address"),
                DatastoreLabels.deviceTypeLabel(DrawerJson.string(data, "device_type")),
                DrawerJson.string(data, "os_version"),
                DrawerJson.string(data, "architecture"),
                DrawerJson.string(data, "memory"),
                finite(data.path("cpu_usage")),
                finite(data.path("memory_usage")),
                DrawerJson.string(data, "agent_version"));
    }

    /**
     * {@code …/filter-options} as build 1's {@link DatastoreFilterOptions}, so {@code FilterDropdown}
     * reads it unchanged: dimensions {@code sensitives}, {@code documentClass}, {@code category},
     * {@code dataElement}, text values in API order, every count null and hasMore false.
     */
    public DatastoreFilterOptions toFilterOptions(JsonNode body) {
        JsonNode data = body.path("data");
        return new DatastoreFilterOptions(List.of(
                dimension("sensitives", data.path("sensitives"), DatastoreLabels::sensitiveCategoryLabel),
                dimension("documentClass", data.path("subcategories"), value -> value),
                dimension("category", data.path("categories"), DatastoreLabels::documentCategoryLabel),
                dimension("dataElement", data.path("dataElements"), value -> value)));
    }

    private static DatastoreFilterOptions.Dimension dimension(String key, JsonNode values,
                                                              UnaryOperator<String> label) {
        List<DatastoreFilterOptions.Option> options = new ArrayList<>();
        for (JsonNode element : items(values)) {
            if (options.size() >= MAX_FILTER_OPTIONS) {
                break;
            }
            String value = DrawerJson.stringValue(element);
            if (value != null) {
                options.add(new DatastoreFilterOptions.Option(value, label.apply(value), null));
            }
        }
        return new DatastoreFilterOptions.Dimension(key, options, false);
    }

    /**
     * {@code …/sensitive-files} with no filter: the Information totals. Extensions by count descending,
     * then key; label groups and file categories in API order.
     */
    public EndpointSummary toSummary(JsonNode body) {
        JsonNode data = body.path("data");

        List<EndpointSummary.ExtensionCount> extensions = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> extensionCounts = data.path("extension_counts").fields();
        while (extensionCounts.hasNext()) {
            Map.Entry<String, JsonNode> entry = extensionCounts.next();
            if (entry.getValue().isNumber()) {
                extensions.add(new EndpointSummary.ExtensionCount(entry.getKey(),
                        DatastoreLabels.extensionLabel(entry.getKey()), entry.getValue().asLong()));
            }
        }
        extensions.sort(Comparator.comparingLong(EndpointSummary.ExtensionCount::count).reversed()
                .thenComparing(EndpointSummary.ExtensionCount::extension));

        List<EndpointSummary.LabelGroup> labelGroups = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> labels = data.path("label_wise_sensitive_entities").fields();
        while (labels.hasNext()) {
            Map.Entry<String, JsonNode> entry = labels.next();
            List<EndpointSummary.EntityCount> entities = new ArrayList<>();
            for (JsonNode entity : items(entry.getValue())) {
                if (entities.size() >= MAX_GROUP_ENTRIES) {
                    break;
                }
                entities.add(new EndpointSummary.EntityCount(DrawerJson.string(entity, "entity_name"),
                        longOr(entity, "count", 0L)));
            }
            labelGroups.add(new EndpointSummary.LabelGroup(
                    DatastoreLabels.sensitiveCategoryLabel(entry.getKey()), entities));
        }

        List<EndpointSummary.CategoryGroup> fileCategories = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> categories = data.path("category_subcategory_counts").fields();
        while (categories.hasNext()) {
            Map.Entry<String, JsonNode> entry = categories.next();
            List<EndpointSummary.EntityCount> documentClasses = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> classes = entry.getValue().fields();
            while (classes.hasNext() && documentClasses.size() < MAX_GROUP_ENTRIES) {
                Map.Entry<String, JsonNode> documentClass = classes.next();
                JsonNode count = documentClass.getValue();
                documentClasses.add(new EndpointSummary.EntityCount(documentClass.getKey(),
                        count.isNumber() ? count.asLong() : 0L));
            }
            fileCategories.add(new EndpointSummary.CategoryGroup(
                    DatastoreLabels.documentCategoryLabel(entry.getKey()), documentClasses));
        }

        return new EndpointSummary(longOrNull(data, "total_files"), cap(extensions, MAX_EXTENSIONS),
                longOrNull(data, "total_sensitives_count"), labelGroups, fileCategories);
    }

    /**
     * What {@code dsepsum} caches: the summary, and the names of the ENTITY metrics of the same unfiltered
     * call, in API order, behind the occurrences' {@code entityName} check.
     */
    public EndpointSummarySnapshot toSummarySnapshot(JsonNode body) {
        List<String> entityNames = new ArrayList<>();
        for (EndpointMetric metric : metrics(body)) {
            if (KIND_ENTITY.equals(metric.kind())) {
                entityNames.add(metric.name());
            }
        }
        return new EndpointSummarySnapshot(toSummary(body), entityNames);
    }

    /**
     * {@code …/sensitive-files} {@code unified_metrics[]}: an item with an entity name is an ENTITY row,
     * else one with a subcategory is a DOCUMENT_CLASS row; others are dropped. Count descending; the sort
     * is stable, so ties keep API order.
     */
    public EndpointSensitiveMetrics toMetrics(JsonNode body) {
        List<EndpointMetric> metrics = metrics(body);
        metrics.sort(Comparator.comparingLong(EndpointMetric::count).reversed());
        return new EndpointSensitiveMetrics(metrics);
    }

    /** The metrics in API order. */
    private static List<EndpointMetric> metrics(JsonNode body) {
        List<EndpointMetric> metrics = new ArrayList<>();
        for (JsonNode item : items(body.path("data").path("unified_metrics"))) {
            if (metrics.size() >= MAX_METRICS) {
                break;
            }
            long count = longOr(item, "count", 0L);
            String entityName = DrawerJson.string(item, "entity_name");
            if (entityName != null) {
                metrics.add(new EndpointMetric(KIND_ENTITY, entityName,
                        DatastoreLabels.sensitiveCategoryLabel(DrawerJson.string(item, "label")), count));
                continue;
            }
            String subcategory = DrawerJson.string(item, "subcategory");
            if (subcategory != null) {
                metrics.add(new EndpointMetric(KIND_DOCUMENT_CLASS, subcategory,
                        DatastoreLabels.documentCategoryLabel(DrawerJson.string(item, "category")), count));
            }
        }
        return metrics;
    }

    /**
     * {@code …/entity}: one page of an entity's occurrences. The samples are the {@code match_text} of
     * every {@code sampleMatches[]} item named {@code entityName}, read with {@code asText()} so they are
     * never trimmed and a numeric one is kept (D2); null, missing and container values are skipped. The
     * only place a sample is read.
     */
    public EndpointOccurrencePage toOccurrences(JsonNode body, String entityName, int skip, int limit) {
        List<EndpointOccurrencePage.Occurrence> rows = new ArrayList<>();
        for (JsonNode item : items(body.path("data"))) {
            if (rows.size() >= MAX_ROWS) {
                break;
            }
            List<String> samples = new ArrayList<>();
            for (JsonNode match : items(item.path("sampleMatches"))) {
                if (!entityName.equals(DrawerJson.string(match, "name"))) {
                    continue;
                }
                for (JsonNode sample : items(match.path("samples"))) {
                    if (samples.size() >= MAX_SAMPLES) {
                        break;
                    }
                    JsonNode text = sample.path("match_text");
                    if (text.isValueNode() && !text.isNull()) {
                        samples.add(text.asText());
                    }
                }
            }
            JsonNode falsePositive = item.path("isFalsePositive");
            rows.add(new EndpointOccurrencePage.Occurrence(DrawerJson.string(item, "name"),
                    DrawerJson.string(item, "path"), DrawerJson.strings(item.path("tags"), MAX_TAGS, MAX_TAG_LENGTH),
                    falsePositive.isBoolean() && falsePositive.booleanValue(), samples));
        }
        return new EndpointOccurrencePage(entityName, total(body, "total", skip, rows.size()), skip, limit, rows);
    }

    /**
     * {@code …/files} and {@code /agent/files}: one page of the File View, a document class's files or the
     * Files page Endpoint tab. Category and document class in title case, the File View's spelling (GAP-51);
     * "Other" and the structured-data placeholder read as null. {@code id} is kept when it is a UUID, for the
     * Endpoint file drawer; a non-positive {@code fileModifiedTime} (Go's zero time, G5) is null; {@code updatedAt}
     * is the Last Scanned time. {@code sampleMatches}, {@code sensitiveEntities}, {@code dataHash} and the other
     * timestamps are never read.
     */
    public EndpointFilePage toFilePage(JsonNode body, int skip, int limit) {
        List<EndpointFilePage.File> rows = new ArrayList<>();
        for (JsonNode item : items(body.path("data"))) {
            if (rows.size() >= MAX_ROWS) {
                break;
            }
            List<String> labels = new ArrayList<>();
            for (JsonNode label : items(item.path("labels"))) {
                String code = DrawerJson.stringValue(label);
                if (code != null) {
                    labels.add(DatastoreLabels.sensitiveCategoryLabel(code));
                }
            }
            String id = DrawerJson.string(item, "id");
            rows.add(new EndpointFilePage.File(id != null && UUID.matcher(id).matches() ? lower(id) : null,
                    DrawerJson.string(item, "name"),
                    lower(DrawerJson.string(item, "extension")),
                    DrawerJson.string(item, "path"),
                    DrawerJson.string(item, "currentUser"),
                    longOrNull(item, "size"),
                    longOr(item, "sensitiveEntitiesCount", 0L),
                    labels,
                    DatastoreLabels.titleCase(shownCategory(DrawerJson.string(item, "category"))),
                    DatastoreLabels.titleCase(shownCategory(DrawerJson.string(item, "subcategory"))),
                    DrawerJson.strings(item.path("tags"), MAX_TAGS, MAX_TAG_LENGTH),
                    positive(DrawerJson.epochSeconds(item.path("fileModifiedTime"))),
                    positive(DrawerJson.epochSeconds(item.path("updatedAt")))));
        }
        return new EndpointFilePage(rows, skip, limit, total(body, "total", skip, rows.size()));
    }

    /** {@code …/scans}, in API order. A negative {@code total_files} becomes null (GAP-50). */
    public EndpointScanPage toScanPage(JsonNode body, int skip, int limit) {
        List<EndpointScanPage.Scan> rows = new ArrayList<>();
        for (JsonNode item : items(body.path("data"))) {
            if (rows.size() >= MAX_ROWS) {
                break;
            }
            String status = upper(DrawerJson.string(item, "status"));
            Long totalFiles = longOrNull(item, "total_files");
            rows.add(new EndpointScanPage.Scan(DrawerJson.epochSeconds(item.path("start_time")),
                    DrawerJson.epochSeconds(item.path("end_time")),
                    status, DatastoreLabels.endpointScanStatusLabel(status),
                    longOrNull(item, "sensitive_files"),
                    totalFiles != null && totalFiles >= 0 ? totalFiles : null));
        }
        return new EndpointScanPage(rows, skip, limit, total(body, "count", skip, rows.size()));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Null for "Other" and the structured-data placeholder, which the file tables show as "-". */
    private static String shownCategory(String value) {
        return OTHER.equals(value) || NOT_SUPPORTED_DOCUMENT_CLASS.equals(value) ? null : value;
    }

    /** Null unless positive: the vendor's zero time and a missing time look the same. */
    private static Long positive(Long epochSeconds) {
        return epochSeconds != null && epochSeconds > 0 ? epochSeconds : null;
    }

    /** The elements of an array; nothing for any other node. */
    private static Iterable<JsonNode> items(JsonNode array) {
        return array.isArray() ? array : List.of();
    }

    /** {@code field} when numeric, else skip + rows. */
    private static long total(JsonNode body, String field, int skip, int rows) {
        Long total = longOrNull(body, field);
        return total != null ? total : (long) skip + rows;
    }

    private static Double finite(JsonNode value) {
        return value.isNumber() && Double.isFinite(value.asDouble()) ? value.asDouble() : null;
    }

    private static <T> List<T> cap(List<T> list, int max) {
        return list.size() <= max ? list : new ArrayList<>(list.subList(0, max));
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
