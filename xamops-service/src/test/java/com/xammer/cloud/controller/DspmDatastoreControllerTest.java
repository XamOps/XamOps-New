package com.xammer.cloud.controller;

import com.xammer.cloud.controller.advice.MattersExceptionHandler;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DatastoreDataFlowService;
import com.xammer.cloud.service.matters.DatastoreDrawerService;
import com.xammer.cloud.service.matters.DatastoreListQuery;
import com.xammer.cloud.service.matters.DatastoreListService;
import com.xammer.cloud.service.matters.DatastoreTab;
import com.xammer.cloud.service.matters.DspmBadRequestException;
import com.xammer.cloud.service.matters.MattersApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DspmDatastoreController} in standalone MockMvc with the real {@link XamLensTabGuard} over mocked
 * rule sources and mocked services. {@code GlobalExceptionHandler} is registered before
 * {@code MattersExceptionHandler} on purpose: the DSPM codes must survive that order (design 5.11).
 */
class DspmDatastoreControllerTest {

    private static final String SOURCE_ID = "0123456789abcdef01234567";
    private static final String UUID = "0000000a-0000-4000-8000-00000000000b";
    private static final String DATASTORE = "/api/dspm/datastores/" + SOURCE_ID;
    private static final String TAB = "secops-xamlens";

    private GlobalTabRuleReader rules;
    private UserTabAccessRepository tabs;
    private DatastoreListService listService;
    private DatastoreDrawerService drawerService;
    private DatastoreDataFlowService dataFlowService;
    private MockMvc mvc;

    private final ClientUserDetails xamops = principal(500L, "ROLE_XAMOPS");
    private final ClientUserDetails superAdmin = principal(700L, "ROLE_SUPER_ADMIN");

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
            String[] more = java.util.Arrays.copyOf(params, params.length + 2);
            more[params.length] = name;
            more[params.length + 1] = value;
            return new Call(path, more);
        }
    }

    /** The 19 endpoints of design 5.2 (build 1's 16 and endpoints 15, 16, 18), each with valid parameters. */
    private static List<Call> everyEndpoint() {
        return List.of(
                new Call("/api/dspm/datastores", "tab", "cloud", "provider", "S3,EBS"),
                new Call("/api/dspm/datastores/filter-options", "tab", "cloud"),
                new Call("/api/dspm/datastores/kpis", "tab", "cloud"),
                new Call(DATASTORE + "/overview"),
                new Call(DATASTORE + "/scan-status"),
                new Call(DATASTORE + "/sensitives"),
                new Call(DATASTORE + "/sensitives/files", "page", "2", "pageSize", "10", "q", "reports"),
                new Call(DATASTORE + "/sensitives/occurrences", "entityName", "PERSON NAME", "skip", "10",
                        "limit", "10", "showFalsePositives", "true"),
                new Call(DATASTORE + "/risks"),
                new Call(DATASTORE + "/access-graph"),
                new Call(DATASTORE + "/scan-history"),
                new Call(DATASTORE + "/data-flow/databases"),
                new Call("/api/dspm/data-flow/databases/" + UUID + "/schemas"),
                new Call("/api/dspm/data-flow/schemas/" + UUID + "/tables", "page", "2", "pageSize", "10",
                        "q", "orders"),
                new Call("/api/dspm/data-flow/databases/" + UUID + "/tables"),
                new Call("/api/dspm/data-flow/tables/" + UUID + "/columns", "page", "1", "pageSize", "25"),
                new Call("/api/dspm/data-flow/schemas/" + UUID + "/erd"),
                new Call("/api/dspm/data-flow/databases/" + UUID + "/erd"),
                new Call("/api/dspm/data-flow/tables/" + UUID + "/relations"));
    }

    @BeforeEach
    void setUp() {
        rules = mock(GlobalTabRuleReader.class);
        tabs = mock(UserTabAccessRepository.class);
        when(rules.rulesFor(anyLong())).thenReturn(GlobalTabRuleReader.UserRules.NONE);
        when(tabs.findTabNamesByUserId(anyLong())).thenReturn(List.of());
        listService = mock(DatastoreListService.class);
        drawerService = mock(DatastoreDrawerService.class);
        dataFlowService = mock(DatastoreDataFlowService.class);

        DspmDatastoreController controller = new DspmDatastoreController(new XamLensTabGuard(rules, tabs),
                listService, drawerService, dataFlowService);
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

    private void expectTabDenied(Call call) throws Exception {
        perform(call).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TAB_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("You do not have access to XamLens."));
    }

    private void verifyNoServiceCall() {
        verifyNoInteractions(listService, drawerService, dataFlowService);
    }

    // ── routing and tenancy ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ROLE_XAMOPS with the tab: all 19 endpoints answer 200, each service gets the principal's client id")
    void everyEndpointAnswers() throws Exception {
        when(tabs.findTabNamesByUserId(500L)).thenReturn(List.of("cloudops-cloudk8s", TAB));

        for (Call call : everyEndpoint()) {
            perform(call).andExpect(status().isOk());
        }

        verify(listService).list(eq(10L), any(DatastoreListQuery.class));
        verify(listService).filterOptions(eq(10L), any(DatastoreListQuery.class));
        verify(listService).kpis(10L, DatastoreTab.CLOUD);
        verify(drawerService).overview(10L, SOURCE_ID);
        verify(drawerService).scanProgress(10L, SOURCE_ID);
        verify(drawerService).sensitives(10L, SOURCE_ID);
        verify(drawerService).files(10L, SOURCE_ID, "reports", 2, 10);
        verify(drawerService).occurrences(10L, SOURCE_ID, "PERSON NAME", 10, 10, true);
        verify(drawerService).risks(10L, SOURCE_ID);
        verify(drawerService).accessGraph(10L, SOURCE_ID);
        verify(drawerService).scanHistory(10L, SOURCE_ID);
        verify(dataFlowService).databases(10L, SOURCE_ID);
        verify(dataFlowService).schemas(10L, UUID);
        verify(dataFlowService).schemaTables(10L, UUID, 2, 10, "orders");
        verify(dataFlowService).databaseTables(10L, UUID, 1, 10, null);
        verify(dataFlowService).columns(10L, UUID, 1, 25);
        verify(dataFlowService).schemaErd(10L, UUID);
        verify(dataFlowService).databaseErd(10L, UUID);
        verify(dataFlowService).tableRelations(10L, UUID);
    }

    @Test
    @DisplayName("Graph View ids are lower-cased UUIDs; a non-UUID or a parameter is a 400; a refused principal a 403")
    void graphViewEndpoints() throws Exception {
        perform(new Call("/api/dspm/data-flow/tables/0000000A-0000-4000-8000-00000000000B/relations"))
                .andExpect(status().isOk());
        verify(dataFlowService).tableRelations(10L, UUID);

        expectInvalid(new Call("/api/dspm/data-flow/tables/not-a-uuid/relations"), "tableId");
        expectInvalid(new Call("/api/dspm/data-flow/schemas/not-a-uuid/erd"), "schemaId");
        expectInvalid(new Call("/api/dspm/data-flow/databases/" + SOURCE_ID + "/erd"), "databaseId");
        expectInvalid(new Call("/api/dspm/data-flow/tables/" + UUID + "/relations", "page", "1"), "page");
        expectInvalid(new Call("/api/dspm/data-flow/schemas/" + UUID + "/erd", "q", "orders"), "q");

        when(tabs.findTabNamesByUserId(500L)).thenReturn(List.of("cloudops-cloudk8s"));
        expectTabDenied(new Call("/api/dspm/data-flow/tables/" + UUID + "/relations"));
        expectTabDenied(new Call("/api/dspm/data-flow/schemas/" + UUID + "/erd"));
        verify(dataFlowService).tableRelations(10L, UUID);
        verifyNoMoreInteractions(dataFlowService);
    }

    @Test
    @DisplayName("the list query is built from the raw parameters; ids are lower-cased")
    void parametersAreParsedStrictly() throws Exception {
        perform(new Call("/api/dspm/datastores", "tab", "cloud", "page", "3", "provider", "S3,EBS",
                "scan_status_exclude", "FAILED")).andExpect(status().isOk());
        perform(new Call("/api/dspm/datastores/0123456789ABCDEF01234567/overview")).andExpect(status().isOk());
        perform(new Call("/api/dspm/data-flow/tables/0000000A-0000-4000-8000-00000000000B/columns"))
                .andExpect(status().isOk());

        ArgumentCaptor<DatastoreListQuery> query = ArgumentCaptor.forClass(DatastoreListQuery.class);
        verify(listService).list(eq(10L), query.capture());
        assertThat(query.getValue().tab()).isEqualTo(DatastoreTab.CLOUD);
        assertThat(query.getValue().page()).isEqualTo(3);
        assertThat(query.getValue().pageSize()).isEqualTo(10);
        assertThat(query.getValue().sort()).isEqualTo("-severity");
        assertThat(query.getValue().filters()).containsEntry("provider",
                new DatastoreListQuery.Selection(false, List.of("EBS", "S3")))
                .containsEntry("scan_status", new DatastoreListQuery.Selection(true, List.of("FAILED")));
        verify(drawerService).overview(10L, SOURCE_ID);
        verify(dataFlowService).columns(10L, UUID, 1, 10);
    }

    @Test
    @DisplayName("a Data Flow search shorter than 3 characters reaches the service as absent")
    void shortDataFlowSearchIsAbsent() throws Exception {
        perform(new Call("/api/dspm/data-flow/schemas/" + UUID + "/tables", "q", "ab")).andExpect(status().isOk());

        verify(dataFlowService).schemaTables(10L, UUID, 1, 10, null);
    }

    @Test
    @DisplayName("?clientId=99 is a 400 on every endpoint, with no service call")
    void clientIdIsRejected() throws Exception {
        for (Call call : everyEndpoint()) {
            expectInvalid(call.with("clientId", "99"), "clientId");
        }
        verifyNoServiceCall();
    }

    // ── tab guard (BUILD-BRIEF 13, resolution 1) ─────────────────────────────────────────────────

    @Test
    @DisplayName("ROLE_XAMOPS hidden by a platform-wide HIDE: 403 TAB_ACCESS_DENIED everywhere, no service call")
    void platformWideHideIsRefused() throws Exception {
        when(rules.rulesFor(500L)).thenReturn(new GlobalTabRuleReader.UserRules(List.of(TAB), List.of()));

        for (Call call : everyEndpoint()) {
            expectTabDenied(call);
        }
        verifyNoServiceCall();
    }

    @Test
    @DisplayName("ROLE_XAMOPS whose tab list lacks secops-xamlens: 403 TAB_ACCESS_DENIED")
    void missingTabRowIsRefused() throws Exception {
        when(tabs.findTabNamesByUserId(500L)).thenReturn(List.of("cloudops-cloudk8s", "secops-xamsecure"));

        expectTabDenied(new Call("/api/dspm/datastores"));
        expectTabDenied(new Call(DATASTORE + "/overview"));
        expectTabDenied(new Call("/api/dspm/data-flow/tables/" + UUID + "/columns"));
        verifyNoServiceCall();
    }

    @Test
    @DisplayName("ROLE_XAMOPS with an empty tab list is unrestricted: 200")
    void unrestrictedUserIsAllowed() throws Exception {
        perform(new Call("/api/dspm/datastores")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a platform-wide GRANT on secops allows a list that lacks the tab: 200")
    void moduleGrantAllows() throws Exception {
        when(rules.rulesFor(500L)).thenReturn(new GlobalTabRuleReader.UserRules(List.of(), List.of("secops")));
        when(tabs.findTabNamesByUserId(500L)).thenReturn(List.of("cloudops-cloudk8s"));

        perform(new Call("/api/dspm/datastores/kpis")).andExpect(status().isOk());
        verify(listService).kpis(10L, DatastoreTab.ALL);
    }

    @Test
    @DisplayName("ROLE_SUPER_ADMIN, a restricted team member under a HIDE included, is never gated: 200")
    void superAdminIsNotGated() throws Exception {
        authenticateAs(superAdmin);
        when(rules.rulesFor(700L)).thenReturn(new GlobalTabRuleReader.UserRules(List.of(TAB, "secops"), List.of()));
        when(tabs.findTabNamesByUserId(700L)).thenReturn(List.of("cloudops-cloudk8s"));

        for (Call call : everyEndpoint()) {
            perform(call).andExpect(status().isOk());
        }
        verify(listService).list(eq(10L), any(DatastoreListQuery.class));
    }

    // ── validation ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every validation class is a 400 naming the parameter, with no service call")
    void validationFailuresNameTheParameter() throws Exception {
        String list = "/api/dspm/datastores";
        expectInvalid(new Call(list, "foo", "1"), "foo");
        expectInvalid(new Call(list, "page", "1", "page", "2"), "page");
        expectInvalid(new Call(list, "tab", "onprem"), "tab");
        expectInvalid(new Call(list, "page", "0"), "page");
        expectInvalid(new Call(list, "pageSize", "101"), "pageSize");
        expectInvalid(new Call(list, "page", "1002", "pageSize", "10"), "page");
        expectInvalid(new Call(list, "sort", "risk_score"), "sort");
        expectInvalid(new Call(list, "q", "x".repeat(201)), "q");
        expectInvalid(new Call(list, "q", "be\u0007ll"), "q");
        expectInvalid(new Call(list, "tab", "cloud", "os", "MAC"), "os");
        expectInvalid(new Call(list, "provider", "ebs"), "provider");
        expectInvalid(new Call(list, "integration_id", "abc"), "integration_id");
        expectInvalid(new Call(list, "tab", "saas", "selected_exclude", "x"), "selected_exclude");
        expectInvalid(new Call(list, "provider", "S3", "provider_exclude", "EBS"), "provider_exclude");
        expectInvalid(new Call("/api/dspm/datastores/filter-options", "page", "1"), "page");
        expectInvalid(new Call("/api/dspm/datastores/kpis", "q", "x"), "q");
        expectInvalid(new Call("/api/dspm/datastores/kpis", "tab", "everything"), "tab");

        expectInvalid(new Call("/api/dspm/datastores/not-a-source-id/overview"), "sourceId");
        expectInvalid(new Call(DATASTORE + "/risks", "foo", "1"), "foo");
        expectInvalid(new Call(DATASTORE + "/sensitives/files", "pageSize", "0"), "pageSize");
        expectInvalid(new Call(DATASTORE + "/sensitives/occurrences"), "entityName");
        expectInvalid(new Call(DATASTORE + "/sensitives/occurrences", "entityName", "x".repeat(129)), "entityName");
        expectInvalid(new Call(DATASTORE + "/sensitives/occurrences", "entityName", "PERSON NAME", "skip", "10001"),
                "skip");
        expectInvalid(new Call(DATASTORE + "/sensitives/occurrences", "entityName", "PERSON NAME", "limit", "0"),
                "limit");
        expectInvalid(new Call(DATASTORE + "/sensitives/occurrences", "entityName", "PERSON NAME",
                "showFalsePositives", "yes"), "showFalsePositives");

        expectInvalid(new Call("/api/dspm/data-flow/databases/not-a-uuid/schemas"), "databaseId");
        expectInvalid(new Call("/api/dspm/data-flow/schemas/not-a-uuid/tables"), "schemaId");
        expectInvalid(new Call("/api/dspm/data-flow/tables/not-a-uuid/columns"), "tableId");
        expectInvalid(new Call("/api/dspm/data-flow/schemas/" + UUID + "/tables", "page", "101", "pageSize", "100"),
                "page");
        expectInvalid(new Call("/api/dspm/data-flow/tables/" + UUID + "/columns", "q", "orders"), "q");

        verifyNoServiceCall();
    }

    @Test
    @DisplayName("an entity outside the datastore's own list is a 400 on entityName")
    void unknownEntityIsRejected() throws Exception {
        when(drawerService.occurrences(10L, SOURCE_ID, "EMAIL ADDRESS", 0, 10, false))
                .thenThrow(new DspmBadRequestException("entityName", "entityName is not an entity of this datastore"));

        expectInvalid(new Call(DATASTORE + "/sensitives/occurrences", "entityName", "EMAIL ADDRESS"), "entityName");
    }

    // ── error mapping ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the DSPM codes survive GlobalExceptionHandler being registered first")
    void adviceOrderHolds() throws Exception {
        when(drawerService.risks(10L, SOURCE_ID)).thenThrow(
                new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND, "Asset not found"));

        expectInvalid(new Call("/api/dspm/datastores", "tab", "nope"), "tab");
        perform(new Call(DATASTORE + "/risks")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));

        when(rules.rulesFor(500L)).thenReturn(new GlobalTabRuleReader.UserRules(List.of("secops"), List.of()));
        expectTabDenied(new Call(DATASTORE + "/risks"));
    }

    @Test
    @DisplayName("ASSET_NOT_FOUND is a 404")
    void assetNotFound() throws Exception {
        when(drawerService.overview(10L, SOURCE_ID)).thenThrow(
                new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND, "Asset not found"));

        perform(new Call(DATASTORE + "/overview")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Asset not found"));
    }

    @Test
    @DisplayName("FORBIDDEN_SCOPE is a 403 with the PLATFORM message")
    void forbiddenScope() throws Exception {
        when(drawerService.accessGraph(10L, SOURCE_ID)).thenThrow(new MattersApiException(
                MattersApiException.Reason.FORBIDDEN_SCOPE, "This Matters key needs the PLATFORM scope"));

        perform(new Call(DATASTORE + "/access-graph")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_SCOPE"))
                .andExpect(jsonPath("$.message").value("This Matters key needs the PLATFORM scope"));
    }

    @Test
    @DisplayName("RATE_LIMITED is a 429 with Retry-After")
    void rateLimited() throws Exception {
        when(listService.kpis(10L, DatastoreTab.SAAS)).thenThrow(new MattersApiException(
                MattersApiException.Reason.RATE_LIMITED, "Matters rate limit exceeded", 30, null));

        perform(new Call("/api/dspm/datastores/kpis", "tab", "saas")).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(30));
    }

    @Test
    @DisplayName("occurrences are answered with Cache-Control: no-store")
    void occurrencesAreNotStored() throws Exception {
        perform(new Call(DATASTORE + "/sensitives/occurrences", "entityName", "PERSON NAME"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(drawerService).occurrences(10L, SOURCE_ID, "PERSON NAME", 0, 10, false);
    }
}
