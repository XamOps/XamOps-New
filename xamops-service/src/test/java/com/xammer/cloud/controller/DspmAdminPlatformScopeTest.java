package com.xammer.cloud.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.MattersProjectMapping;
import com.xammer.cloud.dto.matters.DspmMappingAdminView;
import com.xammer.cloud.dto.matters.DspmMappingRequest;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.MattersAccessAuditRepository;
import com.xammer.cloud.repository.MattersProjectMappingRepository;
import com.xammer.cloud.service.matters.MattersAdminService;
import com.xammer.cloud.service.matters.MattersApiClient;
import com.xammer.cloud.service.matters.MattersAuditService;
import com.xammer.cloud.service.matters.MattersDspmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * F2: the admin page shows a key as CONNECTED only when it can serve the XamLens console routes.
 *
 * Verify and save run through the real controller, {@link MattersAdminService} and {@link MattersApiClient};
 * Matters is a {@link MockRestServiceServer}, so the vendor status → reason → stored status chain is the
 * production one and nothing leaves the JVM. The key below is synthetic.
 */
class DspmAdminPlatformScopeTest {

    private static final long CLIENT_ID = 10L;
    private static final String BASE = "https://matters.test";
    private static final String SECRET = "SyntheticSecret0123456789abcdefWXYZ";
    private static final String KEY = "mts_prod_k1_" + SECRET;
    private static final String ASSETS = BASE + "/api/v1/assets?page_size=1";
    private static final String PROBE = BASE + "/api/v1/scan-management/scan-history?page=1&page_size=1";
    private static final String PAGE = "{\"data\":[],\"pagination\":{\"total\":3}}";
    private static final String NO_PLATFORM =
            "{\"success\":false,\"message\":\"API key not permitted for this action\"}";

    private final AtomicReference<MattersProjectMapping> stored = new AtomicReference<>();
    private MockRestServiceServer server;
    private MattersAuditService audit;
    private DspmAdminController controller;
    private Client client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        audit = mock(MattersAuditService.class);

        MattersProjectMappingRepository mappings = mock(MattersProjectMappingRepository.class);
        ClientRepository clients = mock(ClientRepository.class);
        client = new Client();
        client.setId(CLIENT_ID);
        client.setName("Example Org");
        when(clients.findById(CLIENT_ID)).thenReturn(Optional.of(client));
        when(mappings.findByMattersProjectId(anyString())).thenReturn(Optional.empty());
        when(mappings.findByClientId(CLIENT_ID)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(mappings.save(any(MattersProjectMapping.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });

        MattersApiClient apiClient = new MattersApiClient(restTemplate, new ObjectMapper(), audit);
        MattersAdminService service = new MattersAdminService(
                mappings, clients, apiClient, audit, mock(MattersDspmService.class));
        controller = new DspmAdminController(service, mock(MattersAccessAuditRepository.class), clients);
    }

    private ResponseActions expectGet(String url) {
        return server.expect(ExpectedCount.once(), requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", KEY));
    }

    /** A mapping already on record, as an earlier asset-list-only Verify left it. */
    private void storeMapping(String status) {
        MattersProjectMapping mapping = new MattersProjectMapping();
        mapping.setClient(client);
        mapping.setMattersProjectId("project-1");
        mapping.setBaseUrl(BASE);
        mapping.setApiKey(KEY);
        mapping.setApiKeyEnv("prod");
        mapping.setApiKeyId("k1");
        mapping.setApiKeyLastFour("WXYZ");
        mapping.setConnectionStatus(status);
        stored.set(mapping);
    }

    private static DspmMappingRequest request(String apiKey) {
        return new DspmMappingRequest("project-1", "Example project", null, BASE, apiKey, null, true);
    }

    /** The Verify button: POST /mappings/{clientId}/verify. */
    private DspmMappingAdminView clickVerify() {
        return controller.verify(CLIENT_ID).getBody();
    }

    // ── Verify ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Verify: a key the asset list accepts but the console routes refuse is FORBIDDEN, not CONNECTED")
    void verifyWithoutPlatformScopeIsForbidden() {
        storeMapping(MattersProjectMapping.STATUS_CONNECTED);
        expectGet(ASSETS).andRespond(withSuccess(PAGE, MediaType.APPLICATION_JSON));
        expectGet(PROBE).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body(NO_PLATFORM));

        DspmMappingAdminView view = clickVerify();

        server.verify();
        assertThat(view.connectionStatus()).isEqualTo(MattersProjectMapping.STATUS_FORBIDDEN);
        assertThat(view.lastError()).isEqualTo("FORBIDDEN_SCOPE: This Matters key needs the PLATFORM scope");
        assertThat(stored.get().getConnectionStatus()).isEqualTo(MattersProjectMapping.STATUS_FORBIDDEN);
        assertThat(stored.get().getLastVerifiedAt()).isNull();
    }

    @Test
    @DisplayName("Verify: CONNECTED only after both the asset list and the console route answer")
    void verifyWithPlatformScopeIsConnected() {
        storeMapping(MattersProjectMapping.STATUS_UNVERIFIED);
        expectGet(ASSETS).andRespond(withSuccess(PAGE, MediaType.APPLICATION_JSON));
        expectGet(PROBE).andRespond(withSuccess("{\"data\":[],\"pagination\":{\"total\":0}}",
                MediaType.APPLICATION_JSON));

        DspmMappingAdminView view = clickVerify();

        server.verify();
        assertThat(view.connectionStatus()).isEqualTo(MattersProjectMapping.STATUS_CONNECTED);
        assertThat(view.lastError()).isNull();
        assertThat(view.lastVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("Verify: the other verdicts are unchanged, and a failed asset list skips the probe")
    void otherVerdictsUnchanged() {
        // 401 on the asset list: INVALID_KEY, and no probe (an unexpected request fails server.verify).
        storeMapping(MattersProjectMapping.STATUS_UNVERIFIED);
        expectGet(ASSETS).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"success\":false,\"message\":\"Invalid API key\"}"));
        assertThat(clickVerify().connectionStatus()).isEqualTo(MattersProjectMapping.STATUS_INVALID_KEY);
        server.verify();

        // A stored key that will not decrypt: DECRYPT_FAILED, and Matters is not called at all.
        server.reset();
        storeMapping(MattersProjectMapping.STATUS_CONNECTED);
        stored.get().setApiKey(null);
        assertThat(clickVerify().connectionStatus()).isEqualTo(MattersProjectMapping.STATUS_DECRYPT_FAILED);
        server.verify();

        // 403 on the asset list itself: FORBIDDEN, as before.
        server.reset();
        storeMapping(MattersProjectMapping.STATUS_UNVERIFIED);
        expectGet(ASSETS).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body(NO_PLATFORM));
        assertThat(clickVerify().connectionStatus()).isEqualTo(MattersProjectMapping.STATUS_FORBIDDEN);
        server.verify();

        // The probe keeps failing with 5xx: UNREACHABLE after its one retry.
        server.reset();
        storeMapping(MattersProjectMapping.STATUS_CONNECTED);
        expectGet(ASSETS).andRespond(withSuccess(PAGE, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.times(2), requestTo(PROBE)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(clickVerify().connectionStatus()).isEqualTo(MattersProjectMapping.STATUS_UNREACHABLE);
        server.verify();

        // A long rate limit on the probe says nothing about the key: the stored status is left alone.
        server.reset();
        storeMapping(MattersProjectMapping.STATUS_UNVERIFIED);
        expectGet(ASSETS).andRespond(withSuccess(PAGE, MediaType.APPLICATION_JSON));
        expectGet(PROBE).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "30"));
        DspmMappingAdminView limited = clickVerify();
        assertThat(limited.connectionStatus()).isEqualTo(MattersProjectMapping.STATUS_UNVERIFIED);
        assertThat(limited.lastError()).startsWith("RATE_LIMITED: ");
        server.verify();
    }

    @Test
    @DisplayName("Verify: the key is sent only as X-API-Key, never logged, returned or audited")
    void keyNeverLoggedOrReturned() {
        // Everything at DEBUG, ours and Spring's HTTP client, collected at the root.
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger ours = (Logger) LoggerFactory.getLogger("com.xammer.cloud");
        Logger spring = (Logger) LoggerFactory.getLogger("org.springframework.web");
        Level oursBefore = ours.getLevel();
        Level springBefore = spring.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        ours.setLevel(Level.DEBUG);
        spring.setLevel(Level.DEBUG);
        DspmMappingAdminView view;
        try {
            storeMapping(MattersProjectMapping.STATUS_UNVERIFIED);
            expectGet(ASSETS).andRespond(withSuccess(PAGE, MediaType.APPLICATION_JSON));
            expectGet(PROBE).andRespond(withStatus(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON).body(NO_PLATFORM));
            view = clickVerify();
        } finally {
            root.detachAppender(logs);
            ours.setLevel(oursBefore);
            spring.setLevel(springBefore);
        }

        server.verify();
        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().contains("verification failed"));
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains(SECRET));
        assertThat(view.toString()).doesNotContain(SECRET);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).recordMutationFailure(eq(CLIENT_ID), anyString(), eq("project-1"),
                eq("FORBIDDEN_SCOPE"), detail.capture());
        assertThat(detail.getValue()).doesNotContain(SECRET);
        verify(audit).recordCredentialFailure(eq(CLIENT_ID), eq("k1"), eq("project-1"),
                eq(MattersApiClient.PATH_SCAN_MANAGEMENT_HISTORY), eq("FORBIDDEN_SCOPE"), anyString());
    }

    // ── Save ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Save: a new key without the PLATFORM scope is FORBIDDEN in the save response")
    void saveWithoutPlatformScopeIsForbidden() {
        expectGet(ASSETS).andRespond(withSuccess(PAGE, MediaType.APPLICATION_JSON));
        expectGet(PROBE).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body(NO_PLATFORM));

        ResponseEntity<DspmMappingAdminView> response = controller.saveMapping(CLIENT_ID, request(KEY));

        server.verify();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().connectionStatus()).isEqualTo(MattersProjectMapping.STATUS_FORBIDDEN);
        assertThat(response.getBody().lastError()).contains("needs the PLATFORM scope");
        assertThat(response.getBody().toString()).doesNotContain(SECRET);
        assertThat(stored.get().getConnectionStatus()).isEqualTo(MattersProjectMapping.STATUS_FORBIDDEN);
    }

    @Test
    @DisplayName("Save: a rotated key with the PLATFORM scope comes back CONNECTED")
    void saveWithPlatformScopeIsConnected() {
        storeMapping(MattersProjectMapping.STATUS_FORBIDDEN);
        expectGet(ASSETS).andRespond(withSuccess(PAGE, MediaType.APPLICATION_JSON));
        expectGet(PROBE).andRespond(withSuccess("{\"data\":[],\"pagination\":{\"total\":0}}",
                MediaType.APPLICATION_JSON));

        DspmMappingAdminView view = controller.saveMapping(CLIENT_ID, request(KEY)).getBody();

        server.verify();
        assertThat(view.connectionStatus()).isEqualTo(MattersProjectMapping.STATUS_CONNECTED);
    }

    @Test
    @DisplayName("Save: an edit that keeps the key and the project keeps its verdict and calls nobody")
    void saveThatKeepsTheVerdictMakesNoCall() {
        storeMapping(MattersProjectMapping.STATUS_CONNECTED);
        stored.get().setLastVerifiedAt(Instant.parse("2026-11-01T00:00:00Z"));

        DspmMappingAdminView view = controller.saveMapping(CLIENT_ID, request(null)).getBody();

        server.verify();
        assertThat(view.connectionStatus()).isEqualTo(MattersProjectMapping.STATUS_CONNECTED);
        assertThat(view.lastVerifiedAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    // ── who may call ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The admin API still admits ROLE_BILLOPS_ADMIN and ROLE_SUPER_ADMIN only (the frontend's DSPM_ADMIN_ROLES)")
    void rolesUnchanged() {
        assertThat(DspmAdminController.class.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')");
    }
}
