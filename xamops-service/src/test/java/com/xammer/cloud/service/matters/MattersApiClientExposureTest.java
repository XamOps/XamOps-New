package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.matters.MattersApiClient.ExposureListParams;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The Data Lineage exposure routes of {@link MattersApiClient} (build 4 design 5.5), against a
 * {@link MockRestServiceServer}: nothing leaves the JVM. Synthetic ids only.
 */
class MattersApiClientExposureTest {

    private static final String BASE = "https://matters.test";
    private static final String PROJECT_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String POLICY_UUID = "0000000a-0000-4000-8000-00000000000b";
    private static final String EXPOSURE_ID = "exposure-one";
    private static final String HEX_FILE_ID = "fedcba9876543210fedcba98";
    private static final String UUID_FILE_ID = "0000000c-0000-4000-8000-00000000000d";
    private static final String OK = "{\"success\":true,\"data\":{}}";

    private static final String CATEGORIES = "categories=%5B%22SENSITIVE_FINDINGS%22%5D";
    private static final String TYPES = "datastore_types=%5B%22ONE_DRIVE%22,%22SHARE_POINT%22,%22ENDPOINT%22%5D";
    private static final String STATUSES = "statuses=%5B%22OPEN%22%5D";

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

    private ResponseActions expectExposureGet(String url) {
        return server.expect(ExpectedCount.once(), requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("project-id", PROJECT_ID))
                .andExpect(header("X-API-Key", "synthetic-key"));
    }

    private static void assertReason(Runnable call, MattersApiException.Reason reason) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(MattersApiException.class, e -> assertThat(e.getReason()).isEqualTo(reason));
    }

    private static Map<String, Consumer<MattersApiClient>> exposureCalls(MattersCredential c) {
        Map<String, Consumer<MattersApiClient>> calls = new LinkedHashMap<>();
        calls.put(BASE + "/api/v1/exposure/filters-options", api -> api.exposureFilterOptions(c));
        calls.put(BASE + "/api/v1/exposure/summary?" + CATEGORIES + "&" + TYPES + "&" + STATUSES + "&skip=0&limit=1",
                api -> api.exposureSummary(c, "SENSITIVE_FINDINGS", ExposureListParams.count()));
        calls.put(BASE + "/api/v1/exposure/policy/" + POLICY_UUID + "/exposures?skip=0&limit=7&" + STATUSES,
                api -> api.exposurePolicyExposures(c, POLICY_UUID,
                        new ExposureListParams(0, 7, null, List.of(), List.of(), List.of())));
        calls.put(BASE + "/api/v1/exposure/" + EXPOSURE_ID + "/files?skip=7&limit=7",
                api -> api.exposureFiles(c, EXPOSURE_ID, 7, 7));
        return calls;
    }

    @Test
    @DisplayName("the four exposure routes: paths, the console's query order, project-id, GET, audited by path")
    void exposurePathsAndHeader() {
        Map<String, Consumer<MattersApiClient>> calls = exposureCalls(credential);
        calls.keySet().forEach(url -> expectExposureGet(url).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON)));

        calls.values().forEach(call -> call.accept(client));

        server.verify();
        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        verify(audit, org.mockito.Mockito.times(4))
                .recordCredentialUse(eq(10L), eq("k1"), eq(PROJECT_ID), paths.capture(), anyLong());
        assertThat(paths.getAllValues()).containsExactly(
                "/api/v1/exposure/filters-options",
                "/api/v1/exposure/summary",
                "/api/v1/exposure/policy/" + POLICY_UUID + "/exposures",
                "/api/v1/exposure/" + EXPOSURE_ID + "/files");
    }

    @Test
    @DisplayName("summary and policy exposures add search and the filters as encoded JSON arrays")
    void filtersAreJsonArrays() {
        ExposureListParams params = new ExposureListParams(14, 50, "q4 plan+x",
                List.of("HIGH", "MEDIUM"), List.of("Team Drive (Gmail)"), List.of("PII"));
        expectExposureGet(BASE + "/api/v1/exposure/summary?" + CATEGORIES + "&" + TYPES + "&" + STATUSES
                + "&skip=14&limit=50&search=q4%20plan%2Bx"
                + "&severities=%5B%22HIGH%22,%22MEDIUM%22%5D"
                + "&datastore_names=%5B%22Team%20Drive%20%28Gmail%29%22%5D"
                + "&labels=%5B%22PII%22%5D")
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        expectExposureGet(BASE + "/api/v1/exposure/policy/" + POLICY_UUID + "/exposures?skip=14&limit=50&"
                + STATUSES + "&search=q4%20plan%2Bx&severities=%5B%22HIGH%22,%22MEDIUM%22%5D")
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        client.exposureSummary(credential, "SENSITIVE_FINDINGS", params);
        client.exposurePolicyExposures(credential, POLICY_UUID, params);

        server.verify();
    }

    @Test
    @DisplayName("a non-exposure call sends no project-id header")
    void noProjectIdElsewhere() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/api/v1/assets?page_size=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist("project-id"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(BASE + "/api/v2/hash-lineage/copies?file_id=" + HEX_FILE_ID))
                .andExpect(headerDoesNotExist("project-id"))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        client.verify(credential);
        client.hashLineageCopies(credential, HEX_FILE_ID);

        server.verify();
    }

    @Test
    @DisplayName("a 401 on an exposure route is BAD_UPSTREAM_RESPONSE with the refused message; elsewhere INVALID_KEY")
    void exposureUnauthorized() {
        String body = "{\"message\":\"Invalid API key\"}";
        exposureCalls(credential).forEach((url, call) -> {
            server.reset();
            expectExposureGet(url).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON).body(body));
            assertThatThrownBy(() -> call.accept(client))
                    .isInstanceOfSatisfying(MattersApiException.class, e -> {
                        assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE);
                        assertThat(e.getMessage()).isEqualTo("Matters refused the exposure route for this key");
                    });
            server.verify();
        });

        server.reset();
        server.expect(ExpectedCount.once(), requestTo(BASE + "/api/v1/assets?page_size=1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON).body(body));
        assertReason(() -> client.verify(credential), MattersApiException.Reason.INVALID_KEY);
        server.verify();
        verify(audit).recordCredentialFailure(eq(10L), eq("k1"), eq(PROJECT_ID),
                eq("/api/v1/exposure/filters-options"), eq("BAD_UPSTREAM_RESPONSE"), anyString());
    }

    @Test
    @DisplayName("400 without a not-found phrase is BAD_UPSTREAM_RESPONSE, with one is ASSET_NOT_FOUND; success:false")
    void exposureFailures() {
        String filtersUrl = BASE + "/api/v1/exposure/filters-options";
        expectExposureGet(filtersUrl).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"project-id header required\"}"));
        assertReason(() -> client.exposureFilterOptions(credential), MattersApiException.Reason.BAD_UPSTREAM_RESPONSE);
        server.verify();

        server.reset();
        String filesUrl = BASE + "/api/v1/exposure/" + EXPOSURE_ID + "/files?skip=0&limit=7";
        expectExposureGet(filesUrl).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"No asset found\"}"));
        assertReason(() -> client.exposureFiles(credential, EXPOSURE_ID, 0, 7),
                MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();

        server.reset();
        expectExposureGet(filesUrl).andRespond(withSuccess("{\"success\":false,\"message\":\"boom\"}",
                MediaType.APPLICATION_JSON));
        assertReason(() -> client.exposureFiles(credential, EXPOSURE_ID, 0, 7),
                MattersApiException.Reason.BAD_UPSTREAM_RESPONSE);
        server.verify();

        server.reset();
        expectExposureGet(filesUrl).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertReason(() -> client.exposureFiles(credential, EXPOSURE_ID, 0, 7),
                MattersApiException.Reason.ASSET_NOT_FOUND);
        server.verify();
    }

    @Test
    @DisplayName("a credential without a project id fails before any request on an exposure route")
    void blankProjectId() {
        MattersCredential noProject = new MattersCredential(10L, BASE, "synthetic-key", "k1", " ");

        assertThatThrownBy(() -> client.exposureFilterOptions(noProject))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("credential has no project id");

        server.verify();
    }

    @Test
    @DisplayName("hashLineageCopies takes a 24-hex or a UUID file id and rejects anything else")
    void hashLineageCopiesFileIds() {
        for (String id : List.of(HEX_FILE_ID, UUID_FILE_ID)) {
            server.expect(ExpectedCount.once(), requestTo(BASE + "/api/v2/hash-lineage/copies?file_id=" + id))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));
        }

        client.hashLineageCopies(credential, HEX_FILE_ID);
        client.hashLineageCopies(credential, UUID_FILE_ID);
        server.verify();

        for (String bad : List.of("not-an-id", HEX_FILE_ID + "0", UUID_FILE_ID + "/../x", "")) {
            assertThatThrownBy(() -> client.hashLineageCopies(credential, bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("category, policy id, exposure id, skip and limit are asserted before any request")
    void argumentAssertions() {
        ExposureListParams ok = ExposureListParams.count();
        List<Runnable> calls = List.of(
                () -> client.exposureSummary(credential, "sensitive_findings", ok),
                () -> client.exposureSummary(credential, "DATA_RESIDENCY", ok),
                () -> client.exposureSummary(credential, null, ok),
                () -> client.exposureSummary(credential, "STALE_DATA",
                        new ExposureListParams(0, 0, null, null, null, null)),
                () -> client.exposureSummary(credential, "STALE_DATA",
                        new ExposureListParams(10_001, 1, null, null, null, null)),
                () -> client.exposurePolicyExposures(credential, "not-a-uuid", ok),
                () -> client.exposurePolicyExposures(credential, POLICY_UUID,
                        new ExposureListParams(0, 101, null, null, null, null)),
                () -> client.exposureFiles(credential, "a/../b", 0, 7),
                () -> client.exposureFiles(credential, "x".repeat(65), 0, 7),
                () -> client.exposureFiles(credential, null, 0, 7),
                () -> client.exposureFiles(credential, EXPOSURE_ID, -1, 7),
                () -> client.exposureFiles(credential, EXPOSURE_ID, 0, 101));

        calls.forEach(call -> assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class));

        server.verify();
        verifyNoInteractions(audit);
    }
}
