package com.xammer.cloud.service.matters;

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
 *       page load's latency.</li>
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

    /** API version prefix. Every path below is relative to this. */
    private static final String API_PREFIX = "/api/v1";

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
    /** Followed by {@code {sourceId}/detail}. */
    public static final String PATH_SAAS_ASSET_PREFIX = API_PREFIX + "/data/security/saas/asset/";

    // Data Flow routes. They answer bare objects ({data[], pagination}) without a success flag.
    public static final String PATH_DATABASE_SCHEMA = API_PREFIX + "/data/security/database-schema";

    /** The project's scan runs. Called only by {@link #verifyPlatformScope}; a bare {data[], pagination}. */
    public static final String PATH_SCAN_MANAGEMENT_HISTORY = API_PREFIX + "/scan-management/scan-history";

    private static final Pattern SOURCE_ID = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * Vendor 400 messages that mean "no such asset". The console routes answer an unknown id with 400
     * rather than 404, worded differently per route.
     */
    private static final List<String> NOT_FOUND_PHRASES =
            List.of("no asset data found", "asset not found", "not inventory found", "no asset found");

    static final String ASSET_NOT_FOUND_MESSAGE = "Asset not found";
    static final String FORBIDDEN_SCOPE_MESSAGE = "This Matters key needs the PLATFORM scope";

    /**
     * The vendor's hard ceiling on {@code page_size}. Larger values are clamped upstream rather than
     * rejected, so clamping here too keeps our own pagination arithmetic honest.
     */
    public static final int MAX_PAGE_SIZE = 100;

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

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private JsonNode get(MattersCredential credential, String path, Map<String, List<String>> query) {
        return get(credential, path, query, false);
    }

    /**
     * @param consoleRoute the route may answer 200 with {@code success:false}; that is treated as a
     *                     failure inside the audited block, so the failure row is written.
     */
    private JsonNode get(MattersCredential credential, String path, Map<String, List<String>> query,
                         boolean consoleRoute) {
        URI uri = buildUri(credential.baseUrl(), path, query);
        long startedAt = System.currentTimeMillis();
        try {
            JsonNode body = exchange(uri, credential, path, false);
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
     * @param isRetry guards the single-retry policy — a retried call that fails again propagates
     *                rather than recursing.
     */
    private JsonNode exchange(URI uri, MattersCredential credential, String path, boolean isRetry) {
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(authHeaders(credential)), String.class);
            return parse(response.getBody(), path);

        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();

            if (status.value() == 429) {
                int retryAfter = retryAfterSeconds(e);
                if (!isRetry && retryAfter > 0 && retryAfter <= MAX_RETRY_WAIT_SECONDS) {
                    sleep(retryAfter * 1000L);
                    return exchange(uri, credential, path, true);
                }
                throw new MattersApiException(MattersApiException.Reason.RATE_LIMITED,
                        "Matters rate limit exceeded for this organisation's API key",
                        retryAfter, e);
            }

            if (status.value() == 401) {
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
                if (!isRetry) {
                    sleep(SERVER_ERROR_RETRY_DELAY_MS);
                    return exchange(uri, credential, path, true);
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
                return exchange(uri, credential, path, true);
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

    private HttpHeaders authHeaders(MattersCredential credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", credential.apiKey());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        // Identifies us in the vendor's logs, which matters when diagnosing a rate limit with them.
        headers.set(HttpHeaders.USER_AGENT, "XamOps/1.0");
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

    private static String requireUuid(String id) {
        if (id == null || !UUID.matcher(id).matches()) {
            throw new IllegalArgumentException("Data Flow id must be a UUID");
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
