package com.xammer.cloud.autospotting.plan;

import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.plan.RevertPreconditions;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The shared revert preconditions (design-core.md §7.4, PD-07; plan-phase-11.md task 11.2 verify).
 * Only the freeze (e) and an unreadable switch store hold a revert; a clean store passes; it never
 * checks eligibility/profile/size.
 */
class RevertPreconditionsTest {

    private final AutoSpottingSwitchProperties props = mock(AutoSpottingSwitchProperties.class);
    private final RevertPreconditions preconditions = new RevertPreconditions(props);

    private static SwitchState state(boolean switchUnreadable, Set<String> engaged) {
        return new SwitchState(engaged, Set.of(), switchUnreadable, false);
    }

    @Test
    void cleanStorePasses() {
        preconditions.checkSwitches(state(false, Set.of())); // no throw
    }

    @Test
    void unreadableSwitchStoreHoldsRevertWith503Retryable() {
        assertThatThrownBy(() -> preconditions.checkSwitches(state(true, Set.of())))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("AS_SWITCH_UNREADABLE");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.isRetryable()).isTrue();
                });
    }

    @Test
    void freezeEngagedHoldsRevertWithAsFrozen() {
        assertThatThrownBy(() -> preconditions.checkSwitches(state(false, Set.of(SwitchState.FREEZE))))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("AS_FROZEN");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void v2RestoreLiveReflectsTheCloudMutationFlag() {
        when(props.v2MutationsEnabled(true)).thenReturn(false);
        when(props.v2MutationsEnabled(false)).thenReturn(true);
        assertThat(preconditions.v2RestoreLive(Provider.AWS)).isFalse();
        assertThat(preconditions.v2RestoreLive(Provider.GCP)).isTrue();
    }
}
