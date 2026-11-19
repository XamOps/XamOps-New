package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link MattersApiClient} against a {@link MockRestServiceServer}: nothing leaves the JVM.
 * Every expectation also asserts the request method is GET.
 */
class MattersApiClientTest {

    private static final String BASE = "https://matters.test";
    private static final String SOURCE_ID = "0123456789abcdef01234567";
    private static final String UUID = "0000000a-0000-4000-8000-00000000000b";
    private static final String FILE_ID = "fedcba9876543210fedcba98";
    private static final String DATA_HASH = "0a1b2c3d".repeat(8);
    private static final String OK = "{\"success\":true,\"data\":[]}";

    private final MattersCredential credential =
            new MattersCredential(10L, BASE, "synthetic-key", "k1", "p1");

    private MockRestServiceServer server;
    private MattersAuditService audit;
    private MattersApiClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        audit = mock(MattersAuditService.class);
        client = new MattersApiClient(restTemplate, new ObjectMapper(), audit);
    }

    private ResponseActions expectGet(String url) {
        return server.expect(ExpectedCount.once(), requestTo(url)).andExpect(method(HttpMethod.GET));
    }

    private ResponseActions expectGet(int times, String url) {
        return server.expect(ExpectedCount.times(times), requestTo(url)).andExpect(method(HttpMethod.GET));
    }

    private static String overviewUrl() {
        return BASE + "/api/v1/data/inventory/overview?asset_id=" + SOURCE_ID;
    }

    private void assertReason(Runnable call, MattersApiException.Reason reason) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(MattersApiException.class, e -> assertThat(e.getReason()).isEqualTo(reason));
    }

    // ── 400 ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("400 with Message 'No asset Data found' is ASSET_NOT_FOUND after exactly one request")
    void notFoundUnderCapitalisedMessage() {
        expectGet(overviewUrl()).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\": false, \"Message\": \"No asset Data found\"}"));

        assertThatThrownBy(() -> client.inventoryOverview(credential, SOURCE_ID))
                .isInstanceOfSatisfying(MattersApiException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.ASSET_NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Asset not found");
                });
        server.verify();
        verify(audit).recordCredentialFailure(eq(10L), eq("k1"), eq("p1"),
                eq("/api/v1/data/inventory/overview"), eq("ASSET_NOT_FOUND"), anyString());
    }

    @Test
    @DisplayName("400 'Asset Not Found' under message and 'Not Inventory Found' under error are ASSET_NOT_FOUND")
    void otherNotFoundPhrases() {
        expectGet(overviewUrl()).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"success\":false,\"message\":\"Asset Not Found\"}"));
        assertReason(() -> client.inventoryOverview(credential, SOURCE_ID), MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();

        server.reset();
        expectGet(overviewUrl()).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"success\":false,\"error\":\"Not Inventory Found\"}"));
        assertReason(() -> client.inventoryOverview(credential, SOURCE_ID), MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();

        server.reset();
        expectGet(overviewUrl()).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"no asset found for id\"}"));
        assertReason(() -> client.inventoryOverview(credential, SOURCE_ID), MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();
    }

    @Test
    @DisplayName("any other 400 stays BAD_UPSTREAM_RESPONSE, is not retried and does not echo the vendor text")
    void otherBadRequest() {
        expectGet(overviewUrl()).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"success\":false,\"message\":\"needle invalid\"}"));
        assertThatThrownBy(() -> client.inventoryOverview(credential, SOURCE_ID))
                .isInstanceOfSatisfying(MattersApiException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE);
                    assertThat(e.getMessage()).doesNotContain("needle");
                });
        server.verify();
    }

    // ── 401 / 403 / 404 ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("401 variants are classified as before")
    void unauthorizedVariants() {
        Map<String, MattersApiException.Reason> cases = new LinkedHashMap<>();
        cases.put("{\"message\":\"API key was issued for a different environment\"}",
                MattersApiException.Reason.WRONG_ENVIRONMENT);
        cases.put("{\"message\":\"API key has expired\"}", MattersApiException.Reason.KEY_EXPIRED);
        cases.put("{\"error\":\"API key revoked\"}", MattersApiException.Reason.KEY_EXPIRED);
        cases.put("{\"message\":\"Invalid API key\"}", MattersApiException.Reason.INVALID_KEY);
        cases.forEach((body, reason) -> {
            server.reset();
            expectGet(BASE + "/api/v1/assets?page_size=1").andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON).body(body));
            assertReason(() -> client.verify(credential), reason);
            server.verify();
        });
    }

    @Test
    @DisplayName("403 is FORBIDDEN_SCOPE with the PLATFORM message after one request, and answers 403")
    void forbiddenScope() {
        expectGet(overviewUrl()).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"message\":\"API key not permitted for this action\"}"));
        assertThatThrownBy(() -> client.inventoryOverview(credential, SOURCE_ID))
                .isInstanceOfSatisfying(MattersApiException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.FORBIDDEN_SCOPE);
                    assertThat(e.getMessage()).isEqualTo("This Matters key needs the PLATFORM scope");
                });
        server.verify();
        assertThat(MattersApiException.Reason.FORBIDDEN_SCOPE.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(MattersApiException.Reason.FORBIDDEN_SCOPE.status().value()).isEqualTo(403);
        assertThat(MattersApiException.Reason.FORBIDDEN_SCOPE.connectionStatus())
                .isEqualTo(com.xammer.cloud.domain.MattersProjectMapping.STATUS_FORBIDDEN);
    }

    @Test
    void notFound404() {
        expectGet(BASE + "/api/v1/assets/" + UUID).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertReason(() -> client.getAsset(credential, UUID), MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();
    }

    // ── 429 / 5xx / bodies ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("429 with Retry-After 2 is retried once and then succeeds")
    void shortRateLimitRetriedOnce() {
        expectGet(overviewUrl()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "2"));
        expectGet(overviewUrl()).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        assertThat(client.inventoryOverview(credential, SOURCE_ID).path("success").asBoolean()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("429 with Retry-After 30 is RATE_LIMITED carrying 30 after one request")
    void longRateLimitNotRetried() {
        expectGet(overviewUrl()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "30"));
        assertThatThrownBy(() -> client.inventoryOverview(credential, SOURCE_ID))
                .isInstanceOfSatisfying(MattersApiException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.RATE_LIMITED);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(30);
                });
        server.verify();
    }

    @Test
    @DisplayName("5xx is retried once, then UPSTREAM_UNAVAILABLE")
    void serverErrorRetriedOnce() {
        expectGet(2, overviewUrl()).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertReason(() -> client.inventoryOverview(credential, SOURCE_ID),
                MattersApiException.Reason.UPSTREAM_UNAVAILABLE);
        server.verify();

        server.reset();
        expectGet(overviewUrl()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectGet(overviewUrl()).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        assertThat(client.inventoryOverview(credential, SOURCE_ID)).isNotNull();
        server.verify();
    }

    @Test
    void unparseableAndEmptyBodies() {
        expectGet(overviewUrl()).andRespond(withSuccess("not json {", MediaType.APPLICATION_JSON));
        assertReason(() -> client.inventoryOverview(credential, SOURCE_ID),
                MattersApiException.Reason.BAD_UPSTREAM_RESPONSE);
        server.verify();

        server.reset();
        expectGet(overviewUrl()).andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertReason(() -> client.inventoryOverview(credential, SOURCE_ID),
                MattersApiException.Reason.BAD_UPSTREAM_RESPONSE);
        server.verify();
    }

    @Test
    @DisplayName("200 with success:false on a console route fails inside the audited block")
    void consoleRouteSuccessFalse() {
        expectGet(overviewUrl()).andRespond(withSuccess("{\"success\":false,\"message\":\"needle broke\"}",
                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.inventoryOverview(credential, SOURCE_ID))
                .isInstanceOfSatisfying(MattersApiException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE);
                    assertThat(e.getMessage()).isEqualTo(
                            "Matters answered success:false for /api/v1/data/inventory/overview");
                });
        server.verify();
        verify(audit).recordCredentialFailure(eq(10L), eq("k1"), eq("p1"),
                eq("/api/v1/data/inventory/overview"), eq("BAD_UPSTREAM_RESPONSE"), anyString());
        verify(audit, never()).recordCredentialUse(eq(10L), anyString(), anyString(), anyString(), anyLong());

        server.reset();
        expectGet(overviewUrl()).andRespond(withSuccess("{\"success\":false,\"Message\":\"No asset Data found\"}",
                MediaType.APPLICATION_JSON));
        assertReason(() -> client.inventoryOverview(credential, SOURCE_ID), MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();
    }

    @Test
    @DisplayName("success:false is not checked on the asset list or the Data Flow routes")
    void nonConsoleRoutesIgnoreSuccessFlag() {
        expectGet(BASE + "/api/v1/assets").andRespond(withSuccess("{\"success\":false}", MediaType.APPLICATION_JSON));
        expectGet(BASE + "/api/v1/data/security/database-schema/tables/" + UUID + "/columns?page=1&page_size=100")
                .andRespond(withSuccess("{\"success\":false}", MediaType.APPLICATION_JSON));
        assertThat(client.listAssets(credential, Map.of()).path("success").asBoolean(true)).isFalse();
        assertThat(client.schemaColumns(credential, UUID, 1, 100)).isNotNull();
        server.verify();
    }

    // ── routes ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("each new method hits its path and query with GET, and the audit path has no query")
    void everyNewMethodHitsItsRoute() {
        String schema = BASE + "/api/v1/data/security/database-schema";
        Map<String, Function<MattersApiClient, JsonNode>> calls = new LinkedHashMap<>();
        calls.put(BASE + "/api/v1/data/inventory/overview?asset_id=" + SOURCE_ID,
                c -> c.inventoryOverview(credential, SOURCE_ID));
        calls.put(BASE + "/api/v1/data/inventory/sensitives?asset_id=" + SOURCE_ID,
                c -> c.inventorySensitives(credential, SOURCE_ID));
        calls.put(BASE + "/api/v1/data/inventory/sensitives/entity?asset_id=" + SOURCE_ID
                        + "&entityName=PERSON%20NAME&skip=20&limit=10&isFalsePositive=false",
                c -> c.inventorySensitiveEntity(credential, SOURCE_ID, "PERSON NAME", 20, 10, true));
        calls.put(BASE + "/api/v1/data/inventory/sensitives/entity?asset_id=" + SOURCE_ID
                        + "&entityName=SHA%20KEY&skip=0&limit=100",
                c -> c.inventorySensitiveEntity(credential, SOURCE_ID, "SHA KEY", 0, 100, false));
        calls.put(BASE + "/api/v1/data/inventory/issues/overview?asset_id=" + SOURCE_ID,
                c -> c.inventoryIssues(credential, SOURCE_ID));
        calls.put(BASE + "/api/v1/data/inventory/access_graph/overview?asset_id=" + SOURCE_ID,
                c -> c.inventoryAccessGraph(credential, SOURCE_ID));
        calls.put(BASE + "/api/v1/data/security/inventory/scan/status?asset_id=" + SOURCE_ID,
                c -> c.scanStatus(credential, SOURCE_ID));
        calls.put(BASE + "/api/v1/data/security/scan/asset/" + SOURCE_ID + "/history",
                c -> c.scanHistory(credential, SOURCE_ID));
        calls.put(BASE + "/api/v1/data/security/saas/asset/" + SOURCE_ID + "/detail",
                c -> c.saasAssetDetail(credential, SOURCE_ID));
        calls.put(schema + "/datastores/" + SOURCE_ID + "/databases?page=1&page_size=100",
                c -> c.schemaDatabases(credential, SOURCE_ID, 1, 100));
        calls.put(schema + "/databases/" + UUID + "/schemas?page=2&page_size=100",
                c -> c.schemaSchemas(credential, UUID, 2, 100));
        calls.put(schema + "/schemas/" + UUID + "/tables?page=3&page_size=100",
                c -> c.schemaTables(credential, UUID, 3, 100));
        calls.put(schema + "/databases/" + UUID + "/tables?page=1&page_size=50",
                c -> c.schemaDatabaseTables(credential, UUID, 1, 50));
        calls.put(schema + "/tables/" + UUID + "/columns?page=4&page_size=100",
                c -> c.schemaColumns(credential, UUID, 4, 100));
        calls.put(BASE + "/api/v1/scan-management/scan-history?page=1&page_size=1",
                c -> c.verifyPlatformScope(credential));

        calls.keySet().forEach(url -> expectGet(url).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON)));
        calls.values().forEach(call -> assertThat(call.apply(client).path("success").asBoolean()).isTrue());
        server.verify();

        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        verify(audit, atLeastOnce()).recordCredentialUse(eq(10L), eq("k1"), eq("p1"), paths.capture(), anyLong());
        assertThat(paths.getAllValues()).hasSize(calls.size()).allSatisfy(p -> assertThat(p).doesNotContain("?"));
        assertThat(paths.getAllValues()).contains(
                "/api/v1/data/inventory/sensitives/entity",
                "/api/v1/data/security/scan/asset/" + SOURCE_ID + "/history",
                "/api/v1/data/security/database-schema/tables/" + UUID + "/columns");
    }

    @Test
    @DisplayName("ids are asserted before any request is made")
    void idAssertions() {
        assertThatThrownBy(() -> client.inventoryOverview(credential, "not-hex"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.scanHistory(credential, SOURCE_ID + "/../x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.schemaTables(credential, "not-a-uuid", 1, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.schemaDatabases(credential, UUID, 1, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.inventorySensitiveEntity(credential, SOURCE_ID, " ", 0, 10, false))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    // ── encoding ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("query values are percent-encoded strictly; commas between values stay literal")
    void strictEncoding() {
        expectGet(BASE + "/api/v1/assets?user=a%2Bb%40example.com")
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        expectGet(BASE + "/api/v1/assets?q=a%20b").andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        expectGet(BASE + "/api/v1/assets?provider=EBS,S3").andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        expectGet(BASE + "/api/v1/assets?q=50%25").andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        expectGet(BASE + "/api/v1/assets?category=on_prem&sort=-severity&page_size=10")
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        client.listAssets(credential, Map.of("user", List.of("a+b@example.com")));
        client.listAssets(credential, Map.of("q", List.of("a b")));
        client.listAssets(credential, Map.of("provider", List.of("EBS,S3")));
        client.listAssets(credential, Map.of("q", List.of("50%")));
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        ordered.put("category", List.of("on_prem"));
        ordered.put("sort", List.of("-severity"));
        ordered.put("page_size", List.of("10"));
        client.listAssets(credential, ordered);
        server.verify();

        assertThat(MattersApiClient.encodeValue("a b,c&d")).isEqualTo("a%20b,c%26d");
    }

    @Test
    @DisplayName("a path with a 24-hex id and asset_id= are unchanged by the stricter encoding")
    void idsUnchanged() {
        expectGet(BASE + "/api/v1/data/security/scan/asset/" + SOURCE_ID + "/history")
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        expectGet(overviewUrl()).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        client.scanHistory(credential, SOURCE_ID);
        client.inventoryOverview(credential, SOURCE_ID);
        server.verify();
    }

    // ── SaaS, endpoint and diagram routes ────────────────────────────────────────────────────────

    private static String saasUrl(String route) {
        return BASE + "/api/v1/data/security/saas/asset/" + SOURCE_ID + route;
    }

    private static String agentUrl(String route) {
        return BASE + "/api/v1/endpoint-agent/agent/" + SOURCE_ID + route;
    }

    private static String schemaUrl(String route) {
        return BASE + "/api/v1/data/security/database-schema" + route;
    }

    private static EndpointFileQuery firstFilePage() {
        return new EndpointFileQuery(EndpointFilters.NONE, null, 0, 10);
    }

    @Test
    @DisplayName("each SaaS, endpoint and diagram route hits its path and query with GET; the audit path has no query")
    void everyDrawerRouteHitsItsRoute() {
        EndpointFilters filters = new EndpointFilters(List.of("PII"), List.of("System Documentation"),
                List.of("it & technical"), List.of("EMAIL ADDRESS", "PERSON NAME"));
        EndpointFilters oneClass = new EndpointFilters(List.of(), List.of("System Documentation"), List.of(), List.of());

        Map<String, Function<MattersApiClient, JsonNode>> calls = new LinkedHashMap<>();
        calls.put(saasUrl("/scan-summary"), c -> c.saasScanSummary(credential, SOURCE_ID));
        calls.put(saasUrl("/sensitives/records?view=data_elements"),
                c -> c.saasRecords(credential, SOURCE_ID, "data_elements"));
        calls.put(saasUrl("/sensitives/records?view=document_classes"),
                c -> c.saasRecords(credential, SOURCE_ID, "document_classes"));
        calls.put(saasUrl("/files?limit=5&skip=0&sensitive_entity=EMAIL%20ADDRESS"),
                c -> c.saasFiles(credential, SOURCE_ID,
                        new SaasFileQuery(SaasFileQuery.Kind.DATA_ELEMENT, "EMAIL ADDRESS", null, 0, 5)));
        calls.put(saasUrl("/files?limit=5&skip=10&search=q4%20plan&subcategory=Incident%20Reports"),
                c -> c.saasFiles(credential, SOURCE_ID,
                        new SaasFileQuery(SaasFileQuery.Kind.DOCUMENT_CLASS, "Incident Reports", "q4 plan", 10, 5)));
        calls.put(BASE + "/api/v1/data/security/saas/inventory/" + FILE_ID + "/overview",
                c -> c.saasFileOverview(credential, FILE_ID));
        calls.put(BASE + "/api/v1/remediation/objects/" + FILE_ID + "/logs",
                c -> c.remediationLogs(credential, FILE_ID));
        calls.put(BASE + "/api/v2/hash-lineage/copies?file_id=" + FILE_ID,
                c -> c.hashLineageCopies(credential, FILE_ID));
        calls.put(BASE + "/api/v2/hash-lineage/timeline?data_hash=" + DATA_HASH + "&limit=100",
                c -> c.hashLineageTimeline(credential, DATA_HASH, 100));
        calls.put(agentUrl("/overview"), c -> c.agentOverview(credential, SOURCE_ID));
        calls.put(agentUrl("/filter-options"), c -> c.agentFilterOptions(credential, SOURCE_ID));
        calls.put(agentUrl("/sensitive-files"),
                c -> c.agentSensitiveFiles(credential, SOURCE_ID, EndpointFilters.NONE, false));
        calls.put(agentUrl("/sensitive-files?sensitives=%5B%22PII%22%5D"
                        + "&subcategory=%5B%22System%20Documentation%22%5D"
                        + "&category=%5B%22it%20%26%20technical%22%5D"
                        + "&entity_name=%5B%22EMAIL%20ADDRESS%22,%22PERSON%20NAME%22%5D&isFalsePositive=false"),
                c -> c.agentSensitiveFiles(credential, SOURCE_ID, filters, true));
        calls.put(agentUrl("/entity?entity_name=%5B%22EMAIL%20ADDRESS%22%5D&offset=10&limit=10&isFalsePositive=false"),
                c -> c.agentEntity(credential, SOURCE_ID, "EMAIL ADDRESS", 10, 10, true));
        calls.put(agentUrl("/entity?entity_name=%5B%22PERSON%20NAME%22%5D&offset=0&limit=100"),
                c -> c.agentEntity(credential, SOURCE_ID, "PERSON NAME", 0, 100, false));
        calls.put(agentUrl("/files?skip=0&limit=10"), c -> c.agentFiles(credential, SOURCE_ID, firstFilePage()));
        calls.put(agentUrl("/files?skip=20&limit=10&search=notes%20v2&subcategory=%5B%22System%20Documentation%22%5D"),
                c -> c.agentFiles(credential, SOURCE_ID, new EndpointFileQuery(oneClass, "notes v2", 20, 10)));
        calls.put(agentUrl("/scans?skip=10&limit=10"), c -> c.agentScans(credential, SOURCE_ID, 10, 10));
        calls.put(schemaUrl("/schemas/" + UUID + "/erd"), c -> c.schemaErd(credential, UUID));
        calls.put(schemaUrl("/databases/" + UUID + "/erd"), c -> c.databaseErd(credential, UUID));
        calls.put(schemaUrl("/tables/" + UUID + "/relations"), c -> c.tableRelations(credential, UUID));

        calls.keySet().forEach(url -> expectGet(url).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON)));
        calls.values().forEach(call -> assertThat(call.apply(client).path("success").asBoolean()).isTrue());
        server.verify();

        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        verify(audit, atLeastOnce()).recordCredentialUse(eq(10L), eq("k1"), eq("p1"), paths.capture(), anyLong());
        assertThat(paths.getAllValues()).hasSize(calls.size()).allSatisfy(p -> assertThat(p).doesNotContain("?"));
        assertThat(paths.getAllValues()).contains(
                "/api/v2/hash-lineage/copies",
                "/api/v2/hash-lineage/timeline",
                "/api/v1/data/security/saas/asset/" + SOURCE_ID + "/files",
                "/api/v1/endpoint-agent/agent/" + SOURCE_ID + "/entity",
                "/api/v1/data/security/database-schema/tables/" + UUID + "/relations");
    }

    @Test
    @DisplayName("entity_name is sent as %5B%22EMAIL%20ADDRESS%22%5D, and a two-value array keeps its comma literal")
    void jsonArrayValues() {
        expectGet(agentUrl("/entity?entity_name=%5B%22EMAIL%20ADDRESS%22%5D&offset=0&limit=10"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        expectGet(agentUrl("/sensitive-files?entity_name=%5B%22EMAIL%20ADDRESS%22,%22PERSON%20NAME%22%5D"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        client.agentEntity(credential, SOURCE_ID, "EMAIL ADDRESS", 0, 10, false);
        client.agentSensitiveFiles(credential, SOURCE_ID,
                new EndpointFilters(List.of(), List.of(), List.of(), List.of("EMAIL ADDRESS", "PERSON NAME")), false);
        server.verify();
    }

    @Test
    @DisplayName("an endpoint-agent 500 is BAD_UPSTREAM_RESPONSE after exactly one request, on every agent route")
    void endpointAgentServerErrorNotRetried() {
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put(agentUrl("/overview"), () -> client.agentOverview(credential, SOURCE_ID));
        calls.put(agentUrl("/filter-options"), () -> client.agentFilterOptions(credential, SOURCE_ID));
        calls.put(agentUrl("/sensitive-files"),
                () -> client.agentSensitiveFiles(credential, SOURCE_ID, EndpointFilters.NONE, false));
        calls.put(agentUrl("/entity?entity_name=%5B%22EMAIL%20ADDRESS%22%5D&offset=0&limit=10"),
                () -> client.agentEntity(credential, SOURCE_ID, "EMAIL ADDRESS", 0, 10, false));
        calls.put(agentUrl("/files?skip=0&limit=10"), () -> client.agentFiles(credential, SOURCE_ID, firstFilePage()));
        calls.put(agentUrl("/scans?skip=0&limit=10"), () -> client.agentScans(credential, SOURCE_ID, 0, 10));

        calls.forEach((url, call) -> {
            server.reset();
            expectGet(url).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
            assertThatThrownBy(call::run).isInstanceOfSatisfying(MattersApiException.class, e -> {
                assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE);
                assertThat(e.getMessage())
                        .startsWith("Matters returned 500 for /api/v1/endpoint-agent/agent/" + SOURCE_ID + "/")
                        .doesNotContain("?");
            });
            server.verify();
        });
        verify(audit, times(calls.size())).recordCredentialFailure(eq(10L), eq("k1"), eq("p1"), anyString(),
                eq("BAD_UPSTREAM_RESPONSE"), anyString());
    }

    @Test
    @DisplayName("an endpoint-agent route still retries a connection failure and a short 429 once")
    void endpointAgentKeepsTheOtherRetries() {
        expectGet(agentUrl("/overview")).andRespond(request -> {
            throw new SocketTimeoutException("synthetic read timeout");
        });
        expectGet(agentUrl("/overview")).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        assertThat(client.agentOverview(credential, SOURCE_ID).path("success").asBoolean()).isTrue();
        server.verify();

        server.reset();
        expectGet(agentUrl("/scans?skip=0&limit=10"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "1"));
        expectGet(agentUrl("/scans?skip=0&limit=10")).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        assertThat(client.agentScans(credential, SOURCE_ID, 0, 10).path("success").asBoolean()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("a SaaS 500 is still retried once (two requests), as are diagram and relations 5xx")
    void saasAndDiagramServerErrorsRetriedOnce() {
        expectGet(2, saasUrl("/scan-summary")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertReason(() -> client.saasScanSummary(credential, SOURCE_ID),
                MattersApiException.Reason.UPSTREAM_UNAVAILABLE);
        server.verify();

        server.reset();
        expectGet(2, schemaUrl("/tables/" + UUID + "/relations")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertReason(() -> client.tableRelations(credential, UUID), MattersApiException.Reason.UPSTREAM_UNAVAILABLE);
        server.verify();

        server.reset();
        expectGet(schemaUrl("/schemas/" + UUID + "/erd")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectGet(schemaUrl("/schemas/" + UUID + "/erd"))
                .andRespond(withSuccess("{\"nodes\":[],\"edges\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.schemaErd(credential, UUID).path("nodes").isArray()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("success:false is not checked on the diagram and relations routes, but is on the hash-lineage routes")
    void successFlagByRoute() {
        expectGet(schemaUrl("/databases/" + UUID + "/erd"))
                .andRespond(withSuccess("{\"success\":false}", MediaType.APPLICATION_JSON));
        expectGet(schemaUrl("/tables/" + UUID + "/relations"))
                .andRespond(withSuccess("{\"success\":false}", MediaType.APPLICATION_JSON));
        assertThat(client.databaseErd(credential, UUID)).isNotNull();
        assertThat(client.tableRelations(credential, UUID)).isNotNull();
        server.verify();

        server.reset();
        expectGet(BASE + "/api/v2/hash-lineage/copies?file_id=" + FILE_ID)
                .andRespond(withSuccess("{\"success\":false,\"message\":\"needle\"}", MediaType.APPLICATION_JSON));
        assertReason(() -> client.hashLineageCopies(credential, FILE_ID),
                MattersApiException.Reason.BAD_UPSTREAM_RESPONSE);
        server.verify();
    }

    @Test
    @DisplayName("400 'No file found with ID x' is ASSET_NOT_FOUND after one request, as is a 200 success:false with it")
    void noFileFound() {
        String overview = BASE + "/api/v1/data/security/saas/inventory/" + FILE_ID + "/overview";
        expectGet(overview).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"message\":\"No file found with ID x\"}"));
        assertThatThrownBy(() -> client.saasFileOverview(credential, FILE_ID))
                .isInstanceOfSatisfying(MattersApiException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.ASSET_NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Asset not found");
                });
        server.verify();

        server.reset();
        expectGet(overview).andRespond(withSuccess("{\"success\":false,\"Message\":\"No file found with ID x\"}",
                MediaType.APPLICATION_JSON));
        assertReason(() -> client.saasFileOverview(credential, FILE_ID), MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();
    }

    @Test
    @DisplayName("drawer ids, dataHash, view, limit, skip and offset are asserted before any request is made")
    void drawerArgumentAssertions() {
        SaasFileQuery.Kind element = SaasFileQuery.Kind.DATA_ELEMENT;
        List<Runnable> calls = List.of(
                () -> client.saasScanSummary(credential, "not-hex"),
                () -> client.saasFileOverview(credential, UUID),
                () -> client.remediationLogs(credential, FILE_ID + "/../x"),
                () -> client.hashLineageCopies(credential, null),
                () -> client.agentOverview(credential, SOURCE_ID.substring(1)),
                () -> client.agentFilterOptions(credential, SOURCE_ID + "/../x"),
                () -> client.schemaErd(credential, SOURCE_ID),
                () -> client.databaseErd(credential, "not-a-uuid"),
                () -> client.tableRelations(credential, null),
                () -> client.hashLineageTimeline(credential, DATA_HASH.substring(1), 100),
                () -> client.hashLineageTimeline(credential, DATA_HASH.substring(1) + "g", 100),
                () -> client.hashLineageTimeline(credential, null, 100),
                () -> client.hashLineageTimeline(credential, DATA_HASH, 0),
                () -> client.hashLineageTimeline(credential, DATA_HASH, 101),
                () -> client.saasRecords(credential, SOURCE_ID, "elements"),
                () -> client.saasRecords(credential, SOURCE_ID, null),
                () -> client.saasFiles(credential, SOURCE_ID, new SaasFileQuery(element, "EMAIL ADDRESS", null, 0, 0)),
                () -> client.saasFiles(credential, SOURCE_ID,
                        new SaasFileQuery(element, "EMAIL ADDRESS", null, 10_001, 5)),
                () -> client.agentEntity(credential, SOURCE_ID, " ", 0, 10, false),
                () -> client.agentEntity(credential, SOURCE_ID, "EMAIL ADDRESS", -1, 10, false),
                () -> client.agentEntity(credential, SOURCE_ID, "EMAIL ADDRESS", 0, 101, false),
                () -> client.agentFiles(credential, SOURCE_ID, new EndpointFileQuery(EndpointFilters.NONE, null, 0, 0)),
                () -> client.agentFiles(credential, SOURCE_ID,
                        new EndpointFileQuery(EndpointFilters.NONE, null, 10_001, 10)),
                () -> client.agentScans(credential, SOURCE_ID, 10_001, 10),
                () -> client.agentScans(credential, SOURCE_ID, 0, 101));
        calls.forEach(call -> assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class));
        server.verify();
    }

    // ── Files page and Endpoint file drawer routes (build 3) ─────────────────────────────────────

    private static final String OWNER_A = "0a0b0c0d0e0f101112131415";
    private static final String OWNER_B = "1a1b1c1d1e1f202122232425";

    private static String endpointFileUrl(String route) {
        return BASE + "/api/v1/endpoint-agent/files/" + UUID + route;
    }

    private static SaasFileListQuery firstSaasPage() {
        return new SaasFileListQuery(10, null, null, List.of(), null, null, List.of(), List.of(), List.of(),
                List.of(), List.of());
    }

    private static EndpointFileListQuery firstEndpointPage() {
        return new EndpointFileListQuery(0, 10, null, EndpointFilters.NONE, List.of(), List.of(), List.of(), null);
    }

    @Test
    @DisplayName("each Files route hits its path and query with GET; subcategory is plain, lists are JSON arrays; "
            + "the audit path has no query")
    void everyFilesRouteHitsItsRoute() {
        SaasFileListQuery saas = new SaasFileListQuery(25, "ab+c/d_e-f==", "q4 plan", List.of("PII"),
                "Travel Receipts", "external", List.of("financial"), List.of("GMAIL"), List.of(OWNER_A, OWNER_B),
                List.of("ana@example.com"), List.of());
        EndpointFilters oneClass = new EndpointFilters(List.of(), List.of("Travel Receipts"), List.of(), List.of());
        EndpointFileListQuery endpoint = new EndpointFileListQuery(10, 10, "notes", oneClass, List.of("Reviewed"),
                List.of("UID-01", "UID-02"), List.of("Team-Laptop-02"), "7d");

        Map<String, Function<MattersApiClient, JsonNode>> calls = new LinkedHashMap<>();
        calls.put(BASE + "/api/v2/data/security/saas/sensitivities?limit=10", c -> c.saasSensitivities(credential,
                firstSaasPage()));
        calls.put(BASE + "/api/v2/data/security/saas/sensitivities?limit=25&cursor=ab%2Bc%2Fd_e-f%3D%3D"
                        + "&name_search_str=q4%20plan&label=%5B%22PII%22%5D&subcategory=Travel%20Receipts"
                        + "&category=%5B%22financial%22%5D&source=%5B%22GMAIL%22%5D"
                        + "&owners=%5B%22" + OWNER_A + "%22,%22" + OWNER_B + "%22%5D"
                        + "&internal_users=%5B%22ana%40example.com%22%5D&external_share=true",
                c -> c.saasSensitivities(credential, saas));
        calls.put(BASE + "/api/v1/data/security/saas/categories", c -> c.saasCategories(credential));
        calls.put(BASE + "/api/v1/data/security/saas/sensitivities/users", c -> c.saasSensitivityUsers(credential));
        calls.put(BASE + "/api/v1/endpoint-agent/agent/files?skip=0&limit=10",
                c -> c.endpointFiles(credential, firstEndpointPage()));
        calls.put(BASE + "/api/v1/endpoint-agent/agent/files?skip=10&limit=10&search=notes"
                        + "&subcategory=%5B%22Travel%20Receipts%22%5D&tags=%5B%22Reviewed%22%5D"
                        + "&uids=%5B%22UID-01%22,%22UID-02%22%5D&hostname=%5B%22Team-Laptop-02%22%5D&last_modified=7d",
                c -> c.endpointFiles(credential, endpoint));
        calls.put(BASE + "/api/v1/endpoint-agent/agents/filter-options",
                c -> c.endpointAgentsFilterOptions(credential));
        calls.put(endpointFileUrl("/overview"), c -> c.endpointFileOverview(credential, UUID));
        calls.put(BASE + "/api/v2/endpoint-lineage/asset?file_id=" + UUID, c -> c.endpointLineageAsset(credential, UUID));
        calls.put(BASE + "/api/v1/admin/mip/file-audit-logs/" + UUID, c -> c.mipFileAuditLogs(credential, UUID));
        calls.put(BASE + "/api/v2/hash-lineage/copies?file_id=" + UUID, c -> c.hashLineageCopies(credential, UUID));

        calls.keySet().forEach(url -> expectGet(url).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON)));
        calls.values().forEach(call -> assertThat(call.apply(client).path("success").asBoolean()).isTrue());
        server.verify();

        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        verify(audit, atLeastOnce()).recordCredentialUse(eq(10L), eq("k1"), eq("p1"), paths.capture(), anyLong());
        assertThat(paths.getAllValues()).hasSize(calls.size()).allSatisfy(p -> assertThat(p).doesNotContain("?"));
        assertThat(paths.getAllValues()).contains(
                "/api/v2/data/security/saas/sensitivities",
                "/api/v1/endpoint-agent/agent/files",
                "/api/v2/endpoint-lineage/asset",
                "/api/v1/admin/mip/file-audit-logs/" + UUID);
    }

    @Test
    @DisplayName("a 500 from /agent/files, /agents/filter-options or /files/{id}/overview is BAD_UPSTREAM_RESPONSE "
            + "after exactly one request")
    void endpointFilesServerErrorNotRetried() {
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put(BASE + "/api/v1/endpoint-agent/agent/files?skip=0&limit=10",
                () -> client.endpointFiles(credential, firstEndpointPage()));
        calls.put(BASE + "/api/v1/endpoint-agent/agents/filter-options",
                () -> client.endpointAgentsFilterOptions(credential));
        calls.put(endpointFileUrl("/overview"), () -> client.endpointFileOverview(credential, UUID));

        calls.forEach((url, call) -> {
            server.reset();
            expectGet(url).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
            assertThatThrownBy(call::run).isInstanceOfSatisfying(MattersApiException.class, e -> {
                assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE);
                assertThat(e.getMessage()).doesNotContain("?");
            });
            server.verify();
        });
    }

    @Test
    @DisplayName("a 500 from the SaaS Files, endpoint-lineage, MIP and UUID hash-lineage routes is retried once")
    void filesRetriedRoutes() {
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put(BASE + "/api/v2/data/security/saas/sensitivities?limit=10",
                () -> client.saasSensitivities(credential, firstSaasPage()));
        calls.put(BASE + "/api/v1/data/security/saas/categories", () -> client.saasCategories(credential));
        calls.put(BASE + "/api/v1/data/security/saas/sensitivities/users",
                () -> client.saasSensitivityUsers(credential));
        calls.put(BASE + "/api/v2/endpoint-lineage/asset?file_id=" + UUID,
                () -> client.endpointLineageAsset(credential, UUID));
        calls.put(BASE + "/api/v1/admin/mip/file-audit-logs/" + UUID, () -> client.mipFileAuditLogs(credential, UUID));
        calls.put(BASE + "/api/v2/hash-lineage/copies?file_id=" + UUID, () -> client.hashLineageCopies(credential, UUID));

        calls.forEach((url, call) -> {
            server.reset();
            expectGet(2, url).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
            assertReason(call, MattersApiException.Reason.UPSTREAM_UNAVAILABLE);
            server.verify();
        });

        server.reset();
        expectGet(BASE + "/api/v1/admin/mip/file-audit-logs/" + UUID)
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        expectGet(BASE + "/api/v1/admin/mip/file-audit-logs/" + UUID)
                .andRespond(withSuccess("{\"success\":true,\"audit_logs\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.mipFileAuditLogs(credential, UUID).path("audit_logs").isArray()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("400 'No file found with ID x' on the endpoint file overview is ASSET_NOT_FOUND after one request")
    void endpointFileNotFound() {
        expectGet(endpointFileUrl("/overview")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"message\":\"No file found with ID x\"}"));
        assertReason(() -> client.endpointFileOverview(credential, UUID), MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();
    }

    @Test
    @DisplayName("hashLineageCopies takes a 24-hex id or a UUID; remediationLogs still takes 24 hex only; the new "
            + "routes assert their arguments before any request")
    void filesArgumentAssertions() {
        List<Runnable> calls = List.of(
                () -> client.hashLineageCopies(credential, "not-an-id"),
                () -> client.hashLineageCopies(credential, UUID + "/../x"),
                () -> client.remediationLogs(credential, UUID),
                () -> client.endpointFileOverview(credential, FILE_ID),
                () -> client.endpointFileOverview(credential, null),
                () -> client.endpointLineageAsset(credential, FILE_ID),
                () -> client.mipFileAuditLogs(credential, UUID + "/../x"),
                () -> client.saasSensitivities(credential, new SaasFileListQuery(0, null, null, List.of(), null, null,
                        List.of(), List.of(), List.of(), List.of(), List.of())),
                () -> client.endpointFiles(credential, new EndpointFileListQuery(10_001, 10, null,
                        EndpointFilters.NONE, List.of(), List.of(), List.of(), null)),
                () -> client.endpointFiles(credential, new EndpointFileListQuery(0, 101, null,
                        EndpointFilters.NONE, List.of(), List.of(), List.of(), null)));
        calls.forEach(call -> assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class));
        server.verify();
    }

    // ── build 6: pages, Scan Management and scan settings ────────────────────────────────────────

    private static String scanUrl(String route) {
        return BASE + "/api/v1/scan-management" + route;
    }

    private static ScanListQuery scanList(ScanListQuery.Kind kind, String search, Map<String, String> filters) {
        return new ScanListQuery(kind, search, filters, 2, 10);
    }

    @Test
    @DisplayName("each build 6 route hits its path and query with GET; the audit path has no query")
    void everyBuildSixRouteHitsItsRoute() {
        Map<String, String> scheduleFilters = new LinkedHashMap<>();
        scheduleFilters.put("platform", "cloud");
        scheduleFilters.put("status", "ACTIVE");
        Map<String, String> runFilters = new LinkedHashMap<>();
        runFilters.put("coverage", "SELECTED_DATASTORES");
        runFilters.put("trigger", "IMMEDIATE");

        Map<String, Function<MattersApiClient, JsonNode>> calls = new LinkedHashMap<>();
        calls.put(BASE + "/api/v1/data/security/saas/categories", c -> c.saasCategories(credential));
        calls.put(BASE + "/api/v1/data/security/fetch/assist", c -> c.fetchAssists(credential));
        calls.put(BASE + "/api/v1/remediation/activity?skip=10&limit=10&status=FAILED&q=q4%20plan&actionType=MAKE_PRIVATE",
                c -> c.remediationActivity(credential,
                        new RemediationQuery("q4 plan", "FAILED", "MAKE_PRIVATE", 10, 10)));
        calls.put(scanUrl("/schedules?page=2&page_size=10&q=nightly&platform=cloud&status=ACTIVE"),
                c -> c.scanSchedules(credential, scanList(ScanListQuery.Kind.SCHEDULES, "nightly", scheduleFilters)));
        calls.put(scanUrl("/schedules/" + SOURCE_ID), c -> c.scanSchedule(credential, SOURCE_ID));
        calls.put(scanUrl("/scan-history?page=2&page_size=10&coverage=SELECTED_DATASTORES&trigger=IMMEDIATE"),
                c -> c.scanRuns(credential, scanList(ScanListQuery.Kind.RUNS, null, runFilters)));
        calls.put(scanUrl("/scan-history/" + UUID), c -> c.scanRun(credential, UUID));
        calls.put(scanUrl("/scan-history/" + UUID + "/datastores?page=2&page_size=10&account=prod%20account"),
                c -> c.scanRunDatastores(credential, UUID,
                        scanList(ScanListQuery.Kind.RUN_DATASTORES, null, Map.of("account", "prod account"))));
        calls.put(scanUrl("/scan-scopes?page=2&page_size=10"),
                c -> c.scanScopes(credential, scanList(ScanListQuery.Kind.SCOPES, null, Map.of())));
        calls.put(scanUrl("/scan-scopes/" + UUID), c -> c.scanScope(credential, UUID));
        calls.put(BASE + "/api/v1/data/security/saas/" + SOURCE_ID + "/scan-configuration",
                c -> c.saasScanConfiguration(credential, SOURCE_ID));
        calls.put(BASE + "/api/v1/data/security/saas/assets/list?saasIntegrationId=" + SOURCE_ID
                        + "&driveType=GMAIL&limit=10&skip=1&search=ann&status=ACTIVE&domain=example.test",
                c -> c.saasScanUsers(credential, SOURCE_ID,
                        new SaasUserQuery("GMAIL", "ann", "ACTIVE", "example.test", 2, 10)));
        calls.put(BASE + "/api/v1/data/security/saas/channels/list?saasIntegrationId=" + SOURCE_ID
                        + "&limit=10&skip=0&channelType=PUBLIC&isNew=true",
                c -> c.saasChannels(credential, SOURCE_ID,
                        new SaasChannelQuery(null, "PUBLIC", null, null, true, 1, 10)));
        calls.put(BASE + "/api/v1/endpoint-agent/configuration", c -> c.endpointAgentConfiguration(credential));
        calls.put(BASE + "/api/v1/data/scan/default/configuration?asset_id=" + SOURCE_ID,
                c -> c.defaultScanConfiguration(credential, SOURCE_ID));
        calls.put(BASE + "/api/v1/data/discovery/job/status/single?asset_id=" + SOURCE_ID,
                c -> c.discoveryJobStatus(credential, SOURCE_ID));
        calls.put(BASE + "/api/v1/data/security/databricks/asset/" + SOURCE_ID + "/databases",
                c -> c.databricksDatabases(credential, SOURCE_ID));

        calls.keySet().forEach(url -> expectGet(url).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON)));
        calls.values().forEach(call -> assertThat(call.apply(client).path("success").asBoolean()).isTrue());
        server.verify();

        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        verify(audit, atLeastOnce()).recordCredentialUse(eq(10L), eq("k1"), eq("p1"), paths.capture(), anyLong());
        assertThat(paths.getAllValues()).hasSize(calls.size()).allSatisfy(p -> assertThat(p).doesNotContain("?"));
        assertThat(paths.getAllValues()).contains(
                "/api/v1/remediation/activity",
                "/api/v1/scan-management/scan-history/" + UUID + "/datastores",
                "/api/v1/data/security/saas/assets/list",
                "/api/v1/data/discovery/job/status/single");
    }

    @Test
    @DisplayName("scan-management bodies without success and the job status {success:false} are returned, not thrown")
    void scanManagementAndJobStatusAreNotConsoleRoutes() {
        String bare = "{\"data\":[],\"pagination\":{\"total\":0}}";
        expectGet(scanUrl("/schedules?page=2&page_size=10"))
                .andRespond(withSuccess(bare, MediaType.APPLICATION_JSON));
        expectGet(scanUrl("/scan-history/" + SOURCE_ID)).andRespond(withSuccess("{\"id\":\"x\"}",
                MediaType.APPLICATION_JSON));
        expectGet(BASE + "/api/v1/data/discovery/job/status/single?asset_id=" + SOURCE_ID)
                .andRespond(withSuccess("{\"success\":false,\"is_cooling_valid\":false}", MediaType.APPLICATION_JSON));
        assertThat(client.scanSchedules(credential, scanList(ScanListQuery.Kind.SCHEDULES, null, Map.of()))
                .path("data").isArray()).isTrue();
        assertThat(client.scanRun(credential, SOURCE_ID).path("id").asText()).isEqualTo("x");
        assertThat(client.discoveryJobStatus(credential, SOURCE_ID).path("success").asBoolean()).isFalse();
        server.verify();

        server.reset();
        expectGet(BASE + "/api/v1/data/security/fetch/assist")
                .andRespond(withSuccess("{\"success\":false}", MediaType.APPLICATION_JSON));
        assertReason(() -> client.fetchAssists(credential), MattersApiException.Reason.BAD_UPSTREAM_RESPONSE);
        server.verify();
    }

    @Test
    @DisplayName("'AssetId Not Found', 'No SaaS integration found' and 'Slack integration not found' are ASSET_NOT_FOUND")
    void buildSixNotFoundPhrases() {
        String status = BASE + "/api/v1/data/discovery/job/status/single?asset_id=" + SOURCE_ID;
        expectGet(status).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"message\":\"AssetId Not Found\"}"));
        assertReason(() -> client.discoveryJobStatus(credential, SOURCE_ID),
                MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();

        server.reset();
        String configuration = BASE + "/api/v1/data/security/saas/" + SOURCE_ID + "/scan-configuration";
        expectGet(configuration).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"message\":\"No SaaS integration found\"}"));
        assertReason(() -> client.saasScanConfiguration(credential, SOURCE_ID),
                MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();

        server.reset();
        String channels = BASE + "/api/v1/data/security/saas/channels/list?saasIntegrationId=" + SOURCE_ID
                + "&limit=10&skip=0";
        expectGet(channels).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"message\":\"Slack integration not found\"}"));
        assertReason(() -> client.saasChannels(credential, SOURCE_ID,
                new SaasChannelQuery(null, null, null, null, null, 1, 10)), MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();
        verify(audit, times(3)).recordCredentialFailure(eq(10L), eq("k1"), eq("p1"), anyString(),
                eq("ASSET_NOT_FOUND"), anyString());
    }

    @Test
    @DisplayName("build 6 ids and paging are asserted before any request is made")
    void buildSixArgumentAssertions() {
        ScanListQuery scopes = scanList(ScanListQuery.Kind.SCOPES, null, Map.of());
        List<Runnable> calls = List.of(
                () -> client.scanSchedule(credential, "not-an-id"),
                () -> client.scanSchedule(credential, null),
                () -> client.scanRun(credential, UUID + "/../x"),
                () -> client.scanRunDatastores(credential, SOURCE_ID.substring(1), scopes),
                () -> client.scanScope(credential, UUID.substring(1)),
                () -> client.saasScanConfiguration(credential, UUID),
                () -> client.saasScanUsers(credential, "x", new SaasUserQuery("GMAIL", null, null, null, 1, 10)),
                () -> client.saasChannels(credential, null, new SaasChannelQuery(null, null, null, null, null, 1, 10)),
                () -> client.defaultScanConfiguration(credential, "x"),
                () -> client.discoveryJobStatus(credential, null),
                () -> client.databricksDatabases(credential, SOURCE_ID + "/../x"),
                () -> client.remediationActivity(credential, new RemediationQuery(null, null, null, 10_001, 10)),
                () -> client.remediationActivity(credential, new RemediationQuery(null, null, null, 0, 101)));
        calls.forEach(call -> assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class));
        server.verify();
    }
}
