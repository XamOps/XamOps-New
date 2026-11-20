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
 * term or filter value reaches the log. The SaaS files, endpoint metrics and endpoint files keys do the
 * same with {@link SaasFileQuery#canonicalFragment()}, {@link EndpointFilters#canonicalFragment()} and
 * {@link EndpointFileQuery#canonicalFragment()} (build 2, design 5.8), and the Files page lists with
 * {@link SaasFileListQuery#canonicalFragment()} and {@link EndpointFileListQuery#canonicalFragment()}, whose
 * SaaS fragment holds the vendor cursor too (build 3). Every other key holds only the
 * client id, a validated 24-hex source or file id or UUID, a vendor page number, a validated skip and
 * limit, or a fixed word.
 *
 * <p>{@link #misconfigurations} ({@code dspm:dsmisconf:<clientId>:all}, build 5) holds the project-wide
 * Misconfigurations snapshot aggregated from every cloud and on-prem datastore's issues.
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

    /** {@code dspm:dsdferd:<c>:s:<schemaId>}: a schema's diagram (Graph View). */
    public static String dataFlowSchemaErd(Long clientId, String schemaId) {
        return key("dsdferd", clientId, "s:" + schemaId);
    }

    /** {@code dspm:dsdferd:<c>:d:<databaseId>}: the same for a schema-less database. */
    public static String dataFlowDatabaseErd(Long clientId, String databaseId) {
        return key("dsdferd", clientId, "d:" + databaseId);
    }

    /** {@code dspm:dsdfrel:<c>:<tableId>}: a table's outgoing and incoming keys. */
    public static String dataFlowTableRelations(Long clientId, String tableId) {
        return key("dsdfrel", clientId, tableId);
    }

    // ── SaaS drawer ──────────────────────────────────────────────────────────────────────────────

    public static String saasHeader(Long clientId, String sourceId) {
        return key("dssaashead", clientId, sourceId);
    }

    /** {@code dspm:dssaasrec:<c>:<sourceId>:<view>}: one records view, also the files membership snapshot. */
    public static String saasRecords(Long clientId, String sourceId, String view) {
        return key("dssaasrec", clientId, sourceId + ":" + view);
    }

    /** {@code dspm:dssaasfiles:<c>:<sourceId>:<sha256 hex>}: one page of a data element's or class's files. */
    public static String saasFiles(Long clientId, String sourceId, SaasFileQuery query) {
        return key("dssaasfiles", clientId, sourceId + ":" + sha256Hex(query.canonicalFragment()));
    }

    public static String saasScanSummary(Long clientId, String sourceId) {
        return key("dssaassum", clientId, sourceId);
    }

    // ── SaaS file drawer ─────────────────────────────────────────────────────────────────────────

    public static String saasFileOverview(Long clientId, String fileId) {
        return key("dssfile", clientId, fileId);
    }

    public static String saasFileActivity(Long clientId, String fileId) {
        return key("dssfileact", clientId, fileId);
    }

    public static String saasFileLineage(Long clientId, String fileId) {
        return key("dssfilelin", clientId, fileId);
    }

    public static String saasFileCopies(Long clientId, String fileId) {
        return key("dssfilecopies", clientId, fileId);
    }

    // ── Endpoint drawer ──────────────────────────────────────────────────────────────────────────

    public static String endpointOverview(Long clientId, String agentId) {
        return key("dsep", clientId, agentId);
    }

    public static String endpointFilterOptions(Long clientId, String agentId) {
        return key("dsepopts", clientId, agentId);
    }

    /** {@code dspm:dsepsum:<c>:<agentId>}: the summary snapshot, which also validates {@code entityName}. */
    public static String endpointSummary(Long clientId, String agentId) {
        return key("dsepsum", clientId, agentId);
    }

    /** {@code dspm:dsepmet:<c>:<agentId>:<sha256 hex>}: the Entity View's metrics for one filter set. */
    public static String endpointMetrics(Long clientId, String agentId, EndpointFilters filters,
                                         boolean showFalsePositives) {
        return key("dsepmet", clientId,
                agentId + ":" + sha256Hex(filters.canonicalFragment() + "&fp=" + showFalsePositives));
    }

    /** {@code dspm:dsepfiles:<c>:<agentId>:<sha256 hex>}: one File View page. */
    public static String endpointFiles(Long clientId, String agentId, EndpointFileQuery query) {
        return key("dsepfiles", clientId, agentId + ":" + sha256Hex(query.canonicalFragment()));
    }

    /**
     * {@code dspm:dsepscans:<c>:<agentId>:<skip>-<limit>}: one Scan History page. The two numbers are
     * joined by {@code -}, not {@code :}, so a skip equal to another client's id never matches that
     * client's eviction glob.
     */
    public static String endpointScans(Long clientId, String agentId, int skip, int limit) {
        return key("dsepscans", clientId, agentId + ":" + skip + "-" + limit);
    }

    // ── Misconfigurations ────────────────────────────────────────────────────────────────────────

    /** {@code dspm:dsmisconf:<c>:all}: the per-policy snapshot behind the Misconfigurations page. */
    public static String misconfigurations(Long clientId) {
        return key("dsmisconf", clientId, "all");
    }

    // ── Data Lineage page ────────────────────────────────────────────────────────────────────────

    /** {@code dspm:lnopts:<c>:all}: the exposure filter options snapshot. */
    public static String lineageOptions(Long clientId) {
        return key("lnopts", clientId, "all");
    }

    /** {@code dspm:lncats:<c>:all}: the category cards with their counts. */
    public static String lineageCategories(Long clientId) {
        return key("lncats", clientId, "all");
    }

    /** {@code dspm:lnpol:<c>:<category>:<sha256 hex>}: one page of a category's policies. */
    public static String lineagePolicies(Long clientId, ExposureCategory category, LineageRowQuery query) {
        return key("lnpol", clientId, category.name() + ":" + sha256Hex(query.canonicalFragment()));
    }

    /** {@code dspm:lnds:<c>:<policyId>:<sha256 hex>}: one page of a policy's datastores. */
    public static String lineageDatastores(Long clientId, String policyId, LineageRowQuery query) {
        return key("lnds", clientId, policyId + ":" + sha256Hex(query.canonicalFragment()));
    }

    /**
     * {@code dspm:lnfiles:<c>:<exposureId>:<skip>-<limit>}: one page of an exposure's files. The two numbers
     * are joined by {@code -}, as in {@link #endpointScans}.
     */
    public static String lineageFiles(Long clientId, String exposureId, int skip, int limit) {
        return key("lnfiles", clientId, exposureId + ":" + skip + "-" + limit);
    }

    // ── Files page and Endpoint file drawer (build 3) ────────────────────────────────────────────

    /** {@code dspm:fsaas:<c>:<sha256 hex>}: one SaaS tab page; the hash covers the search, filters and cursor. */
    public static String filesSaas(Long clientId, SaasFileListQuery query) {
        return key("fsaas", clientId, sha256Hex(query.canonicalFragment()));
    }

    /** {@code dspm:fsaasopts:<c>:all}: the SaaS tab's filter options and classes per category. */
    public static String filesSaasOptions(Long clientId) {
        return key("fsaasopts", clientId, "all");
    }

    /** {@code dspm:fep:<c>:<sha256 hex>}: one Endpoint tab page. */
    public static String filesEndpoint(Long clientId, EndpointFileListQuery query) {
        return key("fep", clientId, sha256Hex(query.canonicalFragment()));
    }

    /** {@code dspm:fepopts:<c>:all}: the Endpoint tab's filter options. */
    public static String filesEndpointOptions(Long clientId) {
        return key("fepopts", clientId, "all");
    }

    /** {@code dspm:fepfile:<c>:<fileId>}: the Endpoint file drawer's overview, keyed by the validated UUID. */
    public static String endpointFileOverview(Long clientId, String fileId) {
        return key("fepfile", clientId, fileId);
    }

    /** {@code dspm:fepact:<c>:<fileId>}: the Endpoint file drawer's File Activity. */
    public static String endpointFileActivity(Long clientId, String fileId) {
        return key("fepact", clientId, fileId);
    }

    /** {@code dspm:feplin:<c>:<fileId>}: the Endpoint file drawer's Lineage asset (its own lane). */
    public static String endpointFileLineage(Long clientId, String fileId) {
        return key("feplin", clientId, fileId);
    }

    // ── build 6 ──────────────────────────────────────────────────────────────────────────────────
    // Dashboard, Document Classes, Assists, Remediation, Scan Management and the drawer extras
    // (build 6 design 5.8). Every query fragment that can hold customer text is hashed.

    /** {@code dspm:dsdash:<c>:all}: the whole Dashboard. */
    public static String dashboard(Long clientId) {
        return key("dsdash", clientId, "all");
    }

    /** {@code dspm:dsrows:<c>:<tab slug>}: one tab's collected asset rows (cache only). */
    public static String assetRows(Long clientId, DatastoreTab tab) {
        return key("dsrows", clientId, tab.slug());
    }

    /** {@code dspm:dsdocclass:<c>:all}: the document class catalogue. */
    public static String documentClasses(Long clientId) {
        return key("dsdocclass", clientId, "all");
    }

    /** {@code dspm:dsassists:<c>:all}: the Assists list, also read to name a datastore's assists. */
    public static String assists(Long clientId) {
        return key("dsassists", clientId, "all");
    }

    /** {@code dspm:dsremed:<c>:<sha256 hex>}: one Remediation activity page. */
    public static String remediation(Long clientId, RemediationQuery query) {
        return key("dsremed", clientId, sha256Hex(query.canonicalFragment()));
    }

    /**
     * {@code dspm:<dssmsched | dssmruns | dssmscopes>:<c>:<sha256 hex>}: one schedules, scan history or
     * scopes page. A run's datastores have their own factory, {@link #scanRunDatastores}.
     */
    public static String scanList(Long clientId, ScanListQuery query) {
        String kind = switch (query.kind()) {
            case SCHEDULES -> "dssmsched";
            case RUNS -> "dssmruns";
            case SCOPES -> "dssmscopes";
            case RUN_DATASTORES -> throw new IllegalArgumentException("use scanRunDatastores for a run's datastores");
        };
        return key(kind, clientId, sha256Hex(query.canonicalFragment()));
    }

    /** {@code dspm:dssmrunds:<c>:<runId>:<sha256 hex>}: one page of a run's datastores. */
    public static String scanRunDatastores(Long clientId, String runId, ScanListQuery query) {
        return key("dssmrunds", clientId, runId + ":" + sha256Hex(query.canonicalFragment()));
    }

    public static String scanSchedule(Long clientId, String scheduleId) {
        return key("dssmschedone", clientId, scheduleId);
    }

    public static String scanRun(Long clientId, String runId) {
        return key("dssmrun", clientId, runId);
    }

    public static String scanScope(Long clientId, String scopeId) {
        return key("dssmscope", clientId, scopeId);
    }

    /** {@code dspm:dssaasint:<c>:all}: the SaaS integrations derived from the SaaS rows. */
    public static String saasIntegrations(Long clientId) {
        return key("dssaasint", clientId, "all");
    }

    public static String saasConfiguration(Long clientId, String integrationId) {
        return key("dssaascfg", clientId, integrationId);
    }

    /** {@code dspm:dssaasusers:<c>:<integrationId>:<sha256 hex>}: one page of an integration's users. */
    public static String saasUsers(Long clientId, String integrationId, SaasUserQuery query) {
        return key("dssaasusers", clientId, integrationId + ":" + sha256Hex(query.canonicalFragment()));
    }

    /** {@code dspm:dssaaschan:<c>:<integrationId>:<sha256 hex>}: one page of a Slack integration's channels. */
    public static String saasChannels(Long clientId, String integrationId, SaasChannelQuery query) {
        return key("dssaaschan", clientId, integrationId + ":" + sha256Hex(query.canonicalFragment()));
    }

    /** {@code dspm:dsepcfg:<c>:all}: the endpoint agent configuration. */
    public static String endpointConfiguration(Long clientId) {
        return key("dsepcfg", clientId, "all");
    }

    public static String scanSettings(Long clientId, String sourceId) {
        return key("dsscanset", clientId, sourceId);
    }

    public static String databases(Long clientId, String sourceId) {
        return key("dsdbs", clientId, sourceId);
    }

    public static String syncStatus(Long clientId, String sourceId) {
        return key("dssync", clientId, sourceId);
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
