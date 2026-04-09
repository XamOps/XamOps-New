package com.xammer.cloud.repository;

import com.xammer.cloud.domain.LlmBudgetAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LlmBudgetAlertRepository extends JpaRepository<LlmBudgetAlert, Long> {

    List<LlmBudgetAlert> findByClientIdOrderByTriggeredAtDesc(Long clientId);

    boolean existsByBudgetIdAndThresholdPctAndBudgetMonth(
        Long budgetId, Integer thresholdPct, String budgetMonth);

    List<LlmBudgetAlert> findTop20ByClientIdOrderByTriggeredAtDesc(Long clientId);

    List<LlmBudgetAlert> findByBudgetIdOrderByTriggeredAtDesc(Long budgetId);

    void deleteByBudgetId(Long budgetId);
}
