package com.xammer.cloud.service.spot;

import com.xammer.cloud.domain.K8sSpotInterruptionEvent;
import com.xammer.cloud.service.spot.SpotObservation.SpotKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SpotInterruptionMergeTest {

    private static final Instant T = Instant.parse("2026-10-24T10:00:00Z");
    private static final SpotKey KEY = new SpotKey(7L, "n1", SpotFixtures.CREATED_AT);

    private static SpotObservation obs(String kind, String source, Instant at, SpotHandling handling) {
        return new SpotObservation(KEY, null, "t3.medium", null, "spot", null, kind, source, at, handling,
                null, null, null);
    }

    private static SpotHandling known(String mode) {
        return new SpotHandling(mode, "DRY_RUN=true");
    }

    // ── create / merge ──────────────────────────────────────────────────────

    @Test
    void createCopiesTheObservation() {
        K8sSpotInterruptionEvent row = SpotInterruptionMerge.create(obs("rebalance", "nth-taint", T, known("simulate")));
        assertThat(row.getClusterConfigId()).isEqualTo(7L);
        assertThat(row.getNodeName()).isEqualTo("n1");
        assertThat(row.getNodeCreatedAt()).isEqualTo(SpotFixtures.CREATED_AT);
        assertThat(row.getNoticeKind()).isEqualTo("rebalance");
        assertThat(row.getSources()).isEqualTo("nth-taint");
        assertThat(row.getNoticedAt()).isEqualTo(T);
        assertThat(row.getRebalanceNoticedAt()).isEqualTo(T);
        assertThat(row.getInterruptionNoticedAt()).isNull();
        assertThat(row.getHandlingMode()).isEqualTo("simulate");
        assertThat(row.getAffectedWorkloads()).as("unknown until a snapshot says").isNull();
    }

    @Test
    void earliestTimesWinSourcesAccumulateAndTheKindUpgrades() {
        K8sSpotInterruptionEvent row = SpotInterruptionMerge.create(obs("rebalance", "nth-taint", T, null));
        SpotInterruptionMerge.merge(row, obs("interruption", "karpenter-event", T.minusSeconds(30), null));
        SpotInterruptionMerge.merge(row, obs("interruption", "nth-taint", T.plusSeconds(120), null));
        SpotInterruptionMerge.merge(row, obs("unconfirmed", "node-removed", T.plusSeconds(300), null));
        assertThat(row.getNoticedAt()).isEqualTo(T.minusSeconds(30));
        assertThat(row.getInterruptionNoticedAt()).isEqualTo(T.minusSeconds(30));
        assertThat(row.getRebalanceNoticedAt()).isEqualTo(T);
        assertThat(row.getNoticeKind()).as("never downgraded").isEqualTo("interruption");
        assertThat(row.getSources()).isEqualTo("karpenter-event,node-removed,nth-taint");
    }

    @Test
    void factsAreFilledNeverOverwritten() {
        K8sSpotInterruptionEvent row = SpotInterruptionMerge.create(obs("interruption", "nth-taint", T, null));
        SpotObservation richer = new SpotObservation(KEY, "i-0abc", "m5.large", "ap-south-1b", "on-demand", "ng-1",
                "interruption", "nth-taint", T, null, T.minusSeconds(10), T.plusSeconds(10), true);
        SpotInterruptionMerge.merge(row, richer);
        assertThat(row.getInstanceType()).as("known value kept").isEqualTo("t3.medium");
        assertThat(row.getCapacityType()).isEqualTo("spot");
        assertThat(row.getInstanceId()).isEqualTo("i-0abc");
        assertThat(row.getZone()).isEqualTo("ap-south-1b");
        assertThat(row.getNodegroup()).isEqualTo("ng-1");
        assertThat(row.getNodeLastSeenAt()).isEqualTo(T.minusSeconds(10));
        assertThat(row.getNodeRemovedAt()).isEqualTo(T.plusSeconds(10));
        assertThat(row.getCordonedBeforeRemoval()).isTrue();
        SpotInterruptionMerge.merge(row, new SpotObservation(KEY, null, null, null, null, null, "interruption",
                "nth-taint", T, null, T.plusSeconds(99), T.plusSeconds(99), false));
        assertThat(row.getNodeLastSeenAt()).isEqualTo(T.minusSeconds(10));
        assertThat(row.getCordonedBeforeRemoval()).isTrue();
    }

    // ── handling precedence (AC21) ──────────────────────────────────────────

    @Test
    void agentFirstUnknown_thenSimulateAt50s_isSimulate_thenLiveDoesNotOverwrite() {
        K8sSpotInterruptionEvent row = SpotInterruptionMerge.create(
                obs("interruption", "nth-taint", T, SpotHandling.agentSnapshot()));
        assertThat(row.getHandlingMode()).isEqualTo("unknown");
        SpotInterruptionMerge.merge(row, obs("interruption", "nth-taint", T.plusSeconds(50), known("simulate")));
        assertThat(row.getHandlingMode()).isEqualTo("simulate");
        SpotInterruptionMerge.merge(row, obs("interruption", "nth-taint", T.plusSeconds(110), known("live")));
        assertThat(row.getHandlingMode()).isEqualTo("simulate");
    }

    @Test
    void aKnownValueMoreThanFiveMinutesLaterDoesNotReplaceUnknown() {
        K8sSpotInterruptionEvent row = SpotInterruptionMerge.create(
                obs("interruption", "nth-taint", T, SpotHandling.agentSnapshot()));
        SpotInterruptionMerge.merge(row, obs("interruption", "nth-taint", T.plus(Duration.ofMinutes(6)), known("live")));
        assertThat(row.getHandlingMode()).isEqualTo("unknown");
        SpotInterruptionMerge.merge(row, obs("interruption", "nth-taint", T.plus(Duration.ofMinutes(5)), known("live")));
        assertThat(row.getHandlingMode()).as("exactly 5 min is inside the bound").isEqualTo("live");
    }

    @Test
    void autoscalerFirstLive_thenAgentUnknown_staysLive() {
        K8sSpotInterruptionEvent row = SpotInterruptionMerge.create(obs("interruption", "nth-taint", T, known("live")));
        SpotInterruptionMerge.merge(row, obs("interruption", "nth-taint", T.plusSeconds(200), SpotHandling.agentSnapshot()));
        assertThat(row.getHandlingMode()).isEqualTo("live");
        assertThat(row.getHandlingReason()).isEqualTo("DRY_RUN=true");
    }

    @Test
    void theAnchorIsTheInterruptionNoticeWhenThereIsOne() {
        K8sSpotInterruptionEvent row = SpotInterruptionMerge.create(
                obs("rebalance", "nth-taint", T, SpotHandling.agentSnapshot()));
        SpotInterruptionMerge.merge(row, obs("interruption", "nth-taint", T.plus(Duration.ofMinutes(20)),
                SpotHandling.agentSnapshot()));
        SpotInterruptionMerge.merge(row, obs("interruption", "nth-taint", T.plus(Duration.ofMinutes(21)), known("live")));
        assertThat(row.getHandlingMode()).isEqualTo("live");
    }

    @Test
    void aRowWithNoHandlingTakesWhateverArrives() {
        K8sSpotInterruptionEvent row = SpotInterruptionMerge.create(obs("interruption", "nth-taint", T, null));
        SpotInterruptionMerge.merge(row, obs("interruption", "nth-taint", T.plus(Duration.ofHours(1)),
                SpotHandling.agentSnapshot()));
        assertThat(row.getHandlingMode()).isEqualTo("unknown");
    }

    // ── deriveRecoveryStatus ────────────────────────────────────────────────

    private static K8sSpotInterruptionEvent withWorkloads(String json) {
        K8sSpotInterruptionEvent row = SpotInterruptionMerge.create(obs("interruption", "nth-taint", T, null));
        row.setAffectedWorkloads(json);
        return row;
    }

    private static String entry(String kind, String name) {
        return "{\"namespace\":\"a\",\"kind\":\"" + kind + "\",\"name\":\"" + name + "\",\"podsOnNode\":1}";
    }

    @Test
    void nullWorkloadsIsNoSnapshot_emptyIsNoWorkloads_entriesIsTracking() {
        assertThat(SpotInterruptionMerge.deriveRecoveryStatus(withWorkloads(null))).isEqualTo("no-snapshot");
        assertThat(SpotInterruptionMerge.deriveRecoveryStatus(withWorkloads("[]"))).isEqualTo("no-workloads");
        assertThat(SpotInterruptionMerge.deriveRecoveryStatus(withWorkloads("[" + entry("Deployment", "web") + "]")))
                .isEqualTo("tracking");
        assertThat(SpotInterruptionMerge.deriveRecoveryStatus(withWorkloads("[" + entry("StatefulSet", "pg") + "]")))
                .isEqualTo("tracking");
    }

    @Test
    void noDeploymentOrStatefulSetIsNotMeasurable_AC26() {
        assertThat(SpotInterruptionMerge.deriveRecoveryStatus(withWorkloads("[" + entry("Pod", "debug") + "]")))
                .isEqualTo("not-measurable");
        assertThat(SpotInterruptionMerge.deriveRecoveryStatus(withWorkloads("[" + entry("Job", "migrate") + "]")))
                .isEqualTo("not-measurable");
        assertThat(SpotInterruptionMerge.deriveRecoveryStatus(withWorkloads("[" + entry("ReplicaSet", "batch-6c") + "]")))
                .isEqualTo("not-measurable");
        assertThat(SpotInterruptionMerge.deriveRecoveryStatus(withWorkloads(
                "[" + entry("Deployment", "web") + "," + entry("Pod", "debug") + "]")))
                .isEqualTo("tracking");
    }

    @Test
    void terminalStatusesNeverChange() {
        for (String terminal : SpotInterruptionMerge.TERMINAL) {
            K8sSpotInterruptionEvent row = withWorkloads(null);
            row.setRecoveryStatus(terminal);
            assertThat(SpotInterruptionMerge.deriveRecoveryStatus(row)).isEqualTo(terminal);
        }
    }

    @Test
    void aNoSnapshotRowMovesWhenWorkloadsArrive() {
        K8sSpotInterruptionEvent row = withWorkloads(null);
        row.setRecoveryStatus("no-snapshot");
        row.setAffectedWorkloads("[" + entry("Deployment", "web") + "]");
        assertThat(SpotInterruptionMerge.deriveRecoveryStatus(row)).isEqualTo("tracking");
    }

    // ── fillWorkloadsIfUnknown ──────────────────────────────────────────────

    @Test
    void theProviderIsAskedOnlyForUnknownWorkloadsInsideTheWindow() {
        AtomicInteger calls = new AtomicInteger();
        WorkloadProvider provider = at -> {
            calls.incrementAndGet();
            return new WorkloadCapture(java.util.List.of(), 0, at);
        };
        Duration window = Duration.ofHours(6);

        K8sSpotInterruptionEvent known = withWorkloads("[]");
        assertThat(SpotInterruptionMerge.fillWorkloadsIfUnknown(known, provider, T, window)).isFalse();

        K8sSpotInterruptionEvent old = withWorkloads(null);
        old.setNoticedAt(T.minus(Duration.ofHours(7)));
        assertThat(SpotInterruptionMerge.fillWorkloadsIfUnknown(old, provider, T, window)).isFalse();
        assertThat(calls).hasValue(0);

        K8sSpotInterruptionEvent fresh = withWorkloads(null);
        assertThat(SpotInterruptionMerge.fillWorkloadsIfUnknown(fresh, provider, T, window)).isTrue();
        assertThat(calls).hasValue(1);
        assertThat(fresh.getAffectedWorkloads()).isEqualTo("[]");
        assertThat(fresh.getPodCount()).isZero();

        K8sSpotInterruptionEvent stillUnknown = withWorkloads(null);
        SpotInterruptionMerge.fillWorkloadsIfUnknown(stillUnknown, at -> null, T, window);
        assertThat(stillUnknown.getAffectedWorkloads()).as("a null capture stays unknown").isNull();
    }
}
