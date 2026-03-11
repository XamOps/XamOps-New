package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AksDashboardCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AksDashboardCacheRepository extends JpaRepository<AksDashboardCache, Long> {

    Optional<AksDashboardCache> findByClusterNameAndSubscriptionIdAndResourceGroup(
            String clusterName, String subscriptionId, String resourceGroup);

    List<AksDashboardCache> findBySubscriptionId(String subscriptionId);

    void deleteByClusterNameAndSubscriptionIdAndResourceGroup(
            String clusterName, String subscriptionId, String resourceGroup);

    boolean existsByClusterNameAndSubscriptionIdAndResourceGroup(
            String clusterName, String subscriptionId, String resourceGroup);
}
