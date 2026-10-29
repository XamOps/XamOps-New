package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ClusterOnboardingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data access for {@link ClusterOnboardingEvent} (design §2.1, §2.3).
 */
@Repository
public interface ClusterOnboardingEventRepository extends JpaRepository<ClusterOnboardingEvent, Long> {

    List<ClusterOnboardingEvent> findByCloudAccountPkAndClusterNameOrderByCreatedAtDesc(
            Long cloudAccountPk, String clusterName);

    /** Event-retention batched delete (design §2.1; default 400-day cutoff). */
    @Modifying
    @Query("DELETE FROM ClusterOnboardingEvent e WHERE e.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
