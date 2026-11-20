package com.xammer.cloud.controller;

import com.xammer.cloud.controller.advice.MattersExceptionHandler;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DspmBadRequestException;
import com.xammer.cloud.service.matters.MattersApiException;
import com.xammer.cloud.service.matters.SaasDrawerService;
import com.xammer.cloud.service.matters.SaasFileQuery;
import com.xammer.cloud.service.matters.SaasFileService;
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
 * {@link DspmSaasController} in standalone MockMvc with the real {@link XamLensTabGuard} over mocked rule
 * sources and mocked services, {@code GlobalExceptionHandler} registered before
 * {@code MattersExceptionHandler} as in {@code DspmDatastoreControllerTest}.
 */
class DspmSaasControllerTest {

    private static final String SOURCE_ID = "0123456789abcdef01234567";
    private static final String FILE_ID = "abcdef0123456789abcdef01";
    private static final String SAAS = "/api/dspm/datastores/" + SOURCE_ID + "/saas";
    private static final String FILE = "/api/dspm/saas-files/" + FILE_ID;
    private static final String TAB = "secops-xamlens";

    private GlobalTabRuleReader rules;
    private UserTabAccessRepository tabs;
    private SaasDrawerService drawerService;
    private SaasFileService fileService;
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

    /** Endpoints 1 to 7 and 17 of design 5.2, each with valid parameters. */
    private static List<Call> everyEndpoint() {
        return List.of(
                new Call(SAAS + "/detail"),
                new Call(SAAS + "/records", "view", "document_classes"),
                new Call(SAAS + "/files", "dataElement", "EMAIL ADDRESS", "q", "plan", "skip", "5", "limit", "20"),
                new Call(SAAS + "/scan-summary"),
                new Call(FILE + "/overview"),
                new Call(FILE + "/activity"),
                new Call(FILE + "/lineage"),
                new Call(FILE + "/copies"));
    }

    @BeforeEach
    void setUp() {
        rules = mock(GlobalTabRuleReader.class);
        tabs = mock(UserTabAccessRepository.class);
        when(rules.rulesFor(anyLong())).thenReturn(GlobalTabRuleReader.UserRules.NONE);
        when(tabs.findTabNamesByUserId(anyLong())).thenReturn(List.of());
        drawerService = mock(SaasDrawerService.class);
        fileService = mock(SaasFileService.class);

        DspmSaasController controller = new DspmSaasController(new XamLensTabGuard(rules, tabs), drawerService,
                fileService);
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
        verifyNoInteractions(drawerService, fileService);
    }

    // ── routing and tenancy ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ROLE_XAMOPS with the tab: all 8 endpoints answer 200, each service gets the principal's client id")
    void everyEndpointAnswers() throws Exception {
        when(tabs.findTabNamesByUserId(500L)).thenReturn(List.of("cloudops-cloudk8s", TAB));

        for (Call call : everyEndpoint()) {
            perform(call).andExpect(status().isOk());
        }

        verify(drawerService).header(10L, SOURCE_ID);
        verify(drawerService).records(10L, SOURCE_ID, "document_classes");
        verify(drawerService).files(10L, SOURCE_ID,
                new SaasFileQuery(SaasFileQuery.Kind.DATA_ELEMENT, "EMAIL ADDRESS", "plan", 5, 20));
        verify(drawerService).scanSummary(10L, SOURCE_ID);
        verify(fileService).overview(10L, FILE_ID);
        verify(fileService).activity(10L, FILE_ID);
        verify(fileService).lineage(10L, FILE_ID);
        verify(fileService).copies(10L, FILE_ID);
    }

    @Test
    @DisplayName("defaults: view data_elements, limit 5, skip 0; values trimmed; ids lower-cased")
    void defaultsAndNormalisation() throws Exception {
        perform(new Call(SAAS + "/records")).andExpect(status().isOk());
        perform(new Call(SAAS + "/files", "documentClass", "  Budget Plans ")).andExpect(status().isOk());
        perform(new Call(SAAS + "/files", "dataElement", "EMAIL ADDRESS", "q", "   ")).andExpect(status().isOk());
        perform(new Call("/api/dspm/datastores/0123456789ABCDEF01234567/saas/detail")).andExpect(status().isOk());
        perform(new Call("/api/dspm/saas-files/ABCDEF0123456789ABCDEF01/lineage")).andExpect(status().isOk());

        verify(drawerService).records(10L, SOURCE_ID, "data_elements");
        verify(drawerService).files(10L, SOURCE_ID,
                new SaasFileQuery(SaasFileQuery.Kind.DOCUMENT_CLASS, "Budget Plans", null, 0, 5));
        verify(drawerService).files(10L, SOURCE_ID,
                new SaasFileQuery(SaasFileQuery.Kind.DATA_ELEMENT, "EMAIL ADDRESS", null, 0, 5));
        verify(drawerService).header(10L, SOURCE_ID);
        verify(fileService).lineage(10L, FILE_ID);
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

    // ── validation ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every validation class is a 400 naming the parameter, with no service call")
    void validationFailuresNameTheParameter() throws Exception {
        String files = SAAS + "/files";
        expectInvalid(new Call(files, "dataElement", "EMAIL ADDRESS", "documentClass", "Budget Plans"), "dataElement");
        expectInvalid(new Call(files), "dataElement");
        expectInvalid(new Call(files, "q", "plan"), "dataElement");
        expectInvalid(new Call(files, "documentClass", ""), "documentClass");
        expectInvalid(new Call(files, "dataElement", "   "), "dataElement");
        expectInvalid(new Call(files, "dataElement", "x".repeat(129)), "dataElement");
        expectInvalid(new Call(files, "dataElement", "EMAIL\u0007ADDRESS"), "dataElement");
        expectInvalid(new Call(files, "dataElement", "A", "dataElement", "B"), "dataElement");
        expectInvalid(new Call(files, "dataElement", "EMAIL ADDRESS", "q", "x".repeat(201)), "q");
        expectInvalid(new Call(files, "dataElement", "EMAIL ADDRESS", "skip", "10001"), "skip");
        expectInvalid(new Call(files, "dataElement", "EMAIL ADDRESS", "limit", "0"), "limit");
        expectInvalid(new Call(files, "dataElement", "EMAIL ADDRESS", "limit", "101"), "limit");
        expectInvalid(new Call(files, "dataElement", "EMAIL ADDRESS", "view", "data_elements"), "view");

        expectInvalid(new Call(SAAS + "/records", "view", "files"), "view");
        expectInvalid(new Call(SAAS + "/records", "view", "data_elements", "view", "document_classes"), "view");
        expectInvalid(new Call(SAAS + "/records", "q", "x"), "q");
        expectInvalid(new Call(SAAS + "/detail", "foo", "1"), "foo");
        expectInvalid(new Call(SAAS + "/scan-summary", "page", "1"), "page");
        expectInvalid(new Call("/api/dspm/datastores/not-a-source-id/saas/detail"), "sourceId");
        expectInvalid(new Call("/api/dspm/datastores/0123456789abcdef0123456/saas/records"), "sourceId");

        for (String tab : List.of("overview", "activity", "lineage", "copies")) {
            expectInvalid(new Call("/api/dspm/saas-files/not-a-hex-file-id-0000000/" + tab), "fileId");
            expectInvalid(new Call("/api/dspm/saas-files/abcdef0123456789abcdef0/" + tab), "fileId");
            expectInvalid(new Call(FILE + "/" + tab, "skip", "0"), "skip");
        }

        verifyNoServiceCall();
    }

    @Test
    @DisplayName("a value that is not a row of the datastore is a 400 on its own parameter")
    void nonRowValueIsRejected() throws Exception {
        when(drawerService.files(10L, SOURCE_ID,
                new SaasFileQuery(SaasFileQuery.Kind.DATA_ELEMENT, "PERSON NAME", null, 0, 5)))
                .thenThrow(new DspmBadRequestException("dataElement", "dataElement is not a row of this datastore"));
        when(drawerService.files(10L, SOURCE_ID,
                new SaasFileQuery(SaasFileQuery.Kind.DOCUMENT_CLASS, "Resumes", null, 0, 5)))
                .thenThrow(new DspmBadRequestException("documentClass", "documentClass is not a row of this datastore"));

        expectInvalid(new Call(SAAS + "/files", "dataElement", "PERSON NAME"), "dataElement");
        expectInvalid(new Call(SAAS + "/files", "documentClass", "Resumes"), "documentClass");
    }

    // ── error mapping ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ASSET_NOT_FOUND is a 404 on datastore and file routes")
    void assetNotFound() throws Exception {
        when(drawerService.header(10L, SOURCE_ID)).thenThrow(
                new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND, "Asset not found"));
        when(fileService.overview(10L, FILE_ID)).thenThrow(
                new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND, "Asset not found"));

        perform(new Call(SAAS + "/detail")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
        perform(new Call(FILE + "/overview")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Asset not found"));
    }

    @Test
    @DisplayName("FORBIDDEN_SCOPE is a 403 with the PLATFORM message")
    void forbiddenScope() throws Exception {
        when(drawerService.records(10L, SOURCE_ID, "data_elements")).thenThrow(new MattersApiException(
                MattersApiException.Reason.FORBIDDEN_SCOPE, "This Matters key needs the PLATFORM scope"));

        perform(new Call(SAAS + "/records")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_SCOPE"))
                .andExpect(jsonPath("$.message").value("This Matters key needs the PLATFORM scope"));
    }

    @Test
    @DisplayName("R6: a lineage failure other than not-found answers 502, so the tab can offer Retry")
    void lineageFailureIsABadGateway() throws Exception {
        when(fileService.lineage(10L, FILE_ID)).thenThrow(new MattersApiException(
                MattersApiException.Reason.UPSTREAM_UNAVAILABLE, "Matters is unreachable"));

        perform(new Call(FILE + "/lineage")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }
}
