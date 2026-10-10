package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.service.automation.workload.usage.ContainerUsageAggregate;
import com.xammer.cloud.service.automation.workload.usage.PercentileSet;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The nine measured reference workloads from the live EKS harness run, as engine fixtures.
 *
 * <p><strong>Each fixture states where its numbers come from.</strong> The harness recorded the
 * decisions each controller made and the targets upstream VPA independently computed — not every raw
 * percentile. Where a p95 is needed and only a p80, a {@code max} or a VPA target was captured, the
 * comment says so and gives the derivation, so a future reader can always tell a measurement from a
 * reconstruction.
 *
 * <p>The one case where that matters most — the correctly-sized control workload — is asserted at
 * <em>both</em> the reconstructed target and the measured −32.8% drift, so the suppression does not
 * depend on the reconstruction being exact.
 */
final class ReferenceWorkloads {

    static final Instant NOW = Instant.parse("2026-10-10T06:00:00Z");
    static final Instant WINDOW_START = NOW.minus(Duration.ofHours(24));

    static final long MI = 1024L * 1024L;

    private ReferenceWorkloads() {
    }

    // ---------------------------------------------------------------- builders

    /** Usage with full coverage over the full 24h window: the normal case. */
    static ContainerUsageAggregate usage(String container, long cpuMillisP95, long memBytesP95) {
        return usage(container, cpuMillisP95, memBytesP95, 0.0, 0, 1.0);
    }

    static ContainerUsageAggregate usage(String container, long cpuMillisP95, long memBytesP95,
                                         double throttleRatio, int oomKills, double coverage) {
        return new ContainerUsageAggregate(container,
                PercentileSet.single(95, cpuMillisP95),
                PercentileSet.single(95, memBytesP95),
                throttleRatio, oomKills, WINDOW_START, NOW, coverage, "harness");
    }

    static ResourceEnvelope guaranteed(int cpuMillis, long memBytes) {
        return new ResourceEnvelope(cpuMillis, cpuMillis, memBytes, memBytes);
    }

    static ResourceEnvelope burstable(Integer cpuRequest, Integer cpuLimit, Long memRequest, Long memLimit) {
        return new ResourceEnvelope(cpuRequest, cpuLimit, memRequest, memLimit);
    }

    static WorkloadSnapshot workload(WorkloadRef ref, int replicas, List<ContainerSnapshot> containers) {
        return new WorkloadSnapshot(ref, replicas, false, false, null, null, null, containers);
    }

    static ContainerSnapshot container(String name, ResourceEnvelope current, ContainerUsageAggregate usage) {
        return new ContainerSnapshot(name, current, usage);
    }

    // ---------------------------------------------------------------- 1. overprovisioned-api

    /**
     * {@code team-a/overprovisioned-api} — harness §1, §6, §7-B1/B3.
     *
     * <p>Measured: 1000m CPU / 1Gi memory as both request and limit (Guaranteed), essentially no
     * load, and upstream VPA's independently computed target was <strong>25m / 250Mi</strong> — i.e.
     * VPA stopped exactly at its own floors. The Go engine instead recommended 1m / 1Mi, and when it
     * applied that live it patched the Deployment template, forced a rolling replacement, wrote
     * requests without limits (Guaranteed → Burstable) and re-patched the same workload 60 s later
     * with 4Mi.
     *
     * <p>Reconstruction: the harness recorded "p95 CPU ≈ 0.05m, p95 mem ≈ 0.4Mi". 0.05m is below the
     * engine's integer-millicore resolution, so the fixture uses 1m — the smallest expressible value,
     * which is strictly more generous to the engine than 0.05m and therefore cannot hide a failure.
     */
    static WorkloadSnapshot overprovisionedApi() {
        WorkloadRef ref = WorkloadRef.of("team-a", "Deployment", "overprovisioned-api");
        return workload(ref, 2, List.of(
                container("api", guaranteed(1000, 1024 * MI), usage("api", 1, 419_430L))));
    }

    /** The same workload after the decision above was applied: 25m / 250Mi, still Guaranteed. */
    static WorkloadSnapshot overprovisionedApiAfterApply() {
        WorkloadRef ref = WorkloadRef.of("team-a", "Deployment", "overprovisioned-api");
        return workload(ref, 2, List.of(
                container("api", guaranteed(25, 250 * MI), usage("api", 1, 419_430L))));
    }

    // ---------------------------------------------------------------- 2. rightsized-worker (control)

    /**
     * {@code prod-sim/rightsized-worker} — harness §1, §10 item 1. <strong>The control.</strong>
     *
     * <p>Measured: 80m CPU / 64Mi memory, deliberately sized correctly, and the Go engine changed it
     * by <strong>−32.8%</strong>. VPA's target was 63m / 250Mi — i.e. VPA also saw no reason to move
     * it meaningfully, and its memory target was simply its floor.
     *
     * <p>Reconstruction: p95 CPU 48m → 48 × 1.25 = 60m target (a −20m move); p95 memory 41Mi →
     * 51Mi target. {@link #rightsizedWorkerAtMeasuredDrift()} repeats the case at the measured
     * −32.8% so the assertion does not depend on this reconstruction.
     */
    static WorkloadSnapshot rightsizedWorker() {
        WorkloadRef ref = WorkloadRef.of("prod-sim", "Deployment", "rightsized-worker");
        return workload(ref, 3, List.of(
                container("worker", burstable(80, null, 64 * MI, null),
                        usage("worker", 48, 41 * MI))));
    }

    /**
     * The control workload with the usage that reproduces the <strong>measured</strong> −32.8% CPU
     * drift exactly: a target of 54m against a request of 80m, i.e. −26m.
     *
     * <p>80 × 0.672 = 53.76 ≈ 54m. Working backwards through 25% headroom: 54 / 1.25 = 43.2 → p95
     * CPU 43m. This is the regression test for the measured false positive.
     */
    static WorkloadSnapshot rightsizedWorkerAtMeasuredDrift() {
        WorkloadRef ref = WorkloadRef.of("prod-sim", "Deployment", "rightsized-worker");
        return workload(ref, 3, List.of(
                container("worker", burstable(80, null, 64 * MI, null),
                        usage("worker", 43, 41 * MI))));
    }

    // ---------------------------------------------------------------- 3. throttled-encoder

    /**
     * {@code prod-sim/throttled-encoder} — harness §1, §6.
     *
     * <p>Measured: 10m CPU request against a 50m CPU limit, 32Mi memory request, and a CFS
     * <strong>throttle ratio of 1.0</strong> — the container was throttled 100% of the time, so its
     * measured CPU usage was pinned at the limit and was not a measurement of demand at all. The Go
     * engine raised the request to 56m from that censored usage and left the pod throttled at 1.0,
     * because it never writes limits. VPA's target was 63m.
     *
     * <p>The engine derives the target from the <em>limit</em>: 50 × 1.25 = 63m — which lands on
     * VPA's independently computed 63m, a useful corroboration.
     */
    static WorkloadSnapshot throttledEncoder() {
        WorkloadRef ref = WorkloadRef.of("prod-sim", "Deployment", "throttled-encoder");
        return workload(ref, 2, List.of(
                container("encoder", burstable(10, 50, 32 * MI, null),
                        usage("encoder", 50, 20 * MI, 1.0, 0, 1.0))));
    }

    // ---------------------------------------------------------------- 4. php-apache (HPA)

    /**
     * {@code team-b/php-apache} — harness §1, §2. The coexistence case.
     *
     * <p>Measured: 200m CPU / 64Mi memory with a <strong>CPU-metric HPA targeting 50%</strong>. In
     * one cycle the Go rightsizer said cut the CPU request 200m → 144m while the Go HPA manager said
     * raise the target 50% → 80% and max 5 → 10: three numbers for one workload, from one code path,
     * with no cross-reference. Cutting 200m → 144m alone would have moved measured utilisation from
     * ~65% to ~90% at unchanged load.
     *
     * @param cpuP95Millis the p95 the fixture should present, so the same workload can be tested
     *                     with demand that would decrease <em>and</em> demand that would increase
     * @param memP95Bytes  likewise for memory, which must stay eligible when only CPU is managed
     */
    static WorkloadSnapshot phpApache(long cpuP95Millis, long memP95Bytes) {
        WorkloadRef ref = WorkloadRef.of("team-b", "Deployment", "php-apache");
        HpaView hpa = HpaView.cpuHpa("php-apache", ref, 50, 1, 5);
        return new WorkloadSnapshot(ref, 2, false, false, hpa, null, null, List.of(
                container("php-apache", burstable(200, null, 64 * MI, null),
                        usage("php-apache", cpuP95Millis, memP95Bytes))));
    }

    // ---------------------------------------------------------------- 5. no-requests-sidecar-app

    /**
     * {@code dev-sim/no-requests-sidecar-app} — harness §1.
     *
     * <p>Measured: two containers, <strong>no requests and no limits at all</strong> (BestEffort).
     * The Go engine reported its current state as {@code 0m / 1Mi} — values the container did not
     * have — and then "fixed" that fabricated baseline to {@code 1m / 1Mi} for 0% savings. It also
     * applied one metric to both containers.
     *
     * <p>Reconstruction: the two containers are given deliberately different usage (the app is
     * busier than its sidecar) so the test can prove the recommendations are genuinely per container
     * rather than one value copied twice.
     */
    static WorkloadSnapshot noRequestsSidecarApp() {
        WorkloadRef ref = WorkloadRef.of("dev-sim", "Deployment", "no-requests-sidecar-app");
        return workload(ref, 2, List.of(
                container("app", ResourceEnvelope.UNSET, usage("app", 120, 300 * MI)),
                container("logshipper", ResourceEnvelope.UNSET, usage("logshipper", 8, 40 * MI))));
    }

    // ---------------------------------------------------------------- 6. idle-batch-listener

    /**
     * {@code dev-sim/idle-batch-listener} — harness §1, §2.
     *
     * <p>Measured: 100m CPU / 128Mi memory, 3 replicas, no traffic and no Service, p90 CPU 0. The Go
     * engine recommended a {@code 0m} CPU request <em>and</em> an HPA with {@code minReplicas: 2},
     * which would have pinned an idle workload to two replicas forever.
     *
     * <p>The honest answer is the {@code IDLE} finding: the remedy is scale-to-zero or deletion. No
     * request recommendation survives, and it falls out of the ordinary rules rather than a special
     * case — see the test.
     */
    static WorkloadSnapshot idleBatchListener() {
        WorkloadRef ref = WorkloadRef.of("dev-sim", "Deployment", "idle-batch-listener");
        return workload(ref, 3, List.of(
                container("listener", burstable(100, null, 128 * MI, null),
                        usage("listener", 0, 20 * MI))));
    }

    // ---------------------------------------------------------------- 7. load-generator

    /**
     * {@code team-b/load-generator} — harness §1.
     *
     * <p>Measured: a single replica at 50m / 32Mi. Single-replica protection is one of the two guards
     * the Go rightsizer did honour; it is kept, and moved into the engine so the future horizontal
     * half reads the same rule (the Go HPA manager did not honour it).
     */
    static WorkloadSnapshot loadGenerator() {
        WorkloadRef ref = WorkloadRef.of("team-b", "Deployment", "load-generator");
        return workload(ref, 1, List.of(
                container("generator", burstable(50, null, 32 * MI, null),
                        usage("generator", 40, 20 * MI))));
    }

    // ---------------------------------------------------------------- 8. guaranteed, genuine decrease

    /**
     * A Guaranteed workload with a decrease that is genuinely worth making: 2000m / 2Gi request and
     * limit, measured demand supporting 800m / 1Gi.
     *
     * <p>Derivation: p95 CPU 640m → 640 × 1.25 = 800m; p95 memory 858993459 B → × 1.25 = 1Gi exactly.
     * Not one of the nine measured workloads — it exists to prove requests and limits move
     * <em>together</em> on a Guaranteed workload, which is the half of audit bug 7 that
     * {@link #overprovisionedApi()} also covers but at the floor rather than at a real target.
     */
    static WorkloadSnapshot guaranteedGenuineDecrease() {
        WorkloadRef ref = WorkloadRef.of("prod-sim", "Deployment", "guaranteed-service");
        return workload(ref, 3, List.of(
                container("svc", guaranteed(2000, 2048 * MI), usage("svc", 640, 858_993_459L))));
    }

    // ---------------------------------------------------------------- 9. unschedulable demand

    /**
     * A workload whose measured demand exceeds any node in the cluster.
     *
     * <p>Derived from the measured {@code team-a/unschedulable-giant}, which asked for 32 CPU / 64Gi
     * on nodes with 1930m / 3294Mi allocatable and had been {@code Pending} for 8 hours. Here the
     * current request is modest and the <em>demand</em> is the problem: p95 CPU 3200m → 4000m target
     * against a 1930m node.
     */
    static WorkloadSnapshot unschedulableDemand() {
        WorkloadRef ref = WorkloadRef.of("team-a", "Deployment", "hungry-api");
        return workload(ref, 2, List.of(
                container("api", burstable(500, null, 64 * MI, null),
                        usage("api", 3200, 20 * MI))));
    }
}
