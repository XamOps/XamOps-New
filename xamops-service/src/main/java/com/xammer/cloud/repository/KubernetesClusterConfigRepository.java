package com.xammer.cloud.repository;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}