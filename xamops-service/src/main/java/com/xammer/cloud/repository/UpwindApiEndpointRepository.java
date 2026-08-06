package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindApiEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UpwindApiEndpointRepository extends JpaRepository<UpwindApiEndpoint, Long> {

    Optional<UpwindApiEndpoint> findByUpwindId(String upwindId);

    /** Tenant-filtered read for the APIs tab. */
    List<UpwindApiEndpoint> findByCloudAccountIdInAndStatusOrderByUriAsc(
            List<String> accountIds, String status);

    /** Endpoints served by one resource — powers the unified resource view. */
    List<UpwindApiEndpoint> findByCloudAccountIdInAndResourceIdAndStatusOrderByUriAsc(
            List<String> accountIds, String resourceId, String status);

    /** Accounts Upwind has endpoint data for — used to detect an empty first sync. */
    @Query("SELECT COUNT(e) FROM UpwindApiEndpoint e WHERE e.cloudAccountId = :accountId")
    long countByAccount(@Param("accountId") String accountId);

    /**
     * Archives endpoints not seen in the latest sync of that account. Scoped per account
     * because the fetch is per account — a global sweep would archive other accounts'
     * endpoints that simply weren't part of this run.
     */
    /** Live rows for one account, for the archive safety guard. */
    @Query("SELECT COUNT(e) FROM UpwindApiEndpoint e " +
           "WHERE e.cloudAccountId = :accountId AND e.status = 'ACTIVE'")
    long countActiveByAccount(@Param("accountId") String accountId);

    @Transactional
    @Modifying
    @Query("UPDATE UpwindApiEndpoint e SET e.status = 'RESOLVED' " +
           "WHERE e.cloudAccountId = :accountId AND e.syncedAt < :runStart AND e.status <> 'RESOLVED'")
    int markResolvedNotSyncedSince(@Param("accountId") String accountId,
                                   @Param("runStart") Instant runStart);
}
