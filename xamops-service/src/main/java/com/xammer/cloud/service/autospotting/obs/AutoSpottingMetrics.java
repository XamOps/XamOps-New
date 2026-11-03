package com.xammer.cloud.service.autospotting.obs;

import com.xammer.cloud.domain.autospotting.DriftStatus;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.Provider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The single owner of every AutoSpotting meter (design-ux-ops.md §6.1-§6.2, plan-phase-10.md task
 * 10.1). Callers use the typed methods on this bean, never {@link MeterRegistry} directly, so meter
 * names and tag keys cannot drift and no forbidden dimension (instance, host, account) is ever
 * attached (AC-OBS-01). Names follow Micrometer's dotted style and the names core already emits
 * ({@code autospotting.job.skipped}, {@code autospotting.reconcile.*}, {@code autospotting.ops.stuck}).
 *
 * <p><b>Tags.</b> Only the bounded sets of §6.1 are used: {@code provider} (aws, gcp), {@code kind}
 * (operation kinds), {@code outcome}, {@code region}, {@code api}, {@code reason}, {@code state},
 * {@code tier}, plus the documented {@code direction}, {@code source}, {@code by}, {@code code},
 * {@code severity}, {@code lifecycle}, {@code billable}, {@code currency}, {@code job} and the
 * existing {@code event} and {@code drift} keys. Account id, instance id and host id are never tags:
 * an instance or host tag would stop per-instance counters summing into one series (AC-OBS-01), and
 * an account tag would make an unbounded, billed custom metric per account (§6.1). Per-account
 * breakdowns come from {@code spot_operation}, {@code autospotting_audit} and the logs (§8).
 *
 * <p><b>Gauges.</b> The DB-derived gauges read their value through a 60 s per-instance cache so a
 * scrape never hammers the database; the same value is reported by every instance, so alarms use the
 * Maximum statistic, never Sum (§6.2). {@code autospotting.groups} and {@code autospotting.groups.drift}
 * are bound here from {@link SpotGroupRepository}. The reconciler owns the per-sweep gauges
 * ({@code reconcile.staleness_seconds}, {@code ops.stuck}) and binds them through
 * {@link #bindReconcilerGauge}; later phases bind the ledger and savings gauges the same way, so this
 * bean stays the only place a meter is created.
 *
 * <p><b>Dark.</b> No conversion runs in production at this phase, so the counters and timers are
 * exercised only by the reconciler sweep and the lease/dispatch plumbing (all dark). The gauges read
 * whatever the spine holds (zero while no group is managed). Nothing here mutates a cloud.
 */
@Component
public class AutoSpottingMetrics {

    // ── meter names (§6.2). Names already emitted by core are kept verbatim. ────────
    public static final String CONVERSIONS_STARTED = "autospotting.conversions.started";
    public static final String CONVERSIONS_COMPLETED = "autospotting.conversions.completed";
    public static final String REVERTS = "autospotting.reverts";
    public static final String FALLBACKS = "autospotting.fallbacks";
    public static final String INTERRUPTIONS = "autospotting.interruptions";
    public static final String EVENTS = "autospotting.events";
    public static final String LEASE_FAILURES = "autospotting.lease.failures";
    public static final String JOB_SKIPPED = "autospotting.job.skipped";
    public static final String RECONCILE_RUNS = "autospotting.reconcile.runs";
    public static final String RECONCILE_ACTIONS = "autospotting.reconcile.actions";
    public static final String RECONCILE_STALENESS_SECONDS = "autospotting.reconcile.staleness_seconds";
    public static final String RECONCILE_DRIFT = "autospotting.reconcile.drift";
    public static final String CLOUD_ERRORS = "autospotting.cloud.errors";
    public static final String LEDGER_ENTRIES = "autospotting.ledger.entries";
    public static final String LEDGER_UNKNOWN = "autospotting.ledger.unknown";
    public static final String DRYRUN_OPERATIONS = "autospotting.dryrun.operations";
    public static final String OPS_PREEMPTED = "autospotting.ops.preempted";

    // Meters introduced by phases 04-08 that §6.1 keeps verbatim (callers moved onto the typed
    // methods below). They are not in the §6.2 table under these exact shapes, but the design says to
    // keep the names core already uses.
    public static final String RECONCILE_GROUPS = "autospotting.reconcile.groups";
    public static final String RECONCILE_ALERT = "autospotting.reconcile.alert";
    public static final String DISPATCH = "autospotting.dispatch";

    // Gauge names (§6.2).
    public static final String GROUPS = "autospotting.groups";
    public static final String GROUPS_DRIFT = "autospotting.groups.drift";
    public static final String OPS_STUCK = "autospotting.ops.stuck";

    // Timer names (§6.2).
    public static final String OPERATION_DURATION = "autospotting.operation.duration";
    public static final String RECONCILE_DURATION = "autospotting.reconcile.duration";
    public static final String CLOUD_CALL = "autospotting.cloud.call";
    public static final String PLAN_DURATION = "autospotting.plan.duration";

    /** DB-count gauge cache TTL (§6.2 "cached 60 s per instance"). The cache itself lives in core. */
    public static final Duration GAUGE_CACHE_TTL = Duration.ofSeconds(60);

    private final MeterRegistry registry;

    public AutoSpottingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ── counters ────────────────────────────────────────────────────────────────

    /** {@code autospotting.conversions.started} — an ENABLE/ADOPT/CONFIG_APPLY first moves to RUNNING. */
    public void conversionStarted(Provider provider, String kind) {
        registry.counter(CONVERSIONS_STARTED, "provider", tag(provider), "kind", kind).increment();
    }

    /** {@code autospotting.conversions.completed} — the op reaches a terminal status. */
    public void conversionCompleted(Provider provider, String kind, String outcome) {
        registry.counter(CONVERSIONS_COMPLETED, "provider", tag(provider), "kind", kind, "outcome", outcome)
                .increment();
    }

    /** {@code autospotting.reverts} — a REVERT op is terminal. */
    public void revert(Provider provider, String outcome) {
        registry.counter(REVERTS, "provider", tag(provider), "outcome", outcome).increment();
    }

    /** {@code autospotting.fallbacks} — a FALLBACK_* op is terminal. */
    public void fallback(Provider provider, String direction, String outcome) {
        registry.counter(FALLBACKS, "provider", tag(provider), "direction", direction, "outcome", outcome)
                .increment();
    }

    /** {@code autospotting.interruptions} — an interruption or preemption is recorded. */
    public void interruption(Provider provider, String region, String source) {
        registry.counter(INTERRUPTIONS, "provider", tag(provider), "region", region, "source", source).increment();
    }

    /** {@code autospotting.events} — the SQS listener (AWS) or preemption tracker (GCP) handled an event. */
    public void event(Provider provider, String outcome) {
        registry.counter(EVENTS, "provider", tag(provider), "outcome", outcome).increment();
    }

    /** {@code autospotting.lease.failures} — acquire/heartbeat/fence failures (AC-SPN-02). */
    public void leaseFailure(String reason) {
        registry.counter(LEASE_FAILURES, "reason", reason).increment();
    }

    /** {@code autospotting.job.skipped} — a scheduled job body did not run (core §3.5). */
    public void jobSkipped(String job, String reason) {
        registry.counter(JOB_SKIPPED, "job", job, "reason", reason).increment();
    }

    /** {@code autospotting.reconcile.runs} — a sweep finished. */
    public void reconcileRun(Provider provider, String outcome) {
        registry.counter(RECONCILE_RUNS, "provider", tag(provider), "outcome", outcome).increment();
    }

    /** {@code autospotting.reconcile.actions} — the core executed a {@code ReconcileAction} (DR-19). */
    public void reconcileAction(Provider provider, String action) {
        registry.counter(RECONCILE_ACTIONS, "provider", tag(provider), "action", action).increment();
    }

    /** {@code autospotting.reconcile.drift} — drift was newly detected (a transition, not every sweep). */
    public void reconcileDrift(Provider provider, String code, String severity) {
        registry.counter(RECONCILE_DRIFT, "provider", tag(provider), "code", code, "severity", severity).increment();
    }

    /** {@code autospotting.cloud.errors} — a cloud call failed after SDK retries (core §9.4 ErrorKind). */
    public void cloudError(Provider provider, String api, String kind) {
        registry.counter(CLOUD_ERRORS, "provider", tag(provider), "api", api, "kind", kind).increment();
    }

    /** {@code autospotting.ledger.entries} — the ledger writer inserted entries (tiers per design-ledger). */
    public void ledgerEntries(Provider provider, String tier, long count) {
        registry.counter(LEDGER_ENTRIES, "provider", tag(provider), "tier", tier).increment(count);
    }

    /** {@code autospotting.ledger.unknown} — an entry was written with an UNKNOWN price. */
    public void ledgerUnknown(Provider provider) {
        registry.counter(LEDGER_UNKNOWN, "provider", tag(provider)).increment();
    }

    /** {@code autospotting.dryrun.operations} — a dry-run op is terminal (never in the real meters). */
    public void dryRunOperation(Provider provider, String kind, String outcome) {
        registry.counter(DRYRUN_OPERATIONS, "provider", tag(provider), "kind", kind, "outcome", outcome).increment();
    }

    /** {@code autospotting.ops.preempted} — an op ended CANCELLED because a revert/fallback preempted it. */
    public void opPreempted(Provider provider, String kind, String by) {
        registry.counter(OPS_PREEMPTED, "provider", tag(provider), "kind", kind, "by", by).increment();
    }

    // ── counters kept verbatim from phases 04-08 (§6.1 "names core already uses") ──

    /** {@code autospotting.reconcile.groups} — one group classified in a sweep ({@code state}, {@code drift}). */
    public void reconcileGroup(String state, String drift) {
        registry.counter(RECONCILE_GROUPS, "state", state, "drift", drift).increment();
    }

    /** {@code autospotting.reconcile.alert} — the reconciler raised an alert for a group {@code state}. */
    public void reconcileAlert(String state) {
        registry.counter(RECONCILE_ALERT, "state", state).increment();
    }

    /** {@code autospotting.dispatch} — one dispatcher lifecycle {@code event} (poll/claimed/rejected). */
    public void dispatch(String event) {
        registry.counter(DISPATCH, "event", event).increment();
    }

    // ── timers ────────────────────────────────────────────────────────────────

    /** {@code autospotting.operation.duration} — created_at to finished_at of a terminal op. */
    public void recordOperationDuration(Provider provider, String kind, String outcome, Duration elapsed) {
        registry.timer(OPERATION_DURATION, "provider", tag(provider), "kind", kind, "outcome", outcome)
                .record(elapsed);
    }

    /** {@code autospotting.reconcile.duration} — one sweep (core §5.1). */
    public void recordReconcileDuration(Provider provider, Duration elapsed) {
        registry.timer(RECONCILE_DURATION, "provider", tag(provider)).record(elapsed);
    }

    /** The {@code autospotting.reconcile.duration} timer, for a caller that wraps its own timing. */
    public Timer reconcileDurationTimer(Provider provider) {
        return registry.timer(RECONCILE_DURATION, "provider", tag(provider));
    }

    /** {@code autospotting.cloud.call} — one SDK call including retries ({@code api} is the operation). */
    public void recordCloudCall(Provider provider, String api, String outcome, Duration elapsed) {
        registry.timer(CLOUD_CALL, "provider", tag(provider), "api", api, "outcome", outcome).record(elapsed);
    }

    /** {@code autospotting.plan.duration} — POST /plans server time (NFR-03 budget). */
    public void recordPlanDuration(Provider provider, Duration elapsed) {
        registry.timer(PLAN_DURATION, "provider", tag(provider)).record(elapsed);
    }

    // ── gauges ────────────────────────────────────────────────────────────────

    /**
     * Bind a per-provider reconciler gauge (one of {@code reconcile.staleness_seconds},
     * {@code ops.stuck}) to a value {@code source} the reconciler updates each sweep. Keeping the
     * registration here means no AutoSpotting meter is ever created outside this bean (AC-OBS-01).
     */
    public void bindReconcilerGauge(String name, Provider provider, Supplier<Number> source) {
        registry.gauge(name, Tags.of("provider", tag(provider)), source, s -> s.get().doubleValue());
    }

    /**
     * Bind {@code autospotting.groups} ({@code provider}, {@code state}) to a value {@code source}.
     * The DB count behind it is read and cached 60 s by the core binder (which owns the spine
     * repository); this method only registers the meter, so no AutoSpotting meter is created outside
     * this bean (AC-OBS-01) and the spine repository stays inside {@code ..core..} (ArchUnit §9.1).
     */
    public void bindGroupStateGauge(Provider provider, GroupState state, Supplier<Number> source) {
        registry.gauge(GROUPS, Tags.of("provider", tag(provider), "state", state.name()),
                source, s -> s.get().doubleValue());
    }

    /**
     * Bind {@code autospotting.groups.drift} ({@code provider}, {@code severity}) to a value
     * {@code source}. Severity is MINOR or MATERIAL only (§6.2). Registration only; see
     * {@link #bindGroupStateGauge}.
     */
    public void bindGroupDriftGauge(Provider provider, DriftStatus severity, Supplier<Number> source) {
        registry.gauge(GROUPS_DRIFT, Tags.of("provider", tag(provider), "severity", severity.name()),
                source, s -> s.get().doubleValue());
    }

    private static String tag(Provider provider) {
        return provider == null ? "unknown" : provider.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** A plain {@link AtomicLong} a caller can hand to a bind* method as the gauge source. */
    public static AtomicLong gaugeSource() {
        return new AtomicLong(0);
    }
}
