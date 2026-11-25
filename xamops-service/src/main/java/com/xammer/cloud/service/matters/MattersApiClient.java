package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * HTTP client for the Matters.ai All-Assets v1 API.
 *
 * <h2>Stateless by design</h2>
 * Unlike {@code UpwindApiClient}, which caches one OAuth bearer token in an instance field, this class
 * holds no credential state at all — every method takes a {@link MattersCredential}. It has to: there
 * is one key per customer, so an instance-level cached credential would be a cross-tenant bug waiting
 * to happen. Matters uses a static {@code X-API-Key} header with no token exchange, so there is
 * nothing worth caching anyway.
 *
 * <h2>Retry policy</h2>
 * Every call here runs inside a user request, which bounds what retrying can reasonably do.
 * <ul>
 *   <li><b>429</b> — honoured once, and only if the upstream {@code Retry-After} is short. The limit
 *       is 600 requests/minute <em>per key</em>, and keys are per-customer, so one customer cannot
 *       exhaust another's budget and a 429 here means this customer is genuinely hammering it. Waiting
 *       out a 30-second {@code Retry-After} on a request thread would be worse than reporting it, so
 *       anything above {@link #MAX_RETRY_WAIT_SECONDS} is passed to the caller as a 429 with the
 *       header intact.</li>
 *   <li><b>5xx and connection failures</b> — retried once after a short fixed delay. Transient vendor
 *       blips are the common case; a sustained outage should surface quickly rather than double every
 *       page load's latency. The endpoint-agent routes are the exception for 5xx: some answer 500 for
 *       an unknown agent, so their 5xx is reported at once as a bad upstream response (design 4.8);
 *       their connection failures and short 429s keep the single retry.</li>
 *   <li><b>400, 401, 403, 404</b> — never retried. None of them become true by trying again.</li>
 * </ul>
 *
 * <h2>What is never logged</h2>
 * {@link MattersCredential#apiKey()}, and any query string. Query strings carry customer search terms
 * and filter selections; the audit trail records the path only, for the same reason.
 */
@Component
@Slf4j
public class MattersApiClient {

    /** API version prefix. Every path below is relative to this, except the hash-lineage routes. */
    private static final String API_PREFIX = "/api/v1";

    /** Prefix of the v2 routes: hash lineage, endpoint lineage and the SaaS Files list. */
    private static final String API_V2_PREFIX = "/api/v2";

    public static final String PATH_ASSETS = API_PREFIX + "/assets";
    public static final String PATH_FILTERS = API_PREFIX + "/assets/filters";
    public static final String PATH_SUMMARY = API_PREFIX + "/assets/summary";

    // Console routes behind the XamLens datastore drawer. Keyed by the row's 24-hex source_id.
    public static final String PATH_INVENTORY_OVERVIEW = API_PREFIX + "/data/inventory/overview";
    public static final String PATH_INVENTORY_SENSITIVES = API_PREFIX + "/data/inventory/sensitives";
    public static final String PATH_INVENTORY_SENSITIVE_ENTITY = API_PREFIX + "/data/inventory/sensitives/entity";
    public static final String PATH_INVENTORY_ISSUES = API_PREFIX + "/data/inventory/issues/overview";
    public static final String PATH_INVENTORY_ACCESS_GRAPH = API_PREFIX + "/data/inventory/access_graph/overview";
    public static final String PATH_SCAN_STATUS = API_PREFIX + "/data/security/inventory/scan/status";
    /** Followed by {@code {sourceId}/history}. */
    public static final String PATH_SCAN_HISTORY_PREFIX = API_PREFIX + "/data/security/scan/asset/";
    /**
     * Followed by {@code {sourceId}/detail}, {@code /scan-summary}, {@code /sensitives/records} or
     * {@code /files}.
     */
    public static final String PATH_SAAS_ASSET_PREFIX = API_PREFIX + "/data/security/saas/asset/";

    // Console routes behind the SaaS file drawer. Keyed by the file's 24-hex id from the SaaS file list.
    /** Followed by {@code {fileId}/overview}. */
    public static final String PATH_SAAS_INVENTORY_PREFIX = API_PREFIX + "/data/security/saas/inventory/";
    /** Followed by {@code {fileId}/logs}. */
    public static final String PATH_REMEDIATION_OBJECTS_PREFIX = API_PREFIX + "/remediation/objects/";
    public static final String PATH_HASH_LINEAGE_COPIES = API_V2_PREFIX + "/hash-lineage/copies";
    public static final String PATH_HASH_LINEAGE_TIMELINE = API_V2_PREFIX + "/hash-lineage/timeline";

    // Drive lineage (build 7): people of a OneDrive, SharePoint or Google Drive integration, one person's
    // files and one file's timeline. Keyed by the integration's 24-hex id, then a resource id or a doc id.
    public static final String PATH_DRIVE_LINEAGE_USERS = API_V2_PREFIX + "/drive-lineage/users";
    public static final String PATH_DRIVE_LINEAGE_USER_FILES = API_V2_PREFIX + "/drive-lineage/user-files";
    public static final String PATH_DRIVE_LINEAGE_ASSET = API_V2_PREFIX + "/drive-lineage/asset";

    // Console routes behind the Endpoint drawer. Keyed by the row's 24-hex source_id (the agent id).
    /**
     * Followed by {@code {agentId}/overview}, {@code /filter-options}, {@code /sensitive-files},
     * {@code /entity}, {@code /files} or {@code /scans}.
     */
    public static final String PATH_ENDPOINT_AGENT_PREFIX = API_PREFIX + "/endpoint-agent/agent/";

    // Console routes behind the Files page and its Endpoint file drawer (build 3). The SaaS list pages by
    // keyset cursor; the endpoint list by skip and limit. Endpoint files are keyed by their UUID.
    public static final String PATH_SAAS_SENSITIVITIES = API_V2_PREFIX + "/data/security/saas/sensitivities";
    public static final String PATH_SAAS_CATEGORIES = API_PREFIX + "/data/security/saas/categories";
    public static final String PATH_SAAS_SENSITIVITY_USERS = API_PREFIX + "/data/security/saas/sensitivities/users";
    public static final String PATH_ENDPOINT_FILES = API_PREFIX + "/endpoint-agent/agent/files";
    public static final String PATH_ENDPOINT_AGENTS_FILTER_OPTIONS = API_PREFIX + "/endpoint-agent/agents/filter-options";
    /** Followed by {@code {fileId}/overview}. */
    public static final String PATH_ENDPOINT_FILE_PREFIX = API_PREFIX + "/endpoint-agent/files/";
    public static final String PATH_ENDPOINT_LINEAGE_ASSET = API_V2_PREFIX + "/endpoint-lineage/asset";
    /** Followed by {@code {fileId}}. */
    public static final String PATH_MIP_FILE_AUDIT_LOGS_PREFIX = API_PREFIX + "/admin/mip/file-audit-logs/";

    // Data Flow routes. They answer bare objects ({data[], pagination}) without a success flag. The
    // diagram routes ({schemas|databases}/{id}/erd) and tables/{id}/relations sit under the same prefix.
    public static final String PATH_DATABASE_SCHEMA = API_PREFIX + "/data/security/database-schema";

    /** The project's scan runs. Called only by {@link #verifyPlatformScope}; a bare {data[], pagination}. */
    public static final String PATH_SCAN_MANAGEMENT_HISTORY = API_PREFIX + "/scan-management/scan-history";

    // Console routes behind the Data Lineage page. They need the project-id header and answer a project
    // key they do not accept with 401, which here is not a key problem (build 4 design 4.2).
    /**
     * Followed by {@code filters-options}, {@code summary}, {@code policy/{policyId}/exposures} or
     * {@code {exposureId}/files}.
     */
    public static final String PATH_EXPOSURE = API_PREFIX + "/exposure";

    static final String EXPOSURE_REFUSED_MESSAGE = "Matters refused the exposure route for this key";

    /** The datastore types and statuses the console's Data Lineage page always sends. */
    private static final List<String> EXPOSURE_DATASTORE_TYPES = List.of("ONE_DRIVE", "SHARE_POINT", "ENDPOINT");
    private static final List<String> EXPOSURE_STATUSES = List.of("OPEN");

    // Build 6: the Dashboard, Document Classes, Assists and Remediation pages, Scan Management and the
    // datastore drawer's Scan Settings and sync status (design 5.5). PATH_SAAS_CATEGORIES is above (build 3).
    public static final String PATH_FETCH_ASSIST = API_PREFIX + "/data/security/fetch/assist";
    public static final String PATH_REMEDIATION_ACTIVITY = API_PREFIX + "/remediation/activity";
    /** Bare {data[], pagination} bodies without a success flag. Followed by {@code /{id}} for one schedule. */
    public static final String PATH_SCAN_MANAGEMENT_SCHEDULES = API_PREFIX + "/scan-management/schedules";
    /** Bare {data[], pagination} bodies without a success flag. Followed by {@code /{id}} for one scope. */
    public static final String PATH_SCAN_MANAGEMENT_SCOPES = API_PREFIX + "/scan-management/scan-scopes";
    /** Followed by {@code {integrationId}/scan-configuration}. */
    public static final String PATH_SAAS_PREFIX = API_PREFIX + "/data/security/saas/";
    public static final String PATH_SAAS_ASSETS_LIST = API_PREFIX + "/data/security/saas/assets/list";
    public static final String PATH_SAAS_CHANNELS_LIST = API_PREFIX + "/data/security/saas/channels/list";
    public static final String PATH_ENDPOINT_AGENT_CONFIGURATION = API_PREFIX + "/endpoint-agent/configuration";
    public static final String PATH_DEFAULT_SCAN_CONFIGURATION = API_PREFIX + "/data/scan/default/configuration";
    /** Answers 200 {@code {"success": false}} when the datastore has no sync job: not a failure. */
    public static final String PATH_DISCOVERY_JOB_STATUS = API_PREFIX + "/data/discovery/job/status/single";
    /** Followed by {@code {sourceId}/databases}. */
    public static final String PATH_DATABRICKS_ASSET_PREFIX = API_PREFIX + "/data/security/databricks/asset/";

    private static final Pattern SOURCE_ID = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern DATA_HASH = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern EXPOSURE_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern RESOURCE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern DOC_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    /** The two views of the SaaS records route. */
    private static final List<String> SAAS_VIEWS = List.of("data_elements", "document_classes");

    /**
     * Vendor 400 messages that mean "no such asset". The console routes answer an unknown id with 400
     * rather than 404, worded differently per route. The SaaS file overview says "No file found with ID …".
     * Build 6 adds the job status ("AssetId Not Found"), the SaaS scan configuration and users list ("No SaaS
     * integration found") and the Slack channels list ("Slack integration not found").
     */
    private static final List<String> NOT_FOUND_PHRASES = List.of(
            "no asset data found", "asset not found", "not inventory found", "no asset found", "no file found",
            "assetid not found", "no saas integration found", "slack integration not found");

    static final String ASSET_NOT_FOUND_MESSAGE = "Asset not found";
    static final String FORBIDDEN_SCOPE_MESSAGE = "This Matters key needs the PLATFORM scope";

    /**
     * The vendor's hard ceiling on {@code page_size}. Larger values are clamped upstream rather than
     * rejected, so clamping here too keeps our own pagination arithmetic honest.
     */
    public static final int MAX_PAGE_SIZE = 100;

    /** Largest {@code limit} sent to the console routes that page by skip and limit. */
    public static final int MAX_LIMIT = 100;

    /** Largest {@code skip} or {@code offset} sent to the console routes. */
    public static final int MAX_OFFSET = 10_000;

    /**
     * Longest {@code Retry-After} we will wait out inline. Beyond this the 429 is returned to the
     * caller, because holding a request thread longer than the browser's own 45 s deadline achieves
     * nothing except occupying a thread.
     */
    private static final int MAX_RETRY_WAIT_SECONDS = 3;

    /** Fixed delay before the single retry of a 5xx or connection failure. */
    private static final long SERVER_ERROR_RETRY_DELAY_MS = 400L;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final MattersAuditService auditService;

    public MattersApiClient(@Qualifier("mattersRestTemplate") RestTemplate restTemplate,
                            ObjectMapper objectMapper,
                            MattersAuditService auditService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    /**
     * {@code GET /api/v1/assets} — the paginated, filterable asset list.
     *
     * @param query already-validated query parameters. Multi-value entries are passed as
     *              comma-separated values, which the API treats as OR within a dimension and AND
     *              across dimensions.
     */
    public JsonNode listAssets(MattersCredential credential, Map<String, List<String>> query) {
        return get(credential, PATH_ASSETS, query);
    }

    /** {@code GET /api/v1/assets/filters} — faceted filter options with counts. */
    public JsonNode listFilters(MattersCredential credential, Map<String, List<String>> query) {
        return get(credential, PATH_FILTERS, query);
    }

    /** {@code GET /api/v1/assets/summary} — headline counts and 14-day trends. */
    public JsonNode summary(MattersCredential credential, String category) {
        Map<String, List<String>> query = (category == null || category.isBlank())
                ? Map.of()
                : Map.of("category", List.of(category));
        return get(credential, PATH_SUMMARY, query);
    }

    /**
     * {@code GET /api/v1/assets/{id}} — one asset in full, with {@code facet} and {@code objects}.
     *
     * A non-UUID id returns 404 from the vendor rather than a validation error, and an id belonging to
     * another project is indistinguishable from an unknown one — which is the correct behaviour, and
     * is why {@link MattersApiException.Reason#ASSET_NOT_FOUND} carries no hint about why.
     */
    public JsonNode getAsset(MattersCredential credential, String assetId) {
        return get(credential, PATH_ASSETS + "/" + assetId, Map.of());
    }

    /**
     * Cheapest possible authenticated call, for the admin Verify button.
     *
     * Uses {@code page_size=1} on the list rather than the summary endpoint: it exercises the same
     * auth path for a single row, and its {@code pagination.total} confirms the key resolves to a
     * project with data.
     */
    public JsonNode verify(MattersCredential credential) {
        return get(credential, PATH_ASSETS, Map.of("page_size", List.of("1")));
    }

    /**
     * Cheapest console route, for the admin Verify button's scope check.
     *
     * {@link #verify} only proves the key authenticates: the asset list answers any valid key. The console
     * routes behind the XamLens drawer also need the PLATFORM scope, and Matters answers a key without it
     * with 403, which {@link #exchange} turns into {@link MattersApiException.Reason#FORBIDDEN_SCOPE}.
     *
     * The project's scan-run list is the probe because it needs no asset id (a project with no assets
     * answers an empty page), changes nothing on the vendor side (the scan-status route refreshes stored
     * stage timings), returns a bare object with no {@code success} flag, and {@code page_size=1} keeps
     * the body to one row at most.
     */
    public JsonNode verifyPlatformScope(MattersCredential credential) {
        return get(credential, PATH_SCAN_MANAGEMENT_HISTORY, pageQuery(1, 1));
    }

    // ── console routes (datastore drawer) ────────────────────────────────────────────────────────
    //
    // These answer {"success": false, ...} with a 200 on some failures, so they pass consoleRoute=true.
    // Every id has been validated by the caller; the assertions here only catch programming errors.

    /** {@code GET /api/v1/data/inventory/overview?asset_id=}. Carries raw samples: whitelist downstream. */
    public JsonNode inventoryOverview(MattersCredential credential, String sourceId) {
        return get(credential, PATH_INVENTORY_OVERVIEW, assetQuery(sourceId), true);
    }

    /** {@code GET /api/v1/data/inventory/sensitives?asset_id=}. Carries raw samples. */
    public JsonNode inventorySensitives(MattersCredential credential, String sourceId) {
        return get(credential, PATH_INVENTORY_SENSITIVES, assetQuery(sourceId), true);
    }

    /**
     * {@code GET /api/v1/data/inventory/sensitives/entity} — one page of an entity's occurrences.
     *
     * @param hideFalsePositives sends {@code isFalsePositive=false} when true; no filter otherwise.
     */
    public JsonNode inventorySensitiveEntity(MattersCredential credential, String sourceId, String entityName,
                                             int skip, int limit, boolean hideFalsePositives) {
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("entityName is required");
        }
        Map<String, List<String>> query = assetQuery(sourceId);
        query.put("entityName", List.of(entityName));
        query.put("skip", List.of(String.valueOf(skip)));
        query.put("limit", List.of(String.valueOf(limit)));
        if (hideFalsePositives) {
            query.put("isFalsePositive", List.of("false"));
        }
        return get(credential, PATH_INVENTORY_SENSITIVE_ENTITY, query, true);
    }

    /** {@code GET /api/v1/data/inventory/issues/overview?asset_id=}. */
    public JsonNode inventoryIssues(MattersCredential credential, String sourceId) {
        return get(credential, PATH_INVENTORY_ISSUES, assetQuery(sourceId), true);
    }

    /** {@code GET /api/v1/data/inventory/access_graph/overview?asset_id=}. */
    public JsonNode inventoryAccessGraph(MattersCredential credential, String sourceId) {
        return get(credential, PATH_INVENTORY_ACCESS_GRAPH, assetQuery(sourceId), true);
    }

    /** {@code GET /api/v1/data/security/inventory/scan/status?asset_id=}. */
    public JsonNode scanStatus(MattersCredential credential, String sourceId) {
        return get(credential, PATH_SCAN_STATUS, assetQuery(sourceId), true);
    }

    /** {@code GET /api/v1/data/security/scan/asset/{sourceId}/history}. */
    public JsonNode scanHistory(MattersCredential credential, String sourceId) {
        return get(credential, PATH_SCAN_HISTORY_PREFIX + requireSourceId(sourceId) + "/history", Map.of(), true);
    }

    /** {@code GET /api/v1/data/security/saas/asset/{sourceId}/detail}. */
    public JsonNode saasAssetDetail(MattersCredential credential, String sourceId) {
        return get(credential, PATH_SAAS_ASSET_PREFIX + requireSourceId(sourceId) + "/detail", Map.of(), true);
    }

    // ── console routes (SaaS drawer and SaaS file drawer) ────────────────────────────────────────

    /** {@code GET …/saas/asset/{sourceId}/scan-summary}: supported and unsupported files, file types. */
    public JsonNode saasScanSummary(MattersCredential credential, String sourceId) {
        return get(credential, PATH_SAAS_ASSET_PREFIX + requireSourceId(sourceId) + "/scan-summary", Map.of(), true);
    }

    /** {@code GET …/saas/asset/{sourceId}/sensitives/records?view=}, view data_elements or document_classes. */
    public JsonNode saasRecords(MattersCredential credential, String sourceId, String view) {
        String path = PATH_SAAS_ASSET_PREFIX + requireSourceId(sourceId) + "/sensitives/records";
        return get(credential, path, Map.of("view", List.of(requireView(view))), true);
    }

    /**
     * {@code GET …/saas/asset/{sourceId}/files}: one page of the files behind a data element or a
     * document class. The route needs {@code limit}; the element or class goes as a plain string (GAP-20).
     */
    public JsonNode saasFiles(MattersCredential credential, String sourceId, SaasFileQuery query) {
        String path = PATH_SAAS_ASSET_PREFIX + requireSourceId(sourceId) + "/files";
        requireOffset("skip", query.skip());
        requireLimit(query.limit());
        return get(credential, path, query.toVendorParams(), true);
    }

    /** {@code GET /api/v1/data/security/saas/inventory/{fileId}/overview}. */
    public JsonNode saasFileOverview(MattersCredential credential, String fileId) {
        return get(credential, PATH_SAAS_INVENTORY_PREFIX + requireSourceId(fileId) + "/overview", Map.of(), true);
    }

    /** {@code GET /api/v1/remediation/objects/{fileId}/logs}: the File Activity tab. */
    public JsonNode remediationLogs(MattersCredential credential, String fileId) {
        return get(credential, PATH_REMEDIATION_OBJECTS_PREFIX + requireSourceId(fileId) + "/logs", Map.of(), true);
    }

    /**
     * {@code GET /api/v2/hash-lineage/copies?file_id=}: the file's content-hash copies. The id is a SaaS
     * file's 24-hex id or an endpoint file's UUID.
     */
    public JsonNode hashLineageCopies(MattersCredential credential, String fileId) {
        return get(credential, PATH_HASH_LINEAGE_COPIES, Map.of("file_id", List.of(requireFileId(fileId))), true);
    }

    /** {@code GET /api/v2/hash-lineage/timeline?data_hash=&limit=}: lanes and events of one content hash. */
    public JsonNode hashLineageTimeline(MattersCredential credential, String dataHash, int limit) {
        if (dataHash == null || !DATA_HASH.matcher(dataHash).matches()) {
            throw new IllegalArgumentException("dataHash must be 64 hexadecimal characters");
        }
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("data_hash", List.of(dataHash));
        query.put("limit", List.of(String.valueOf(requireLimit(limit))));
        return get(credential, PATH_HASH_LINEAGE_TIMELINE, query, true);
    }

    // ── console routes (Data Lineage public path, build 7) ───────────────────────────────────────
    //
    // They answer {"success": ...}, so they pass consoleRoute=true, and keep the single 5xx retry, as the
    // hash-lineage routes do. The audit records the path only, so no resource id or doc id is audited.

    /** {@code GET /api/v2/drive-lineage/users?integration_id=}: people whose drives hold scanned files. */
    public JsonNode driveLineageUsers(MattersCredential credential, String integrationId) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("integration_id", List.of(requireSourceId(integrationId)));
        return get(credential, PATH_DRIVE_LINEAGE_USERS, query, true);
    }

    /** {@code GET /api/v2/drive-lineage/user-files?integration_id=&resource_id=}: one person's files. */
    public JsonNode driveLineageUserFiles(MattersCredential credential, String integrationId, String resourceId) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("integration_id", List.of(requireSourceId(integrationId)));
        query.put("resource_id", List.of(requireResourceId(resourceId)));
        return get(credential, PATH_DRIVE_LINEAGE_USER_FILES, query, true);
    }

    /** {@code GET /api/v2/drive-lineage/asset?integration_id=&doc_id=}: one file's asset, events, edges, alerts. */
    public JsonNode driveLineageAsset(MattersCredential credential, String integrationId, String docId) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("integration_id", List.of(requireSourceId(integrationId)));
        query.put("doc_id", List.of(requireDocId(docId)));
        return get(credential, PATH_DRIVE_LINEAGE_ASSET, query, true);
    }

    // ── console routes (Endpoint drawer) ─────────────────────────────────────────────────────────
    //
    // Some of these answer 500 for an unknown agent, so they pass retryServerErrors=false: a 5xx is
    // reported at once instead of being sent twice (design 4.8).

    /** {@code GET /api/v1/endpoint-agent/agent/{agentId}/overview}. */
    public JsonNode agentOverview(MattersCredential credential, String agentId) {
        return get(credential, agentPath(agentId, "/overview"), Map.of(), true, false);
    }

    /** {@code GET …/endpoint-agent/agent/{agentId}/filter-options}. */
    public JsonNode agentFilterOptions(MattersCredential credential, String agentId) {
        return get(credential, agentPath(agentId, "/filter-options"), Map.of(), true, false);
    }

    /**
     * {@code GET …/endpoint-agent/agent/{agentId}/sensitive-files}: totals and metrics, filtered by
     * JSON-array filters.
     *
     * @param hideFalsePositives sends {@code isFalsePositive=false} when true; no filter otherwise.
     */
    public JsonNode agentSensitiveFiles(MattersCredential credential, String agentId, EndpointFilters filters,
                                        boolean hideFalsePositives) {
        String path = agentPath(agentId, "/sensitive-files");
        Map<String, List<String>> query = new LinkedHashMap<>(filters.toVendorParams(objectMapper));
        if (hideFalsePositives) {
            query.put("isFalsePositive", List.of("false"));
        }
        return get(credential, path, query, true, false);
    }

    /**
     * {@code GET …/endpoint-agent/agent/{agentId}/entity?entity_name=["…"]&offset=&limit=}: one page of
     * an entity's occurrences. Carries raw samples: whitelist downstream, never cache.
     *
     * @param hideFalsePositives sends {@code isFalsePositive=false} when true; no filter otherwise.
     */
    public JsonNode agentEntity(MattersCredential credential, String agentId, String entityName, int offset,
                                int limit, boolean hideFalsePositives) {
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("entityName is required");
        }
        String path = agentPath(agentId, "/entity");
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("entity_name", List.of(jsonArray(List.of(entityName))));
        query.put("offset", List.of(String.valueOf(requireOffset("offset", offset))));
        query.put("limit", List.of(String.valueOf(requireLimit(limit))));
        if (hideFalsePositives) {
            query.put("isFalsePositive", List.of("false"));
        }
        return get(credential, path, query, true, false);
    }

    /** {@code GET …/endpoint-agent/agent/{agentId}/files}: one page of the File View. */
    public JsonNode agentFiles(MattersCredential credential, String agentId, EndpointFileQuery query) {
        String path = agentPath(agentId, "/files");
        requireOffset("skip", query.skip());
        requireLimit(query.limit());
        return get(credential, path, query.toVendorParams(objectMapper), true, false);
    }

    /** {@code GET …/endpoint-agent/agent/{agentId}/scans?skip=&limit=}: Scan History. */
    public JsonNode agentScans(MattersCredential credential, String agentId, int skip, int limit) {
        String path = agentPath(agentId, "/scans");
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("skip", List.of(String.valueOf(requireOffset("skip", skip))));
        query.put("limit", List.of(String.valueOf(requireLimit(limit))));
        return get(credential, path, query, true, false);
    }

    // ── console routes (Files page and Endpoint file drawer) ─────────────────────────────────────
    //
    // The SaaS, endpoint-lineage and MIP routes keep the single 5xx retry; the endpoint-agent routes pass
    // retryServerErrors=false, as the Endpoint drawer's do (design 4.8).

    /**
     * {@code GET /api/v2/data/security/saas/sensitivities}: one keyset page of the SaaS Files list. Filters
     * go as JSON arrays, the document class as a plain {@code subcategory} string.
     */
    public JsonNode saasSensitivities(MattersCredential credential, SaasFileListQuery query) {
        requireLimit(query.limit());
        return get(credential, PATH_SAAS_SENSITIVITIES, query.toVendorParams(objectMapper), true);
    }

    /** {@code GET /api/v1/data/security/saas/categories}: every category with its document classes. */
    public JsonNode saasCategories(MattersCredential credential) {
        return get(credential, PATH_SAAS_CATEGORIES, Map.of(), true);
    }

    /** {@code GET /api/v1/data/security/saas/sensitivities/users}: owners, internal and external users. */
    public JsonNode saasSensitivityUsers(MattersCredential credential) {
        return get(credential, PATH_SAAS_SENSITIVITY_USERS, Map.of(), true);
    }

    /** {@code GET /api/v1/endpoint-agent/agent/files}: one page of every agent's files. */
    public JsonNode endpointFiles(MattersCredential credential, EndpointFileListQuery query) {
        requireOffset("skip", query.skip());
        requireLimit(query.limit());
        return get(credential, PATH_ENDPOINT_FILES, query.toVendorParams(objectMapper), true, false);
    }

    /** {@code GET /api/v1/endpoint-agent/agents/filter-options}: the filter values across every agent. */
    public JsonNode endpointAgentsFilterOptions(MattersCredential credential) {
        return get(credential, PATH_ENDPOINT_AGENTS_FILTER_OPTIONS, Map.of(), true, false);
    }

    /** {@code GET /api/v1/endpoint-agent/files/{fileId}/overview}. Carries raw samples: whitelist downstream. */
    public JsonNode endpointFileOverview(MattersCredential credential, String fileId) {
        return get(credential, PATH_ENDPOINT_FILE_PREFIX + requireUuid(fileId) + "/overview", Map.of(), true, false);
    }

    /** {@code GET /api/v2/endpoint-lineage/asset?file_id=}: the endpoint file's recorded activity. */
    public JsonNode endpointLineageAsset(MattersCredential credential, String fileId) {
        return get(credential, PATH_ENDPOINT_LINEAGE_ASSET, Map.of("file_id", List.of(requireUuid(fileId))), true);
    }

    /** {@code GET /api/v1/admin/mip/file-audit-logs/{fileId}}: the endpoint file's MIP tag history. */
    public JsonNode mipFileAuditLogs(MattersCredential credential, String fileId) {
        return get(credential, PATH_MIP_FILE_AUDIT_LOGS_PREFIX + requireUuid(fileId), Map.of(), true);
    }

    // ── Data Flow routes ─────────────────────────────────────────────────────────────────────────

    /** {@code GET …/database-schema/datastores/{sourceId}/databases?page=&page_size=}. */
    public JsonNode schemaDatabases(MattersCredential credential, String sourceId, int page, int pageSize) {
        return get(credential, PATH_DATABASE_SCHEMA + "/datastores/" + requireSourceId(sourceId) + "/databases",
                pageQuery(page, pageSize));
    }

    /** {@code GET …/database-schema/databases/{databaseId}/schemas?page=&page_size=}. */
    public JsonNode schemaSchemas(MattersCredential credential, String databaseId, int page, int pageSize) {
        return get(credential, PATH_DATABASE_SCHEMA + "/databases/" + requireUuid(databaseId) + "/schemas",
                pageQuery(page, pageSize));
    }

    /** {@code GET …/database-schema/schemas/{schemaId}/tables?page=&page_size=}. */
    public JsonNode schemaTables(MattersCredential credential, String schemaId, int page, int pageSize) {
        return get(credential, PATH_DATABASE_SCHEMA + "/schemas/" + requireUuid(schemaId) + "/tables",
                pageQuery(page, pageSize));
    }

    /** {@code GET …/database-schema/databases/{databaseId}/tables?page=&page_size=}, for schema-less engines. */
    public JsonNode schemaDatabaseTables(MattersCredential credential, String databaseId, int page, int pageSize) {
        return get(credential, PATH_DATABASE_SCHEMA + "/databases/" + requireUuid(databaseId) + "/tables",
                pageQuery(page, pageSize));
    }

    /** {@code GET …/database-schema/tables/{tableId}/columns?page=&page_size=}. */
    public JsonNode schemaColumns(MattersCredential credential, String tableId, int page, int pageSize) {
        return get(credential, PATH_DATABASE_SCHEMA + "/tables/" + requireUuid(tableId) + "/columns",
                pageQuery(page, pageSize));
    }

    /** {@code GET …/database-schema/schemas/{schemaId}/erd}: the schema diagram's tables and keys. */
    public JsonNode schemaErd(MattersCredential credential, String schemaId) {
        return get(credential, PATH_DATABASE_SCHEMA + "/schemas/" + requireUuid(schemaId) + "/erd", Map.of());
    }

    /** {@code GET …/database-schema/databases/{databaseId}/erd}, for schema-less engines. */
    public JsonNode databaseErd(MattersCredential credential, String databaseId) {
        return get(credential, PATH_DATABASE_SCHEMA + "/databases/" + requireUuid(databaseId) + "/erd", Map.of());
    }

    /** {@code GET …/database-schema/tables/{tableId}/relations}: the table's outgoing and incoming keys. */
    public JsonNode tableRelations(MattersCredential credential, String tableId) {
        return get(credential, PATH_DATABASE_SCHEMA + "/tables/" + requireUuid(tableId) + "/relations", Map.of());
    }

    // ── console routes (Data Lineage page) ───────────────────────────────────────────────────────
    //
    // Each sends the project-id header and maps a 401 to BAD_UPSTREAM_RESPONSE (exposureRoute=true).
    // The query keys and their order are the console's. Every id and number was validated by the caller.

    /**
     * Paging, search and the vendor ids of the three filters for {@link #exposureSummary} and
     * {@link #exposurePolicyExposures}. Empty lists send nothing; a null search sends nothing.
     */
    public record ExposureListParams(int skip, int limit, String search, List<String> severities,
                                     List<String> datastoreNames, List<String> labels) {

        public ExposureListParams {
            severities = severities == null ? List.of() : List.copyOf(severities);
            datastoreNames = datastoreNames == null ? List.of() : List.copyOf(datastoreNames);
            labels = labels == null ? List.of() : List.copyOf(labels);
        }

        /** One row, no search and no filter: a category's exposure count. */
        public static ExposureListParams count() {
            return new ExposureListParams(0, 1, null, List.of(), List.of(), List.of());
        }
    }

    /** {@code GET /api/v1/exposure/filters-options}: categories, severities, datastore names and labels. */
    public JsonNode exposureFilterOptions(MattersCredential credential) {
        return get(credential, PATH_EXPOSURE + "/filters-options", Map.of(), true, true, true);
    }

    /**
     * {@code GET /api/v1/exposure/summary}: one page of a category's policies, each one exposure.
     *
     * @param categoryId an {@link ExposureCategory} name
     */
    public JsonNode exposureSummary(MattersCredential credential, String categoryId, ExposureListParams params) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("categories", List.of(jsonArray(List.of(requireCategory(categoryId)))));
        query.put("datastore_types", List.of(jsonArray(EXPOSURE_DATASTORE_TYPES)));
        query.put("statuses", List.of(jsonArray(EXPOSURE_STATUSES)));
        putPage(query, params);
        putSearch(query, params);
        putArrayIfAny(query, "severities", params.severities());
        putArrayIfAny(query, "datastore_names", params.datastoreNames());
        putArrayIfAny(query, "labels", params.labels());
        return get(credential, PATH_EXPOSURE + "/summary", query, true, true, true);
    }

    /** {@code GET /api/v1/exposure/policy/{policyId}/exposures}: one page of a policy's affected datastores. */
    public JsonNode exposurePolicyExposures(MattersCredential credential, String policyId, ExposureListParams params) {
        String path = PATH_EXPOSURE + "/policy/" + requireUuid(policyId) + "/exposures";
        Map<String, List<String>> query = new LinkedHashMap<>();
        putPage(query, params);
        query.put("statuses", List.of(jsonArray(EXPOSURE_STATUSES)));
        putSearch(query, params);
        putArrayIfAny(query, "severities", params.severities());
        return get(credential, path, query, true, true, true);
    }

    /** {@code GET /api/v1/exposure/{exposureId}/files?skip=&limit=}: one page of an exposure's files. */
    public JsonNode exposureFiles(MattersCredential credential, String exposureId, int skip, int limit) {
        String path = PATH_EXPOSURE + "/" + requireExposureId(exposureId) + "/files";
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("skip", List.of(String.valueOf(requireOffset("skip", skip))));
        query.put("limit", List.of(String.valueOf(requireLimit(limit))));
        return get(credential, path, query, true, true, true);
    }

    private static void putPage(Map<String, List<String>> query, ExposureListParams params) {
        query.put("skip", List.of(String.valueOf(requireOffset("skip", params.skip()))));
        query.put("limit", List.of(String.valueOf(requireLimit(params.limit()))));
    }

    private static void putSearch(Map<String, List<String>> query, ExposureListParams params) {
        if (params.search() != null && !params.search().isBlank()) {
            query.put("search", List.of(params.search()));
        }
    }

    private void putArrayIfAny(Map<String, List<String>> query, String name, List<String> values) {
        if (!values.isEmpty()) {
            query.put(name, List.of(jsonArray(values)));
        }
    }

    // ── build 6: pages, Scan Management and the drawer's scan settings (design 5.5) ──────────────
    //
    // The scan-management routes answer bare objects without a success flag, so they use the 3-argument
    // get (consoleRoute=false), as the Data Flow routes do. Every id has been validated by the caller.

    /** {@code GET /api/v1/data/security/fetch/assist}: the project's assists. Whitelist downstream. */
    public JsonNode fetchAssists(MattersCredential credential) {
        return get(credential, PATH_FETCH_ASSIST, Map.of(), true);
    }

    /** {@code GET /api/v1/remediation/activity?skip=&limit=[&status=&q=&actionType=]}. */
    public JsonNode remediationActivity(MattersCredential credential, RemediationQuery query) {
        requireOffset("skip", query.skip());
        requireLimit(query.limit());
        return get(credential, PATH_REMEDIATION_ACTIVITY, query.toVendorParams(), true);
    }

    /** {@code GET /api/v1/scan-management/schedules?page=&page_size=…}. */
    public JsonNode scanSchedules(MattersCredential credential, ScanListQuery query) {
        return get(credential, PATH_SCAN_MANAGEMENT_SCHEDULES, query.toVendorParams());
    }

    /** {@code GET /api/v1/scan-management/schedules/{scheduleId}}. */
    public JsonNode scanSchedule(MattersCredential credential, String scheduleId) {
        return get(credential, PATH_SCAN_MANAGEMENT_SCHEDULES + "/" + requireScanObjectId(scheduleId), Map.of());
    }

    /** {@code GET /api/v1/scan-management/scan-history?page=&page_size=…}. */
    public JsonNode scanRuns(MattersCredential credential, ScanListQuery query) {
        return get(credential, PATH_SCAN_MANAGEMENT_HISTORY, query.toVendorParams());
    }

    /** {@code GET /api/v1/scan-management/scan-history/{runId}}. */
    public JsonNode scanRun(MattersCredential credential, String runId) {
        return get(credential, PATH_SCAN_MANAGEMENT_HISTORY + "/" + requireScanObjectId(runId), Map.of());
    }

    /** {@code GET /api/v1/scan-management/scan-history/{runId}/datastores?page=&page_size=…}. */
    public JsonNode scanRunDatastores(MattersCredential credential, String runId, ScanListQuery query) {
        return get(credential, PATH_SCAN_MANAGEMENT_HISTORY + "/" + requireScanObjectId(runId) + "/datastores",
                query.toVendorParams());
    }

    /** {@code GET /api/v1/scan-management/scan-scopes?page=&page_size=…}. */
    public JsonNode scanScopes(MattersCredential credential, ScanListQuery query) {
        return get(credential, PATH_SCAN_MANAGEMENT_SCOPES, query.toVendorParams());
    }

    /** {@code GET /api/v1/scan-management/scan-scopes/{scopeId}}. */
    public JsonNode scanScope(MattersCredential credential, String scopeId) {
        return get(credential, PATH_SCAN_MANAGEMENT_SCOPES + "/" + requireScanObjectId(scopeId), Map.of());
    }

    /** {@code GET /api/v1/data/security/saas/{integrationId}/scan-configuration}. Whitelist downstream. */
    public JsonNode saasScanConfiguration(MattersCredential credential, String integrationId) {
        return get(credential, PATH_SAAS_PREFIX + requireSourceId(integrationId) + "/scan-configuration",
                Map.of(), true);
    }

    /** {@code GET /api/v1/data/security/saas/assets/list?saasIntegrationId=&driveType=&limit=&skip=…}. */
    public JsonNode saasScanUsers(MattersCredential credential, String integrationId, SaasUserQuery query) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("saasIntegrationId", List.of(requireSourceId(integrationId)));
        params.putAll(query.toVendorParams());
        return get(credential, PATH_SAAS_ASSETS_LIST, params, true);
    }

    /** {@code GET /api/v1/data/security/saas/channels/list?saasIntegrationId=&limit=&skip=…}. */
    public JsonNode saasChannels(MattersCredential credential, String integrationId, SaasChannelQuery query) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("saasIntegrationId", List.of(requireSourceId(integrationId)));
        params.putAll(query.toVendorParams());
        return get(credential, PATH_SAAS_CHANNELS_LIST, params, true);
    }

    /** {@code GET /api/v1/endpoint-agent/configuration}. Carries bucket names and URLs: whitelist downstream. */
    public JsonNode endpointAgentConfiguration(MattersCredential credential) {
        return get(credential, PATH_ENDPOINT_AGENT_CONFIGURATION, Map.of(), true);
    }

    /** {@code GET /api/v1/data/scan/default/configuration?asset_id=}. May carry credentials: whitelist downstream. */
    public JsonNode defaultScanConfiguration(MattersCredential credential, String sourceId) {
        return get(credential, PATH_DEFAULT_SCAN_CONFIGURATION, assetQuery(sourceId), true);
    }

    /**
     * {@code GET /api/v1/data/discovery/job/status/single?asset_id=}. consoleRoute=false: the route answers
     * 200 {@code {"success": false}} when the datastore has no sync job, which the mapper reads as "no job".
     */
    public JsonNode discoveryJobStatus(MattersCredential credential, String sourceId) {
        return get(credential, PATH_DISCOVERY_JOB_STATUS, assetQuery(sourceId));
    }

    /** {@code GET /api/v1/data/security/databricks/asset/{sourceId}/databases}. */
    public JsonNode databricksDatabases(MattersCredential credential, String sourceId) {
        return get(credential, PATH_DATABRICKS_ASSET_PREFIX + requireSourceId(sourceId) + "/databases",
                Map.of(), true);
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private JsonNode get(MattersCredential credential, String path, Map<String, List<String>> query) {
        return get(credential, path, query, false);
    }

    private JsonNode get(MattersCredential credential, String path, Map<String, List<String>> query,
                         boolean consoleRoute) {
        return get(credential, path, query, consoleRoute, true);
    }

    private JsonNode get(MattersCredential credential, String path, Map<String, List<String>> query,
                         boolean consoleRoute, boolean retryServerErrors) {
        return get(credential, path, query, consoleRoute, retryServerErrors, false);
    }

    /**
     * @param consoleRoute      the route may answer 200 with {@code success:false}; that is treated as a
     *                          failure inside the audited block, so the failure row is written.
     * @param retryServerErrors false for routes that answer 5xx for an unknown id: a 5xx is then
     *                          {@code BAD_UPSTREAM_RESPONSE} after one request.
     * @param exposureRoute     a Data Lineage exposure route: sends {@code project-id} and maps a 401 to
     *                          {@code BAD_UPSTREAM_RESPONSE} rather than a key verdict.
     */
    private JsonNode get(MattersCredential credential, String path, Map<String, List<String>> query,
                         boolean consoleRoute, boolean retryServerErrors, boolean exposureRoute) {
        URI uri = buildUri(credential.baseUrl(), path, query);
        long startedAt = System.currentTimeMillis();
        try {
            JsonNode body = exchange(uri, credential, path, false, retryServerErrors, exposureRoute);
            if (consoleRoute) {
                requireSuccess(body, path);
            }
            auditService.recordCredentialUse(credential.clientId(), credential.keyId(),
                    credential.projectId(), path, System.currentTimeMillis() - startedAt);
            return body;
        } catch (MattersApiException e) {
            auditService.recordCredentialFailure(credential.clientId(), credential.keyId(),
                    credential.projectId(), path, e.getReason().name(), e.getMessage());
            throw e;
        }
    }

    /**
     * Issues the request, translating transport and status failures into
     * {@link MattersApiException}.
     *
     * @param isRetry           guards the single-retry policy — a retried call that fails again
     *                          propagates rather than recursing.
     * @param retryServerErrors false: a 5xx is not retried. 429 and connection failures are unaffected.
     * @param exposureRoute     sends {@code project-id}; a 401 is {@code BAD_UPSTREAM_RESPONSE}, not a key verdict.
     */
    private JsonNode exchange(URI uri, MattersCredential credential, String path, boolean isRetry,
                              boolean retryServerErrors, boolean exposureRoute) {
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(authHeaders(credential, exposureRoute)), String.class);
            return parse(response.getBody(), path);

        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();

            if (status.value() == 429) {
                int retryAfter = retryAfterSeconds(e);
                if (!isRetry && retryAfter > 0 && retryAfter <= MAX_RETRY_WAIT_SECONDS) {
                    sleep(retryAfter * 1000L);
                    return exchange(uri, credential, path, true, retryServerErrors, exposureRoute);
                }
                throw new MattersApiException(MattersApiException.Reason.RATE_LIMITED,
                        "Matters rate limit exceeded for this organisation's API key",
                        retryAfter, e);
            }

            if (status.value() == 401) {
                if (exposureRoute) {
                    // The same key gets 200 elsewhere: the route is closed to it, the key is not bad.
                    throw new MattersApiException(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE,
                            EXPOSURE_REFUSED_MESSAGE, e);
                }
                throw unauthorized(e);
            }
            if (status.value() == 403) {
                throw new MattersApiException(MattersApiException.Reason.FORBIDDEN_SCOPE,
                        FORBIDDEN_SCOPE_MESSAGE, e);
            }
            if (status.value() == 404) {
                throw new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND,
                        ASSET_NOT_FOUND_MESSAGE, e);
            }
            // The console routes answer an unknown asset id with 400 and a not-found message. Any other
            // 400 falls through to BAD_UPSTREAM_RESPONSE below. Neither is retried.
            if (status.value() == 400 && isNotFoundMessage(vendorMessage(e))) {
                throw new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND,
                        ASSET_NOT_FOUND_MESSAGE, e);
            }

            if (status.is5xxServerError()) {
                if (!retryServerErrors) {
                    // The endpoint-agent routes answer 500 for an unknown agent; asking twice changes nothing.
                    throw new MattersApiException(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE,
                            "Matters returned " + status.value() + " for " + path, e);
                }
                if (!isRetry) {
                    sleep(SERVER_ERROR_RETRY_DELAY_MS);
                    return exchange(uri, credential, path, true, retryServerErrors, exposureRoute);
                }
                throw new MattersApiException(MattersApiException.Reason.UPSTREAM_UNAVAILABLE,
                        "Matters returned " + status.value() + " for " + path, e);
            }

            // 4xx we do not recognise. Reported as a bad upstream response rather than mapped onto a
            // credential problem — guessing here is how a working key gets marked invalid.
            throw new MattersApiException(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE,
                    "Matters returned an unexpected " + status.value() + " for " + path, e);

        } catch (ResourceAccessException e) {
            // Connect timeout, read timeout, DNS or TLS failure — the tenant is unreachable.
            if (!isRetry) {
                sleep(SERVER_ERROR_RETRY_DELAY_MS);
                return exchange(uri, credential, path, true, retryServerErrors, exposureRoute);
            }
            throw new MattersApiException(MattersApiException.Reason.UPSTREAM_UNAVAILABLE,
                    "Could not reach Matters: " + e.getMostSpecificCause().getMessage(), e);
        }
    }

    /**
     * Distinguishes the 401 causes, which the vendor separates by message.
     *
     * Worth the string matching: "wrong environment" means the base URL is wrong and the key is fine,
     * while the others mean the key needs rotating. Sending an operator to rotate a perfectly good key
     * is the failure this avoids. An unrecognised message falls back to INVALID_KEY, which is the
     * common case.
     */
    private MattersApiException unauthorized(RestClientResponseException e) {
        String message = vendorMessage(e).toLowerCase(Locale.ROOT);
        if (message.contains("different environment")) {
            return new MattersApiException(MattersApiException.Reason.WRONG_ENVIRONMENT,
                    "The API key was issued for a different Matters environment than the configured "
                            + "base URL", e);
        }
        if (message.contains("expired") || message.contains("revoked")) {
            return new MattersApiException(MattersApiException.Reason.KEY_EXPIRED,
                    "The Matters API key has been revoked or has expired", e);
        }
        return new MattersApiException(MattersApiException.Reason.INVALID_KEY,
                "Matters rejected the API key", e);
    }

    /** Pulls the message out of the vendor's {@code {"success":false,"message":"..."}} error body. */
    private String vendorMessage(RestClientResponseException e) {
        try {
            return vendorMessage(objectMapper.readTree(e.getResponseBodyAsString()));
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * {@code message}, then {@code Message} (the console routes capitalise it), then {@code error}.
     * Used only for classification; never copied into an exception message.
     */
    private static String vendorMessage(JsonNode body) {
        if (body == null) {
            return "";
        }
        for (String field : List.of("message", "Message", "error")) {
            JsonNode value = body.path(field);
            if (value.isTextual()) {
                return value.asText();
            }
        }
        return "";
    }

    private static boolean isNotFoundMessage(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return NOT_FOUND_PHRASES.stream().anyMatch(lower::contains);
    }

    /** A console-route body with {@code success: false} is a failure, whatever the HTTP status said. */
    private static void requireSuccess(JsonNode body, String path) {
        JsonNode success = body.path("success");
        if (success.isBoolean() && !success.booleanValue()) {
            if (isNotFoundMessage(vendorMessage(body))) {
                throw new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND, ASSET_NOT_FOUND_MESSAGE);
            }
            throw new MattersApiException(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE,
                    "Matters answered success:false for " + path);
        }
    }

    private int retryAfterSeconds(RestClientResponseException e) {
        HttpHeaders headers = e.getResponseHeaders();
        if (headers == null) {
            return 0;
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ex) {
            // Retry-After may also be an HTTP date. Not parsed: the vendor documents and sends
            // seconds, and a wrong guess here would mean sleeping for an arbitrary period.
            return 0;
        }
    }

    private JsonNode parse(String body, String path) {
        if (body == null || body.isBlank()) {
            throw new MattersApiException(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE,
                    "Matters returned an empty body for " + path);
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new MattersApiException(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE,
                    "Matters returned a body that could not be parsed for " + path, e);
        }
    }

    private HttpHeaders authHeaders(MattersCredential credential, boolean exposureRoute) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", credential.apiKey());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        // Identifies us in the vendor's logs, which matters when diagnosing a rate limit with them.
        headers.set(HttpHeaders.USER_AGENT, "XamOps/1.0");
        if (exposureRoute) {
            // The exposure routes answer 400 "project-id header required" without it.
            String projectId = credential.projectId();
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalStateException("credential has no project id");
            }
            headers.set("project-id", projectId);
        }
        return headers;
    }

    /**
     * Builds the URI, encoding every query value strictly.
     *
     * Template encoding ({@code queryParam(..).encode()}) leaves {@code +}, {@code @} and the other
     * sub-delimiters literal, and a server that decodes the query as form data reads a literal {@code +}
     * as a space. So each value is encoded here with {@link #encodeValue}, and the already-encoded
     * components are assembled with {@code build(true)} so nothing is encoded twice.
     */
    private URI buildUri(String baseUrl, String path, Map<String, List<String>> query) {
        // Base URL and path: encoded as before. The path is a constant plus an id validated by the caller.
        URI base = UriComponentsBuilder.fromUriString(baseUrl).path(path).encode().build().toUri();
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(base);
        query.forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                builder.queryParam(key, values.stream().map(MattersApiClient::encodeValue).toArray());
            }
        });
        return builder.build(true).toUri();   // every component is already encoded; do not encode again
    }

    /** UriUtils.encode leaves only unreserved characters literal; commas stay literal as the value separator. */
    static String encodeValue(String joined) {
        return Arrays.stream(joined.split(",", -1))
                .map(part -> UriUtils.encode(part, StandardCharsets.UTF_8))
                .collect(Collectors.joining(","));
    }

    /**
     * A JSON array of strings, as the endpoint-agent routes take their filters: {@code ["EMAIL ADDRESS"]}.
     * {@link #encodeValue} then percent-encodes it, leaving each comma literal, and a literal comma
     * decodes to itself, so the vendor reads exactly this text.
     */
    private String jsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a JSON array of strings", e);
        }
    }

    private static String agentPath(String agentId, String route) {
        return PATH_ENDPOINT_AGENT_PREFIX + requireSourceId(agentId) + route;
    }

    private static String requireView(String view) {
        if (view == null || !SAAS_VIEWS.contains(view)) {
            throw new IllegalArgumentException("view must be data_elements or document_classes");
        }
        return view;
    }

    private static int requireLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be from 1 to " + MAX_LIMIT);
        }
        return limit;
    }

    private static int requireOffset(String name, int value) {
        if (value < 0 || value > MAX_OFFSET) {
            throw new IllegalArgumentException(name + " must be from 0 to " + MAX_OFFSET);
        }
        return value;
    }

    private static Map<String, List<String>> assetQuery(String sourceId) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("asset_id", List.of(requireSourceId(sourceId)));
        return query;
    }

    private static Map<String, List<String>> pageQuery(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be at least 1 and pageSize from 1 to " + MAX_PAGE_SIZE);
        }
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("page", List.of(String.valueOf(page)));
        query.put("page_size", List.of(String.valueOf(pageSize)));
        return query;
    }

    private static String requireSourceId(String sourceId) {
        if (sourceId == null || !SOURCE_ID.matcher(sourceId).matches()) {
            throw new IllegalArgumentException("sourceId must be 24 hexadecimal characters");
        }
        return sourceId;
    }

    /** A file id as the lineage routes take it: the SaaS list's 24 hex or the endpoint list's UUID. */
    private static String requireFileId(String fileId) {
        if (fileId == null || !(SOURCE_ID.matcher(fileId).matches() || UUID.matcher(fileId).matches())) {
            throw new IllegalArgumentException("fileId must be 24 hexadecimal characters or a UUID");
        }
        return fileId;
    }

    private static String requireResourceId(String resourceId) {
        if (resourceId == null || !RESOURCE_ID.matcher(resourceId).matches()) {
            throw new IllegalArgumentException("resourceId must be 1 to 64 letters, digits, '_' or '-'");
        }
        return resourceId;
    }

    private static String requireDocId(String docId) {
        if (docId == null || !DOC_ID.matcher(docId).matches()) {
            throw new IllegalArgumentException("docId must be 1 to 128 letters, digits, '_' or '-'");
        }
        return docId;
    }

    private static String requireExposureId(String exposureId) {
        if (exposureId == null || !EXPOSURE_ID.matcher(exposureId).matches()) {
            throw new IllegalArgumentException("exposureId must be 1 to 64 letters, digits or hyphens");
        }
        return exposureId;
    }

    private static String requireCategory(String categoryId) {
        if (categoryId == null
                || Arrays.stream(ExposureCategory.values()).noneMatch(c -> c.name().equals(categoryId))) {
            throw new IllegalArgumentException("categoryId must be an exposure category");
        }
        return categoryId;
    }

    private static String requireUuid(String id) {
        if (id == null || !UUID.matcher(id).matches()) {
            throw new IllegalArgumentException("Data Flow id must be a UUID");
        }
        return id;
    }

    /** A schedule, run or scope id: 24 hex characters (legacy schedules) or a canonical UUID (runs). */
    private static String requireScanObjectId(String id) {
        if (id == null || !(SOURCE_ID.matcher(id).matches() || UUID.matcher(id).matches())) {
            throw new IllegalArgumentException("scan object id must be 24 hexadecimal characters or a UUID");
        }
        return id;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // Restore the flag rather than swallowing it, so a shutdown interrupt is not lost and
            // whatever is above this on the stack can still observe it.
            Thread.currentThread().interrupt();
            throw new MattersApiException(MattersApiException.Reason.UPSTREAM_UNAVAILABLE,
                    "Interrupted while waiting to retry a Matters request", e);
        }
    }
}
