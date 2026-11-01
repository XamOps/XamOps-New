package com.xammer.cloud.autospotting.plan;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.service.autospotting.core.plan.PlanKinds;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan-kind-by-state table of design-core.md §7.4 (plan-phase-11.md task 11.2 verify): each kind
 * is allowed in its states and one disallowed state is refused. REVERT's NOT_MANAGED/AS_GROUP_BUSY
 * exceptions are resolved by {@code PlanService}, not this table, so REVERT is "allowed" here for the
 * revertable states and simply absent for the rest.
 */
class PlanKindsTest {

    @Test
    void enableIsAllowedFromUnmanagedRevertedPlannedAndNoRow_notActive() {
        assertThat(PlanKinds.isAllowed(OperationKind.ENABLE, null)).isTrue(); // no row
        assertThat(PlanKinds.isAllowed(OperationKind.ENABLE, GroupState.UNMANAGED)).isTrue();
        assertThat(PlanKinds.isAllowed(OperationKind.ENABLE, GroupState.REVERTED)).isTrue();
        assertThat(PlanKinds.isAllowed(OperationKind.ENABLE, GroupState.PLANNED)).isTrue(); // superseding
        assertThat(PlanKinds.isAllowed(OperationKind.ENABLE, GroupState.ACTIVE)).isFalse();
    }

    @Test
    void adoptOnlyFromLegacyUnconfirmed() {
        assertThat(PlanKinds.isAllowed(OperationKind.ADOPT, GroupState.LEGACY_UNCONFIRMED)).isTrue();
        assertThat(PlanKinds.isAllowed(OperationKind.ADOPT, GroupState.ACTIVE)).isFalse();
        assertThat(PlanKinds.isAllowed(OperationKind.ADOPT, null)).isFalse();
    }

    @Test
    void configApplyFromActiveAndPausedDrift_notReverted() {
        assertThat(PlanKinds.isAllowed(OperationKind.CONFIG_APPLY, GroupState.ACTIVE)).isTrue();
        assertThat(PlanKinds.isAllowed(OperationKind.CONFIG_APPLY, GroupState.PAUSED)).isTrue();
        assertThat(PlanKinds.isAllowed(OperationKind.CONFIG_APPLY, GroupState.REVERTED)).isFalse();
    }

    @Test
    void profileChangeOnlyFromActive() {
        assertThat(PlanKinds.isAllowed(OperationKind.PROFILE_CHANGE, GroupState.ACTIVE)).isTrue();
        assertThat(PlanKinds.isAllowed(OperationKind.PROFILE_CHANGE, GroupState.PAUSED)).isFalse();
    }

    @Test
    void acceptBaselineOnlyFromPaused() {
        assertThat(PlanKinds.isAllowed(OperationKind.ACCEPT_BASELINE, GroupState.PAUSED)).isTrue();
        assertThat(PlanKinds.isAllowed(OperationKind.ACCEPT_BASELINE, GroupState.ACTIVE)).isFalse();
    }

    @Test
    void migPolicyUpdateFromNoRowUnmanagedRevertedLegacy_notActive() {
        assertThat(PlanKinds.isAllowed(OperationKind.MIG_POLICY_UPDATE, null)).isTrue();
        assertThat(PlanKinds.isAllowed(OperationKind.MIG_POLICY_UPDATE, GroupState.UNMANAGED)).isTrue();
        assertThat(PlanKinds.isAllowed(OperationKind.MIG_POLICY_UPDATE, GroupState.REVERTED)).isTrue();
        assertThat(PlanKinds.isAllowed(OperationKind.MIG_POLICY_UPDATE, GroupState.LEGACY_UNCONFIRMED)).isTrue();
        assertThat(PlanKinds.isAllowed(OperationKind.MIG_POLICY_UPDATE, GroupState.ACTIVE)).isFalse();
    }

    @Test
    void revertAllowedFromEveryRevertableState_notFromUnmanagedOrReverted() {
        for (GroupState s : Set.of(GroupState.ACTIVE, GroupState.DEGRADED, GroupState.FALLBACK,
                GroupState.PAUSED, GroupState.APPLYING, GroupState.LEGACY_UNCONFIRMED, GroupState.PLANNED,
                GroupState.FAILED)) {
            assertThat(PlanKinds.isAllowed(OperationKind.REVERT, s)).as("REVERT from %s", s).isTrue();
        }
        assertThat(PlanKinds.isAllowed(OperationKind.REVERT, GroupState.UNMANAGED)).isFalse();
        assertThat(PlanKinds.isAllowed(OperationKind.REVERT, GroupState.REVERTED)).isFalse();
    }

    @Test
    void systemKindsAreNotPlanKinds() {
        assertThat(PlanKinds.isPlanKind(OperationKind.FALLBACK_ENTER)).isFalse();
        assertThat(PlanKinds.isPlanKind(OperationKind.FALLBACK_EXIT)).isFalse();
        assertThat(PlanKinds.isPlanKind(OperationKind.ENABLE)).isTrue();
    }

    @Test
    void notAllowedDetailNamesStateAndAllowedKinds() {
        String detail = PlanKinds.notAllowedDetail(OperationKind.CONFIG_APPLY, GroupState.FALLBACK);
        assertThat(detail).contains("CONFIG_APPLY").contains("FALLBACK");
        // FALLBACK allows only REVERT among plan kinds.
        assertThat(PlanKinds.kindsAllowedIn(GroupState.FALLBACK)).containsExactly(OperationKind.REVERT);
    }
}
