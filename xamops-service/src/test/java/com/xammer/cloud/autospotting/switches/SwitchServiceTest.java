package com.xammer.cloud.autospotting.switches;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.repository.autospotting.AutoSpottingAllowlistEntryRepository;
import com.xammer.cloud.repository.autospotting.AutoSpottingSwitchRepository;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.autospotting.core.switches.ActionGate;
import com.xammer.cloud.service.autospotting.core.switches.ActionGate.ActionKind;
import com.xammer.cloud.service.autospotting.core.switches.ActionGate.Code;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SwitchService} and the {@code property AND NOT db.engaged} rule through
 * {@link ActionGate} (plan-phase-05.md task 5.1, AC-SPN-05 unit part, design-core.md §6.1). The
 * cross-instance 60 s propagation (AC-SPN-05 integration part) is a documented manual/integration
 * check; here the cache is driven with a 0 s refresh so a DB engage is seen on the next read.
 */
class SwitchServiceTest {

    private AutoSpottingSwitchRepository switches;
    private AutoSpottingAllowlistEntryRepository allowlist;

    @BeforeEach
    void setUp() {
        switches = mock(AutoSpottingSwitchRepository.class);
        allowlist = mock(AutoSpottingAllowlistEntryRepository.class);
        when(allowlist.findAllowedCloudAccountIds()).thenReturn(List.of());
    }

    private SwitchService service(long refreshSeconds) {
        return new SwitchService(switches, allowlist, refreshSeconds);
    }

    // ── a DB engage reaches the effective value within one refresh (AC-SPN-05 unit part) ────

    @Test
    void dbEngageIsSeenOnTheNextRefresh() {
        // refresh=0 so current() always re-reads: models "within one 15 s refresh".
        SwitchService service = service(0);
        when(switches.findEngagedScopeKeys()).thenReturn(List.of());
        assertThat(service.current().globalEngaged()).isFalse();

        when(switches.findEngagedScopeKeys()).thenReturn(List.of(SwitchState.GLOBAL));
        assertThat(service.current().globalEngaged()).isTrue();
    }

    @Test
    void cacheServesTheSameSnapshotWithinTheRefreshWindow() {
        // A long refresh: the second current() must serve the cached (not-engaged) snapshot even
        // though the store would now return GLOBAL.
        SwitchService service = service(3600);
        when(switches.findEngagedScopeKeys()).thenReturn(List.of());
        assertThat(service.current().globalEngaged()).isFalse();

        when(switches.findEngagedScopeKeys()).thenReturn(List.of(SwitchState.GLOBAL));
        assertThat(service.current().globalEngaged()).as("cached within the window").isFalse();
        // readUncached() bypasses the cache (GR-06).
        assertThat(service.readUncached().globalEngaged()).isTrue();
    }

    // ── fail closed per store ───────────────────────────────────────────────────────────────

    @Test
    void unreadableSwitchStoreFailsClosed() {
        SwitchService service = service(0);
        when(switches.findEngagedScopeKeys()).thenThrow(new DataAccessResourceFailureException("down"));
        SwitchState state = service.current();
        assertThat(state.switchStoreUnreadable()).isTrue();
        assertThat(state.globalEngaged()).isTrue();
        assertThat(state.cloudEngaged(true)).isTrue();
        assertThat(state.freezeEngaged()).isTrue();
        assertThat(state.allowlistUnreadable()).isFalse();
    }

    @Test
    void unreadableAllowlistFailsClosedToEmpty() {
        SwitchService service = service(0);
        when(switches.findEngagedScopeKeys()).thenReturn(List.of());
        when(allowlist.findAllowedCloudAccountIds()).thenThrow(new DataAccessResourceFailureException("down"));
        SwitchState state = service.current();
        assertThat(state.allowlistUnreadable()).isTrue();
        assertThat(state.allowlisted(1L)).isFalse();
        assertThat(state.switchStoreUnreadable()).isFalse();
    }

    // ── DB release can't exceed a false property (property AND NOT db) ──────────────────────

    @Test
    void dbCannotTurnOnWhatThePropertyTurnedOff() {
        // Global property off, DB empty (nothing engaged). The DB can only tighten, so the effective
        // value stays off: a confirm ENABLE is AS_DISABLED even though no DB row engages GLOBAL.
        AutoSpottingSwitchProperties props = new AutoSpottingSwitchProperties(
                true, true, false, false, true, true, false, false, false, false);
        AutoSpottingFeatureFlags flags = new AutoSpottingFeatureFlags(true, true, false, false);
        ActionGate gate = new ActionGate(props, flags, /* autospotting.enabled */ false);

        CloudAccount account = new CloudAccount();
        account.setId(123L);
        SwitchState empty = new SwitchState(java.util.Set.of(), java.util.Set.of(123L), false, false);

        ActionGate.Decision d = gate.check(ActionKind.ENABLE, account, Provider.AWS, empty);
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.code()).isEqualTo(Code.AS_DISABLED);
    }
}
