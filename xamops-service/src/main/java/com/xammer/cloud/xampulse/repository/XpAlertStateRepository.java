package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpAlertState;
import com.xammer.cloud.xampulse.domain.XpAlertStateId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpAlertStateRepository extends JpaRepository<XpAlertState, XpAlertStateId> {
    List<XpAlertState> findByRuleId(Long ruleId);
    void deleteByRuleId(Long ruleId);
}
