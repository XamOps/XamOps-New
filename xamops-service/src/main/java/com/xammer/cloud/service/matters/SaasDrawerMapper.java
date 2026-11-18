package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.xammer.cloud.dto.matters.datastores.SaasDrawerHeader;
import com.xammer.cloud.dto.matters.datastores.SaasFileActivity;
import com.xammer.cloud.dto.matters.datastores.SaasFileCopies;
import com.xammer.cloud.dto.matters.datastores.SaasFileLineage;
import com.xammer.cloud.dto.matters.datastores.SaasFileOverview;
import com.xammer.cloud.dto.matters.datastores.SaasFilePage;
import com.xammer.cloud.dto.matters.datastores.SaasRecords;
import com.xammer.cloud.dto.matters.datastores.SaasScanSummary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.xammer.cloud.service.matters.DatastoreRowMapper.longOr;
import static com.xammer.cloud.service.matters.DatastoreRowMapper.longOrNull;

/**
 * Field mapping for the SaaS drawer and the SaaS file drawer (design 5.6.1). Every DTO is a whitelist:
 * a vendor field not named here never reaches a response or a cache entry. Reads are defensive: a
 * missing section gives an empty list or null, never an exception. Text is {@link DrawerJson} text.
 */
@Component
public class SaasDrawerMapper {

    static final int MAX_RECORDS = 1_000;
    static final int MAX_FILES = 100;
    static final int MAX_FILE_TYPES = 200;
    static final int MAX_SHARING_ENTRIES = 200;
    static final int MAX_SHARING_LENGTH = 320;
    static final int MAX_SENSITIVES = 500;
    static final int MAX_METADATA = 50;
    static final int MAX_METADATA_VALUE_LENGTH = 2_048;
    static final int MAX_ACTIVITY = 100;
    static final int MAX_COPIES = 200;
    static final int MAX_TIMELINE_LANES = 50;
    static final int MAX_EVENTS = 100;
    static final int MAX_COVERAGE_NOTE_LENGTH = 300;

    private static final Pattern FILE_ID = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final Pattern CODE_CASE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /**
     * A copy read once for both {@link #toLineage} and {@link #toCopies}, so their lanes and rows agree.
     * Everything but {@code lane} is internal: used for matching and the copy rows, never returned.
     */
    private record CopyLane(JsonNode copy, SaasFileLineage.Lane lane, String extension, String source,
                            String locationKey, String dataObjectId, String fileId, String resourceId) {
    }

    /** A lane that events can be matched to: its key and the ids it is known by. */
    private record LaneIds(String key, String dataObjectId, String fileId, String resourceId) {
    }

    // ── SaaS drawer ──────────────────────────────────────────────────────────────────────────────

    /** {@code …/saas/asset/{id}/detail}. The title uses the list row, so {@code name} is not read. */
    public SaasDrawerHeader toHeader(JsonNode body) {
        JsonNode detail = body.path("detail");
        String driveType = upper(DrawerJson.string(detail, "drive_type"));
        String accountName = DrawerJson.string(detail, "account_name");
        if (accountName == null) {
            accountName = DrawerJson.string(detail.path("source"), "name");
        }
        String backfill = upper(DrawerJson.string(detail, "scan_state"));
        String incremental = upper(DrawerJson.string(detail, "realtime_scan_status"));
        return new SaasDrawerHeader(driveType, DatastoreLabels.subtypeLabel(driveType), accountName,
                backfill, DatastoreLabels.saasScanStateLabel(backfill),
                incremental, DatastoreLabels.saasScanStateLabel(incremental));
    }

    /**
     * {@code …/sensitives/records?view=}, in API order (the vendor sends count order; its order of ties
     * is kept). Rows with a blank name are dropped: they can be neither expanded nor sent as a filter.
     */
    public SaasRecords toRecords(JsonNode body, String view) {
        boolean documentClasses = DatastoreParams.VIEW_DOCUMENT_CLASSES.equals(view);
        List<SaasRecords.Row> rows = new ArrayList<>();
        for (JsonNode record : items(body.path("records"))) {
            if (rows.size() >= MAX_RECORDS) {
                break;
            }
            String name;
            String categoryLabel;
            if (documentClasses) {
                name = DrawerJson.string(record, "subcategory_label");
                categoryLabel = DrawerJson.string(record, "category_label");
            } else {
                name = DrawerJson.string(record, "sensitive_record");
                categoryLabel = DatastoreLabels.sensitiveCategoryLabel(firstText(record.path("label")));
            }
            if (name != null) {
                rows.add(new SaasRecords.Row(name, categoryLabel, longOr(record, "count", 0L)));
            }
        }
        return new SaasRecords(view, rows);
    }

    /** {@code …/saas/asset/{id}/files}. {@code total} is {@code count} when numeric, else skip + rows. */
    public SaasFilePage toFilePage(JsonNode body, int skip, int limit) {
        List<SaasFilePage.File> rows = new ArrayList<>();
        for (JsonNode file : items(body.path("files"))) {
            if (rows.size() >= MAX_FILES) {
                break;
            }
            String id = DrawerJson.string(file, "id");
            rows.add(new SaasFilePage.File(
                    id != null && FILE_ID.matcher(id).matches() ? id.toLowerCase(Locale.ROOT) : null,
                    DrawerJson.string(file, "file"), longOr(file, "records", 0L)));
        }
        Long count = longOrNull(body, "count");
        return new SaasFilePage(rows, skip, limit, count != null ? count : (long) skip + rows.size());
    }

    /** {@code …/saas/asset/{id}/scan-summary}: file types by count descending, then label, then key. */
    public SaasScanSummary toScanSummary(JsonNode body) {
        JsonNode summary = body.path("scan_summary");
        List<SaasScanSummary.FileType> fileTypes = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> breakdown = summary.path("file_type_breakdown").fields();
        while (breakdown.hasNext()) {
            Map.Entry<String, JsonNode> entry = breakdown.next();
            if (entry.getValue().isNumber()) {
                fileTypes.add(new SaasScanSummary.FileType(entry.getKey(),
                        DatastoreLabels.saasFileTypeLabel(entry.getKey()), entry.getValue().asLong()));
            }
        }
        fileTypes.sort(Comparator.comparingLong(SaasScanSummary.FileType::count).reversed()
                .thenComparing(type -> type.label() == null ? "" : type.label(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SaasScanSummary.FileType::extension));
        JsonNode properties = summary.path("properties");
        return new SaasScanSummary(part(summary.path("supported")), part(summary.path("unsupported")),
                cap(fileTypes, MAX_FILE_TYPES),
                new SaasScanSummary.Properties(DrawerJson.string(properties, "owner"),
                        longOrNull(properties, "datastore_size"),
                        DrawerJson.epochSeconds(properties.path("created_on")),
                        DrawerJson.epochSeconds(properties.path("last_scan"))));
    }

    // ── SaaS file drawer ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code …/saas/inventory/{fileId}/overview}. Metadata keeps only text values of at most 2,048
     * characters under keys that are not credential-shaped, which drops Gmail {@code headers},
     * {@code labelIds}, {@code sizeEstimate}, {@code internalDate} and Drive {@code owners},
     * {@code permissions}, {@code modifiedTime}, {@code size} (GAP-52). {@code id}, {@code file_id},
     * {@code path}, {@code labels}, {@code mip_labels}, {@code created_at}, {@code category} and
     * {@code subcategory} are not read.
     */
    public SaasFileOverview toFileOverview(JsonNode body) {
        JsonNode overview = body.path("overview");
        String extension = lower(DrawerJson.string(overview, "file_extension"));
        JsonNode source = overview.path("source");
        JsonNode structured = overview.path("is_structured");
        return new SaasFileOverview(
                DrawerJson.string(overview, "name"),
                extension,
                DatastoreLabels.fileExtensionDescription(extension),
                upper(DrawerJson.string(source, "drive_type")),
                DrawerJson.string(source, "account_name"),
                DrawerJson.string(overview, "owner"),
                DrawerJson.string(overview, "category_label"),
                DrawerJson.string(overview, "subcategory_label"),
                longOrNull(overview, "size"),
                structured.isBoolean() ? structured.booleanValue() : null,
                DrawerJson.epochSeconds(overview.path("last_modified_time")),
                DrawerJson.string(overview, "last_modified_by"),
                longOr(overview, "sensitive_entities_count", 0L),
                longOrNull(overview, "policy_violation_count"),
                sharing(overview.path("sharing")),
                sensitives(overview.path("sensitives")),
                metadata(overview.path("metadata")));
    }

    private static SaasFileOverview.Sharing sharing(JsonNode sharing) {
        return new SaasFileOverview.Sharing(
                DrawerJson.strings(sharing.path("internal_emails"), MAX_SHARING_ENTRIES, MAX_SHARING_LENGTH),
                DrawerJson.strings(sharing.path("external_emails"), MAX_SHARING_ENTRIES, MAX_SHARING_LENGTH),
                DrawerJson.strings(sharing.path("internal_groups"), MAX_SHARING_ENTRIES, MAX_SHARING_LENGTH),
                DrawerJson.strings(sharing.path("external_groups"), MAX_SHARING_ENTRIES, MAX_SHARING_LENGTH),
                isTrue(sharing.path("is_private")),
                isTrue(sharing.path("is_public")),
                isTrue(sharing.path("is_org_wide")),
                isTrue(sharing.path("is_external_share")));
    }

    private static List<SaasFileOverview.Sensitive> sensitives(JsonNode sensitives) {
        List<SaasFileOverview.Sensitive> out = new ArrayList<>();
        for (JsonNode sensitive : items(sensitives)) {
            if (out.size() >= MAX_SENSITIVES) {
                break;
            }
            out.add(new SaasFileOverview.Sensitive(DrawerJson.string(sensitive, "data_element"),
                    DatastoreLabels.sensitiveCategoryLabel(firstText(sensitive.path("label"))),
                    longOr(sensitive, "count", 0L)));
        }
        return out;
    }

    private static List<SaasFileOverview.MetadataEntry> metadata(JsonNode metadata) {
        List<SaasFileOverview.MetadataEntry> out = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = metadata.fields();
        while (fields.hasNext() && out.size() < MAX_METADATA) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            String value = DrawerJson.stringValue(field.getValue());
            if (value == null || value.length() > MAX_METADATA_VALUE_LENGTH
                    || DatastoreDrawerMapper.isCredentialShaped(key)) {
                continue;
            }
            out.add(new SaasFileOverview.MetadataEntry(key, DatastoreLabels.metadataLabel(key), value));
        }
        return out;
    }

    /**
     * {@code /api/v1/remediation/objects/{fileId}/logs}. The item fields are unverified (GAP-53), so each
     * is read from the first of several names. An item with neither a title nor a time is dropped. Newest
     * first, untimed last, ties in API order.
     */
    public SaasFileActivity toActivity(JsonNode body) {
        List<SaasFileActivity.Entry> entries = new ArrayList<>();
        for (JsonNode item : items(body.path("response"))) {
            if (entries.size() >= MAX_ACTIVITY) {
                break;
            }
            if (!item.isObject()) {
                continue;
            }
            String title = firstText(item, "title", "action_title", "action", "action_type");
            if (title != null && CODE_CASE.matcher(title).matches()) {
                title = DspmLabels.prettify(title);
            }
            Long occurredAt = DrawerJson.epochSeconds(firstPresent(item,
                    "created_at", "timestamp", "time", "executed_at", "updated_at"));
            if (title == null && occurredAt == null) {
                continue;
            }
            String status = DrawerJson.string(item, "status");
            entries.add(new SaasFileActivity.Entry(title, status == null ? null : DspmLabels.prettify(status),
                    firstText(item, "initiated_by", "initiator", "actor", "user", "created_by"), occurredAt));
        }
        // List.sort is stable, so ties keep API order.
        entries.sort(Comparator.comparing(SaasFileActivity.Entry::occurredAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new SaasFileActivity(entries);
    }

    /**
     * The Lineage tab from {@code /api/v2/hash-lineage/copies} and, when it returned a copy,
     * {@code /api/v2/hash-lineage/timeline}.
     *
     * @param copiesBody   the copies answer.
     * @param timelineBody the timeline answer, or null when it was not called or found nothing.
     * @param fileId       the validated, lower-cased request id: it decides {@code self} (design 4.4).
     */
    public SaasFileLineage toLineage(JsonNode copiesBody, JsonNode timelineBody, String fileId) {
        JsonNode data = copiesBody.path("data");
        String coverageNote = DrawerJson.string(data, "hash_coverage_note");
        if (coverageNote != null && coverageNote.length() > MAX_COVERAGE_NOTE_LENGTH) {
            coverageNote = null;
        }

        List<SaasFileLineage.Lane> lanes = new ArrayList<>();
        List<LaneIds> laneIds = new ArrayList<>();
        Set<String> copyObjectIds = new HashSet<>();
        for (CopyLane copy : copyLanes(data, fileId)) {
            lanes.add(copy.lane());
            laneIds.add(new LaneIds(copy.lane().key(), copy.dataObjectId(), copy.fileId(), copy.resourceId()));
            if (copy.dataObjectId() != null) {
                copyObjectIds.add(copy.dataObjectId());
            }
        }

        JsonNode timeline = timelineBody == null ? MissingNode.getInstance() : timelineBody.path("data");
        int index = 0;
        int added = 0;
        for (JsonNode item : items(timeline.path("lanes"))) {
            int position = index++;
            if (added >= MAX_TIMELINE_LANES) {
                break;
            }
            String dataObjectId = DrawerJson.string(item, "data_object_id");
            if (!item.isObject() || (dataObjectId != null && copyObjectIds.contains(dataObjectId))) {
                continue;
            }
            String driveType = upper(DrawerJson.string(item, "drive_type"));
            String os = upper(DrawerJson.string(item, "os"));
            String externalId = DrawerJson.string(item, "external_id");
            SaasFileLineage.Lane lane = new SaasFileLineage.Lane("tl-" + position, null,
                    firstNonNull(DrawerJson.string(item, "display_name"), DrawerJson.string(item, "hostname")),
                    externalId != null && externalId.contains("@") ? externalId : null,
                    driveType, os, upper(DrawerJson.string(item, "provider")),
                    DatastoreLabels.copySourceLabel(DrawerJson.string(item, "source"), driveType, os), false, null);
            lanes.add(lane);
            laneIds.add(new LaneIds(lane.key(), dataObjectId, null, DrawerJson.string(item, "resource_id")));
            added++;
        }

        List<SaasFileLineage.Event> events = new ArrayList<>();
        for (JsonNode event : items(timeline.path("events"))) {
            if (events.size() >= MAX_EVENTS) {
                break;
            }
            if (!event.isObject()) {
                continue;
            }
            String type = firstText(event, "type", "event_type", "action", "name");
            events.add(new SaasFileLineage.Event(laneKey(event, laneIds),
                    type == null ? null : DspmLabels.prettify(type),
                    firstText(event, "actor", "actor_email", "user", "user_email", "performed_by"),
                    DrawerJson.epochSeconds(firstPresent(event,
                            "timestamp", "time", "occurred_at", "created_at", "event_time"))));
        }
        return new SaasFileLineage(true, coverageNote, lanes, events);
    }

    /**
     * File Copies and Related Files from {@code /api/v2/hash-lineage/copies}: every copy but the opened
     * file's own, in API order. {@code locations} counts their distinct lanes (GAP-60).
     */
    public SaasFileCopies toCopies(JsonNode copiesBody, String fileId) {
        List<SaasFileCopies.Copy> copies = new ArrayList<>();
        Set<String> locations = new HashSet<>();
        for (CopyLane copy : copyLanes(copiesBody.path("data"), fileId)) {
            SaasFileLineage.Lane lane = copy.lane();
            if (lane.self()) {
                continue;
            }
            boolean saas = "SAAS".equals(upper(copy.source())) || lane.driveType() != null;
            String userOrDevice = saas
                    ? lane.ownerEmail()
                    : firstNonNull(DrawerJson.string(copy.copy(), "current_os_user"),
                    DrawerJson.string(copy.copy().path("lane"), "hostname"));
            copies.add(new SaasFileCopies.Copy(lane.key(), lane.fileName(), copy.extension(),
                    DrawerJson.string(copy.copy(), "path"), lane.driveType(), lane.os(), lane.provider(),
                    lane.channelLabel(), DatastoreLabels.copyLocationLabel(copy.source(), lane.driveType(), lane.os()),
                    lane.ownerName(), lane.ownerEmail(), userOrDevice));
            locations.add(copy.locationKey());
        }
        return new SaasFileCopies(true, copies, locations.size());
    }

    /** Every {@code data.copies[]} item [200], in API order, through {@link #copyLane}. */
    private static List<CopyLane> copyLanes(JsonNode data, String fileId) {
        List<CopyLane> out = new ArrayList<>();
        int index = 0;
        for (JsonNode copy : items(data.path("copies"))) {
            int position = index++;
            if (position >= MAX_COPIES) {
                break;
            }
            if (copy.isObject()) {
                out.add(copyLane(copy, position, data, fileId));
            }
        }
        return out;
    }

    /**
     * One copy as a lane. The key is {@code copy-<index>}, an opaque React key, so no vendor id leaves the
     * server. {@code self} is decided by file id, never by {@code is_origin} (GAP-57).
     */
    private static CopyLane copyLane(JsonNode copy, int index, JsonNode data, String fileId) {
        JsonNode lane = copy.path("lane");
        String key = "copy-" + index;
        String name = DrawerJson.string(copy, "name");
        String extension = lower(DrawerJson.string(copy, "extension"));
        if (extension == null) {
            extension = DrawerJson.extensionOf(name);
        }
        String owner = DrawerJson.string(copy, "owner");
        String lastModifiedBy = DrawerJson.string(copy, "last_modified_by");
        String laneExternalId = DrawerJson.string(lane, "external_id");
        String driveType = upper(DrawerJson.string(lane, "drive_type"));
        String os = upper(DrawerJson.string(lane, "os"));
        String source = DrawerJson.string(copy, "source");
        String copyFileId = DrawerJson.string(copy, "file_id");
        String dataObjectId = DrawerJson.string(copy, "data_object_id");
        String resourceId = DrawerJson.string(lane, "resource_id");

        Long firstSeenAt = DrawerJson.epochSeconds(copy.path("created_at"));
        if (firstSeenAt == null) {
            firstSeenAt = DrawerJson.epochSeconds(copy.path("created"));
        }
        SaasFileLineage.Lane out = new SaasFileLineage.Lane(key, name,
                firstNonNull(DrawerJson.string(lane, "display_name"), owner, lastModifiedBy,
                        DrawerJson.string(lane, "hostname")),
                firstEmail(owner, laneExternalId, lastModifiedBy),
                driveType, os, upper(DrawerJson.string(lane, "provider")),
                DatastoreLabels.copySourceLabel(source, driveType, os),
                isSelf(copyFileId, dataObjectId, data.path("origin"), fileId),
                firstSeenAt);
        String locationKey = firstNonNull(DrawerJson.string(lane, "legacy_id"), resourceId, key);
        return new CopyLane(copy, out, extension, source, locationKey, dataObjectId, copyFileId, resourceId);
    }

    /**
     * A copy with a {@code file_id} is the opened file's own exactly when the ids match, ignoring case. A
     * copy without one is only when {@code data.origin} names the opened file and the same data object.
     */
    private static boolean isSelf(String copyFileId, String dataObjectId, JsonNode origin, String fileId) {
        if (fileId == null) {
            return false;
        }
        if (copyFileId != null) {
            return copyFileId.equalsIgnoreCase(fileId);
        }
        String originFileId = DrawerJson.string(origin, "file_id");
        String originObjectId = DrawerJson.string(origin, "data_object_id");
        return originFileId != null && originFileId.equalsIgnoreCase(fileId)
                && dataObjectId != null && dataObjectId.equals(originObjectId);
    }

    /** The lane whose data object id, else file id, equals the event's, else the first by resource id. */
    private static String laneKey(JsonNode event, List<LaneIds> lanes) {
        String dataObjectId = DrawerJson.string(event, "data_object_id");
        if (dataObjectId != null) {
            for (LaneIds lane : lanes) {
                if (dataObjectId.equals(lane.dataObjectId())) {
                    return lane.key();
                }
            }
        }
        String fileId = DrawerJson.string(event, "file_id");
        if (fileId != null) {
            for (LaneIds lane : lanes) {
                if (fileId.equalsIgnoreCase(lane.fileId())) {
                    return lane.key();
                }
            }
        }
        String resourceId = DrawerJson.string(event, "resource_id");
        if (resourceId != null) {
            for (LaneIds lane : lanes) {
                if (resourceId.equals(lane.resourceId())) {
                    return lane.key();
                }
            }
        }
        return null;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** The elements of an array; nothing for any other node, so an object's values are never read as rows. */
    private static Iterable<JsonNode> items(JsonNode array) {
        return array.isArray() ? array : List.of();
    }

    private static SaasScanSummary.Part part(JsonNode part) {
        return new SaasScanSummary.Part(longOr(part, "count", 0L), longOr(part, "size", 0L));
    }

    private static <T> List<T> cap(List<T> list, int max) {
        return list.size() <= max ? list : new ArrayList<>(list.subList(0, max));
    }

    /** The first text element of an array. */
    private static String firstText(JsonNode array) {
        for (JsonNode element : items(array)) {
            String text = DrawerJson.stringValue(element);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /** The first field of {@code node} that holds text. */
    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String text = DrawerJson.string(node, field);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /** The first field of {@code node} that is present and not JSON null. */
    private static JsonNode firstPresent(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static String firstEmail(String... values) {
        for (String value : values) {
            if (value != null && value.contains("@")) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean isTrue(JsonNode value) {
        return value.isBoolean() && value.booleanValue();
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
