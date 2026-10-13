package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.VerticalApplyService;
import com.xammer.cloud.service.automation.workload.api.VerticalAutomationReadService;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyRequestDto;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyResponseDto;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalEngineResponse;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The scheduled auto-apply loop: once per interval, for each ready+enabled cluster, apply at most one
 * eligible workload through <strong>the same path a human's Apply takes</strong>.
 *
 * <h2>It adds no second apply path and no gate</h2>
 *
 * This service reads eligibility and calls {@link VerticalApplyService#apply(ClientUserDetails,
 * CloudAccount, ClusterTarget, WorkloadRef, ApplyRequestDto, ActionTrigger)} with
 * {@link ActionTrigger#AUTO}. Every safety check — write permission, read-only connection, controller
 * readiness, the engine's per-workload holds, the per-workload cooldown — runs inside {@code apply}
 * exactly as it does for endpoint 3. The loop never patches a cluster itself and never re-decides a
 * gate; its own limits sit <em>on top of</em> that, deciding only <em>whether</em> and <em>which</em>
 * to hand to {@code apply}.
 *
 * <h2>The hard limits this loop enforces</h2>
 *
 * <ul>
 *   <li><strong>Global kill switch.</strong> The cycle returns immediately unless
 *       {@link AutoApplyProperties#isEnabled()} (the master switch, off everywhere but UAT) AND
 *       {@link WorkloadAutoscalingProperties#isAutoApplyPermitted()} (enabled + ready + an executor
 *       present). Either being false stops the whole loop.</li>
 *   <li><strong>One apply per cluster per cycle.</strong> At most one workload is handed to
 *       {@code apply} per cluster per interval.</li>
 *   <li><strong>Per-cluster cooldown.</strong> After a successful apply a cluster is left alone for
 *       {@link AutoApplyProperties#getClusterCooldownMinutes()}.</li>
 *   <li><strong>Per-workload cooldown.</strong> Enforced inside {@code apply} already; the loop does
 *       not duplicate it.</li>
 *   <li><strong>Circuit breaker.</strong> After {@link AutoApplyProperties#getCircuitBreakerThreshold()}
 *       consecutive apply failures on a cluster, the loop stops attempting that cluster and logs why.
 *       A success resets the count.</li>
 *   <li>Read-only, demo, not-ready, and unreachable clusters are skipped without calling
 *       {@code apply}.</li>
 * </ul>
 *
 * <h2>Per-instance state</h2>
 *
 * The per-cluster cooldown stamps and consecutive-failure counts live in memory
 * ({@link ConcurrentHashMap}), so they hold per backend instance — the same scope as
 * {@code VerticalApplyService.inFlight}. With more than one instance behind a balancer, each would run
 * its own loop; the per-workload cooldown stamped durably on the policy row is what still bounds the
 * total apply rate across instances.
 *
 * <h2>Hibernate session note</h2>
 *
 * The {@code @Scheduled} method has no open session, so the per-cluster work runs inside a
 * {@link Transactional} read: resolving {@code CloudAccount.client} (a LAZY association) off the
 * scheduled thread would otherwise throw {@code LazyInitializationException}.
 */
@Service
public class AutomaticApplyService {

    private static final Logger logger = LoggerFactory.getLogger(AutomaticApplyService.class);

    private static final String ACCESS_CONFIGURED = "CONFIGURED";

    private final AutoApplyProperties autoApplyProperties;
    private final WorkloadAutoscalingProperties autoscalingProperties;
    private final VerticalAutomationReadService readService;
    private final VerticalApplyService applyService;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final SystemActorResolver systemActorResolver;
    private final Clock clock;

    /** Per cluster config id: the instant the loop last applied to it, for the per-cluster cooldown. */
    private final ConcurrentMap<Long, Instant> lastAppliedByCluster = new ConcurrentHashMap<>();

    /** Per cluster config id: consecutive apply failures. Reset to zero on a success. */
    private final ConcurrentMap<Long, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    /** Per cluster config id: whether the circuit breaker has tripped and the cluster is suppressed. */
    private final java.util.Set<Long> circuitBroken = ConcurrentHashMap.newKeySet();

    @Autowired
    public AutomaticApplyService(AutoApplyProperties autoApplyProperties,
                                 WorkloadAutoscalingProperties autoscalingProperties,
                                 VerticalAutomationReadService readService,
                                 VerticalApplyService applyService,
                                 KubernetesClusterConfigRepository clusterConfigRepository,
                                 CloudAccountRepository cloudAccountRepository,
                                 SystemActorResolver systemActorResolver) {
        this(autoApplyProperties, autoscalingProperties, readService, applyService,
                clusterConfigRepository, cloudAccountRepository, systemActorResolver, Clock.systemUTC());
    }

    /** @param clock the cooldown's only ambient input; the seam the fixtures pin */
    AutomaticApplyService(AutoApplyProperties autoApplyProperties,
                          WorkloadAutoscalingProperties autoscalingProperties,
                          VerticalAutomationReadService readService,
                          VerticalApplyService applyService,
                          KubernetesClusterConfigRepository clusterConfigRepository,
                          CloudAccountRepository cloudAccountRepository,
                          SystemActorResolver systemActorResolver,
                          Clock clock) {
        this.autoApplyProperties = autoApplyProperties;
        this.autoscalingProperties = autoscalingProperties;
        this.readService = readService;
        this.applyService = applyService;
        this.clusterConfigRepository = clusterConfigRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.systemActorResolver = systemActorResolver;
        this.clock = clock;
    }

    /**
     * One cycle. Scheduled by a fixed delay expressed in milliseconds from
     * {@code auto-apply.interval-minutes} (default 15), with a one-minute initial delay so a restart
     * does not fire it immediately.
     */
    @Scheduled(
            fixedDelayString = "#{${xamops.automation.vertical.auto-apply.interval-minutes:15} * 60 * 1000}",
            initialDelay = 60_000)
    public void runCycle() {
        // The global kill switch: the master flag AND the engine readiness/executor gate. While
        // either is false the loop does not run at all, independently of any per-cluster state.
        if (!autoApplyProperties.isEnabled() || !autoscalingProperties.isAutoApplyPermitted()) {
            return;
        }
        try {
            applyOnePerReadyCluster();
        } catch (RuntimeException unexpected) {
            // A failure enumerating clusters must not kill the scheduler thread for good.
            logger.warn("Auto-apply cycle failed before reaching any cluster: {}",
                    unexpected.getMessage());
        }
    }

    /**
     * The body of one cycle, in a read-only transaction so {@code CloudAccount.client} and the read
     * service's own lazy reads resolve off the scheduled thread.
     */
    @Transactional(readOnly = true)
    public void applyOnePerReadyCluster() {
        for (KubernetesClusterConfig config : clusterConfigRepository.findAll()) {
            try {
                applyOne(config);
            } catch (RuntimeException clusterFailure) {
                // One cluster's unexpected failure must not stop the others this cycle.
                logger.warn("Auto-apply skipped cluster config {} after an unexpected error: {}",
                        config == null ? null : config.getId(), clusterFailure.getMessage());
            }
        }
    }

    private void applyOne(KubernetesClusterConfig config) {
        Long clusterConfigId = config.getId();
        if (clusterConfigId == null) {
            return;
        }
        if (circuitBroken.contains(clusterConfigId)) {
            return;
        }
        if (withinClusterCooldown(clusterConfigId)) {
            return;
        }

        CloudAccount account = resolveAccount(config).orElse(null);
        if (account == null) {
            return;
        }
        // Read-only connections are skipped here: apply would refuse one, and tripping the breaker on
        // a connection that can never write is the wrong signal.
        if (!"read-write".equalsIgnoreCase(account.getAccessType())) {
            return;
        }
        Long clientId = account.getClient() == null ? null : account.getClient().getId();
        if (clientId == null) {
            return;
        }

        // No primary automation-capable user (including a demo-only tenant, whose demo emails are
        // filtered out) means no identity that clears the write gate: skip rather than invent one.
        Optional<ClientUserDetails> actor = systemActorResolver.resolve(clientId);
        if (actor.isEmpty()) {
            return;
        }

        ClusterTarget target = new ClusterTarget(clientId, clusterConfigId, account.getAwsAccountId(),
                config.getClusterName(), config.getRegion());

        VerticalEngineResponse read = readService.read(target, config);
        if (!clusterIsApplyable(read)) {
            // Not ready, access not configured, unreachable, or a read gate is set: these would be
            // refused downstream, so the loop does not attempt an apply or trip the breaker.
            return;
        }

        Optional<VerticalWorkloadDto> selected = selectWorkload(read);
        if (selected.isEmpty()) {
            return;
        }
        VerticalWorkloadDto workload = selected.get();

        List<ApplyRequestDto.Container> body = applyBody(workload);
        if (body.isEmpty()) {
            // Eligible but nothing actually differs from current: nothing to apply this cycle.
            return;
        }
        ApplyRequestDto request = new ApplyRequestDto(body, "IN_PLACE", Boolean.FALSE);
        WorkloadRef ref = WorkloadRef.of(workload.ref().namespace(), workload.ref().kind(),
                workload.ref().name());

        try {
            ApplyResponseDto response = applyService.apply(actor.get(), account, target, ref, request,
                    ActionTrigger.AUTO);
            onApplySucceeded(clusterConfigId, ref, response);
        } catch (RuntimeException applyFailure) {
            onApplyFailed(clusterConfigId, ref, applyFailure);
        }
    }

    // ---------------------------------------------------------------- limits

    private boolean withinClusterCooldown(Long clusterConfigId) {
        Instant lastApplied = lastAppliedByCluster.get(clusterConfigId);
        if (lastApplied == null) {
            return false;
        }
        int cooldownMinutes = autoApplyProperties.getClusterCooldownMinutes();
        if (cooldownMinutes <= 0) {
            return false;
        }
        return clock.instant().isBefore(lastApplied.plus(Duration.ofMinutes(cooldownMinutes)));
    }

    private void onApplySucceeded(Long clusterConfigId, WorkloadRef ref, ApplyResponseDto response) {
        consecutiveFailures.remove(clusterConfigId);
        lastAppliedByCluster.put(clusterConfigId, clock.instant());
        logger.info("Auto-apply applied {} on cluster config {}: state {} outcome {}",
                ref.describe(), clusterConfigId, response.state(),
                response.result() == null ? "unknown" : response.result().terminal());
    }

    private void onApplyFailed(Long clusterConfigId, WorkloadRef ref, RuntimeException failure) {
        int failures = consecutiveFailures.merge(clusterConfigId, 1, Integer::sum);
        logger.warn("Auto-apply failed on {} of cluster config {} ({} consecutive): {}",
                ref.describe(), clusterConfigId, failures, failure.getMessage());
        int threshold = autoApplyProperties.getCircuitBreakerThreshold();
        if (threshold > 0 && failures >= threshold) {
            circuitBroken.add(clusterConfigId);
            logger.warn("Auto-apply circuit breaker opened for cluster config {} after {} consecutive"
                    + " failures; it will be skipped until the service restarts. Last reason: {}",
                    clusterConfigId, failures, failure.getMessage());
        }
    }

    // ---------------------------------------------------------------- selection + body

    /**
     * Whether the read reports a cluster the loop may apply to: access configured and no gate the
     * apply would refuse on ({@code CONTROLLER_NOT_READY}, the two cluster-access gates, or a caller
     * write gate the read happened to carry).
     */
    private static boolean clusterIsApplyable(VerticalEngineResponse read) {
        if (read == null || read.engine() == null) {
            return false;
        }
        if (!ACCESS_CONFIGURED.equals(read.engine().clusterAccess())) {
            return false;
        }
        List<String> gates = read.gateReasons();
        return !gates.contains("CONTROLLER_NOT_READY")
                && !gates.contains("CLUSTER_ACCESS_NOT_CONFIGURED")
                && !gates.contains("CLUSTER_UNREACHABLE")
                && !gates.contains("READ_ONLY_CONNECTION")
                && !gates.contains("NO_USER_PERMISSION")
                && !gates.contains("DEMO");
    }

    /**
     * The single workload to apply this cycle: the first, in a deterministic namespace/name order,
     * whose policy mode is {@code AUTO} and which the read reports {@code autoApplyEligible}.
     */
    private static Optional<VerticalWorkloadDto> selectWorkload(VerticalEngineResponse read) {
        return read.workloads().stream()
                .filter(workload -> workload.autoApplyEligible())
                .filter(workload -> workload.policy() != null
                        && "AUTO".equalsIgnoreCase(workload.policy().mode()))
                .min(Comparator
                        .comparing((VerticalWorkloadDto w) -> w.ref().namespace())
                        .thenComparing(w -> w.ref().name()));
    }

    /**
     * The apply body for the selected workload: for each container, each resource field is emitted
     * <strong>only when the recommended value differs from the current one</strong>, and left null
     * (leave alone) otherwise — the same per-field diff {@code ContainerApplyTarget.ofChanges} does.
     * A decision's {@code recommended} is a complete envelope, so copying it wholesale would patch a
     * resource the engine deliberately froze; only the diff is a safe apply body.
     */
    private static List<ApplyRequestDto.Container> applyBody(VerticalWorkloadDto workload) {
        List<ApplyRequestDto.Container> containers = new ArrayList<>();
        for (VerticalWorkloadDto.ContainerDto container : workload.containers()) {
            VerticalWorkloadDto.ResourcesDto recommended = container.recommended();
            if (recommended == null) {
                continue;
            }
            VerticalWorkloadDto.ResourcesDto current = container.current();
            Integer cpuRequestM = changed(recommended.cpuRequestM(), current == null ? null : current.cpuRequestM());
            Long memRequestMi = changed(recommended.memRequestMi(), current == null ? null : current.memRequestMi());
            Integer cpuLimitM = changed(recommended.cpuLimitM(), current == null ? null : current.cpuLimitM());
            Long memLimitMi = changed(recommended.memLimitMi(), current == null ? null : current.memLimitMi());
            if (cpuRequestM == null && memRequestMi == null && cpuLimitM == null && memLimitMi == null) {
                continue;
            }
            containers.add(new ApplyRequestDto.Container(container.name(), cpuRequestM, memRequestMi,
                    cpuLimitM, memLimitMi));
        }
        return containers;
    }

    /** @return {@code recommended} when it differs from {@code current}, else null — "leave it alone" */
    private static <T> T changed(T recommended, T current) {
        if (recommended == null) {
            return null;
        }
        return Objects.equals(recommended, current) ? null : recommended;
    }

    // ---------------------------------------------------------------- account resolution

    /**
     * The {@code CloudAccount} owning a registered cluster.
     *
     * <p>{@code kubernetes_cluster_config.cloud_account_id} holds the {@code cloud_account} primary key
     * on modern rows and the AWS account number on legacy ones (audit B4), so this tries the primary
     * key first and the AWS account number second — the same ambiguity the controller resolves, in the
     * opposite direction (config → account).
     */
    private Optional<CloudAccount> resolveAccount(KubernetesClusterConfig config) {
        Long cloudAccountId = config.getCloudAccountId();
        if (cloudAccountId == null) {
            return Optional.empty();
        }
        Optional<CloudAccount> byPrimaryKey = cloudAccountRepository.findById(cloudAccountId);
        if (byPrimaryKey.isPresent()) {
            return byPrimaryKey;
        }
        return cloudAccountRepository.findByAwsAccountId(String.valueOf(cloudAccountId)).stream()
                .findFirst();
    }
}
