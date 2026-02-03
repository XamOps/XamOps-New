package com.xammer.cloud.repository;

import com.xammer.cloud.domain.EksDashboardCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for EKS dashboard cache operations.
 */
@Repository
public interface EksDashboardCacheRepository extends JpaRepository<EksDashboardCache, Long> {

    /**
     * Find cached dashboard by cluster name, account ID, and region.
     */
    Optional<EksDashboardCache> findByClusterNameAndAccountIdAndRegion(
            String clusterName, String accountId, String region);

    /**
     * Find all cached dashboards for an account.
     */
    List<EksDashboardCache> findByAccountId(String accountId);

    /**
     * Find all cached dashboards for a specific cluster name (across accounts).
     */
    List<EksDashboardCache> findByClusterName(String clusterName);

    /**
     * Delete cached dashboard by cluster name, account ID, and region.
     */
    void deleteByClusterNameAndAccountIdAndRegion(String clusterName, String accountId, String region);

    /**
     * Check if cache exists for a cluster.
     */
    boolean existsByClusterNameAndAccountIdAndRegion(String clusterName, String accountId, String region);
}
