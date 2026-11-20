package com.xammer.cloud.autospotting.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.PauseReason;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.NotificationStore;
import com.xammer.cloud.repository.autospotting.NotificationStore.NewNotification;
import com.xammer.cloud.repository.autospotting.NotificationStore.OperationFacts;
import com.xammer.cloud.repository.autospotting.NotificationStore.PlanConfirmation;
import com.xammer.cloud.service.autospotting.core.audit.AuditRedactor;
import com.xammer.cloud.service.autospotting.notify.AutoSpottingNotificationService;
import com.xammer.cloud.service.autospotting.notify.NotificationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which transition becomes which customer notice, with which dedupe key and mandatory flag (design-ux-ops.md
 * section 7.2, PO-24). The store is a mock: this pins the mapping, the redaction and the payload content.
 */
class AutoSpottingNotificationServiceTest {

    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final UUID OP = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();

    private NotificationStore store;
    private AutoSpottingNotificationService service;
    private SpotGroup group;

    @BeforeEach
    void setUp() {
        store = mock(NotificationStore.class);
        when(store.insert(any())).thenReturn(true);
        when(store.accountName(5L)).thenReturn(Optional.of("Prod AWS"));
        when(store.latestTransitionId(GROUP_ID)).thenReturn(77L);
        service = new AutoSpottingNotificationService(store, new AuditRedactor(JSON));
        group = new SpotGroup();
        group.setId(GROUP_ID);
        group.setClientId(9L);
        group.setCloudAccountId(5L);
        group.setProvider(Provider.AWS);
        group.setGroupKey("us-east-1/web-asg");
    }

    private List<NewNotification> written() {
        ArgumentCaptor<NewNotification> captor = ArgumentCaptor.forClass(NewNotification.class);
        verify(store, org.mockito.Mockito.atLeast(0)).insert(captor.capture());
        return captor.getAllValues();
    }

    private void operation(String kind, String errorCode, UUID plan) {
        when(store.operationFacts(OP)).thenReturn(Optional.of(new OperationFacts(kind, errorCode, plan)));
    }

    @Test
    void fallbackAppliedIsFallbackEnteredKeyedOnTheOperation() {
        service.onTransition(group, GroupState.ACTIVE, GroupState.FALLBACK, "FALLBACK_APPLIED", OP, null, false);
        assertThat(written()).hasSize(1);
        NewNotification n = written().get(0);
        assertThat(n.eventType()).isEqualTo("FALLBACK_ENTERED");
        assertThat(n.dedupeKey()).isEqualTo("fb_in:" + OP);
        assertThat(n.mandatory()).isFalse();
        assertThat(n.clientId()).isEqualTo(9L);
        assertThat(n.cloudAccountId()).isEqualTo(5L);
        assertThat(n.groupId()).isEqualTo(GROUP_ID);
    }

    @Test
    void aFallbackThatKeptAPreemptedConfigChangeAlsoWritesTheMandatoryDeferredNotice() {
        service.onTransition(group, GroupState.ACTIVE, GroupState.FALLBACK, "FALLBACK_APPLIED", OP, null, true);
        assertThat(written()).extracting(NewNotification::eventType)
                .containsExactly("FALLBACK_ENTERED", "CONFIG_DEFERRED_BY_FALLBACK");
        assertThat(written().get(1).mandatory()).isTrue();
        assertThat(written().get(1).dedupeKey()).isEqualTo("cfg_deferred:" + OP);
    }

    @Test
    void fallbackExitIsFallbackExited() {
        service.onTransition(group, GroupState.FALLBACK, GroupState.ACTIVE, "OP_FALLBACK_EXIT_VERIFIED", OP, null,
                false);
        assertThat(written().get(0).eventType()).isEqualTo("FALLBACK_EXITED");
        assertThat(written().get(0).dedupeKey()).isEqualTo("fb_out:" + OP);
    }

    @Test
    void enableAdoptAndConfigApplyAreAppliedNotices() {
        operation("ENABLE", null, PLAN);
        service.onTransition(group, GroupState.APPLYING, GroupState.ACTIVE, "OP_ENABLE_VERIFIED", OP, null, true);
        service.onTransition(group, GroupState.APPLYING, GroupState.LEGACY_UNCONFIRMED, "OP_ADOPT_VERIFIED", UUID.randomUUID(),
                null, false);
        assertThat(written()).extracting(NewNotification::eventType).containsExactly("PLAN_APPLIED", "PLAN_APPLIED");
        assertThat(written().get(0).dedupeKey()).isEqualTo("applied:" + OP);
        assertThat(written().get(0).mandatory()).isFalse();
    }

    @Test
    void anOperationThatEndedCompensatedIsAMandatoryApplyFailureWithTheErrorCodeOnly() throws Exception {
        operation("CONFIG_APPLY", "CAP ACITY!\n<script>", null);
        service.onTransition(group, GroupState.REVERTING, GroupState.ACTIVE, "COMPENSATED_PRIOR_CONFIG", OP, null,
                false);
        NewNotification n = written().get(0);
        assertThat(n.eventType()).isEqualTo("APPLY_FAILED");
        assertThat(n.mandatory()).isTrue();
        assertThat(n.dedupeKey()).isEqualTo("apply_failed:" + OP);
        JsonNode payload = JSON.readTree(n.payloadJson());
        assertThat(payload.path("outcome").asText()).isEqualTo("RESTORED");
        assertThat(payload.path("errorCode").asText()).isEqualTo("CAPACITYscript"); // a code, never raw text
        assertThat(payload.path("groupKey").asText()).isEqualTo("us-east-1/web-asg");
        assertThat(payload.path("accountName").asText()).isEqualTo("Prod AWS");
    }

    @Test
    void aFailedCompensationOfAnEnableIsAnApplyFailure_ofARevertIsARevertFailure() {
        operation("ENABLE", "X", null);
        service.onTransition(group, GroupState.REVERTING, GroupState.FAILED, "COMPENSATION_FAILED", OP, null, false);
        assertThat(written().get(0).eventType()).isEqualTo("APPLY_FAILED");

        NotificationStore store2 = mock(NotificationStore.class);
        when(store2.insert(any())).thenReturn(true);
        when(store2.operationFacts(OP)).thenReturn(Optional.of(new OperationFacts("REVERT", "X", null)));
        new AutoSpottingNotificationService(store2, new AuditRedactor(JSON)).onTransition(group, GroupState.REVERTING,
                GroupState.FAILED, "COMPENSATION_FAILED", OP, null, false);
        ArgumentCaptor<NewNotification> captor = ArgumentCaptor.forClass(NewNotification.class);
        verify(store2).insert(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("REVERT_FAILED");
        assertThat(captor.getValue().mandatory()).isTrue();
        assertThat(captor.getValue().dedupeKey()).isEqualTo("revert_failed:" + OP);
    }

    @Test
    void revertStartedAndCompletedAreOptionalAndNameTheAdminWhenAReasonWasGiven() throws Exception {
        operation("REVERT", null, PLAN);
        when(store.planConfirmation(PLAN)).thenReturn(Optional.of(new PlanConfirmation("admin@xamops.test", "incident 12")));
        service.onTransition(group, GroupState.ACTIVE, GroupState.REVERTING, "REVERT_STARTED", OP, null, false);
        service.onTransition(group, GroupState.REVERTING, GroupState.REVERTED, "OP_REVERT_VERIFIED", OP, null, false);
        assertThat(written()).extracting(NewNotification::eventType).containsExactly("REVERT_STARTED", "REVERT_COMPLETED");
        assertThat(written()).extracting(NewNotification::mandatory).containsOnly(false);
        assertThat(written()).extracting(NewNotification::dedupeKey).containsExactly("revert_started:" + OP,
                "revert_completed:" + OP);
        JsonNode payload = JSON.readTree(written().get(0).payloadJson());
        assertThat(payload.path("confirmedBy").asText()).isEqualTo("admin@xamops.test");
        assertThat(payload.path("reason").asText()).isEqualTo("incident 12");
    }

    @Test
    void anOwnersRevertWithNoReasonDoesNotNameAnyone() throws Exception {
        operation("REVERT", null, PLAN);
        when(store.planConfirmation(PLAN)).thenReturn(Optional.of(new PlanConfirmation("owner@example.test", null)));
        service.onTransition(group, GroupState.ACTIVE, GroupState.REVERTING, "REVERT_STARTED", OP, null, false);
        assertThat(JSON.readTree(written().get(0).payloadJson()).has("confirmedBy")).isFalse();
    }

    @Test
    void aDriftPauseIsAMandatoryNoticeKeyedOnTheTransition() {
        service.onTransition(group, GroupState.ACTIVE, GroupState.PAUSED, "DRIFT_DETECTED", null, PauseReason.DRIFT,
                false);
        NewNotification n = written().get(0);
        assertThat(n.eventType()).isEqualTo("DRIFT_PAUSED");
        assertThat(n.mandatory()).isTrue();
        assertThat(n.dedupeKey()).isEqualTo("drift:" + GROUP_ID + ":77");
    }

    @Test
    void aFreezePauseAndOtherTransitionsWriteNothing() {
        service.onTransition(group, GroupState.ACTIVE, GroupState.PAUSED, "FREEZE", null, PauseReason.FREEZE, false);
        service.onTransition(group, GroupState.ACTIVE, GroupState.DEGRADED, "HEALTH_DEGRADED", null, null, false);
        service.onTransition(group, GroupState.PLANNED, GroupState.UNMANAGED, "PLAN_EXPIRED", null, null, false);
        service.onTransition(group, GroupState.ACTIVE, GroupState.REVERTING, "ENABLE_COMPENSATING", OP, null, false);
        verify(store, never()).insert(any());
    }

    @Test
    void aGroupWithNoClientWritesNothing() {
        group.setClientId(null);
        service.onTransition(group, GroupState.ACTIVE, GroupState.FALLBACK, "FALLBACK_APPLIED", OP, null, false);
        verify(store, never()).insert(any());
    }

    @Test
    void adminEnabledNamesTheAdminAndIsMandatory() throws Exception {
        service.recordAdminEnabled(group, 5L, null, PLAN, "admin@xamops.test", "PLATFORM_ADMIN", 3L, "ticket 42");
        NewNotification n = written().get(0);
        assertThat(n.eventType()).isEqualTo(NotificationEvent.ADMIN_ENABLED.name());
        assertThat(n.mandatory()).isTrue();
        assertThat(n.dedupeKey()).isEqualTo("admin_enable:" + PLAN);
        JsonNode payload = JSON.readTree(n.payloadJson());
        assertThat(payload.path("confirmedBy").asText()).isEqualTo("admin@xamops.test");
        assertThat(payload.path("reason").asText()).isEqualTo("ticket 42");
        assertThat(payload.path("impersonatorUserId").asLong()).isEqualTo(3L);
    }

    @Test
    void secretsInAFreeTextReasonAreMaskedBeforeStorage() throws Exception {
        service.recordAdminEnabled(group, 5L, null, PLAN, "admin@xamops.test", "PLATFORM_ADMIN", null,
                "key AKIAABCDEFGHIJKLMNOP");
        assertThat(written().get(0).payloadJson()).doesNotContain("AKIAABCDEFGHIJKLMNOP").contains("REDACTED");
    }

    @Test
    void theMandatoryFlagsFollowTheDesign() {
        assertThat(List.of(NotificationEvent.values())).filteredOn(NotificationEvent::mandatory)
                .containsExactlyInAnyOrder(NotificationEvent.APPLY_FAILED, NotificationEvent.CONFIG_DEFERRED_BY_FALLBACK,
                        NotificationEvent.DEGRADED_LONG, NotificationEvent.REVERT_FAILED, NotificationEvent.DRIFT_PAUSED,
                        NotificationEvent.ADMIN_ENABLED);
    }
}
