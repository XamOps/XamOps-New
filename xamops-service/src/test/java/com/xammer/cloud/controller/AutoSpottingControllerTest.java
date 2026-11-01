package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.autospotting.CostResponse;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.AutoSpottingService;
import com.xammer.cloud.service.autospotting.AutoSpottingBlockedException;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
 * AWS AutoSpotting controller: the 12-digit account-id contract, the conversion pause
 * (decision 9: checked after the tenancy guard and before any service call) and the typed
 * refusal mapping. The service is mocked, so nothing here reaches AWS.
 */
class AutoSpottingControllerTest {

    private static final String AWS_ID = "012345678901";
    private static final String FOREIGN_ID = "999999999999";
    private static final String BASE = "/api/autospotting";
    private static final String ASG = "?region=us-east-1&asgName=web";

    private AutoSpottingService service;
    private CloudAccountAccessService accessService;
    private com.xammer.cloud.service.autospotting.core.LegacyDisableGuard legacyDisableGuard;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        service = mock(AutoSpottingService.class);
        accessService = mock(CloudAccountAccessService.class);
        legacyDisableGuard = mock(com.xammer.cloud.service.autospotting.core.LegacyDisableGuard.class);
        // Default: a group with no v2 row and the flag off keeps working; the guard holds no lease.
        when(legacyDisableGuard.beforeDisable(any(), any(), any(), any()))
                .thenReturn(com.xammer.cloud.service.autospotting.core.LegacyDisableGuard.Guard.noLease());

        account = new CloudAccount();
        account.setId(7L);
        account.setProvider("AWS");
        account.setAwsAccountId(AWS_ID);
        account.setAccessType("read-write");
        account.setStatus("CONNECTED");

        when(accessService.requireAwsAccess(any(), eq(AWS_ID))).thenReturn(account);
        when(accessService.requireAwsAccess(any(), eq(FOREIGN_ID)))
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

    private MockMvc mvc(boolean awsConversionsEnabled) {
        AutoSpottingController controller = new AutoSpottingController(service, accessService,
                new AutoSpottingFeatureFlags(awsConversionsEnabled, false, false, false), legacyDisableGuard);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /**
     * B3 (A-02, AC-PLN-07, plan-phase-11.md task 11.5): the legacy mutating endpoints now return 409
     * {@code PLAN_REQUIRED} whatever the conversion flags, and make no SDK call. A conversion runs
     * only through a confirmed v2 plan.
     */
    private static void expectPlanRequired(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(AutoSpottingBlockedException.PLAN_REQUIRED))
                .andExpect(jsonPath("$.planUrl").isNotEmpty());
    }

    // ── B3: legacy mutating endpoints return PLAN_REQUIRED (AC-PLN-07), no SDK call ──

    @Test
    void enableReturnsPlanRequiredWhateverTheFlag_andNoServiceCall() throws Exception {
        expectPlanRequired(mvc(false).perform(post(BASE + "/asgs/" + AWS_ID + "/enable" + ASG)));
        expectPlanRequired(mvc(true).perform(post(BASE + "/asgs/" + AWS_ID + "/enable" + ASG)));
        verifyNoInteractions(service);
    }

    @Test
    void instanceTypesWithApplyTrue_returnsPlanRequired() throws Exception {
        expectPlanRequired(mvc(false).perform(json(put(BASE + "/asgs/" + AWS_ID + "/instance-types" + ASG
                + "&apply=true"), "{\"instanceTypes\":[\"t3.micro\"]}")));
        expectPlanRequired(mvc(true).perform(json(put(BASE + "/asgs/" + AWS_ID + "/instance-types" + ASG
                + "&apply=true"), "{\"instanceTypes\":[\"t3.micro\"]}")));
        verifyNoInteractions(service);
    }

    @Test
    void instanceTypesWithApplyOmitted_returnsPlanRequired() throws Exception {
        // apply defaults to true, so the implicit-apply form also routes to PLAN_REQUIRED.
        expectPlanRequired(mvc(false).perform(json(put(BASE + "/asgs/" + AWS_ID + "/instance-types" + ASG),
                "{\"instanceTypes\":[\"t3.micro\"]}")));
        verifyNoInteractions(service);
    }

    @Test
    void spotConfigWithApplyTrue_returnsPlanRequired() throws Exception {
        expectPlanRequired(mvc(false).perform(json(put(BASE + "/asgs/" + AWS_ID + "/spot-config" + ASG
                + "&apply=true"), "{\"spotPercentage\":75}")));
        verifyNoInteractions(service);
    }

    @Test
    void asgConfigUpdate_returnsPlanRequired() throws Exception {
        expectPlanRequired(mvc(false).perform(json(put(BASE + "/asgs/" + AWS_ID + "/config" + ASG),
                "{\"enabled\":false}")));
        expectPlanRequired(mvc(true).perform(json(put(BASE + "/asgs/" + AWS_ID + "/config" + ASG),
                "{\"enabled\":false}")));
        verifyNoInteractions(service);
    }

    @Test
    void register_returnsPlanRequired() throws Exception {
        expectPlanRequired(mvc(false).perform(post(BASE + "/register/" + AWS_ID)));
        verifyNoInteractions(service);
    }

    @Test
    void paused_instanceTypesSaveOnly_stillWorks() throws Exception {
        mvc(false).perform(json(put(BASE + "/asgs/" + AWS_ID + "/instance-types" + ASG + "&apply=false"),
                        "{\"instanceTypes\":[\"t3.micro\",\"t3a.micro\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false));
        verify(service).saveInstanceTypeConfig(account, "us-east-1", "web", List.of("t3.micro", "t3a.micro"), false);
    }

    @Test
    void paused_disableStillWorks() throws Exception {
        mvc(false).perform(post(BASE + "/asgs/" + AWS_ID + "/disable" + ASG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(service).disableAutoSpotting(account, "us-east-1", "web");
    }

    @Test
    void paused_syncStillWorks() throws Exception {
        when(service.backfillSpotEvents(account, "us-east-1", "web")).thenReturn(2);

        mvc(false).perform(post(BASE + "/asgs/" + AWS_ID + "/sync" + ASG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backfilled").value(2));
    }

    @Test
    void paused_costsReadStillWorks() throws Exception {
        when(service.getCostData(account, false)).thenReturn(new CostResponse());

        mvc(false).perform(get(BASE + "/costs/" + AWS_ID)).andExpect(status().isOk());
        verify(service).getCostData(account, false);
    }

    @Test
    void featureStatus_reportsBothFlags() throws Exception {
        mvc(false).perform(get(BASE + "/feature-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aws.conversionsEnabled").value(false))
                .andExpect(jsonPath("$.gcp.conversionsEnabled").value(false));

        mvc(true).perform(get(BASE + "/feature-status"))
                .andExpect(jsonPath("$.aws.conversionsEnabled").value(true));
        verifyNoInteractions(service, accessService);
    }

    @Test
    void featureStatus_withoutPrincipal_isDenied() throws Exception {
        SecurityContextHolder.clearContext();

        mvc(false).perform(get(BASE + "/feature-status")).andExpect(status().isForbidden());
    }

    // ── enabled-flag no longer opens the SDK path (B3): still PLAN_REQUIRED ─────

    @Test
    void enabledFlag_doesNotReopenTheSdkPath_stillPlanRequired() throws Exception {
        // Even with aws.conversions-enabled=true, enable/apply return PLAN_REQUIRED and never call the
        // service: the flag no longer gates these endpoints (plan-phase-11.md task 11.5).
        expectPlanRequired(mvc(true).perform(post(BASE + "/asgs/" + AWS_ID + "/enable" + ASG)));
        expectPlanRequired(mvc(true).perform(json(put(BASE + "/asgs/" + AWS_ID + "/instance-types" + ASG
                + "&apply=true"), "{\"instanceTypes\":[\"t3.micro\"]}")));
        verifyNoInteractions(service);
    }

    // ── tenancy and the account-id contract ──────────────────────────────────

    @Test
    void foreignAccount_isForbiddenEvenWhilePaused() throws Exception {
        mvc(false).perform(post(BASE + "/asgs/" + FOREIGN_ID + "/enable" + ASG))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void leadingZeroAccountId_isPassedVerbatimToTheGuard() throws Exception {
        mvc(false).perform(get(BASE + "/settings/" + AWS_ID)).andExpect(status().isOk());
        verify(accessService).requireAwsAccess(any(), eq("012345678901"));
        verify(service).getSettings(account);
    }

    @Test
    void readOnlyDisable_returns409WithStableCode() throws Exception {
        doThrow(AutoSpottingBlockedException.readOnlyConnection())
                .when(service).disableAutoSpotting(account, "us-east-1", "web");

        mvc(false).perform(post(BASE + "/asgs/" + AWS_ID + "/disable" + ASG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(AutoSpottingBlockedException.READ_ONLY_CONNECTION));
    }

    // ── the Phase A disable rule (A-02, AC-PLN-07, plan-phase-08.md task 8.4) ──

    @Test
    void disable_v2StateRow_returnsPlanRequired_andNoServiceCall() throws Exception {
        when(legacyDisableGuard.beforeDisable(eq(com.xammer.cloud.domain.autospotting.Provider.AWS),
                eq(7L), eq("us-east-1/web"), any()))
                .thenThrow(AutoSpottingBlockedException.planRequired("/autospotting/aws/" + AWS_ID
                        + "/groups/us-east-1%2Fweb/revert"));

        mvc(false).perform(post(BASE + "/asgs/" + AWS_ID + "/disable" + ASG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(AutoSpottingBlockedException.PLAN_REQUIRED))
                .andExpect(jsonPath("$.planUrl").isNotEmpty());
        verify(service, org.mockito.Mockito.never()).disableAutoSpotting(any(), any(), any());
    }

    @Test
    void disable_noRow_phaseABehaviourUnchanged() throws Exception {
        // Default guard stub returns Guard.noLease(): the legacy disable proceeds (FR-CMP-05).
        mvc(false).perform(post(BASE + "/asgs/" + AWS_ID + "/disable" + ASG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(service).disableAutoSpotting(account, "us-east-1", "web");
    }

    @Test
    void disable_leaseHeld_returnsGroupBusy() throws Exception {
        when(legacyDisableGuard.beforeDisable(any(), any(), any(), any()))
                .thenThrow(AutoSpottingBlockedException.groupBusy());

        mvc(false).perform(post(BASE + "/asgs/" + AWS_ID + "/disable" + ASG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(AutoSpottingBlockedException.AS_GROUP_BUSY));
        verify(service, org.mockito.Mockito.never()).disableAutoSpotting(any(), any(), any());
    }

    @Test
    void disable_v2MutationsEnabled_everyGroupPlanRequired() throws Exception {
        // The guard throws PLAN_REQUIRED for every group once the flag is true (§7.4). We model that
        // by stubbing the guard to throw regardless of the row.
        when(legacyDisableGuard.beforeDisable(any(), any(), any(), any()))
                .thenThrow(AutoSpottingBlockedException.planRequired("/plan"));

        mvc(false).perform(post(BASE + "/asgs/" + AWS_ID + "/disable" + ASG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(AutoSpottingBlockedException.PLAN_REQUIRED));
        verify(service, org.mockito.Mockito.never()).disableAutoSpotting(any(), any(), any());
    }
}
