package com.xammer.cloud.repository;

import com.xammer.cloud.domain.WorkloadVerticalAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Durable auto-apply / manual-apply action rows.
 *
 * <p>Every lookup is scoped by {@code clusterConfigId}, which is
 * {@code kubernetes_cluster_config.id} — a surrogate key, never the ambiguous
 * {@code cloud_account_id}. Callers resolve the cluster config from an account the caller is
 * entitled to before coming here, so a query cannot reach another tenant's row by construction.
 */
@Repository
public interface WorkloadVerticalActionRepository extends JpaRepository<WorkloadVerticalAction, Long> {

    /** The newest action for one workload — what endpoint 1 reports as {@code lastAction}. */
    Optional<WorkloadVerticalAction>
    findTopByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadNameOrderByAppliedAtDesc(
            Long clusterConfigId, String namespace, String workloadKind, String workloadName);

    /**
     * Every action for one cluster, newest first, for the read endpoint's single pass over a
     * cluster's workloads: the caller keeps the first row it sees per workload key.
     */
    List<WorkloadVerticalAction> findByClusterConfigIdOrderByAppliedAtDesc(Long clusterConfigId);
}
