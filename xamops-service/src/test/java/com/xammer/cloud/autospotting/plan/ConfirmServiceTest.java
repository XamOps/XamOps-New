package com.xammer.cloud.autospotting.plan;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.domain.autospotting.SpotPlan;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Decision;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Role;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService.ConfirmCommand;
import com.xammer.cloud.service.autospotting.core.plan.RevertPreconditions;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The confirm check order and outcomes (design-core.md §7.4; plan-phase-11.md task 11.2 verify). Unit
 * test over mocked collaborators: a stale fingerprint → 409 and no op; a missing ack → 422; desired 1
 * ENABLE → 422 while desired 1 REVERT is accepted; an unreadable switch store holds a revert with
 * AS_SWITCH_UNREADABLE; a clean confirm flips the plan and inserts the op.
 */
class ConfirmServiceTest {

    private static final long ACCOUNT_ID = 42L;

    private SpotPlanRepository plans;
    private SpotGroupRepository groups;
    private SpotOperationRepository operations;
    private OperationStore operationStore;
    private PreemptionService preemption;
    private AutoSpottingAuthorization authorization;
    private RevertPreconditions revertPreconditions;
    private SwitchService switchService;
    private AutoSpottingSwitchProperties switchProperties;
    private ProviderRegistry providers;
    private AutoSpottingAuditWriter auditWriter;
    private ConfirmService confirm;

    @BeforeEach
    void setUp() {
        plans = mock(SpotPlanRepository.class);
        groups = mock(SpotGroupRepository.class);
        operations = mock(SpotOperationRepository.class);
        operationStore = mock(OperationStore.class);
        preemption = mock(PreemptionService.class);
        authorization = mock(AutoSpottingAuthorization.class);
        revertPreconditions = new RevertPreconditions(mock(AutoSpottingSwitchProperties.class));
        switchService = mock(SwitchService.class);
        switchProperties = mock(AutoSpottingSwitchProperties.class);
        providers = new ProviderRegistry(List.of()); // no provider: fingerprint re-read "times out"
        auditWriter = mock(AutoSpottingAuditWriter.class);
        confirm = new ConfirmService(plans, groups, operations, operationStore, preemption, authorization,
                revertPreconditions, switchService, switchProperties, providers, auditWriter,
                mock(com.xammer.cloud.service.autospotting.notify.AutoSpottingNotificationService.class));

        when(switchService.readUncached()).thenReturn(new SwitchState(Set.of(), Set.of(), false, false));
        when(authorization.authorizeConfirm(any(), any(), any(), any())).thenReturn(allow());
        when(preemption.preemptFor(any(), any(), any()))
                .thenReturn(new PreemptionService.Preemption(PreemptionService.Verdict.CLEAR, null, false));
    }

    private static Decision allow() {
        return new Decision(true, Role.CLIENT_OWNER, Actor.user(100L, "ROLE_USER"), false, null, null, null);
    }

    private CloudAccount account() {
        CloudAccount a = new CloudAccount();
        a.setId(ACCOUNT_ID);
        a.setProvider("AWS");
        Client c = new Client();
        c.setId(7L);
        a.setClient(c);
        return a;
    }

    private ClientUserDetails user() {
        return new ClientUserDetails("owner", "x", List.of(new SimpleGrantedAuthority("ROLE_USER")),
                7L, 100L, "t");
    }

    private SpotGroup group(GroupState state) {
        SpotGroup g = new SpotGroup();
        g.setId(UUID.randomUUID());
        g.setProvider(Provider.AWS);
        g.setCloudAccountId(ACCOUNT_ID);
        g.setClientId(7L);
        g.setGroupKey("us-east-1/asg");
        g.setState(state);
        return g;
    }

    private SpotPlan plan(OperationKind kind, UUID groupId, String warningIds, boolean requiresOptin) {
        SpotPlan p = new SpotPlan();
        p.setId(UUID.randomUUID());
        p.setGroupId(groupId);
        p.setCloudAccountId(ACCOUNT_ID);
        p.setKind(kind);
        p.setStatus(SpotPlan.PlanStatus.OPEN);
        p.setFromState(GroupState.UNMANAGED);
        p.setVersion(1);
        p.setSchemaVersion(1);
        p.setLiveFingerprint("fp:1");
        p.setBody("{\"target\":{}}");
        p.setRequiresFullSpotOptin(requiresOptin);
        p.setWarningIds(warningIds);
        p.setCreatedBy("owner");
        p.setCreatedAt(Instant.now());
        p.setExpiresAt(Instant.now().plusSeconds(600));
        return p;
    }

    private ConfirmCommand cmd(int version, List<String> acks, boolean optin) {
        return new ConfirmCommand(version, acks, optin, null, false, "key-1", "hash-1");
    }

    @Test
    void cleanEnableConfirmFlipsPlanAndInsertsOp() {
        SpotGroup g = group(GroupState.ACTIVE);
        SpotPlan p = plan(OperationKind.CONFIG_APPLY, g.getId(), "[]", false);
        when(plans.findById(p.getId())).thenReturn(Optional.of(p));
        when(operations.findByPlanId(p.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        when(plans.confirmIfOpen(eq(p.getId()), eq(1), anyString(), any(), anyString(), any())).thenReturn(1);

        ConfirmService.ConfirmResult result = confirm.confirm(account(), user(), p.getId(), cmd(1, List.of(), false));

        assertThat(result.status()).isEqualTo("QUEUED");
        verify(operationStore).insertQueued(any());
        verify(auditWriter).write(any());
    }

    @Test
    void missingAckIs422AndNoOp() {
        SpotGroup g = group(GroupState.ACTIVE);
        SpotPlan p = plan(OperationKind.CONFIG_APPLY, g.getId(), "[\"w1\"]", false);
        when(plans.findById(p.getId())).thenReturn(Optional.of(p));
        when(operations.findByPlanId(p.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));

        assertThatThrownBy(() -> confirm.confirm(account(), user(), p.getId(), cmd(1, List.of(), false)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ACK_REQUIRED"));
        verify(operationStore, never()).insertQueued(any());
    }

    @Test
    void versionMismatchIs409AndNoOp() {
        SpotGroup g = group(GroupState.ACTIVE);
        SpotPlan p = plan(OperationKind.CONFIG_APPLY, g.getId(), "[]", false);
        when(plans.findById(p.getId())).thenReturn(Optional.of(p));
        when(operations.findByPlanId(p.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));

        assertThatThrownBy(() -> confirm.confirm(account(), user(), p.getId(), cmd(2, List.of(), false)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PLAN_VERSION_MISMATCH"));
        verify(operationStore, never()).insertQueued(any());
    }

    /** A SUCCEEDED REVERT op of {@code groupId} whose plan body says {@code reasons} (the drain-only history). */
    private void lastRevertWas(UUID groupId, String reasonsJson) {
        SpotOperation last = new SpotOperation();
        last.setId(UUID.randomUUID());
        last.setKind(OperationKind.REVERT);
        last.setStatus(com.xammer.cloud.domain.autospotting.OperationStatus.SUCCEEDED);
        last.setCreatedAt(Instant.now());
        SpotPlan lastPlan = plan(OperationKind.REVERT, groupId, "[]", false);
        lastPlan.setBody("{\"revertPath\":{\"reasons\":" + reasonsJson + "}}");
        last.setPlanId(lastPlan.getId());
        when(operations.findByGroupId(groupId)).thenReturn(List.of(last));
        when(plans.findById(lastPlan.getId())).thenReturn(Optional.of(lastPlan));
    }

    @Test
    void gr02ARevertedGroupCannotBeRevertedAgainUnlessTheLastRevertWasDrainOnly() {
        SpotGroup g = group(GroupState.REVERTED);
        SpotPlan p = plan(OperationKind.REVERT, g.getId(), "[]", false);
        when(plans.findById(p.getId())).thenReturn(Optional.of(p));
        when(operations.findByPlanId(p.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        lastRevertWas(g.getId(), "[]");
        assertThatThrownBy(() -> confirm.confirm(account(), user(), p.getId(), cmd(1, List.of(), false)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PLAN_KIND_NOT_ALLOWED"));
        verify(operationStore, never()).insertQueued(any());
    }

    @Test
    void gr02ARevertedGroupAfterADrainOnlyRevertCanBeRevertedExactly() {
        SpotGroup g = group(GroupState.REVERTED);
        SpotPlan p = plan(OperationKind.REVERT, g.getId(), "[]", false);
        when(plans.findById(p.getId())).thenReturn(Optional.of(p));
        when(operations.findByPlanId(p.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        when(plans.confirmIfOpen(eq(p.getId()), eq(1), anyString(), any(), anyString(), any())).thenReturn(1);
        lastRevertWas(g.getId(), "[\"PASSROLE_DENIED\"]");
        assertThat(confirm.confirm(account(), user(), p.getId(), cmd(1, List.of(), false)).status()).isEqualTo("QUEUED");
        verify(operationStore).insertQueued(any());
    }

    @Test
    void desiredOneEnableIs422_whileDesiredOneRevertIsAccepted() {
        // ENABLE with 100% Spot opt-in required but not given → 422 (PROGRESS check).
        SpotGroup g = group(GroupState.UNMANAGED);
        SpotPlan enable = plan(OperationKind.ENABLE, g.getId(), "[]", true);
        when(plans.findById(enable.getId())).thenReturn(Optional.of(enable));
        when(operations.findByPlanId(enable.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        assertThatThrownBy(() -> confirm.confirm(account(), user(), enable.getId(), cmd(1, List.of(), false)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // REVERT never runs the PROGRESS 100%-Spot/size check, so it is accepted.
        SpotGroup active = group(GroupState.ACTIVE);
        SpotPlan revert = plan(OperationKind.REVERT, active.getId(), "[]", true);
        when(plans.findById(revert.getId())).thenReturn(Optional.of(revert));
        when(operations.findByPlanId(revert.getId())).thenReturn(Optional.empty());
        when(groups.findById(active.getId())).thenReturn(Optional.of(active));
        when(plans.confirmIfOpen(eq(revert.getId()), eq(1), anyString(), any(), anyString(), any()))
                .thenReturn(1);
        ConfirmService.ConfirmResult result =
                confirm.confirm(account(), user(), revert.getId(), cmd(1, List.of(), false));
        assertThat(result.status()).isEqualTo("QUEUED");
    }

    @Test
    void aPlanWithAHardBlockerIs422WithTheBlockersOwnCodeAndNoOp() {
        // A Launch Configuration group (or desired <= 1, a terminate-first policy, ...) is never confirmed.
        SpotGroup g = group(GroupState.UNMANAGED);
        SpotPlan enable = plan(OperationKind.ENABLE, g.getId(), "[]", false);
        enable.setBody("{\"target\":{},\"eligibility\":{\"blockers\":[{\"code\":\"LAUNCH_CONFIGURATION\","
                + "\"text\":\"This group uses a launch configuration. Migrate it to a launch template, then plan again.\"}]}}");
        when(plans.findById(enable.getId())).thenReturn(Optional.of(enable));
        when(operations.findByPlanId(enable.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));

        assertThatThrownBy(() -> confirm.confirm(account(), user(), enable.getId(), cmd(1, List.of(), false)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("LAUNCH_CONFIGURATION");
                    assertThat(ex.getMessage()).contains("Migrate it to a launch template");
                });
        verify(operationStore, never()).insertQueued(any());
    }

    @Test
    void aPlanWithNoBlockersStoresThePlanTimeConfigurationForPriorConfigCompensation() {
        SpotGroup g = group(GroupState.ACTIVE);
        SpotPlan apply = plan(OperationKind.CONFIG_APPLY, g.getId(), "[]", false);
        apply.setBody("{\"current\":{\"shape\":\"MIXED_INSTANCES_POLICY\"},\"target\":{\"shape\":\"MIXED_INSTANCES_POLICY\"},"
                + "\"eligibility\":{\"blockers\":[]}}");
        when(plans.findById(apply.getId())).thenReturn(Optional.of(apply));
        when(operations.findByPlanId(apply.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        when(plans.confirmIfOpen(eq(apply.getId()), eq(1), anyString(), any(), anyString(), any())).thenReturn(1);

        confirm.confirm(account(), user(), apply.getId(), cmd(1, List.of(), false));

        org.mockito.ArgumentCaptor<com.xammer.cloud.service.autospotting.core.ops.OperationRecord> captor =
                org.mockito.ArgumentCaptor.forClass(com.xammer.cloud.service.autospotting.core.ops.OperationRecord.class);
        verify(operationStore).insertQueued(captor.capture());
        assertThat(captor.getValue().compensationConfigJson()).isEqualTo("{\"shape\":\"MIXED_INSTANCES_POLICY\"}");
        assertThat(captor.getValue().targetConfigJson()).isEqualTo("{\"shape\":\"MIXED_INSTANCES_POLICY\"}");
    }

    /** D-23-07/08: a mutating ENABLE confirm for a group that is not PLANNED is 409 and enqueues nothing. */
    @Test
    void anEnableForAGroupThatIsNotPlannedIs409AndNoOp() {
        for (GroupState notPlanned : new GroupState[] {GroupState.UNMANAGED, GroupState.REVERTED}) {
            SpotGroup g = group(notPlanned);
            SpotPlan enable = plan(OperationKind.ENABLE, g.getId(), "[]", false);
            when(plans.findById(enable.getId())).thenReturn(Optional.of(enable));
            when(operations.findByPlanId(enable.getId())).thenReturn(Optional.empty());
            when(groups.findById(g.getId())).thenReturn(Optional.of(g));
            assertThatThrownBy(() -> confirm.confirm(account(), user(), enable.getId(), cmd(1, List.of(), false)))
                    .isInstanceOfSatisfying(AutoSpottingApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo("AS_GROUP_NOT_PLANNED");
                    });
        }
        verify(operationStore, never()).insertQueued(any());
        verify(plans, never()).confirmIfOpen(any(), anyInt(), anyString(), any(), anyString(), any());
    }

    /** Finding #10: the global dry-run switch only makes the op dry-run; it does not exempt a non-PLANNED ENABLE. */
    @Test
    void aNonPlannedEnableIs409EvenWhenOnlyTheGlobalDryRunSwitchIsOn() {
        when(switchProperties.mutationsDryRun()).thenReturn(true);
        SpotGroup g = group(GroupState.UNMANAGED);
        SpotPlan enable = plan(OperationKind.ENABLE, g.getId(), "[]", false);
        when(plans.findById(enable.getId())).thenReturn(Optional.of(enable));
        when(operations.findByPlanId(enable.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        assertThatThrownBy(() -> confirm.confirm(account(), user(), enable.getId(), cmd(1, List.of(), false)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("AS_GROUP_NOT_PLANNED");
                });
        verify(operationStore, never()).insertQueued(any());
    }

    /** Finding #9: a kind whose group state has no edge to the op's start state is refused at confirm. */
    @Test
    void aRevertForAGroupWithNoEdgeToRevertingIs409AndNoOp() {
        // PlanKinds lists REVERT as allowed from PLANNED, but PLANNED has no edge to REVERTING.
        SpotGroup g = group(GroupState.PLANNED);
        SpotPlan revert = plan(OperationKind.REVERT, g.getId(), "[]", false);
        when(plans.findById(revert.getId())).thenReturn(Optional.of(revert));
        when(operations.findByPlanId(revert.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        assertThatThrownBy(() -> confirm.confirm(account(), user(), revert.getId(), cmd(1, List.of(), false)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(operationStore, never()).insertQueued(any());
    }

    /** A dry-run ENABLE plan never moves the group to PLANNED (§7.2), so its confirm is not refused. */
    @Test
    void aDryRunEnableForAnUnmanagedGroupIsStillAccepted() {
        SpotGroup g = group(GroupState.UNMANAGED);
        SpotPlan enable = plan(OperationKind.ENABLE, g.getId(), "[]", false);
        enable.setDryRun(true);
        when(plans.findById(enable.getId())).thenReturn(Optional.of(enable));
        when(operations.findByPlanId(enable.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        when(plans.confirmIfOpen(eq(enable.getId()), eq(1), anyString(), any(), anyString(), any())).thenReturn(1);
        assertThat(confirm.confirm(account(), user(), enable.getId(), cmd(1, List.of(), false)).status())
                .isEqualTo("QUEUED");
    }

    @Test
    void theFullSpotOptInIsRecordedWithTheAcknowledgements() {
        SpotGroup g = group(GroupState.PLANNED);
        SpotPlan enable = plan(OperationKind.ENABLE, g.getId(), "[]", true);
        when(plans.findById(enable.getId())).thenReturn(Optional.of(enable));
        when(operations.findByPlanId(enable.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        when(plans.confirmIfOpen(eq(enable.getId()), eq(1), anyString(), any(), anyString(), any())).thenReturn(1);

        confirm.confirm(account(), user(), enable.getId(), cmd(1, List.of(), true));

        org.mockito.ArgumentCaptor<String> acks = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(plans).confirmIfOpen(eq(enable.getId()), eq(1), anyString(), any(), acks.capture(), any());
        assertThat(acks.getValue()).contains(ConfirmService.FULL_SPOT_OPTIN);
    }

    @Test
    void gr03AGcpRevertPlanThatNeedsAPermissionIs422RetryableAndNoOp_otherRevertsAreNotRefused() {
        CloudAccount gcp = account();
        gcp.setProvider("GCP");
        SpotGroup g = group(GroupState.ACTIVE);
        g.setProvider(Provider.GCP);
        SpotPlan p = plan(OperationKind.REVERT, g.getId(), "[]", false);
        p.setBody("{\"eligibility\":{\"blockers\":[{\"code\":\"REVERT_NEEDS_PERMISSION\",\"text\":\"x\"}]}}");
        when(plans.findById(p.getId())).thenReturn(Optional.of(p));
        when(operations.findByPlanId(p.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        assertThatThrownBy(() -> confirm.confirm(gcp, user(), p.getId(), cmd(1, List.of(), false)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("REVERT_NEEDS_PERMISSION");
                    assertThat(ex.getMessage()).isEqualTo("x");
                    assertThat(ex.isRetryable()).isTrue();
                });
        verify(plans).markStaleIfOpen(p.getId());
        verify(operationStore, never()).insertQueued(any());
        verify(plans, never()).confirmIfOpen(any(), anyInt(), anyString(), any(), anyString(), any());
        // The same blocker on an AWS plan is not a GCP refusal (AWS has its own drain-only path), and a GCP revert
        // plan without it confirms.
        SpotGroup ok = group(GroupState.ACTIVE);
        ok.setProvider(Provider.GCP);
        SpotPlan clean = plan(OperationKind.REVERT, ok.getId(), "[]", false);
        clean.setBody("{\"eligibility\":{\"blockers\":[]}}");
        when(plans.findById(clean.getId())).thenReturn(Optional.of(clean));
        when(operations.findByPlanId(clean.getId())).thenReturn(Optional.empty());
        when(groups.findById(ok.getId())).thenReturn(Optional.of(ok));
        when(plans.confirmIfOpen(eq(clean.getId()), eq(1), anyString(), any(), anyString(), any())).thenReturn(1);
        assertThat(confirm.confirm(gcp, user(), clean.getId(), cmd(1, List.of(), false)).status()).isEqualTo("QUEUED");
    }
    @Test
    void aBlockedRevertAnswersItsBlockerBeforeTheAcknowledgementAndTheRefusalCommits() throws Exception {
        SpotGroup g = group(GroupState.FAILED);
        SpotPlan p = plan(OperationKind.REVERT, g.getId(), "[\"REVERT_FROM_FAILED\"]", false);
        p.setBody("{\"eligibility\":{\"blockers\":[{\"code\":\"REVERT_NEEDS_PASSROLE\",\"text\":\"Grant it.\"}]}}");
        when(plans.findById(p.getId())).thenReturn(Optional.of(p));
        when(operations.findByPlanId(p.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        assertThatThrownBy(() -> confirm.confirm(account(), user(), p.getId(), cmd(1, List.of(), false)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("REVERT_NEEDS_PASSROLE");
                    assertThat(ex.getMessage()).isEqualTo("Grant it.");
                });
        verify(plans).markStaleIfOpen(p.getId());
        // The STALE mark must survive the refusal: the exception is exempt from the transaction's rollback.
        var tx = ConfirmService.class.getMethod("confirm", CloudAccount.class,
                com.xammer.cloud.security.ClientUserDetails.class, java.util.UUID.class,
                ConfirmService.ConfirmCommand.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx.noRollbackFor()).contains(ConfirmService.BlockedRevertException.class);
    }
    @Test
    void aRevertPlanWithAnUnreadableBodyFailsClosedWithCheckFailed() {
        SpotGroup g = group(GroupState.ACTIVE);
        SpotPlan p = plan(OperationKind.REVERT, g.getId(), "[]", false);
        p.setBody("not json");
        when(plans.findById(p.getId())).thenReturn(Optional.of(p));
        when(operations.findByPlanId(p.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        assertThatThrownBy(() -> confirm.confirm(account(), user(), p.getId(), cmd(1, List.of(), false)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("CHECK_FAILED");
                    assertThat(ex.getMessage()).contains("could not be read");
                });
        verify(operationStore, never()).insertQueued(any());
    }
    @Test
    void unreadableSwitchStoreHoldsRevert() {
        when(switchService.readUncached()).thenReturn(new SwitchState(Set.of(), Set.of(), true, false));
        SpotGroup g = group(GroupState.ACTIVE);
        SpotPlan p = plan(OperationKind.REVERT, g.getId(), "[]", false);
        when(plans.findById(p.getId())).thenReturn(Optional.of(p));
        when(operations.findByPlanId(p.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));

        assertThatThrownBy(() -> confirm.confirm(account(), user(), p.getId(), cmd(1, List.of(), false)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("AS_SWITCH_UNREADABLE");
                    assertThat(ex.isRetryable()).isTrue();
                });
        verify(operationStore, never()).insertQueued(any());
    }

    @Test
    void secondConfirmReturnsTheSameOpId() {
        SpotGroup g = group(GroupState.ACTIVE);
        SpotPlan p = plan(OperationKind.CONFIG_APPLY, g.getId(), "[]", false);
        when(plans.findById(p.getId())).thenReturn(Optional.of(p));
        SpotOperation existing = new SpotOperation();
        existing.setId(UUID.randomUUID());
        existing.setStatus(com.xammer.cloud.domain.autospotting.OperationStatus.QUEUED);
        when(operations.findByPlanId(p.getId())).thenReturn(Optional.of(existing));

        ConfirmService.ConfirmResult result = confirm.confirm(account(), user(), p.getId(), cmd(1, List.of(), false));
        assertThat(result.operationId()).isEqualTo(existing.getId());
        verify(operationStore, never()).insertQueued(any());
    }

    @Test
    void busyGroupIs409() {
        SpotGroup g = group(GroupState.ACTIVE);
        SpotPlan p = plan(OperationKind.CONFIG_APPLY, g.getId(), "[]", false);
        when(plans.findById(p.getId())).thenReturn(Optional.of(p));
        when(operations.findByPlanId(p.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        when(preemption.preemptFor(any(), any(), any()))
                .thenReturn(new PreemptionService.Preemption(PreemptionService.Verdict.BUSY, null, false));

        assertThatThrownBy(() -> confirm.confirm(account(), user(), p.getId(), cmd(1, List.of(), false)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("AS_GROUP_BUSY"));
        verify(operationStore, never()).insertQueued(any());
    }

    @Test
    void forbiddenAuthorizationIs403() {
        SpotGroup g = group(GroupState.ACTIVE);
        SpotPlan p = plan(OperationKind.CONFIG_APPLY, g.getId(), "[]", false);
        when(plans.findById(p.getId())).thenReturn(Optional.of(p));
        when(operations.findByPlanId(p.getId())).thenReturn(Optional.empty());
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        when(authorization.authorizeConfirm(any(), any(), any(), any())).thenReturn(
                new Decision(false, Role.SUB_USER, null, false, "AS_FORBIDDEN_ROLE", HttpStatus.FORBIDDEN, "no"));

        assertThatThrownBy(() -> confirm.confirm(account(), user(), p.getId(), cmd(1, List.of(), false)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(operationStore, never()).insertQueued(any());
    }
}
