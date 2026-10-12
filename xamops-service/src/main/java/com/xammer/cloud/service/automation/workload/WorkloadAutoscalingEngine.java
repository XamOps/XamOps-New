package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.service.automation.workload.usage.ContainerUsageAggregate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Decides what one workload's resources should be.
 *
 * <h2>Shape</h2>
 *
 * A <strong>pure function</strong>: no I/O, no Spring, plain records in and out, and the only
 * ambient input is an injectable {@link Clock}. That is what lets the nine measured reference
 * workloads be asserted as literal fixtures in millisecond unit tests with no cluster — and it is
 * what the Go reference could not do, because its decision logic was interleaved with Prometheus
 * queries and Kubernetes patches.
 *
 * <h2>One engine, not two</h2>
 *
 * The decision carries both a vertical half (implemented) and a horizontal half (modelled,
 * {@code NOT_EVALUATED}), reconciled in {@link #reconcile}. The measured failure was two controllers
 * rewriting one workload in one cycle with no cross-reference, disagreeing about a CPU request that
 * <em>is</em> the HPA's utilisation denominator. One component has to own both.
 *
 * <h2>Order of operations</h2>
 *
 * <ol>
 *   <li>{@linkplain #gate Gates} — the first match stops evaluation entirely.</li>
 *   <li>Per-container targets from the policy's percentile plus headroom, with the limit standing in
 *       for demand only where usage is <em>measurably</em> censored by it
 *       ({@link #demandCensoredByLimit}).</li>
 *   <li>Floors, clamping <strong>decreases only</strong>.</li>
 *   <li>The node-capacity cap, computed over all containers together.</li>
 *   <li>Asymmetric relative-<em>and</em>-absolute hysteresis against the <strong>current</strong>
 *       request, each absolute half capped at the largest move expressible in its direction.</li>
 *   <li>The OOM guard, then cooldown — which an OOM-driven memory increase outranks.</li>
 *   <li>{@link #reconcile}: a resource-metric HPA freezes that resource's request.</li>
 *   <li>QoS-preserving {@code (request, limit)} pairs, validated at construction.</li>
 * </ol>
 */
public final class WorkloadAutoscalingEngine {

    private final Clock clock;

    public WorkloadAutoscalingEngine() {
        this(Clock.systemUTC());
    }

    public WorkloadAutoscalingEngine(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock is mandatory");
        }
        this.clock = clock;
    }

    public WorkloadDecision decide(WorkloadSnapshot snapshot, WorkloadPolicy policy, NodeCapacity capacity) {
        if (snapshot == null || policy == null || capacity == null) {
            throw new IllegalArgumentException("snapshot, policy and capacity are all mandatory");
        }
        Instant now = clock.instant();
        Provenance provenance = provenanceOf(snapshot, policy, now);

        SkipReason skip = gate(snapshot, policy, now);
        if (skip != null) {
            return new WorkloadDecision(snapshot.ref(), skip, EnumSet.noneOf(Finding.class), provenance,
                    false, VerticalDecision.skipped(), HorizontalDecision.notEvaluated());
        }

        EnumSet<Finding> findings = EnumSet.noneOf(Finding.class);
        List<Draft> drafts = new ArrayList<>();
        for (ContainerSnapshot container : snapshot.containers()) {
            drafts.add(draftFor(container, policy, findings));
        }

        applyNodeCapacityCap(drafts, policy, capacity, findings);
        applyHysteresis(drafts, policy);
        applyCooldown(drafts, policy, now);
        reconcile(drafts, snapshot, findings);

        QosClass qosClass = snapshot.qosClass();
        List<ContainerDecision> decisions = new ArrayList<>();
        for (Draft draft : drafts) {
            decisions.add(draft.toDecision(qosClass, policy));
        }
        VerticalDecision vertical = new VerticalDecision(decisions);

        boolean autoApply = policy.mode() == WorkloadMode.AUTO
                && vertical.hasRecommendations()
                && !findings.contains(Finding.REQUESTS_UNSET)
                && decisions.stream().filter(ContainerDecision::hasRecommendation)
                        .allMatch(ContainerDecision::autoApplyEligible);

        return new WorkloadDecision(snapshot.ref(), null, findings, provenance, autoApply,
                vertical, HorizontalDecision.notEvaluated());
    }

    // ------------------------------------------------------------------ gates

    /**
     * The gates, in order; the first match wins and the workload carries no container decisions at
     * all afterwards.
     *
     * <p>{@code PROTECTED_NAMESPACE} and {@code SINGLE_REPLICA} are the two guards the Go rightsizer
     * did honour correctly. They are kept, and moved into the engine so the vertical half and the
     * future horizontal half read one rule — the Go HPA manager not honouring single-replica
     * protection while the rightsizer did is precisely the inconsistency one engine removes.
     *
     * @return the reason to skip, or null to evaluate
     */
    private SkipReason gate(WorkloadSnapshot snapshot, WorkloadPolicy policy, Instant now) {
        String namespace = snapshot.ref().namespace().toLowerCase(Locale.ROOT);
        if (WorkloadPolicyDefaults.PROTECTED_NAMESPACES.contains(namespace)) {
            return SkipReason.PROTECTED_NAMESPACE;
        }
        if (snapshot.replicas() <= 1 && policy.protectSingleReplica()) {
            return SkipReason.SINGLE_REPLICA;
        }
        if (snapshot.rolloutInProgress()) {
            return SkipReason.ROLLOUT_IN_PROGRESS;
        }
        if (snapshot.lockAnnotationActive()) {
            return SkipReason.LOCKED;
        }
        HpaView hpa = snapshot.effectiveHpa();
        if (hpa != null && hpa.scaledWithin(WorkloadPolicyDefaults.HPA_VOLATILITY_WINDOW, now)) {
            return SkipReason.HPA_VOLATILE;
        }
        if (!hasEnoughHistory(snapshot)) {
            return SkipReason.INSUFFICIENT_HISTORY;
        }
        if (policy.mode() == WorkloadMode.OFF) {
            return SkipReason.POLICY_OFF;
        }
        return null;
    }

    /**
     * Enough history means enough for <em>every</em> container: the least-covered container decides,
     * because a recommendation for a sidecar nobody measured is as wrong as one for the main
     * container.
     */
    private boolean hasEnoughHistory(WorkloadSnapshot snapshot) {
        for (ContainerSnapshot container : snapshot.containers()) {
            ContainerUsageAggregate usage = container.usage();
            if (usage.sampleCoverage() < WorkloadPolicyDefaults.MIN_SAMPLE_COVERAGE) {
                return false;
            }
            if (usage.window().compareTo(WorkloadPolicyDefaults.MIN_WINDOW) < 0) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ per-container targets

    private Draft draftFor(ContainerSnapshot container, WorkloadPolicy policy, EnumSet<Finding> findings) {
        Draft draft = new Draft(container);
        ContainerUsageAggregate usage = container.usage();
        ResourceEnvelope current = container.current();

        if (current.anyRequestUnset()) {
            findings.add(Finding.REQUESTS_UNSET);
            draft.reasons.add(ContainerReason.REQUESTS_UNSET);
        }
        if (usage.oomKillsInWindow() > 0) {
            findings.add(Finding.OOM_RISK);
        }
        if (isIdle(usage, current, policy)) {
            // A finding, not a branch. The guarantee that an idle workload never receives a
            // near-zero request comes from the floors below, which is strictly stronger: branching
            // here would leave the near-zero path reachable for a workload just above the idle
            // threshold. The honest remedy for a genuinely idle workload is scale-to-zero or
            // deletion, which this engine does not do -- and for a small idle workload the floors
            // and hysteresis produce no recommendation anyway.
            findings.add(Finding.IDLE);
        }

        if (usage.cpuThrottleRatio() >= WorkloadPolicyDefaults.THROTTLE_RATIO_THRESHOLD) {
            findings.add(Finding.THROTTLED);
        }

        // ---- CPU
        long cpuPercentile = usage.cpuMillis().at(policy.percentile());
        long cpuBasis = cpuPercentile;
        if (demandCensoredByLimit(usage, current, cpuPercentile)) {
            // Usage is pinned at the limit, so it is not a measurement of demand. Deriving the
            // target from the limit is what actually relieves the throttling; the Go engine raised
            // the request to match the censored usage and left the pod throttled at ratio 1.0.
            //
            // max(), not the limit outright: the basis must never fall below what was actually
            // measured, and must never exceed it by more than the censoring tolerance allows.
            cpuBasis = Math.max(cpuPercentile, current.cpuLimitMillis());
            draft.reasons.add(ContainerReason.DEMAND_CENSORED_BY_LIMIT);
        }
        draft.cpuTarget = applyFloor(withHeadroom(cpuBasis, policy.headroomPct()),
                toLong(current.cpuRequestMillis()), policy.cpuFloorMillis(), draft);

        // ---- Memory
        long memBasis = usage.memBytes().at(policy.percentile());
        draft.memTarget = applyFloor(withHeadroom(memBasis, policy.headroomPct()),
                current.memRequestBytes(), policy.memFloorBytes(), draft);

        // ---- OOM guard: a memory shortfall OOM-kills immediately and irreversibly, so a decrease
        // is refused whatever the percentile says. Judged against the smallest request a live pod
        // runs with (review-2 SF2): when the pods disagree, current is the template, and a raise for
        // the pods being killed would otherwise read as a decrease from it and be dropped.
        Long oomBasis = container.oomGuardMemBytes();
        if (usage.oomKillsInWindow() > 0 && draft.memTarget != null
                && oomBasis != null && draft.memTarget < oomBasis) {
            draft.memTarget = null;
            draft.reasons.add(ContainerReason.OOM_RISK);
        }
        return draft;
    }

    /**
     * Whether measured CPU usage is <strong>actually</strong> censored by the container's own limit,
     * which is the only condition under which that limit may stand in for demand.
     *
     * <p>Both halves are required: CFS throttling at or above
     * {@link WorkloadPolicyDefaults#THROTTLE_RATIO_THRESHOLD}, <em>and</em> a measured percentile
     * within {@link WorkloadPolicyDefaults#CENSORED_USAGE_FRACTION_OF_LIMIT} of the limit. Throttling
     * alone is not evidence of censoring — a container that bursts briefly can throttle at ratio 0.25
     * while its p95 sits far below the ceiling, and treating its limit as demand turned a measured
     * 80m into a 1250m request.
     *
     * <p>This is also the bound on the series. The limit moves with the request
     * ({@link QosRules#limitFor}), so an ungated limit-derived basis would make each cycle's input a
     * number the previous cycle wrote and the request would ratchet until the node-capacity cap bound
     * it — the measured {@code 1Mi → 4Mi} thrash loop running in the opposite direction. With the
     * gate, the basis is at most {@code percentile / 0.9} and every increase therefore stays anchored
     * to measurement.
     */
    private static boolean demandCensoredByLimit(ContainerUsageAggregate usage, ResourceEnvelope current,
                                                 long cpuPercentileMillis) {
        Integer limit = current.cpuLimitMillis();
        if (limit == null || limit <= 0) {
            return false;
        }
        if (usage.cpuThrottleRatio() < WorkloadPolicyDefaults.THROTTLE_RATIO_THRESHOLD) {
            return false;
        }
        long censoringThreshold =
                Math.round(limit * WorkloadPolicyDefaults.CENSORED_USAGE_FRACTION_OF_LIMIT);
        return cpuPercentileMillis >= censoringThreshold;
    }

    /**
     * Idle means effectively no load: CPU at or below {@code max(1m, 1% of the request)} and memory
     * at or below its floor.
     */
    private boolean isIdle(ContainerUsageAggregate usage, ResourceEnvelope current, WorkloadPolicy policy) {
        long cpu = usage.cpuMillis().at(policy.percentile());
        long cpuThreshold = 1;
        if (current.cpuRequestMillis() != null) {
            cpuThreshold = Math.max(1,
                    Math.round(current.cpuRequestMillis() * WorkloadPolicyDefaults.IDLE_CPU_FRACTION_OF_REQUEST));
        }
        return cpu <= cpuThreshold && usage.memBytes().at(policy.percentile()) <= policy.memFloorBytes();
    }

    /**
     * Floors clamp <strong>decreases only</strong> (audit bug 4).
     *
     * <ul>
     *   <li>{@code target < floor} and {@code current > floor} → recommend the floor: a legitimate,
     *       smaller decrease.</li>
     *   <li>{@code target < floor} and {@code current <= floor} → recommend nothing. Increases are
     *       driven only by measured demand, never by the floor's existence, so a workload that
     *       already sits below the floor is never raised to reach it.</li>
     *   <li>{@code current} unset → recommend {@code max(target, floor)}. There is no deliberate
     *       choice to respect, and an advisory of {@code 1m} would reproduce exactly the fabricated
     *       {@code 1m / 1Mi} output this engine exists to prevent.</li>
     *   <li>otherwise → the target.</li>
     * </ul>
     *
     * @return the floored target, or null to recommend nothing for this resource
     */
    private Long applyFloor(long target, Long current, long floor, Draft draft) {
        if (current == null) {
            if (target < floor) {
                draft.reasons.add(ContainerReason.FLOOR_CLAMPED);
                return floor;
            }
            return target;
        }
        if (target >= floor) {
            return target;
        }
        if (current > floor) {
            draft.reasons.add(ContainerReason.FLOOR_CLAMPED);
            return floor;
        }
        draft.reasons.add(ContainerReason.BELOW_FLOOR_NO_RAISE);
        return null;
    }

    /** {@code value × (1 + headroom/100)}, in exact integer arithmetic, rounded half-up. */
    private static long withHeadroom(long value, int headroomPct) {
        return (value * (100L + headroomPct) + 50) / 100;
    }

    // ------------------------------------------------------------------ node capacity

    /**
     * Caps each recommendation so the pod's total requests fit the largest schedulable node times
     * {@code nodeCapacityFraction}.
     *
     * <p>A recommendation that cannot be scheduled is worse than none: the pod goes {@code Pending}
     * and the workload loses capacity it had. Measured reference: a workload asking for 32 CPU /
     * 64Gi on 1930m / 3294Mi nodes, {@code Pending} for 8 hours.
     *
     * <p>Siblings are measured at their <em>uncapped</em> target or current value. Capping each
     * container against already-capped siblings would make the result depend on container order.
     *
     * <p>If the cap would push an increase down to or below the current request, nothing is
     * recommended for that resource — the cap must not turn "we would like more" into "take some
     * away".
     */
    private void applyNodeCapacityCap(List<Draft> drafts, WorkloadPolicy policy, NodeCapacity capacity,
                                      EnumSet<Finding> findings) {
        long cpuBudget = capacity.cpuBudgetMillis(policy.nodeCapacityFraction());
        long memBudget = capacity.memBudgetBytes(policy.nodeCapacityFraction());

        for (Draft draft : drafts) {
            long cpuSiblings = 0;
            long memSiblings = 0;
            for (Draft other : drafts) {
                if (other == draft) {
                    continue;
                }
                cpuSiblings += other.cpuTarget != null
                        ? other.cpuTarget : orZero(toLong(other.container.current().cpuRequestMillis()));
                memSiblings += other.memTarget != null
                        ? other.memTarget : orZero(other.container.current().memRequestBytes());
            }
            draft.cpuTarget = cap(draft.cpuTarget, toLong(draft.container.current().cpuRequestMillis()),
                    cpuBudget - cpuSiblings, draft, findings);
            draft.memTarget = cap(draft.memTarget, draft.container.current().memRequestBytes(),
                    memBudget - memSiblings, draft, findings);
        }
    }

    private Long cap(Long target, Long current, long allowed, Draft draft, EnumSet<Finding> findings) {
        if (target == null || target <= allowed) {
            return target;
        }
        draft.reasons.add(ContainerReason.NODE_CAPACITY_CAPPED);
        findings.add(Finding.NODE_CAPACITY_CAPPED);
        if (allowed <= 0) {
            return null;
        }
        if (current != null && allowed <= current) {
            // The cap bites below where we already are; leave it alone rather than shrink it.
            return null;
        }
        return allowed;
    }

    // ------------------------------------------------------------------ hysteresis and cooldown

    /**
     * Drops any target whose delta does not clear both the relative and the absolute band for its
     * direction.
     *
     * <p>Always measured against the <strong>current</strong> request, which is what makes the
     * measured {@code 1Mi → 4Mi} thrash loop structurally impossible: a second cycle computing the
     * same target sees a zero delta and recommends nothing.
     *
     * <p>A container with no current request is exempt: there is no baseline to take a percentage
     * of, and suppressing the advisory would hide the {@code REQUESTS_UNSET} finding's whole point.
     *
     * <p>Each band's <strong>absolute</strong> half is capped at the largest move this engine can
     * express in that direction at this container's scale — headroom for an increase, the distance to
     * the floor for a decrease ({@link HysteresisBand#cappedTo}). An absolute threshold may filter a
     * move; it may never make a direction unreachable, which a flat threshold does below a certain
     * request size. The <strong>relative</strong> half is never capped, and it is what keeps doing
     * the filtering at small scale.
     */
    private void applyHysteresis(List<Draft> drafts, WorkloadPolicy policy) {
        for (Draft draft : drafts) {
            ResourceEnvelope current = draft.container.current();
            Long currentCpu = toLong(current.cpuRequestMillis());
            if (draft.cpuTarget != null && currentCpu != null) {
                HysteresisBand band = reachableBand(policy.cpuIncrease(), policy.cpuDecrease(),
                        currentCpu, draft.cpuTarget, policy.cpuFloorMillis(), policy.headroomPct());
                if (!band.clears(currentCpu, draft.cpuTarget)) {
                    draft.cpuTarget = null;
                    draft.reasons.add(ContainerReason.WITHIN_HYSTERESIS);
                }
            }
            if (draft.memTarget != null && current.memRequestBytes() != null) {
                long currentMem = current.memRequestBytes();
                HysteresisBand band = reachableBand(policy.memIncrease(), policy.memDecrease(),
                        currentMem, draft.memTarget, policy.memFloorBytes(), policy.headroomPct());
                if (!band.clears(currentMem, draft.memTarget)) {
                    draft.memTarget = null;
                    draft.reasons.add(ContainerReason.WITHIN_HYSTERESIS);
                }
            }
            // A target identical to the current value is not a change, whatever the bands say.
            if (draft.cpuTarget != null && draft.cpuTarget.equals(currentCpu)) {
                draft.cpuTarget = null;
            }
            if (draft.memTarget != null && draft.memTarget.equals(current.memRequestBytes())) {
                draft.memTarget = null;
            }
        }
    }

    /**
     * The band for this move's direction, with its absolute half capped at what the engine can
     * actually express — so the threshold can never be unsatisfiable by construction.
     *
     * <p>The bound is a property of the arithmetic, not of the workload:
     *
     * <ul>
     *   <li><strong>Increase:</strong> {@code request × headroomPct/100}. A container whose demand
     *       basis is capped at its own request — a Guaranteed-shaped one, where the limit is the
     *       request and CFS caps measured usage at the limit — has only headroom to generate a
     *       delta. The engine writes containers into exactly that state when it floors them, so
     *       without the cap its own most aggressive recommendation would be irreversible.</li>
     *   <li><strong>Decrease:</strong> {@code request − floor}. Floors clamp every target, so no
     *       larger decrease exists to be recommended.</li>
     * </ul>
     *
     * @see HysteresisBand#cappedTo(long)
     */
    private static HysteresisBand reachableBand(HysteresisBand increase, HysteresisBand decrease,
                                                long current, long target, long floor, int headroomPct) {
        if (target > current) {
            return increase.cappedTo(current * headroomPct / 100);
        }
        return decrease.cappedTo(current - floor);
    }

    /**
     * Nothing new is recommended within {@code cooldownMinutes} of an apply — <strong>except</strong>
     * a memory increase on a container that OOM-killed inside the window.
     *
     * <p>The cooldown exists to damp thrash: a second opinion arriving minutes after the first is
     * usually the engine arguing with its own write. An OOM kill is not that. It is evidence, it
     * arrived <em>after</em> the apply, and the sequence that produces it is ordinary — a memory
     * decrease is applied, the container OOM-kills under the new request, the next cycle lands inside
     * the cooldown. Suppressing the remedy there would have the operator reading {@code OOM_RISK} in
     * the findings beside {@code COOLDOWN} as the reason nothing was proposed, for the rest of the
     * hour.
     *
     * <p>The exemption is deliberately narrow: memory only, increases only, and only with kills
     * measured in the window. The upstream guard already refuses a memory <em>decrease</em> on the
     * same evidence, so the two together make this container's memory increase-only until the kills
     * age out of the window. CPU is not exempt — an OOM kill says nothing about CPU demand.
     *
     * <p>{@code COOLDOWN} is recorded only when something was actually suppressed, so the reason
     * never claims a hold that did not happen.
     */
    private void applyCooldown(List<Draft> drafts, WorkloadPolicy policy, Instant now) {
        Instant lastApplied = policy.lastAppliedAt();
        if (lastApplied == null || policy.cooldownMinutes() <= 0) {
            return;
        }
        if (lastApplied.isBefore(now.minus(Duration.ofMinutes(policy.cooldownMinutes())))) {
            return;
        }
        for (Draft draft : drafts) {
            boolean memoryExempt = isOomDrivenMemoryIncrease(draft);
            boolean suppressed = false;
            if (draft.cpuTarget != null) {
                draft.cpuTarget = null;
                suppressed = true;
            }
            if (draft.memTarget != null && !memoryExempt) {
                draft.memTarget = null;
                suppressed = true;
            }
            if (memoryExempt) {
                draft.reasons.add(ContainerReason.OOM_RISK);
            }
            if (suppressed) {
                draft.reasons.add(ContainerReason.COOLDOWN);
            }
        }
    }

    /**
     * Whether this draft's memory target is a <em>raise</em> on a container with OOM kills measured
     * in the window — the one case the cooldown does not outrank.
     *
     * <p>An unset current request counts as a raise, which is the same convention
     * {@link Draft#changeKind} uses. Such a recommendation is advisory and never auto-applied
     * ({@code REQUESTS_UNSET}), so the exemption only affects what the operator is shown.
     */
    private static boolean isOomDrivenMemoryIncrease(Draft draft) {
        if (draft.memTarget == null || draft.container.usage().oomKillsInWindow() <= 0) {
            return false;
        }
        // The same basis as the OOM guard: a raise for the pods that are being killed.
        Long currentMem = draft.container.oomGuardMemBytes();
        return currentMem == null || draft.memTarget > currentMem;
    }

    // ------------------------------------------------------------------ coexistence (bug 5)

    /**
     * The single place the vertical and horizontal halves meet.
     *
     * <p>Implemented this run — the conservative half: a {@code Resource: cpu} HPA metric
     * <strong>freezes the CPU request</strong>, because the request is the HPA's utilisation
     * denominator and changing it silently moves the HPA's set point. Memory stays eligible when only
     * CPU is managed, and vice versa. On a Guaranteed-shaped container the freeze extends to that
     * resource's limit, since moving the request alone would downgrade the class — so an HPA on a
     * Guaranteed workload means no change to that resource at all.
     *
     * <p>Measured case: cutting {@code php-apache}'s CPU request 200m → 144m would have moved
     * measured utilisation from ~65% to ~90% at unchanged load, while a second controller
     * simultaneously raised the HPA target from 50% to 80%.
     *
     * <p>The alternative — co-adjustment of the HPA's target utilisation — requires writing the HPA,
     * which this engine does not yet own. See {@link HorizontalDecision#change} for the arithmetic
     * and where it lands.
     */
    private void reconcile(List<Draft> drafts, WorkloadSnapshot snapshot, EnumSet<Finding> findings) {
        HpaView hpa = snapshot.effectiveHpa();
        if (hpa == null) {
            return;
        }
        if (!hpa.cpuResourceMetricPresent() && !hpa.memResourceMetricPresent()) {
            return;
        }
        findings.add(Finding.HPA_COEXISTENCE);
        for (Draft draft : drafts) {
            boolean guaranteed = QosRules.shapeOf(draft.container.current()) == QosRules.Shape.GUARANTEED_SHAPED;
            if (hpa.cpuResourceMetricPresent()) {
                draft.cpuFrozen = true;
                draft.cpuTarget = null;
                draft.reasons.add(ContainerReason.HPA_CPU_MANAGED);
                if (guaranteed) {
                    draft.reasons.add(ContainerReason.QOS_PRESERVED_NO_CHANGE);
                }
            }
            if (hpa.memResourceMetricPresent()) {
                draft.memFrozen = true;
                draft.memTarget = null;
                draft.reasons.add(ContainerReason.HPA_MEMORY_MANAGED);
                if (guaranteed) {
                    draft.reasons.add(ContainerReason.QOS_PRESERVED_NO_CHANGE);
                }
            }
        }
    }

    // ------------------------------------------------------------------ provenance

    private Provenance provenanceOf(WorkloadSnapshot snapshot, WorkloadPolicy policy, Instant now) {
        ContainerUsageAggregate first = snapshot.containers().get(0).usage();
        double coverage = snapshot.containers().stream()
                .mapToDouble(c -> c.usage().sampleCoverage())
                .min().orElse(0);
        Instant start = snapshot.containers().stream()
                .map(c -> c.usage().windowStart()).min(Instant::compareTo).orElse(first.windowStart());
        Instant end = snapshot.containers().stream()
                .map(c -> c.usage().windowEnd()).max(Instant::compareTo).orElse(first.windowEnd());
        return new Provenance(policy.percentile(), policy.windowHours(), start, end, coverage,
                first.sourceName(), now, policy.source());
    }

    private static Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    // ------------------------------------------------------------------ the mutable draft

    /** One container's decision while it is still being computed. Never escapes this class. */
    private static final class Draft {

        private final ContainerSnapshot container;
        private final EnumSet<ContainerReason> reasons = EnumSet.noneOf(ContainerReason.class);
        private Long cpuTarget;
        private Long memTarget;
        private boolean cpuFrozen;
        private boolean memFrozen;

        private Draft(ContainerSnapshot container) {
            this.container = container;
        }

        /**
         * Turns the surviving targets into a validated {@code (request, limit)} pair per resource.
         *
         * <p>Limits move with their requests through {@link QosRules#limitFor}, and the whole
         * envelope is built by {@link ResourceEnvelope#recommendation}, which rejects a non-positive
         * request, a request above its limit, and any QoS-shape change. So an invalid recommendation
         * cannot exist as a value here, let alone be served or applied.
         */
        private ContainerDecision toDecision(QosClass qosClass, WorkloadPolicy policy) {
            ResourceEnvelope current = container.current();
            if (cpuTarget == null && memTarget == null) {
                if (reasons.isEmpty()) {
                    reasons.add(ContainerReason.WITHIN_HYSTERESIS);
                }
                return ContainerDecision.noChange(container.name(), qosClass, current, reasons);
            }

            QosRules.Shape shape = QosRules.shapeOf(current);

            Integer newCpuRequest = current.cpuRequestMillis();
            Integer newCpuLimit = current.cpuLimitMillis();
            if (cpuTarget != null) {
                newCpuRequest = Math.toIntExact(cpuTarget);
                Long limit = QosRules.limitFor(shape, toLong(current.cpuRequestMillis()),
                        toLong(current.cpuLimitMillis()), cpuTarget, policy.maxLimitIncreaseFactor());
                newCpuLimit = limit == null ? null : Math.toIntExact(limit);
                if (!java.util.Objects.equals(newCpuLimit, current.cpuLimitMillis())) {
                    reasons.add(ContainerReason.LIMIT_MOVED);
                }
            }

            Long newMemRequest = current.memRequestBytes();
            Long newMemLimit = current.memLimitBytes();
            if (memTarget != null) {
                newMemRequest = memTarget;
                newMemLimit = QosRules.limitFor(shape, current.memRequestBytes(), current.memLimitBytes(),
                        memTarget, policy.maxLimitIncreaseFactor());
                if (!java.util.Objects.equals(newMemLimit, current.memLimitBytes())) {
                    reasons.add(ContainerReason.LIMIT_MOVED);
                }
            }

            ResourceEnvelope recommended = ResourceEnvelope.recommendation(
                    current, newCpuRequest, newCpuLimit, newMemRequest, newMemLimit);
            return new ContainerDecision(container.name(), qosClass, current, recommended,
                    List.copyOf(reasons), changeKind(current));
        }

        /** Direction against the current request. An unset request counts as an increase. */
        private ChangeKind changeKind(ResourceEnvelope current) {
            Boolean cpuUp = direction(cpuTarget, toLong(current.cpuRequestMillis()));
            Boolean memUp = direction(memTarget, current.memRequestBytes());
            if (cpuUp == null && memUp == null) {
                return ChangeKind.NONE;
            }
            if (cpuUp == null) {
                return memUp ? ChangeKind.INCREASE : ChangeKind.DECREASE;
            }
            if (memUp == null) {
                return cpuUp ? ChangeKind.INCREASE : ChangeKind.DECREASE;
            }
            if (cpuUp.equals(memUp)) {
                return cpuUp ? ChangeKind.INCREASE : ChangeKind.DECREASE;
            }
            return ChangeKind.MIXED;
        }

        private static Boolean direction(Long target, Long current) {
            if (target == null) {
                return null;
            }
            if (current == null) {
                return Boolean.TRUE;
            }
            return target > current;
        }
    }
}
