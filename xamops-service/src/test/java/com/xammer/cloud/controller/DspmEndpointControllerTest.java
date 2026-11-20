package com.xammer.cloud.controller;

import com.xammer.cloud.controller.advice.MattersExceptionHandler;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DspmBadRequestException;
import com.xammer.cloud.service.matters.EndpointDrawerService;
import com.xammer.cloud.service.matters.EndpointFileQuery;
import com.xammer.cloud.service.matters.EndpointFilters;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DspmEndpointController} in standalone MockMvc with the real {@link XamLensTabGuard} over mocked
 * rule sources and a mocked service, {@code GlobalExceptionHandler} registered before
 * {@code MattersExceptionHandler} as in {@code DspmDatastoreControllerTest}.
 */
class DspmEndpointControllerTest {

    private static final String SOURCE_ID = "0123456789abcdef01234567";
    private static final String ENDPOINT = "/api/dspm/datastores/" + SOURCE_ID + "/endpoint";
    private static final String TAB = "secops-xamlens";

    private GlobalTabRuleReader rules;
    private UserTabAccessRepository tabs;
    private EndpointDrawerService drawerService;
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

    /** Endpoints 8 to 14 of design 5.2, each with valid parameters. */
    private static List<Call> everyEndpoint() {
        return List.of(
                new Call(ENDPOINT + "/overview"),
                new Call(ENDPOINT + "/filter-options"),
                new Call(ENDPOINT + "/summary"),
                new Call(ENDPOINT + "/sensitives", "sensitives", "PII", "documentClass",
                        "system documentation,incident reports", "showFalsePositives", "true"),
                new Call(ENDPOINT + "/occurrences", "entityName", "EMAIL ADDRESS", "skip", "10", "limit", "20",
                        "showFalsePositives", "true"),
                new Call(ENDPOINT + "/files", "category", "it & technical", "dataElement", "EMAIL ADDRESS",
                        "q", "notes", "skip", "10", "limit", "25"),
                new Call(ENDPOINT + "/scans", "skip", "10", "limit", "5"));
    }

    private static EndpointFilters filters(List<String> sensitives, List<String> documentClass, List<String> category,
                                           List<String> dataElement) {
        return new EndpointFilters(sensitives, documentClass, category, dataElement);
    }

    @BeforeEach
    void setUp() {
        rules = mock(GlobalTabRuleReader.class);
        tabs = mock(UserTabAccessRepository.class);
        when(rules.rulesFor(anyLong())).thenReturn(GlobalTabRuleReader.UserRules.NONE);
        when(tabs.findTabNamesByUserId(anyLong())).thenReturn(List.of());
        drawerService = mock(EndpointDrawerService.class);

        DspmEndpointController controller = new DspmEndpointController(new XamLensTabGuard(rules, tabs), drawerService);
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
    @DisplayName("ROLE_XAMOPS with the tab: all 7 endpoints answer 200, the service gets the principal's client id")
    void everyEndpointAnswers() throws Exception {
        when(tabs.findTabNamesByUserId(500L)).thenReturn(List.of("cloudops-cloudk8s", TAB));

        for (Call call : everyEndpoint()) {
            perform(call).andExpect(status().isOk());
        }

        verify(drawerService).overview(10L, SOURCE_ID);
        verify(drawerService).filterOptions(10L, SOURCE_ID);
        verify(drawerService).summary(10L, SOURCE_ID);
        verify(drawerService).sensitives(10L, SOURCE_ID,
                filters(List.of("PII"), List.of("incident reports", "system documentation"), List.of(), List.of()),
                true);
        verify(drawerService).occurrences(10L, SOURCE_ID, "EMAIL ADDRESS", 10, 20, true);
        verify(drawerService).files(10L, SOURCE_ID, new EndpointFileQuery(
                filters(List.of(), List.of(), List.of("it & technical"), List.of("EMAIL ADDRESS")), "notes", 10, 25));
        verify(drawerService).scans(10L, SOURCE_ID, 10, 5);
    }

    @Test
    @DisplayName("defaults, merged repeated filters and lower-cased ids")
    void defaultsAndMergedFilters() throws Exception {
        perform(new Call(ENDPOINT + "/sensitives")).andExpect(status().isOk());
        perform(new Call(ENDPOINT + "/sensitives", "sensitives", "PII", "sensitives", "FINANCIAL,PII",
                "category", " hr , it & technical")).andExpect(status().isOk());
        perform(new Call(ENDPOINT + "/occurrences", "entityName", " PERSON NAME ")).andExpect(status().isOk());
        perform(new Call(ENDPOINT + "/files")).andExpect(status().isOk());
        perform(new Call(ENDPOINT + "/scans")).andExpect(status().isOk());
        perform(new Call("/api/dspm/datastores/0123456789ABCDEF01234567/endpoint/overview")).andExpect(status().isOk());

        verify(drawerService).sensitives(10L, SOURCE_ID, EndpointFilters.NONE, false);
        verify(drawerService).sensitives(10L, SOURCE_ID,
                filters(List.of("FINANCIAL", "PII"), List.of(), List.of("hr", "it & technical"), List.of()), false);
        verify(drawerService).occurrences(10L, SOURCE_ID, "PERSON NAME", 0, 10, false);
        verify(drawerService).files(10L, SOURCE_ID, new EndpointFileQuery(EndpointFilters.NONE, null, 0, 10));
        verify(drawerService).scans(10L, SOURCE_ID, 0, 10);
        verify(drawerService).overview(10L, SOURCE_ID);
    }

    @Test
    @DisplayName("?clientId=99 is a 400 on every endpoint, with no service call")
    void clientIdIsRejected() throws Exception {
        for (Call call : everyEndpoint()) {
            expectInvalid(call.with("clientId", "99"), "clientId");
        }
        verifyNoInteractions(drawerService);
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
        verifyNoInteractions(drawerService);
    }

    // ── validation ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every validation class is a 400 naming the parameter, with no service call")
    void validationFailuresNameTheParameter() throws Exception {
        String sensitives = ENDPOINT + "/sensitives";
        String fiftyOne = IntStream.rangeClosed(0, 50).mapToObj(i -> "c" + i).collect(Collectors.joining(","));
        expectInvalid(new Call(sensitives, "category", fiftyOne), "category");
        expectInvalid(new Call(sensitives, "sensitives", "pii"), "sensitives");
        expectInvalid(new Call(sensitives, "documentClass", ","), "documentClass");
        expectInvalid(new Call(sensitives, "documentClass", ""), "documentClass");
        expectInvalid(new Call(sensitives, "category", " "), "category");
        expectInvalid(new Call(sensitives, "dataElement", "x".repeat(129)), "dataElement");
        expectInvalid(new Call(sensitives, "dataElement", "EMAIL\u0007ADDRESS"), "dataElement");
        expectInvalid(new Call(sensitives, "showFalsePositives", "yes"), "showFalsePositives");
        expectInvalid(new Call(sensitives, "showFalsePositives", "true", "showFalsePositives", "false"),
                "showFalsePositives");
        expectInvalid(new Call(sensitives, "q", "notes"), "q");

        String occurrences = ENDPOINT + "/occurrences";
        expectInvalid(new Call(occurrences), "entityName");
        expectInvalid(new Call(occurrences, "entityName", "x".repeat(129)), "entityName");
        expectInvalid(new Call(occurrences, "entityName", "A", "entityName", "B"), "entityName");
        expectInvalid(new Call(occurrences, "entityName", "EMAIL ADDRESS", "skip", "10001"), "skip");
        expectInvalid(new Call(occurrences, "entityName", "EMAIL ADDRESS", "limit", "0"), "limit");
        expectInvalid(new Call(occurrences, "entityName", "EMAIL ADDRESS", "limit", "101"), "limit");
        expectInvalid(new Call(occurrences, "entityName", "EMAIL ADDRESS", "category", "hr"), "category");

        String files = ENDPOINT + "/files";
        expectInvalid(new Call(files, "sensitives", "Pii"), "sensitives");
        expectInvalid(new Call(files, "documentClass", ","), "documentClass");
        expectInvalid(new Call(files, "q", "x".repeat(201)), "q");
        expectInvalid(new Call(files, "skip", "-1"), "skip");
        expectInvalid(new Call(files, "limit", "abc"), "limit");
        expectInvalid(new Call(files, "showFalsePositives", "true"), "showFalsePositives");

        expectInvalid(new Call(ENDPOINT + "/scans", "limit", "101"), "limit");
        expectInvalid(new Call(ENDPOINT + "/scans", "q", "x"), "q");
        expectInvalid(new Call(ENDPOINT + "/overview", "foo", "1"), "foo");
        expectInvalid(new Call(ENDPOINT + "/filter-options", "page", "1"), "page");
        expectInvalid(new Call(ENDPOINT + "/summary", "showFalsePositives", "true"), "showFalsePositives");
        expectInvalid(new Call("/api/dspm/datastores/not-a-source-id/endpoint/summary"), "sourceId");

        verifyNoInteractions(drawerService);
    }

    @Test
    @DisplayName("an entity outside the agent's own list is a 400 on entityName")
    void unknownEntityIsRejected() throws Exception {
        when(drawerService.occurrences(10L, SOURCE_ID, "BANK ACCOUNT", 0, 10, false))
                .thenThrow(new DspmBadRequestException("entityName", "entityName is not an entity of this agent"));

        expectInvalid(new Call(ENDPOINT + "/occurrences", "entityName", "BANK ACCOUNT"), "entityName");
    }

    // ── error mapping and headers ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ASSET_NOT_FOUND is a 404")
    void assetNotFound() throws Exception {
        when(drawerService.overview(10L, SOURCE_ID)).thenThrow(
                new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND, "Asset not found"));

        perform(new Call(ENDPOINT + "/overview")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Asset not found"));
    }

    @Test
    @DisplayName("FORBIDDEN_SCOPE is a 403 with the PLATFORM message")
    void forbiddenScope() throws Exception {
        when(drawerService.summary(10L, SOURCE_ID)).thenThrow(new MattersApiException(
                MattersApiException.Reason.FORBIDDEN_SCOPE, "This Matters key needs the PLATFORM scope"));

        perform(new Call(ENDPOINT + "/summary")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_SCOPE"))
                .andExpect(jsonPath("$.message").value("This Matters key needs the PLATFORM scope"));
    }

    @Test
    @DisplayName("an endpoint-agent 5xx reaches the caller as a 502 BAD_UPSTREAM_RESPONSE")
    void upstreamFailureIsABadGateway() throws Exception {
        when(drawerService.scans(10L, SOURCE_ID, 0, 10)).thenThrow(new MattersApiException(
                MattersApiException.Reason.BAD_UPSTREAM_RESPONSE, "Matters returned 500"));

        perform(new Call(ENDPOINT + "/scans")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("BAD_UPSTREAM_RESPONSE"));
    }

    @Test
    @DisplayName("occurrences are answered with Cache-Control: no-store")
    void occurrencesAreNotStored() throws Exception {
        perform(new Call(ENDPOINT + "/occurrences", "entityName", "EMAIL ADDRESS"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(drawerService).occurrences(10L, SOURCE_ID, "EMAIL ADDRESS", 0, 10, false);
    }
}
