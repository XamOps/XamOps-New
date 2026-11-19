package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.LineageCategories;
import com.xammer.cloud.dto.matters.datastores.LineageDatastorePage;
import com.xammer.cloud.dto.matters.datastores.LineageFilePage;
import com.xammer.cloud.dto.matters.datastores.LineagePolicyPage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static com.xammer.cloud.service.matters.DatastoreRowMapper.longOr;
import static com.xammer.cloud.service.matters.DatastoreRowMapper.longOrNull;

/**
 * Field mapping for the Data Lineage exposure routes (build 4 design 5.6). Every DTO is a whitelist: a
 * vendor field not named here never reaches a response or a cache entry. Reads are defensive: a missing
 * section gives an empty list, a null or 0, never an exception. Text is {@link DrawerJson} text.
 *
 * <p>The field names come from the console bundle and are unverified (design A2): the switch-on runbook
 * checks them against live answers first.
 */
@Component
public class LineageMapper {

    static final int MAX_CATEGORIES = 20;
    static final int MAX_SEVERITIES = 10;
    static final int MAX_DATASTORE_NAMES = 1_000;
    static final int MAX_LABELS = 100;
    static final int MAX_POLICIES = 50;
    static final int MAX_DATASTORES = 50;
    static final int MAX_FILES = 100;

    /** Longest free-text option value: the frontend's free-text rule. */
    static final int MAX_OPTION_LENGTH = 128;

    /** The severity values the frontend's {@code severity} rule accepts. */
    private static final Set<String> SEVERITY_OPTIONS = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "NONE");

    /** The bands a policy's dot has a colour for. */
    private static final Set<String> POLICY_SEVERITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern HEX_ID = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final Pattern EXPOSURE_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    // ── filter options ───────────────────────────────────────────────────────────────────────────

    /** {@code data.filter_options}: the known categories and the three filters' options. */
    public LineageFilterSnapshot toSnapshot(JsonNode body) {
        JsonNode options = body == null ? null : body.path("data").path("filter_options");
        if (options == null) {
            return new LineageFilterSnapshot(List.of(), Map.of(), List.of(), List.of(), List.of());
        }
        Map<ExposureCategory, String> found = new EnumMap<>(ExposureCategory.class);
        int seen = 0;
        for (JsonNode entry : array(options.path("categories"))) {
            if (seen++ >= MAX_CATEGORIES) {
                break;
            }
            String id = idText(entry.path("id"));
            ExposureCategory.fromVendorId(id).ifPresent(category -> {
                if (!found.containsKey(category)) {
                    String name = DrawerJson.string(entry, "name");
                    found.put(category, name == null ? category.title() : name);
                }
            });
        }
        List<String> categoryIds = new ArrayList<>();
        Map<String, String> categoryNames = new LinkedHashMap<>();
        found.forEach((category, name) -> {
            categoryIds.add(category.name());
            categoryNames.put(category.name(), name);
        });
        return new LineageFilterSnapshot(List.copyOf(categoryIds), categoryNames,
                options(options.path("severities"), MAX_SEVERITIES, LineageMapper::severityValue, true),
                options(options.path("datastore_names"), MAX_DATASTORE_NAMES, LineageMapper::freeTextValue, false),
                options(options.path("labels"), MAX_LABELS, LineageMapper::freeTextValue, false));
    }

    /**
     * One card per {@code snapshot.categoryIds()}, its count the {@code data.count} of that category's
     * summary answer (0 when missing).
     */
    public LineageCategories toCategories(LineageFilterSnapshot snapshot, Map<String, JsonNode> summaries) {
        List<LineageCategories.Category> categories = new ArrayList<>();
        for (String id : snapshot.categoryIds()) {
            JsonNode summary = summaries.get(id);
            long count = summary == null ? 0L : longOr(summary.path("data"), "count", 0L);
            String name = snapshot.categoryNames().get(id);
            if (name == null) {
                name = ExposureCategory.valueOf(id).title();
            }
            categories.add(new LineageCategories.Category(id, name, count));
        }
        return new LineageCategories(true, List.copyOf(categories));
    }

    // ── rows ─────────────────────────────────────────────────────────────────────────────────────

    /** {@code data.policies[]}: one policy per exposure; {@code data.count} the total. */
    public LineagePolicyPage toPolicyPage(JsonNode body, int skip, int limit) {
        JsonNode data = body == null ? null : body.path("data");
        List<LineagePolicyPage.Policy> rows = new ArrayList<>();
        if (data != null) {
            for (JsonNode policy : array(data.path("policies"))) {
                if (rows.size() >= MAX_POLICIES) {
                    break;
                }
                String severityText = DrawerJson.string(policy, "severity");
                String severity = severityText == null ? null : severityText.toUpperCase(Locale.ROOT);
                rows.add(new LineagePolicyPage.Policy(
                        matching(idText(policy.path("ref_policy_id")), UUID),
                        DrawerJson.string(policy, "policy_name"),
                        severity != null && POLICY_SEVERITIES.contains(severity) ? severity : null,
                        severityText == null ? null : DatastoreLabels.titleCase(severityText),
                        upper(DrawerJson.string(policy, "target_service")),
                        longOrNull(policy, "total_exposures")));
            }
        }
        long total = data == null ? 0L : longOr(data, "count", 0L);
        return new LineagePolicyPage(true, List.copyOf(rows), total, skip, limit);
    }

    /** {@code data[]}: a policy's affected datastores; the top-level {@code count} the total. */
    public LineageDatastorePage toDatastorePage(JsonNode body, int skip, int limit) {
        List<LineageDatastorePage.Datastore> rows = new ArrayList<>();
        if (body != null) {
            for (JsonNode datastore : array(body.path("data"))) {
                if (rows.size() >= MAX_DATASTORES) {
                    break;
                }
                String id = idText(datastore.path("id"));
                rows.add(new LineageDatastorePage.Datastore(
                        id != null && EXPOSURE_ID.matcher(id).matches() ? id : null,
                        DrawerJson.string(datastore, "datastore_name"),
                        upper(DrawerJson.string(datastore, "datastore_service")),
                        longOrNull(datastore, "affected_files_count")));
            }
        }
        long total = body == null ? 0L : longOr(body, "count", 0L);
        return new LineageDatastorePage(true, List.copyOf(rows), total, skip, limit);
    }

    /** {@code data.files[]}: an exposure's affected files; {@code data.total_files} the total. */
    public LineageFilePage toFilePage(JsonNode body, int skip, int limit) {
        JsonNode data = body == null ? null : body.path("data");
        List<LineageFilePage.File> rows = new ArrayList<>();
        if (data != null) {
            for (JsonNode file : array(data.path("files"))) {
                if (rows.size() >= MAX_FILES) {
                    break;
                }
                String name = firstText(file, "name", "file_name");
                rows.add(new LineageFilePage.File(
                        fileId(file),
                        name,
                        DrawerJson.extensionOf(name),
                        firstText(file, "owner", "file_owner", "currentUser"),
                        modifiedAt(file)));
            }
        }
        long total = data == null ? 0L : longOr(data, "total_files", 0L);
        return new LineageFilePage(true, List.copyOf(rows), total, skip, limit);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Options of one filter: {@code vendorId} the id text, {@code value} from {@code valueRule} (null drops
     * the entry), {@code label} the name text else the id. A repeated value keeps its first entry.
     */
    private static List<LineageFilterSnapshot.Option> options(JsonNode array, int cap, UnaryOperator<String> valueRule,
                                                              boolean severityLabel) {
        List<LineageFilterSnapshot.Option> out = new ArrayList<>();
        Set<String> values = new HashSet<>();
        int seen = 0;
        for (JsonNode entry : array(array)) {
            if (seen++ >= cap) {
                break;
            }
            String vendorId = idText(entry.path("id"));
            String value = vendorId == null ? null : valueRule.apply(vendorId);
            if (value == null || !values.add(value)) {
                continue;
            }
            String name = DrawerJson.string(entry, "name");
            String label = name == null ? vendorId : name;
            out.add(new LineageFilterSnapshot.Option(value,
                    severityLabel ? DatastoreLabels.titleCase(label) : label, vendorId));
        }
        return List.copyOf(out);
    }

    private static String severityValue(String id) {
        String upper = id.toUpperCase(Locale.ROOT);
        return SEVERITY_OPTIONS.contains(upper) ? upper : null;
    }

    private static String freeTextValue(String id) {
        if (id.length() > MAX_OPTION_LENGTH || id.indexOf(',') >= 0 || DatastoreParams.hasControlCharacter(id)) {
            return null;
        }
        return id;
    }

    /** The first of {@code id}, {@code file_id}, {@code _id} that is 24 hex or a UUID, lower-cased. */
    private static String fileId(JsonNode file) {
        for (String field : List.of("id", "file_id", "_id")) {
            String id = idText(file.path(field));
            if (id != null && (HEX_ID.matcher(id).matches() || UUID.matcher(id).matches())) {
                return id.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /** {@code last_modified_time}, else {@code fileModifiedTime}; a non-positive time is no time. */
    private static Long modifiedAt(JsonNode file) {
        Long value = positive(DrawerJson.epochSeconds(file.get("last_modified_time")));
        return value != null ? value : positive(DrawerJson.epochSeconds(file.get("fileModifiedTime")));
    }

    private static Long positive(Long seconds) {
        return seconds == null || seconds <= 0 ? null : seconds;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String text = DrawerJson.string(node, field);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /** A JSON string trimmed, or an integral JSON number in decimal; null otherwise. */
    private static String idText(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.bigIntegerValue().toString();
        }
        return DrawerJson.stringValue(value);
    }

    private static String matching(String id, Pattern pattern) {
        return id != null && pattern.matcher(id).matches() ? id.toLowerCase(Locale.ROOT) : null;
    }

    private static String upper(String text) {
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    private static Iterable<JsonNode> array(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }
}
