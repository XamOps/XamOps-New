package com.xammer.cloud.service.automation.workload.usage.store;

import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.DailyRow;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.HourlyRow;
import com.xammer.cloud.service.automation.workload.usage.store.UsageRollupStore.SampleRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class UsageRollupMathTest {

    private static final long MI = 1024L * 1024L;
    private static final long CLIENT = 7L;
    private static final long CLUSTER = 70L;
    private static final long WORKLOAD = 700L;
    private static final Instant HOUR = Instant.parse("2026-10-24T10:00:00Z");
    private static final double EPS = 1e-9;

    // ---------------------------------------------------------------- percentileLinear

    @Test
    @DisplayName("[1..12]: p50 = 6.5, p95 = 11.45 (rank 0.95 * 11 = 10.45 -> 11 + 0.45 * (12 - 11))")
    void percentileOfOneToTwelve() {
        double[] values = new double[12];
        for (int i = 0; i < 12; i++) {
            values[i] = i + 1;
        }
        assertThat(UsageRollupMath.percentileLinear(values, 0.5)).isCloseTo(6.5, within(EPS));
        assertThat(UsageRollupMath.percentileLinear(values, 0.95)).isCloseTo(11.45, within(EPS));
        assertThat(UsageRollupMath.percentileLinear(values, 0.0)).isEqualTo(1.0);
        assertThat(UsageRollupMath.percentileLinear(values, 1.0)).isEqualTo(12.0);
    }

    @Test
    @DisplayName("n = 1: every percentile is the value")
    void percentileOfOne() {
        double[] one = {42.5};
        assertThat(UsageRollupMath.percentileLinear(one, 0.0)).isEqualTo(42.5);
        assertThat(UsageRollupMath.percentileLinear(one, 0.5)).isEqualTo(42.5);
        assertThat(UsageRollupMath.percentileLinear(one, 0.95)).isEqualTo(42.5);
        assertThat(UsageRollupMath.percentileLinear(one, 1.0)).isEqualTo(42.5);
    }

    @Test
    @DisplayName("n = 2: p95 = a + 0.95 * (b - a)")
    void percentileOfTwo() {
        double a = 100;
        double b = 300;
        assertThat(UsageRollupMath.percentileLinear(new double[] {a, b}, 0.95))
                .isCloseTo(a + 0.95 * (b - a), within(EPS))
                .isCloseTo(290.0, within(EPS));
        assertThat(UsageRollupMath.percentileLinear(new double[] {a, b}, 0.5)).isCloseTo(200.0, within(EPS));
    }

    @Test
    @DisplayName("n = 4 [10, 20, 30, 40]: p25 = 17.5, p50 = 25, p95 = 38.5")
    void percentileOfFour() {
        // percentile_cont: rank = p * 3. p25: 0.75 -> 10 + 0.75 * 10 = 17.5; p50: 1.5 -> 20 + 0.5 * 10 = 25;
        // p95: 2.85 -> 30 + 0.85 * 10 = 38.5.
        double[] values = {10, 20, 30, 40};
        assertThat(UsageRollupMath.percentileLinear(values, 0.25)).isCloseTo(17.5, within(EPS));
        assertThat(UsageRollupMath.percentileLinear(values, 0.5)).isCloseTo(25.0, within(EPS));
        assertThat(UsageRollupMath.percentileLinear(values, 0.95)).isCloseTo(38.5, within(EPS));
    }

    @Test
    @DisplayName("n = 120 of i^2 (i = 0..119): p50 = 3540.5, p95 = 12780.35")
    void percentileOfOneHundredTwenty() {
        // percentile_cont: rank = p * 119.
        // p50: 59.5 -> 59^2 + 0.5 * (60^2 - 59^2) = 3481 + 0.5 * 119 = 3540.5
        // p95: 113.05 -> 113^2 + 0.05 * (114^2 - 113^2) = 12769 + 0.05 * 227 = 12780.35
        double[] values = new double[120];
        for (int i = 0; i < 120; i++) {
            values[i] = (double) i * i;
        }
        assertThat(UsageRollupMath.percentileLinear(values, 0.5)).isCloseTo(3540.5, within(1e-6));
        assertThat(UsageRollupMath.percentileLinear(values, 0.95)).isCloseTo(12780.35, within(1e-6));
    }

    @Test
    @DisplayName("no values, or p outside [0, 1], is rejected")
    void percentileRejectsBadInput() {
        assertThatThrownBy(() -> UsageRollupMath.percentileLinear(new double[0], 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UsageRollupMath.percentileLinear(new double[] {1}, 1.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UsageRollupMath.percentileLinear(new double[] {1}, -0.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UsageRollupMath.percentileLinear(new double[] {1}, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- bucketing

    @Test
    @DisplayName("hourOf: 10:59:59.999Z -> 10:00Z, 11:00:00Z -> 11:00Z")
    void hourBoundaries() {
        assertThat(UsageRollupMath.hourOf(Instant.parse("2026-10-24T10:59:59.999Z")))
                .isEqualTo(Instant.parse("2026-10-24T10:00:00Z"));
        assertThat(UsageRollupMath.hourOf(Instant.parse("2026-10-24T11:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-24T11:00:00Z"));
    }

    @Test
    @DisplayName("an instant from a +05:30 timestamp lands in its UTC hour, not the local one")
    void offsetTimestampsBucketInUtc() {
        Instant beforeBoundary = OffsetDateTime.parse("2026-10-24T16:29:59+05:30").toInstant();
        Instant atBoundary = OffsetDateTime.parse("2026-10-24T16:30:00+05:30").toInstant();
        assertThat(UsageRollupMath.hourOf(beforeBoundary)).isEqualTo(Instant.parse("2026-10-24T10:00:00Z"));
        assertThat(UsageRollupMath.hourOf(atBoundary)).isEqualTo(Instant.parse("2026-10-24T11:00:00Z"));
        assertThat(UsageRollupMath.utcDay(OffsetDateTime.parse("2026-10-25T03:00:00+05:30").toInstant()))
                .as("03:00 IST on the 25th is still the 24th in UTC").isEqualTo(LocalDate.of(2026, 10, 24));
    }

    @Test
    @DisplayName("utcDay: 23:59:59Z is that day, 00:00:00Z the next")
    void dayBoundaries() {
        assertThat(UsageRollupMath.utcDay(Instant.parse("2026-10-24T23:59:59Z"))).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(UsageRollupMath.utcDay(Instant.parse("2026-10-25T00:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 25));
    }

    // ---------------------------------------------------------------- rollupHour

    @Test
    @DisplayName("3 replicas x 12 snapshots -> sample_count 36, snapshot_count 12, cpu/mem percentiles over all 36")
    void threeReplicasTwelveSnapshots() {
        List<SampleRow> samples = new ArrayList<>();
        for (int snapshot = 0; snapshot < 12; snapshot++) {
            for (int pod = 0; pod < 3; pod++) {
                // cpu = 1..36 across the hour; memory = cpu MiB
                int v = snapshot * 3 + pod + 1;
                samples.add(sample(snapshot, v * MI, v, 3, 100.0, 64 * MI, pod));
            }
        }
        HourlyRow row = UsageRollupMath.rollupHour(CLIENT, CLUSTER, WORKLOAD, HOUR, samples);

        assertThat(row.clientId()).isEqualTo(CLIENT);
        assertThat(row.clusterConfigId()).isEqualTo(CLUSTER);
        assertThat(row.workloadId()).isEqualTo(WORKLOAD);
        assertThat(row.hour()).isEqualTo(HOUR);
        assertThat(row.sampleCount()).isEqualTo(36);
        assertThat(row.snapshotCount()).isEqualTo(12);
        // [1..36]: p50 rank 17.5 -> 18.5; p95 rank 33.25 -> 34.25; max 36.
        assertThat(row.cpuP50M()).isCloseTo(18.5, within(EPS));
        assertThat(row.cpuP95M()).isCloseTo(34.25, within(EPS));
        assertThat(row.cpuMaxM()).isEqualTo(36.0);
        assertThat(row.memP50B()).isEqualTo(Math.round(18.5 * MI));
        assertThat(row.memP95B()).isEqualTo(Math.round(34.25 * MI));
        assertThat(row.memMaxB()).isEqualTo(36 * MI);
        assertThat(row.replicasMin()).isEqualTo(3);
        assertThat(row.replicasMax()).isEqualTo(3);
        assertThat(row.replicasAvg()).isEqualTo(3.0);
        assertThat(row.cpuReqM()).isEqualTo(100.0);
        assertThat(row.memLimB()).isEqualTo(64 * MI);
    }

    @Test
    @DisplayName("memory percentiles round HALF_UP to whole bytes")
    void memoryRoundsHalfUp() {
        // [1, 2] bytes: p50 = 1.5 -> 2 (HALF_UP), p95 = 1.95 -> 2. [10, 11]: p50 = 10.5 -> 11.
        HourlyRow small = UsageRollupMath.rollupHour(CLIENT, CLUSTER, WORKLOAD, HOUR,
                List.of(sample(0, 1, 1, 1, null, null, 0), sample(1, 2, 1, 1, null, null, 0)));
        assertThat(small.memP50B()).isEqualTo(2);
        assertThat(small.memP95B()).isEqualTo(2);
        HourlyRow other = UsageRollupMath.rollupHour(CLIENT, CLUSTER, WORKLOAD, HOUR,
                List.of(sample(0, 10, 1, 1, null, null, 0), sample(1, 11, 1, 1, null, null, 0)));
        assertThat(other.memP50B()).isEqualTo(11);
    }

    @Test
    @DisplayName("a mid-hour scale-up 2 -> 4: replicas min 2, max 4, avg over snapshots (not samples) 3")
    void scaleUpMidHour() {
        List<SampleRow> samples = new ArrayList<>();
        for (int snapshot = 0; snapshot < 12; snapshot++) {
            int replicas = snapshot < 6 ? 2 : 4;
            for (int pod = 0; pod < replicas; pod++) {
                samples.add(sample(snapshot, 10 * MI, 5, replicas, 100.0, null, pod));
            }
        }
        HourlyRow row = UsageRollupMath.rollupHour(CLIENT, CLUSTER, WORKLOAD, HOUR, samples);
        assertThat(row.sampleCount()).isEqualTo(36);
        assertThat(row.snapshotCount()).isEqualTo(12);
        assertThat(row.replicasMin()).isEqualTo(2);
        assertThat(row.replicasMax()).isEqualTo(4);
        assertThat(row.replicasAvg()).as("(6 x 2 + 6 x 4) / 12, not weighted by pod count").isEqualTo(3.0);
    }

    @Test
    @DisplayName("requests/limits are the ones in force at the latest snapshot after a mid-hour resize")
    void requestsFromLatestSnapshot() {
        List<SampleRow> samples = new ArrayList<>();
        for (int snapshot = 0; snapshot < 12; snapshot++) {
            // Resized down from 500m / 1Gi to 250m / 512Mi at snapshot 6: the earlier, larger values must not win.
            boolean resized = snapshot >= 6;
            samples.add(new SampleRow(CLUSTER, CLIENT, WORKLOAD, at(snapshot), 100 * MI,
                    resized ? 512 * MI : 1024 * MI, resized ? 768 * MI : 2048 * MI,
                    50, resized ? 250.0 : 500.0, resized ? 400.0 : 1000.0, 1, 1));
        }
        HourlyRow row = UsageRollupMath.rollupHour(CLIENT, CLUSTER, WORKLOAD, HOUR, samples);
        assertThat(row.cpuReqM()).isEqualTo(250.0);
        assertThat(row.cpuLimM()).isEqualTo(400.0);
        assertThat(row.memReqB()).isEqualTo(512 * MI);
        assertThat(row.memLimB()).isEqualTo(768 * MI);
    }

    @Test
    @DisplayName("two pods disagreeing at the latest snapshot (rollout): the largest non-null value per field")
    void latestSnapshotTieTakesLargestNonNull() {
        List<SampleRow> samples = List.of(
                new SampleRow(CLUSTER, CLIENT, WORKLOAD, at(0), MI, 999 * MI, null, 1, 900.0, null, 1, 2),
                new SampleRow(CLUSTER, CLIENT, WORKLOAD, at(1), MI, 256 * MI, null, 1, 250.0, null, 1, 2),
                new SampleRow(CLUSTER, CLIENT, WORKLOAD, at(1), MI, null, 128 * MI, 1, 300.0, null, 2, 2));
        HourlyRow row = UsageRollupMath.rollupHour(CLIENT, CLUSTER, WORKLOAD, HOUR, samples);
        assertThat(row.cpuReqM()).isEqualTo(300.0);
        assertThat(row.memReqB()).as("null on one pod, set on the other: the set value").isEqualTo(256 * MI);
        assertThat(row.memLimB()).isEqualTo(128 * MI);
        assertThat(row.cpuLimM()).as("null on every pod at the latest snapshot").isNull();
    }

    @Test
    @DisplayName("unset requests and limits stay null, never 0")
    void nullRequestsStayNull() {
        HourlyRow row = UsageRollupMath.rollupHour(CLIENT, CLUSTER, WORKLOAD, HOUR,
                List.of(sample(0, MI, 1, 1, null, null, 0), sample(1, MI, 2, 1, null, null, 0)));
        assertThat(row.cpuReqM()).isNull();
        assertThat(row.cpuLimM()).isNull();
        assertThat(row.memReqB()).isNull();
        assertThat(row.memLimB()).isNull();
    }

    @Test
    @DisplayName("an hour with no samples has no row")
    void noSamplesNoRow() {
        assertThatThrownBy(() -> UsageRollupMath.rollupHour(CLIENT, CLUSTER, WORKLOAD, HOUR, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UsageRollupMath.rollupDay(CLIENT, CLUSTER, WORKLOAD, LocalDate.of(2026, 10, 24),
                List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- rollupDay

    @Test
    @DisplayName("24 hours: p50 of hourly p50s, p95 of hourly p95s, max of maxes, sums, weighted replicas_avg")
    void fullDay() {
        LocalDate day = LocalDate.of(2026, 10, 24);
        Instant midnight = Instant.parse("2026-10-24T00:00:00Z");
        List<HourlyRow> hours = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            boolean firstHalf = i < 12;
            hours.add(new HourlyRow(CLUSTER, CLIENT, WORKLOAD, midnight.plus(Duration.ofHours(i)),
                    (i + 1) * MI, 2L * (i + 1) * MI, 3L * (i + 1) * MI,
                    i == 23 ? 512 * MI : 256 * MI, null,
                    i + 1, 2.0 * (i + 1), 3.0 * (i + 1),
                    i == 23 ? 250.0 : 100.0, i == 23 ? 500.0 : null,
                    firstHalf ? 2.0 : 4.0, firstHalf ? 24 : 12, firstHalf ? 12 : 6,
                    firstHalf ? 2 : 3, firstHalf ? 2 : 5));
        }
        // Shuffle the order: the latest hour is found by its timestamp, not its position.
        java.util.Collections.reverse(hours);
        DailyRow row = UsageRollupMath.rollupDay(CLIENT, CLUSTER, WORKLOAD, day, hours);

        assertThat(row.day()).isEqualTo(day);
        // p50 of [1..24]: rank 11.5 -> 12.5. p95 of [2, 4, .., 48]: rank 21.85 -> 44 + 0.85 * 2 = 45.7.
        assertThat(row.cpuP50M()).isCloseTo(12.5, within(EPS));
        assertThat(row.cpuP95M()).isCloseTo(45.7, within(EPS));
        assertThat(row.cpuMaxM()).isEqualTo(72.0);
        assertThat(row.memP50B()).isEqualTo(Math.round(12.5 * MI));
        assertThat(row.memP95B()).isEqualTo(Math.round(45.7 * MI));
        assertThat(row.memMaxB()).isEqualTo(72 * MI);
        assertThat(row.sampleCount()).isEqualTo(12 * 24 + 12 * 12);
        assertThat(row.snapshotCount()).isEqualTo(12 * 12 + 12 * 6);
        assertThat(row.hoursCovered()).isEqualTo(24);
        assertThat(row.replicasMin()).isEqualTo(2);
        assertThat(row.replicasMax()).isEqualTo(5);
        // (12 h x 12 snapshots x 2.0 + 12 h x 6 snapshots x 4.0) / 216 = 576 / 216 = 2.666..; the plain mean is 3.
        assertThat(row.replicasAvg()).isCloseTo(576.0 / 216.0, within(EPS));
        assertThat(row.cpuReqM()).as("from the latest hour").isEqualTo(250.0);
        assertThat(row.cpuLimM()).isEqualTo(500.0);
        assertThat(row.memReqB()).isEqualTo(512 * MI);
        assertThat(row.memLimB()).isNull();
    }

    @Test
    @DisplayName("a 3-hour partial day: hours_covered 3, percentiles over those 3 hours only")
    void partialDay() {
        LocalDate day = LocalDate.of(2026, 10, 24);
        List<HourlyRow> hours = List.of(
                hourly(Instant.parse("2026-10-24T05:00:00Z"), 3, 10, 11, 1.0, 12, 1, 1),
                hourly(Instant.parse("2026-10-24T06:00:00Z"), 1, 30, 35, 3.0, 4, 3, 3),
                hourly(Instant.parse("2026-10-24T07:00:00Z"), 2, 20, 22, 2.0, 8, 2, 2));
        DailyRow row = UsageRollupMath.rollupDay(CLIENT, CLUSTER, WORKLOAD, day, hours);
        // p50 of [1, 2, 3] = 2; p95 of [10, 20, 30]: rank 1.9 -> 20 + 0.9 * 10 = 29.
        assertThat(row.cpuP50M()).isCloseTo(2.0, within(EPS));
        assertThat(row.cpuP95M()).isCloseTo(29.0, within(EPS));
        assertThat(row.cpuMaxM()).isEqualTo(35.0);
        assertThat(row.hoursCovered()).isEqualTo(3);
        assertThat(row.sampleCount()).isEqualTo(24 + 8 + 16);
        assertThat(row.snapshotCount()).isEqualTo(24);
        assertThat(row.replicasMin()).isEqualTo(1);
        assertThat(row.replicasMax()).isEqualTo(3);
        // (12 x 1 + 4 x 3 + 8 x 2) / 24 = 40 / 24
        assertThat(row.replicasAvg()).isCloseTo(40.0 / 24.0, within(EPS));
        assertThat(row.cpuReqM()).as("from 07:00, the latest hour").isEqualTo(700.0);
    }

    // ---------------------------------------------------------------- fixtures

    private static Instant at(int snapshot) {
        return HOUR.plus(Duration.ofMinutes(5L * snapshot));
    }

    private static SampleRow sample(int snapshot, long memB, double cpuM, int replicas, Double cpuReqM, Long memLimB,
                                    int pod) {
        return new SampleRow(CLUSTER, CLIENT, WORKLOAD, at(snapshot), memB, null, memLimB, cpuM, cpuReqM, null,
                pod, replicas);
    }

    private static HourlyRow hourly(Instant hour, double cpuP50, double cpuP95, double cpuMax, double replicasAvg,
                                    int snapshots, int replicasMin, int replicasMax) {
        double cpuReq = hour.atOffset(java.time.ZoneOffset.UTC).getHour() * 100.0;
        return new HourlyRow(CLUSTER, CLIENT, WORKLOAD, hour, MI, 2 * MI, 3 * MI, null, null,
                cpuP50, cpuP95, cpuMax, cpuReq, null, replicasAvg, snapshots * 2, snapshots,
                replicasMin, replicasMax);
    }
}
