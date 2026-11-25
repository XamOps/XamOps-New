package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpAlertRule;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpAlertRuleRepository extends JpaRepository<XpAlertRule, Long> {
    List<XpAlertRule> findByCloudAccountIdIn(Collection<Long> cloudAccountIds);
    Optional<XpAlertRule> findByIdAndCloudAccountId(Long id, Long cloudAccountId);
    void deleteByIdAndCloudAccountId(Long id, Long cloudAccountId);
    /** System-wide scan for the evaluator; each rule is then evaluated strictly inside its own tenant. */
    @Query("select r from XpAlertRule r where r.enabled = true")
    List<XpAlertRule> findAllEnabledForScheduler();
    Optional<XpAlertRule> findByIdAndCloudAccountIdIn(Long id, Collection<Long> cloudAccountIds);
    List<XpAlertRule> findByCloudAccountId(Long cloudAccountId);
}
