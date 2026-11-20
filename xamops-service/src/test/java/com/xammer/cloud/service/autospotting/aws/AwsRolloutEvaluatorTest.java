package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rollout Done rule and the rollback triggers (design-aws.md §2.5, PD-03, DR-07, AC-AWS-05). Pure
 * time-driven tests: the evaluator takes {@code now} as a parameter, so a 10 minute window is a
 * subtraction, not a sleep.
 */
class AwsRolloutEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-11-17T10:00:00Z");
    private final AwsRolloutEvaluator evaluator = new AwsRolloutEvaluator(AwsRolloutProperties.defaults());

    private static AwsRolloutEvaluator.Observation obs(Instant now, int desired, int spot, int healthy,
                                                       Instant lastSpotFailure) {
        return new AwsRolloutEvaluator.Observation(now, desired, spot, healthy, lastSpotFailure);
    }

    private AwsRolloutEvaluator.Verdict poll(Instant now, int desired, int spot, int healthy, Instant failure,
                                             int spotTarget, AwsRolloutEvaluator.State state) {
        return evaluator.evaluate(obs(now, desired, spot, healthy, failure), spotTarget, state,
                AwsRolloutEvaluator.Mode.PROGRESS);
    }

    // ---- the Done rule (PD-03, DR-07) ----

    @Test
    void desiredTwoAggressiveIsDoneOnlyWithOneSpotInstance() {
        int target = AwsRolloutEvaluator.spotTarget(2, 1, 0);
        assertThat(target).isEqualTo(1);
        assertThat(poll(T0, 2, 0, 2, null, target, AwsRolloutEvaluator.State.initial()))
                .isInstanceOf(AwsRolloutEvaluator.Waiting.class);
        assertThat(poll(T0, 2, 1, 2, null, target, AwsRolloutEvaluator.State.initial()))
                .isInstanceOf(AwsRolloutEvaluator.Done.class);
    }

    @Test
    void theToleranceNeverAcceptsZeroSpotWhenTheProfileExpectsSome() {
        // desired 12 Conservative: spotTarget 5; tolerance max(1, 1.2) = 1.2. Zero Spot is 5 away: not done.
        // desired 2 Aggressive: spotTarget 1, |0-1| = 1 <= tolerance 1, yet zero Spot must not be accepted.
        assertThat(evaluator.spotConverged(2, 0, 1)).isFalse();
        assertThat(evaluator.spotConverged(2, 1, 1)).isTrue();
        assertThat(evaluator.spotConverged(12, 0, 1)).isFalse();
    }

    @Test
    void theToleranceIsOneInstanceOrTenPercentWhicheverIsLarger() {
        assertThat(evaluator.tolerance(4)).isEqualTo(1.0);
        assertThat(evaluator.tolerance(10)).isEqualTo(1.0);
        assertThat(evaluator.tolerance(40)).isEqualTo(4.0);
        // desired 40, target 20: 17 is within 4 (and at least 16); 15 is not.
        assertThat(evaluator.spotConverged(40, 17, 20)).isTrue();
        assertThat(evaluator.spotConverged(40, 15, 20)).isFalse();
        assertThat(evaluator.spotConverged(40, 25, 20)).isFalse();
    }

    @Test
    void aGroupThatWantsNoSpotIsDoneWhenHealthy() {
        assertThat(AwsRolloutEvaluator.spotTarget(1, 1, 0)).isZero();
        assertThat(poll(T0, 1, 0, 1, null, 0, AwsRolloutEvaluator.State.initial()))
                .isInstanceOf(AwsRolloutEvaluator.Done.class);
    }

    @Test
    void notDoneWhileAnInstanceIsUnhealthyEvenAtTheRightSpotCount() {
        assertThat(poll(T0, 4, 1, 3, null, 1, AwsRolloutEvaluator.State.initial()))
                .isInstanceOf(AwsRolloutEvaluator.Waiting.class);
    }

    // ---- unhealthy rollouts ----

    @Test
    void aDipInTargetGroupHealthForTenMinutesTriggersRolloutUnhealthy() {
        AwsRolloutEvaluator.State s = AwsRolloutEvaluator.State.initial();
        AwsRolloutEvaluator.Verdict v = poll(T0, 4, 1, 3, null, 1, s);
        assertThat(v).isInstanceOf(AwsRolloutEvaluator.Waiting.class);
        s = v.state();
        v = poll(T0.plus(Duration.ofMinutes(9)), 4, 1, 3, null, 1, s);
        assertThat(v).isInstanceOf(AwsRolloutEvaluator.Waiting.class);
        s = v.state();
        v = poll(T0.plus(Duration.ofMinutes(10)), 4, 1, 3, null, 1, s);
        assertThat(v).isInstanceOfSatisfying(AwsRolloutEvaluator.Failed.class, f -> {
            assertThat(f.kind()).isEqualTo(ErrorKind.VALIDATION);
            assertThat(f.code()).isEqualTo("ROLLOUT_UNHEALTHY");
        });
    }

    @Test
    void aShortDipThatRecoversNeverFails() {
        AwsRolloutEvaluator.State s = poll(T0, 4, 1, 3, null, 1, AwsRolloutEvaluator.State.initial()).state();
        AwsRolloutEvaluator.Verdict recovered = poll(T0.plus(Duration.ofMinutes(5)), 4, 1, 4, null, 1, s);
        assertThat(recovered).isInstanceOf(AwsRolloutEvaluator.Done.class);
        assertThat(recovered.state().unhealthySince()).isNull();
        // A later dip starts its own 10 minute window.
        AwsRolloutEvaluator.Verdict dip = poll(T0.plus(Duration.ofMinutes(12)), 4, 1, 3, null, 1, recovered.state());
        assertThat(dip).isInstanceOf(AwsRolloutEvaluator.Waiting.class);
    }

    @Test
    void aShortfallWithASpotCauseInTheWindowRollsBackAsSpotShortfall() {
        Instant failure = T0.plus(Duration.ofMinutes(8));
        AwsRolloutEvaluator.State s = poll(T0, 4, 1, 3, failure, 1, AwsRolloutEvaluator.State.initial()).state();
        AwsRolloutEvaluator.Verdict v = poll(T0.plus(Duration.ofMinutes(10)), 4, 1, 3, failure, 1, s);
        assertThat(v).isInstanceOfSatisfying(AwsRolloutEvaluator.Failed.class, f -> {
            assertThat(f.kind()).isEqualTo(ErrorKind.CAPACITY);
            assertThat(f.code()).isEqualTo("SPOT_SHORTFALL_DURING_ROLLOUT");
        });
    }

    @Test
    void aSpotFailureOlderThanTheWindowIsNotACause() {
        Instant old = T0.minus(Duration.ofMinutes(30));
        AwsRolloutEvaluator.State s = poll(T0, 4, 1, 3, old, 1, AwsRolloutEvaluator.State.initial()).state();
        AwsRolloutEvaluator.Verdict v = poll(T0.plus(Duration.ofMinutes(10)), 4, 1, 3, old, 1, s);
        assertThat(v).isInstanceOfSatisfying(AwsRolloutEvaluator.Failed.class,
                f -> assertThat(f.code()).isEqualTo("ROLLOUT_UNHEALTHY"));
    }

    // ---- Spot capacity stall ----

    @Test
    void spotFailuresWithHealthyCapacityKeepWaitingUntilTheCap() {
        Instant failure = T0;
        AwsRolloutEvaluator.Verdict v = poll(T0, 4, 0, 4, failure, 1, AwsRolloutEvaluator.State.initial());
        assertThat(v).isInstanceOfSatisfying(AwsRolloutEvaluator.Waiting.class,
                w -> assertThat(w.message()).isEqualTo("Waiting for Spot capacity"));
        AwsRolloutEvaluator.State s = v.state();
        v = poll(T0.plus(Duration.ofMinutes(119)), 4, 0, 4, failure.plus(Duration.ofMinutes(118)), 1, s);
        assertThat(v).isInstanceOf(AwsRolloutEvaluator.Waiting.class);
        v = poll(T0.plus(Duration.ofHours(2)), 4, 0, 4, failure.plus(Duration.ofMinutes(119)), 1, v.state());
        assertThat(v).isInstanceOfSatisfying(AwsRolloutEvaluator.Failed.class, f -> {
            assertThat(f.kind()).isEqualTo(ErrorKind.CAPACITY);
            assertThat(f.code()).isEqualTo("SPOT_CAPACITY_STALL");
        });
    }

    @Test
    void theWaitCapIsConfigurable() {
        AwsRolloutEvaluator shortCap = new AwsRolloutEvaluator(new AwsRolloutProperties(1, Duration.ofMinutes(30),
                Duration.ofMinutes(10)));
        AwsRolloutEvaluator.State s = shortCap.evaluate(obs(T0, 4, 0, 4, T0), 1,
                AwsRolloutEvaluator.State.initial(), AwsRolloutEvaluator.Mode.PROGRESS).state();
        AwsRolloutEvaluator.Verdict v = shortCap.evaluate(obs(T0.plus(Duration.ofMinutes(30)), 4, 0, 4,
                T0.plus(Duration.ofMinutes(29))), 1, s, AwsRolloutEvaluator.Mode.PROGRESS);
        assertThat(v).isInstanceOf(AwsRolloutEvaluator.Failed.class);
    }

    @Test
    void aFallbackExitStallsAfterThirtyMinutesWithNoSpotProgress() {
        AwsRolloutEvaluator.State s = evaluator.evaluate(obs(T0, 4, 0, 4, T0), 1, AwsRolloutEvaluator.State.initial(),
                AwsRolloutEvaluator.Mode.FALLBACK_EXIT).state();
        AwsRolloutEvaluator.Verdict v = evaluator.evaluate(obs(T0.plus(Duration.ofMinutes(29)), 4, 0, 4,
                T0.plus(Duration.ofMinutes(28))), 1, s, AwsRolloutEvaluator.Mode.FALLBACK_EXIT);
        assertThat(v).isInstanceOf(AwsRolloutEvaluator.Waiting.class);
        v = evaluator.evaluate(obs(T0.plus(Duration.ofMinutes(30)), 4, 0, 4, T0.plus(Duration.ofMinutes(29))), 1,
                v.state(), AwsRolloutEvaluator.Mode.FALLBACK_EXIT);
        assertThat(v).isInstanceOfSatisfying(AwsRolloutEvaluator.Failed.class,
                f -> assertThat(f.code()).isEqualTo("SPOT_CAPACITY_STALL"));
    }

    @Test
    void aFallbackExitThatGainsSpotRestartsItsStallClock() {
        AwsRolloutEvaluator.State s = evaluator.evaluate(obs(T0, 8, 0, 8, T0), 4, AwsRolloutEvaluator.State.initial(),
                AwsRolloutEvaluator.Mode.FALLBACK_EXIT).state();
        s = evaluator.evaluate(obs(T0.plus(Duration.ofMinutes(25)), 8, 1, 8, T0.plus(Duration.ofMinutes(24))), 4, s,
                AwsRolloutEvaluator.Mode.FALLBACK_EXIT).state();
        AwsRolloutEvaluator.Verdict v = evaluator.evaluate(obs(T0.plus(Duration.ofMinutes(40)), 8, 1, 8,
                T0.plus(Duration.ofMinutes(39))), 4, s, AwsRolloutEvaluator.Mode.FALLBACK_EXIT);
        assertThat(v).isInstanceOf(AwsRolloutEvaluator.Waiting.class);
    }

    // ---- compensation leg and the reconciler's shortage rule ----

    @Test
    void theCompensationLegNeverFailsIntoAnotherCompensation() {
        AwsRolloutEvaluator.State s = AwsRolloutEvaluator.State.initial();
        AwsRolloutEvaluator.Verdict v = evaluator.evaluateCompensation(obs(T0, 4, 0, 2, T0), s);
        assertThat(v).isInstanceOf(AwsRolloutEvaluator.Waiting.class);
        v = evaluator.evaluateCompensation(obs(T0.plus(Duration.ofHours(5)), 4, 0, 2, T0), v.state());
        assertThat(v).isInstanceOf(AwsRolloutEvaluator.Waiting.class);
        v = evaluator.evaluateCompensation(obs(T0.plus(Duration.ofHours(6)), 4, 0, 4, null), v.state());
        assertThat(v).isInstanceOf(AwsRolloutEvaluator.Done.class);
    }

    @Test
    void theReconcilerShortageNeedsATenMinuteSpotCauseShortfall() {
        Instant now = T0.plus(Duration.ofMinutes(12));
        Instant streak = T0;
        Instant lastFailure = T0.plus(Duration.ofMinutes(9));
        assertThat(evaluator.shortage(now, 4, 3, streak, lastFailure)).isTrue();
        assertThat(evaluator.shortage(now, 4, 4, streak, lastFailure)).as("capacity is healthy").isFalse();
        assertThat(evaluator.shortage(T0.plus(Duration.ofMinutes(9)), 4, 3, streak, lastFailure))
                .as("under ten minutes").isFalse();
        assertThat(evaluator.shortage(now, 4, 3, streak, T0.plus(Duration.ofMinutes(1))))
                .as("last Spot failure is outside the window").isFalse();
        assertThat(evaluator.shortage(now, 4, 3, null, null)).as("no Spot failure at all").isFalse();
    }

    // ---- state and progress JSON ----

    @Test
    void theStateSurvivesAProgressJsonRoundTrip() throws IOException {
        AwsRolloutEvaluator.Observation o = obs(T0, 4, 1, 3, T0.minusSeconds(30));
        AwsRolloutEvaluator.Verdict v = poll(T0, 4, 1, 3, T0.minusSeconds(30), 1, AwsRolloutEvaluator.State.initial());
        String json = AwsRolloutEvaluator.progressJson(o, 1, v.state(), "Waiting for instances to become healthy");
        JsonNode n = new ObjectMapper().readTree(json);
        assertThat(n.path("spotNow").asInt()).isEqualTo(1);
        assertThat(n.path("spotTarget").asInt()).isEqualTo(1);
        assertThat(n.path("inService").asInt()).isEqualTo(3);
        assertThat(n.path("desired").asInt()).isEqualTo(4);
        assertThat(AwsRolloutEvaluator.State.fromJson(json)).isEqualTo(v.state());
        assertThat(AwsRolloutEvaluator.State.fromJson("not json")).isEqualTo(AwsRolloutEvaluator.State.initial());
        assertThat(AwsRolloutEvaluator.State.fromJson(null)).isEqualTo(AwsRolloutEvaluator.State.initial());
    }
}
