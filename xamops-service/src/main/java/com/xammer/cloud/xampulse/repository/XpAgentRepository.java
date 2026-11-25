package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpAgent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpAgentRepository extends JpaRepository<XpAgent, Long> {
    List<XpAgent> findByCloudAccountId(Long cloudAccountId);
    Optional<XpAgent> findByCloudAccountIdAndInstanceId(Long cloudAccountId, String instanceId);
}
