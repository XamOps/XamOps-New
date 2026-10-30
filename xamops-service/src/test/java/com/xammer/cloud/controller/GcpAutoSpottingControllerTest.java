package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.AutoSpottingBlockedException;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.gcp.GcpAutoSpottingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GCP AutoSpotting controller: the conversion pause (checked after the tenancy guard and before
 * any service call) and the typed refusal mapping. The service is mocked, so nothing reaches GCP.
 */
class GcpAutoSpottingControllerTest {

    private static final String PROJECT = "proj";
    private static final String FOREIGN = "other-proj";
    private static final String BASE = "/api/gcp/autospotting";
    private static final String MIG = "&region=us-central1&migName=web";

    private GcpAutoSpottingService gcpService;
    private CloudAccountAccessService accessService;
    private com.xammer.cloud.service.autospotting.core.LegacyDisableGuard legacyDisableGuard;

    @BeforeEach
    void setUp() {
        gcpService = mock(GcpAutoSpottingService.class);
        accessService = mock(CloudAccountAccessService.class);
        legacyDisableGuard = mock(com.xammer.cloud.service.autospotting.core.LegacyDisableGuard.class);
        when(legacyDisableGuard.beforeDisable(any(), any(), any(), any()))
                .thenReturn(com.xammer.cloud.service.autospotting.core.LegacyDisableGuard.Guard.noLease());
        CloudAccount account = new CloudAccount();
        account.setId(3L);
        account.setProvider("GCP");
        account.setGcpProjectId(PROJECT);
        when(accessService.requireAccessByIdentifier(any(), eq(PROJECT))).thenReturn(account);
        when(accessService.requireAccessByIdentifier(any(), eq(FOREIGN)))
                .thenThrow(new AccessDeniedException("Account not found or access denied."));

        ClientUserDetails principal = new ClientUserDetails("user@example.com", "pw",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), 10L, 500L, "tenant-a");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc(boolean gcpConversionsEnabled) {
        GcpAutoSpottingController controller = new GcpAutoSpottingController(gcpService, accessService,
                new AutoSpottingFeatureFlags(false, gcpConversionsEnabled, false, false), legacyDisableGuard);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private static void expectCode(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── paused (default) ─────────────────────────────────────────────────────

    @Test
    void paused_enableIsRefusedWithoutServiceCall() throws Exception {
        expectCode(mvc(false).perform(post(BASE + "/enable?accountId=" + PROJECT + MIG)),
                409, AutoSpottingBlockedException.CONVERSIONS_PAUSED);
        verifyNoInteractions(gcpService);
    }

    @Test
    void paused_migConfigUpdateIsRefusedWithoutServiceCall() throws Exception {
        expectCode(mvc(false).perform(put(BASE + "/migs/" + PROJECT + "/config?" + MIG.substring(1))
                        .contentType(MediaType.APPLICATION_JSON).content("{}")),
                409, AutoSpottingBlockedException.CONVERSIONS_PAUSED);
        verifyNoInteractions(gcpService);
    }

    @Test
    void paused_disableStillWorks() throws Exception {
        mvc(false).perform(post(BASE + "/disable?accountId=" + PROJECT + MIG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(gcpService).disableAutoSpotting(PROJECT, "us-central1", "web");
    }

    @Test
    void paused_listStillWorks() throws Exception {
        when(gcpService.listMigsAllRegionsWithCosts(PROJECT)).thenReturn(List.of());
        mvc(false).perform(get(BASE + "/migs/all-regions/" + PROJECT)).andExpect(status().isOk());
        verify(gcpService).listMigsAllRegionsWithCosts(PROJECT);
    }

    @Test
    void paused_foreignAccountGets403NotPaused() throws Exception {
        mvc(false).perform(post(BASE + "/enable?accountId=" + FOREIGN + MIG))
                .andExpect(status().isForbidden());
        verifyNoInteractions(gcpService);
    }

    // ── enabled ──────────────────────────────────────────────────────────────

    @Test
    void enabled_enableReachesService() throws Exception {
        mvc(true).perform(post(BASE + "/enable?accountId=" + PROJECT + MIG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(gcpService).enableAutoSpotting(PROJECT, "us-central1", "web");
    }

    @Test
    void enabled_gkeManagedEnableMapsTo422() throws Exception {
        doThrow(AutoSpottingBlockedException.gkeManaged())
                .when(gcpService).enableAutoSpotting(PROJECT, "us-central1", "web");
        expectCode(mvc(true).perform(post(BASE + "/enable?accountId=" + PROJECT + MIG)),
                422, AutoSpottingBlockedException.GKE_MANAGED);
    }

    @Test
    void disableConflictMapsTo409() throws Exception {
        doThrow(AutoSpottingBlockedException.gcpUpdateConflict("web"))
                .when(gcpService).disableAutoSpotting(PROJECT, "us-central1", "web");
        expectCode(mvc(false).perform(post(BASE + "/disable?accountId=" + PROJECT + MIG)),
                409, AutoSpottingBlockedException.GCP_MIG_UPDATE_CONFLICT);
    }

    @Test
    void disableGenericFailureStays500() throws Exception {
        doThrow(new RuntimeException("Failed to disable Spot: boom"))
                .when(gcpService).disableAutoSpotting(PROJECT, "us-central1", "web");
        mvc(false).perform(post(BASE + "/disable?accountId=" + PROJECT + MIG))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    // ── the Phase A disable rule (A-02, AC-PLN-07, plan-phase-08.md task 8.4) ──

    @Test
    void disable_v2StateRow_returnsPlanRequired_andNoServiceCall() throws Exception {
        when(legacyDisableGuard.beforeDisable(eq(com.xammer.cloud.domain.autospotting.Provider.GCP),
                eq(3L), eq(PROJECT + "/us-central1/web"), any()))
                .thenThrow(AutoSpottingBlockedException.planRequired("/autospotting/gcp/" + PROJECT + "/x/revert"));

        mvc(false).perform(post(BASE + "/disable?accountId=" + PROJECT + MIG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(AutoSpottingBlockedException.PLAN_REQUIRED))
                .andExpect(jsonPath("$.planUrl").isNotEmpty());
        verify(gcpService, org.mockito.Mockito.never()).disableAutoSpotting(any(), any(), any());
    }

    @Test
    void disable_noRow_phaseABehaviourUnchanged() throws Exception {
        // Default guard stub returns Guard.noLease(): the legacy disable proceeds (FR-CMP-05).
        mvc(false).perform(post(BASE + "/disable?accountId=" + PROJECT + MIG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(gcpService).disableAutoSpotting(PROJECT, "us-central1", "web");
    }

    @Test
    void disable_leaseHeld_returnsGroupBusy() throws Exception {
        when(legacyDisableGuard.beforeDisable(any(), any(), any(), any()))
                .thenThrow(AutoSpottingBlockedException.groupBusy());

        mvc(false).perform(post(BASE + "/disable?accountId=" + PROJECT + MIG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(AutoSpottingBlockedException.AS_GROUP_BUSY));
        verify(gcpService, org.mockito.Mockito.never()).disableAutoSpotting(any(), any(), any());
    }

    @Test
    void disable_v2MutationsEnabled_everyGroupPlanRequired() throws Exception {
        when(legacyDisableGuard.beforeDisable(any(), any(), any(), any()))
                .thenThrow(AutoSpottingBlockedException.planRequired("/plan"));

        mvc(false).perform(post(BASE + "/disable?accountId=" + PROJECT + MIG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(AutoSpottingBlockedException.PLAN_REQUIRED));
        verify(gcpService, org.mockito.Mockito.never()).disableAutoSpotting(any(), any(), any());
    }
}
