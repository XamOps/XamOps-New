package com.xammer.cloud.repository;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}