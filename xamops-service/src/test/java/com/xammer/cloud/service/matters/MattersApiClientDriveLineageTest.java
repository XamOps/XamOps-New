package com.xammer.cloud.service.matters;

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
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The drive-lineage routes of {@link MattersApiClient} (build 7 design 5.3), against a
 * {@link MockRestServiceServer}: nothing leaves the JVM. Synthetic ids only.
 */
class MattersApiClientDriveLineageTest {

    private static final String BASE = "https://matters.test";
    private static final String PROJECT_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String INTEGRATION = "0123456789abcdef01234567";
    private static final String RESOURCE = "0000000e-0000-4000-8000-00000000000f";
    private static final String DOC_ID = "Doc_Synthetic-0001";
    private static final String OK = "{\"success\":true,\"data\":[]}";

    private final MattersCredential credential =
            new MattersCredential(10L, BASE, "synthetic-key", "k1", PROJECT_ID);

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

    private void expectGet(String url, String body) {
        server.expect(ExpectedCount.once(), requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", "synthetic-key"))
                .andExpect(headerDoesNotExist("project-id"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static void assertReason(Runnable call, MattersApiException.Reason reason) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(MattersApiException.class, e -> assertThat(e.getReason()).isEqualTo(reason));
    }

    @Test
    @DisplayName("the three routes: exact paths and query order, GET, no project-id, audited by path without '?'")
    void pathsAndAudit() {
        expectGet(BASE + "/api/v2/drive-lineage/users?integration_id=" + INTEGRATION, OK);
        expectGet(BASE + "/api/v2/drive-lineage/user-files?integration_id=" + INTEGRATION
                + "&resource_id=" + RESOURCE, OK);
        expectGet(BASE + "/api/v2/drive-lineage/asset?integration_id=" + INTEGRATION + "&doc_id=" + DOC_ID,
                "{\"success\":true,\"data\":{}}");

        client.driveLineageUsers(credential, INTEGRATION);
        client.driveLineageUserFiles(credential, INTEGRATION, RESOURCE);
        client.driveLineageAsset(credential, INTEGRATION, DOC_ID);

        server.verify();
        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        verify(audit, times(3)).recordCredentialUse(eq(10L), eq("k1"), eq(PROJECT_ID), paths.capture(), anyLong());
        assertThat(paths.getAllValues()).containsExactly(
                "/api/v2/drive-lineage/users",
                "/api/v2/drive-lineage/user-files",
                "/api/v2/drive-lineage/asset");
        assertThat(paths.getAllValues()).allSatisfy(path -> assertThat(path).doesNotContain("?"));
    }

    @Test
    @DisplayName("an upper-case integration id is sent as given; the bounds of the resource and doc id rules pass")
    void idBounds() {
        String upper = INTEGRATION.toUpperCase();
        String resource64 = "r".repeat(64);
        String doc128 = "D".repeat(127) + "_";
        expectGet(BASE + "/api/v2/drive-lineage/users?integration_id=" + upper, OK);
        expectGet(BASE + "/api/v2/drive-lineage/user-files?integration_id=" + INTEGRATION
                + "&resource_id=" + resource64, OK);
        expectGet(BASE + "/api/v2/drive-lineage/asset?integration_id=" + INTEGRATION + "&doc_id=" + doc128, OK);

        client.driveLineageUsers(credential, upper);
        client.driveLineageUserFiles(credential, INTEGRATION, resource64);
        client.driveLineageAsset(credential, INTEGRATION, doc128);

        server.verify();
    }

    @Test
    @DisplayName("a rejected integration, resource or doc id makes no request and writes no audit row")
    void rejectedIdsMakeNoRequest() {
        List<Runnable> calls = List.of(
                () -> client.driveLineageUsers(credential, null),
                () -> client.driveLineageUsers(credential, INTEGRATION + "0"),
                () -> client.driveLineageUsers(credential, "zz23456789abcdef01234567"),
                () -> client.driveLineageUserFiles(credential, "short", RESOURCE),
                () -> client.driveLineageUserFiles(credential, INTEGRATION, null),
                () -> client.driveLineageUserFiles(credential, INTEGRATION, ""),
                () -> client.driveLineageUserFiles(credential, INTEGRATION, "r".repeat(65)),
                () -> client.driveLineageUserFiles(credential, INTEGRATION, "a/../b"),
                () -> client.driveLineageUserFiles(credential, INTEGRATION, "a b"),
                () -> client.driveLineageAsset(credential, INTEGRATION + "x", DOC_ID),
                () -> client.driveLineageAsset(credential, INTEGRATION, null),
                () -> client.driveLineageAsset(credential, INTEGRATION, ""),
                () -> client.driveLineageAsset(credential, INTEGRATION, "d".repeat(129)),
                () -> client.driveLineageAsset(credential, INTEGRATION, "doc.pdf"),
                () -> client.driveLineageAsset(credential, INTEGRATION, "doc&x=1"));

        calls.forEach(call -> assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class));

        server.verify();
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("one retry on 500, then UPSTREAM_UNAVAILABLE; a 500 then 200 answers")
    void oneRetryOnServerError() {
        String url = BASE + "/api/v2/drive-lineage/users?integration_id=" + INTEGRATION;
        server.expect(ExpectedCount.once(), requestTo(url)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(ExpectedCount.once(), requestTo(url)).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        assertThat(client.driveLineageUsers(credential, INTEGRATION).path("success").asBoolean()).isTrue();
        server.verify();

        server.reset();
        String assetUrl = BASE + "/api/v2/drive-lineage/asset?integration_id=" + INTEGRATION + "&doc_id=" + DOC_ID;
        server.expect(ExpectedCount.twice(), requestTo(assetUrl)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertReason(() -> client.driveLineageAsset(credential, INTEGRATION, DOC_ID),
                MattersApiException.Reason.UPSTREAM_UNAVAILABLE);
        server.verify();
    }

    @Test
    @DisplayName("success:false 'asset not found', a 404 and a 400 'Asset not found' are ASSET_NOT_FOUND; other success:false is BAD_UPSTREAM_RESPONSE")
    void notFound() {
        String url = BASE + "/api/v2/drive-lineage/asset?integration_id=" + INTEGRATION + "&doc_id=" + DOC_ID;
        server.expect(ExpectedCount.once(), requestTo(url)).andRespond(withSuccess(
                "{\"success\": false, \"message\": \"asset not found\"}", MediaType.APPLICATION_JSON));
        assertReason(() -> client.driveLineageAsset(credential, INTEGRATION, DOC_ID),
                MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();

        server.reset();
        server.expect(ExpectedCount.once(), requestTo(url)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertReason(() -> client.driveLineageAsset(credential, INTEGRATION, DOC_ID),
                MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();

        server.reset();
        server.expect(ExpectedCount.once(), requestTo(url)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\": \"Asset not found\"}"));
        assertReason(() -> client.driveLineageAsset(credential, INTEGRATION, DOC_ID),
                MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();

        server.reset();
        String usersUrl = BASE + "/api/v2/drive-lineage/users?integration_id=" + INTEGRATION;
        server.expect(ExpectedCount.once(), requestTo(usersUrl)).andRespond(withSuccess(
                "{\"success\": false, \"message\": \"boom\"}", MediaType.APPLICATION_JSON));
        assertReason(() -> client.driveLineageUsers(credential, INTEGRATION),
                MattersApiException.Reason.BAD_UPSTREAM_RESPONSE);
        server.verify();
        verify(audit).recordCredentialFailure(eq(10L), eq("k1"), eq(PROJECT_ID),
                eq("/api/v2/drive-lineage/users"), eq("BAD_UPSTREAM_RESPONSE"), anyString());
    }

    @Test
    @DisplayName("MattersApiClient still has exactly one HttpMethod.GET site and no other HTTP method")
    void oneGetSite() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/xammer/cloud/service/matters/MattersApiClient.java"));
        Matcher get = Pattern.compile("HttpMethod\\.GET").matcher(source);
        int count = 0;
        while (get.find()) {
            count++;
        }
        assertThat(count).isEqualTo(1);
        assertThat(source).doesNotContainPattern("HttpMethod\\.(POST|PUT|PATCH|DELETE)");
    }
}
