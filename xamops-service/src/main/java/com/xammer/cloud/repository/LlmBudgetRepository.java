package com.xammer.cloud.repository;

import com.xammer.cloud.domain.LlmBudget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LlmBudgetRepository extends JpaRepository<LlmBudget, Long> {

    List<LlmBudget> findByClientIdOrderByCreatedAtDesc(Long clientId);

    List<LlmBudget> findByClientIdAndStatus(Long clientId, String status);

    Optional<LlmBudget> findByClientIdAndProviderConfigId(Long clientId, Long providerConfigId);

    @Query("SELECT b FROM LlmBudget b WHERE b.clientId = :clientId AND b.providerConfigId IS NULL")
    Optional<LlmBudget> findGlobalBudget(@Param("clientId") Long clientId);

    @Query("SELECT b FROM LlmBudget b WHERE b.clientId = :clientId " +
           "AND (b.providerConfigId = :providerConfigId OR b.providerConfigId IS NULL) " +
           "AND b.status = 'ACTIVE' ORDER BY b.providerConfigId DESC NULLS LAST")
    List<LlmBudget> findActiveBudgetsForProvider(
        @Param("clientId") Long clientId,
        @Param("providerConfigId") Long providerConfigId);

    List<LlmBudget> findByStatus(String status);
}
