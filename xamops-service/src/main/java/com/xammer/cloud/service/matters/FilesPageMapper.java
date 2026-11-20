package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.EndpointFileActivity;
import com.xammer.cloud.dto.matters.datastores.EndpointFileLineage;
import com.xammer.cloud.dto.matters.datastores.EndpointFileOverview;
import com.xammer.cloud.dto.matters.datastores.SaasFileLineage;
import com.xammer.cloud.dto.matters.datastores.SaasFileListPage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static com.xammer.cloud.service.matters.DatastoreRowMapper.longOr;
import static com.xammer.cloud.service.matters.DatastoreRowMapper.longOrNull;

/**
 * Field mapping for the Files page and the Endpoint file drawer (design 5.6 as changed by plan C1 to C7, C10,
 * C12, C18, C19). Every DTO is a whitelist and every read is defensive: a missing section gives an empty list
 * or null, never an exception. Samples are never read.
 */
@Component
public class FilesPageMapper {

    static final int MAX_ROWS = 100;
    static final int MAX_OPTIONS = 500;
    static final int MAX_SENSITIVES = 500;
    static final int MAX_LINEAGE_EVENTS = 100;
    static final int MAX_ACTIVITY_ENTRIES = 200;
    static final int MAX_TAGS = 20;
    static final int MAX_TAG_LENGTH = 128;

    /** The Sensitives filter on both tabs (plan C1): the console's static list, in its order. */
    static final List<String> SENSITIVE_CODES = List.of("PII", "PCI", "PHI", "SECRETS");

    /** The App Type filter (plan C5): the console's fixed nine, in its order. */
    static final List<String> APP_TYPES = List.of("ONE_DRIVE", "SHARE_POINT", "OUTLOOK", "GOOGLE_WORKSPACE", "GMAIL",
            "JIRA", "GITLAB", "CONFLUENCE", "SLACK");

    private static final Pattern HEX24 = Pattern.compile("^[0-9a-fA-F]{24}$");
    /** A file extension short enough to be one; Gmail rows carry the subject's tail here (GAP-30). */
    private static final Pattern SHORT_EXTENSION = Pattern.compile("^[A-Za-z0-9]{1,8}$");

    // ── SaaS tab ─────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code /api/v2/data/security/saas/sensitivities} → one page, rows in API order [100]. {@code origin},
     * {@code previous_cursor}, the share lists' addresses and a non-short {@code extension} are dropped.
     */
    public SaasFileListPage toSaasPage(JsonNode body, int limit) {
        List<SaasFileListPage.Row> rows = new ArrayList<>();
        for (JsonNode item : items(body.path("sensitives"))) {
            if (rows.size() >= MAX_ROWS) {
                break;
            }
            if (!item.isObject()) {
                continue;
            }
            String id = DrawerJson.string(item, "id");
            String extension = DrawerJson.string(item, "extension");
            String source = upper(DrawerJson.string(item, "source"));
            rows.add(new SaasFileListPage.Row(
                    id != null && HEX24.matcher(id).matches() ? lower(id) : null,
                    DrawerJson.string(item, "file_name"),
                    extension != null && SHORT_EXTENSION.matcher(extension).matches() ? lower(extension) : null,
                    source,
                    DatastoreLabels.appTypeLabel(source),
                    DatastoreLabels.categoryChipLabel(DrawerJson.string(item, "category")),
                    DrawerJson.string(item, "subcategory"),
                    sharingModes(item.path("share_info")),
                    DrawerJson.string(item, "owner"),
                    positive(DrawerJson.epochSeconds(item.path("last_modified_time")))));
        }
        String next = DrawerJson.string(body, "next_cursor");
        return new SaasFileListPage(rows, limit, DatastoreParams.isCursor(next) ? next : null);
    }

    /**
     * The modes of one {@code share_info} (plan C7), in display order: PUBLIC when {@code is_public} is
     * boolean true; EXTERNAL when {@code externally_shared} is an object or boolean true; INTERNAL likewise for
     * {@code internally_shared}; PRIVATE alone when none applies.
     */
    public List<SaasFileListPage.SharingMode> sharingModes(JsonNode shareInfo) {
        List<SaasFileListPage.SharingMode> modes = new ArrayList<>();
        if (shareInfo != null && isTrue(shareInfo.path("is_public"))) {
            modes.add(mode("PUBLIC"));
        }
        if (shareInfo != null && present(shareInfo.path("externally_shared"))) {
            modes.add(mode("EXTERNAL"));
        }
        if (shareInfo != null && present(shareInfo.path("internally_shared"))) {
            modes.add(mode("INTERNAL"));
        }
        if (modes.isEmpty()) {
            modes.add(mode("PRIVATE"));
        }
        return modes;
    }

    /**
     * {@code /saas/categories} and {@code /saas/sensitivities/users} → F2's cache entry. Dimensions in the
     * More filters order (plan C5): sensitives, category, appType, documentClass, owner, internalUser,
     * externalUser, sharing. Every count is null; each list is capped at 500 with {@code hasMore}.
     */
    public SaasFileOptionsEntry toSaasOptions(JsonNode categoriesBody, JsonNode usersBody) {
        Map<String, List<String>> classesByCategory = new LinkedHashMap<>();
        Set<String> categories = new LinkedHashSet<>();
        Set<String> classes = new LinkedHashSet<>();
        for (JsonNode item : items(categoriesBody.path("categories"))) {
            String category = DrawerJson.string(item, "category");
            Set<String> own = new LinkedHashSet<>();
            for (JsonNode element : items(item.path("subcategories"))) {
                String documentClass = DrawerJson.stringValue(element);
                if (documentClass != null) {
                    own.add(documentClass);
                    classes.add(documentClass);
                }
            }
            if (category != null) {
                categories.add(category);
                Set<String> merged = new LinkedHashSet<>(classesByCategory.getOrDefault(category, List.of()));
                merged.addAll(own);
                classesByCategory.put(category, List.copyOf(merged));
            }
        }

        List<DatastoreFilterOptions.Option> owners = new ArrayList<>();
        Set<String> ownerIds = new LinkedHashSet<>();
        for (JsonNode item : items(usersBody.path("owners"))) {
            String value = DrawerJson.string(item, "value");
            if (value == null || !HEX24.matcher(value).matches() || !ownerIds.add(lower(value))) {
                continue;
            }
            String key = DrawerJson.string(item, "key");
            owners.add(new DatastoreFilterOptions.Option(lower(value), key != null ? key : lower(value), null));
        }

        List<DatastoreFilterOptions.Dimension> dimensions = List.of(
                fixed("sensitives", SENSITIVE_CODES, DatastoreLabels::sensitiveCategoryLabel),
                listed("category", List.copyOf(categories), DatastoreLabels::categoryChipLabel),
                fixed("appType", APP_TYPES, DatastoreLabels::appTypeOptionLabel),
                listed("documentClass", List.copyOf(classes), DatastoreLabels::categoryChipLabel),
                capped("owner", owners),
                listed("internalUser", texts(usersBody.path("internal_users")), value -> value),
                listed("externalUser", texts(usersBody.path("external_users")), value -> value),
                fixed("sharing", SaasFileListQuery.SHARING, DatastoreLabels::sharingFilterLabel));
        return new SaasFileOptionsEntry(new DatastoreFilterOptions(dimensions), classesByCategory);
    }

    // ── Endpoint tab ─────────────────────────────────────────────────────────────────────────────

    /**
     * {@code /agents/filter-options} → F4 (plan C6), in the More filters order: category, sensitives,
     * documentClass, dataElement, tag, uid, hostname, lastModified. The structured-data placeholder is dropped
     * from categories and classes; the vendor's {@code sensitives} and {@code users} are not read.
     */
    public DatastoreFilterOptions toEndpointOptions(JsonNode body) {
        JsonNode data = body.path("data");
        return new DatastoreFilterOptions(List.of(
                listed("category", withoutPlaceholder(texts(data.path("categories"))), value -> value),
                fixed("sensitives", SENSITIVE_CODES, DatastoreLabels::sensitiveCategoryLabel),
                listed("documentClass", withoutPlaceholder(texts(data.path("subcategories"))), value -> value),
                listed("dataElement", texts(data.path("dataElements")), DatastoreLabels::dataElementOptionLabel),
                listed("tag", texts(data.path("tags")), value -> value),
                listed("uid", texts(data.path("uids")), value -> value),
                listed("hostname", texts(data.path("hostnames")), value -> value),
                fixed("lastModified", EndpointFileListQuery.LAST_MODIFIED, DatastoreLabels::lastModifiedLabel)));
    }

    // ── Endpoint file drawer ─────────────────────────────────────────────────────────────────────

    /**
     * {@code /endpoint-agent/files/{id}/overview} → F5. {@code sample_matches} is never read; {@code id},
     * {@code labels}, {@code device.agent_id}, {@code scan_status}, {@code created_at} and the sync and delete
     * flags are dropped.
     */
    public EndpointFileOverview toEndpointFileOverview(JsonNode body) {
        JsonNode overview = body.path("overview");
        String name = DrawerJson.string(overview, "name");
        String extension = lower(DrawerJson.string(overview, "extension"));
        String category = DrawerJson.string(overview, "category_label");
        String documentClass = DrawerJson.string(overview, "subcategory_label");
        List<EndpointFileOverview.Sensitive> sensitives = new ArrayList<>();
        for (JsonNode item : items(overview.path("sensitives"))) {
            if (sensitives.size() >= MAX_SENSITIVES) {
                break;
            }
            String dataElement = DrawerJson.string(item, "data_element");
            if (dataElement == null) {
                continue;
            }
            String code = texts(item.path("label")).stream().findFirst().orElse(null);
            sensitives.add(new EndpointFileOverview.Sensitive(dataElement,
                    DatastoreLabels.sensitiveCategoryLabel(code), longOr(item, "count", 0L)));
        }
        return new EndpointFileOverview(name,
                extension != null ? extension : DrawerJson.extensionOf(name),
                DrawerJson.string(overview, "owner"),
                DatastoreLabels.categoryChipLabel(category != null ? category : DrawerJson.string(overview, "category")),
                documentClass != null ? documentClass : DrawerJson.string(overview, "subcategory"),
                longOr(overview, "sensitive_entities_count", 0L),
                DrawerJson.string(overview, "path"),
                longOrNull(overview, "size"),
                DrawerJson.string(overview.path("device"), "hostname"),
                positive(DrawerJson.epochSeconds(overview.path("last_modified_time"))),
                positive(DrawerJson.epochSeconds(overview.path("file_created_time"))),
                positive(DrawerJson.epochSeconds(overview.path("file_accessed_time"))),
                DrawerJson.strings(overview.path("tags"), MAX_TAGS, MAX_TAG_LENGTH),
                sensitives);
    }

    /**
     * {@code /api/v2/endpoint-lineage/asset} → the file's own lane (plan C19), or null when {@code data.asset}
     * is not an object. {@code path}, {@code doc_id}, {@code resource_id}, {@code edges} and {@code alerts} are
     * not read. Events in API order [100], each with a null lane key.
     */
    public EndpointFileLineage.Asset toEndpointLineageAsset(JsonNode body) {
        JsonNode data = body.path("data");
        JsonNode asset = data.path("asset");
        if (!asset.isObject()) {
            return null;
        }
        List<SaasFileLineage.Event> events = new ArrayList<>();
        for (JsonNode event : items(data.path("events"))) {
            if (events.size() >= MAX_LINEAGE_EVENTS) {
                break;
            }
            if (!event.isObject()) {
                continue;
            }
            String type = SaasDrawerMapper.firstText(event, "event_type", "type");
            events.add(new SaasFileLineage.Event(null,
                    type == null ? null : DspmLabels.prettify(type),
                    SaasDrawerMapper.firstText(event, "actor_email", "actor", "os_user"),
                    DrawerJson.epochSeconds(SaasDrawerMapper.firstPresent(event,
                            "timestamp", "time", "occurred_at", "created_at"))));
        }
        return new EndpointFileLineage.Asset(DrawerJson.string(asset, "title"), DrawerJson.string(asset, "device"),
                DrawerJson.string(asset, "os_user"), DrawerJson.epochSeconds(asset.path("first_seen")), events);
    }

    /**
     * {@code /api/v1/admin/mip/file-audit-logs/{id}} → File Activity (plan C18), in API order [200]. An item
     * with neither an action nor a time is dropped.
     */
    public EndpointFileActivity toEndpointActivity(JsonNode body) {
        List<EndpointFileActivity.Entry> entries = new ArrayList<>();
        for (JsonNode item : items(body.path("audit_logs"))) {
            if (entries.size() >= MAX_ACTIVITY_ENTRIES) {
                break;
            }
            if (!item.isObject()) {
                continue;
            }
            String action = upper(DrawerJson.string(item, "action"));
            Long occurredAt = DrawerJson.epochSeconds(item.path("timestamp"));
            if (action == null && occurredAt == null) {
                continue;
            }
            entries.add(new EndpointFileActivity.Entry(action, DatastoreLabels.mipActionLabel(action),
                    tagName(item.path("selected_tag")), tagName(item.path("previous_tag")),
                    DrawerJson.string(item, "performed_by"), occurredAt,
                    isTrue(item.path("keywords_written")), isTrue(item.path("mip_label_written"))));
        }
        return new EndpointFileActivity(entries);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static SaasFileListPage.SharingMode mode(String code) {
        return new SaasFileListPage.SharingMode(code, DatastoreLabels.sharingModeLabel(code));
    }

    /** A tag's {@code name} as text of at most 128 characters, else null. */
    private static String tagName(JsonNode tag) {
        String name = DrawerJson.string(tag, "name");
        return name != null && name.length() <= MAX_TAG_LENGTH ? name : null;
    }

    /** A fixed option list: never capped. */
    private static DatastoreFilterOptions.Dimension fixed(String key, List<String> values, UnaryOperator<String> label) {
        List<DatastoreFilterOptions.Option> options = new ArrayList<>();
        for (String value : values) {
            options.add(new DatastoreFilterOptions.Option(value, label.apply(value), null));
        }
        return new DatastoreFilterOptions.Dimension(key, options, false);
    }

    /** Values from a route, de-duplicated in order, labelled, capped at 500 with hasMore. */
    private static DatastoreFilterOptions.Dimension listed(String key, List<String> values, UnaryOperator<String> label) {
        List<DatastoreFilterOptions.Option> options = new ArrayList<>();
        for (String value : new LinkedHashSet<>(values)) {
            options.add(new DatastoreFilterOptions.Option(value, label.apply(value), null));
        }
        return capped(key, options);
    }

    private static DatastoreFilterOptions.Dimension capped(String key, List<DatastoreFilterOptions.Option> options) {
        boolean hasMore = options.size() > MAX_OPTIONS;
        return new DatastoreFilterOptions.Dimension(key,
                hasMore ? List.copyOf(options.subList(0, MAX_OPTIONS)) : options, hasMore);
    }

    /** The text elements of an array, in order; nothing for any other node. */
    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode element : items(array)) {
            String value = DrawerJson.stringValue(element);
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<String> withoutPlaceholder(List<String> values) {
        return values.stream().filter(v -> !EndpointDrawerMapper.NOT_SUPPORTED_DOCUMENT_CLASS.equals(v)).toList();
    }

    /** An object, or boolean true: the console's "present" for a share list. */
    private static boolean present(JsonNode value) {
        return value.isObject() || isTrue(value);
    }

    private static boolean isTrue(JsonNode value) {
        return value.isBoolean() && value.booleanValue();
    }

    private static Long positive(Long epochSeconds) {
        return epochSeconds != null && epochSeconds > 0 ? epochSeconds : null;
    }

    private static Iterable<JsonNode> items(JsonNode array) {
        return array != null && array.isArray() ? array : List.of();
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
