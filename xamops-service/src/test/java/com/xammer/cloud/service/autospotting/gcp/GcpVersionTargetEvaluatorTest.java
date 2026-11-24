package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator.Instance;
import com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator.Mode;
import com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator.Observation;
import com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator.State;
import com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator.Target;
import com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator.Verdict;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The §4.4 success test and the §3.6 stall rules (AC-GCP-04, AC-GCP-08 mechanics, AC-GCP-09). */
class GcpVersionTargetEvaluatorTest {
    static final String P = "https://www.googleapis.com/compute/v1/projects/xamops-test/global/instanceTemplates/";
    static final String ORIG = P + "web-tpl-1";
    static final String SPOT = P + "web-xas-aaaaaaaa";
    static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    final GcpVersionTargetEvaluator ev = new GcpVersionTargetEvaluator();

    static Target floor(int n) {
        return new Target(Map.of(GcpMigFacts.STANDARD_VERSION, ORIG, GcpMigFacts.SPOT_VERSION, SPOT), n);
    }

    static Instance std() {
        return new Instance(GcpMigFacts.STANDARD_VERSION, ORIG, "RUNNING", "NONE", "HEALTHY");
    }

    static Instance spot() {
        return new Instance(GcpMigFacts.SPOT_VERSION, SPOT, "RUNNING", "NONE", "HEALTHY");
    }

    static Instance oldOne() {
        return new Instance(GcpMigFacts.SPOT_VERSION, ORIG, "RUNNING", "NONE", "HEALTHY");
    }

    static Observation obs(Instant now, boolean reached, boolean stable, int size, List<Instance> inst, List<String> errors) {
        return new Observation(now, reached, stable, size, inst, true, errors);
    }

    static List<Instance> fourTwo() {
        return List.of(std(), spot(), spot(), spot());
    }

    @Test
    void successNeedsReachedAndStableOnTwoPollsThirtySecondsApart() {
        State s0 = new State(T0, T0, -1, null);
        Verdict first = ev.evaluate(obs(T0, true, true, 4, fourTwo(), List.of()), floor(1), s0, Mode.PROGRESS);
        assertThat(first).isInstanceOf(GcpVersionTargetEvaluator.Waiting.class);
        State s1 = ((GcpVersionTargetEvaluator.Waiting) first).state();
        Verdict tooSoon = ev.evaluate(obs(T0.plusSeconds(15), true, true, 4, fourTwo(), List.of()), floor(1), s1, Mode.PROGRESS);
        assertThat(tooSoon).isInstanceOf(GcpVersionTargetEvaluator.Waiting.class);
        Verdict ok = ev.evaluate(obs(T0.plusSeconds(30), true, true, 4, fourTwo(), List.of()), floor(1), s1, Mode.PROGRESS);
        assertThat(ok).isInstanceOf(GcpVersionTargetEvaluator.Done.class);
    }

    @Test
    void anOperationBeingDoneOrAPatchBeingAcceptedIsNotSuccess_versionTargetNotReachedKeepsWaiting() {
        State seen = new State(T0, T0, 4, T0.minusSeconds(120));
        Verdict v = ev.evaluate(obs(T0, false, true, 4, fourTwo(), List.of()), floor(1), seen, Mode.PROGRESS);
        assertThat(v).isInstanceOf(GcpVersionTargetEvaluator.Waiting.class);
    }

    @Test
    void notStableIsNotSuccessEvenWhenEverythingElseHolds() {
        State seen = new State(T0, T0, 4, T0.minusSeconds(120));
        assertThat(ev.evaluate(obs(T0, true, false, 4, fourTwo(), List.of()), floor(1), seen, Mode.PROGRESS))
                .isInstanceOf(GcpVersionTargetEvaluator.Waiting.class);
    }

    @Test
    void anInstanceOnTheOldTemplateOfASpotVersionBlocksSuccess() {
        State seen = new State(T0, T0, 3, T0.minusSeconds(120));
        List<Instance> inst = List.of(std(), spot(), spot(), oldOne());
        assertThat(ev.evaluate(obs(T0, true, true, 4, inst, List.of()), floor(1), seen, Mode.PROGRESS))
                .isInstanceOf(GcpVersionTargetEvaluator.Waiting.class);
    }

    @Test
    void theFloorVersionKeepsNAndFewerThanNStandardInstancesBlocksSuccess() {
        State seen = new State(T0, T0, 4, T0.minusSeconds(120));
        List<Instance> noStd = List.of(spot(), spot(), spot(), spot());
        assertThat(ev.evaluate(obs(T0, true, true, 4, noStd, List.of()), floor(1), seen, Mode.PROGRESS))
                .isInstanceOf(GcpVersionTargetEvaluator.Waiting.class);
        assertThat(ev.evaluate(obs(T0, true, true, 4, fourTwo(), List.of()), floor(1), seen, Mode.PROGRESS))
                .isInstanceOf(GcpVersionTargetEvaluator.Done.class);
        // N is capped by the target size: a floor of 3 on a 2-VM group needs only 2 standard instances.
        assertThat(GcpFloorPlanner.minStandard(3, 2)).isEqualTo(2);
    }

    @Test
    void healthyCountMustEqualTargetSize() {
        State seen = new State(T0, T0, 4, T0.minusSeconds(120));
        List<Instance> sick = List.of(std(), spot(), spot(),
                new Instance(GcpMigFacts.SPOT_VERSION, SPOT, "RUNNING", "NONE", "UNHEALTHY"));
        assertThat(ev.evaluate(obs(T0, true, true, 4, sick, List.of()), floor(1), seen, Mode.PROGRESS))
                .isInstanceOf(GcpVersionTargetEvaluator.Waiting.class);
        List<Instance> short1 = List.of(std(), spot(), spot());
        assertThat(ev.evaluate(obs(T0, true, true, 4, short1, List.of()), floor(1), seen, Mode.PROGRESS))
                .isInstanceOf(GcpVersionTargetEvaluator.Waiting.class);
    }

    @Test
    void capacityStallFailsAProgressRolloutButOnlyWaitsInLenientMode() {
        State stuck = new State(T0.minusSeconds(3600), T0.minus(Duration.ofMinutes(21)), 1, null);
        List<Instance> partial = List.of(std(), oldOne(), oldOne(), oldOne());
        List<String> errors = List.of("ZONE_RESOURCE_POOL_EXHAUSTED");
        Verdict v = ev.evaluate(obs(T0, false, false, 4, partial, errors), floor(1), stuck, Mode.PROGRESS);
        assertThat(v).isInstanceOfSatisfying(GcpVersionTargetEvaluator.Failed.class, f -> {
            assertThat(f.kind()).isEqualTo(ErrorKind.CAPACITY);
            assertThat(f.code()).isEqualTo(GcpVersionTargetEvaluator.CAPACITY_STALL);
        });
        assertThat(ev.evaluate(obs(T0, false, false, 4, partial, errors), floor(1), stuck, Mode.LENIENT))
                .isInstanceOf(GcpVersionTargetEvaluator.Waiting.class);
    }

    @Test
    void healthStallFailsAProgressRollout() {
        State stuck = new State(T0.minusSeconds(3600), T0.minus(Duration.ofMinutes(25)), 3, null);
        List<Instance> bad = List.of(std(), spot(), oldOne(),
                new Instance(GcpMigFacts.SPOT_VERSION, SPOT, "RUNNING", "NONE", "UNHEALTHY"));
        assertThat(ev.evaluate(obs(T0, false, false, 4, bad, List.of()), floor(1), stuck, Mode.PROGRESS))
                .isInstanceOfSatisfying(GcpVersionTargetEvaluator.Failed.class,
                        f -> assertThat(f.code()).isEqualTo(GcpVersionTargetEvaluator.HEALTH_STALL));
    }

    @Test
    void silentStallKeepsWaitingAndFlagsStalled_neverFailedForTime() {
        State stuck = new State(T0.minus(Duration.ofHours(7)), T0.minus(Duration.ofMinutes(50)), 1, null);
        List<Instance> partial = List.of(std(), oldOne(), oldOne(), oldOne());
        Verdict v = ev.evaluate(obs(T0, false, false, 4, partial, List.of()), floor(1), stuck, Mode.PROGRESS);
        assertThat(v).isInstanceOfSatisfying(GcpVersionTargetEvaluator.Waiting.class, w -> assertThat(w.stalled()).isTrue());
        State seven = new State(T0.minus(Duration.ofHours(7)), T0.minus(Duration.ofHours(7)), 1, null);
        assertThat(ev.evaluate(obs(T0, false, false, 4, partial, List.of()), floor(1), seven, Mode.PROGRESS))
                .isNotInstanceOf(GcpVersionTargetEvaluator.Failed.class);
    }

    @Test
    void progressResetsTheStallClock() {
        State prior = new State(T0.minusSeconds(3600), T0.minus(Duration.ofMinutes(30)), 1, null);
        List<Instance> moved = List.of(std(), spot(), oldOne(), oldOne());
        Verdict v = ev.evaluate(obs(T0, false, false, 4, moved, List.of("ZONE_RESOURCE_POOL_EXHAUSTED")), floor(1), prior,
                Mode.PROGRESS);
        assertThat(v).isInstanceOfSatisfying(GcpVersionTargetEvaluator.Waiting.class,
                w -> assertThat(w.state().lastProgressAt()).isEqualTo(T0));
    }

    @Test
    void fullSpotSingleVersionSucceedsWithNoStandardInstances() {
        Target t = new Target(Map.of(GcpMigFacts.SPOT_VERSION, SPOT), 0);
        State seen = new State(T0, T0, 3, T0.minusSeconds(120));
        List<Instance> inst = new ArrayList<>(List.of(spot(), spot(), spot()));
        assertThat(ev.evaluate(obs(T0, true, true, 3, inst, List.of()), t, seen, Mode.PROGRESS))
                .isInstanceOf(GcpVersionTargetEvaluator.Done.class);
    }

    @Test
    void stateRoundTripsThroughTheProgressJson() {
        Observation o = obs(T0, false, false, 4, fourTwo(), List.of());
        State s = new State(T0, T0.plusSeconds(5), 2, T0.plusSeconds(9));
        String json = GcpVersionTargetEvaluator.progressJson(o, floor(1), s, "m");
        State back = State.fromJson(json, T0);
        assertThat(back).isEqualTo(s);
    }
}
