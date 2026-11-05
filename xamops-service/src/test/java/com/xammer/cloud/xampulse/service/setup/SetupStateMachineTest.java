package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.XpAccountState;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.xammer.cloud.xampulse.dto.XpAccountState.*;
import static org.junit.jupiter.api.Assertions.*;

class SetupStateMachineTest {

    @Test
    void theHappyPathIsPlannedDeployingVerifyingConnected() {
        XpAccountState s = SetupStateMachine.move(NOT_SET_UP, PLANNED);
        s = SetupStateMachine.move(s, DEPLOYING);
        s = SetupStateMachine.move(s, VERIFYING);
        s = SetupStateMachine.move(s, CONNECTED);
        assertEquals(CONNECTED, s);
    }

    @Test
    void illegalMovesAreConflicts() {
        assertThrows(XamPulseException.class, () -> SetupStateMachine.move(NOT_SET_UP, CONNECTED));
        assertThrows(XamPulseException.class, () -> SetupStateMachine.move(PLANNED, CONNECTED));
        assertThrows(XamPulseException.class, () -> SetupStateMachine.move(DISCONNECTED, CONNECTED));
        assertThrows(XamPulseException.class, () -> SetupStateMachine.move(CONNECTED, NOT_SET_UP));
        assertFalse(SetupStateMachine.canMove(CONNECTED, PLANNED));
    }

    @Test
    void anAccountCanAlwaysBeDisconnectedAndThenPlannedAgain() {
        for (XpAccountState s : List.of(PLANNED, DEPLOYING, VERIFYING, CONNECTED, DEGRADED)) {
            assertTrue(SetupStateMachine.canMove(s, DISCONNECTED), s.name());
        }
        assertTrue(SetupStateMachine.canMove(DISCONNECTED, PLANNED));
    }

    @Test
    void unknownStoredStatesReadAsNotSetUp() {
        assertEquals(NOT_SET_UP, SetupStateMachine.parse(null));
        assertEquals(NOT_SET_UP, SetupStateMachine.parse("nope"));
        assertEquals(CONNECTED, SetupStateMachine.parse("CONNECTED"));
    }

    // ---- the watcher's pure rule ----

    private static XpAgent agent(String state, Instant lastSeen) {
        XpAgent a = new XpAgent();
        a.setState(state);
        a.setLastSeenAt(lastSeen);
        return a;
    }

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void healthyGlobalStackMovesPlannedAndDeployingToVerifying() {
        assertEquals(VERIFYING, XamPulseStackWatcher.nextState(PLANNED, true, false, List.of(), NOW, true));
        assertEquals(VERIFYING, XamPulseStackWatcher.nextState(DEPLOYING, true, false, List.of(), NOW, true));
    }

    @Test
    void verifyingBecomesConnectedOnlyWithARecentHeartbeat() {
        assertEquals(VERIFYING, XamPulseStackWatcher.nextState(VERIFYING, true, false, List.of(), NOW, true));
        assertEquals(VERIFYING, XamPulseStackWatcher.nextState(VERIFYING, true, false, List.of(agent("ACTIVE", NOW.minusSeconds(3600))), NOW, true));
        assertEquals(CONNECTED, XamPulseStackWatcher.nextState(VERIFYING, true, false, List.of(agent("ACTIVE", NOW.minusSeconds(10))), NOW, true));
        assertEquals(VERIFYING, XamPulseStackWatcher.nextState(VERIFYING, true, false, List.of(agent("REVOKED", NOW)), NOW, true));
    }

    @Test
    void connectedDegradesWhenEveryAgentGoesSilentAndRecovers() {
        assertEquals(DEGRADED, XamPulseStackWatcher.nextState(CONNECTED, true, false, List.of(agent("ACTIVE", NOW.minusSeconds(1800))), NOW, true));
        assertEquals(CONNECTED, XamPulseStackWatcher.nextState(DEGRADED, true, false, List.of(agent("ACTIVE", NOW.minusSeconds(30))), NOW, true));
        assertEquals(CONNECTED, XamPulseStackWatcher.nextState(CONNECTED, true, false, List.of(agent("ACTIVE", NOW.minusSeconds(30))), NOW, true));
    }

    @Test
    void aMissingOrFailedGlobalStackDegradesAConnectedAccountButAnUpdateDoesNot() {
        assertEquals(DEGRADED, XamPulseStackWatcher.nextState(CONNECTED, false, false, List.of(agent("ACTIVE", NOW)), NOW, true));
        assertEquals(CONNECTED, XamPulseStackWatcher.nextState(CONNECTED, false, true, List.of(agent("ACTIVE", NOW)), NOW, true));
        assertEquals(DEPLOYING, XamPulseStackWatcher.nextState(PLANNED, false, true, List.of(), NOW, true));
        assertEquals(PLANNED, XamPulseStackWatcher.nextState(PLANNED, false, false, List.of(), NOW, true));
    }

    @Test
    void disconnectedAndNotSetUpNeverMove() {
        assertEquals(DISCONNECTED, XamPulseStackWatcher.nextState(DISCONNECTED, true, false, List.of(agent("ACTIVE", NOW)), NOW, true));
        assertEquals(NOT_SET_UP, XamPulseStackWatcher.nextState(NOT_SET_UP, true, false, List.of(agent("ACTIVE", NOW)), NOW, true));
    }

    @Test
    void everyTransitionTheWatcherCanProduceIsLegal() {
        for (XpAccountState from : XpAccountState.values()) {
            for (boolean ok : new boolean[] {true, false}) {
                for (boolean deploying : new boolean[] {true, false}) {
                    for (List<XpAgent> agents : List.of(List.<XpAgent>of(), List.of(agent("ACTIVE", NOW)))) {
                        XpAccountState to = XamPulseStackWatcher.nextState(from, ok, deploying, agents, NOW, true);
                        // The watcher only moves when the state changes, so staying put needs no edge.
                        assertTrue(to == from || SetupStateMachine.canMove(from, to), from + " -> " + to);
                    }
                }
            }
        }
    }

    @Test
    void stackStatusesMapToRowStates() {
        assertEquals("OK", StackReader.rowState("CREATE_COMPLETE"));
        assertEquals("OK", StackReader.rowState("UPDATE_COMPLETE"));
        assertEquals("OK", StackReader.rowState("UPDATE_ROLLBACK_COMPLETE"));
        assertEquals("DEPLOYING", StackReader.rowState("CREATE_IN_PROGRESS"));
        assertEquals("DEPLOYING", StackReader.rowState("UPDATE_IN_PROGRESS"));
        assertEquals("FAILED", StackReader.rowState("ROLLBACK_COMPLETE"));
        assertEquals("FAILED", StackReader.rowState("ROLLBACK_IN_PROGRESS"));
        assertEquals("FAILED", StackReader.rowState("CREATE_FAILED"));
        assertEquals("FAILED", StackReader.rowState("DELETE_IN_PROGRESS"));
        assertEquals("NONE", StackReader.rowState("DELETE_COMPLETE"));
        assertEquals("NONE", StackReader.rowState(null));
    }

    @Test
    void connectedNeedsLandedDataNotJustAHeartbeat() {
        List<XpAgent> a = List.of(agent("ACTIVE", NOW.minusSeconds(10)));
        assertEquals(VERIFYING, XamPulseStackWatcher.nextState(VERIFYING, true, false, a, NOW, false));
        assertEquals(DEGRADED, XamPulseStackWatcher.nextState(DEGRADED, true, false, a, NOW, false));
        assertEquals(CONNECTED, XamPulseStackWatcher.nextState(CONNECTED, true, false, a, NOW, false), "an already connected account is not demoted for missing a probe");
    }

    @Test
    void oneHealthyRegionDoesNotMaskARegionThatShipsNothing() {
        XpAgent a = agent("ACTIVE", NOW.minusSeconds(10));
        a.setRegion("ap-south-1");
        a.setInstanceId("i-0000000000000001");
        XpAgent b = agent("ACTIVE", NOW.minusSeconds(10));
        b.setRegion("eu-west-1");
        b.setInstanceId("i-0000000000000002");
        List<XpAgent> both = List.of(a, b);
        var onlyOne = new TelemetryProbe.Landed(java.util.Set.of("ap-south-1"), java.util.Set.of(), true);
        assertFalse(TelemetryRules.dataLandedEverywhere(both, onlyOne, NOW));
        var metricsForB = new TelemetryProbe.Landed(java.util.Set.of("ap-south-1"), java.util.Set.of("i-0000000000000002"), true);
        assertTrue(TelemetryRules.dataLandedEverywhere(both, metricsForB, NOW));
        assertFalse(TelemetryRules.dataLandedEverywhere(both, TelemetryProbe.Landed.unavailable(), NOW));
        assertFalse(TelemetryRules.dataLandedEverywhere(List.of(), metricsForB, NOW), "no reporting agent, no data proof");
    }
}
