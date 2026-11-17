package com.xammer.cloud.service.matters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Every Redis key of the XamLens Datastores endpoints (design 5.9).
 *
 * <h2>Eviction</h2>
 * Each key has the shape {@code dspm:<kind>:<clientId>:<rest>}, so the existing
 * {@code MattersDspmService.evictClient} glob {@code dspm:*:<clientId>:*} ({@link #evictionPattern})
 * clears all of them when a client's mapping is repointed, deleted or loses its entitlement. That
 * eviction is what makes these keys project-scoped: a client maps to exactly one Matters project.
 *
 * <h2>No customer content</h2>
 * {@code RedisCacheService} logs every key at INFO, on a hit and on a write. The list and facets keys
 * therefore keep a readable prefix and end in the lower-case hex SHA-256 of the query's canonical
 * fragment ({@link DatastoreListQuery#canonicalListFragment()}), never the fragment itself, so no search
 * term or filter value reaches the log. Every other key holds only the client id, a validated 24-hex
 * source id or UUID, a vendor page number or a fixed word.
 */
public final class DatastoreCacheKeys {

    private static final String PREFIX = "dspm:";

    private DatastoreCacheKeys() {
    }

    // ── list page ────────────────────────────────────────────────────────────────────────────────

    /** {@code dspm:dslist:<c>:<tab>:<sha256 hex>}: one labelled list page. */
    public static String list(Long clientId, DatastoreListQuery query) {
        return key("dslist", clientId, query.tab().slug() + ":" + sha256Hex(query.canonicalListFragment()));
    }

    /** {@code dspm:dsfacets:<c>:<tab>:<sha256 hex>}: one tab's facets, integration labels raw. */
    public static String facets(Long clientId, DatastoreListQuery query) {
        return key("dsfacets", clientId, query.tab().slug() + ":" + sha256Hex(query.canonicalFacetsFragment()));
    }

    /** {@code dspm:dssummary:<c>:all}: the summary snapshot behind every tab's KPI cards. */
    public static String summary(Long clientId) {
        return key("dssummary", clientId, "all");
    }

    /** {@code dspm:dsacct:<c>:labels}: integration id → account label, for the 12-hour labeler entry. */
    public static String accountLabels(Long clientId) {
        return key("dsacct", clientId, "labels");
    }

    // ── drawer ───────────────────────────────────────────────────────────────────────────────────

    public static String overview(Long clientId, String sourceId) {
        return key("dsoverview", clientId, sourceId);
    }

    public static String scanProgress(Long clientId, String sourceId) {
        return key("dsscan", clientId, sourceId);
    }

    /** Entities and database tables: the small half of the sensitives response. */
    public static String sensitives(Long clientId, String sourceId) {
        return key("dssens", clientId, sourceId);
    }

    /** File View rows, samples stripped: the large half of the same response. */
    public static String files(Long clientId, String sourceId) {
        return key("dsfiles", clientId, sourceId);
    }

    public static String risks(Long clientId, String sourceId) {
        return key("dsrisks", clientId, sourceId);
    }

    public static String accessGraph(Long clientId, String sourceId) {
        return key("dsaccess", clientId, sourceId);
    }

    public static String scanHistory(Long clientId, String sourceId) {
        return key("dshistory", clientId, sourceId);
    }

    // ── Data Flow ────────────────────────────────────────────────────────────────────────────────

    public static String dataFlowDatabases(Long clientId, String sourceId) {
        return key("dsdfdb", clientId, sourceId);
    }

    public static String dataFlowSchemas(Long clientId, String databaseId) {
        return key("dsdfschemas", clientId, databaseId);
    }

    /** {@code dspm:dsdftables:<c>:s:<schemaId>:<vendorPage>}: one 100-row vendor page of a schema's tables. */
    public static String dataFlowSchemaTables(Long clientId, String schemaId, int vendorPage) {
        return key("dsdftables", clientId, "s:" + schemaId + ":" + vendorPage);
    }

    /** {@code dspm:dsdftables:<c>:d:<databaseId>:<vendorPage>}: the same for a schema-less database. */
    public static String dataFlowDatabaseTables(Long clientId, String databaseId, int vendorPage) {
        return key("dsdftables", clientId, "d:" + databaseId + ":" + vendorPage);
    }

    /** {@code dspm:dsdfcols:<c>:<tableId>:<vendorPage>}: one 100-row vendor page of a table's columns. */
    public static String dataFlowColumns(Long clientId, String tableId, int vendorPage) {
        return key("dsdfcols", clientId, tableId + ":" + vendorPage);
    }

    // ── eviction ─────────────────────────────────────────────────────────────────────────────────

    /** The glob {@code MattersDspmService.evictClient} sweeps; it matches every key above. */
    public static String evictionPattern(Long clientId) {
        return PREFIX + "*:" + clientId + ":*";
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes, as {@code InstallScriptIntegrity.sha256} computes it. */
    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java platform; unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String key(String kind, Long clientId, String rest) {
        return PREFIX + kind + ":" + clientId + ":" + rest;
    }
}
