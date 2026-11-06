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
}
