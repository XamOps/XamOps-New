package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.autospotting.ConfigMode;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.PauseReason;
import com.xammer.cloud.service.autospotting.aws.AwsFallbackRules.Decision;
import com.xammer.cloud.service.autospotting.aws.AwsFallbackRules.Signals;
import com.xammer.cloud.service.autospotting.core.FallbackProperties;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The fallback entry rules FB-1 to FB-4 and the health rules around them (design-aws.md §6.3, §6.5). */
class AwsFallbackRulesTest {
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private final AwsFallbackRules rules = new AwsFallbackRules(FallbackProperties.defaults());

    private static Instant ago(int minutes) {
        return NOW.minusSeconds(minutes * 60L);
    }

    /** desired 4, 3 healthy; a shortfall that began {@code shortMinutes} ago. */
    private static Signals short4(int shortMinutes, List<Instant> spotFailures, Instant quotaAt, int interruptions) {
        return new Signals(NOW, 4, 3, 3, 1, ago(shortMinutes), spotFailures, quotaAt, null, interruptions, 3, true,
                0, Map.of(), null);
    }

    private static Signals healthy(int interruptions, int spot) {
        return new Signals(NOW, 4, 4, 4, 0, null, List.of(), null, null, interruptions, spot, true, 0, Map.of(), null);
    }

    private Decision active(Signals s) {
        return rules.evaluate(GroupState.ACTIVE, null, null, ConfigMode.MANAGED, ago(500), s);
    }

    @Test
    void fb1ShortfallForTenMinutesWithASpotFailureInTheWindow() {
        Decision d = active(short4(10, List.of(ago(5)), null, 0));
        assertThat(d.code()).isEqualTo("FB-1");
        assertThat(d.actions()).containsExactly(ReconcileAction.ENQUEUE_FALLBACK);
    }

    @Test
    void fb1WaitsForTheTenMinutesAndNeedsASpotCause() {
        assertThat(active(short4(9, List.of(ago(5)), null, 0)).actions()).doesNotContain(ReconcileAction.ENQUEUE_FALLBACK);
        // a failure older than the shortfall is not its cause
        assertThat(active(short4(10, List.of(ago(11)), null, 0)).actions())
                .doesNotContain(ReconcileAction.ENQUEUE_FALLBACK);
        // an unreplaced interruption is a Spot cause
        assertThat(active(short4(10, List.of(), null, 1)).code()).isEqualTo("FB-1");
    }

    @Test
    void fb2ThreeSpotFailuresInFifteenMinutesWithAShortfall() {
        Decision d = active(short4(1, List.of(ago(1), ago(5), ago(14)), null, 0));
        assertThat(d.code()).isEqualTo("FB-2");
        assertThat(d.actions()).containsExactly(ReconcileAction.ENQUEUE_FALLBACK);
        assertThat(active(short4(1, List.of(ago(1), ago(5), ago(16)), null, 0)).actions())
                .doesNotContain(ReconcileAction.ENQUEUE_FALLBACK);
    }

    @Test
    void fb3AQuotaFailureWithAShortfallEntersOnTheFirstObservation() {
        Decision d = active(short4(1, List.of(), ago(2), 0));
        assertThat(d.code()).isEqualTo("FB-3");
        assertThat(d.actions()).containsExactly(ReconcileAction.ENQUEUE_FALLBACK);
        // no shortfall, no fallback
        assertThat(active(new Signals(NOW, 4, 4, 4, 0, null, List.of(), ago(2), null, 0, 3, true, 0, Map.of(), null))
                .actions()).isEmpty();
    }

    @Test
    void fb4AnInterruptionStormIsPreEmptiveAndNotForApplyingGroups() {
        // max(3, 30% of 10 Spot instances) = 3
        Decision d = active(healthy(3, 10));
        assertThat(d.code()).isEqualTo("FB-4");
        assertThat(d.actions()).containsExactly(ReconcileAction.ENQUEUE_FALLBACK);
        assertThat(active(healthy(2, 10)).actions()).isEmpty();
        // 30% of 20 Spot instances = 6
        assertThat(active(healthy(5, 20)).actions()).isEmpty();
        assertThat(active(healthy(6, 20)).code()).isEqualTo("FB-4");
        Decision applying = rules.evaluate(GroupState.APPLYING, null, null, ConfigMode.MANAGED, ago(5), healthy(9, 10));
        assertThat(applying.actions()).isEmpty();
    }

    @Test
    void anOnDemandFailureNeverMakesAFallbackItMakesTheGroupDegraded() {
        Signals onDemandOnly = new Signals(NOW, 4, 3, 3, 2, ago(30), List.of(), null, ago(3), 0, 3, true, 0,
                Map.of(), null);
        Decision d = active(onDemandOnly);
        assertThat(d.actions()).doesNotContain(ReconcileAction.ENQUEUE_FALLBACK);
        assertThat(d.actions()).contains(ReconcileAction.MARK_DEGRADED, ReconcileAction.ALERT);
        // still no fallback from FALLBACK or DEGRADED either
        assertThat(rules.evaluate(GroupState.DEGRADED, null, null, ConfigMode.MANAGED, ago(60), onDemandOnly).actions())
                .doesNotContain(ReconcileAction.ENQUEUE_FALLBACK);
    }

    @Test
    void entryStates() {
        Signals fb1 = short4(12, List.of(ago(5)), null, 0);
        assertThat(rules.evaluate(GroupState.DEGRADED, null, null, ConfigMode.MANAGED, ago(60), fb1).actions())
                .containsExactly(ReconcileAction.ENQUEUE_FALLBACK);
        // DEGRADED on the fallback mix does not enter again
        assertThat(rules.evaluate(GroupState.DEGRADED, null, null, ConfigMode.FALLBACK, ago(60), fb1).actions())
                .doesNotContain(ReconcileAction.ENQUEUE_FALLBACK);
        assertThat(rules.evaluate(GroupState.PAUSED, PauseReason.ACCOUNT_SWITCH, GroupState.ACTIVE, ConfigMode.MANAGED,
                ago(60), fb1).actions()).containsExactly(ReconcileAction.ENQUEUE_FALLBACK);
        assertThat(rules.evaluate(GroupState.PAUSED, PauseReason.OPERATOR, GroupState.DEGRADED, ConfigMode.MANAGED,
                ago(60), fb1).actions()).containsExactly(ReconcileAction.ENQUEUE_FALLBACK);
        // FREEZE and DRIFT pauses get no automatic action (§6.4)
        assertThat(rules.evaluate(GroupState.PAUSED, PauseReason.FREEZE, GroupState.ACTIVE, ConfigMode.MANAGED,
                ago(60), fb1).actions()).doesNotContain(ReconcileAction.ENQUEUE_FALLBACK);
        assertThat(rules.evaluate(GroupState.PAUSED, PauseReason.DRIFT, GroupState.ACTIVE, ConfigMode.MANAGED,
                ago(60), fb1).actions()).doesNotContain(ReconcileAction.ENQUEUE_FALLBACK);
        // APPLYING: the core decides whether the in-flight op may be preempted
        assertThat(rules.evaluate(GroupState.APPLYING, null, null, ConfigMode.MANAGED, ago(5), fb1).actions())
                .containsExactly(ReconcileAction.ENQUEUE_FALLBACK);
        assertThat(rules.evaluate(GroupState.UNMANAGED, null, null, ConfigMode.MANAGED, ago(5), fb1).actions()).isEmpty();
        assertThat(rules.evaluate(GroupState.REVERTING, null, null, ConfigMode.MANAGED, ago(5), fb1).actions()).isEmpty();
    }

    @Test
    void aGroupAlreadyAtOneHundredPercentOnDemandHasNoFallbackPath() {
        Signals noPath = new Signals(NOW, 4, 3, 3, 1, ago(12), List.of(ago(5)), null, null, 0, 0, true, 100,
                Map.of(), null);
        Decision d = active(noPath);
        assertThat(d.code()).isEqualTo("NO_PATH");
        assertThat(d.actions()).containsExactly(ReconcileAction.MARK_DEGRADED, ReconcileAction.ALERT);
        assertThat(rules.evaluate(GroupState.DEGRADED, null, null, ConfigMode.MANAGED, ago(60), noPath).actions())
                .containsExactly(ReconcileAction.ALERT);
    }

    @Test
    void healthFailuresWithoutASpotCauseDegradeWithAnAlertAndProposeRemovingTheConcentratedType() {
        Signals concentrated = new Signals(NOW, 4, 4, 2, 0, ago(3), List.of(), null, null, 0, 3, true, 0,
                Map.of("c5.large", 2), null);
        Decision d = active(concentrated);
        assertThat(d.actions()).containsExactly(ReconcileAction.MARK_DEGRADED, ReconcileAction.ALERT);
        assertThat(d.proposeRemoveType()).isEqualTo("c5.large");
        Signals spread = new Signals(NOW, 4, 4, 2, 0, ago(3), List.of(), null, null, 0, 3, true, 0,
                Map.of("c5.large", 1, "m5.large", 1), null);
        assertThat(active(spread).proposeRemoveType()).isNull();
        Signals one = new Signals(NOW, 4, 4, 3, 0, ago(3), List.of(), null, null, 0, 3, true, 0,
                Map.of("c5.large", 1), null);
        assertThat(active(one).proposeRemoveType()).isNull();
    }

    @Test
    void aFallbackGroupIsDegradedOnlyTwentyMinutesAfterTheTransition() {
        Signals stillShort = new Signals(NOW, 4, 3, 3, 1, ago(40), List.of(ago(30)), null, null, 0, 0, true, 100,
                Map.of(), null);
        assertThat(rules.evaluate(GroupState.FALLBACK, null, null, ConfigMode.FALLBACK, ago(19), stillShort).actions())
                .isEmpty();
        assertThat(rules.evaluate(GroupState.FALLBACK, null, null, ConfigMode.FALLBACK, ago(20), stillShort).actions())
                .containsExactly(ReconcileAction.MARK_DEGRADED, ReconcileAction.ALERT);
    }

    @Test
    void aDegradedFallbackGroupRecoversAndNeverEntersAgain() {
        Signals recovered = new Signals(NOW, 4, 4, 4, 5, null, List.of(ago(60)), null, ago(40), 0, 0, true, 100,
                Map.of(), null);
        Decision d = rules.evaluate(GroupState.DEGRADED, null, null, ConfigMode.FALLBACK, ago(90), recovered);
        assertThat(d.actions()).containsExactly(ReconcileAction.MARK_RECOVERED);
    }

    @Test
    void theExitSignalNeedsCapacityAndAPlacementScoreOfSevenOrNone() {
        Signals back = new Signals(NOW, 4, 4, 4, 0, null, List.of(), null, null, 0, 0, true, 100, Map.of(), 8);
        assertThat(rules.evaluate(GroupState.FALLBACK, null, null, ConfigMode.FALLBACK, ago(90), back).actions())
                .containsExactly(ReconcileAction.ENQUEUE_FALLBACK_EXIT);
        Signals lowScore = new Signals(NOW, 4, 4, 4, 0, null, List.of(), null, null, 0, 0, true, 100, Map.of(), 6);
        assertThat(rules.evaluate(GroupState.FALLBACK, null, null, ConfigMode.FALLBACK, ago(90), lowScore).actions())
                .isEmpty();
        Signals unavailable = new Signals(NOW, 4, 4, 4, 0, null, List.of(), null, null, 0, 0, true, 100, Map.of(), null);
        assertThat(rules.evaluate(GroupState.FALLBACK, null, null, ConfigMode.FALLBACK, ago(90), unavailable).actions())
                .containsExactly(ReconcileAction.ENQUEUE_FALLBACK_EXIT);
    }

    @Test
    void signalsAreReadFromTheStatusJson() {
        String health = "{\"inService\":3,\"healthyInService\":3,\"recentFailedActivities\":2,"
                + "\"shortfallSince\":\"" + ago(12) + "\",\"spotFailureTimes\":[\"" + ago(4) + "\"],"
                + "\"quotaFailureAt\":null,\"interruptions30m\":1,\"unhealthyByType\":{\"c5.large\":2}}";
        String capacity = "{\"desired\":4,\"spot\":3,\"onDemand\":1,\"mixed\":true,\"odPercentAboveBase\":0}";
        Signals s = AwsFallbackRules.signals(NOW, health, capacity);
        assertThat(s.shortfall()).isTrue();
        assertThat(s.spotFailureTimes()).containsExactly(ago(4));
        assertThat(s.interruptions30m()).isEqualTo(1);
        assertThat(s.unhealthyByType()).containsEntry("c5.large", 2);
        assertThat(AwsFallbackRules.signals(NOW, "not json", capacity)).isNull();
        assertThat(rules.evaluate(GroupState.ACTIVE, null, null, ConfigMode.MANAGED, ago(500), s).code())
                .isEqualTo("FB-1");
    }
}
