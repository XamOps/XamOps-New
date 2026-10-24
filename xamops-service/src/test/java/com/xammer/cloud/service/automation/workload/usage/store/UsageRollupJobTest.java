package com.xammer.cloud.service.automation.workload.usage.store;

import com.xammer.cloud.service.automation.workload.usage.store.UsagePartitions.Table;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.HourlyRow;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.PartitionLockTimeoutException;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.SampleRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UsageRollupJobTest {

    private static final long MI = 1024L * 1024L;
    private static final String SAMPLE = Table.SAMPLE.parent();
    private static final String HOURLY = Table.HOURLY.parent();
    private static final String DAILY = Table.DAILY.parent();

    private final UsageRollupStore store = mock(UsageRollupStore.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    /** Runs the callback directly, like a transaction that always commits. */
    private final TransactionOperations transactions = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> callback) {
            return callback.doInTransaction(new SimpleTransactionStatus());
        }
    };

    @BeforeEach
    void lockIsFree() {
        when(store.tryAdvisoryXactLock(anyLong())).thenReturn(true);
    }

    // ---------------------------------------------------------------- wiring

    @Test
    @DisplayName("both jobs are @Scheduled in UTC on the property-driven crons; startup listens for ApplicationReadyEvent")
    void scheduling() throws NoSuchMethodException {
        Scheduled hourly = UsageRollupJob.class.getMethod("runHourly").getAnnotation(Scheduled.class);
        Scheduled maintenance = UsageRollupJob.class.getMethod("runMaintenance").getAnnotation(Scheduled.class);
        assertThat(hourly.zone()).isEqualTo("UTC");
        assertThat(hourly.cron()).isEqualTo("${xamops.k8s.usage-rollup.hourly-cron:0 7 * * * *}");
        assertThat(maintenance.zone()).isEqualTo("UTC");
        assertThat(maintenance.cron()).isEqualTo("${xamops.k8s.usage-rollup.maintenance-cron:0 30 1 * * *}");
        Method ready = UsageRollupJob.class.getMethod("onApplicationReady");
        assertThat(ready.getAnnotation(EventListener.class).value()).containsExactly(ApplicationReadyEvent.class);
    }

    @Test
    @DisplayName("flag off: neither job nor the startup hook touches the store")
    void flagOff() {
        UsageRollupJob job = job(props(false, 90), "2026-10-24T05:07:00Z");
        job.runHourly();
        job.runMaintenance();
        job.onApplicationReady();
        verifyNoInteractions(store);
    }

    // ---------------------------------------------------------------- hourly

    @Test
    @DisplayName("at 05:07Z the hourly window is [02:00Z, 05:00Z)")
    void hourlyWindow() {
        when(store.clustersWithUsage()).thenReturn(List.of(1L, 2L));
        UsageRollupJob job = job(props(true, 90), "2026-10-24T05:07:00Z");
        job.runHourly();
        verify(store).readSamples(1L, Instant.parse("2026-10-24T02:00:00Z"), Instant.parse("2026-10-24T05:00:00Z"));
        verify(store).readSamples(2L, Instant.parse("2026-10-24T02:00:00Z"), Instant.parse("2026-10-24T05:00:00Z"));
        verify(store, never()).upsertHourly(any());
    }

    @Test
    @DisplayName("samples are grouped by (workload, UTC hour); a rerun over the same samples writes identical rows")
    void rerunIsIdempotent() {
        Instant start = Instant.parse("2026-10-24T02:00:00Z");
        Instant end = Instant.parse("2026-10-24T05:00:00Z");
        List<SampleRow> samples = List.of(
                sample(1, 10, "2026-10-24T03:55:00Z", 100, 2),
                sample(1, 10, "2026-10-24T03:55:00Z", 120, 2),
                sample(1, 10, "2026-10-24T04:00:00Z", 140, 2),
                sample(1, 11, "2026-10-24T04:30:00Z", 50, 1));
        when(store.clustersWithUsage()).thenReturn(List.of(1L));
        when(store.readSamples(1L, start, end)).thenReturn(samples);

        UsageRollupJob job = job(props(true, 90), "2026-10-24T05:07:00Z");
        job.runHourly();
        job.runHourly();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HourlyRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(store, times(2)).upsertHourly(rows.capture());
        List<HourlyRow> first = rows.getAllValues().get(0);
        assertThat(first).hasSize(3);
        assertThat(rows.getAllValues().get(1)).as("recompute, never accumulate").isEqualTo(first);
        HourlyRow h3 = first.stream().filter(r -> r.workloadId() == 10 && r.hour().equals(
                Instant.parse("2026-10-24T03:00:00Z"))).findFirst().orElseThrow();
        assertThat(h3.sampleCount()).isEqualTo(2);
        assertThat(h3.snapshotCount()).isEqualTo(1);
        assertThat(h3.cpuMaxM()).isEqualTo(120.0);
        assertThat(h3.clientId()).isEqualTo(5L);
        verify(store, times(2)).ensurePartition(Table.HOURLY, LocalDate.of(2026, 10, 1));
        verify(store, times(2)).touchLastSeen(eq(1L), eq(Set.of(10L, 11L)), eq(Instant.parse("2026-10-24T04:30:00Z")));
    }

    @Test
    @DisplayName("cluster 1 failing does not stop cluster 2, and counts errors{stage=hourly}")
    void clusterFailureIsIsolated() {
        Instant start = Instant.parse("2026-10-24T02:00:00Z");
        Instant end = Instant.parse("2026-10-24T05:00:00Z");
        when(store.clustersWithUsage()).thenReturn(List.of(1L, 2L));
        when(store.readSamples(1L, start, end)).thenThrow(new IllegalStateException("boom"));
        when(store.readSamples(2L, start, end)).thenReturn(List.of(sample(2, 20, "2026-10-24T04:10:00Z", 5, 1)));

        UsageRollupJob job = job(props(true, 90), "2026-10-24T05:07:00Z");
        assertThatCode(job::runHourly).doesNotThrowAnyException();

        verify(store).upsertHourly(any());
        assertThat(errors("hourly")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a store failure before any cluster never escapes runHourly")
    void hourlyNeverThrows() {
        when(store.clustersWithUsage()).thenThrow(new IllegalStateException("db down"));
        assertThatCode(job(props(true, 90), "2026-10-24T05:07:00Z")::runHourly).doesNotThrowAnyException();
        assertThat(errors("hourly")).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------- maintenance: partitions

    @Test
    @DisplayName("on 2026-12-30 maintenance ensures Dec..Feb hourly + daily and samples Dec 30 .. Jan 6 (crosses the year)")
    void ensuresPartitionsAcrossTheYear() {
        UsageRollupJob job = job(props(true, 90), "2026-12-30T01:30:00Z");
        job.runMaintenance();
        assertThat(ensured()).containsExactlyInAnyOrder(
                "k8s_workload_usage_hourly_y2026m12", "k8s_workload_usage_hourly_y2027m01",
                "k8s_workload_usage_hourly_y2027m02",
                "k8s_workload_usage_daily_y2026m12", "k8s_workload_usage_daily_y2027m01",
                "k8s_workload_usage_daily_y2027m02",
                "k8s_workload_usage_sample_d20261230", "k8s_workload_usage_sample_d20261231",
                "k8s_workload_usage_sample_d20270101", "k8s_workload_usage_sample_d20270102",
                "k8s_workload_usage_sample_d20270103", "k8s_workload_usage_sample_d20270104",
                "k8s_workload_usage_sample_d20270105", "k8s_workload_usage_sample_d20270106");
        verify(store, times(14)).tryAdvisoryXactLock(UsageRollupJob.DDL_LOCK_KEY);
    }

    @Test
    @DisplayName("the startup hook ensures the same partitions")
    void startupEnsuresPartitions() {
        job(props(true, 90), "2026-12-30T08:00:00Z").onApplicationReady();
        assertThat(ensured()).hasSize(14).contains("k8s_workload_usage_sample_d20270106");
        verify(store, never()).dropPartition(anyString());
    }

    @Test
    @DisplayName("a create that gives up on lock_timeout is skipped, not failed: the others are still ensured")
    void lockTimeoutIsSkippedNotFailed() {
        doThrow(new PartitionLockTimeoutException("k8s_workload_usage_sample_d20261231", null))
                .when(store).ensurePartition(Table.SAMPLE, LocalDate.of(2026, 12, 31));
        UsageRollupJob job = job(props(true, 90), "2026-12-30T01:30:00Z");

        assertThatCode(job::runMaintenance).doesNotThrowAnyException();
        assertThatCode(job::onApplicationReady).doesNotThrowAnyException();

        verify(store, times(2)).ensurePartition(Table.SAMPLE, LocalDate.of(2027, 1, 6));
        verify(store, times(2)).ensurePartition(Table.HOURLY, LocalDate.of(2027, 2, 1));
        assertThat(errors("maintenance")).as("a lock timeout is retried next run, not an error").isZero();
    }

    @Test
    @DisplayName("a daily-partition create that gives up on lock_timeout skips that day, the others still roll")
    void dailyLockTimeoutSkipsTheDay() {
        when(store.clustersWithUsage()).thenReturn(List.of(3L));
        Instant oct22 = Instant.parse("2026-10-22T00:00:00Z");
        Instant oct23 = Instant.parse("2026-10-23T00:00:00Z");
        Instant oct24 = Instant.parse("2026-10-24T00:00:00Z");
        when(store.readHourly(3L, oct22, oct23)).thenReturn(List.of(hourly(3, 30, "2026-10-22T10:00:00Z")));
        when(store.readHourly(3L, oct23, oct24)).thenReturn(List.of(hourly(3, 30, "2026-10-23T10:00:00Z")));
        doThrow(new PartitionLockTimeoutException("k8s_workload_usage_daily_y2026m10", null))
                .when(store).ensurePartition(Table.DAILY, LocalDate.of(2026, 10, 22));

        job(props(true, 90), "2026-10-24T01:30:00Z").runMaintenance();

        verify(store, times(1)).upsertDaily(any());
        assertThat(errors("maintenance")).isZero();
    }

    @Test
    @DisplayName("advisory lock held elsewhere: no DDL at all, and no exception")
    void lockHeldElsewhereSkipsDdl() {
        when(store.tryAdvisoryXactLock(anyLong())).thenReturn(false);
        when(store.listPartitions(SAMPLE)).thenReturn(List.of("k8s_workload_usage_sample_d20261001"));
        when(store.listPartitions(HOURLY)).thenReturn(List.of("k8s_workload_usage_hourly_y2026m07"));
        when(store.listPartitions(DAILY)).thenReturn(List.of("k8s_workload_usage_daily_y2020m01"));

        UsageRollupJob job = job(props(true, 90), "2026-11-05T01:30:00Z");
        assertThatCode(job::runMaintenance).doesNotThrowAnyException();
        assertThatCode(job::onApplicationReady).doesNotThrowAnyException();

        verify(store, never()).ensurePartition(any(), any());
        verify(store, never()).dropPartition(anyString());
    }

    // ---------------------------------------------------------------- maintenance: retention

    @Test
    @DisplayName("sample partitions whose upper bound is 48 h or more ago are dropped, newer ones kept")
    void samplesRetention() {
        // now 2026-11-05T01:30Z -> cutoff 2026-11-03T01:30Z
        when(store.listPartitions(SAMPLE)).thenReturn(List.of(
                "k8s_workload_usage_sample_d20261101", // upper 11-02T00:00 -> drop
                "k8s_workload_usage_sample_d20261102", // upper 11-03T00:00 -> drop
                "k8s_workload_usage_sample_d20261103", // upper 11-04T00:00 -> keep
                "k8s_workload_usage_sample_d20261105"));
        job(props(true, 90), "2026-11-05T01:30:00Z").runMaintenance();
        verify(store).dropPartition("k8s_workload_usage_sample_d20261101");
        verify(store).dropPartition("k8s_workload_usage_sample_d20261102");
        verify(store, never()).dropPartition("k8s_workload_usage_sample_d20261103");
        verify(store, never()).dropPartition("k8s_workload_usage_sample_d20261105");
    }

    @Test
    @DisplayName("hourly y2026m07 at 2026-11-05 is dropped once countUnrolledDays is 0, after re-running July's daily rollup")
    void agedHourlyDroppedWhenVerified() {
        when(store.clustersWithUsage()).thenReturn(List.of(3L));
        when(store.listPartitions(HOURLY)).thenReturn(List.of(
                "k8s_workload_usage_hourly_y2026m07", "k8s_workload_usage_hourly_y2026m08"));
        when(store.countUnrolledDays("k8s_workload_usage_hourly_y2026m07")).thenReturn(0L);

        job(props(true, 90), "2026-11-05T01:30:00Z").runMaintenance();

        verify(store).dropPartition("k8s_workload_usage_hourly_y2026m07");
        verify(store, never()).dropPartition("k8s_workload_usage_hourly_y2026m08");
        verify(store, never()).countUnrolledDays("k8s_workload_usage_hourly_y2026m08");
        // Every day of July was re-rolled before the check.
        verify(store).readHourly(3L, Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z"));
        verify(store).readHourly(3L, Instant.parse("2026-07-31T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(errors("maintenance")).isZero();
    }

    @Test
    @DisplayName("hourly y2026m07 is KEPT, with an error counted, while countUnrolledDays reports 5")
    void agedHourlyKeptWhenUnverified() {
        when(store.listPartitions(HOURLY)).thenReturn(List.of("k8s_workload_usage_hourly_y2026m07"));
        when(store.countUnrolledDays("k8s_workload_usage_hourly_y2026m07")).thenReturn(5L);

        job(props(true, 90), "2026-11-05T01:30:00Z").runMaintenance();

        verify(store).countUnrolledDays("k8s_workload_usage_hourly_y2026m07");
        verify(store, never()).dropPartition("k8s_workload_usage_hourly_y2026m07");
        assertThat(errors("maintenance")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the July re-roll writes daily rows from the hourly rows it reads")
    void agedHourlyReRollWritesDaily() {
        Instant julyFirst = Instant.parse("2026-07-01T00:00:00Z");
        Instant julySecond = Instant.parse("2026-07-02T00:00:00Z");
        when(store.listPartitions(HOURLY)).thenReturn(List.of("k8s_workload_usage_hourly_y2026m07"));
        when(store.clustersWithUsage()).thenReturn(List.of(3L));
        when(store.readHourly(3L, julyFirst, julySecond)).thenReturn(List.of(
                hourly(3, 30, "2026-07-01T10:00:00Z"), hourly(3, 30, "2026-07-01T11:00:00Z")));

        job(props(true, 90), "2026-11-05T01:30:00Z").runMaintenance();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UsageRollupStore.DailyRow>> daily = ArgumentCaptor.forClass(List.class);
        verify(store).upsertDaily(daily.capture());
        assertThat(daily.getValue()).singleElement().satisfies(row -> {
            assertThat(row.day()).isEqualTo(LocalDate.of(2026, 7, 1));
            assertThat(row.hoursCovered()).isEqualTo(2);
            assertThat(row.clusterConfigId()).isEqualTo(3L);
            assertThat(row.workloadId()).isEqualTo(30L);
        });
        verify(store).ensurePartition(Table.DAILY, LocalDate.of(2026, 7, 1));
    }

    @Test
    @DisplayName("daily partitions whose upper bound is 730 days or more ago are dropped")
    void dailyRetention() {
        // now 2026-11-05T01:30Z -> cutoff 2024-11-05T01:30Z
        when(store.listPartitions(DAILY)).thenReturn(List.of(
                "k8s_workload_usage_daily_y2024m10", // upper 2024-11-01 -> drop
                "k8s_workload_usage_daily_y2024m11")); // upper 2024-12-01 -> keep
        job(props(true, 90), "2026-11-05T01:30:00Z").runMaintenance();
        verify(store).dropPartition("k8s_workload_usage_daily_y2024m10");
        verify(store, never()).dropPartition("k8s_workload_usage_daily_y2024m11");
    }

    @Test
    @DisplayName("hourly-retention-days 120 keeps y2026m07 at 2026-11-05, which 90 would drop")
    void longerHourlyRetentionKeeps() {
        when(store.listPartitions(HOURLY)).thenReturn(List.of("k8s_workload_usage_hourly_y2026m07"));
        job(props(true, 120), "2026-11-05T01:30:00Z").runMaintenance();
        verify(store, never()).countUnrolledDays(anyString());
        verify(store, never()).dropPartition(anyString());
    }

    @Test
    @DisplayName("partition names this code did not produce are never dropped or verified")
    void foreignPartitionsNeverDropped() {
        when(store.listPartitions(SAMPLE)).thenReturn(List.of(
                "k8s_workload_usage_sample_d20200101_bak", "k8s_workload_usage_sample_old"));
        when(store.listPartitions(HOURLY)).thenReturn(List.of(
                "k8s_workload_usage_hourly_old", "k8s_workload_usage_hourly_y2026m13",
                "k8s_workload_usage_hourly_y2020m1", "k8s_workload_usage_daily_y2020m01"));
        when(store.listPartitions(DAILY)).thenReturn(List.of(
                "k8s_workload_usage_daily_2020", "k8s_workload_usage_hourly_y2020m01"));
        job(props(true, 90), "2026-11-05T01:30:00Z").runMaintenance();
        verify(store, never()).dropPartition(anyString());
        verify(store, never()).countUnrolledDays(anyString());
    }

    @Test
    @DisplayName("one failing maintenance step does not stop the next, and nothing escapes")
    void maintenanceStepsAreIsolated() {
        when(store.listPartitions(SAMPLE)).thenThrow(new IllegalStateException("catalog read failed"));
        when(store.listPartitions(DAILY)).thenReturn(List.of("k8s_workload_usage_daily_y2024m10"));
        UsageRollupJob job = job(props(true, 90), "2026-11-05T01:30:00Z");
        assertThatCode(job::runMaintenance).doesNotThrowAnyException();
        verify(store).dropPartition("k8s_workload_usage_daily_y2024m10");
        verify(store, atLeastOnce()).listPartitions(HOURLY);
        assertThat(errors("maintenance")).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------- fixtures

    private UsageRollupJob job(UsageRollupProperties properties, String now) {
        return new UsageRollupJob(properties, store, registry, transactions,
                Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    private static UsageRollupProperties props(boolean enabled, int hourlyRetentionDays) {
        return new UsageRollupProperties(enabled, hourlyRetentionDays, 730, 48, 2, 3, 3, 50,
                UsageRollupProperties.DEFAULT_HOURLY_CRON, UsageRollupProperties.DEFAULT_MAINTENANCE_CRON);
    }

    private double errors(String stage) {
        return registry.get(UsageSampleCapture.ERRORS).tag("stage", stage).counter().count();
    }

    private Set<String> ensured() {
        ArgumentCaptor<Table> tables = ArgumentCaptor.forClass(Table.class);
        ArgumentCaptor<LocalDate> periods = ArgumentCaptor.forClass(LocalDate.class);
        verify(store, atLeastOnce()).ensurePartition(tables.capture(), periods.capture());
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tables.getAllValues().size(); i++) {
            names.add(UsagePartitions.partitionName(tables.getAllValues().get(i), periods.getAllValues().get(i)));
        }
        assertThat(names).as("each partition ensured once").doesNotHaveDuplicates();
        return names.stream().collect(Collectors.toSet());
    }

    private static SampleRow sample(long cluster, long workload, String at, double cpuM, int replicas) {
        return new SampleRow(cluster, 5L, workload, Instant.parse(at), 64 * MI, null, 128 * MI, cpuM, 100.0, null,
                (long) (cpuM * 7), replicas);
    }

    private static HourlyRow hourly(long cluster, long workload, String hour) {
        return new HourlyRow(cluster, 5L, workload, Instant.parse(hour), 10 * MI, 12 * MI, 14 * MI, null, null,
                1.5, 2.5, 3.5, 100.0, null, 2.0, 24, 12, 2, 2);
    }
}
