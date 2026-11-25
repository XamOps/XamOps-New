package com.xammer.cloud.service.matters;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The opaque datastore id of the Data Lineage public path (build 7 design 4.3, 5.4). It carries what the
 * vendor needs for the datastore's files and lineage:
 * <ul>
 *   <li>an endpoint: {@code ep-<agentId>}, the agent id 24 hex, lower-cased;</li>
 *   <li>a drive person: {@code dr-<integrationId>-<resourceId>}, the integration id 24 hex, lower-cased, and
 *       the person's resource id as the vendor returned it (1 to 64 letters, digits, {@code _} or {@code -}).</li>
 * </ul>
 *
 * @param agentId       ENDPOINT only, else null
 * @param integrationId DRIVE only, else null
 * @param resourceId    DRIVE only, else null
 */
public record PublicLineageKey(Kind kind, String agentId, String integrationId, String resourceId) {

    /** The two kinds of datastore the public path shows. */
    public enum Kind { ENDPOINT, DRIVE }

    static final String DATASTORE_ID_MESSAGE = "datastoreId must be a datastore of the lineage page";
    static final String ENDPOINT_FILE_MESSAGE = "fileId must be a UUID for an endpoint file";
    static final String DRIVE_FILE_MESSAGE = "fileId must be a drive document id";

    private static final Pattern HEX24 = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final Pattern RESOURCE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern DOC_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern ENDPOINT_ID = Pattern.compile("^ep-([0-9a-fA-F]{24})$");
    private static final Pattern DRIVE_ID = Pattern.compile("^dr-([0-9a-fA-F]{24})-([A-Za-z0-9_-]{1,64})$");

    /**
     * The key a path variable names.
     *
     * @throws DspmBadRequestException on {@code parameter} when the value has neither format
     */
    public static PublicLineageKey parse(String parameter, String raw) {
        if (raw != null) {
            Matcher endpoint = ENDPOINT_ID.matcher(raw);
            if (endpoint.matches()) {
                return new PublicLineageKey(Kind.ENDPOINT, endpoint.group(1).toLowerCase(Locale.ROOT), null, null);
            }
            Matcher drive = DRIVE_ID.matcher(raw);
            if (drive.matches()) {
                return new PublicLineageKey(Kind.DRIVE, null, drive.group(1).toLowerCase(Locale.ROOT), drive.group(2));
            }
        }
        throw new DspmBadRequestException(parameter, DATASTORE_ID_MESSAGE);
    }

    /** An endpoint's key; null when the agent id is not 24 hex. */
    public static PublicLineageKey endpoint(String agentId) {
        if (agentId == null || !HEX24.matcher(agentId).matches()) {
            return null;
        }
        return new PublicLineageKey(Kind.ENDPOINT, agentId.toLowerCase(Locale.ROOT), null, null);
    }

    /** A drive person's key; null when the integration id is not 24 hex or the resource id fails its rule. */
    public static PublicLineageKey drive(String integrationId, String resourceId) {
        if (integrationId == null || !HEX24.matcher(integrationId).matches()
                || resourceId == null || !RESOURCE_ID.matcher(resourceId).matches()) {
            return null;
        }
        return new PublicLineageKey(Kind.DRIVE, null, integrationId.toLowerCase(Locale.ROOT), resourceId);
    }

    /** The id handed to the browser: {@code ep-<agentId>} or {@code dr-<integrationId>-<resourceId>}. */
    public String value() {
        return kind == Kind.ENDPOINT ? "ep-" + agentId : "dr-" + integrationId + "-" + resourceId;
    }

    /**
     * A file id of this datastore: an endpoint file's UUID, lower-cased, or a drive document id, kept as sent
     * (the vendor id is case-sensitive).
     *
     * @throws DspmBadRequestException on {@code parameter} when the value is not a file id of this kind
     */
    public String fileId(String parameter, String raw) {
        if (kind == Kind.ENDPOINT) {
            if (raw == null || !UUID.matcher(raw).matches()) {
                throw new DspmBadRequestException(parameter, ENDPOINT_FILE_MESSAGE);
            }
            return raw.toLowerCase(Locale.ROOT);
        }
        if (!isDocId(raw)) {
            throw new DspmBadRequestException(parameter, DRIVE_FILE_MESSAGE);
        }
        return raw;
    }

    /**
     * A drive document id: 1 to 128 letters, digits, {@code _} or {@code -}, and not shaped as a UUID, so an
     * endpoint file id is never taken for a drive one (design 9.1, cross-kind rejected). The capture's ids are
     * 33 or 44 characters (requirements A6).
     */
    static boolean isDocId(String raw) {
        return raw != null && DOC_ID.matcher(raw).matches() && !UUID.matcher(raw).matches();
    }
}
