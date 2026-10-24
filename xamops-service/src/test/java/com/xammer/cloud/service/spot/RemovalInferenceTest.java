package com.xammer.cloud.service.spot;

import com.xammer.cloud.domain.K8sSpotInterruptionEvent;
import com.xammer.cloud.service.spot.RemovalInference.Decision;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class RemovalInferenceTest {

    private static final Instant T0 = Instant.parse("2026-10-24T10:00:00Z");
    private static final Instant T1 = T0.plus(Duration.ofMinutes(5));
    private static final Duration GAP = Duration.ofMinutes(15);
    private static final Supplier<Set<String>> NONE_DRAINED = Set::of;

    @Test
    void aSpotNodeAbsentFromTheNextSnapshotIsRemoved() {
        SnapshotView s0 = SpotFixtures.snap().node("spot-1", "spot").node("od-1", "on-demand").node("keep", "spot").view(T0);
        SnapshotView s1 = SpotFixtures.snap().node("keep", "spot").view(T1);
        assertThat(RemovalInference.removedSpotNodes(s0, s1, T0, T1, GAP))
                .extracting(SnapshotView.NodeView::name).containsExactly("spot-1");
    }

    @Test
    void aGapOverTheLimitIsNotTrusted() {
        SnapshotView s0 = SpotFixtures.snap().node("spot-1", "spot").node("keep", "spot").view(T0);
        SnapshotView s1 = SpotFixtures.snap().node("keep", "spot").view(T0.plus(Duration.ofMinutes(16)));
        assertThat(RemovalInference.removedSpotNodes(s0, s1, T0, T0.plus(Duration.ofMinutes(16)), GAP)).isEmpty();
        assertThat(RemovalInference.removedSpotNodes(s0, s1, T0, T0.plus(GAP), GAP)).as("exactly the limit").hasSize(1);
    }

    @Test
    void anEmptyOrMissingS1NodeListIsNotTrusted() {
        SnapshotView s0 = SpotFixtures.snap().node("spot-1", "spot").view(T0);
        SnapshotView empty = SpotFixtures.snap().view(T1);
        SnapshotView missing = SnapshotView.parse(SpotFixtures.MAPPER, "{\"pods\":[]}", T1);
        assertThat(RemovalInference.removedSpotNodes(s0, empty, T0, T1, GAP)).isEmpty();
        assertThat(RemovalInference.removedSpotNodes(s0, missing, T0, T1, GAP)).isEmpty();
        assertThat(RemovalInference.removedSpotNodes(null, empty, null, T1, GAP)).isEmpty();
    }

    @Test
    void eachExclusionTaintSkips_AC5() {
        for (String taint : SpotNoticeRules.REMOVAL_EXCLUSION_TAINTS) {
            SnapshotView.NodeView n0 = SpotFixtures.snap().node("spot-1", "spot", taint).view(T0).node("spot-1");
            assertThat(RemovalInference.decide(n0, false, NONE_DRAINED)).as(taint).isEqualTo(Decision.SKIP_EXCLUSION_TAINT);
        }
    }

    @Test
    void anExistingRowIsUpdatedEvenWithAnExclusionTaint_AC4() {
        SnapshotView.NodeView n0 = SpotFixtures.snap().node("spot-1", "spot", "karpenter.sh/disrupted").view(T0).node("spot-1");
        assertThat(RemovalInference.decide(n0, true, NONE_DRAINED)).isEqualTo(Decision.UPDATE_EXISTING);
    }

    @Test
    void aXamopsDrainedNodeSkips_andTheLookupRunsOnlyWhenNeeded() {
        AtomicInteger lookups = new AtomicInteger();
        Supplier<Set<String>> drained = () -> {
            lookups.incrementAndGet();
            return Set.of("spot-1");
        };
        SnapshotView.NodeView n0 = SpotFixtures.snap().node("spot-1", "spot").view(T0).node("spot-1");
        SnapshotView.NodeView other = SpotFixtures.snap().node("spot-2", "spot").view(T0).node("spot-2");
        assertThat(RemovalInference.decide(n0, true, drained)).isEqualTo(Decision.UPDATE_EXISTING);
        assertThat(lookups).hasValue(0);
        assertThat(RemovalInference.decide(n0, false, drained)).isEqualTo(Decision.SKIP_XAMOPS_DRAIN);
        assertThat(RemovalInference.decide(other, false, drained)).isEqualTo(Decision.CREATE_UNCONFIRMED);
    }

    @Test
    void theUnconfirmedObservationIsBoundedByT0AndT1() {
        SnapshotView.NodeView n0 = SpotFixtures.snap().node("spot-1", "spot", true, Map.of()).view(T0).node("spot-1");
        SpotObservation obs = RemovalInference.unconfirmed(7L, n0, T0, T1);
        assertThat(obs.kind()).isEqualTo("unconfirmed");
        assertThat(obs.source()).isEqualTo("node-removed");
        assertThat(obs.noticedAt()).isEqualTo(T1);
        assertThat(obs.nodeRemovedAt()).isEqualTo(T1);
        assertThat(obs.nodeLastSeenAt()).isEqualTo(T0);
        assertThat(obs.cordoned()).isTrue();
        assertThat(obs.handling().mode()).isEqualTo("unknown");
        assertThat(obs.key().nodeCreatedAt()).isEqualTo(SpotFixtures.CREATED_AT);
        assertThat(obs.nodegroup()).isEqualTo("ng-spot-1");
    }

    @Test
    void applyRemovalSetsTheThreeFieldsOnlyWhereNull_andWorkloadsFillFromS0() {
        K8sSpotInterruptionEvent row = new K8sSpotInterruptionEvent();
        row.setNoticedAt(T0.minusSeconds(60));
        RemovalInference.applyRemoval(row, T0, T1, true);
        assertThat(row.getNodeRemovedAt()).isEqualTo(T1);
        assertThat(row.getNodeLastSeenAt()).isEqualTo(T0);
        assertThat(row.getCordonedBeforeRemoval()).isTrue();

        SnapshotView s0 = SpotFixtures.snap().node("spot-1", "spot").deployment("a", "web", 2, 2)
                .pod("a", "web-1a-x", "spot-1", "Running", "ReplicaSet", "web-1a").view(T0);
        SpotInterruptionMerge.fillWorkloadsIfUnknown(row,
                at -> WorkloadCaptureBuilder.capture(s0, "spot-1", at), T1, Duration.ofHours(6));
        assertThat(WorkloadCapture.fromJson(row.getAffectedWorkloads()))
                .containsExactly(new WorkloadCapture.WorkloadEntry("a", "Deployment", "web", 1));
    }

    @Test
    void applyRemovalKeepsFieldsAlreadySet() {
        K8sSpotInterruptionEvent row = new K8sSpotInterruptionEvent();
        Instant removed = T0.minusSeconds(600);
        row.setNoticedAt(T0.minusSeconds(900));
        row.setNodeRemovedAt(removed);
        row.setNodeLastSeenAt(removed.minusSeconds(300));
        row.setCordonedBeforeRemoval(false);
        row.setAffectedWorkloads("[]");
        RemovalInference.applyRemoval(row, T0, T1, true);
        SpotInterruptionMerge.fillWorkloadsIfUnknown(row, at -> {
            throw new AssertionError("workloads are known; the provider must not be asked");
        }, T1, Duration.ofHours(6));
        assertThat(row.getNodeRemovedAt()).isEqualTo(removed);
        assertThat(row.getNodeLastSeenAt()).isEqualTo(removed.minusSeconds(300));
        assertThat(row.getCordonedBeforeRemoval()).isFalse();
        assertThat(row.getAffectedWorkloads()).isEqualTo("[]");
    }
}
