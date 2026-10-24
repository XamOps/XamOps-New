package com.xammer.cloud.service.spot;

import com.xammer.cloud.service.spot.WorkloadCapture.WorkloadEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Capture rules 1-4 of design §B4.2: unknown (null) and empty ([]) are different results. */
class WorkloadCaptureBuilderTest {

    private static final Instant SNAP_AT = Instant.parse("2026-10-24T10:00:00Z");
    private static final Instant BEFORE = SNAP_AT.minusSeconds(60);
    private static final Instant AFTER = SNAP_AT.plusSeconds(60);

    @Test
    void rule1_noSnapshotOrNodeNotListedIsUnknown() {
        assertThat(WorkloadCaptureBuilder.capture(null, "n1", AFTER)).isNull();
        SnapshotView snap = SpotFixtures.snap().node("n2", "spot").view(SNAP_AT);
        assertThat(WorkloadCaptureBuilder.capture(snap, "n1", AFTER)).isNull();
    }

    @Test
    void rule1_emptyAfterTheNoticeIsUnknown() {
        SnapshotView snap = SpotFixtures.snap().node("n1", "spot").view(SNAP_AT);
        assertThat(WorkloadCaptureBuilder.capture(snap, "n1", BEFORE))
                .as("taken after the notice: the pods may already have been evicted")
                .isNull();
    }

    @Test
    void rule2_emptyAtOrBeforeTheNoticeIsARealEmptyList() {
        SnapshotView snap = SpotFixtures.snap().node("n1", "spot")
                .pod("kube-system", "aws-node-x", "n1", "Running", "DaemonSet", "aws-node")
                .pod("batch", "done-1", "n1", "Succeeded", "Job", "done")
                .view(SNAP_AT);
        WorkloadCapture c = WorkloadCaptureBuilder.capture(snap, "n1", AFTER);
        assertThat(c).isNotNull();
        assertThat(c.entries()).isEmpty();
        assertThat(c.podCount()).isZero();
        assertThat(WorkloadCaptureBuilder.capture(snap, "n1", SNAP_AT)).as("at the notice instant").isNotNull();
    }

    @Test
    void rule3_aReplicaSetPodWithoutAnObservedDeploymentListIsUnknown_AC28() {
        // absent
        SpotFixtures.Snap absent = SpotFixtures.snap().node("n1", "spot")
                .pod("team-a", "web-5d9c8f7b6-x", "n1", "Running", "ReplicaSet", "web-5d9c8f7b6").noDeployments();
        assertThat(WorkloadCaptureBuilder.capture(absent.view(SNAP_AT), "n1", AFTER)).isNull();
        // null
        Map<String, Object> withNull = absent.asMap();
        withNull.put("deployments", null);
        SnapshotView nullList = SnapshotView.parse(SpotFixtures.MAPPER, SpotFixtures.json(withNull), SNAP_AT);
        assertThat(WorkloadCaptureBuilder.capture(nullList, "n1", AFTER)).isNull();
        // []
        SnapshotView empty = SpotFixtures.snap().node("n1", "spot")
                .pod("team-a", "web-5d9c8f7b6-x", "n1", "Running", "ReplicaSet", "web-5d9c8f7b6").view(SNAP_AT);
        assertThat(WorkloadCaptureBuilder.capture(empty, "n1", AFTER)).isNull();
    }

    @Test
    void rule3_doesNotApplyToStatefulSetJobOrOwnerlessPods() {
        SnapshotView snap = SpotFixtures.snap().node("n1", "spot").noDeployments()
                .pod("db", "pg-0", "n1", "Running", "StatefulSet", "pg")
                .pod("batch", "migrate-abc", "n1", "Running", "Job", "migrate")
                .pod("dev", "debug", "n1", "Running", null, null)
                .view(SNAP_AT);
        WorkloadCapture c = WorkloadCaptureBuilder.capture(snap, "n1", AFTER);
        assertThat(c).isNotNull();
        assertThat(c.entries()).extracting(WorkloadEntry::kind).containsExactly("StatefulSet", "Job", "Pod");
    }

    @Test
    void rule4_replicaSetPodsBecomeTheirDeployment() {
        SnapshotView snap = SpotFixtures.snap().node("n1", "spot").deployment("team-a", "web", 3, 3)
                .pod("team-a", "web-5d9c8f7b6-x", "n1", "Running", "ReplicaSet", "web-5d9c8f7b6")
                .pod("team-a", "web-5d9c8f7b6-y", "n1", "Running", "ReplicaSet", "web-5d9c8f7b6")
                .pod("team-a", "web-5d9c8f7b6-z", "n2", "Running", "ReplicaSet", "web-5d9c8f7b6")
                .view(SNAP_AT);
        WorkloadCapture c = WorkloadCaptureBuilder.capture(snap, "n1", BEFORE);
        assertThat(c.entries()).containsExactly(new WorkloadEntry("team-a", "Deployment", "web", 2));
        assertThat(c.podCount()).isEqualTo(2);
        assertThat(c.snapshotAt()).isEqualTo(SNAP_AT);
    }

    @Test
    void rule4_fiftyFiveEntriesKeepTheDeploymentAfterTheCapAndTheTrueTotal() {
        SpotFixtures.Snap s = SpotFixtures.snap().node("n1", "spot").deployment("zz", "zz-web", 1, 1);
        for (int i = 0; i < 54; i++) {
            s.pod("aa", String.format("job-%02d-x", i), "n1", "Running", "Job", String.format("job-%02d", i));
        }
        // The only Deployment, last in name order.
        s.pod("zz", "zz-web-5d9c-x", "n1", "Running", "ReplicaSet", "zz-web-5d9c");
        WorkloadCapture c = WorkloadCaptureBuilder.capture(s.view(SNAP_AT), "n1", AFTER);
        assertThat(c.entries()).hasSize(WorkloadCaptureBuilder.MAX_ENTRIES);
        assertThat(c.entries().get(0)).isEqualTo(new WorkloadEntry("zz", "Deployment", "zz-web", 1));
        assertThat(c.podCount()).isEqualTo(55);
    }

    @Test
    void deploymentsAndStatefulSetsComeFirst() {
        SnapshotView snap = SpotFixtures.snap().node("n1", "spot").deployment("b", "web", 1, 1)
                .pod("a", "debug", "n1", "Running", "", "")
                .pod("b", "web-1a-x", "n1", "Running", "ReplicaSet", "web-1a")
                .pod("a", "pg-0", "n1", "Pending", "StatefulSet", "pg")
                .view(SNAP_AT);
        assertThat(WorkloadCaptureBuilder.capture(snap, "n1", AFTER).entries())
                .extracting(WorkloadEntry::kind).containsExactly("StatefulSet", "Deployment", "Pod");
    }
}
