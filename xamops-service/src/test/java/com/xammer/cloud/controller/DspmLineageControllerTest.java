package com.xammer.cloud.controller;

import com.xammer.cloud.controller.advice.MattersExceptionHandler;
import com.xammer.cloud.dto.matters.datastores.LineageCategories;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DspmBadRequestException;
import com.xammer.cloud.service.matters.ExposureCategory;
import com.xammer.cloud.service.matters.LineageFilters;
import com.xammer.cloud.service.matters.LineageRowQuery;
import com.xammer.cloud.service.matters.LineageService;
import com.xammer.cloud.service.matters.MattersApiException;
import com.xammer.cloud.service.matters.PublicLineageKey;
import com.xammer.cloud.service.matters.PublicLineageService;
import com.xammer.cloud.dto.matters.datastores.PublicFileLineage;
import com.xammer.cloud.dto.matters.datastores.PublicLineageDatastorePage;
import com.xammer.cloud.dto.matters.datastores.PublicLineageFilePage;
import com.xammer.cloud.dto.matters.datastores.SaasFileLineage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DspmLineageController} in standalone MockMvc with the real {@link XamLensTabGuard} over mocked rule
 * sources and a mocked {@link LineageService}, {@code GlobalExceptionHandler} registered before
 * {@code MattersExceptionHandler} as in {@code DspmSaasControllerTest}.
 */
class DspmLineageControllerTest {

    private static final String BASE = "/api/dspm/lineage";
    private static final String POLICY_UUID = "0000000a-0000-4000-8000-00000000000b";
    private static final String EXPOSURE_ID = "exposure-one";
    private static final String HEX_FILE_ID = "abcdef0123456789abcdef01";
    private static final String UUID_FILE_ID = "0000000c-0000-4000-8000-00000000000d";
    private static final String TAB = "secops-xamlens";

    private GlobalTabRuleReader rules;
    private UserTabAccessRepository tabs;
    private LineageService service;
    private PublicLineageService publicService;
    private MockMvc mvc;

    private final ClientUserDetails xamops = principal(500L, "ROLE_XAMOPS");

    private static ClientUserDetails principal(long userId, String role) {
        return new ClientUserDetails("user" + userId + "@example.com", "pw",
                List.of(new SimpleGrantedAuthority(role)), 10L, userId, "tenant-a");
    }

    /** One request: a path and its query parameters as name/value pairs. */
    private record Call(String path, String... params) {
        MockHttpServletRequestBuilder request() {
            MockHttpServletRequestBuilder builder = get(path);
            for (int i = 0; i < params.length; i += 2) {
                builder.param(params[i], params[i + 1]);
            }
            return builder;
        }

        Call with(String name, String value) {
            String[] more = Arrays.copyOf(params, params.length + 2);
            more[params.length] = name;
            more[params.length + 1] = value;
            return new Call(path, more);
        }
    }

    private static final String POLICIES = BASE + "/policies";
    private static final String DATASTORES = BASE + "/policies/" + POLICY_UUID + "/datastores";
    private static final String FILES = BASE + "/datastores/" + EXPOSURE_ID + "/files";

    /** Endpoints L1 to L6 of design 5.2, each with valid parameters. */
    private static List<Call> everyEndpoint() {
        return List.of(
                new Call(BASE + "/categories"),
                new Call(BASE + "/filter-options", "level", "policy"),
                new Call(POLICIES, "category", "SENSITIVE_FINDINGS", "q", "plan", "skip", "7", "limit", "50",
                        "severity", "HIGH,MEDIUM", "datastoreName_exclude", "Lab Laptop", "label", "PII"),
                new Call(DATASTORES, "q", "lab", "skip", "7", "limit", "7", "severity_exclude", "LOW"),
                new Call(FILES, "skip", "7", "limit", "100"),
                new Call(BASE + "/files/" + HEX_FILE_ID + "/lineage"));
    }

    @BeforeEach
    void setUp() {
        rules = mock(GlobalTabRuleReader.class);
        tabs = mock(UserTabAccessRepository.class);
        when(rules.rulesFor(anyLong())).thenReturn(GlobalTabRuleReader.UserRules.NONE);
        when(tabs.findTabNamesByUserId(anyLong())).thenReturn(List.of());
        service = mock(LineageService.class);
        publicService = mock(PublicLineageService.class);
        DspmLineageController controller =
                new DspmLineageController(new XamLensTabGuard(rules, tabs), service, publicService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(), new MattersExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
        authenticateAs(xamops);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(ClientUserDetails principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private ResultActions perform(Call call) throws Exception {
        return mvc.perform(call.request());
    }

    private void expectInvalid(Call call, String parameter) throws Exception {
        perform(call).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.parameter").value(parameter))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ── routing and tenancy ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("all 6 endpoints answer 200 and pass the parsed values and the principal's client id")
    void everyEndpointAnswers() throws Exception {
        when(service.categories(10L)).thenReturn(LineageCategories.unavailable());

        for (Call call : everyEndpoint()) {
            perform(call).andExpect(status().isOk());
        }

        verify(service).categories(10L);
        verify(service).filterOptions(10L, "policy");
        verify(service).policies(10L, ExposureCategory.SENSITIVE_FINDINGS, new LineageRowQuery("plan", 7, 50,
                new LineageFilters(new LineageFilters.Selection(false, List.of("HIGH", "MEDIUM")),
                        new LineageFilters.Selection(true, List.of("Lab Laptop")),
                        new LineageFilters.Selection(false, List.of("PII")))));
        verify(service).datastores(10L, POLICY_UUID, new LineageRowQuery("lab", 7, 7,
                new LineageFilters(new LineageFilters.Selection(true, List.of("LOW")), null, null)));
        verify(service).files(10L, EXPOSURE_ID, 7, 100);
        verify(service).fileLineage(10L, HEX_FILE_ID);
    }

    @Test
    @DisplayName("defaults: skip 0, limit 7, no search; ids lower-cased; a UUID file id is accepted")
    void defaultsAndNormalisation() throws Exception {
        when(service.categories(10L)).thenReturn(LineageCategories.unavailable());
        perform(new Call(BASE + "/categories")).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.categories[0].id").value("SENSITIVE_FINDINGS"))
                .andExpect(jsonPath("$.categories[0].exposures").isEmpty());
        perform(new Call(POLICIES, "category", "STALE_DATA", "q", "   ")).andExpect(status().isOk());
        perform(new Call(BASE + "/policies/" + POLICY_UUID.toUpperCase() + "/datastores")).andExpect(status().isOk());
        perform(new Call(FILES)).andExpect(status().isOk());
        perform(new Call(BASE + "/files/" + UUID_FILE_ID.toUpperCase() + "/lineage")).andExpect(status().isOk());
        perform(new Call(BASE + "/filter-options", "level", "datastore")).andExpect(status().isOk());

        verify(service).policies(10L, ExposureCategory.STALE_DATA, new LineageRowQuery(null, 0, 7, LineageFilters.NONE));
        verify(service).datastores(10L, POLICY_UUID, new LineageRowQuery(null, 0, 7, LineageFilters.NONE));
        verify(service).files(10L, EXPOSURE_ID, 0, 7);
        verify(service).fileLineage(10L, UUID_FILE_ID);
        verify(service).filterOptions(10L, "datastore");
    }

    @Test
    @DisplayName("?clientId=99 is a 400 on every endpoint, with no service call")
    void clientIdIsRejected() throws Exception {
        for (Call call : everyEndpoint()) {
            expectInvalid(call.with("clientId", "99"), "clientId");
        }
        verifyNoInteractions(service);
    }

    // ── tab guard ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a principal the guard refuses gets 403 TAB_ACCESS_DENIED on every endpoint, with no service call")
    void guardRefusalIsAForbidden() throws Exception {
        when(rules.rulesFor(500L)).thenReturn(new GlobalTabRuleReader.UserRules(List.of(TAB), List.of()));

        for (Call call : everyEndpoint()) {
            perform(call).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TAB_ACCESS_DENIED"))
                    .andExpect(jsonPath("$.message").value("You do not have access to XamLens."));
        }
        verifyNoInteractions(service);
    }

    // ── validation ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every design 5.3 validation row is a 400 naming the parameter, with no service call")
    void validationFailuresNameTheParameter() throws Exception {
        expectInvalid(new Call(BASE + "/categories", "q", "x"), "q");

        String options = BASE + "/filter-options";
        expectInvalid(new Call(options), "level");
        expectInvalid(new Call(options, "level", "file"), "level");
        expectInvalid(new Call(options, "level", "policy", "level", "datastore"), "level");
        expectInvalid(new Call(options, "level", "policy", "skip", "0"), "skip");

        Call policies = new Call(POLICIES, "category", "SENSITIVE_FINDINGS");
        expectInvalid(new Call(POLICIES), "category");
        expectInvalid(new Call(POLICIES, "category", "sensitive_findings"), "category");
        expectInvalid(new Call(POLICIES, "category", "DATA_RESIDENCY"), "category");
        expectInvalid(new Call(POLICIES, "category", "STALE_DATA", "category", "RISKY_USER"), "category");
        expectInvalid(policies.with("q", "x".repeat(201)), "q");
        expectInvalid(policies.with("q", "a").with("q", "b"), "q");
        expectInvalid(policies.with("skip", "10001"), "skip");
        expectInvalid(policies.with("skip", "-1"), "skip");
        expectInvalid(policies.with("limit", "0"), "limit");
        expectInvalid(policies.with("limit", "51"), "limit");
        expectInvalid(policies.with("limit", "7").with("limit", "7"), "limit");
        expectInvalid(policies.with("severity", ","), "severity");
        expectInvalid(policies.with("label", "x".repeat(129)), "label");
        expectInvalid(policies.with("datastoreName", "a\u0007b"), "datastoreName");
        expectInvalid(policies.with("severity", "HIGH").with("severity_exclude", "LOW"), "severity_exclude");
        expectInvalid(policies.with("page", "1"), "page");

        expectInvalid(new Call(BASE + "/policies/not-a-uuid/datastores"), "policyId");
        expectInvalid(new Call(DATASTORES, "datastoreName", "Lab Laptop"), "datastoreName");
        expectInvalid(new Call(DATASTORES, "label_exclude", "PII"), "label_exclude");
        expectInvalid(new Call(DATASTORES, "limit", "51"), "limit");
        expectInvalid(new Call(DATASTORES, "category", "STALE_DATA"), "category");

        expectInvalid(new Call(BASE + "/datastores/bad_id/files"), "exposureId");
        expectInvalid(new Call(BASE + "/datastores/" + "x".repeat(65) + "/files"), "exposureId");
        expectInvalid(new Call(FILES, "limit", "101"), "limit");
        expectInvalid(new Call(FILES, "skip", "0", "skip", "1"), "skip");
        expectInvalid(new Call(FILES, "q", "x"), "q");

        expectInvalid(new Call(BASE + "/files/not-a-file-id/lineage"), "fileId");
        expectInvalid(new Call(BASE + "/files/abcdef0123456789abcdef0/lineage"), "fileId");
        expectInvalid(new Call(BASE + "/files/" + HEX_FILE_ID + "/lineage", "skip", "0"), "skip");

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a filter value that is not an option is a 400 on its own parameter")
    void nonOptionIsRejected() throws Exception {
        when(service.policies(eq(10L), eq(ExposureCategory.STALE_DATA), any())).thenThrow(
                new DspmBadRequestException("severity_exclude", "severity_exclude is not an option of this project"));

        expectInvalid(new Call(POLICIES, "category", "STALE_DATA", "severity_exclude", "UNKNOWN"), "severity_exclude");
    }

    // ── error mapping ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Matters failures map through MattersExceptionHandler")
    void mattersFailuresAreMapped() throws Exception {
        when(service.categories(10L)).thenThrow(new MattersApiException(
                MattersApiException.Reason.BAD_UPSTREAM_RESPONSE, "Matters refused the exposure route for this key"));
        when(service.files(10L, EXPOSURE_ID, 0, 7)).thenThrow(
                new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND, "Asset not found"));

        perform(new Call(BASE + "/categories")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("BAD_UPSTREAM_RESPONSE"));
        perform(new Call(FILES)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
    }

    // ── public path, P1 to P3 (build 7) ──────────────────────────────────────────────────────────

    private static final String AGENT = "0123456789abcdef01234567";
    private static final String INTEGRATION = "fedcba9876543210fedcba98";
    private static final String RESOURCE = "Res_Synthetic-1";
    private static final String DOC_ID = "Doc_Synthetic-0001";
    private static final String PUBLIC = BASE + "/public/datastores";
    private static final String ENDPOINT_ID = "ep-" + AGENT;
    private static final String DRIVE_ID = "dr-" + INTEGRATION + "-" + RESOURCE;
    private static final PublicLineageKey ENDPOINT_KEY = PublicLineageKey.endpoint(AGENT);
    private static final PublicLineageKey DRIVE_KEY = PublicLineageKey.drive(INTEGRATION, RESOURCE);

    /** Endpoints P1 to P3 of build 7 design 5.2, each with valid parameters. */
    private static List<Call> everyPublicEndpoint() {
        return List.of(
                new Call(PUBLIC, "q", "lab", "skip", "7", "limit", "50"),
                new Call(PUBLIC + "/" + ENDPOINT_ID + "/files", "skip", "7", "limit", "100"),
                new Call(PUBLIC + "/" + DRIVE_ID + "/files/" + DOC_ID + "/lineage"));
    }

    @Test
    @DisplayName("P1 to P3 answer 200 and pass the parsed values and the principal's client id")
    void everyPublicEndpointAnswers() throws Exception {
        when(publicService.datastores(10L, "lab", 7, 50)).thenReturn(new PublicLineageDatastorePage(List.of(
                new PublicLineageDatastorePage.Datastore(ENDPOINT_ID, "ENDPOINT", "Lab Laptop", "ENDPOINT", null)),
                1, 7, 50));
        when(publicService.files(10L, ENDPOINT_KEY, 7, 100)).thenReturn(new PublicLineageFilePage(List.of(), 0, 7, 100));
        when(publicService.fileLineage(10L, DRIVE_KEY, DOC_ID)).thenReturn(
                new PublicFileLineage("DRIVE", null, SaasFileLineage.unavailable(), null));

        List<Call> calls = everyPublicEndpoint();
        perform(calls.get(0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].id").value(ENDPOINT_ID))
                .andExpect(jsonPath("$.rows[0].files").isEmpty())
                .andExpect(jsonPath("$.total").value(1));
        perform(calls.get(1)).andExpect(status().isOk()).andExpect(jsonPath("$.rows").isEmpty());
        perform(calls.get(2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DRIVE"))
                .andExpect(jsonPath("$.asset").isEmpty())
                .andExpect(jsonPath("$.ownerEmail").isEmpty());

        verify(publicService).datastores(10L, "lab", 7, 50);
        verify(publicService).files(10L, ENDPOINT_KEY, 7, 100);
        verify(publicService).fileLineage(10L, DRIVE_KEY, DOC_ID);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("public defaults: no search, skip 0, limit 7; hex lower-cased; endpoint UUID lower-cased; doc id kept")
    void publicDefaultsAndNormalisation() throws Exception {
        String uuid = "0000000c-0000-4000-8000-00000000000d";
        perform(new Call(PUBLIC)).andExpect(status().isOk());
        perform(new Call(PUBLIC, "q", "   ")).andExpect(status().isOk());
        perform(new Call(PUBLIC + "/ep-" + AGENT.toUpperCase() + "/files")).andExpect(status().isOk());
        perform(new Call(PUBLIC + "/dr-" + INTEGRATION.toUpperCase() + "-" + RESOURCE + "/files")).andExpect(status().isOk());
        perform(new Call(PUBLIC + "/" + ENDPOINT_ID + "/files/" + uuid.toUpperCase() + "/lineage")).andExpect(status().isOk());
        perform(new Call(PUBLIC + "/" + DRIVE_ID + "/files/" + DOC_ID + "/lineage")).andExpect(status().isOk());

        verify(publicService, org.mockito.Mockito.times(2)).datastores(10L, null, 0, 7);
        verify(publicService).files(10L, ENDPOINT_KEY, 0, 7);
        verify(publicService).files(10L, DRIVE_KEY, 0, 7);
        verify(publicService).fileLineage(10L, ENDPOINT_KEY, uuid);
        verify(publicService).fileLineage(10L, DRIVE_KEY, DOC_ID);
    }

    @Test
    @DisplayName("P1 to P3: a principal the guard refuses gets 403 TAB_ACCESS_DENIED, with no service call")
    void publicGuardRefusal() throws Exception {
        when(rules.rulesFor(500L)).thenReturn(new GlobalTabRuleReader.UserRules(List.of(TAB), List.of()));

        for (Call call : everyPublicEndpoint()) {
            perform(call).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TAB_ACCESS_DENIED"))
                    .andExpect(jsonPath("$.message").value("You do not have access to XamLens."));
        }
        // Even an invalid request is refused by the guard first.
        perform(new Call(PUBLIC + "/not-an-id/files", "limit", "0")).andExpect(status().isForbidden());
        verifyNoInteractions(publicService, service);
    }

    @Test
    @DisplayName("P1 to P3: every design 5.4 row is a 400 naming the parameter, never the value, with no service call")
    void publicValidation() throws Exception {
        for (Call call : everyPublicEndpoint()) {
            expectInvalid(call.with("clientId", "99"), "clientId");
        }

        expectInvalid(new Call(PUBLIC, "q", "x".repeat(201)), "q");
        expectInvalid(new Call(PUBLIC, "q", "a\u0007b"), "q");
        expectInvalid(new Call(PUBLIC, "q", "a", "q", "b"), "q");
        expectInvalid(new Call(PUBLIC, "skip", "-1"), "skip");
        expectInvalid(new Call(PUBLIC, "skip", "10001"), "skip");
        expectInvalid(new Call(PUBLIC, "skip", "0", "skip", "1"), "skip");
        expectInvalid(new Call(PUBLIC, "limit", "0"), "limit");
        expectInvalid(new Call(PUBLIC, "limit", "51"), "limit");
        expectInvalid(new Call(PUBLIC, "category", "STALE_DATA"), "category");
        expectInvalid(new Call(PUBLIC, "severity", "HIGH"), "severity");

        String files = PUBLIC + "/" + DRIVE_ID + "/files";
        expectInvalid(new Call(files, "limit", "101"), "limit");
        expectInvalid(new Call(files, "limit", "x"), "limit");
        expectInvalid(new Call(files, "skip", "x"), "skip");
        expectInvalid(new Call(files, "q", "x"), "q");
        expectInvalid(new Call(PUBLIC + "/" + AGENT + "/files"), "datastoreId");
        expectInvalid(new Call(PUBLIC + "/ep-" + AGENT + "0/files"), "datastoreId");
        expectInvalid(new Call(PUBLIC + "/dr-" + INTEGRATION + "-" + "r".repeat(65) + "/files"), "datastoreId");
        expectInvalid(new Call(PUBLIC + "/exposure-one/files"), "datastoreId");

        String uuid = "0000000c-0000-4000-8000-00000000000d";
        expectInvalid(new Call(PUBLIC + "/" + DRIVE_ID + "/files/" + uuid + "/lineage"), "fileId");
        expectInvalid(new Call(PUBLIC + "/" + ENDPOINT_ID + "/files/" + DOC_ID + "/lineage"), "fileId");
        expectInvalid(new Call(PUBLIC + "/" + DRIVE_ID + "/files/" + "d".repeat(129) + "/lineage"), "fileId");
        expectInvalid(new Call(PUBLIC + "/bad/files/" + DOC_ID + "/lineage"), "datastoreId");
        expectInvalid(new Call(PUBLIC + "/" + DRIVE_ID + "/files/" + DOC_ID + "/lineage", "skip", "0"), "skip");

        perform(new Call(PUBLIC + "/" + ENDPOINT_ID + "/files/" + DOC_ID + "/lineage"))
                .andExpect(jsonPath("$.message").value("fileId must be a UUID for an endpoint file"));
        perform(new Call(PUBLIC + "/" + DRIVE_ID + "/files/" + uuid + "/lineage"))
                .andExpect(jsonPath("$.message").value("fileId must be a drive document id"));
        perform(new Call(PUBLIC + "/needle-value/files"))
                .andExpect(jsonPath("$.message").value("datastoreId must be a datastore of the lineage page"));

        verifyNoInteractions(publicService, service);
    }

    @Test
    @DisplayName("P1 to P3: Matters failures map through MattersExceptionHandler")
    void publicMattersFailuresAreMapped() throws Exception {
        when(publicService.datastores(10L, null, 0, 7)).thenThrow(new MattersApiException(
                MattersApiException.Reason.UPSTREAM_UNAVAILABLE, "Matters returned 503"));
        when(publicService.files(10L, ENDPOINT_KEY, 0, 7)).thenThrow(
                new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND, "Asset not found"));
        when(publicService.fileLineage(eq(10L), eq(DRIVE_KEY), any())).thenThrow(
                new MattersApiException(MattersApiException.Reason.FORBIDDEN_SCOPE, "scope"));

        perform(new Call(PUBLIC)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
        perform(new Call(PUBLIC + "/" + ENDPOINT_ID + "/files")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
        perform(new Call(PUBLIC + "/" + DRIVE_ID + "/files/" + DOC_ID + "/lineage")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_SCOPE"));
    }
}
