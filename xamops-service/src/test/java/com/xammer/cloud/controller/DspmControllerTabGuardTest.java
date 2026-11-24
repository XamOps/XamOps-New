package com.xammer.cloud.controller;

import com.xammer.cloud.controller.advice.MattersExceptionHandler;
import com.xammer.cloud.dto.matters.DspmFilters;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.DspmStatus;
import com.xammer.cloud.dto.matters.DspmSummary;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DspmAssetQuery;
import com.xammer.cloud.service.matters.MattersDspmService;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

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

/** A24: the five existing DSPM endpoints now run {@link XamLensTabGuard} first, and answer as before otherwise. */
class DspmControllerTabGuardTest {

    private static final String TAB = "secops-xamlens";

    private static final List<String> ENDPOINTS = List.of(
            "/api/dspm/status", "/api/dspm/summary", "/api/dspm/cloud/assets", "/api/dspm/cloud/filters",
            "/api/dspm/assets/asset-1");

    private GlobalTabRuleReader rules;
    private UserTabAccessRepository tabs;
    private MattersDspmService dspmService;
    private MockMvc mvc;

    private static ClientUserDetails principal(long userId, String role) {
        return new ClientUserDetails("user" + userId + "@example.com", "pw",
                List.of(new SimpleGrantedAuthority(role)), 10L, userId, "tenant-a");
    }

    @BeforeEach
    void setUp() {
        rules = mock(GlobalTabRuleReader.class);
        tabs = mock(UserTabAccessRepository.class);
        when(rules.rulesFor(anyLong())).thenReturn(GlobalTabRuleReader.UserRules.NONE);
        when(tabs.findTabNamesByUserId(anyLong())).thenReturn(List.of());

        dspmService = mock(MattersDspmService.class);
        DspmSummary.Metric metric = new DspmSummary.Metric(3, List.of());
        when(dspmService.getStatus(10L)).thenReturn(DspmStatus.ready("synthetic-project", null));
        when(dspmService.getSummary(10L, null)).thenReturn(new DspmSummary(metric, metric, metric, Map.of(), Map.of()));
        when(dspmService.getAssets(eq(10L), any(DspmAssetQuery.class))).thenReturn(DspmPage.empty(1, 25));
        when(dspmService.getFilters(eq(10L), any(DspmAssetQuery.class))).thenReturn(new DspmFilters(Map.of(), Map.of()));

        DspmController controller = new DspmController(dspmService, new XamLensTabGuard(rules, tabs));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(), new MattersExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(ClientUserDetails principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    @DisplayName("a restricted ROLE_XAMOPS user without the tab gets 403 TAB_ACCESS_DENIED on all five")
    void restrictedUserWithoutTheTabIsRefused() throws Exception {
        authenticateAs(principal(500L, "ROLE_XAMOPS"));
        when(tabs.findTabNamesByUserId(500L)).thenReturn(List.of("cloudops-cloudk8s"));

        for (String endpoint : ENDPOINTS) {
            mvc.perform(get(endpoint)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TAB_ACCESS_DENIED"));
        }
        verifyNoInteractions(dspmService);
    }

    @Test
    @DisplayName("a platform-wide HIDE on secops-xamlens refuses ROLE_XAMOPS on all five")
    void platformWideHideIsRefused() throws Exception {
        authenticateAs(principal(500L, "ROLE_XAMOPS"));
        when(rules.rulesFor(500L)).thenReturn(new GlobalTabRuleReader.UserRules(List.of(TAB), List.of()));

        for (String endpoint : ENDPOINTS) {
            mvc.perform(get(endpoint)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(dspmService);
    }

    @Test
    @DisplayName("ROLE_XAMOPS with the tab keeps today's responses")
    void userWithTheTabKeepsTheResponses() throws Exception {
        authenticateAs(principal(500L, "ROLE_XAMOPS"));
        when(tabs.findTabNamesByUserId(500L)).thenReturn(List.of(TAB));

        for (String endpoint : ENDPOINTS) {
            mvc.perform(get(endpoint)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/dspm/status")).andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.projectName").value("synthetic-project"));
        mvc.perform(get("/api/dspm/cloud/assets")).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(25));
    }

    @Test
    @DisplayName("ROLE_SUPER_ADMIN with a restricted list and a HIDE rule is never gated: 200 on all five")
    void superAdminIsNotGated() throws Exception {
        authenticateAs(principal(700L, "ROLE_SUPER_ADMIN"));
        when(rules.rulesFor(700L)).thenReturn(new GlobalTabRuleReader.UserRules(List.of(TAB), List.of()));
        when(tabs.findTabNamesByUserId(700L)).thenReturn(List.of("cloudops-cloudk8s"));

        for (String endpoint : ENDPOINTS) {
            mvc.perform(get(endpoint)).andExpect(status().isOk());
        }
        verify(dspmService).getStatus(10L);
        verify(dspmService).getAsset(10L, "asset-1");
    }
}
