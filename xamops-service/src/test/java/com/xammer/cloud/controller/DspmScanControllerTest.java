package com.xammer.cloud.controller;

import com.xammer.cloud.controller.advice.MattersExceptionHandler;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.MattersApiException;
import com.xammer.cloud.service.matters.SaasChannelQuery;
import com.xammer.cloud.service.matters.SaasUserQuery;
import com.xammer.cloud.service.matters.ScanConfigurationService;
import com.xammer.cloud.service.matters.ScanListQuery;
import com.xammer.cloud.service.matters.ScanManagementService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DspmScanController} in standalone MockMvc with the real {@link XamLensTabGuard} over mocked rule
 * sources and mocked services, {@code GlobalExceptionHandler} registered before
 * {@code MattersExceptionHandler}, as in {@code DspmSaasControllerTest}.
 */
class DspmScanControllerTest {

    private static final String TAB = "secops-xamlens";
    private static final String SM = "/api/dspm/scan-management";
    private static final String HEX = "abcdef0123456789abcdef01";
    private static final String UUID = "0000000a-0000-4000-8000-00000000000b";
    private static final String SOURCE_ID = "0123456789abcdef01234567";
    private static final String SAAS = SM + "/saas-integrations/" + HEX;
    private static final String DS = "/api/dspm/datastores/" + SOURCE_ID;

    private GlobalTabRuleReader rules;
    private UserTabAccessRepository tabs;
    private ScanManagementService scanService;
    private ScanConfigurationService configurationService;
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

    /** S1 to S12 and X1 to X3 of build 6 design 5.2, each with valid parameters. */
    private static List<Call> everyEndpoint() {
        return List.of(
                new Call(SM + "/schedules", "q", "nightly", "platform", "cloud", "status", "ACTIVE",
                        "coverage", "ALL_DATASTORES", "page", "2", "pageSize", "20"),
                new Call(SM + "/schedules/" + HEX),
                new Call(SM + "/scan-history", "q", "nightly", "coverage", "SELECTED_DATASTORES", "platform",
                        "endpoint", "status", "SUCCESS", "trigger", "SCHEDULED", "page", "1", "pageSize", "10"),
                new Call(SM + "/scan-history/" + UUID),
                new Call(SM + "/scan-history/" + UUID + "/datastores", "q", "laptop", "account", " ops account ",
                        "status", "FAILED", "page", "3", "pageSize", "5"),
                new Call(SM + "/scan-scopes", "q", "prod", "platform", "on_prem", "page", "1", "pageSize", "10"),
                new Call(SM + "/scan-scopes/" + UUID),
                new Call(SM + "/saas-integrations"),
                new Call(SAAS + "/configuration"),
                new Call(SAAS + "/users", "driveType", "GMAIL", "q", "ann", "status", "SUSPENDED",
                        "domain", "example.test", "page", "2", "pageSize", "25"),
                new Call(SAAS + "/channels", "q", "general", "channelType", "PUBLIC", "channelStatus", "ACTIVE",
                        "scanState", "ENABLED", "isNew", "true", "page", "1", "pageSize", "10"),
                new Call(SM + "/endpoint-configuration"),
                new Call(DS + "/sync-status"),
                new Call(DS + "/scan-settings"),
                new Call(DS + "/scan-settings/databases"));
    }

    @BeforeEach
    void setUp() {
        rules = mock(GlobalTabRuleReader.class);
        tabs = mock(UserTabAccessRepository.class);
        when(rules.rulesFor(anyLong())).thenReturn(GlobalTabRuleReader.UserRules.NONE);
        when(tabs.findTabNamesByUserId(anyLong())).thenReturn(List.of());
        scanService = mock(ScanManagementService.class);
        configurationService = mock(ScanConfigurationService.class);

        DspmScanController controller = new DspmScanController(new XamLensTabGuard(rules, tabs), scanService,
                configurationService);
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

    private void verifyNoServiceCall() {
        verifyNoInteractions(scanService, configurationService);
    }

    private static ScanListQuery list(ScanListQuery.Kind kind, String search, Map<String, String> filters,
                                      int page, int pageSize) {
        return new ScanListQuery(kind, search, filters, page, pageSize);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // ── routing and tenancy ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ROLE_XAMOPS with the tab: all 15 endpoints answer 200, each service gets the validated values")
    void everyEndpointAnswers() throws Exception {
        when(tabs.findTabNamesByUserId(500L)).thenReturn(List.of("cloudops-cloudk8s", TAB));

        for (Call call : everyEndpoint()) {
            perform(call).andExpect(status().isOk());
        }

        verify(scanService).schedules(10L, list(ScanListQuery.Kind.SCHEDULES, "nightly",
                ordered("platform", "cloud", "status", "ACTIVE", "coverage", "ALL_DATASTORES"), 2, 20));
        verify(scanService).schedule(10L, HEX);
        verify(scanService).runs(10L, list(ScanListQuery.Kind.RUNS, "nightly", ordered("coverage",
                "SELECTED_DATASTORES", "platform", "endpoint", "status", "SUCCESS", "trigger", "SCHEDULED"), 1, 10));
        verify(scanService).run(10L, UUID);
        verify(scanService).runDatastores(10L, UUID, list(ScanListQuery.Kind.RUN_DATASTORES, "laptop",
                ordered("account", "ops account", "status", "FAILED"), 3, 5));
        verify(scanService).scopes(10L, list(ScanListQuery.Kind.SCOPES, "prod", ordered("platform", "on_prem"), 1, 10));
        verify(scanService).scope(10L, UUID);
        verify(configurationService).saasIntegrations(10L);
        verify(configurationService).saasConfiguration(10L, HEX);
        verify(configurationService).saasUsers(10L, HEX,
                new SaasUserQuery("GMAIL", "ann", "SUSPENDED", "example.test", 2, 25));
        verify(configurationService).saasChannels(10L, HEX,
                new SaasChannelQuery("general", "PUBLIC", "ACTIVE", "ENABLED", true, 1, 10));
        verify(configurationService).endpointConfiguration(10L);
        verify(configurationService).syncStatus(10L, SOURCE_ID);
        verify(configurationService).scanSettings(10L, SOURCE_ID);
        verify(configurationService).databases(10L, SOURCE_ID);
    }

    @Test
    @DisplayName("defaults: page 1, pageSize 10, no filters; ids lower-cased")
    void defaultsAndNormalisation() throws Exception {
        perform(new Call(SM + "/schedules")).andExpect(status().isOk());
        perform(new Call(SM + "/schedules/ABCDEF0123456789ABCDEF01")).andExpect(status().isOk());
        perform(new Call(SM + "/scan-history/0000000A-0000-4000-8000-00000000000B")).andExpect(status().isOk());
        perform(new Call(SM + "/saas-integrations/ABCDEF0123456789ABCDEF01/users", "driveType", "GMAIL"))
                .andExpect(status().isOk());
        perform(new Call(SM + "/saas-integrations/" + HEX + "/channels", "isNew", "false")).andExpect(status().isOk());
        perform(new Call("/api/dspm/datastores/0123456789ABCDEF01234567/sync-status")).andExpect(status().isOk());

        verify(scanService).schedules(10L, list(ScanListQuery.Kind.SCHEDULES, null, Map.of(), 1, 10));
        verify(scanService).schedule(10L, HEX);
        verify(scanService).run(10L, UUID);
        verify(configurationService).saasUsers(10L, HEX, new SaasUserQuery("GMAIL", null, null, null, 1, 10));
        verify(configurationService).saasChannels(10L, HEX, new SaasChannelQuery(null, null, null, null, false, 1, 10));
        verify(configurationService).syncStatus(10L, SOURCE_ID);
    }

    @Test
    @DisplayName("?clientId=99 is a 400 on every endpoint, with no service call")
    void clientIdIsRejected() throws Exception {
        for (Call call : everyEndpoint()) {
            expectInvalid(call.with("clientId", "99"), "clientId");
        }
        verifyNoServiceCall();
    }

    // ── tab guard ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a principal the guard refuses gets 403 TAB_ACCESS_DENIED everywhere, with no service call")
    void guardRefusalIsAForbidden() throws Exception {
        when(rules.rulesFor(500L)).thenReturn(new GlobalTabRuleReader.UserRules(List.of(TAB), List.of()));

        for (Call call : everyEndpoint()) {
            perform(call).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TAB_ACCESS_DENIED"))
                    .andExpect(jsonPath("$.message").value("You do not have access to XamLens."));
        }
        verifyNoServiceCall();
    }

    @Test
    @DisplayName("the guard runs before validation: a refused principal with a malformed id gets 403")
    void guardBeforeValidation() throws Exception {
        when(rules.rulesFor(500L)).thenReturn(new GlobalTabRuleReader.UserRules(List.of(TAB), List.of()));

        perform(new Call(SM + "/schedules/not-an-id")).andExpect(status().isForbidden());
        perform(new Call(SAAS + "/users")).andExpect(status().isForbidden());
        verifyNoServiceCall();
    }

    // ── validation ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every validation class is a 400 naming the parameter, with no service call")
    void validationFailuresNameTheParameter() throws Exception {
        // unlisted names, including another list's filter
        expectInvalid(new Call(SM + "/schedules", "trigger", "SCHEDULED"), "trigger");
        expectInvalid(new Call(SM + "/scan-scopes", "status", "ACTIVE"), "status");
        expectInvalid(new Call(SM + "/scan-history/" + UUID + "/datastores", "platform", "cloud"), "platform");
        expectInvalid(new Call(SM + "/saas-integrations", "page", "1"), "page");
        expectInvalid(new Call(SM + "/endpoint-configuration", "q", "x"), "q");
        expectInvalid(new Call(DS + "/scan-settings", "foo", "1"), "foo");

        // path ids
        expectInvalid(new Call(SM + "/schedules/not-an-id"), "scheduleId");
        expectInvalid(new Call(SM + "/schedules/abcdef0123456789abcdef0"), "scheduleId");
        expectInvalid(new Call(SM + "/scan-history/0000000a-0000-4000-8000-00000000000"), "runId");
        expectInvalid(new Call(SM + "/scan-history/not-a-run/datastores"), "runId");
        expectInvalid(new Call(SM + "/scan-scopes/xyz"), "scopeId");
        expectInvalid(new Call(SM + "/saas-integrations/" + UUID + "/configuration"), "integrationId");
        expectInvalid(new Call(SM + "/saas-integrations/abc/users", "driveType", "GMAIL"), "integrationId");
        expectInvalid(new Call(SM + "/saas-integrations/zzzzzzzzzzzzzzzzzzzzzzzz/channels"), "integrationId");
        expectInvalid(new Call("/api/dspm/datastores/not-a-source-id/sync-status"), "sourceId");
        expectInvalid(new Call("/api/dspm/datastores/" + UUID + "/scan-settings"), "sourceId");
        expectInvalid(new Call("/api/dspm/datastores/0123456789abcdef0123456/scan-settings/databases"), "sourceId");

        // values
        expectInvalid(new Call(SM + "/schedules", "platform", "aws"), "platform");
        expectInvalid(new Call(SM + "/scan-history", "platform", "CLOUD"), "platform");
        expectInvalid(new Call(SM + "/schedules", "status", "active"), "status");
        expectInvalid(new Call(SM + "/scan-history", "trigger", "SCHEDULED-X"), "trigger");
        expectInvalid(new Call(SM + "/scan-history", "coverage", "ALL", "coverage", "SELECTED"), "coverage");
        expectInvalid(new Call(SM + "/scan-history/" + UUID + "/datastores", "account", "a,b"), "account");
        expectInvalid(new Call(SM + "/scan-history/" + UUID + "/datastores", "account", "x".repeat(129)), "account");
        expectInvalid(new Call(SM + "/scan-scopes", "pageSize", "101"), "pageSize");
        expectInvalid(new Call(SM + "/scan-scopes", "page", "0"), "page");
        expectInvalid(new Call(SM + "/schedules", "page", "1002", "pageSize", "10"), "page");
        expectInvalid(new Call(SM + "/schedules", "q", "x".repeat(201)), "q");
        expectInvalid(new Call(SM + "/scan-history", "q", "night\u0007ly"), "q");

        // users and channels
        expectInvalid(new Call(SAAS + "/users"), "driveType");
        expectInvalid(new Call(SAAS + "/users", "q", "ann"), "driveType");
        expectInvalid(new Call(SAAS + "/users", "driveType", "gmail"), "driveType");
        expectInvalid(new Call(SAAS + "/users", "driveType", "GMAIL", "status", "DELETED"), "status");
        expectInvalid(new Call(SAAS + "/users", "driveType", "GMAIL", "domain", "a/b"), "domain");
        expectInvalid(new Call(SAAS + "/users", "driveType", "GMAIL", "pageSize", "101"), "pageSize");
        expectInvalid(new Call(SAAS + "/users", "driveType", "GMAIL", "q", "x".repeat(201)), "q");
        expectInvalid(new Call(SAAS + "/channels", "isNew", "yes"), "isNew");
        expectInvalid(new Call(SAAS + "/channels", "channelType", "public"), "channelType");
        expectInvalid(new Call(SAAS + "/channels", "scanState", "ON-OFF"), "scanState");
        expectInvalid(new Call(SAAS + "/channels", "channelStatus", "A", "channelStatus", "B"), "channelStatus");
        expectInvalid(new Call(SAAS + "/channels", "driveType", "SLACK"), "driveType");

        verifyNoServiceCall();
    }

    // ── error mapping ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ASSET_NOT_FOUND is a 404, a bad upstream body a 502")
    void errorMapping() throws Exception {
        when(scanService.schedule(10L, HEX)).thenThrow(
                new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND, "Schedule not found"));
        when(configurationService.syncStatus(10L, SOURCE_ID)).thenThrow(
                new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND, "AssetId Not Found"));
        when(configurationService.endpointConfiguration(10L)).thenThrow(
                new MattersApiException(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE, "unreadable"));

        perform(new Call(SM + "/schedules/" + HEX)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
        perform(new Call(DS + "/sync-status")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
        perform(new Call(SM + "/endpoint-configuration")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("BAD_UPSTREAM_RESPONSE"));
    }
}
