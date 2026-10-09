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
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        service = mock(AutoSpottingService.class);
        accessService = mock(CloudAccountAccessService.class);

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
                new AutoSpottingFeatureFlags(awsConversionsEnabled, false, false));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static void expectPaused(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(AutoSpottingBlockedException.CONVERSIONS_PAUSED))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── paused (default) ─────────────────────────────────────────────────────

    @Test
    void paused_enableIsRefusedWithoutServiceCall() throws Exception {
        expectPaused(mvc(false).perform(post(BASE + "/asgs/" + AWS_ID + "/enable" + ASG)));
        verifyNoInteractions(service);
    }

    @Test
    void paused_instanceTypesWithApplyTrue_isRefused() throws Exception {
        expectPaused(mvc(false).perform(json(put(BASE + "/asgs/" + AWS_ID + "/instance-types" + ASG + "&apply=true"),
                "{\"instanceTypes\":[\"t3.micro\"]}")));
        verifyNoInteractions(service);
    }

    @Test
    void paused_instanceTypesWithApplyOmitted_isRefused() throws Exception {
        expectPaused(mvc(false).perform(json(put(BASE + "/asgs/" + AWS_ID + "/instance-types" + ASG),
                "{\"instanceTypes\":[\"t3.micro\"]}")));
        verifyNoInteractions(service);
    }

    @Test
    void paused_spotConfigWithApplyTrue_isRefused() throws Exception {
        expectPaused(mvc(false).perform(json(put(BASE + "/asgs/" + AWS_ID + "/spot-config" + ASG + "&apply=true"),
                "{\"spotPercentage\":75}")));
        verifyNoInteractions(service);
    }

    @Test
    void paused_asgConfigUpdate_isAlwaysRefused() throws Exception {
        expectPaused(mvc(false).perform(json(put(BASE + "/asgs/" + AWS_ID + "/config" + ASG),
                "{\"enabled\":false}")));
        verifyNoInteractions(service);
    }

    @Test
    void paused_register_isRefused() throws Exception {
        expectPaused(mvc(false).perform(post(BASE + "/register/" + AWS_ID)));
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

    // ── enabled ──────────────────────────────────────────────────────────────

    @Test
    void enabled_enableReachesServiceWithResolvedAccount() throws Exception {
        mvc(true).perform(post(BASE + "/asgs/" + AWS_ID + "/enable" + ASG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(service).enableAutoSpotting(account, "us-east-1", "web");
    }

    @Test
    void enabled_instanceTypesApplyTrue_reachesService() throws Exception {
        mvc(true).perform(json(put(BASE + "/asgs/" + AWS_ID + "/instance-types" + ASG + "&apply=true"),
                        "{\"instanceTypes\":[\"t3.micro\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));
        verify(service).saveInstanceTypeConfig(account, "us-east-1", "web", List.of("t3.micro"), true);
    }

    @Test
    void enabled_readOnlyRefusalFromServiceIsMappedNotWrapped() throws Exception {
        doThrow(AutoSpottingBlockedException.readOnlyConnection())
                .when(service).saveSpotConfig(account, "us-east-1", "web", 75, null, true);

        mvc(true).perform(json(put(BASE + "/asgs/" + AWS_ID + "/spot-config" + ASG + "&apply=true"),
                        "{\"spotPercentage\":75}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(AutoSpottingBlockedException.READ_ONLY_CONNECTION));
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
}
