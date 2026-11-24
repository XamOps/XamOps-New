package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.SpotPlan;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.core.IdempotencyService;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService;
import com.xammer.cloud.service.autospotting.core.plan.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The plan request for a MIG_POLICY_UPDATE carries the edited rolling-update fields to the plan service
 * (plan-phase-27.md 27.4), and the idempotency hash covers them (a changed edit is a different request).
 */
class MigPolicyUpdateControllerTest {
    private final PlanService planService = mock(PlanService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final CloudAccountAccessService access = mock(CloudAccountAccessService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CloudAccount account = new CloudAccount();
        account.setId(7L);
        account.setProvider("GCP");
        when(access.requireAccess(any(), eq(7L))).thenReturn(account);
        when(idempotency.requireKey("k1")).thenReturn("k1");
        when(idempotency.hash(any())).thenAnswer(i -> "h:" + i.getArgument(0));
        SpotPlan plan = new SpotPlan();
        plan.setId(UUID.randomUUID());
        plan.setGroupId(UUID.randomUUID());
        plan.setCloudAccountId(7L);
        plan.setKind(OperationKind.MIG_POLICY_UPDATE);
        plan.setStatus(SpotPlan.PlanStatus.OPEN);
        plan.setFromState(GroupState.UNMANAGED);
        plan.setVersion(1);
        plan.setSchemaVersion(1);
        plan.setLiveFingerprint("fp");
        plan.setBody("{\"target\":{}}");
        plan.setWarningIds("[]");
        plan.setCreatedBy("owner");
        plan.setCreatedAt(Instant.now());
        plan.setExpiresAt(Instant.now().plusSeconds(600));
        when(planService.createPlan(any(), any(), any())).thenReturn(plan);
        PlanController controller = new PlanController(access, planService, mock(ConfirmService.class), idempotency,
                mock(SpotPlanRepository.class));
        com.xammer.cloud.security.ClientUserDetails principal = new com.xammer.cloud.security.ClientUserDetails(
                "user@example.com", "pw", java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                "ROLE_USER")), 10L, 500L, "tenant-a");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null,
                        principal.getAuthorities()));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void aPolicyEditReachesThePlanServiceAsTheCommandsPolicyEdit() throws Exception {
        mvc.perform(post("/api/autospotting/v2/accounts/7/plans").header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MIG_POLICY_UPDATE\",\"groupRef\":\"xamops-test/us-central1/web-mig\","
                                + "\"policyEdit\":{\"updatePolicy\":{\"maxSurge\":{\"fixed\":6}}}}"))
                .andExpect(status().isCreated());
        ArgumentCaptor<PlanService.CreateCommand> cmd = ArgumentCaptor.forClass(PlanService.CreateCommand.class);
        verify(planService).createPlan(any(), any(), cmd.capture());
        assertThat(cmd.getValue().kind()).isEqualTo(OperationKind.MIG_POLICY_UPDATE);
        assertThat(cmd.getValue().policyEditJson()).isEqualTo("{\"updatePolicy\":{\"maxSurge\":{\"fixed\":6}}}");
        assertThat(cmd.getValue().requestSha256()).contains("policyEdit");
    }

    @Test
    void aRequestWithoutAPolicyEditKeepsItsOldHashAndCommand() throws Exception {
        mvc.perform(post("/api/autospotting/v2/accounts/7/plans").header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"ENABLE\",\"groupRef\":\"us-east-1/asg\",\"profileId\":\"conservative\"}"))
                .andExpect(status().isCreated());
        ArgumentCaptor<PlanService.CreateCommand> cmd = ArgumentCaptor.forClass(PlanService.CreateCommand.class);
        verify(planService).createPlan(any(), any(), cmd.capture());
        assertThat(cmd.getValue().policyEditJson()).isNull();
        assertThat(cmd.getValue().requestSha256()).doesNotContain("policyEdit");
    }
}
