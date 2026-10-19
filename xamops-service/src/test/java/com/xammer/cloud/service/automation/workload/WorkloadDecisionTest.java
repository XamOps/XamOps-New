package com.xammer.cloud.service.automation.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link WorkloadDecision} invariants around the F3 RPS advisory.
 *
 * <p>The whole point of carrying the advisory on {@code WorkloadDecision} rather than on
 * {@link HpaDecision} is that the common case is a <strong>non-change</strong> HPA decision
 * ({@code NO_CHANGE} / {@code NOT_EVALUATED}): {@code HpaDecision}'s own constructor throws if such a
 * decision carries a number, so a replica count there would trip the invariant. Here a
 * {@code NO_CHANGE} HPA coexists with a sized {@link RpsReplicaRecommendation} without any issue.
 */
class WorkloadDecisionTest {

    private static final WorkloadRef REF = WorkloadRef.of("team-a", "Deployment", "api");

    @Test
    @DisplayName("a NO_CHANGE HPA decision coexists with a non-null sized RPS advisory")
    void noChangeHpaCoexistsWithASizedRpsAdvisory() {
        WorkloadDecision base = decision(HpaDecision.noChange(List.of()));

        RpsReplicaRecommendation sized = RpsReplicaRecommendation.sized(10, "ceil(...) = 10");
        WorkloadDecision withRps = base.withRps(sized);

        assertThat(withRps.hpa().state()).isEqualTo(HpaDecision.State.NO_CHANGE);
        assertThat(withRps.rps()).isEqualTo(sized);
        assertThat(withRps.rps().replicas()).isEqualTo(10);
    }

    @Test
    @DisplayName("the compatibility constructor defaults rps to null; withRps returns a copy")
    void theCompatibilityConstructorDefaultsRpsToNull() {
        WorkloadDecision base = decision(HpaDecision.notEvaluated());

        assertThat(base.rps()).as("the engine never sets it").isNull();

        WorkloadDecision withRps = base.withRps(RpsReplicaRecommendation.noCapacityBasis());
        assertThat(withRps.rps()).isNotNull();
        // Every other field is preserved on the copy.
        assertThat(withRps.ref()).isEqualTo(base.ref());
        assertThat(withRps.hpa()).isEqualTo(base.hpa());
        assertThat(withRps.vertical()).isEqualTo(base.vertical());
        assertThat(withRps.horizontal()).isEqualTo(base.horizontal());
        assertThat(withRps.provenance()).isEqualTo(base.provenance());
        assertThat(base.rps()).as("the original is untouched").isNull();
    }

    @Test
    @DisplayName("a dash advisory carries no replica count and no formula")
    void aDashAdvisoryCarriesNoNumber() {
        assertThat(RpsReplicaRecommendation.noRpsSource().replicas()).isNull();
        assertThat(RpsReplicaRecommendation.noRpsSource().formula()).isNull();
        assertThat(RpsReplicaRecommendation.noRpsSource().reason()).isEqualTo(RpsReason.NO_RPS_SOURCE);

        assertThat(RpsReplicaRecommendation.noCapacityBasis().reason())
                .isEqualTo(RpsReason.NO_CAPACITY_BASIS);
    }

    private static WorkloadDecision decision(HpaDecision hpa) {
        Provenance provenance = new Provenance(95, 24, Instant.parse("2026-10-11T00:00:00Z"),
                Instant.parse("2026-10-12T00:00:00Z"), 1.0, "test", Instant.parse("2026-10-12T00:00:00Z"),
                WorkloadPolicy.Source.DEFAULTS);
        return new WorkloadDecision(REF, null, Set.of(), provenance, false,
                new VerticalDecision(List.of()), HorizontalDecision.notEvaluated(), hpa);
    }
}
