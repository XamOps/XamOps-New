package com.xammer.cloud.autospotting.lifecycle;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupTransitions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every allowed and every forbidden edge of the §2.2 diagram (plan-phase-06.md task 6.1 verify;
 * FR-TST-01, design-ux-ops.md §9.1: "Every edge in this diagram and in 2.2.1, and every forbidden
 * edge, has a state-machine unit test"). The allowed set here is the §2.2 diagram transcribed by
 * hand; the parameterized tests then assert {@link GroupTransitions} agrees for all 12×12 ordered
 * pairs, so a drift in either direction (an edge added or dropped) fails.
 */
class GroupTransitionsTest {

    // The allowed edges of the §2.2 stateDiagram-v2 (incl. DC-05 FALLBACK<->DEGRADED and the
    // PLANNED->PLANNED / PAUSED->PAUSED self-edges). Transcribed independently of GroupTransitions.
    private static final List<GroupState[]> ALLOWED_EDGES = List.of(
            edge(GroupState.UNMANAGED, GroupState.PLANNED),
            edge(GroupState.UNMANAGED, GroupState.GONE),
            edge(GroupState.REVERTED, GroupState.PLANNED),
            edge(GroupState.REVERTED, GroupState.REVERTING),
            edge(GroupState.REVERTED, GroupState.GONE),
            edge(GroupState.PLANNED, GroupState.PLANNED),
            edge(GroupState.PLANNED, GroupState.UNMANAGED),
            edge(GroupState.PLANNED, GroupState.REVERTED),
            edge(GroupState.PLANNED, GroupState.APPLYING),
            edge(GroupState.PLANNED, GroupState.GONE),
            edge(GroupState.LEGACY_UNCONFIRMED, GroupState.APPLYING),
            edge(GroupState.LEGACY_UNCONFIRMED, GroupState.REVERTING),
            edge(GroupState.LEGACY_UNCONFIRMED, GroupState.UNMANAGED),
            edge(GroupState.LEGACY_UNCONFIRMED, GroupState.GONE),
            edge(GroupState.APPLYING, GroupState.ACTIVE),
            edge(GroupState.APPLYING, GroupState.REVERTING),
            edge(GroupState.APPLYING, GroupState.UNMANAGED),
            edge(GroupState.APPLYING, GroupState.REVERTED),
            edge(GroupState.APPLYING, GroupState.PLANNED),
            edge(GroupState.APPLYING, GroupState.LEGACY_UNCONFIRMED),
            edge(GroupState.APPLYING, GroupState.PAUSED),
            edge(GroupState.APPLYING, GroupState.FALLBACK),
            edge(GroupState.APPLYING, GroupState.FAILED),
            edge(GroupState.APPLYING, GroupState.GONE),
            edge(GroupState.ACTIVE, GroupState.APPLYING),
            edge(GroupState.ACTIVE, GroupState.FALLBACK),
            edge(GroupState.ACTIVE, GroupState.DEGRADED),
            edge(GroupState.ACTIVE, GroupState.PAUSED),
            edge(GroupState.ACTIVE, GroupState.REVERTING),
            edge(GroupState.ACTIVE, GroupState.GONE),
            edge(GroupState.DEGRADED, GroupState.ACTIVE),
            edge(GroupState.DEGRADED, GroupState.FALLBACK),
            edge(GroupState.DEGRADED, GroupState.PAUSED),
            edge(GroupState.DEGRADED, GroupState.REVERTING),
            edge(GroupState.DEGRADED, GroupState.GONE),
            edge(GroupState.FALLBACK, GroupState.APPLYING),
            edge(GroupState.FALLBACK, GroupState.DEGRADED),
            edge(GroupState.FALLBACK, GroupState.PAUSED),
            edge(GroupState.FALLBACK, GroupState.REVERTING),
            edge(GroupState.FALLBACK, GroupState.GONE),
            edge(GroupState.PAUSED, GroupState.APPLYING),
            edge(GroupState.PAUSED, GroupState.PAUSED),
            edge(GroupState.PAUSED, GroupState.ACTIVE),
            edge(GroupState.PAUSED, GroupState.FALLBACK),
            edge(GroupState.PAUSED, GroupState.DEGRADED),
            edge(GroupState.PAUSED, GroupState.REVERTING),
            edge(GroupState.PAUSED, GroupState.GONE),
            edge(GroupState.REVERTING, GroupState.REVERTED),
            edge(GroupState.REVERTING, GroupState.FAILED),
            edge(GroupState.REVERTING, GroupState.ACTIVE),
            edge(GroupState.REVERTING, GroupState.DEGRADED),
            edge(GroupState.REVERTING, GroupState.FALLBACK),
            edge(GroupState.REVERTING, GroupState.PAUSED),
            edge(GroupState.REVERTING, GroupState.LEGACY_UNCONFIRMED),
            edge(GroupState.REVERTING, GroupState.GONE),
            edge(GroupState.FAILED, GroupState.REVERTING),
            edge(GroupState.FAILED, GroupState.GONE));

    private static GroupState[] edge(GroupState from, GroupState to) {
        return new GroupState[]{from, to};
    }

    private static Set<String> allowedKeys() {
        return ALLOWED_EDGES.stream().map(e -> e[0] + "->" + e[1]).collect(java.util.stream.Collectors.toSet());
    }

    @ParameterizedTest(name = "allowed {0} -> {1}")
    @MethodSource("allowedEdges")
    void everyDiagramEdgeIsAllowed(GroupState from, GroupState to) {
        assertThat(GroupTransitions.isAllowed(from, to))
                .as("edge %s -> %s is in the §2.2 diagram and must be allowed", from, to)
                .isTrue();
    }

    @ParameterizedTest(name = "forbidden {0} -> {1}")
    @MethodSource("allPairs")
    void everyNonDiagramEdgeIsForbidden(GroupState from, GroupState to) {
        boolean inDiagram = allowedKeys().contains(from + "->" + to);
        if (inDiagram) {
            return; // covered by everyDiagramEdgeIsAllowed
        }
        assertThat(GroupTransitions.isAllowed(from, to))
                .as("edge %s -> %s is not in the §2.2 diagram and must be forbidden", from, to)
                .isFalse();
    }

    @Test
    void nullStatesAreForbidden() {
        assertThat(GroupTransitions.isAllowed(null, GroupState.ACTIVE)).isFalse();
        assertThat(GroupTransitions.isAllowed(GroupState.ACTIVE, null)).isFalse();
        assertThat(GroupTransitions.isAllowed(null, null)).isFalse();
    }

    @Test
    void goneIsTerminal() {
        assertThat(GroupTransitions.allowedFrom(GroupState.GONE)).isEmpty();
    }

    @Test
    void revertedGoesToPlannedRevertingOrGone() {
        // REVERTING is the GR-02 edge: the exact revert after a drain-only revert (plan kinds gate it).
        assertThat(GroupTransitions.allowedFrom(GroupState.REVERTED))
                .containsExactlyInAnyOrder(GroupState.PLANNED, GroupState.REVERTING, GroupState.GONE);
    }

    static Stream<Arguments> allowedEdges() {
        return ALLOWED_EDGES.stream().map(e -> Arguments.of(e[0], e[1]));
    }

    static Stream<Arguments> allPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (GroupState from : GroupState.values()) {
            for (GroupState to : GroupState.values()) {
                pairs.add(Arguments.of(from, to));
            }
        }
        return pairs.stream();
    }
}
