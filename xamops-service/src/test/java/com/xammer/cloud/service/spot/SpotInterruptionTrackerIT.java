package com.xammer.cloud.service.spot;

import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.AgentEvent;
import com.xammer.cloud.domain.K8sSpotInterruptionEvent;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentIngestPayload;
import com.xammer.cloud.dto.agent.AgentIngestResponse;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.JpaSliceTestConfig;
import com.xammer.cloud.repository.K8sSpotInterruptionEventRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.AgentIngestService;
import com.xammer.cloud.service.automation.workload.usage.store.UsageSampleCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * The real write path end to end on H2: {@link AgentIngestService#processIngest} commits, the
 * after-commit {@link SpotInterruptionTracker} turns the pushed data into rows. Not transactional
 * itself ({@code NOT_SUPPORTED}) so the ingest transaction really commits and the listener fires.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AgentIngestService.class, SpotInterruptionTracker.class, SpotInterruptionWriter.class,
        SpotInterruptionProperties.class})
class SpotInterruptionTrackerIT {

    @Autowired
    AgentIngestService ingest;
    @Autowired
    K8sSpotInterruptionEventRepository events;
    @Autowired
    AgentDataCacheRepository cacheRepository;
    @Autowired
    AgentEventRepository agentEventRepository;
    @Autowired
    KubernetesClusterConfigRepository clusterRepository;
    @MockitoSpyBean
    SpotInterruptionWriter writer;
    /** The usage rollup's ingest hook; not under test here. */
    @MockitoBean
    UsageSampleCapture usageSampleCapture;

    private KubernetesClusterConfig cluster;

    @BeforeEach
    void cluster() {
        KubernetesClusterConfig cfg = new KubernetesClusterConfig();
        cfg.setCloudAccountId(1L);
        cfg.setClusterName("xamops-test-1");
        cfg.setRegion("ap-south-1");
        cfg.setKubeconfigYaml("");
        cluster = clusterRepository.save(cfg);
    }

    @AfterEach
    void clean() {
        events.deleteAll();
        cacheRepository.deleteAll();
        agentEventRepository.deleteAll();
        clusterRepository.deleteAll();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private AgentIngestResponse push(String collector, List<Object> items) {
        AgentIngestPayload.DataBatch batch = new AgentIngestPayload.DataBatch();
        batch.setCollector(collector);
        batch.setItems(items);
        AgentIngestPayload payload = new AgentIngestPayload();
        payload.setAgentVersion("1.0.0-autoscaler");
        payload.setBatches(List.of(batch));
        AgentIngestResponse response = ingest.processIngest(cluster, payload, "10.0.0.1");
        assertThat(response.getResults().get(collector).isSuccess()).as(collector + " stored").isTrue();
        return response;
    }

    private AgentIngestResponse pushReport(Map<String, Object> report) {
        return push("node-optimizer-report", List.of(report));
    }

    private void pushSnapshot(SpotFixtures.Snap snap) {
        push("k8s-resources", List.of(snap.asMap()));
    }

    @SafeVarargs
    private void pushActions(Map<String, Object>... records) {
        push("autoscaling-actions", new ArrayList<>(List.of(records)));
    }

    private List<K8sSpotInterruptionEvent> rows() {
        return events.findAll();
    }

    private K8sSpotInterruptionEvent only() {
        List<K8sSpotInterruptionEvent> all = rows();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private K8sSpotInterruptionEvent row(String node) {
        return events.findFirstByClusterConfigIdAndNodeNameAndNodeCreatedAt(cluster.getId(), node,
                SpotFixtures.CREATED_AT).orElseThrow();
    }

    private Instant cacheReceivedAt(String collector) {
        return ServerTime.serverInstant(cacheRepository.findByClusterConfigIdAndDataType(cluster.getId(), collector)
                .orElseThrow().getReceivedAt());
    }

    // ── AC2 / AC24 ───────────────────────────────────────────────────────────

    @Test
    void anAutoscalerNoticeCreatesExactlyOneRow_andTheSameReportAgainNoSecond_AC2() {
        Map<String, Object> report = SpotFixtures.report("simulate", false,
                List.of(SpotFixtures.notice("n1", "interruption", "nth-taint", false)));
        pushReport(report);
        pushReport(report);

        K8sSpotInterruptionEvent row = only();
        assertThat(row.getClusterConfigId()).isEqualTo(cluster.getId());
        assertThat(row.getNoticeKind()).isEqualTo("interruption");
        assertThat(row.getSources()).isEqualTo("nth-taint");
        assertThat(row.getInstanceId()).isEqualTo("i-0abc");
        assertThat(row.getInterruptionNoticedAt()).isEqualTo(row.getNoticedAt());
        assertThat(row.getHandlingMode()).isEqualTo("not-eligible");
    }

    @Test
    void aNoticeForANodeNotInTheCachedSnapshotIsNoSnapshot_andTheNextSnapshotFillsIt_AC24() {
        pushSnapshot(SpotFixtures.snap().node("other", "spot"));
        pushReport(SpotFixtures.report("live", false,
                List.of(SpotFixtures.notice("n1", "interruption", "nth-taint", false))));
        K8sSpotInterruptionEvent row = only();
        assertThat(row.getAffectedWorkloads()).as("unknown, not []").isNull();
        assertThat(row.getRecoveryStatus()).isEqualTo("no-snapshot");

        // Listed after the notice with no qualifying pod: emptiness is not evidence, still unknown.
        pushSnapshot(SpotFixtures.snap().node("other", "spot")
                .node("n1", "spot", "aws-node-termination-handler/spot-itn"));
        assertThat(only().getRecoveryStatus()).isEqualTo("no-snapshot");
        assertThat(only().getAffectedWorkloads()).isNull();

        // Listed with a qualifying pod: filled, now tracking.
        pushSnapshot(SpotFixtures.snap().node("other", "spot")
                .node("n1", "spot", "aws-node-termination-handler/spot-itn")
                .deployment("team-a", "web", 2, 1)
                .pod("team-a", "web-5d9c8f7b6-x", "n1", "Running", "ReplicaSet", "web-5d9c8f7b6"));
        row = only();
        assertThat(WorkloadCapture.fromJson(row.getAffectedWorkloads()))
                .containsExactly(new WorkloadCapture.WorkloadEntry("team-a", "Deployment", "web", 1));
        assertThat(row.getRecoveryStatus()).isEqualTo("tracking");
        assertThat(row.getSources()).isEqualTo("nth-taint");
        assertThat(row.getHandlingMode()).as("the autoscaler's known value is kept").isEqualTo("not-eligible");
    }

    @Test
    void aNoticeForACachedNodeTakesItsWorkloadsFromTheLatestSnapshot() {
        pushSnapshot(SpotFixtures.snap().node("n1", "spot").deployment("team-a", "web", 2, 2)
                .pod("team-a", "web-5d9c8f7b6-x", "n1", "Running", "ReplicaSet", "web-5d9c8f7b6")
                .pod("kube-system", "aws-node-a", "n1", "Running", "DaemonSet", "aws-node"));
        pushReport(SpotFixtures.report("simulate", false,
                List.of(SpotFixtures.notice("n1", "rebalance", "nth-taint", true))));
        K8sSpotInterruptionEvent row = only();
        assertThat(row.getNoticeKind()).isEqualTo("rebalance");
        assertThat(row.getRebalanceNoticedAt()).isNotNull();
        assertThat(row.getHandlingMode()).isEqualTo("simulate");
        assertThat(row.getPodCount()).isEqualTo(1);
        assertThat(row.getWorkloadsSnapshotAt()).isEqualTo(cacheReceivedAt("k8s-resources"));
        assertThat(row.getRecoveryStatus()).isEqualTo("tracking");
    }

    // ── AC17 ────────────────────────────────────────────────────────────────

    @Test
    void handlingIsStoredAsReported_AC17() {
        pushReport(SpotFixtures.report("live", false,
                List.of(SpotFixtures.notice("n1", "interruption", "nth-taint", false))));
        K8sSpotInterruptionEvent n1 = row("n1");
        assertThat(n1.getHandlingMode()).isEqualTo("not-eligible");
        assertThat(n1.getHandlingReason()).startsWith("live:");

        pushReport(SpotFixtures.report("live", true,
                List.of(SpotFixtures.notice("n2", "interruption", "nth-label-legacy", false))));
        assertThat(row("n2").getHandlingMode()).isEqualTo("unknown");
        assertThat(row("n2").getSources()).isEqualTo("nth-label-legacy");
    }

    // ── AC3 ─────────────────────────────────────────────────────────────────

    @Test
    void aKarpenterSpotInterruptedEventCreatesARowAtItsFirstTime_AC3() {
        pushSnapshot(SpotFixtures.snap().node("n1", "spot")
                .event("Warning", "SpotInterrupted", "Node", "n1", "2026-10-24T09:58:00Z")
                .event("Warning", "SpotInterrupted", "Node", "not-in-snapshot", "2026-10-24T09:58:00Z")
                .event("Normal", "SpotInterrupted", "Node", "n1", "2026-10-24T09:57:00Z")
                .event("Warning", "SpotInterrupted", "Pod", "n1", "2026-10-24T09:57:00Z"));
        K8sSpotInterruptionEvent row = only();
        assertThat(row.getNodeName()).isEqualTo("n1");
        assertThat(row.getSources()).isEqualTo("karpenter-event");
        assertThat(row.getNoticeKind()).isEqualTo("interruption");
        assertThat(row.getNoticedAt()).isEqualTo(Instant.parse("2026-10-24T09:58:00Z"));
        assertThat(row.getHandlingMode()).isEqualTo("unknown");
        assertThat(row.getAffectedWorkloads()).as("listed with no pods, but after the notice: unknown, not []").isNull();
    }

    // ── AC4 / AC23 / AC5 ────────────────────────────────────────────────────

    @Test
    void aVanishedSpotNodeIsUnconfirmed_andARecoveredReplacementIsEvaluatedTheSameSnapshot_AC4_AC23() {
        pushSnapshot(SpotFixtures.snap().node("n1", "spot", true, Map.of()).node("keep", "spot")
                .node("od", "on-demand")
                .deployment("team-a", "web", 2, 2)
                .pod("team-a", "web-5d9c8f7b6-x", "n1", "Running", "ReplicaSet", "web-5d9c8f7b6")
                .pod("team-a", "web-5d9c8f7b6-y", "keep", "Running", "ReplicaSet", "web-5d9c8f7b6"));
        Instant t0 = cacheReceivedAt("k8s-resources");
        assertThat(rows()).isEmpty();

        pushSnapshot(SpotFixtures.snap().node("keep", "spot")
                .deployment("team-a", "web", 2, 2)
                .pod("team-a", "web-5d9c8f7b6-y", "keep", "Running", "ReplicaSet", "web-5d9c8f7b6")
                .pod("team-a", "web-5d9c8f7b6-z", "keep", "Running", "ReplicaSet", "web-5d9c8f7b6"));
        K8sSpotInterruptionEvent row = only();
        assertThat(row.getNodeName()).isEqualTo("n1");
        assertThat(row.getNoticeKind()).isEqualTo("unconfirmed");
        assertThat(row.getSources()).isEqualTo("node-removed");
        assertThat(row.getNodeLastSeenAt()).isEqualTo(t0);
        assertThat(row.getNodeRemovedAt()).isEqualTo(row.getNoticedAt());
        assertThat(row.getCordonedBeforeRemoval()).isTrue();
        assertThat(row.getWorkloadsSnapshotAt()).isEqualTo(t0);
        assertThat(row.getRecoveryStatus()).as("evaluated in the same snapshot").isEqualTo("recovered");
        assertThat(row.getRecoveredObservedAt()).isEqualTo(row.getNodeRemovedAt());
        assertThat(row.getRecoveryLastUnrecoveredAt()).as("lower bound defaults to the notice").isNull();
    }

    @Test
    void aRemovalOfANodeWithARowSetsOnlyTheRemovalFields_andCreatesNoRow_AC4() {
        pushSnapshot(SpotFixtures.snap().node("n1", "spot", "karpenter.sh/disrupted").node("keep", "spot"));
        pushReport(SpotFixtures.report("off", false,
                List.of(SpotFixtures.notice("n1", "interruption", "nth-taint", true))));
        K8sSpotInterruptionEvent before = only();
        assertThat(before.getAffectedWorkloads()).isEqualTo("[]");
        assertThat(before.getRecoveryStatus()).isEqualTo("no-workloads");
        Instant t0 = cacheReceivedAt("k8s-resources");

        pushSnapshot(SpotFixtures.snap().node("keep", "spot"));
        K8sSpotInterruptionEvent after = only();
        assertThat(after.getNodeRemovedAt()).isNotNull();
        assertThat(after.getNodeLastSeenAt()).isEqualTo(t0);
        assertThat(after.getCordonedBeforeRemoval()).isFalse();
        assertThat(after.getNoticeKind()).isEqualTo("interruption");
        assertThat(after.getSources()).as("no other field changes").isEqualTo("nth-taint");
        assertThat(after.getNoticedAt()).isEqualTo(before.getNoticedAt());
        assertThat(after.getAffectedWorkloads()).isEqualTo("[]");
        assertThat(after.getRecoveryStatus()).isEqualTo("no-workloads");
        assertThat(after.getHandlingMode()).isEqualTo("off");

        // A later removal sighting keeps the already-set removal time.
        Instant removed = after.getNodeRemovedAt();
        pushSnapshot(SpotFixtures.snap().node("n1", "spot").node("keep", "spot"));
        pushSnapshot(SpotFixtures.snap().node("keep", "spot"));
        assertThat(only().getNodeRemovedAt()).isEqualTo(removed);
    }

    @Test
    void aRemovalWithAnExclusionTaintAndNoRowCreatesNothing_AC5() {
        pushSnapshot(SpotFixtures.snap().node("n1", "spot", "ToBeDeletedByClusterAutoscaler").node("keep", "spot"));
        pushSnapshot(SpotFixtures.snap().node("keep", "spot"));
        assertThat(rows()).isEmpty();
    }

    @Test
    void anEmptyNextSnapshotNeverLooksLikeRemovals() {
        pushSnapshot(SpotFixtures.snap().node("n1", "spot"));
        pushSnapshot(SpotFixtures.snap());
        assertThat(rows()).isEmpty();
    }

    // ── AC28 ────────────────────────────────────────────────────────────────

    @Test
    void aSnapshotWithoutDeploymentsLeavesAReplicaSetPodUnknown_andTheNextOneFillsIt_AC28() {
        pushSnapshot(SpotFixtures.snap().node("n1", "spot", "aws-node-termination-handler/spot-itn").noDeployments()
                .pod("team-a", "web-5d9c8f7b6-x", "n1", "Running", "ReplicaSet", "web-5d9c8f7b6"));
        K8sSpotInterruptionEvent row = only();
        assertThat(row.getAffectedWorkloads()).isNull();
        assertThat(row.getRecoveryStatus()).isEqualTo("no-snapshot");

        pushSnapshot(SpotFixtures.snap().node("n1", "spot", "aws-node-termination-handler/spot-itn")
                .deployment("team-a", "web", 2, 1)
                .pod("team-a", "web-5d9c8f7b6-x", "n1", "Running", "ReplicaSet", "web-5d9c8f7b6"));
        row = only();
        assertThat(WorkloadCapture.fromJson(row.getAffectedWorkloads()))
                .containsExactly(new WorkloadCapture.WorkloadEntry("team-a", "Deployment", "web", 1));
        assertThat(row.getRecoveryStatus()).isEqualTo("tracking");
        assertThat(row.getNodeLastSeenAt()).isNotNull();
    }

    // ── AC7 ─────────────────────────────────────────────────────────────────

    @Test
    void aSpotInterruptionDrainRecordFillsTheDrainOutcome_includingANodeSet_AC7() {
        pushReport(SpotFixtures.report("live", false, List.of(
                SpotFixtures.notice("a", "interruption", "nth-label-legacy", true),
                SpotFixtures.notice("b", "interruption", "nth-label-legacy", true),
                SpotFixtures.notice("c", "interruption", "nth-label-legacy", true))));
        // Microsecond-aligned: Linux clocks give nanoseconds and the DB rounds (not truncates) to micros.
        Instant started = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        pushActions(
                SpotFixtures.action("node/c", "refused", false, "spot-interruption", started.toString(), null),
                SpotFixtures.action("nodes/a,b", "succeeded", false, "spot-interruption", started.toString(),
                        started.plusSeconds(40).toString()),
                SpotFixtures.action("node/zzz", "succeeded", false, "spot-interruption", started.toString(), null));

        for (String node : List.of("a", "b")) {
            K8sSpotInterruptionEvent r = row(node);
            assertThat(r.getDrainStatus()).as(node).isEqualTo("succeeded");
            assertThat(r.getDrainDryRun()).isFalse();
            assertThat(r.getDrainReason()).isEqualTo("drained 1 node");
            assertThat(r.getDrainFinishedAt()).isEqualTo(started.plusSeconds(40).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        }
        assertThat(row("c").getDrainStatus()).isEqualTo("refused");
        assertThat(row("c").getDrainFinishedAt()).isNull();
        assertThat(rows()).hasSize(3);

        // An earlier outcome never replaces a later one.
        pushActions(SpotFixtures.action("node/a", "failed", false, "spot-interruption", started.toString(),
                started.plusSeconds(10).toString()));
        assertThat(row("a").getDrainStatus()).isEqualTo("succeeded");
    }

    @Test
    void aDrainRecordOutsideTheJoinWindowIsIgnored_AC7() {
        pushReport(SpotFixtures.report("live", false,
                List.of(SpotFixtures.notice("a", "interruption", "nth-label-legacy", true))));
        Instant started = Instant.now().minus(Duration.ofMinutes(45));
        pushActions(SpotFixtures.action("node/a", "succeeded", false, "spot-interruption", started.toString(),
                started.plusSeconds(30).toString()));
        assertThat(row("a").getDrainStatus()).isNull();
    }

    // ── AC19 ────────────────────────────────────────────────────────────────

    @Test
    void aLiveXamopsDrainIsCapturedOnce_andItsRemovalIsNotUnconfirmed_AC19() {
        pushSnapshot(SpotFixtures.snap().node("n1", "spot").node("keep", "spot"));
        String started = Instant.now().toString();
        Map<String, Object> drain = SpotFixtures.action("node/n1", "succeeded", false, "idle-drain", started, started);
        pushActions(drain);
        pushActions(drain);

        List<AgentEvent> captured = agentEventRepository.findByClusterConfigIdAndEventTypeAndCreatedAtAfter(
                cluster.getId(), "xamops-node-drained", ServerTime.serverLocal(Instant.now().minus(Duration.ofHours(1))));
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getDetails())
                .isEqualTo("{\"node\":\"n1\",\"trigger\":\"idle-drain\",\"finishedAt\":\"" + started + "\"}");

        // Simulated and non-XamOps-drain records are not captured.
        pushActions(SpotFixtures.action("node/keep", "succeeded", true, "idle-drain", started, started),
                SpotFixtures.action("node/keep", "succeeded", false, "idle-node", started, started));
        assertThat(agentEventRepository.findByClusterConfigIdAndEventTypeAndCreatedAtAfter(
                cluster.getId(), "xamops-node-drained", ServerTime.serverLocal(Instant.now().minus(Duration.ofHours(1)))))
                .hasSize(1);

        // The latest-only actions cache now holds newer, unrelated records; the removal is still excluded.
        pushSnapshot(SpotFixtures.snap().node("keep", "spot"));
        assertThat(rows()).isEmpty();
    }

    // ── AC8 ─────────────────────────────────────────────────────────────────

    @Test
    void aTrackerFailureNeverFailsThePush_AC8() {
        doThrow(new IllegalStateException("simulated tracking failure")).when(writer).upsert(any(), any(), any());
        AgentIngestResponse response = pushReport(SpotFixtures.report("live", false,
                List.of(SpotFixtures.notice("n1", "interruption", "nth-taint", false))));

        verify(writer, atLeastOnce()).upsert(any(), any(), any());
        assertThat(response.isSuccess()).isTrue();
        AgentDataCache cache = cacheRepository.findByClusterConfigIdAndDataType(cluster.getId(), "node-optimizer-report")
                .orElseThrow();
        assertThat(cache.getDataJson()).contains("\"interruptionNotices\"");
        assertThat(rows()).isEmpty();
    }
}
