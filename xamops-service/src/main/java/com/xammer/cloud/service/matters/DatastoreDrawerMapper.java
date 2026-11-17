package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilePage;
import com.xammer.cloud.dto.matters.datastores.DatastoreOccurrencePage;
import com.xammer.cloud.dto.matters.datastores.DatastoreOverview;
import com.xammer.cloud.dto.matters.datastores.DatastoreRisks;
import com.xammer.cloud.dto.matters.datastores.DatastoreScanHistory;
import com.xammer.cloud.dto.matters.datastores.DatastoreScanProgress;
import com.xammer.cloud.dto.matters.datastores.DatastoreSensitives;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static com.xammer.cloud.service.matters.DatastoreRowMapper.longOr;
import static com.xammer.cloud.service.matters.DatastoreRowMapper.longOrNull;
import static com.xammer.cloud.service.matters.DatastoreRowMapper.text;
import static com.xammer.cloud.service.matters.DatastoreRowMapper.totalPages;

/**
 * The cloud / on-prem datastore drawer: overview, sensitives, file rows, occurrences, risks, scan history
 * and scan progress (design 5.6.4 to 5.6.7 and 5.6.9).
 *
 * <p>This is where the whitelist lives. The overview and sensitives routes carry raw samples and
 * connection config; only the fields named here leave the server, and only {@link #toOccurrences}
 * carries a sample (decision D1). Dependency-free and defensive, like {@link MattersAssetMapper}.
 */
@Component
public class DatastoreDrawerMapper {

    /** Most file rows kept per asset: a bound on the cached value, not expected on real data. */
    public static final int MAX_FILE_ROWS = 50_000;

    static final int MAX_URL_LENGTH = 2_048;
    static final int MAX_PROPERTY_TEXT_LENGTH = 2_048;

    private static final String REGION = "region";
    private static final String DATASTORE_SIZE = "datastore_size";

    /** Properties the console never shows. */
    private static final Set<String> HIDDEN_PROPERTIES = Set.of("account_name", "cost", "owner");

    /**
     * DA14, defence in depth: a property whose lower-cased key contains one of these is dropped. These
     * are the fields the vendor says it strips from {@code asset_details}, plus common secret names.
     */
    private static final List<String> CREDENTIAL_MARKERS = List.of("password", "secret", "token", "credential",
            "private_key", "access_key", "api_key", "cli_keys", "connection_uri", "connectionuri", "username");

    private static final Pattern DIGITS = Pattern.compile("^\\d{1,18}$");

    private static final Comparator<DatastoreOverview.FileType> BY_LABEL =
            Comparator.comparing(DatastoreOverview.FileType::label, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(DatastoreOverview.FileType::key);

    // ── overview ─────────────────────────────────────────────────────────────────────────────────

    /** {@code /api/v1/data/inventory/overview} → the whitelisted overview. */
    public DatastoreOverview toOverview(JsonNode body) {
        JsonNode details = body.path("inventories_details");
        String scanningStatus = text(details, "scanning_status");
        JsonNode canDiscover = details.path("canDiscoverSchema");
        return new DatastoreOverview(
                consoleUrl(text(details.path("asset_details"), "url")),
                scanningStatus,
                DatastoreLabels.overviewScanningStatusLabel(scanningStatus),
                canDiscover.isBoolean() && canDiscover.booleanValue(),
                fileSummary(details),
                sensitiveRecords(details.path("sensitive_records")),
                properties(details.path("properties")),
                securityConfigurations(details.path("security_configurations")));
    }

    /**
     * The console link: only an absolute https URI with a host, no user-info, and at most 2,048
     * characters. Anything else gives null, so the frontend never renders a link it should not.
     */
    static String consoleUrl(String raw) {
        if (raw == null || raw.length() > MAX_URL_LENGTH) {
            return null;
        }
        try {
            URI uri = new URI(raw);
            boolean ok = uri.isAbsolute()
                    && "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().isEmpty()
                    && uri.getRawUserInfo() == null;
            return ok ? raw : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * Null when {@code file_types} is not an object. Types: non-zero counts descending with ties by
     * label, then zero counts by label; "by label" is case-insensitive, then the key (A18).
     */
    private DatastoreOverview.FileSummary fileSummary(JsonNode details) {
        JsonNode fileTypes = details.path("file_types");
        if (!fileTypes.isObject()) {
            return null;
        }
        List<DatastoreOverview.FileType> nonZero = new ArrayList<>();
        List<DatastoreOverview.FileType> zero = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = fileTypes.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String label = DatastoreLabels.fileTypeLabel(field.getKey());
            long count = field.getValue().isNumber() ? field.getValue().asLong() : 0L;
            DatastoreOverview.FileType type =
                    new DatastoreOverview.FileType(field.getKey(), label == null ? field.getKey() : label, count);
            (count != 0 ? nonZero : zero).add(type);
        }
        nonZero.sort(Comparator.comparingLong(DatastoreOverview.FileType::count).reversed().thenComparing(BY_LABEL));
        zero.sort(BY_LABEL);
        List<DatastoreOverview.FileType> types = new ArrayList<>(nonZero);
        types.addAll(zero);
        return new DatastoreOverview.FileSummary(longOrNull(details, "total_files"), types);
    }

    private DatastoreOverview.SensitiveRecords sensitiveRecords(JsonNode records) {
        List<DatastoreOverview.LabelGroup> groups = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = records.path("label_wise_sensitive_records_count").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            List<DatastoreOverview.EntityCount> entities = new ArrayList<>();
            for (JsonNode entity : field.getValue()) {
                String name = text(entity, "entity_name");
                entities.add(new DatastoreOverview.EntityCount(name == null ? "" : name, longOr(entity, "count", 0L)));
            }
            groups.add(new DatastoreOverview.LabelGroup(field.getKey(),
                    DatastoreLabels.sensitiveCategoryLabel(field.getKey()), entities));
        }
        return new DatastoreOverview.SensitiveRecords(longOrNull(records, "sensitive_records_count"), groups);
    }

    /**
     * Region, then Datastore Size, then every other key in API order, except the hidden and
     * credential-shaped keys. Only scalars leave the server.
     */
    private List<DatastoreOverview.Property> properties(JsonNode properties) {
        List<DatastoreOverview.Property> out = new ArrayList<>();
        if (!properties.isObject()) {
            return out;
        }
        addProperty(out, REGION, properties.path(REGION));
        JsonNode size = properties.path(DATASTORE_SIZE);
        if (size.isNumber()) {
            out.add(new DatastoreOverview.Property(DATASTORE_SIZE, DatastoreLabels.propertyLabel(DATASTORE_SIZE),
                    null, size.asLong()));
        } else if (size.isTextual() && DIGITS.matcher(size.asText()).matches()) {
            out.add(new DatastoreOverview.Property(DATASTORE_SIZE, DatastoreLabels.propertyLabel(DATASTORE_SIZE),
                    null, Long.parseLong(size.asText())));
        } else {
            addProperty(out, DATASTORE_SIZE, size);
        }
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if (REGION.equals(key) || DATASTORE_SIZE.equals(key) || HIDDEN_PROPERTIES.contains(key)) {
                continue;
            }
            addProperty(out, key, field.getValue());
        }
        return out;
    }

    private static void addProperty(List<DatastoreOverview.Property> out, String key, JsonNode value) {
        if (isCredentialShaped(key)) {
            return;
        }
        String text = scalarText(value);
        if (text != null) {
            out.add(new DatastoreOverview.Property(key, DatastoreLabels.propertyLabel(key), text, null));
        }
    }

    /** Text 1..2,048 characters after trimming; integral numbers without separators; booleans Yes / No. */
    private static String scalarText(JsonNode value) {
        if (value.isTextual()) {
            String text = value.asText().trim();
            return text.isEmpty() || text.length() > MAX_PROPERTY_TEXT_LENGTH ? null : text;
        }
        if (value.isIntegralNumber()) {
            return String.valueOf(value.asLong());
        }
        if (value.isNumber()) {
            return value.asText();
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "Yes" : "No";
        }
        return null;
    }

    private static boolean isCredentialShaped(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return CREDENTIAL_MARKERS.stream().anyMatch(lower::contains);
    }

    /**
     * Null when not an object. Otherwise the five known keys in API order; strings prettified
     * ("SSE_S3" → "Sse S3"), booleans Enabled / Disabled; null, blank and non-scalars skipped.
     */
    private List<DatastoreOverview.SecurityConfiguration> securityConfigurations(JsonNode configurations) {
        if (!configurations.isObject()) {
            return null;
        }
        List<DatastoreOverview.SecurityConfiguration> out = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = configurations.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String label = DatastoreLabels.securityConfigurationLabel(field.getKey());
            if (label == null) {
                continue;
            }
            JsonNode value = field.getValue();
            String text = null;
            if (value.isTextual() && !value.asText().isBlank()) {
                text = DspmLabels.prettify(value.asText());
            } else if (value.isBoolean()) {
                text = value.booleanValue() ? "Enabled" : "Disabled";
            }
            if (text != null) {
                out.add(new DatastoreOverview.SecurityConfiguration(field.getKey(), label, text));
            }
        }
        return out;
    }

    // ── sensitives ───────────────────────────────────────────────────────────────────────────────

    /**
     * {@code /api/v1/data/inventory/sensitives} → entities and database tables. The first blank entity
     * moves last and further blank ones are dropped; tables are sorted by count descending, stably.
     * {@code inventories_sensitives[]} is ignored, and no sample is read.
     */
    public DatastoreSensitives toSensitives(JsonNode body) {
        List<DatastoreSensitives.Entity> entities = new ArrayList<>();
        DatastoreSensitives.Entity blank = null;
        for (JsonNode node : body.path("entity_level_response")) {
            String name = text(node, "entity_name");
            name = name == null ? "" : name;
            DatastoreSensitives.Entity entity = new DatastoreSensitives.Entity(name, longOr(node, "count", 0L),
                    firstLabel(node.path("labels")), name.isBlank());
            if (!entity.blank()) {
                entities.add(entity);
            } else if (blank == null) {
                blank = entity;
            }
        }
        if (blank != null) {
            entities.add(blank);
        }

        JsonNode databaseLevel = body.path("database_level_response");
        List<DatastoreSensitives.Database> databases = new ArrayList<>();
        for (JsonNode database : databaseLevel.path("databases")) {
            List<DatastoreSensitives.Table> tables = new ArrayList<>();
            for (JsonNode table : database.path("tables")) {
                tables.add(toTable(table));
            }
            tables.sort(Comparator.comparingLong(DatastoreSensitives.Table::count).reversed());
            databases.add(new DatastoreSensitives.Database(text(database, "id"), text(database, "name"), tables));
        }
        return new DatastoreSensitives(databaseLevel.isObject(), entities, databases);
    }

    private DatastoreSensitives.Table toTable(JsonNode table) {
        TreeSet<String> labels = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<DatastoreSensitives.ColumnFinding> findings = new ArrayList<>();
        for (JsonNode item : table.path("sensitive_entities")) {
            List<String> itemLabels = displayLabels(item.path("identified_labels"));
            labels.addAll(itemLabels);
            String entityType = text(item, "entity_type");
            if (entityType != null && !entityType.isBlank()) {
                JsonNode falsePositive = item.path("isFalsePositive");
                findings.add(new DatastoreSensitives.ColumnFinding(text(item, "column_name"), entityType, itemLabels,
                        longOr(item, "count", 0L), falsePositive.isBoolean() && falsePositive.booleanValue()));
            }
        }
        return new DatastoreSensitives.Table(text(table, "name"), text(table, "schema_name"),
                longOr(table, "count", 0L), List.copyOf(labels), !findings.isEmpty(), findings);
    }

    // ── file rows ────────────────────────────────────────────────────────────────────────────────

    /**
     * One row per {@code file_level_sensitives_count} key, in API key order, blank and zero rows kept.
     * {@code columnName}, {@code reason} and {@code sampled_data} are never read.
     *
     * <p>At most {@link #MAX_FILE_ROWS} + 1 rows are returned: the extra row only records that the cap
     * was hit, and {@link #filePage} drops it and reports {@code truncated}. That keeps the cached value
     * a plain {@code List<FileRow>}.
     */
    public List<DatastoreFilePage.FileRow> toFileRows(JsonNode body) {
        List<DatastoreFilePage.FileRow> rows = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> files = body.path("file_level_sensitives_count").fields();
        while (files.hasNext() && rows.size() <= MAX_FILE_ROWS) {
            Map.Entry<String, JsonNode> file = files.next();
            long count = 0L;
            TreeSet<String> labels = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            List<DatastoreFilePage.FileEntity> entities = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fileEntities = file.getValue().fields();
            while (fileEntities.hasNext()) {
                Map.Entry<String, JsonNode> entity = fileEntities.next();
                JsonNode value = entity.getValue();
                count += longOr(value, "entityCount", 0L);
                labels.addAll(displayLabels(value.path("labels")));
                entities.add(new DatastoreFilePage.FileEntity(entity.getKey(), firstLabel(value.path("labels"))));
            }
            rows.add(new DatastoreFilePage.FileRow(file.getKey(), basename(file.getKey()), count,
                    List.copyOf(labels), entities));
        }
        return rows;
    }

    /**
     * Filters rows whose path contains {@code q} case-insensitively (A15), then slices one page.
     *
     * @param rows     the cached {@link #toFileRows} result.
     * @param q        the validated search term, or null.
     * @param page     validated, 1-based.
     * @param pageSize validated, 1..100.
     */
    public DatastoreFilePage filePage(List<DatastoreFilePage.FileRow> rows, String q, int page, int pageSize) {
        boolean truncated = rows.size() > MAX_FILE_ROWS;
        List<DatastoreFilePage.FileRow> kept = truncated ? rows.subList(0, MAX_FILE_ROWS) : rows;
        List<DatastoreFilePage.FileRow> matched = kept;
        if (q != null) {
            String needle = q.toLowerCase(Locale.ROOT);
            matched = kept.stream()
                    .filter(row -> row.path() != null && row.path().toLowerCase(Locale.ROOT).contains(needle))
                    .toList();
        }
        int total = matched.size();
        int from = (int) Math.min((long) (page - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        return new DatastoreFilePage(List.copyOf(matched.subList(from, to)), page, pageSize, total,
                totalPages(total, pageSize), truncated);
    }

    /**
     * Strips trailing {@code /}, then takes the text after the last {@code /}; an empty result gives
     * the original. {@code s3a://bucket/id/db/public/agent_events/} → {@code agent_events}.
     */
    public static String basename(String path) {
        if (path == null) {
            return null;
        }
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        String trimmed = path.substring(0, end);
        String name = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        return name.isEmpty() ? path : name;
    }

    // ── occurrences ──────────────────────────────────────────────────────────────────────────────

    /**
     * {@code /sensitives/entity} → one occurrence page. {@code total} is {@code pagination.total} when
     * numeric, else {@code skip} + the rows returned; {@code total_sensitive_occurrences} counts records,
     * not rows, and is never used. {@code skip} and {@code limit} echo the request.
     */
    public DatastoreOccurrencePage toOccurrences(JsonNode body, String entityName, int skip, int limit) {
        JsonNode response = body.path("entity_level_response");
        List<DatastoreOccurrencePage.Occurrence> rows = new ArrayList<>();
        for (JsonNode row : response.path("data")) {
            String url = text(row, "url");
            url = url == null || url.isBlank() ? null : url;
            List<String> samples = new ArrayList<>();
            for (JsonNode sample : row.path("sampled_data")) {
                JsonNode value = sample.path("value");
                if (!value.isMissingNode() && !value.isNull() && !value.isContainerNode()) {
                    samples.add(value.asText());
                }
            }
            JsonNode falsePositive = row.path("is_false_positive");
            rows.add(new DatastoreOccurrencePage.Occurrence(
                    url == null ? null : basename(url), url,
                    text(row, "database_name"), text(row, "schema_name"), text(row, "table_name"),
                    text(row, "column_name"),
                    falsePositive.isBoolean() && falsePositive.booleanValue(),
                    samples));
        }
        JsonNode total = response.path("pagination").path("total");
        return new DatastoreOccurrencePage(entityName, total.isNumber() ? total.asLong() : (long) skip + rows.size(),
                skip, limit, rows);
    }

    // ── risks, scan history, scan progress ───────────────────────────────────────────────────────

    /** {@code /issues/overview} → misconfigurations. policy_id, learn_more, compliance and details are dropped. */
    public DatastoreRisks toRisks(JsonNode body) {
        List<DatastoreRisks.Misconfiguration> items = new ArrayList<>();
        for (JsonNode risk : body.path("risk")) {
            String severity = upper(text(risk, "severity"));
            String cli = text(risk, "remediation_recommendation_code_cli");
            items.add(new DatastoreRisks.Misconfiguration(
                    text(risk, "risk"),
                    upper(text(risk, "source")),
                    severity,
                    DatastoreLabels.misconfigurationSeverityLabel(severity),
                    text(risk, "description"),
                    text(risk, "remediation"),
                    cli == null || cli.isBlank() ? null : cli));
        }
        return new DatastoreRisks(items);
    }

    /** {@code /scan/asset/{id}/history} → runs as returned (newest first). id and end_time are dropped. */
    public DatastoreScanHistory toScanHistory(JsonNode body) {
        List<DatastoreScanHistory.Run> runs = new ArrayList<>();
        for (JsonNode run : body.path("scan_history")) {
            String sensitivity = text(run, "sensitivity_level");
            String status = text(run, "status");
            JsonNode execution = run.path("execution_number");
            runs.add(new DatastoreScanHistory.Run(
                    execution.isNumber() ? execution.asInt() : null,
                    longOrNull(run, "start_time"),
                    longOrNull(run, "count_of_data_at_risk"),
                    sensitivity,
                    DatastoreLabels.sensitivityLevelLabel(sensitivity),
                    text(run, "triggered_by"),
                    status,
                    DatastoreLabels.scanRunStatusLabel(status)));
        }
        return new DatastoreScanHistory(runs);
    }

    /** {@code /inventory/scan/status} → {@code scan_progress} when numeric; stages and counts are dropped. */
    public DatastoreScanProgress toScanProgress(JsonNode body) {
        JsonNode progress = body.path("scan_progress");
        return new DatastoreScanProgress(progress.isNumber() ? progress.asDouble() : null);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Display labels of a {@code labels[]} code array, in API order. */
    private static List<String> displayLabels(JsonNode codes) {
        List<String> labels = new ArrayList<>();
        for (JsonNode code : codes) {
            String label = code.isTextual() ? DatastoreLabels.sensitiveCategoryLabel(code.asText()) : null;
            if (label != null) {
                labels.add(label);
            }
        }
        return labels;
    }

    private static String firstLabel(JsonNode codes) {
        List<String> labels = displayLabels(codes);
        return labels.isEmpty() ? null : labels.get(0);
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
