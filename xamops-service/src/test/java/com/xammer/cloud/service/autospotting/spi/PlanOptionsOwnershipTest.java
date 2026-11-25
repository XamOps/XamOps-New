package com.xammer.cloud.service.autospotting.spi;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-23b-01: the ownership markers in desired_config (burstable types XamOps added, AWS's owned maintenance policy)
 * count only while XamOps' configuration is on the group. The customer's last burstable choice is remembered always.
 */
class PlanOptionsOwnershipTest {

    private static final String DESIRED = "{\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200},"
            + "\"options\":{\"allowBurstable\":true,\"burstableAdded\":[\"t3a.micro\"]}}";

    private static final Set<GroupState> MANAGED = EnumSet.of(GroupState.APPLYING, GroupState.ACTIVE,
            GroupState.DEGRADED, GroupState.FALLBACK, GroupState.PAUSED, GroupState.REVERTING, GroupState.FAILED);

    private static SpotGroup group(GroupState state) {
        SpotGroup g = new SpotGroup();
        g.setState(state);
        g.setDesiredConfig(DESIRED);
        return g;
    }

    @ParameterizedTest
    @EnumSource(GroupState.class)
    void ownershipMarkersApplyOnlyWhileTheGroupIsUnderManagement(GroupState state) {
        PlanOptions o = PlanOptions.forGroup(null, group(state));
        boolean managed = MANAGED.contains(state);
        assertThat(o.underManagement()).isEqualTo(managed);
        assertThat(o.ownershipConfigJson()).isEqualTo(managed ? DESIRED : null);
        assertThat(o.storedBurstableAdded()).isEqualTo(managed ? List.of("t3a.micro") : List.of());
        assertThat(o.storedAllowBurstable()).as("the customer's choice is remembered in every state").isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = GroupState.class, names = {"UNMANAGED", "PLANNED", "REVERTED", "LEGACY_UNCONFIRMED"})
    void aConfigStillOnTheGroupKeepsTheMarkersOutsideTheManagedStates(GroupState state) {
        SpotGroup g = group(state);
        String flagged = DESIRED.substring(0, DESIRED.length() - 1) + ",\"" + PlanOptions.CONFIG_ON_GROUP + "\":true}";
        g.setDesiredConfig(flagged);
        PlanOptions o = PlanOptions.forGroup(null, g);
        assertThat(o.underManagement()).isTrue();
        assertThat(o.storedBurstableAdded()).containsExactly("t3a.micro");
        assertThat(PlanOptions.ownershipConfigJson(g)).isEqualTo(flagged);
    }

    @Test
    void aGoneGroupNeverClaimsEvenWithTheFlag() {
        SpotGroup g = group(GroupState.GONE);
        g.setDesiredConfig("{\"" + PlanOptions.CONFIG_ON_GROUP + "\":true}");
        assertThat(PlanOptions.forGroup(null, g).underManagement()).isFalse();
    }

    @Test
    void theStaticGateMatchesTheOptions() {
        assertThat(PlanOptions.ownershipConfigJson((SpotGroup) null)).isNull();
        assertThat(PlanOptions.ownershipConfigJson(group(GroupState.ACTIVE))).isEqualTo(DESIRED);
        assertThat(PlanOptions.ownershipConfigJson(group(GroupState.REVERTED))).isNull();
    }

    @Test
    void aGroupWithNoRowHasNoStoredConfig() {
        PlanOptions o = PlanOptions.forGroup(true, null);
        assertThat(o.ownershipConfigJson()).isNull();
        assertThat(o.storedBurstableAdded()).isEmpty();
        assertThat(o.effectiveAllowBurstable()).isTrue();
    }
}
