package com.xammer.cloud.service.automation.workload.usage.store;

import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.DailyRow;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.HourlyRow;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.SampleRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * The rollup arithmetic: percentiles, UTC bucketing, and the hourly and daily rows. Pure: no Spring,
 * no database, no clock.
 *
 * <h2>Percentiles</h2>
 *
 * Linear interpolation between closest ranks, identical to PostgreSQL {@code percentile_cont} (and
 * numpy's default "linear"): {@code rank = p * (n - 1)}, interpolated between {@code floor(rank)} and
 * {@code ceil(rank)}. With few samples the p95 is close to the max; {@code sample_count} is stored
 * next to it so a reader can tell.
 *
 * <h2>Daily rows</h2>
 *
 * A daily row is a downsample of that day's hourly rows (plan decision 8): p50 = median of the hourly
 * p50s, p95 = p95 of the hourly p95s, max = max of the hourly maxes. That is an approximation of a
 * raw-sample daily p95, documented as such on the column.
 */
public final class UsageRollupMath {

    private UsageRollupMath() {
    }

    /**
     * The {@code p}-quantile of {@code sortedAscending} by linear interpolation
     * ({@code percentile_cont}).
     *
     * @param sortedAscending the values, already sorted ascending; not modified
     * @param p               the quantile in {@code [0, 1]}
     * @throws IllegalArgumentException when the array is empty or {@code p} is outside {@code [0, 1]}
     */
    public static double percentileLinear(double[] sortedAscending, double p) {
        if (sortedAscending == null || sortedAscending.length == 0) {
            throw new IllegalArgumentException("percentile of no values");
        }
        if (!(p >= 0.0 && p <= 1.0)) {
            throw new IllegalArgumentException("p must be in [0, 1], was " + p);
        }
        int n = sortedAscending.length;
        double rank = p * (n - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        double low = sortedAscending[lower];
        if (upper == lower) {
            return low;
        }
        return low + (rank - lower) * (sortedAscending[upper] - low);
    }

    /** The start of the UTC hour containing {@code instant}. */
    public static Instant hourOf(Instant instant) {
        return instant.truncatedTo(ChronoUnit.HOURS);
    }

    /** The UTC calendar day containing {@code instant}. */
    public static LocalDate utcDay(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * One hourly row from one workload-container's samples of one hour.
     *
     * <ul>
     *   <li>{@code sample_count} = number of samples (one per pod per snapshot);
     *       {@code snapshot_count} = distinct {@code sampled_at}</li>
     *   <li>cpu/memory p50, p95 over all samples, max = largest sample; memory rounded HALF_UP</li>
     *   <li>replicas: one {@code running_replicas} value per snapshot (the largest, should samples of
     *       one snapshot disagree) &rarr; min / max / mean over snapshots</li>
     *   <li>requests/limits: the values in force at the latest snapshot; when its samples differ (a
     *       rollout in progress) the largest non-null value per field; null only when all are null</li>
     * </ul>
     *
     * @throws IllegalArgumentException when {@code samples} is empty
     */
    public static HourlyRow rollupHour(long clientId, long clusterConfigId, long workloadId, Instant hour,
                                       List<SampleRow> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("an hourly row needs at least one sample");
        }
        double[] cpu = sorted(samples, SampleRow::cpuM);
        double[] mem = sorted(samples, s -> (double) s.memB());

        // One replicas value per snapshot, in sampled_at order.
        TreeMap<Instant, Integer> replicasPerSnapshot = new TreeMap<>();
        for (SampleRow s : samples) {
            replicasPerSnapshot.merge(s.sampledAt(), s.runningReplicas(), Math::max);
        }
        Instant latest = replicasPerSnapshot.lastKey();
        List<SampleRow> atLatest = samples.stream().filter(s -> s.sampledAt().equals(latest)).toList();

        int replicasMin = Integer.MAX_VALUE;
        int replicasMax = Integer.MIN_VALUE;
        long replicasSum = 0;
        for (int replicas : replicasPerSnapshot.values()) {
            replicasMin = Math.min(replicasMin, replicas);
            replicasMax = Math.max(replicasMax, replicas);
            replicasSum += replicas;
        }
        int snapshots = replicasPerSnapshot.size();

        return new HourlyRow(clusterConfigId, clientId, workloadId, hour,
                roundHalfUp(percentileLinear(mem, 0.5)),
                roundHalfUp(percentileLinear(mem, 0.95)),
                roundHalfUp(mem[mem.length - 1]),
                largest(atLatest, SampleRow::memReqB),
                largest(atLatest, SampleRow::memLimB),
                percentileLinear(cpu, 0.5),
                percentileLinear(cpu, 0.95),
                cpu[cpu.length - 1],
                largest(atLatest, SampleRow::cpuReqM),
                largest(atLatest, SampleRow::cpuLimM),
                (double) replicasSum / snapshots,
                samples.size(),
                snapshots,
                replicasMin,
                replicasMax);
    }

    /**
     * One daily row from one workload-container's hourly rows of one UTC day (plan decision 8):
     * p50 = median of the hourly p50s, p95 = p95 of the hourly p95s, max = max of the hourly maxes,
     * counts summed, {@code hours_covered} = number of hourly rows, replicas min/max over the hours,
     * replicas_avg weighted by each hour's {@code snapshot_count}, requests/limits of the latest hour.
     *
     * @throws IllegalArgumentException when {@code hours} is empty
     */
    public static DailyRow rollupDay(long clientId, long clusterConfigId, long workloadId, LocalDate day,
                                     List<HourlyRow> hours) {
        if (hours == null || hours.isEmpty()) {
            throw new IllegalArgumentException("a daily row needs at least one hourly row");
        }
        double[] cpuP50 = sorted(hours, HourlyRow::cpuP50M);
        double[] cpuP95 = sorted(hours, HourlyRow::cpuP95M);
        double[] memP50 = sorted(hours, h -> (double) h.memP50B());
        double[] memP95 = sorted(hours, h -> (double) h.memP95B());

        HourlyRow last = hours.stream().max(Comparator.comparing(HourlyRow::hour)).orElseThrow();
        double cpuMax = Double.NEGATIVE_INFINITY;
        long memMax = Long.MIN_VALUE;
        int sampleCount = 0;
        int snapshotCount = 0;
        int replicasMin = Integer.MAX_VALUE;
        int replicasMax = Integer.MIN_VALUE;
        double weightedReplicas = 0.0;
        double plainReplicas = 0.0;
        for (HourlyRow h : hours) {
            cpuMax = Math.max(cpuMax, h.cpuMaxM());
            memMax = Math.max(memMax, h.memMaxB());
            sampleCount += h.sampleCount();
            snapshotCount += h.snapshotCount();
            replicasMin = Math.min(replicasMin, h.replicasMin());
            replicasMax = Math.max(replicasMax, h.replicasMax());
            weightedReplicas += h.replicasAvg() * h.snapshotCount();
            plainReplicas += h.replicasAvg();
        }
        // snapshot_count is >= 1 on every row the hourly job writes; the plain mean only guards a
        // hand-written row with 0.
        double replicasAvg = snapshotCount > 0 ? weightedReplicas / snapshotCount : plainReplicas / hours.size();

        return new DailyRow(clusterConfigId, clientId, workloadId, day,
                roundHalfUp(percentileLinear(memP50, 0.5)),
                roundHalfUp(percentileLinear(memP95, 0.95)),
                memMax,
                last.memReqB(),
                last.memLimB(),
                percentileLinear(cpuP50, 0.5),
                percentileLinear(cpuP95, 0.95),
                cpuMax,
                last.cpuReqM(),
                last.cpuLimM(),
                replicasAvg,
                sampleCount,
                snapshotCount,
                hours.size(),
                replicasMin,
                replicasMax);
    }

    // ---------------------------------------------------------------- helpers

    private static <T> double[] sorted(List<T> rows, ToDoubleFunction<T> value) {
        double[] values = new double[rows.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = value.applyAsDouble(rows.get(i));
        }
        Arrays.sort(values);
        return values;
    }

    private static long roundHalfUp(double value) {
        return new BigDecimal(value).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** The largest non-null value of a field, or null when every row has it null. */
    private static <T extends Comparable<T>> T largest(List<SampleRow> rows, Function<SampleRow, T> field) {
        T best = null;
        for (SampleRow row : rows) {
            T value = field.apply(row);
            if (value != null && (best == null || value.compareTo(best) > 0)) {
                best = value;
            }
        }
        return best;
    }
}
