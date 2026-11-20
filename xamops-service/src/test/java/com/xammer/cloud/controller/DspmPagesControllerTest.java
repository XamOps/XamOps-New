package com.xammer.cloud.controller;

import com.xammer.cloud.controller.advice.MattersExceptionHandler;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DashboardService;
import com.xammer.cloud.service.matters.DspmPagesService;
import com.xammer.cloud.service.matters.MattersApiException;
import com.xammer.cloud.service.matters.RemediationQuery;
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DspmPagesController} in standalone MockMvc with the real {@link XamLensTabGuard} over mocked rule
 * sources and mocked services, {@code GlobalExceptionHandler} registered before
 * {@code MattersExceptionHandler}, as in {@code DspmSaasControllerTest}.
 */
class DspmPagesControllerTest {

    private static final String TAB = "secops-xamlens";

    private GlobalTabRuleReader rules;
    private UserTabAccessRepository tabs;
    private DashboardService dashboardService;
    private DspmPagesService pagesService;
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

    /** P1 to P4 of build 6 design 5.2, each with valid parameters. */
    private static List<Call> everyEndpoint() {
        return List.of(
                new Call("/api/dspm/dashboard"),
                new Call("/api/dspm/document-classes"),
                new Call("/api/dspm/assists"),
                new Call("/api/dspm/remediation/activity", "q", " plan ", "status", "WAITING_APPROVAL",
                        "actionType", "MAKE_PRIVATE", "skip", "20", "limit", "25"));
    }

    @BeforeEach
    void setUp() {
        rules = mock(GlobalTabRuleReader.class);
        tabs = mock(UserTabAccessRepository.class);
        when(rules.rulesFor(anyLong())).thenReturn(GlobalTabRuleReader.UserRules.NONE);
        when(tabs.findTabNamesByUserId(anyLong())).thenReturn(List.of());
        dashboardService = mock(DashboardService.class);
        pagesService = mock(DspmPagesService.class);

        DspmPagesController controller = new DspmPagesController(new XamLensTabGuard(rules, tabs), dashboardService,
                pagesService);
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
        verifyNoInteractions(dashboardService, pagesService);
    }

    // ── routing and tenancy ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ROLE_XAMOPS with the tab: all 4 endpoints answer 200, each service gets the principal's client id")
    void everyEndpointAnswers() throws Exception {
        when(tabs.findTabNamesByUserId(500L)).thenReturn(List.of("cloudops-cloudk8s", TAB));

        for (Call call : everyEndpoint()) {
            perform(call).andExpect(status().isOk());
        }

        verify(dashboardService).dashboard(10L);
        verify(pagesService).documentClasses(10L);
        verify(pagesService).assists(10L);
        verify(pagesService).remediation(10L, new RemediationQuery("plan", "WAITING_APPROVAL", "MAKE_PRIVATE", 20, 25));
    }

    @Test
    @DisplayName("remediation defaults: skip 0, limit 10, a blank q is absent")
    void remediationDefaults() throws Exception {
        perform(new Call("/api/dspm/remediation/activity", "q", "   ")).andExpect(status().isOk());

        verify(pagesService).remediation(10L, new RemediationQuery(null, null, null, 0, 10));
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
    @DisplayName("an unknown role gets 403 before validation, even with an invalid parameter")
    void guardBeforeValidation() throws Exception {
        authenticateAs(principal(501L, "ROLE_AUDITOR"));

        perform(new Call("/api/dspm/remediation/activity", "limit", "0")).andExpect(status().isForbidden());
        perform(new Call("/api/dspm/dashboard", "foo", "1")).andExpect(status().isForbidden());
        verifyNoServiceCall();
    }

    // ── validation ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every validation class is a 400 naming the parameter, with no service call")
    void validationFailuresNameTheParameter() throws Exception {
        expectInvalid(new Call("/api/dspm/dashboard", "tab", "cloud"), "tab");
        expectInvalid(new Call("/api/dspm/document-classes", "q", "x"), "q");
        expectInvalid(new Call("/api/dspm/assists", "page", "1"), "page");

        String remediation = "/api/dspm/remediation/activity";
        expectInvalid(new Call(remediation, "status", "completed"), "status");
        expectInvalid(new Call(remediation, "status", "A".repeat(65)), "status");
        expectInvalid(new Call(remediation, "actionType", "MAKE-PRIVATE"), "actionType");
        expectInvalid(new Call(remediation, "status", "COMPLETED", "status", "FAILED"), "status");
        expectInvalid(new Call(remediation, "skip", "10001"), "skip");
        expectInvalid(new Call(remediation, "skip", "-1"), "skip");
        expectInvalid(new Call(remediation, "limit", "0"), "limit");
        expectInvalid(new Call(remediation, "limit", "101"), "limit");
        expectInvalid(new Call(remediation, "q", "x".repeat(201)), "q");
        expectInvalid(new Call(remediation, "q", "pl\u0007an"), "q");
        expectInvalid(new Call(remediation, "page", "1"), "page");

        verifyNoServiceCall();
    }

    // ── error mapping ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("vendor failures keep the Matters error contract")
    void errorMapping() throws Exception {
        when(dashboardService.dashboard(10L)).thenThrow(new MattersApiException(
                MattersApiException.Reason.UPSTREAM_UNAVAILABLE, "Matters is unreachable"));
        when(pagesService.assists(10L)).thenThrow(new MattersApiException(
                MattersApiException.Reason.FORBIDDEN_SCOPE, "This Matters key needs the PLATFORM scope"));

        perform(new Call("/api/dspm/dashboard")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
        perform(new Call("/api/dspm/assists")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_SCOPE"));
    }
}
