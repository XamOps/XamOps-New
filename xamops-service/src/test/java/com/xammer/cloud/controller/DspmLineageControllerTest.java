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
        DspmLineageController controller = new DspmLineageController(new XamLensTabGuard(rules, tabs), service);
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
}
