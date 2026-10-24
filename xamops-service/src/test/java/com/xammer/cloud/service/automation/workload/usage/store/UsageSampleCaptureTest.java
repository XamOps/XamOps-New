package com.xammer.cloud.service.automation.workload.usage.store;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UsageSampleCaptureTest {

    private static final Instant NOW = Instant.parse("2026-10-24T23:58:30.250Z");
    private static final long CLUSTER = 11L;
    private static final long ACCOUNT = 22L;
    private static final long CLIENT = 33L;

    private final UsageRollupStore store = mock(UsageRollupStore.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TaskExecutor direct = Runnable::run;

    @BeforeEach
    void stubStore() {
        when(store.resolveClientId(CLUSTER)).thenReturn(Optional.of(CLIENT));
        when(store.upsertIdentities(eq(CLIENT), eq(CLUSTER), anyCollection(), any())).thenAnswer(inv -> {
            Collection<UsageRollupStore.IdentityKey> keys = inv.getArgument(2);
            Map<String, Long> ids = new HashMap<>();
            long next = 100;
            for (UsageRollupStore.IdentityKey key : keys) {
                ids.put(key.workloadKey(), next++);
            }
            return ids;
        });
        when(store.insertSamples(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("flag off: neither the executor nor the store is touched")
    void flagOff() {
        TaskExecutor executor = mock(TaskExecutor.class);
        UsageSampleCapture capture = capture(false, executor);

        capture.submit(CLUSTER, ACCOUNT, snapshot(), collectedAt(NOW));

        verifyNoInteractions(executor, store);
    }

    @Test
    @DisplayName("missing arguments or a non-object item are ignored")
    void missingArguments() {
        TaskExecutor executor = mock(TaskExecutor.class);
        UsageSampleCapture capture = capture(true, executor);

        capture.submit(null, ACCOUNT, snapshot(), collectedAt(NOW));
        capture.submit(CLUSTER, null, snapshot(), collectedAt(NOW));
        capture.submit(CLUSTER, ACCOUNT, null, collectedAt(NOW));
        capture.submit(CLUSTER, ACCOUNT, List.of(snapshot()), collectedAt(NOW));
        capture.submit(CLUSTER, ACCOUNT, snapshot(), null);

        verifyNoInteractions(executor, store);
    }

    @Test
    @DisplayName("inside a transaction nothing runs until afterCommit")
    void afterCommitOnly() {
        UsageSampleCapture capture = capture(true, direct);
        TransactionSynchronizationManager.initSynchronization();

        capture.submit(CLUSTER, ACCOUNT, snapshot(), collectedAt(NOW));

        verifyNoInteractions(store);
        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        assertThat(registered).hasSize(1);

        registered.get(0).afterCommit();

        verify(store).insertSamples(anyList());
        assertThat(count(UsageSampleCapture.SAMPLES_WRITTEN)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a full queue drops the snapshot, counts it, and does not throw")
    void rejectedIsDropped() {
        TaskExecutor executor = mock(TaskExecutor.class);
        doThrow(new TaskRejectedException("full")).when(executor).execute(any());
        UsageSampleCapture capture = capture(true, executor);

        assertThatCode(() -> capture.submit(CLUSTER, ACCOUNT, snapshot(), collectedAt(NOW)))
                .doesNotThrowAnyException();
        assertThatCode(() -> capture.submit(CLUSTER, ACCOUNT, snapshot(), collectedAt(NOW)))
                .doesNotThrowAnyException();

        assertThat(count(UsageSampleCapture.DROPPED)).isEqualTo(2.0);
        assertThat(errors("enqueue")).isZero();
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("any other executor failure is counted as an enqueue error, never thrown")
    void executorFailureIsCounted() {
        TaskExecutor executor = mock(TaskExecutor.class);
        doThrow(new IllegalStateException("shut down")).when(executor).execute(any());

        assertThatCode(() -> capture(true, executor).submit(CLUSTER, ACCOUNT, snapshot(), collectedAt(NOW)))
                .doesNotThrowAnyException();

        assertThat(errors("enqueue")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a store failure is counted as a capture error and does not throw")
    void storeFailure() {
        when(store.insertSamples(anyList())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> capture(true, direct).submit(CLUSTER, ACCOUNT, snapshot(), collectedAt(NOW)))
                .doesNotThrowAnyException();

        assertThat(errors("capture")).isEqualTo(1.0);
        assertThat(count(UsageSampleCapture.SAMPLES_WRITTEN)).isZero();
        verify(store, never()).evictCluster(anyLong());
    }

    @Test
    @DisplayName("a foreign-key violation also evicts the cluster's cached identities")
    void foreignKeyViolationEvicts() {
        when(store.insertSamples(anyList())).thenThrow(new DataIntegrityViolationException("fk",
                new SQLException("violates foreign key", "23503")));

        capture(true, direct).submit(CLUSTER, ACCOUNT, snapshot(), collectedAt(NOW));

        assertThat(errors("capture")).isEqualTo(1.0);
        verify(store).evictCluster(CLUSTER);
    }

    @Test
    @DisplayName("an unresolved client writes nothing: a tenant is never guessed")
    void unresolvedClient() {
        when(store.resolveClientId(CLUSTER)).thenReturn(Optional.empty());

        capture(true, direct).submit(CLUSTER, ACCOUNT, snapshot(), collectedAt(NOW));

        verify(store, never()).upsertIdentities(anyLong(), anyLong(), anyCollection(), any());
        verify(store, never()).insertSamples(anyList());
        assertThat(errors("capture")).isZero();
    }

    @Test
    @DisplayName("the client is resolved once per cluster within the cache window")
    void clientCached() {
        UsageSampleCapture capture = capture(true, direct);

        capture.submit(CLUSTER, ACCOUNT, snapshot(), collectedAt(NOW));
        capture.submit(CLUSTER, ACCOUNT, snapshot(), collectedAt(NOW));

        verify(store, times(1)).resolveClientId(CLUSTER);
    }

    @Test
    @DisplayName("collectedAt inside the window is used (to the second); a 1 h old one is replaced by receivedAt")
    void sampledAtWindow() {
        LocalDateTime fiveMinutesAgo = collectedAt(NOW.minusSeconds(300).plusMillis(700));
        capture(true, direct).submit(CLUSTER, ACCOUNT, snapshot(), fiveMinutesAgo);
        assertThat(sampledAts()).containsOnly(Instant.parse("2026-10-24T23:53:30Z"));

        clearInvocations(store);
        capture(true, direct).submit(CLUSTER, ACCOUNT, snapshot(), collectedAt(NOW.minusSeconds(3600)));
        assertThat(sampledAts()).containsOnly(Instant.parse("2026-10-24T23:58:30Z"));

        clearInvocations(store);
        capture(true, direct).submit(CLUSTER, ACCOUNT, snapshot(), collectedAt(NOW.plusSeconds(600)));
        assertThat(sampledAts()).as("too far in the future").containsOnly(Instant.parse("2026-10-24T23:58:30Z"));
    }

    @Test
    @DisplayName("the sample partition of the sample's UTC day is ensured before inserting")
    void ensuresUtcDayPartition() {
        // 23:58Z on the 24th is already the 25th in +05:30; the partition must follow UTC.
        capture(true, direct).submit(CLUSTER, ACCOUNT, snapshot(), collectedAt(NOW));

        InOrder order = inOrder(store);
        order.verify(store).ensurePartition(UsagePartitions.Table.SAMPLE, LocalDate.of(2026, 10, 24));
        order.verify(store).insertSamples(anyList());
    }

    @Test
    @DisplayName("rows carry the resolved client, the cluster and the extracted metrics")
    void rowsCarryTenant() {
        capture(true, direct).submit(CLUSTER, ACCOUNT, snapshot(), collectedAt(NOW));

        List<UsageRollupStore.SampleRow> rows = insertedRows();
        assertThat(rows).hasSize(2).allSatisfy(r -> {
            assertThat(r.clientId()).isEqualTo(CLIENT);
            assertThat(r.clusterConfigId()).isEqualTo(CLUSTER);
            assertThat(r.runningReplicas()).isEqualTo(2);
            assertThat(r.cpuM()).isEqualTo(1.234567);
            assertThat(r.memB()).isEqualTo(35651584L);
        });
        assertThat(rows).extracting(UsageRollupStore.SampleRow::workloadId).containsOnly(100L);
    }

    @Test
    @DisplayName("a replayed push with a skewed collectedAt is captured once, though receivedAt differs per attempt")
    void replayWithSkewedCollectedAtIsCapturedOnce() {
        UsageSampleCapture capture = capture(true, direct);
        LocalDateTime skewed = collectedAt(NOW.minusSeconds(3600));

        capture.process(CLUSTER, snapshot(), skewed, NOW);
        capture.process(CLUSTER, snapshot(), skewed, NOW.plusSeconds(30));

        verify(store, times(1)).insertSamples(anyList());
        assertThat(count(UsageSampleCapture.SAMPLES_WRITTEN)).isEqualTo(2.0);

        capture.process(CLUSTER, snapshot(), collectedAt(NOW.minusSeconds(3300)), NOW.plusSeconds(300));
        verify(store, times(2)).insertSamples(anyList());
        // Per cluster: another cluster's snapshot with the same collectedAt is not a replay.
        capture.process(CLUSTER + 1, snapshot(), skewed, NOW.plusSeconds(60));
        verify(store).resolveClientId(CLUSTER + 1);
    }

    @Test
    @DisplayName("a failed capture is not remembered: the retry of that snapshot is written")
    void failedCaptureIsRetried() {
        when(store.insertSamples(anyList())).thenThrow(new IllegalStateException("db down"))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        UsageSampleCapture capture = capture(true, direct);
        LocalDateTime skewed = collectedAt(NOW.minusSeconds(3600));

        capture.process(CLUSTER, snapshot(), skewed, NOW);
        capture.process(CLUSTER, snapshot(), skewed, NOW.plusSeconds(30));

        verify(store, times(2)).insertSamples(anyList());
        assertThat(errors("capture")).isEqualTo(1.0);
        assertThat(count(UsageSampleCapture.SAMPLES_WRITTEN)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("the queued task holds its own compact copy, not the ingest's snapshot map")
    void queuedTaskHoldsACompactCopy() {
        List<Runnable> queued = new java.util.ArrayList<>();
        UsageSampleCapture capture = capture(true, queued::add);
        Map<String, Object> raw = new HashMap<>(snapshot());
        raw.put("events", List.of(Map.of("message", "x".repeat(1000))));

        capture.submit(CLUSTER, ACCOUNT, raw, collectedAt(NOW));
        raw.clear();
        assertThat(queued).hasSize(1);
        queued.get(0).run();

        assertThat(insertedRows()).hasSize(2);
    }

    @Test
    @DisplayName("the executor bean is one thread with AbortPolicy and the configured queue")
    void executorBean() {
        ThreadPoolTaskExecutor executor = new UsageRollupConfig().usageRollupExecutor(properties(true));
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getQueueCapacity()).isEqualTo(50);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            executor.shutdown();
        }
    }

    // ---------------------------------------------------------------- helpers

    private UsageSampleCapture capture(boolean enabled, TaskExecutor executor) {
        return new UsageSampleCapture(properties(enabled), store, executor, registry,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static UsageRollupProperties properties(boolean enabled) {
        return new UsageRollupProperties(enabled, 90, 730, 48, 2, 3, 3, 50,
                UsageRollupProperties.DEFAULT_HOURLY_CRON, UsageRollupProperties.DEFAULT_MAINTENANCE_CRON);
    }

    private static LocalDateTime collectedAt(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private double count(String name) {
        var counter = registry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    private double errors(String stage) {
        var counter = registry.find(UsageSampleCapture.ERRORS).tag("stage", stage).counter();
        return counter == null ? 0 : counter.count();
    }

    @SuppressWarnings("unchecked")
    private List<UsageRollupStore.SampleRow> insertedRows() {
        ArgumentCaptor<List<UsageRollupStore.SampleRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).insertSamples(captor.capture());
        return captor.getValue();
    }

    private List<Instant> sampledAts() {
        return insertedRows().stream().map(UsageRollupStore.SampleRow::sampledAt).toList();
    }

    /** Two Running pods of Deployment web, one container each, in the agent's JSON shape. */
    private static Map<String, Object> snapshot() {
        return Map.of(
                "pods", List.of(pod("web-5f6d7-aaaaa"), pod("web-5f6d7-bbbbb")),
                "podMetrics", List.of(metrics("web-5f6d7-aaaaa"), metrics("web-5f6d7-bbbbb")));
    }

    private static Map<String, Object> pod(String name) {
        return Map.of("name", name, "namespace", "shop", "phase", "Running",
                "labels", Map.of("pod-template-hash", "5f6d7"),
                "controllerKind", "ReplicaSet", "controllerName", "web-5f6d7",
                "containers", List.of(Map.of("name", "app", "cpuRequest", "100m", "cpuLimit", "",
                        "memoryRequest", "64Mi", "memoryLimit", "")));
    }

    private static Map<String, Object> metrics(String podName) {
        return Map.of("podName", podName, "namespace", "shop",
                "containers", List.of(Map.of("name", "app", "cpuUsage", "1234567n", "memoryUsage", "34Mi")));
    }
}
