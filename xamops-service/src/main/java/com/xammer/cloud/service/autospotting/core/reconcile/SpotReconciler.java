package com.xammer.cloud.service.autospotting.core.reconcile;

import com.xammer.cloud.domain.autospotting.DriftStatus;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationStatus;
import com.xammer.cloud.domain.autospotting.PauseReason;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.domain.autospotting.SpotPlan;
import com.xammer.cloud.repository.autospotting.ReconcilerStore;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseRegistry;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lease.LeaseUnavailableException;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.TransitionRequest;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdc;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import com.xammer.cloud.service.autospotting.spi.SpotProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The reconciler sweep (design-core.md §5.1, §5.3; plan-phase-08.md task 8.1). Per provider, every
 * {@code autospotting.reconciler.interval} (default 5 min) on {@code spotTaskScheduler}, it runs
 * through {@link JobRunner#runLeased}{@code ("reconciler:{provider}")} (AC-SPN-13). It is the only
 * automatic actor: it reads, classifies and enqueues; it never mutates the cloud inline.
 *
 * <p>Per group (§5.1), under the DR-23 cap and order ({@link SweepOrder}):
 * <ol>
 *   <li>If an op holds the group lease (the lease can't be acquired), run only the read-only
 *       {@link HealthPass}. Drift is not classified while an op is in flight.</li>
 *   <li>Otherwise acquire the group lease and call {@code provider.getStatus} (read-only).</li>
 *   <li>Classify drift ({@link DriftClassifier}) and GONE ({@link GoneDetector}).</li>
 *   <li>Apply any pending switch PAUSE/release first ({@link SwitchPauseApplier}, §6.4), with the
 *       GR-06 fenced re-read.</li>
 *   <li>Run the health transitions / fallback decisions through {@code provider.reconcile} (they
 *       never mutate inline; a paused group's health only updates {@code paused_from_state}).</li>
 *   <li>(Plan expiry is owned by the plan path; not acted on here while dark.)</li>
 *   <li>Stamp {@code last_reconciled_at}, emit metrics, release the lease.</li>
 * </ol>
 *
 * <p>Gauges: {@code autospotting.reconcile.staleness_seconds} (max over managed groups of
 * {@code now − last_reconciled_at}) and {@code autospotting.ops.stuck} (APPLYING/REVERTING past the
 * stuck threshold or {@code deadline_at}, AC-SPN-06).
 *
 * <p><b>Dark.</b> No {@link SpotProvider} is registered in production, so {@code getStatus}/
 * {@code reconcile} are never called: the sweep acquires the reconciler job lease, finds no provider
 * to read a group's status, stamps the runtime columns and releases. It mutates no cloud and enables
 * no customer-visible path (plan-phase-08.md "Flag or rollout gate: None").
 */
@Component
public class SpotReconciler {

    private static final Logger log = LoggerFactory.getLogger(SpotReconciler.class);

    static final String JOB_PREFIX = "reconciler:";

    private final ProviderRegistry providers;
    private final SpotGroupRepository groups;
    private final SpotOperationRepository operations;
    private final SpotPlanRepository plans;
    private final SweepOrder sweepOrder;
    private final ReconcilerStore reconcilerStore;
    private final SwitchService switchService;
    private final SwitchPauseApplier switchPauseApplier;
    private final DriftClassifier driftClassifier;
    private final GoneDetector goneDetector;
    private final HealthPass healthPass;
    private final GroupLifecycleService lifecycle;
    private final LeaseService leaseService;
    private final LeaseRegistry leaseRegistry;
    private final JobRunner jobRunner;
    private final com.xammer.cloud.service.autospotting.core.lease.LeaseProperties leaseProperties;
    private final AutoSpottingMetrics metrics;
    private final Duration stuckThreshold;

    /** Per-provider staleness and stuck gauges (updated each sweep, published by Micrometer). */
    private final java.util.Map<Provider, AtomicLong> staleness = new java.util.EnumMap<>(Provider.class);
    private final java.util.Map<Provider, AtomicInteger> stuck = new java.util.EnumMap<>(Provider.class);

    public SpotReconciler(ProviderRegistry providers, SpotGroupRepository groups,
                          SpotOperationRepository operations, SpotPlanRepository plans, SweepOrder sweepOrder,
                          ReconcilerStore reconcilerStore, SwitchService switchService,
                          SwitchPauseApplier switchPauseApplier, DriftClassifier driftClassifier,
                          GoneDetector goneDetector, HealthPass healthPass, GroupLifecycleService lifecycle,
                          LeaseService leaseService, LeaseRegistry leaseRegistry, JobRunner jobRunner,
                          com.xammer.cloud.service.autospotting.core.lease.LeaseProperties leaseProperties,
                          AutoSpottingMetrics metrics,
                          @Value("${autospotting.reconciler.stuck-threshold:2h}") Duration stuckThreshold) {
        this.providers = providers;
        this.groups = groups;
        this.operations = operations;
        this.plans = plans;
        this.sweepOrder = sweepOrder;
        this.reconcilerStore = reconcilerStore;
        this.switchService = switchService;
        this.switchPauseApplier = switchPauseApplier;
        this.driftClassifier = driftClassifier;
        this.goneDetector = goneDetector;
        this.healthPass = healthPass;
        this.lifecycle = lifecycle;
        this.leaseService = leaseService;
        this.leaseRegistry = leaseRegistry;
        this.jobRunner = jobRunner;
        this.leaseProperties = leaseProperties;
        this.metrics = metrics;
        this.stuckThreshold = stuckThreshold;
        for (Provider provider : Provider.values()) {
            AtomicLong stalenessGauge = new AtomicLong(0);
            AtomicInteger stuckGauge = new AtomicInteger(0);
            staleness.put(provider, stalenessGauge);
            stuck.put(provider, stuckGauge);
            // The reconciler keeps the AtomicLong/AtomicInteger it updates each sweep; the gauges are
            // created by AutoSpottingMetrics so no meter is ever registered outside that bean (AC-OBS-01).
            metrics.bindReconcilerGauge(AutoSpottingMetrics.RECONCILE_STALENESS_SECONDS, provider,
                    stalenessGauge::get);
            metrics.bindReconcilerGauge(AutoSpottingMetrics.OPS_STUCK, provider, stuckGauge::get);
        }
    }

    /**
     * The periodic sweep trigger: every provider, through its {@code reconciler:{provider}} job lease
     * (§5.1, FR-SPN-08). Runs on {@code spotTaskScheduler}; the body runs on {@code spotJobsExecutor}
     * under the lease ({@link JobRunner#runLeased}). No {@code runKey}: the reconciler runs every
     * interval (it is not a once-a-day job). The §3.5 lease race skip is counted by {@code JobRunner}.
     */
    @Scheduled(scheduler = "spotTaskScheduler", fixedDelayString = "${autospotting.reconciler.interval-ms:300000}")
    public void scheduledSweep() {
        for (Provider provider : Provider.values()) {
            String job = JOB_PREFIX + provider.name().toLowerCase(Locale.ROOT);
            jobRunner.runLeased(job, null, leaseProperties.reconcilerTtl(),
                    leaseProperties.reconcilerHeartbeat(), Duration.ZERO, () -> sweepProvider(provider));
        }
    }

    /**
     * One provider's sweep. Public so {@code ReconcilerIT} drives it directly (the IT exercises the
     * job-lease race separately). Updates the staleness and stuck gauges even when dark.
     */
    public void sweepProvider(Provider provider) {
        long startNanos = System.nanoTime();
        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder()
                .provider(provider.name().toLowerCase(Locale.ROOT)).open()) {
            List<UUID> order = sweepOrder.forProvider(provider);
            for (UUID groupId : order) {
                SpotGroup group = groups.findById(groupId).orElse(null);
                if (group == null) {
                    continue;
                }
                try {
                    reconcileGroup(provider, group);
                } catch (LeaseUnavailableException storeDown) {
                    log.debug("reconcile group {} skipped: lease store unavailable", groupId);
                } catch (RuntimeException e) {
                    log.warn("reconcile group {} error (continuing): {}", groupId, e.toString());
                }
            }
            expirePlans(provider);
            updateGauges(provider);
        } finally {
            metrics.recordReconcileDuration(provider, Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    private void reconcileGroup(Provider provider, SpotGroup group) {
        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder()
                .groupId(group.getId())
                .cloudAccountId(group.getCloudAccountId())
                .open()) {
            reconcileGroupScoped(provider, group);
        }
    }

    private void reconcileGroupScoped(Provider provider, SpotGroup group) {
        Optional<SpotProvider> providerImpl = providers.find(provider);

        // Step 1: is the group lease held by an op? LeaseRegistry first, then try to acquire (§5.1).
        if (leaseRegistry.isHeldLocally(group.getId())) {
            runHealthPass(providerImpl.orElse(null), group);
            metrics.jobSkipped("reconciler", "lease_held");
            return;
        }
        Optional<LeaseHandle> lease = leaseService.acquireGroup(group.getId(), LeaseService.Component.RECONCILER);
        if (lease.isEmpty()) {
            // Held elsewhere (an op is driving it on another instance): read-only health pass only.
            runHealthPass(providerImpl.orElse(null), group);
            metrics.jobSkipped("reconciler", "lease_held");
            return;
        }

        LeaseHandle handle = lease.get();
        try {
            fullSweep(provider, providerImpl.orElse(null), group, handle);
        } finally {
            leaseService.releaseGroup(handle);
        }
    }

    /** The read-only health pass branch (§5.1 step 1): an op holds the lease. */
    private void runHealthPass(SpotProvider provider, SpotGroup group) {
        if (provider == null) {
            // Dark: no provider to read status. Still stamp the runtime column so staleness advances.
            reconcilerStore.writeRuntime(group.getId(), group.getHealthState(), group.getLastCapacity(),
                    Instant.now());
            return;
        }
        GroupStatus status = provider.getStatus(group, readOnlyView());
        List<ReconcileAction> actions = provider.reconcile(group, status);
        healthPass.run(group, status, actions);
    }

    /** The full sweep under the group lease (§5.1 steps 2-7). */
    private void fullSweep(Provider provider, SpotProvider providerImpl, SpotGroup group, LeaseHandle handle) {
        Instant now = Instant.now();
        if (providerImpl == null) {
            // Dark: no provider. Stamp the runtime column (step 7) and return; nothing to classify.
            reconcilerStore.writeRuntime(group.getId(), group.getHealthState(), group.getLastCapacity(), now);
            metrics.reconcileGroup(group.getState().name(), "NONE");
            return;
        }

        // Step 2: read-only live status.
        GroupStatus status;
        boolean permissionError = false;
        try {
            status = providerImpl.getStatus(group, readOnlyView());
        } catch (RuntimeException e) {
            // A provider surfaces PERMISSION through getStatus normally; a thrown read error is logged
            // and the group is left unchanged this sweep (never treated as drift or GONE).
            log.warn("getStatus failed for group {}: {}", group.getId(), e.toString());
            reconcilerStore.writeRuntime(group.getId(), group.getHealthState(), group.getLastCapacity(), now);
            return;
        }

        // Step 3: GONE (DR-7) and drift (§5.2).
        GoneDetector.Verdict gone = goneDetector.check(group, status, permissionError);
        if (gone == GoneDetector.Verdict.GONE) {
            markGone(handle, group);
            return;
        }
        if (gone == GoneDetector.Verdict.NOT_FOUND_FIRST) {
            reconcilerStore.writeError(group.getId(), GoneDetector.NOT_FOUND_ONCE,
                    "group not found once; GONE requires two sweeps (DR-7)", now);
            reconcilerStore.writeRuntime(group.getId(), status.healthJson(), status.capacityJson(), now);
            return;
        }
        if (gone == GoneDetector.Verdict.PERMISSION) {
            reconcilerStore.writeError(group.getId(), "PERMISSION", "permission error on getStatus", now);
            reconcilerStore.writeRuntime(group.getId(), status.healthJson(), status.capacityJson(), now);
            metrics.reconcileAlert(group.getState().name());
            return;
        }
        // FOUND: clear any stale NOT_FOUND_ONCE/PERMISSION marker.
        if (GoneDetector.NOT_FOUND_ONCE.equals(group.getLastErrorCode())
                || "PERMISSION".equals(group.getLastErrorCode())) {
            reconcilerStore.writeError(group.getId(), null, null, now);
        }

        // Step 4: apply pending switch PAUSE / release first (§6.4), with the GR-06 fenced re-read.
        SwitchState switchState = switchService.readUncached();
        if (switchPauseApplier.pauseEngaged(switchState, group)) {
            PauseReason reason = switchPauseApplier.reReadEngaged(group); // GR-06 fenced re-read
            if (reason != null && switchPauseApplier.applyPause(handle, group, reason)) {
                reconcilerStore.writeRuntime(group.getId(), status.healthJson(), status.capacityJson(), now);
                return; // paused this sweep; health transitions are not written over the pause (DR-01)
            }
        } else {
            switchPauseApplier.applyRelease(handle, group, switchState);
        }

        // Drift is classified only for groups with an owned fingerprint (§5.2), and never while an op
        // is in flight (handled by the lease-held branch).
        if (DriftClassifier.classifiable(group)) {
            classifyAndRecordDrift(handle, group, status, permissionError, now);
        }

        // Step 5: health transitions / fallback via provider.reconcile (never mutate inline). The
        // actions that need the lease are applied here; a paused group's health only updates
        // paused_from_state (DR-01). Dark until a provider returns actions.
        List<ReconcileAction> actions = providerImpl.reconcile(group, status);
        applyReconcileActions(handle, group, status, actions);

        // Step 7: stamp runtime columns, metrics.
        reconcilerStore.writeRuntime(group.getId(), status.healthJson(), status.capacityJson(), now);
        metrics.reconcileGroup(group.getState().name(),
                group.getDriftStatus() == null ? "NONE" : group.getDriftStatus().name());
    }

    private void classifyAndRecordDrift(LeaseHandle handle, SpotGroup group, GroupStatus status,
                                        boolean permissionError, Instant now) {
        DriftClassifier.Classification c = driftClassifier.classify(group, status, permissionError,
                false, false, false, false, false);
        if (c.code() == DriftClassifier.Code.NONE) {
            if (group.getDriftStatus() != DriftStatus.NONE) {
                reconcilerStore.writeDrift(group.getId(), DriftStatus.NONE, null, now);
            }
            return;
        }
        reconcilerStore.writeDrift(group.getId(), c.materiality(),
                "{\"code\":\"" + c.code().name() + "\"}", now);
        if (c.alert()) {
            metrics.reconcileAlert(group.getState().name());
        }
        if (c.pausesDrift() && isStable(group.getState())) {
            TransitionRequest req = TransitionRequest
                    .of(GroupState.PAUSED, c.code().name(), Actor.system(), group.getCurrentOperationId(), now)
                    .withPause(PauseReason.DRIFT, group.getState());
            lifecycle.transition(handle, group, req);
        }
    }

    private void applyReconcileActions(LeaseHandle handle, SpotGroup group, GroupStatus status,
                                       List<ReconcileAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        for (ReconcileAction action : actions) {
            switch (action) {
                case MARK_GONE -> {
                    // GONE here only if the provider decided it off a prior NOT_FOUND; the DR-7 two-sweep
                    // rule is enforced by GoneDetector above, so reconcile MARK_GONE is advisory.
                    if (goneDetector.check(group, status, false) == GoneDetector.Verdict.GONE) {
                        markGone(handle, group);
                    }
                }
                case MARK_DEGRADED -> transitionIfAllowed(handle, group, GroupState.DEGRADED, "HEALTH_DEGRADED");
                case MARK_RECOVERED -> transitionIfAllowed(handle, group, recoveredTarget(group), "HEALTH_RECOVERED");
                case ALERT -> metrics.reconcileAlert(group.getState().name());
                // ENQUEUE_FALLBACK / REQUEST_COMPENSATION / UPDATE_PAUSED_FROM / PAUSE / UNPAUSE are the
                // health-pass / switch-pause responsibilities; left to those paths (dark here).
                default -> { /* no-op in the dark phase */ }
            }
        }
    }

    private void transitionIfAllowed(LeaseHandle handle, SpotGroup group, GroupState to, String reason) {
        if (to == null) {
            return;
        }
        try {
            lifecycle.transition(handle, group, TransitionRequest.of(to, reason, Actor.system(),
                    group.getCurrentOperationId(), Instant.now()));
        } catch (IllegalArgumentException forbidden) {
            log.debug("reconcile transition {} -> {} not allowed for group {}: {}", group.getState(), to,
                    group.getId(), forbidden.getMessage());
        }
    }

    private void markGone(LeaseHandle handle, SpotGroup group) {
        try {
            lifecycle.transition(handle, group, TransitionRequest.of(GroupState.GONE, "GROUP_DELETED",
                    Actor.system(), group.getCurrentOperationId(), Instant.now()));
            failInFlightOp(group);
        } catch (IllegalArgumentException forbidden) {
            log.debug("GONE transition not allowed for group {} in {}: {}", group.getId(), group.getState(),
                    forbidden.getMessage());
        }
    }

    /** An in-flight op on a GONE group ends FAILED with {@code GROUP_DELETED} (§5.2 DR-7). */
    private void failInFlightOp(SpotGroup group) {
        for (SpotOperation op : operations.findNonTerminalByGroup(group.getId())) {
            operations.finish(op.getId(), OperationStatus.FAILED.name(), "GROUP_DELETED",
                    "group deleted (DR-7)", null, Instant.now());
        }
    }

    /**
     * Step 6 (§7.4): expire OPEN plans past their {@code expires_at}. Each is marked EXPIRED; an
     * ENABLE plan that left its group PLANNED returns the group to the plan's {@code from_state}
     * (UNMANAGED or REVERTED) under the row lock when no other OPEN plan remains, so a PLANNED group
     * never lingers with no open plan (§7.4). Runs once per provider sweep; no cloud is touched.
     */
    private void expirePlans(Provider provider) {
        Instant now = Instant.now();
        List<SpotPlan> expired;
        try {
            expired = plans.findExpiredOpen(now);
        } catch (RuntimeException e) {
            log.debug("plan expiry sweep skipped: {}", e.toString());
            return;
        }
        for (SpotPlan plan : expired) {
            SpotGroup group = groups.findById(plan.getGroupId()).orElse(null);
            if (group == null || group.getProvider() != provider) {
                continue; // another provider's plan, or a rowless/synthetic group: handled elsewhere
            }
            if (plans.expireIfOpen(plan.getId()) != 1) {
                continue; // a confirm/supersede won the race; nothing to do
            }
            if (plan.getKind() == com.xammer.cloud.domain.autospotting.OperationKind.ENABLE
                    && group.getState() == GroupState.PLANNED
                    && plans.findOpenByGroup(group.getId()).isEmpty()) {
                returnFromPlanned(group, plan.getFromState());
            }
        }
    }

    /** Return a PLANNED group to the expired plan's {@code from_state} under the row lock (§7.4). */
    private void returnFromPlanned(SpotGroup group, GroupState fromState) {
        GroupState target = (fromState == GroupState.UNMANAGED || fromState == GroupState.REVERTED)
                ? fromState : GroupState.UNMANAGED;
        try {
            GroupLifecycleService.Result result = lifecycle.transitionUnderRowLock(group,
                    TransitionRequest.of(target, "PLAN_EXPIRED", Actor.system(), null, Instant.now()));
            if (result == GroupLifecycleService.Result.STALE && log.isDebugEnabled()) {
                log.debug("plan expiry: group {} return to {} missed the guard (stale)", group.getId(), target);
            }
        } catch (IllegalArgumentException forbidden) {
            log.debug("plan expiry: return {} -> {} not allowed for group {}: {}", group.getState(), target,
                    group.getId(), forbidden.getMessage());
        }
    }

    private void updateGauges(Provider provider) {
        Double maxStaleness = reconcilerStore.maxStalenessSeconds(provider);
        staleness.get(provider).set(maxStaleness == null ? 0L : Math.round(maxStaleness));
        int stuckCount = countStuck(provider);
        stuck.get(provider).set(stuckCount);
    }

    private int countStuck(Provider provider) {
        int count = 0;
        Instant threshold = Instant.now().minus(stuckThreshold);
        for (GroupState state : List.of(GroupState.APPLYING, GroupState.REVERTING)) {
            for (SpotGroup group : groups.findByProviderAndState(provider, state)) {
                for (SpotOperation op : operations.findNonTerminalByGroup(group.getId())) {
                    boolean pastDeadline = op.getDeadlineAt() != null && op.getDeadlineAt().isBefore(Instant.now());
                    boolean pastThreshold = group.getStateChangedAt() != null
                            && group.getStateChangedAt().isBefore(threshold);
                    if (pastDeadline || pastThreshold) {
                        count++;
                        break;
                    }
                }
            }
        }
        return count;
    }

    private static GroupState recoveredTarget(SpotGroup group) {
        // MARK_RECOVERED goes to the stable state of the group's config_mode (§5.2): FALLBACK when
        // config_mode=FALLBACK, else ACTIVE. The provider decided recovery; the target follows mode.
        return group.getConfigMode() == com.xammer.cloud.domain.autospotting.ConfigMode.FALLBACK
                ? GroupState.FALLBACK : GroupState.ACTIVE;
    }

    private static boolean isStable(GroupState state) {
        return state == GroupState.ACTIVE || state == GroupState.DEGRADED || state == GroupState.FALLBACK;
    }

    /** The read-only cloud view. Dark: no real view is wired; a registered provider supplies its own. */
    private ReadOnlyCloudView readOnlyView() {
        return new ReadOnlyCloudView() {
        };
    }
}
