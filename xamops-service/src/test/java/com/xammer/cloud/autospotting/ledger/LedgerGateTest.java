package com.xammer.cloud.autospotting.ledger;

import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import com.xammer.cloud.service.autospotting.ledger.LedgerGate;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Switch L governs the ledger jobs: property AND NOT db.engaged, failing closed (design-core.md §6.1). */
class LedgerGateTest {
    private final AutoSpottingSwitchProperties props = mock(AutoSpottingSwitchProperties.class);
    private final SwitchService switches = mock(SwitchService.class);
    private final LedgerGate gate = new LedgerGate(props, switches);

    private static SwitchState state(boolean unreadable, String... engaged) {
        return new SwitchState(Set.of(engaged), Set.of(), unreadable, false);
    }

    @Test
    void offWhenThePropertyIsOff_whatever_theDbSays() {
        when(props.ledgerWriterEnabled(true)).thenReturn(false);
        when(switches.current()).thenReturn(state(false));
        assertThat(gate.awsWriterOn()).isFalse();
    }

    @Test
    void onWhenPropertyOnAndNothingEngaged_evenWithFreezeOrPausedConversions() {
        when(props.ledgerWriterEnabled(true)).thenReturn(true);
        when(switches.current()).thenReturn(state(false, "FREEZE", "CONVERSIONS:aws", "GLOBAL", "CLOUD:aws"));
        assertThat(gate.awsWriterOn()).isTrue(); // never governed by switches (a) to (e)
    }

    @Test
    void offWhenTheLedgerSwitchIsEngaged() {
        when(props.ledgerWriterEnabled(true)).thenReturn(true);
        when(switches.current()).thenReturn(state(false, "LEDGER:aws"));
        assertThat(gate.awsWriterOn()).isFalse();
    }

    @Test
    void offWhenTheSwitchStoreIsUnreadable() {
        when(props.ledgerWriterEnabled(true)).thenReturn(true);
        when(switches.current()).thenReturn(state(true));
        assertThat(gate.awsWriterOn()).isFalse();
    }
}
