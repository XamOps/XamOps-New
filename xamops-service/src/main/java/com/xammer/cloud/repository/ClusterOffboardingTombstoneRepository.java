package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ClusterOffboardingTombstone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data access for {@link ClusterOffboardingTombstone} (design §2.1, §2.2).
 */
@Repository
public interface ClusterOffboardingTombstoneRepository extends JpaRepository<ClusterOffboardingTombstone, Long> {

    Optional<ClusterOffboardingTombstone> findByCloudAccountPkAndClusterName(Long cloudAccountPk, String clusterName);

    boolean existsByCloudAccountPkAndClusterName(Long cloudAccountPk, String clusterName);

    /** A new token clears the tombstone (onboarding pre-registration; design §2.2). */
    @Modifying
    @Query("DELETE FROM ClusterOffboardingTombstone t " +
           "WHERE t.cloudAccountPk = :cloudAccountPk AND t.clusterName = :clusterName")
    int clear(@Param("cloudAccountPk") Long cloudAccountPk, @Param("clusterName") String clusterName);
}
