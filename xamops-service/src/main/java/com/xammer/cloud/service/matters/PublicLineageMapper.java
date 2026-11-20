package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.xammer.cloud.dto.matters.datastores.PublicLineageDatastorePage;
import com.xammer.cloud.dto.matters.datastores.PublicLineageFilePage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Field mapping for the Data Lineage public path (build 7 design 5.5). Every record is a whitelist and every
 * read is defensive: a missing section gives an empty list or null, never an exception. "Text" is
 * {@link DrawerJson#stringValue}; times go through {@link DrawerJson#epochSeconds}, and a non-positive result
 * is null (build 3's zero-time rule). Ids handed to the browser are ids the endpoints accept, else null.
 */
@Component
public class PublicLineageMapper {

    static final int MAX_ENDPOINT_ROWS = 100;
    static final int MAX_INTEGRATIONS = 10;
    static final int MAX_PEOPLE = 200;
    static final int MAX_ENDPOINT_FILES = 100;
    static final int MAX_DRIVE_FILES = 1_000;
    static final int MAX_EMAIL_LENGTH = 320;

    static final String KIND_ENDPOINT = "ENDPOINT";
    static final String KIND_DRIVE = "DRIVE";

    private static final Pattern HEX24 = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern SHORT_EXTENSION = Pattern.compile("^[A-Za-z0-9]{1,8}$");

    /** The provider codes of a drive integration's sample asset, as the node's service. */
    private static final Map<String, String> SERVICES = Map.of(
            "GOOGLE_WORKSPACE", "GOOGLE_DRIVE",
            "GOOGLE_DRIVE", "GOOGLE_DRIVE",
            "ONE_DRIVE", "ONE_DRIVE",
            "SHARE_POINT", "SHARE_POINT");

    /** The Office {@code doc_type} labels of the drive file list, as extensions. */
    private static final Map<String, String> OFFICE_TYPES = Map.of(
            "microsoft word", "docx",
            "microsoft excel", "xlsx",
            "microsoft powerpoint", "pptx");

    /** An endpoint of the project's asset list: its 24-hex agent id, lower-cased, and its name. */
    public record EndpointRef(String agentId, String name) {
    }

    /**
     * {@code /api/v1/assets?category=endpoint} → the endpoints in API order [100]. A row whose
     * {@code source_id} is not 24 hex is dropped: without an id it can be neither counted nor opened.
     */
    public List<EndpointRef> endpointRows(JsonNode assetsBody) {
        List<EndpointRef> rows = new ArrayList<>();
        for (JsonNode item : items(path(assetsBody, "data"))) {
            if (rows.size() >= MAX_ENDPOINT_ROWS) {
                break;
            }
            String sourceId = DrawerJson.string(item, "source_id");
            if (sourceId == null || !HEX24.matcher(sourceId).matches()) {
                continue;
            }
            rows.add(new EndpointRef(sourceId.toLowerCase(Locale.ROOT), DrawerJson.string(item, "name")));
        }
        return List.copyOf(rows);
    }

    /** {@code …/agent/{id}/files} → its {@code total}, 0 when absent. */
    public long fileTotal(JsonNode filesBody) {
        return DatastoreRowMapper.longOr(nonNull(filesBody), "total", 0L);
    }

    /**
     * {@code /api/v1/assets/filters?category=saas} → the {@code integration_id[].value} texts that are 24 hex,
     * lower-cased, de-duplicated, in API order [10].
     */
    public List<String> integrationIds(JsonNode filtersBody) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode option : items(path(filtersBody, "integration_id"))) {
            if (ids.size() >= MAX_INTEGRATIONS) {
                break;
            }
            String value = DrawerJson.string(option, "value");
            if (value != null && HEX24.matcher(value).matches()) {
                ids.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(ids);
    }

    /**
     * A one-row {@code /api/v1/assets?integration_id=} → the integration's service from
     * {@code data[0].source.provider}: Google → {@code GOOGLE_DRIVE}, {@code ONE_DRIVE}, {@code SHARE_POINT},
     * any other code upper-cased as itself, null without one.
     */
    public String service(JsonNode sampleBody) {
        String provider = DrawerJson.stringValue(path(sampleBody, "data").path(0).path("source").path("provider"));
        if (provider == null) {
            return null;
        }
        String code = provider.toUpperCase(Locale.ROOT);
        return SERVICES.getOrDefault(code, code);
    }

    /**
     * {@code /api/v2/drive-lineage/users} → the integration's people with files, as datastores [200]. A row
     * is kept only when {@code file_count} is above 0; its id is null when the resource id fails the rule.
     * {@code is_external} is not read.
     */
    public List<PublicLineageDatastorePage.Datastore> people(JsonNode usersBody, String integrationId, String service) {
        List<PublicLineageDatastorePage.Datastore> rows = new ArrayList<>();
        for (JsonNode item : items(path(usersBody, "data"))) {
            if (rows.size() >= MAX_PEOPLE) {
                break;
            }
            Long files = item.isObject() ? DatastoreRowMapper.longOrNull(item, "file_count") : null;
            if (files == null || files <= 0) {
                continue;
            }
            PublicLineageKey key = PublicLineageKey.drive(integrationId, DrawerJson.string(item, "resource_id"));
            String name = DrawerJson.string(item, "display_name");
            rows.add(new PublicLineageDatastorePage.Datastore(key == null ? null : key.value(), KIND_DRIVE,
                    name != null ? name : DrawerJson.string(item, "email"), service, files));
        }
        return List.copyOf(rows);
    }

    /**
     * {@code …/agent/{id}/files} → one page of files [100]. Samples, sensitive entities and paths are not read.
     */
    public PublicLineageFilePage toEndpointFiles(JsonNode body, int skip, int limit) {
        List<PublicLineageFilePage.File> rows = new ArrayList<>();
        for (JsonNode item : items(path(body, "data"))) {
            if (rows.size() >= MAX_ENDPOINT_FILES) {
                break;
            }
            if (!item.isObject()) {
                continue;
            }
            String id = DrawerJson.string(item, "id");
            String name = DrawerJson.string(item, "name");
            String extension = DrawerJson.string(item, "extension");
            rows.add(new PublicLineageFilePage.File(
                    id != null && UUID.matcher(id).matches() ? id.toLowerCase(Locale.ROOT) : null,
                    name,
                    extension != null && SHORT_EXTENSION.matcher(extension).matches()
                            ? extension.toLowerCase(Locale.ROOT)
                            : DrawerJson.extensionOf(name),
                    DrawerJson.string(item, "currentUser"),
                    epoch(item.path("fileModifiedTime")),
                    epoch(item.path("fileCreatedTime"))));
        }
        return new PublicLineageFilePage(rows, fileTotal(body), skip, limit);
    }

    /**
     * {@code /api/v2/drive-lineage/user-files} → the person's whole file list [1,000]. {@code visibility},
     * {@code size_bytes} and {@code event_count} are not read.
     */
    public List<PublicLineageFilePage.File> toDriveFiles(JsonNode body) {
        List<PublicLineageFilePage.File> rows = new ArrayList<>();
        for (JsonNode item : items(path(body, "data"))) {
            if (rows.size() >= MAX_DRIVE_FILES) {
                break;
            }
            if (!item.isObject()) {
                continue;
            }
            String docId = DrawerJson.string(item, "doc_id");
            String title = DrawerJson.string(item, "title");
            rows.add(new PublicLineageFilePage.File(
                    PublicLineageKey.isDocId(docId) ? docId : null,
                    title,
                    driveExtension(title, DrawerJson.string(item, "doc_type")),
                    null,
                    epoch(item.path("last_seen")),
                    null));
        }
        return List.copyOf(rows);
    }

    /** {@code /api/v2/drive-lineage/asset} → {@code data.asset.owner_email}, at most 320 characters, else null. */
    public String driveOwner(JsonNode assetBody) {
        String owner = DrawerJson.stringValue(path(assetBody, "data").path("asset").path("owner_email"));
        return owner != null && owner.length() <= MAX_EMAIL_LENGTH ? owner : null;
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    /** The title's extension when short; else {@code doc_type} when short, lower-cased; else the Office map. */
    private static String driveExtension(String title, String docType) {
        String fromTitle = DrawerJson.extensionOf(title);
        if (fromTitle != null && SHORT_EXTENSION.matcher(fromTitle).matches()) {
            return fromTitle;
        }
        if (docType == null) {
            return null;
        }
        if (SHORT_EXTENSION.matcher(docType).matches()) {
            return docType.toLowerCase(Locale.ROOT);
        }
        return OFFICE_TYPES.get(docType.toLowerCase(Locale.ROOT));
    }

    /** Epoch seconds; a non-positive time is null. */
    private static Long epoch(JsonNode value) {
        Long seconds = DrawerJson.epochSeconds(value);
        return seconds != null && seconds > 0 ? seconds : null;
    }

    private static JsonNode nonNull(JsonNode node) {
        return node == null ? MissingNode.getInstance() : node;
    }

    private static JsonNode path(JsonNode node, String field) {
        return nonNull(node).path(field);
    }

    private static Iterable<JsonNode> items(JsonNode array) {
        return array.isArray() ? array : List.of();
    }
}
