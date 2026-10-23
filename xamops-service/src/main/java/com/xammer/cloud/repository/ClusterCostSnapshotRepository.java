package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ClusterCostSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data access for {@link ClusterCostSnapshot}, always scoped by
 * {@code clusterConfigId} ({@code kubernetes_cluster_config.id}), never the
 * ambiguous {@code cloud_account_id} (audit finding B4).
 */
@Repository
public interface ClusterCostSnapshotRepository extends JpaRepository<ClusterCostSnapshot, Long> {

    /**
     * The prior-period read-back: the newest snapshot for one cluster captured at
     * or before the given cutoff instant.
     */
    Optional<ClusterCostSnapshot> findTopByClusterConfigIdAndCapturedAtLessThanEqualOrderByCapturedAtDesc(
            Long clusterConfigId, Instant cutoff);

    /** The newest snapshot overall for one cluster. */
    Optional<ClusterCostSnapshot> findTopByClusterConfigIdOrderByCapturedAtDesc(Long clusterConfigId);
}
