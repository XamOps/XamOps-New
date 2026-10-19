package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.domain.WorkloadVerticalAction;
import com.xammer.cloud.repository.WorkloadVerticalActionRepository;
import com.xammer.cloud.service.automation.workload.ConfidenceProperties;
import com.xammer.cloud.service.automation.workload.ContainerSnapshot;
import com.xammer.cloud.service.automation.workload.HpaView;
import com.xammer.cloud.service.automation.workload.NodeCapacity;
import com.xammer.cloud.service.automation.workload.RpsReplicaRecommendation;
import com.xammer.cloud.service.automation.workload.RpsReplicaSizing;
import com.xammer.cloud.service.automation.workload.RpsSizingProperties;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingEngine;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.WorkloadDecision;
import com.xammer.cloud.service.automation.workload.WorkloadPolicy;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.WorkloadSnapshot;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalEngineResponse;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.UpstreamVpaReader;
import com.xammer.cloud.service.automation.workload.policy.WorkloadPolicyService;
import com.xammer.cloud.service.automation.workload.usage.ContainerUsageAggregate;
import com.xammer.cloud.service.automation.workload.usage.RpsQuery;
import com.xammer.cloud.service.automation.workload.usage.UsageHistoryProvider;
import com.xammer.cloud.service.automation.workload.usage.UsageQuery;
import com.xammer.cloud.service.automation.workload.usage.UsageUnavailableException;
import com.xammer.cloud.service.automation.workload.usage.VerticalMetricsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Assembles the read endpoint's payload.
 *
 * <h2>It never fabricates</h2>
 *
 * With the master switch off — the default in every profile — this makes <strong>no</strong> cluster
 * or AWS call: it returns engine status plus gate reasons and an <strong>empty</strong>
 * {@code workloads} array, and it makes no claim about cluster access it has not verified
 * ({@code clusterAccess: null}). With the switch on, everything in the payload is measured: the
 * workloads come from the cluster, the numbers come from the cluster's Prometheus, and a workload
 * that cannot be measured is published with a skip reason and <strong>no numbers</strong> rather than
 * with zeros.
 *
 * <h2>What one request does</h2>
 *
 * <ol>
 *   <li>Open one {@link ClusterAccess} — one connection serves the whole request.</li>
 *   <li>Probe the usage source through it (at most once per {@link #PROBE_TTL}).</li>
 *   <li>Read the cluster inventory: workloads, pods by owner reference, HPAs, PDBs, node capacity.</li>
 *   <li>Read upstream VPA's recommendations for the comparison column.</li>
 *   <li>Per workload: resolve the policy, measure usage, {@link WorkloadAutoscalingEngine#decide},
 *       map to the wire shape.</li>
 * </ol>
 *
 * <h2>What the probe cache is for now</h2>
 *
 * Opening a provider is not free: the EKS path calls {@code describeCluster}, mints a token, completes
 * a TLS handshake and issues a {@code namespaces/kube-system} GET. A cluster whose access is
 * <strong>broken</strong> is therefore not re-dialled on every poll — the failure is cached on the
 * same terms as a success, which is what stops an unroutable cluster sitting on the latency path of a
 * UI refresh. A cluster whose access <em>works</em> is opened once per request, because the request's
 * data comes through it; what the TTL still saves there is the Prometheus reachability probe.
 */
@Service
public class VerticalAutomationReadService {

    private static final Logger logger = LoggerFactory.getLogger(VerticalAutomationReadService.class);

    private static final String ACCESS_CONFIGURED = "CONFIGURED";
    private static final String ACCESS_NOT_CONFIGURED = "NOT_CONFIGURED";
    private static final String ACCESS_UNREACHABLE = "UNREACHABLE";
    private static final String PATH_DIRECT = "DIRECT";
    private static final String PATH_AGENT = "AGENT";
    private static final String PATH_NONE = "NONE";

    /**
     * How long a direct-path probe result and a metrics-source probe result are reused.
     *
     * <p>60s is short enough that a customer fixing an access entry — or installing Prometheus —
     * sees the result on their next refresh rather than on a restart.
     */
    static final Duration PROBE_TTL = Duration.ofSeconds(60);

    private final WorkloadAutoscalingProperties properties;
    private final VerticalMetricsProperties metricsProperties;
    private final ConfidenceProperties confidenceProperties;
    private final RpsSizingProperties rpsProperties;
    private final UsageHistoryProvider usageProvider;
    private final List<ClusterAccessProvider> accessProviders;
    private final ClusterWorkloadInventory inventory;
    private final UpstreamVpaReader vpaReader;
    private final WorkloadPolicyService policyService;
    private final WorkloadVerticalActionRepository actionRepository;
    private final WorkloadAutoscalingEngine engine;
    private final Clock clock;
    private final LongSupplier nanoClock;

    /**
     * Keyed by {@code client.id} and {@code kubernetes_cluster_config.id} — the canonical pair — so
     * the key space is bounded by the registered-cluster table and one tenant's clusters can never
     * be served another tenant's probe result.
     */
    private final Map<String, ProbeEntry> probeCache = new ConcurrentHashMap<>();

    @Autowired
    public VerticalAutomationReadService(WorkloadAutoscalingProperties properties,
                                         VerticalMetricsProperties metricsProperties,
                                         ConfidenceProperties confidenceProperties,
                                         RpsSizingProperties rpsProperties,
                                         UsageHistoryProvider usageProvider,
                                         List<ClusterAccessProvider> accessProviders,
                                         ClusterWorkloadInventory inventory,
                                         UpstreamVpaReader vpaReader,
                                         WorkloadPolicyService policyService,
                                         WorkloadVerticalActionRepository actionRepository) {
        this(properties, metricsProperties, confidenceProperties, rpsProperties, usageProvider,
                accessProviders, inventory, vpaReader, policyService, actionRepository,
                Clock.systemUTC(), System::nanoTime);
    }

    /**
     * @param clock     the engine's and the cycle's only ambient input; the seam the fixtures pin
     * @param nanoClock a monotonic nanosecond source; the seam the TTL is tested through
     */
    VerticalAutomationReadService(WorkloadAutoscalingProperties properties,
                                  VerticalMetricsProperties metricsProperties,
                                  ConfidenceProperties confidenceProperties,
                                  RpsSizingProperties rpsProperties,
                                  UsageHistoryProvider usageProvider,
                                  List<ClusterAccessProvider> accessProviders,
                                  ClusterWorkloadInventory inventory,
                                  UpstreamVpaReader vpaReader,
                                  WorkloadPolicyService policyService,
                                  WorkloadVerticalActionRepository actionRepository,
                                  Clock clock,
                                  LongSupplier nanoClock) {
        this.properties = properties;
        this.metricsProperties = metricsProperties;
        this.confidenceProperties = confidenceProperties;
        this.rpsProperties = rpsProperties;
        this.usageProvider = usageProvider;
        this.inventory = inventory;
        this.vpaReader = vpaReader;
        this.policyService = policyService;
        this.actionRepository = actionRepository;
        this.clock = clock;
        this.nanoClock = nanoClock;
        this.engine = new WorkloadAutoscalingEngine(clock);
        // Sorted here rather than relying on the injected order. Spring does order a List<T>
        // injection point by @Order, but the two providers' supports() predicates overlap for
        // essentially every registered cluster, so which one serves a cluster is a decision and
        // must be visible as one in the code that makes it -- not an artefact of component scanning.
        List<ClusterAccessProvider> ordered = new ArrayList<>(accessProviders);
        AnnotationAwareOrderComparator.sort(ordered);
        this.accessProviders = List.copyOf(ordered);
    }

    public VerticalEngineResponse read(ClusterTarget target, KubernetesClusterConfig config) {
        List<String> gateReasons = new ArrayList<>();

        if (!properties.isEnabled() || !properties.isReady()) {
            gateReasons.add(ReasonCode.CONTROLLER_NOT_READY.name());
        }

        Attempt attempt = attempt(target);

        if (attempt.direct().reason() != null && !gateReasons.contains(attempt.direct().reason())) {
            gateReasons.add(attempt.direct().reason());
        }
        if (!attempt.metricsReachable()) {
            gateReasons.add(ReasonCode.NO_METRICS_SOURCE.name());
        }
        for (String reason : attempt.evaluation().gateReasons()) {
            if (!gateReasons.contains(reason)) {
                gateReasons.add(reason);
            }
        }

        VerticalEngineResponse.AgentAvailability agent = agentPath(config);

        String executionPath;
        if (attempt.direct().available()) {
            executionPath = PATH_DIRECT;
        } else if (agent.available()) {
            executionPath = PATH_AGENT;
        } else {
            executionPath = PATH_NONE;
        }

        VerticalEngineResponse.EngineStatus engineStatus = new VerticalEngineResponse.EngineStatus(
                properties.isEnabled(),
                properties.isReady(),
                attempt.direct().clusterAccess(),
                executionPath,
                new VerticalEngineResponse.ExecutionPathDetail(
                        new VerticalEngineResponse.PathAvailability(attempt.direct().available(),
                                attempt.direct().reason()),
                        agent),
                new VerticalEngineResponse.MetricsSource(attempt.metricsReachable(),
                        attempt.metricsReachable() ? usageProvider.sourceName() : null),
                attempt.evaluation().lastCycle());

        return new VerticalEngineResponse(engineStatus, gateReasons, attempt.evaluation().workloads());
    }

    // ------------------------------------------------------------------ access + evaluation

    /**
     * One request's worth of cluster contact: the access probe, the metrics probe and the evaluation.
     *
     * <p><strong>While the master switch is off, nothing is opened and no claim is made</strong> —
     * {@code available: false} with a {@code null} reason and {@code clusterAccess: null}. Guessing
     * {@code CLUSTER_ACCESS_NOT_CONFIGURED} would send a customer to fix an access entry that may
     * already be correct, which is worse than saying nothing. The metrics source reads as
     * unreachable, which is a statement about the engine and not about the customer's cluster: while
     * the switch is off the engine may not query any source, so none can supply it percentiles.
     */
    private Attempt attempt(ClusterTarget target) {
        if (!properties.isEnabled()) {
            return new Attempt(new DirectPath(false, null, null), false, Evaluation.notRun());
        }

        String key = target.clientId() + ":" + target.clusterConfigId();
        long now = nanoClock.getAsLong();
        ProbeEntry cached = probeCache.get(key);
        boolean fresh = cached != null && !cached.isExpiredAt(now);

        if (fresh && !cached.direct().available()) {
            // A known-broken cluster is not re-dialled on every poll, and the cached answer is the
            // same answer rather than a weaker one.
            return new Attempt(cached.direct(), cached.metricsReachable(), Evaluation.notRun());
        }

        // accessProviders is sorted by declared precedence in the constructor, so "first that
        // supports the target" is the EKS direct path whenever it applies, and the kubeconfig
        // fallback otherwise.
        Optional<ClusterAccessProvider> provider = accessProviders.stream()
                .filter(candidate -> candidate.supports(target))
                .findFirst();
        if (provider.isEmpty()) {
            DirectPath notConfigured = new DirectPath(false,
                    ReasonCode.CLUSTER_ACCESS_NOT_CONFIGURED.name(), ACCESS_NOT_CONFIGURED);
            probeCache.put(key, new ProbeEntry(notConfigured, false, now + PROBE_TTL.toNanos()));
            return new Attempt(notConfigured, false, Evaluation.notRun());
        }

        try (ClusterAccess access = provider.get().open(target)) {
            logger.debug("Direct cluster access verified for {} via {}", target.describe(),
                    access.providerName());
            DirectPath direct = new DirectPath(true, null, ACCESS_CONFIGURED);
            boolean metricsReachable = fresh ? cached.metricsReachable() : usageProvider.reachable(access);
            Evaluation evaluation = metricsReachable
                    ? evaluate(access, target)
                    : Evaluation.gated(clock.instant(), ReasonCode.NO_METRICS_SOURCE.name());
            probeCache.put(key, new ProbeEntry(direct, metricsReachable,
                    fresh ? cached.expiresAtNanos() : now + PROBE_TTL.toNanos()));
            return new Attempt(direct, metricsReachable, evaluation);
        } catch (ClusterAccessException e) {
            // The two typed kinds are the only outcomes, which is what makes this mapping total.
            String clusterAccess = e.kind() == ClusterAccessException.Kind.CLUSTER_UNREACHABLE
                    ? ACCESS_UNREACHABLE : ACCESS_NOT_CONFIGURED;
            DirectPath failed = new DirectPath(false, e.reasonCode(), clusterAccess);
            probeCache.put(key, new ProbeEntry(failed, false, now + PROBE_TTL.toNanos()));
            return new Attempt(failed, false, Evaluation.notRun());
        }
    }

    /**
     * One real evaluation cycle over one cluster.
     *
     * <p>A cluster-wide failure — the inventory read, the node capacity, or a usage failure that is
     * not about one workload's history — publishes <strong>no</strong> workloads plus the gate code,
     * rather than a partial list the reader cannot tell is partial.
     */
    private Evaluation evaluate(ClusterAccess access, ClusterTarget target) {
        Instant at = clock.instant();
        ClusterWorkloadInventory.Inventory cluster;
        try {
            cluster = inventory.read(access.client());
        } catch (RuntimeException e) {
            logger.warn("Vertical evaluation could not read cluster {}: {}", target.describe(),
                    e.getMessage());
            return Evaluation.failed(at, "cluster inventory could not be read: " + e.getMessage());
        }
        NodeCapacity capacity = cluster.capacity();
        if (capacity == null) {
            // An unknown capacity is not a capacity of zero, and every recommendation is capped
            // against it -- so without it there is nothing honest to publish.
            return Evaluation.failed(at, "no schedulable node reported allocatable CPU and memory");
        }

        UpstreamVpaReader.View upstreamVpa = vpaReader.read(access.client());
        Map<String, WorkloadPolicy> storedPolicies = policyService.resolveAll(target.clusterConfigId());
        Map<String, VerticalWorkloadDto.LastActionDto> lastActions =
                lastActions(target.clusterConfigId());

        List<VerticalWorkloadDto> workloads = new ArrayList<>();
        for (ClusterWorkloadInventory.Workload workload : cluster.workloads()) {
            WorkloadPolicy policy = policyFor(storedPolicies, workload.ref());
            Map<String, ContainerUsageAggregate> usage;
            try {
                usage = measure(access, target, workload, policy);
            } catch (UsageUnavailableException e) {
                if (e.kind() == UsageUnavailableException.Kind.NO_METRICS_SOURCE) {
                    logger.info("Vertical evaluation stopped for {}: {}", target.describe(),
                            e.getMessage());
                    return Evaluation.failedGate(at, ReasonCode.NO_METRICS_SOURCE.name(), e.getMessage());
                }
                workloads.add(VerticalDecisionMapper.notEvaluated(workload, policy,
                        ReasonCode.INSUFFICIENT_HISTORY.name(), usageProvider.sourceName(),
                        findingsFor(upstreamVpa)));
                continue;
            }

            WorkloadSnapshot snapshot = snapshot(workload, usage, upstreamVpa);
            if (snapshot == null) {
                workloads.add(VerticalDecisionMapper.notEvaluated(workload, policy,
                        ReasonCode.INSUFFICIENT_HISTORY.name(), usageProvider.sourceName(),
                        findingsFor(upstreamVpa)));
                continue;
            }
            WorkloadDecision decision = engine.decide(snapshot, policy, capacity);
            // Always carry an honest RPS outcome: feature off => NO_RPS_SOURCE (no source is read),
            // otherwise the measured outcome. The advisory is recommend-only and never auto-applies.
            decision = decision.withRps(rpsProperties.enabled()
                    ? rpsAdvisory(access, workload, policy)
                    : RpsReplicaRecommendation.noRpsSource());
            workloads.add(VerticalDecisionMapper.toDto(snapshot, policy, decision, properties,
                    upstreamVpa, lastActions.get(WorkloadPolicyService.key(workload.ref())),
                    confidenceProperties));
        }
        return Evaluation.completed(at, workloads);
    }

    /**
     * The newest recorded action per workload for one cluster, keyed the same way
     * {@link #policyFor} keys policies, in a single pass. The repository returns rows newest-first,
     * so the first row seen for a key is the one kept and later (older) rows are ignored.
     *
     * <p>{@code notEvaluated} workloads keep {@code lastAction} null regardless, because that path
     * does not consult this map.
     */
    private Map<String, VerticalWorkloadDto.LastActionDto> lastActions(Long clusterConfigId) {
        Map<String, VerticalWorkloadDto.LastActionDto> byKey = new java.util.HashMap<>();
        for (WorkloadVerticalAction action
                : actionRepository.findByClusterConfigIdOrderByAppliedAtDesc(clusterConfigId)) {
            String key = WorkloadPolicyService.key(action.getNamespace(), action.getWorkloadKind(),
                    action.getWorkloadName());
            byKey.computeIfAbsent(key, ignored -> new VerticalWorkloadDto.LastActionDto(
                    action.getAppliedAt(), action.getDryRun(), action.getOutcome()));
        }
        return byKey;
    }

    /**
     * Measures one workload, namespace-scoped and selected by the pods the inventory resolved through
     * owner references.
     *
     * <p>A workload with no pods right now cannot be measured at all, which is
     * {@code INSUFFICIENT_HISTORY} by definition — a source exists, it has observed nothing of this
     * workload.
     */
    private Map<String, ContainerUsageAggregate> measure(ClusterAccess access, ClusterTarget target,
                                                         ClusterWorkloadInventory.Workload workload,
                                                         WorkloadPolicy policy)
            throws UsageUnavailableException {
        if (workload.pods().isEmpty()) {
            throw new UsageUnavailableException(UsageUnavailableException.Kind.INSUFFICIENT_HISTORY,
                    workload.ref().describe() + " currently has no pods, so nothing has been measured"
                            + " for it.");
        }
        UsageQuery query = new UsageQuery(target, workload.ref(), workload.containerNames(),
                workload.pods().names(), workload.pods().nameRules(), policy.percentile(),
                policy.windowHours());
        return usageProvider.aggregates(access, query);
    }

    /**
     * The RPS-based replica-sizing advisory for one workload (F3), computed only when the feature is
     * enabled. One of three honest outcomes:
     *
     * <ol>
     *   <li>{@code NO_RPS_SOURCE} — the configured metric returned no throughput (unknown / absent /
     *       NaN), or no metric is configured;</li>
     *   <li>{@code NO_CAPACITY_BASIS} — throughput is known but no real per-replica capacity is
     *       configured (capacity is never self-derived from the observed RPS — the H1 fix);</li>
     *   <li>{@code RPS_SIZED} — both present, so a count is computed, bounded by the HPA range when
     *       both bounds exist and emitted unbounded with a {@code (no HPA range)} note otherwise.</li>
     * </ol>
     *
     * <p>Recommend-only: nothing is applied and no min/max/target is patched. The RPS read is
     * best-effort and the HPA bounds pass straight through as nullable {@code Integer}.
     */
    private RpsReplicaRecommendation rpsAdvisory(ClusterAccess access,
                                                 ClusterWorkloadInventory.Workload workload,
                                                 WorkloadPolicy policy) {
        RpsQuery query = new RpsQuery(workload.ref(), workload.containerNames(),
                workload.pods().names(), workload.pods().nameRules(), policy.windowHours(),
                metricsProperties.ignoreContainerStartSeconds(), rpsProperties.metric());
        OptionalDouble observedRps = usageProvider.observedRps(access, query);
        if (observedRps.isEmpty()) {
            return RpsReplicaRecommendation.noRpsSource();
        }
        Double capacity = rpsProperties.capacityPerReplicaRps();
        if (capacity == null) {
            return RpsReplicaRecommendation.noCapacityBasis();
        }

        HpaView hpa = workload.hpa();
        Integer minReplicas = hpa == null ? null : hpa.minReplicas();
        Integer maxReplicas = hpa == null ? null : hpa.maxReplicas();
        double rps = observedRps.getAsDouble();
        double targetUtil = rpsProperties.targetUtil();

        OptionalInt sized = RpsReplicaSizing.size(rps, capacity, targetUtil, minReplicas, maxReplicas);
        if (sized.isEmpty()) {
            // A non-positive capacity/target would have been filtered by RpsSizingProperties, so this
            // is the defensive total-function fallback rather than an expected path.
            return RpsReplicaRecommendation.noCapacityBasis();
        }
        int replicas = sized.getAsInt();
        String formula = rpsFormula(rps, capacity, targetUtil, replicas, minReplicas, maxReplicas);
        return RpsReplicaRecommendation.sized(replicas, formula);
    }

    /** The human-readable derivation, noting the HPA clamp when present and {@code (no HPA range)} otherwise. */
    private static String rpsFormula(double observedRps, double capacity, double targetUtil,
                                     int replicas, Integer minReplicas, Integer maxReplicas) {
        long raw = (long) Math.ceil((observedRps / capacity) / targetUtil);
        String base = String.format(java.util.Locale.ROOT,
                "ceil(%.1f rps / %.1f per replica / %.2f util) = %d", observedRps, capacity,
                targetUtil, raw);
        if (RpsReplicaSizing.bounded(minReplicas, maxReplicas)) {
            return base + ", clamped to HPA [" + minReplicas + "," + maxReplicas + "] \u2192 " + replicas;
        }
        return base + " (no HPA range)";
    }

    /**
     * @return the snapshot, or null when the provider did not return an aggregate for every container
     *         — a contract violation that is reported as a waiting state rather than as a 500,
     *         because a container measured by nobody must not receive a recommendation
     */
    private WorkloadSnapshot snapshot(ClusterWorkloadInventory.Workload workload,
                                      Map<String, ContainerUsageAggregate> usage,
                                      UpstreamVpaReader.View upstreamVpa) {
        List<ContainerSnapshot> containers = new ArrayList<>();
        for (ClusterWorkloadInventory.ContainerSpec container : workload.containers()) {
            ContainerUsageAggregate measured = usage.get(container.name());
            if (measured == null) {
                logger.info("No usage aggregate for container {} of {}", container.name(),
                        workload.ref().describe());
                return null;
            }
            containers.add(new ContainerSnapshot(container.name(), container.current(), measured,
                    container.smallestLive()));
        }
        return new WorkloadSnapshot(workload.ref(), workload.replicas(), workload.rolloutInProgress(),
                workload.lockAnnotationActive(), workload.hpa(), workload.pdbMinAvailable(),
                null,       // GitOps detection is deferred, so it stays unknown rather than false
                upstreamVpa.modeFor(workload.ref()),
                containers);
    }

    /**
     * The policy for one workload, with the two configured defaults applied <strong>only</strong>
     * when no row exists.
     *
     * <p>A stored {@code vertical_workload_policy} row is never overridden, so
     * {@code policy.mode} and {@code provenance.windowHours} always report what was actually used and
     * {@code provenance.policySource} always says whether a human chose it.
     */
    private WorkloadPolicy policyFor(Map<String, WorkloadPolicy> storedPolicies, WorkloadRef ref) {
        WorkloadPolicy stored = storedPolicies.get(WorkloadPolicyService.key(ref));
        if (stored != null) {
            return stored;
        }
        return metricsProperties.defaultsPolicy();
    }

    private static List<String> findingsFor(UpstreamVpaReader.View upstreamVpa) {
        return upstreamVpa.readable() ? List.of() : List.of(UpstreamVpaReader.FINDING_NOT_READABLE);
    }

    /**
     * Whether a live in-cluster agent could carry commands.
     *
     * <p>Read entirely from the cluster-config row, with no I/O: the agent polls outbound-only, so
     * its last check-in is the only evidence available and the only evidence needed. Reuses the UI's
     * existing {@code AGENT_NOT_INSTALLED} rather than introducing a new shared code.
     */
    private VerticalEngineResponse.AgentAvailability agentPath(KubernetesClusterConfig config) {
        if (config == null) {
            return new VerticalEngineResponse.AgentAvailability(false, ReasonCode.AGENT_NOT_INSTALLED, null);
        }
        boolean active = config.isAgentActive();
        return new VerticalEngineResponse.AgentAvailability(active,
                active ? null : ReasonCode.AGENT_NOT_INSTALLED,
                config.getLastAgentCheckin() == null
                        ? null
                        : config.getLastAgentCheckin().toInstant(java.time.ZoneOffset.UTC));
    }

    /** @param clusterAccess null when the engine has not checked */
    private record DirectPath(boolean available, String reason, String clusterAccess) {
    }

    /** What one request's cluster contact produced. */
    private record Attempt(DirectPath direct, boolean metricsReachable, Evaluation evaluation) {
    }

    /**
     * One evaluation cycle's outcome.
     *
     * <p>{@code lastCycle} is built from this and nothing else, so it reports a real cycle or none at
     * all — never a fabricated timestamp.
     */
    private record Evaluation(List<VerticalWorkloadDto> workloads, List<String> gateReasons,
                              VerticalEngineResponse.LastCycle lastCycle) {

        /** No cycle ran: the engine is gated, or the cluster was never reached. */
        private static Evaluation notRun() {
            return new Evaluation(List.of(), List.of(), VerticalEngineResponse.LastCycle.never());
        }

        private static Evaluation completed(Instant at, List<VerticalWorkloadDto> workloads) {
            return new Evaluation(List.copyOf(workloads), List.of(),
                    new VerticalEngineResponse.LastCycle(at, workloads.size(), null));
        }

        /** The cycle ran and failed cluster-wide: no workloads, and the error is named. */
        private static Evaluation failed(Instant at, String error) {
            return new Evaluation(List.of(), List.of(),
                    new VerticalEngineResponse.LastCycle(at, 0, error));
        }

        private static Evaluation failedGate(Instant at, String gateReason, String error) {
            return new Evaluation(List.of(), List.of(gateReason),
                    new VerticalEngineResponse.LastCycle(at, 0, error));
        }

        /** The cycle was not attempted because a gate stopped it; the gate is reported, not an error. */
        private static Evaluation gated(Instant at, String gateReason) {
            return new Evaluation(List.of(), List.of(gateReason),
                    new VerticalEngineResponse.LastCycle(at, 0, null));
        }
    }

    /**
     * One cached probe result and the monotonic deadline it stops being usable at.
     *
     * <p>Deadline rather than "recorded at + TTL at read time" so a clock source cannot make an
     * entry live longer than it was granted.
     */
    private record ProbeEntry(DirectPath direct, boolean metricsReachable, long expiresAtNanos) {

        private boolean isExpiredAt(long nowNanos) {
            // Subtraction, not comparison: nanoTime wraps, and a difference stays correct across it.
            return nowNanos - expiresAtNanos >= 0;
        }
    }
}
