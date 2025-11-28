package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KubernetesClusterRepository extends JpaRepository<KubernetesCluster, Long> {

    List<KubernetesCluster> findByCloudAccountId(Long cloudAccountId);

    // ✅ ADDED: This method was missing and caused the compilation error
    Optional<KubernetesCluster> findByCloudAccountAndClusterName(CloudAccount cloudAccount, String clusterName);
}