package com.xammer.cloud.controller;

import com.xammer.cloud.controller.advice.MattersExceptionHandler;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.EndpointFileListQuery;
import com.xammer.cloud.service.matters.EndpointFileService;
import com.xammer.cloud.service.matters.EndpointFilters;
import com.xammer.cloud.service.matters.FilesService;
import com.xammer.cloud.service.matters.MattersApiException;
import com.xammer.cloud.service.matters.SaasFileListQuery;
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
 * {@link DspmFilesController} in standalone MockMvc with the real {@link XamLensTabGuard} over mocked rule
 * sources and mocked services, {@code GlobalExceptionHandler} registered before {@code MattersExceptionHandler}
 * as in {@code DspmSaasControllerTest}. Values are invented.
 */
class DspmFilesControllerTest {

    private static final String FILE_ID = "0000aaaa-0000-4000-8000-00000000000c";
    private static final String FILE = "/api/dspm/endpoint-files/" + FILE_ID;
    private static final String SAAS = "/api/dspm/files/saas";
    private static final String ENDPOINT = "/api/dspm/files/endpoint";
    private static final String OWNER = "0a0b0c0d0e0f101112131415";
    private static final String TAB = "secops-xamlens";

    private GlobalTabRuleReader rules;
    private UserTabAccessRepository tabs;
    private FilesService filesService;
    private EndpointFileService endpointFileService;
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

    /** F1 to F8, each with valid parameters. */
    private static List<Call> everyEndpoint() {
        return List.of(
                new Call(SAAS, "limit", "25", "q", "plan", "sensitives", "PII", "documentClass", "Travel Receipts",
                        "sharing", "external", "category", "financial", "appType", "GMAIL", "owner", OWNER,
                        "internalUser", "sam@example.test", "externalUser", "lee@example.org", "cursor", "abc+/=="),
                new Call(SAAS + "/filter-options", "category", "financial"),
                new Call(ENDPOINT, "skip", "10", "limit", "25", "q", "notes", "sensitives", "PII",
                        "documentClass", "Travel Receipts", "category", "financial", "dataElement", "BADGE NUMBER",
                        "tag", "Reviewed", "uid", "UID-01", "hostname", "Team-Laptop-02", "lastModified", "7d"),
                new Call(ENDPOINT + "/filter-options"),
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
        filesService = mock(FilesService.class);
        endpointFileService = mock(EndpointFileService.class);

        DspmFilesController controller = new DspmFilesController(new XamLensTabGuard(rules, tabs), filesService,
                endpointFileService);
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
        verifyNoInteractions(filesService, endpointFileService);
    }

    // ── routing and tenancy ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ROLE_XAMOPS with the tab: F1 to F8 answer 200, each service gets the principal's client id")
    void everyEndpointAnswers() throws Exception {
        when(tabs.findTabNamesByUserId(500L)).thenReturn(List.of(TAB));

        for (Call call : everyEndpoint()) {
            perform(call).andExpect(status().isOk());
        }

        verify(filesService).saasFiles(10L, new SaasFileListQuery(25, "abc+/==", "plan", List.of("PII"),
                "Travel Receipts", "external", List.of("financial"), List.of("GMAIL"), List.of(OWNER),
                List.of("sam@example.test"), List.of("lee@example.org")));
        verify(filesService).saasFilterOptions(10L, List.of("financial"));
        verify(filesService).endpointFiles(10L, new EndpointFileListQuery(10, 25, "notes",
                new EndpointFilters(List.of("PII"), List.of("Travel Receipts"), List.of("financial"),
                        List.of("BADGE NUMBER")), List.of("Reviewed"), List.of("UID-01"), List.of("Team-Laptop-02"),
                "7d"));
        verify(filesService).endpointFilterOptions(10L);
        verify(endpointFileService).overview(10L, FILE_ID);
        verify(endpointFileService).activity(10L, FILE_ID);
        verify(endpointFileService).lineage(10L, FILE_ID);
        verify(endpointFileService).copies(10L, FILE_ID);
    }

    @Test
    @DisplayName("defaults: SaaS limit 10 and no cursor, Endpoint skip 0 and limit 10, F2 without category; "
            + "the file id and the owner id lower-cased")
    void defaultsAndNormalisation() throws Exception {
        perform(new Call(SAAS)).andExpect(status().isOk());
        perform(new Call(SAAS, "owner", OWNER.toUpperCase())).andExpect(status().isOk());
        perform(new Call(SAAS + "/filter-options")).andExpect(status().isOk());
        perform(new Call(ENDPOINT)).andExpect(status().isOk());
        perform(new Call("/api/dspm/endpoint-files/" + FILE_ID.toUpperCase() + "/lineage")).andExpect(status().isOk());

        verify(filesService).saasFiles(10L, new SaasFileListQuery(10, null, null, List.of(), null, null, List.of(),
                List.of(), List.of(), List.of(), List.of()));
        verify(filesService).saasFiles(10L, new SaasFileListQuery(10, null, null, List.of(), null, null, List.of(),
                List.of(), List.of(OWNER), List.of(), List.of()));
        verify(filesService).saasFilterOptions(10L, List.of());
        verify(filesService).endpointFiles(10L, new EndpointFileListQuery(0, 10, null, EndpointFilters.NONE,
                List.of(), List.of(), List.of(), null));
        verify(endpointFileService).lineage(10L, FILE_ID);
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
    @DisplayName("a principal the guard refuses gets 403 TAB_ACCESS_DENIED on F1 to F8, with no service call, "
            + "before validation")
    void guardRefusalIsAForbidden() throws Exception {
        when(rules.rulesFor(500L)).thenReturn(new GlobalTabRuleReader.UserRules(List.of(TAB), List.of()));

        for (Call call : everyEndpoint()) {
            perform(call).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TAB_ACCESS_DENIED"));
            perform(call.with("clientId", "99")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TAB_ACCESS_DENIED"));
        }
        verifyNoServiceCall();
    }

    // ── validation ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every F1 and F2 rule is a 400 naming the parameter, with no service call")
    void saasValidation() throws Exception {
        expectInvalid(new Call(SAAS, "skip", "0"), "skip");
        expectInvalid(new Call(SAAS, "limit", "101"), "limit");
        expectInvalid(new Call(SAAS, "limit", "10", "limit", "20"), "limit");
        expectInvalid(new Call(SAAS, "cursor", "a b"), "cursor");
        expectInvalid(new Call(SAAS, "cursor", "c".repeat(1025)), "cursor");
        expectInvalid(new Call(SAAS, "q", "x".repeat(201)), "q");
        expectInvalid(new Call(SAAS, "sharing", "Public"), "sharing");
        expectInvalid(new Call(SAAS, "sharing", "public", "sharing", "internal"), "sharing");
        expectInvalid(new Call(SAAS, "documentClass", "a", "documentClass", "b"), "documentClass");
        expectInvalid(new Call(SAAS, "documentClass", "d".repeat(129)), "documentClass");
        expectInvalid(new Call(SAAS, "sensitives", "pii"), "sensitives");
        expectInvalid(new Call(SAAS, "appType", "Gmail"), "appType");
        expectInvalid(new Call(SAAS, "category", ","), "category");
        expectInvalid(new Call(SAAS, "owner", "robin@example.test"), "owner");
        expectInvalid(new Call(SAAS, "owner", "o".repeat(321)), "owner");
        expectInvalid(new Call(SAAS, "internalUser", "a".repeat(321)), "internalUser");
        expectInvalid(new Call(SAAS, "externalUser", "a\u0007b"), "externalUser");
        expectInvalid(new Call(SAAS, "user", "robin"), "user");

        expectInvalid(new Call(SAAS + "/filter-options", "category", "c".repeat(129)), "category");
        expectInvalid(new Call(SAAS + "/filter-options", "q", "x"), "q");
        verifyNoServiceCall();
    }

    @Test
    @DisplayName("the HEX24 owner rule: 24 hexadecimal characters, named in the message")
    void ownerRule() throws Exception {
        perform(new Call(SAAS, "owner", OWNER.substring(1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("owner"))
                .andExpect(jsonPath("$.message").value("owner values must be 24 hexadecimal characters"));
        verifyNoServiceCall();
    }

    @Test
    @DisplayName("every F3, F4 and F5 to F8 rule is a 400 naming the parameter, with no service call")
    void endpointValidation() throws Exception {
        expectInvalid(new Call(ENDPOINT, "cursor", "abc"), "cursor");
        expectInvalid(new Call(ENDPOINT, "user", "robin"), "user");
        expectInvalid(new Call(ENDPOINT, "skip", "10001"), "skip");
        expectInvalid(new Call(ENDPOINT, "limit", "0"), "limit");
        expectInvalid(new Call(ENDPOINT, "q", "a", "q", "b"), "q");
        expectInvalid(new Call(ENDPOINT, "sensitives", "pii"), "sensitives");
        expectInvalid(new Call(ENDPOINT, "dataElement", "x".repeat(129)), "dataElement");
        expectInvalid(new Call(ENDPOINT, "tag", ","), "tag");
        expectInvalid(new Call(ENDPOINT, "uid", "u\u0001x"), "uid");
        expectInvalid(new Call(ENDPOINT, "hostname", "h".repeat(129)), "hostname");
        expectInvalid(new Call(ENDPOINT, "lastModified", "1d"), "lastModified");
        expectInvalid(new Call(ENDPOINT + "/filter-options", "q", "x"), "q");

        for (String tab : List.of("overview", "activity", "lineage", "copies")) {
            expectInvalid(new Call("/api/dspm/endpoint-files/0a0b0c0d0e0f101112131415/" + tab), "fileId");
            expectInvalid(new Call("/api/dspm/endpoint-files/not-a-uuid/" + tab), "fileId");
            expectInvalid(new Call(FILE + "/" + tab, "skip", "0"), "skip");
        }
        verifyNoServiceCall();
    }

    // ── error mapping ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ASSET_NOT_FOUND on F5 is a 404; BAD_UPSTREAM_RESPONSE on F3 is a 502")
    void errors() throws Exception {
        when(endpointFileService.overview(10L, FILE_ID)).thenThrow(
                new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND, "Asset not found"));
        when(filesService.endpointFiles(10L, new EndpointFileListQuery(0, 10, null, EndpointFilters.NONE, List.of(),
                List.of(), List.of(), null))).thenThrow(new MattersApiException(
                MattersApiException.Reason.BAD_UPSTREAM_RESPONSE, "Matters returned 500"));

        perform(new Call(FILE + "/overview")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
        perform(new Call(ENDPOINT)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("BAD_UPSTREAM_RESPONSE"));
    }
}
