package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.List;

@Repository
public interface KubernetesClusterRepository extends JpaRepository<KubernetesCluster, Long> {
    // Keep existing
    List<KubernetesCluster> findByCloudAccount(CloudAccount cloudAccount);

    Optional<KubernetesCluster> findByCloudAccountAndClusterName(CloudAccount cloudAccount, String clusterName);

    // ✅ RENAMED: Use underscore for explicit property traversal
    Optional<KubernetesCluster> findByCloudAccount_IdAndClusterName(Long cloudAccountId, String clusterName);

    // ✅ ADDED: For debugging/logging purposes
    List<KubernetesCluster> findByCloudAccountId(Long cloudAccountId);
}