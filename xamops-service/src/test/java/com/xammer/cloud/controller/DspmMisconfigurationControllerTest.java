package com.xammer.cloud.controller;

import com.xammer.cloud.controller.advice.MattersExceptionHandler;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationDetail;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationList;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.MattersApiException;
import com.xammer.cloud.service.matters.MisconfigurationService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * {@link DspmMisconfigurationController} in standalone MockMvc with the real {@link XamLensTabGuard} over
 * mocked rule sources and a mocked service, {@code GlobalExceptionHandler} registered before
 * {@code MattersExceptionHandler} as in {@code DspmSaasControllerTest}.
 */
class DspmMisconfigurationControllerTest {

    private static final String LIST = "/api/dspm/misconfigurations";
    private static final String POLICY_ID = "p-0123456789abcdef";
    private static final String DETAIL = LIST + "/policies/" + POLICY_ID;
    private static final String TAB = "secops-xamlens";

    private GlobalTabRuleReader rules;
    private UserTabAccessRepository tabs;
    private MisconfigurationService service;
    private MockMvc mvc;

    private final ClientUserDetails xamops = principal(500L, "ROLE_XAMOPS");

    private static ClientUserDetails principal(long userId, String role) {
        return new ClientUserDetails("user" + userId + "@example.com", "pw",
                List.of(new SimpleGrantedAuthority(role)), 10L, userId, "tenant-a");
    }

    private static DatastoreRow datastore() {
        return new DatastoreRow("asset-1", "0123456789abcdef01234567", "store-a", "cloud", "S3", "S3",
                new DatastoreRow.Risk(7.5, "HIGH", "High"), new DatastoreRow.Sensitives(12L, List.of("Personal")),
                new DatastoreRow.Source("S3", "Amazon S3", "account-one", "eu-west-1"),
                new DatastoreRow.Scan("CLASSIFIED", "Classified", 1700000000L), null);
    }

    private static MisconfigurationList list() {
        return new MisconfigurationList(1L, new MisconfigurationList.SeverityCounts(1, 0, 1, 0, 0), 3, false,
                List.of(new MisconfigurationList.Row(POLICY_ID, "Public access", "UPWIND", "HIGH", "High", 1, 12L,
                        List.of("Personal"), List.of("NIST"), List.of("account-one"))));
    }

    private static MisconfigurationDetail detail() {
        return new MisconfigurationDetail(POLICY_ID, "Public access", "UPWIND", "HIGH", "High", "## Why",
                "1. Fix", null, "https://docs.example.test/a", List.of("NIST"),
                List.of(new MisconfigurationDetail.AffectedDatastore(datastore(), 1700000000L)));
    }

    @BeforeEach
    void setUp() {
        rules = mock(GlobalTabRuleReader.class);
        tabs = mock(UserTabAccessRepository.class);
        when(rules.rulesFor(anyLong())).thenReturn(GlobalTabRuleReader.UserRules.NONE);
        when(tabs.findTabNamesByUserId(anyLong())).thenReturn(List.of());
        service = mock(MisconfigurationService.class);

        DspmMisconfigurationController controller =
                new DspmMisconfigurationController(new XamLensTabGuard(rules, tabs), service);
        mvc = standaloneSetup(controller)
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

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    private void expectInvalid(MockHttpServletRequestBuilder request, String parameter) throws Exception {
        perform(request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.parameter").value(parameter))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ── routing, shape and tenancy ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the list answers 200 with its JSON shape, for the principal's client id")
    void listAnswers() throws Exception {
        when(service.list(10L)).thenReturn(list());

        perform(get(LIST)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAffectedResources").value(1))
                .andExpect(jsonPath("$.open.total").value(1))
                .andExpect(jsonPath("$.open.critical").value(0))
                .andExpect(jsonPath("$.open.high").value(1))
                .andExpect(jsonPath("$.open.medium").value(0))
                .andExpect(jsonPath("$.open.low").value(0))
                .andExpect(jsonPath("$.datastoresRead").value(3))
                .andExpect(jsonPath("$.capped").value(false))
                .andExpect(jsonPath("$.rows[0].policyId").value(POLICY_ID))
                .andExpect(jsonPath("$.rows[0].title").value("Public access"))
                .andExpect(jsonPath("$.rows[0].source").value("UPWIND"))
                .andExpect(jsonPath("$.rows[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.rows[0].severityLabel").value("High"))
                .andExpect(jsonPath("$.rows[0].affectedResources").value(1))
                .andExpect(jsonPath("$.rows[0].sensitiveRecords").value(12))
                .andExpect(jsonPath("$.rows[0].labels[0]").value("Personal"))
                .andExpect(jsonPath("$.rows[0].frameworks[0]").value("NIST"))
                .andExpect(jsonPath("$.rows[0].cloudAccounts[0]").value("account-one"));
        verify(service).list(10L);
    }

    @Test
    @DisplayName("the detail answers 200 with its JSON shape; null fields are present")
    void detailAnswers() throws Exception {
        when(service.detail(10L, POLICY_ID)).thenReturn(detail());

        perform(get(DETAIL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.policyId").value(POLICY_ID))
                .andExpect(jsonPath("$.title").value("Public access"))
                .andExpect(jsonPath("$.source").value("UPWIND"))
                .andExpect(jsonPath("$.severity").value("HIGH"))
                .andExpect(jsonPath("$.severityLabel").value("High"))
                .andExpect(jsonPath("$.description").value("## Why"))
                .andExpect(jsonPath("$.remediation").value("1. Fix"))
                .andExpect(jsonPath("$.cli").isEmpty())
                .andExpect(jsonPath("$.learnMore").value("https://docs.example.test/a"))
                .andExpect(jsonPath("$.frameworks[0]").value("NIST"))
                .andExpect(jsonPath("$.affected[0].datastore.sourceId").value("0123456789abcdef01234567"))
                .andExpect(jsonPath("$.affected[0].datastore.source.accountLabel").value("account-one"))
                .andExpect(jsonPath("$.affected[0].lastScannedAt").value(1700000000L));
        verify(service).detail(10L, POLICY_ID);
    }

    @Test
    @DisplayName("snake_case and dotted policy ids reach the service as given")
    void policyIdKeptAsGiven() throws Exception {
        when(service.detail(10L, "Snake_Case.rule-1")).thenReturn(detail());

        perform(get(LIST + "/policies/Snake_Case.rule-1")).andExpect(status().isOk());
        verify(service).detail(10L, "Snake_Case.rule-1");
    }

    @Test
    @DisplayName("?clientId=99 or any other parameter is a 400 naming it, with no service call")
    void parametersAreRejected() throws Exception {
        expectInvalid(get(LIST).param("clientId", "99"), "clientId");
        expectInvalid(get(DETAIL).param("clientId", "99"), "clientId");
        expectInvalid(get(LIST).param("page", "1"), "page");
        expectInvalid(get(DETAIL).param("q", "x"), "q");
        verifyNoInteractions(service);
    }

    // ── tab guard ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a principal without secops-xamlens gets 403 TAB_ACCESS_DENIED on both, with no service call")
    void guardRefusalIsAForbidden() throws Exception {
        when(rules.rulesFor(500L)).thenReturn(new GlobalTabRuleReader.UserRules(List.of(TAB), List.of()));

        for (String path : List.of(LIST, DETAIL)) {
            perform(get(path)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TAB_ACCESS_DENIED"))
                    .andExpect(jsonPath("$.message").value("You do not have access to XamLens."));
        }
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("the guard runs before parameter validation")
    void guardBeforeValidation() throws Exception {
        when(rules.rulesFor(500L)).thenReturn(new GlobalTabRuleReader.UserRules(List.of(TAB), List.of()));

        perform(get(LIST).param("clientId", "99")).andExpect(status().isForbidden());
        perform(get(LIST + "/policies/-bad")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    // ── validation and errors ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an invalid policy id is a 400 naming policyId, with no service call")
    void invalidPolicyId() throws Exception {
        expectInvalid(get(LIST + "/policies/-leading"), "policyId");
        expectInvalid(get(LIST + "/policies/" + "a".repeat(129)), "policyId");
        expectInvalid(get(LIST + "/policies/p%20one"), "policyId");
        expectInvalid(get(LIST + "/policies/p~1"), "policyId");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("an unknown policy is a 404 ASSET_NOT_FOUND")
    void unknownPolicy() throws Exception {
        when(service.detail(10L, POLICY_ID)).thenThrow(new MattersApiException(
                MattersApiException.Reason.ASSET_NOT_FOUND, "Misconfiguration not found"));

        perform(get(DETAIL)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Misconfiguration not found"));
    }

    @Test
    @DisplayName("an upstream failure of the load is a 502 UPSTREAM_UNAVAILABLE")
    void upstreamFailure() throws Exception {
        when(service.list(10L)).thenThrow(new MattersApiException(
                MattersApiException.Reason.UPSTREAM_UNAVAILABLE, "Matters took too long to list misconfigurations"));

        perform(get(LIST)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }
}
