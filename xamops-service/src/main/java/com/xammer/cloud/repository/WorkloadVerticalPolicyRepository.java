package com.xammer.cloud.repository;

import com.xammer.cloud.domain.WorkloadVerticalPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Per-workload vertical-rightsizing policies.
 *
 * <p>Every lookup is scoped by {@code clusterConfigId}, which is
 * {@code kubernetes_cluster_config.id} — a surrogate key, never the ambiguous
 * {@code cloud_account_id}. Callers resolve the cluster config from an account the caller is
 * entitled to before coming here, so a query cannot reach another tenant's row by construction.
 */
@Repository
public interface WorkloadVerticalPolicyRepository extends JpaRepository<WorkloadVerticalPolicy, Long> {

    Optional<WorkloadVerticalPolicy> findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
            Long clusterConfigId, String namespace, String workloadKind, String workloadName);

    List<WorkloadVerticalPolicy> findByClusterConfigId(Long clusterConfigId);

    /**
     * Belt-and-braces variant for any path that has the tenant to hand: the unique constraint is on
     * the cluster scope, and this adds the denormalised {@code client_id} to the predicate so a
     * mismatched pair returns nothing rather than another tenant's policy.
     */
    List<WorkloadVerticalPolicy> findByClientIdAndClusterConfigId(Long clientId, Long clusterConfigId);
}
