package com.xammer.cloud.service.spot;

import com.xammer.cloud.domain.K8sSpotInterruptionEvent;
import com.xammer.cloud.service.spot.RecoveryEvaluator.Evaluation;
import com.xammer.cloud.service.spot.RecoveryEvaluator.Outcome;
import com.xammer.cloud.service.spot.WorkloadCapture.WorkloadEntry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryEvaluatorTest {

    private static final Instant NOTICED = Instant.parse("2026-10-24T10:00:00Z");
    private static final Instant T1 = NOTICED.plus(Duration.ofMinutes(5));
    private static final Duration WINDOW = Duration.ofHours(6);
    private static final WorkloadEntry WEB = new WorkloadEntry("team-a", "Deployment", "web", 2);
    private static final WorkloadEntry PG = new WorkloadEntry("db", "StatefulSet", "pg", 1);
    private static final WorkloadEntry DEBUG = new WorkloadEntry("dev", "Pod", "debug", 1);

    private static K8sSpotInterruptionEvent row(Instant noticedAt, WorkloadEntry... workloads) {
        K8sSpotInterruptionEvent row = new K8sSpotInterruptionEvent();
        row.setNodeName("n1");
        row.setNoticedAt(noticedAt);
        row.setAffectedWorkloads(WorkloadCapture.toJson(List.of(workloads)));
        row.setRecoveryStatus("tracking");
        return row;
    }

    private static Evaluation eval(K8sSpotInterruptionEvent row, SnapshotView s1) {
        return RecoveryEvaluator.evaluate(row, WorkloadCapture.fromJson(row.getAffectedWorkloads()), s1, T1, WINDOW);
    }

    @Test
    void replicaSetPodsAreEvaluatedAgainstTheirDeployment_notReadyStaysTracking_AC6() {
        K8sSpotInterruptionEvent row = row(NOTICED, WEB);
        SnapshotView s1 = SpotFixtures.snap().node("n2", "spot").deployment("team-a", "web", 3, 2)
                .pod("team-a", "web-5d9c8f7b6-a", "n2", "Running", "ReplicaSet", "web-5d9c8f7b6").view(T1);
        Evaluation e = eval(row, s1);
        assertThat(e.outcome()).isEqualTo(Outcome.UNRECOVERED);
        RecoveryEvaluator.apply(row, e, T1);
        assertThat(row.getRecoveryStatus()).isEqualTo("tracking");
        assertThat(row.getRecoveryLastUnrecoveredAt()).isEqualTo(T1);
        assertThat(row.getRecoveredObservedAt()).isNull();
    }

    @Test
    void recoveredOnceReadyWithNoPendingPodAndTheNodeGone() {
        K8sSpotInterruptionEvent row = row(NOTICED, WEB);
        SnapshotView s1 = SpotFixtures.snap().node("n2", "spot").deployment("team-a", "web", 3, 3)
                .pod("team-a", "web-5d9c8f7b6-a", "n2", "Running", "ReplicaSet", "web-5d9c8f7b6").view(T1);
        Evaluation e = eval(row, s1);
        assertThat(e.outcome()).isEqualTo(Outcome.RECOVERED);
        RecoveryEvaluator.apply(row, e, T1);
        assertThat(row.getRecoveryStatus()).isEqualTo("recovered");
        assertThat(row.getRecoveredObservedAt()).isEqualTo(T1);
    }

    @Test
    void aPendingPodBlocksRecoveryAndIsTracked() {
        K8sSpotInterruptionEvent row = row(NOTICED, WEB);
        SnapshotView s1 = SpotFixtures.snap().node("n2", "spot").deployment("team-a", "web", 3, 3)
                .pod("team-a", "web-5d9c8f7b6-a", null, "Pending", "ReplicaSet", "web-5d9c8f7b6")
                .pod("team-a", "web-5d9c8f7b6-b", null, "Pending", "ReplicaSet", "web-5d9c8f7b6").view(T1);
        Evaluation e = eval(row, s1);
        assertThat(e.outcome()).isEqualTo(Outcome.UNRECOVERED);
        assertThat(e.pendingNow()).isEqualTo(2);
        RecoveryEvaluator.apply(row, e, T1);
        assertThat(row.getPendingFirstSeenAt()).isEqualTo(T1);
        assertThat(row.getPendingLastSeenAt()).isEqualTo(T1);
        assertThat(row.getPendingMaxPods()).isEqualTo(2);
        Instant t2 = T1.plus(Duration.ofMinutes(5));
        RecoveryEvaluator.apply(row, new Evaluation(Outcome.UNRECOVERED, 1), t2);
        assertThat(row.getPendingFirstSeenAt()).isEqualTo(T1);
        assertThat(row.getPendingLastSeenAt()).isEqualTo(t2);
        assertThat(row.getPendingMaxPods()).isEqualTo(2);
    }

    @Test
    void aPodStillOnTheNodeBlocksRecovery() {
        K8sSpotInterruptionEvent row = row(NOTICED, WEB);
        SnapshotView s1 = SpotFixtures.snap().node("n1", "spot").deployment("team-a", "web", 3, 3)
                .pod("team-a", "web-5d9c8f7b6-a", "n1", "Running", "ReplicaSet", "web-5d9c8f7b6").view(T1);
        assertThat(eval(row, s1).outcome()).isEqualTo(Outcome.UNRECOVERED);
    }

    @Test
    void aWorkloadInTheStoredListAndAbsentFromS1WasDeleted_isOk() {
        K8sSpotInterruptionEvent row = row(NOTICED, WEB, PG);
        SnapshotView s1 = SpotFixtures.snap().node("n2", "spot").deployment("other", "api", 1, 1)
                .statefulSet("db", "pg", 1, 1).view(T1);
        assertThat(eval(row, s1).outcome()).isEqualTo(Outcome.RECOVERED);
    }

    @Test
    void partialSnapshotGuard_missingOrEmptyDeploymentsLeavesTheRowUntouched_AC22() {
        K8sSpotInterruptionEvent row = row(NOTICED, WEB);
        for (SnapshotView s1 : List.of(
                SpotFixtures.snap().node("n2", "spot").noDeployments().view(T1),
                SpotFixtures.snap().node("n2", "spot").view(T1))) {
            Evaluation e = eval(row, s1);
            assertThat(e.outcome()).isEqualTo(Outcome.SKIP);
            RecoveryEvaluator.apply(row, e, T1);
            assertThat(row.getRecoveryStatus()).isEqualTo("tracking");
            assertThat(row.getRecoveredObservedAt()).isNull();
            assertThat(row.getRecoveryLastUnrecoveredAt()).isNull();
            assertThat(row.getPendingFirstSeenAt()).isNull();
        }
    }

    @Test
    void partialSnapshotGuard_sameForStatefulSets_AC22() {
        K8sSpotInterruptionEvent row = row(NOTICED, PG);
        SnapshotView s1 = SpotFixtures.snap().node("n2", "spot").deployment("x", "y", 1, 1).noStatefulSets().view(T1);
        assertThat(eval(row, s1).outcome()).isEqualTo(Outcome.SKIP);
    }

    @Test
    void anOwnerlessPodOnlyIsNeverRecovered_AC26() {
        K8sSpotInterruptionEvent row = row(NOTICED, DEBUG);
        SnapshotView s1 = SpotFixtures.snap().node("n2", "spot").deployment("x", "y", 1, 1).view(T1);
        Evaluation e = eval(row, s1);
        assertThat(e.outcome()).isEqualTo(Outcome.NOT_MEASURABLE);
        RecoveryEvaluator.apply(row, e, T1);
        assertThat(row.getRecoveryStatus()).isEqualTo("not-measurable");
        assertThat(row.getRecoveredObservedAt()).isNull();
    }

    @Test
    void aDeploymentAndAnOwnerlessPodIsRecoveredOnTheDeploymentsReadiness_AC26() {
        K8sSpotInterruptionEvent row = row(NOTICED, WEB, DEBUG);
        SnapshotView s1 = SpotFixtures.snap().node("n2", "spot").deployment("team-a", "web", 2, 2).view(T1);
        assertThat(eval(row, s1).outcome()).isEqualTo(Outcome.RECOVERED);
    }

    @Test
    void aSevenHourOldTrackingRowExpires_AC6() {
        K8sSpotInterruptionEvent gone = row(T1.minus(Duration.ofHours(7)), WEB);
        SnapshotView withoutNode = SpotFixtures.snap().node("n2", "spot").noDeployments().view(T1);
        Evaluation e = eval(gone, withoutNode);
        assertThat(e.outcome()).as("expiry runs even on a partial snapshot").isEqualTo(Outcome.EXPIRED_NOT_OBSERVED);
        RecoveryEvaluator.apply(gone, e, T1);
        assertThat(gone.getRecoveryStatus()).isEqualTo("not-observed");

        K8sSpotInterruptionEvent stays = row(T1.minus(Duration.ofHours(7)), WEB);
        SnapshotView withNode = SpotFixtures.snap().node("n1", "spot").view(T1);
        RecoveryEvaluator.apply(stays, eval(stays, withNode), T1);
        assertThat(stays.getRecoveryStatus()).isEqualTo("node-not-removed");
    }
}
