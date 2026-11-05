package com.xammer.cloud.repository;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface KubernetesClusterConfigRepository extends JpaRepository<KubernetesClusterConfig, Long> {

    // Find by cloud account ID and cluster name
    Optional<KubernetesClusterConfig> findByCloudAccountIdAndClusterName(Long cloudAccountId, String clusterName);

    // Find all clusters by cloud account ID
    List<KubernetesClusterConfig> findByCloudAccountId(Long cloudAccountId);

    // Find clusters with Kubescape enabled
    List<KubernetesClusterConfig> findByKubescapeEnabledTrue();

    // Find clusters with Karpenter enabled
    List<KubernetesClusterConfig> findByKarpenterEnabledTrue();

    // Find clusters with XamOps Autoscaling Agent enabled
    List<KubernetesClusterConfig> findByAutoscalingEnabledTrue();

    // ===== Agent Support Methods =====

    /**
     * Find cluster config by agent API key (for agent authentication)
     */
    Optional<KubernetesClusterConfig> findByAgentApiKey(String agentApiKey);

    /**
     * Check if agent API key exists
     */
    boolean existsByAgentApiKey(String agentApiKey);

    /**
     * Find all clusters using agent data source
     */
    List<KubernetesClusterConfig> findByDataSourceMode(String dataSourceMode);

    /**
     * Find all clusters with active agents
     */
    @Query("SELECT c FROM KubernetesClusterConfig c WHERE c.agentStatus = 'active' " +
           "AND c.lastAgentCheckin > :cutoff")
    List<KubernetesClusterConfig> findActiveAgents(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Find all clusters with inactive agents (no check-in in last 15 minutes)
     */
    @Query("SELECT c FROM KubernetesClusterConfig c WHERE c.dataSourceMode IN ('agent', 'hybrid') " +
           "AND (c.lastAgentCheckin IS NULL OR c.lastAgentCheckin < :cutoff)")
    List<KubernetesClusterConfig> findInactiveAgents(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Find clusters that need scheduled sync (both kubeconfig and agent modes)
     */
    @Query("SELECT c FROM KubernetesClusterConfig c WHERE " +
           "(c.dataSourceMode = 'kubeconfig' AND c.kubeconfigYaml IS NOT NULL AND c.kubeconfigYaml <> '') " +
           "OR (c.dataSourceMode IN ('agent', 'hybrid') AND c.agentStatus = 'active')")
    List<KubernetesClusterConfig> findClustersForScheduledSync();

    /**
     * Find by cluster name (across all accounts)
     */
    List<KubernetesClusterConfig> findByClusterName(String clusterName);

    @Modifying
    @Transactional
    void deleteByCloudAccountId(Long cloudAccountId);

    // ===== CloudK8s onboarding (design §2.1, §2.2) =====

    /**
     * Resolve a cluster config by its automation-agent API key. Used by the agent
     * authenticate order after {@link #findByAgentApiKey(String)} (tier 1b, R2-4).
     */
    Optional<KubernetesClusterConfig> findByAutomationAgentApiKey(String automationAgentApiKey);

    /**
     * Serialise find-or-create for one (account, cluster) pair inside the caller's
     * transaction, without touching existing rows or needing a unique constraint
     * (design §2.2, review F-15). A Postgres transaction-scoped advisory lock keyed
     * by {@code hashtext(cloudAccountPk || '/' || clusterName)}; it is released at
     * commit/rollback automatically.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(CAST(:cloudAccountPk AS text) || '/' || :clusterName))",
           nativeQuery = true)
    void lockAccountCluster(@Param("cloudAccountPk") Long cloudAccountPk,
                            @Param("clusterName") String clusterName);

    // ===== Manual approval mode (Phase B) =====
    //
    // The only writers of the eight updatable=false columns on KubernetesClusterConfig (see the
    // entity). Each is its own statement and must be called from a @Transactional service method;
    // none is called from the agent heartbeat's wholesale save(config).

    /** Clusters whose stored approval mode is the given value (the workload proposer's selector). */
    List<KubernetesClusterConfig> findByAutomationApprovalMode(String automationApprovalMode);

    /** The cluster row under a write lock: serialises proposal upserts, claims and mode changes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM KubernetesClusterConfig c WHERE c.id = :id")
    Optional<KubernetesClusterConfig> findByIdForUpdate(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("update KubernetesClusterConfig c set c.automationApprovalMode = :mode, "
            + "c.approvalModeChangedAt = :at, c.approvalModeChangedBy = :by where c.id = :id")
    int setApprovalMode(@Param("id") Long id, @Param("mode") String mode, @Param("at") Instant at,
                        @Param("by") Long by);

    @Modifying(flushAutomatically = true)
    @Query("update KubernetesClusterConfig c set c.policyServedApprovalMode = :mode, "
            + "c.policyServedAt = :at where c.id = :id")
    int recordPolicyServed(@Param("id") Long id, @Param("mode") String mode, @Param("at") Instant at);

    @Modifying(flushAutomatically = true)
    @Query("update KubernetesClusterConfig c set c.automationAgentFeatures = :features, "
            + "c.automationAgentFeaturesSeenAt = :at, c.automationAgentDrainTimeoutSeconds = :drainTimeout "
            + "where c.id = :id")
    int recordAgentFeatures(@Param("id") Long id, @Param("features") String features, @Param("at") Instant at,
                            @Param("drainTimeout") Integer drainTimeoutSeconds);
}