package com.xammer.cloud.repository;

import com.xammer.cloud.domain.LlmRoutingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LlmRoutingRuleRepository extends JpaRepository<LlmRoutingRule, Long> {
    List<LlmRoutingRule> findByClientIdOrderByPriorityAsc(Long clientId);
    List<LlmRoutingRule> findByClientIdAndEnabledTrueOrderByPriorityAsc(Long clientId);
}
