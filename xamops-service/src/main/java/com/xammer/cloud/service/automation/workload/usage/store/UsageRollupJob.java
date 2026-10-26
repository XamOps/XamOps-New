package com.xammer.cloud.service.automation.workload.usage.store;

import com.xammer.cloud.service.automation.workload.usage.store.UsagePartitions.Partition;
import com.xammer.cloud.service.automation.workload.usage.store.UsagePartitions.Table;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.DailyRow;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.HourlyRow;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.PartitionLockTimeoutException;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.SampleRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The two scheduled usage-rollup jobs. Both run in UTC, both return at once while
 * {@link UsageRollupProperties#enabled()} is false, and neither ever throws out of its
 * {@code @Scheduled} method.
 *
 * <h2>{@link #runHourly()}</h2>
 *
 * Recomputes the last {@code rollup-lookback-hours} closed UTC hours from the staged samples and
 * upserts one hourly row per (workload-container, hour). An upsert replaces the row, so a rerun, or a
 * late sample folded into an hour on the next run, never double counts. Each cluster runs in its own
 * try/catch.
 *
 * <h2>{@link #runMaintenance()}</h2>
 *
 * <ol type="a">
 *   <li>ensure partitions: hourly and daily for the current UTC month and {@code premake-months}
 *       ahead, samples for today .. today + {@value #SAMPLE_PREMAKE_DAYS}</li>
 *   <li>daily downsample of the last {@code daily-lookback-days} closed UTC days</li>
 *   <li>drop sample partitions older than {@code sample-retention-hours}</li>
 *   <li>for each hourly partition older than {@code hourly-retention-days}: re-run the daily rollup of
 *       every day of that month, then drop it only when {@link UsageRollupStore#countUnrolledDays} is 0;
 *       otherwise keep it, log ERROR and count</li>
 *   <li>drop daily partitions older than {@code daily-retention-days}</li>
 * </ol>
 *
 * Every DDL statement runs in its own short transaction that first takes
 * {@code pg_try_advisory_xact_lock(}{@value #DDL_LOCK_KEY}{@code )} and skips the statement when
 * another instance holds it. A partition create also gives up after the store's {@code lock_timeout}
 * (it locks {@code client} and {@code kubernetes_cluster_config}, which agent ingest writes); that is
 * counted as skipped, not failed, and retried on the next run, which the week of pre-made sample
 * partitions absorbs. Only partitions whose names {@link UsagePartitions#parse} accepts are ever
 * considered for a drop. Step (a) also runs once on {@link ApplicationReadyEvent}.
 *
 * <p>Real data only: nothing is backfilled or synthesised; an hour without samples has no row.
 * Logs name clusters by config id only.
 */
@Service
public class UsageRollupJob {

    private static final Logger logger = LoggerFactory.getLogger(UsageRollupJob.class);

    /** Advisory lock serialising partition DDL across instances ("XAMOPSUR" in ASCII). */
    static final long DDL_LOCK_KEY = 0x58414D4F50535552L;
    /**
     * Sample partitions pre-created beyond today: a week, so several runs in a row can skip a create
     * on a lock timeout before the capture path ever has to create one on demand.
     */
    static final int SAMPLE_PREMAKE_DAYS = 7;

    private final UsageRollupProperties properties;
    private final UsageRollupStore store;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final Counter hourlyErrors;
    private final Counter maintenanceErrors;

    @Autowired
    public UsageRollupJob(UsageRollupProperties properties, UsageRollupStore store,
                          ObjectProvider<MeterRegistry> meterRegistry,
                          PlatformTransactionManager transactionManager) {
        this(properties, store, meterRegistry.getIfAvailable(), new TransactionTemplate(transactionManager),
                Clock.systemUTC());
    }

    /** For tests: explicit (nullable) registry, transaction operations and a pinned clock. */
    UsageRollupJob(UsageRollupProperties properties, UsageRollupStore store, MeterRegistry meterRegistry,
                   TransactionOperations transactions, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
        MeterRegistry registry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
        this.hourlyErrors = Counter.builder(UsageSampleCapture.ERRORS).tag("stage", "hourly").register(registry);
        this.maintenanceErrors = Counter.builder(UsageSampleCapture.ERRORS).tag("stage", "maintenance")
                .register(registry);
    }

    // ---------------------------------------------------------------- hourly

    /** Hourly rollup of the last closed hours. Never throws. */
    @Scheduled(cron = "${" + UsageRollupProperties.HOURLY_CRON_KEY + ":" + UsageRollupProperties.DEFAULT_HOURLY_CRON
            + "}", zone = "UTC")
    public void runHourly() {
        if (!properties.enabled()) {
            return;
        }
        try {
            Instant end = UsageRollupMath.hourOf(clock.instant());
            Instant start = end.minus(Duration.ofHours(properties.rollupLookbackHours()));
            int clusters = 0;
            int rows = 0;
            int failures = 0;
            for (Long clusterConfigId : store.clustersWithUsage()) {
                try {
                    int written = rollupCluster(clusterConfigId, start, end);
                    if (written > 0) {
                        clusters++;
                        rows += written;
                    }
                } catch (RuntimeException e) {
                    failures++;
                    hourlyErrors.increment();
                    logger.warn("Usage rollup: hourly rollup failed for cluster config {}: {}",
                            clusterConfigId, e.toString());
                }
            }
            logger.info("Usage rollup hourly [{}, {}): {} clusters, {} hourly rows, {} failures",
                    start, end, clusters, rows, failures);
        } catch (RuntimeException e) {
            hourlyErrors.increment();
            logger.warn("Usage rollup: hourly run failed before reaching any cluster: {}", e.toString());
        }
    }

    private record HourKey(long clientId, long workloadId, Instant hour) {
    }

    private int rollupCluster(long clusterConfigId, Instant start, Instant end) {
        List<SampleRow> samples = store.readSamples(clusterConfigId, start, end);
        if (samples.isEmpty()) {
            return 0;
        }
        Map<HourKey, List<SampleRow>> groups = new LinkedHashMap<>();
        Set<Long> workloadIds = new LinkedHashSet<>();
        Instant lastSampled = samples.get(0).sampledAt();
        for (SampleRow s : samples) {
            groups.computeIfAbsent(new HourKey(s.clientId(), s.workloadId(), UsageRollupMath.hourOf(s.sampledAt())),
                    k -> new ArrayList<>()).add(s);
            workloadIds.add(s.workloadId());
            if (s.sampledAt().isAfter(lastSampled)) {
                lastSampled = s.sampledAt();
            }
        }
        List<HourlyRow> rows = new ArrayList<>(groups.size());
        Set<LocalDate> months = new LinkedHashSet<>();
        groups.forEach((key, group) -> {
            rows.add(UsageRollupMath.rollupHour(key.clientId(), clusterConfigId, key.workloadId(), key.hour(), group));
            months.add(UsagePartitions.periodOf(Table.HOURLY, key.hour()));
        });
        for (LocalDate month : months) {
            store.ensurePartition(Table.HOURLY, month);
        }
        store.upsertHourly(rows);
        store.touchLastSeen(clusterConfigId, workloadIds, lastSampled);
        return rows.size();
    }

    // ---------------------------------------------------------------- maintenance

    /** Daily maintenance: partitions, daily downsample, retention. Never throws. */
    @Scheduled(cron = "${" + UsageRollupProperties.MAINTENANCE_CRON_KEY + ":"
            + UsageRollupProperties.DEFAULT_MAINTENANCE_CRON + "}", zone = "UTC")
    public void runMaintenance() {
        if (!properties.enabled()) {
            return;
        }
        Instant now = clock.instant();
        LocalDate today = UsageRollupMath.utcDay(now);
        Tally tally = new Tally();

        step("ensure partitions", tally, () -> ensurePartitions(today, tally));
        step("daily rollup", tally, () ->
                tally.dailyRows += rollupDays(today.minusDays(properties.dailyLookbackDays()), today.minusDays(1),
                        tally));
        step("sample retention", tally, () ->
                tally.samplesDropped += dropAged(Table.SAMPLE,
                        now.minus(Duration.ofHours(properties.sampleRetentionHours())), tally));
        step("hourly retention", tally, () ->
                dropAgedHourly(now.minus(Duration.ofDays(properties.hourlyRetentionDays())), tally));
        step("daily retention", tally, () ->
                tally.dailyDropped += dropAged(Table.DAILY,
                        now.minus(Duration.ofDays(properties.dailyRetentionDays())), tally));

        logger.info("Usage rollup maintenance {}: {} partitions ensured, {} daily rows, dropped {} sample / {} hourly"
                        + " / {} daily partitions, {} hourly partitions kept unverified, {} DDL skipped (lock held"
                        + " elsewhere), {} partition creates gave up on lock_timeout (retried next run), {} failures",
                today, tally.ensured, tally.dailyRows, tally.samplesDropped, tally.hourlyDropped, tally.dailyDropped,
                tally.hourlyKept, tally.lockSkipped, tally.lockTimeouts, tally.failures);
    }

    /** Pre-creates the partitions the next writes need, once the application is up. Never throws. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.enabled()) {
            return;
        }
        Tally tally = new Tally();
        step("ensure partitions at startup", tally, () -> ensurePartitions(UsageRollupMath.utcDay(clock.instant()),
                tally));
        logger.info("Usage rollup startup: {} partitions ensured, {} skipped (lock held elsewhere), {} gave up on"
                + " lock_timeout (retried by maintenance), {} failures",
                tally.ensured, tally.lockSkipped, tally.lockTimeouts, tally.failures);
    }

    /** (a) hourly + daily: current month .. + premake-months; sample: today .. today + SAMPLE_PREMAKE_DAYS. */
    private void ensurePartitions(LocalDate today, Tally tally) {
        List<Partition> wanted = new ArrayList<>();
        LocalDate month = today.withDayOfMonth(1);
        for (int i = 0; i <= properties.premakeMonths(); i++) {
            wanted.add(new Partition(Table.HOURLY, month.plusMonths(i)));
            wanted.add(new Partition(Table.DAILY, month.plusMonths(i)));
        }
        for (int i = 0; i <= SAMPLE_PREMAKE_DAYS; i++) {
            wanted.add(new Partition(Table.SAMPLE, today.plusDays(i)));
        }
        for (Partition partition : wanted) {
            try {
                if (runLocked(() -> store.ensurePartition(partition.table(), partition.period()))) {
                    tally.ensured++;
                } else {
                    tally.lockSkipped++;
                }
            } catch (PartitionLockTimeoutException e) {
                lockTimedOut(tally, partition.name());
            } catch (RuntimeException e) {
                failed(tally, "could not ensure partition " + partition.name(), e);
            }
        }
    }

    /**
     * (b) Daily rows for UTC days {@code [from, toInclusive]} from the hourly rows, per cluster in its
     * own try/catch. Returns the rows written.
     */
    private int rollupDays(LocalDate from, LocalDate toInclusive, Tally tally) {
        int written = 0;
        List<Long> clusters = store.clustersWithUsage();
        for (LocalDate day = from; !day.isAfter(toInclusive); day = day.plusDays(1)) {
            Instant dayStart = UsagePartitions.lowerBound(Table.SAMPLE, day);
            Instant dayEnd = UsagePartitions.upperBound(Table.SAMPLE, day);
            for (Long clusterConfigId : clusters) {
                try {
                    written += rollupDay(clusterConfigId, day, dayStart, dayEnd);
                } catch (PartitionLockTimeoutException e) {
                    lockTimedOut(tally, UsagePartitions.partitionName(Table.DAILY, day));
                } catch (RuntimeException e) {
                    failed(tally, "daily rollup of " + day + " failed for cluster config " + clusterConfigId, e);
                }
            }
        }
        return written;
    }

    private record DayKey(long clientId, long workloadId) {
    }

    private int rollupDay(long clusterConfigId, LocalDate day, Instant dayStart, Instant dayEnd) {
        List<HourlyRow> hours = store.readHourly(clusterConfigId, dayStart, dayEnd);
        if (hours.isEmpty()) {
            return 0;
        }
        Map<DayKey, List<HourlyRow>> groups = new LinkedHashMap<>();
        for (HourlyRow h : hours) {
            groups.computeIfAbsent(new DayKey(h.clientId(), h.workloadId()), k -> new ArrayList<>()).add(h);
        }
        List<DailyRow> rows = new ArrayList<>(groups.size());
        groups.forEach((key, group) ->
                rows.add(UsageRollupMath.rollupDay(key.clientId(), clusterConfigId, key.workloadId(), day, group)));
        store.ensurePartition(Table.DAILY, day);
        store.upsertDaily(rows);
        return rows.size();
    }

    /** (c) / (e) Drops the partitions of {@code table} whose upper bound is at or before {@code cutoff}. */
    private int dropAged(Table table, Instant cutoff, Tally tally) {
        int dropped = 0;
        for (Partition partition : aged(table, cutoff)) {
            try {
                if (runLocked(() -> store.dropPartition(partition.name()))) {
                    dropped++;
                    logger.info("Usage rollup: dropped aged partition {}", partition.name());
                } else {
                    tally.lockSkipped++;
                }
            } catch (RuntimeException e) {
                failed(tally, "could not drop partition " + partition.name(), e);
            }
        }
        return dropped;
    }

    /** (d) An aged hourly partition is dropped only once every day in it has its daily rows. */
    private void dropAgedHourly(Instant cutoff, Tally tally) {
        for (Partition partition : aged(Table.HOURLY, cutoff)) {
            try {
                LocalDate first = partition.period();
                LocalDate last = UsagePartitions.periodEnd(Table.HOURLY, first).minusDays(1);
                tally.dailyRows += rollupDays(first, last, tally);
                long unrolled = store.countUnrolledDays(partition.name());
                if (unrolled != 0) {
                    tally.hourlyKept++;
                    tally.failures++;
                    maintenanceErrors.increment();
                    logger.error("Usage rollup: keeping aged hourly partition {}: {} (cluster, workload, day) groups"
                            + " have no daily row yet", partition.name(), unrolled);
                    continue;
                }
                if (runLocked(() -> store.dropPartition(partition.name()))) {
                    tally.hourlyDropped++;
                    logger.info("Usage rollup: dropped aged hourly partition {} (daily rollup verified)",
                            partition.name());
                } else {
                    tally.lockSkipped++;
                }
            } catch (RuntimeException e) {
                failed(tally, "could not retire hourly partition " + partition.name(), e);
            }
        }
    }

    /** Our partitions of {@code table} (names {@link UsagePartitions#parse} accepts) ending at or before cutoff. */
    private List<Partition> aged(Table table, Instant cutoff) {
        List<Partition> aged = new ArrayList<>();
        for (String name : store.listPartitions(table.parent())) {
            Optional<Partition> parsed = UsagePartitions.parse(name);
            if (parsed.isEmpty() || parsed.get().table() != table) {
                continue;
            }
            Partition partition = parsed.get();
            if (!UsagePartitions.upperBound(table, partition.period()).isAfter(cutoff)) {
                aged.add(partition);
            }
        }
        return aged;
    }

    /**
     * Runs one DDL action in its own short transaction holding the DDL advisory lock. Returns false,
     * without running it, when another session holds the lock.
     */
    private boolean runLocked(Runnable ddl) {
        Boolean ran = transactions.execute(status -> {
            if (!store.tryAdvisoryXactLock(DDL_LOCK_KEY)) {
                return false;
            }
            ddl.run();
            return true;
        });
        return Boolean.TRUE.equals(ran);
    }

    private void step(String name, Tally tally, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            failed(tally, name + " failed", e);
        }
    }

    /** A partition create gave up on its lock timeout: skipped and counted, retried on the next run. */
    private void lockTimedOut(Tally tally, String partition) {
        tally.lockTimeouts++;
        logger.warn("Usage rollup maintenance: gave up creating partition {} on lock_timeout (a long write on"
                + " client / kubernetes_cluster_config held it); retried next run", partition);
    }

    private void failed(Tally tally, String what, RuntimeException e) {
        tally.failures++;
        maintenanceErrors.increment();
        logger.warn("Usage rollup maintenance: {}: {}", what, e.toString());
    }

    /** Counts for the one summary line of a maintenance run. */
    private static final class Tally {
        int ensured;
        int lockSkipped;
        int lockTimeouts;
        int dailyRows;
        int samplesDropped;
        int hourlyDropped;
        int hourlyKept;
        int dailyDropped;
        int failures;
    }
}
