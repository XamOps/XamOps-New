package com.xammer.cloud.repository;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * A scalar projection of the cluster's stored approval mode. A single-column JPQL query is answered
 * by a fresh SELECT and never from the first-level entity cache, which is what lets
 * {@code ApprovalModeReader} observe a mode flip committed after the caller loaded its entity.
 */
@Repository
public interface ApprovalModeProjectionRepository extends JpaRepository<KubernetesClusterConfig, Long> {

    @Query("select c.automationApprovalMode from KubernetesClusterConfig c where c.id = :id")
    Optional<String> approvalMode(@Param("id") Long id);
}
