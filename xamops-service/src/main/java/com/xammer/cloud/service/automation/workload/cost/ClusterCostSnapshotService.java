package com.xammer.cloud.service.automation.workload.cost;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.ClusterCostSnapshot;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.ClusterCostSnapshotRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.VerticalAutomationReadService;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalEngineResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * The scheduled daily cluster-cost-snapshot loop: once per capture period, for each
 * enabled+computable cluster, compute the <strong>real</strong> current {@code clusterMonthlyCost}
 * through the same authoritative automation engine the Overview hero reads
 * ({@link VerticalAutomationReadService#read} &rarr; {@code VerticalEngineResponse.cost()
 * .clusterMonthlyCost()}) and persist exactly one {@link ClusterCostSnapshot} row, so a later
 * request has a durable prior-period baseline to compare against.
 *
 * <h2>It recomputes nothing and fabricates nothing</h2>
 *
 * The job never prices anything itself and never touches the agent-dashboard path: it reuses the one
 * authoritative read path. When that read reports no cost ({@code cost()} null) or an unavailable
 * cost ({@code clusterMonthlyCost()} null) — the feature off, nothing priceable, a transient blip —
 * the loop <strong>skips the cluster and writes no row</strong>, never a fabricated 0.
 *
 * <h2>The two gates</h2>
 *
 * <ul>
 *   <li><strong>Master switch.</strong> {@link ClusterCostSnapshotProperties#enabled()} — off
 *       everywhere but UAT.</li>
 *   <li><strong>Cost feature.</strong> {@link K8sCostProperties#enabled()} — the read path produces
 *       no cost at all while it is off.</li>
 * </ul>
 *
 * Both must be on or {@link #runCycle()} returns immediately, exactly like
 * {@code AutomaticApplyService.runCycle}.
 *
 * <h2>Enumeration + account resolution</h2>
 *
 * Copied from {@code AutomaticApplyService}: enumerate {@code clusterConfigRepository.findAll()} and
 * resolve the owning {@code CloudAccount} by primary key first and the AWS account number second
 * (the legacy {@code cloud_account_id} ambiguity, audit B4). Every cluster is scoped by
 * {@code kubernetes_cluster_config.id}, never the ambiguous account column.
 *
 * <h2>Hibernate session note</h2>
 *
 * The {@code @Scheduled} method has no open session, so the per-cluster work runs inside a
 * {@link Transactional} read: resolving {@code CloudAccount.client} (a LAZY association) and the
 * read service's own lazy reads off the scheduled thread would otherwise throw
 * {@code LazyInitializationException}.
 *
 * <h2>No account number ever logged</h2>
 *
 * Every log line names a cluster through {@link ClusterTarget#describe()} or its config id, matching
 * {@code AutomaticApplyService}'s logging discipline; the 12-digit AWS account number is never
 * logged.
 */
@Service
public class ClusterCostSnapshotService {

    private static final Logger logger = LoggerFactory.getLogger(ClusterCostSnapshotService.class);

    private final ClusterCostSnapshotProperties snapshotProperties;
    private final K8sCostProperties costProperties;
    private final VerticalAutomationReadService readService;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final ClusterCostSnapshotRepository snapshotRepository;
    private final Clock clock;

    @Autowired
    public ClusterCostSnapshotService(ClusterCostSnapshotProperties snapshotProperties,
                                      K8sCostProperties costProperties,
                                      VerticalAutomationReadService readService,
                                      KubernetesClusterConfigRepository clusterConfigRepository,
                                      CloudAccountRepository cloudAccountRepository,
                                      ClusterCostSnapshotRepository snapshotRepository) {
        this(snapshotProperties, costProperties, readService, clusterConfigRepository,
                cloudAccountRepository, snapshotRepository, Clock.systemUTC());
    }

    /** @param clock the captured instant's only ambient input; the seam the fixtures pin */
    ClusterCostSnapshotService(ClusterCostSnapshotProperties snapshotProperties,
                               K8sCostProperties costProperties,
                               VerticalAutomationReadService readService,
                               KubernetesClusterConfigRepository clusterConfigRepository,
                               CloudAccountRepository cloudAccountRepository,
                               ClusterCostSnapshotRepository snapshotRepository,
                               Clock clock) {
        this.snapshotProperties = snapshotProperties;
        this.costProperties = costProperties;
        this.readService = readService;
        this.clusterConfigRepository = clusterConfigRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.snapshotRepository = snapshotRepository;
        this.clock = clock;
    }

    /**
     * One snapshot cycle. Scheduled by a fixed delay expressed in milliseconds from
     * {@code xamops.k8s.cost.snapshot.interval-minutes} (default 1440 = daily), with a one-minute
     * initial delay so a restart does not fire it immediately.
     */
    @Scheduled(
            fixedDelayString = "#{${xamops.k8s.cost.snapshot.interval-minutes:1440} * 60 * 1000}",
            initialDelay = 60_000)
    public void runCycle() {
        // Both gates: the snapshot master switch AND the cost feature. While either is false the loop
        // does not run at all, independently of any per-cluster state.
        if (!snapshotProperties.enabled() || !costProperties.enabled()) {
            return;
        }
        try {
            snapshotAllEnabledClusters();
        } catch (RuntimeException unexpected) {
            // A failure enumerating clusters must not kill the scheduler thread for good.
            logger.warn("Cluster-cost-snapshot cycle failed before reaching any cluster: {}",
                    unexpected.getMessage());
        }
    }

    /**
     * The body of one cycle, in a read-only transaction so {@code CloudAccount.client} and the read
     * service's own lazy reads resolve off the scheduled thread. Each cluster runs in its own
     * try/catch so one cluster's failure does not stop the others, and the cycle ends with one
     * structured summary line.
     */
    @Transactional(readOnly = true)
    public void snapshotAllEnabledClusters() {
        int scanned = 0;
        int snapshotted = 0;
        int skipped = 0;
        for (KubernetesClusterConfig config : clusterConfigRepository.findAll()) {
            scanned++;
            try {
                if (snapshotOne(config)) {
                    snapshotted++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException clusterFailure) {
                // One cluster's unexpected failure must not stop the others this cycle.
                skipped++;
                logger.warn("Cluster-cost-snapshot skipped cluster config {} after an unexpected"
                                + " error: {}",
                        config == null ? null : config.getId(), clusterFailure.getMessage());
            }
        }
        logger.info("Cluster-cost-snapshot cycle summary: {} scanned, {} snapshotted, {} skipped",
                scanned, snapshotted, skipped);
    }

    /**
     * Handle one cluster. Returns {@code true} when a row was written, {@code false} when the cluster
     * was skipped (no account/client, or no computable cost). Each skip logs its concrete reason,
     * naming the cluster through its config id or {@link ClusterTarget#describe()} — never the AWS
     * account number.
     */
    private boolean snapshotOne(KubernetesClusterConfig config) {
        Long clusterConfigId = config.getId();
        if (clusterConfigId == null) {
            logger.info("Cluster-cost-snapshot skipped a cluster config with no id");
            return false;
        }

        CloudAccount account = resolveAccount(config).orElse(null);
        if (account == null) {
            logger.info("Cluster-cost-snapshot skipped cluster config {}: reason no-cloud-account",
                    clusterConfigId);
            return false;
        }
        Long clientId = account.getClient() == null ? null : account.getClient().getId();
        if (clientId == null) {
            logger.info("Cluster-cost-snapshot skipped cluster config {}: reason no-client",
                    clusterConfigId);
            return false;
        }

        ClusterTarget target = new ClusterTarget(clientId, clusterConfigId, account.getAwsAccountId(),
                config.getClusterName(), config.getRegion());

        VerticalEngineResponse read = readService.read(account, target, config);
        if (read.cost() == null || read.cost().clusterMonthlyCost() == null) {
            // No computable cost: the feature off for this cluster, nothing priceable, or a transient
            // blip. Write NO row rather than a fabricated 0.
            logger.info("Cluster-cost-snapshot skipped {}: reason no-computable-cost",
                    target.describe());
            return false;
        }

        ClusterCostSnapshot snapshot = new ClusterCostSnapshot(clusterConfigId,
                read.cost().clusterMonthlyCost(), clock.instant());
        snapshotRepository.save(snapshot);
        logger.info("Cluster-cost-snapshot captured {}: clusterMonthlyCost {}",
                target.describe(), read.cost().clusterMonthlyCost());
        return true;
    }

    /**
     * The {@code CloudAccount} owning a registered cluster.
     *
     * <p>{@code kubernetes_cluster_config.cloud_account_id} holds the {@code cloud_account} primary
     * key on modern rows and the AWS account number on legacy ones (audit B4), so this tries the
     * primary key first and the AWS account number second — copied from
     * {@code AutomaticApplyService.resolveAccount}.
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
