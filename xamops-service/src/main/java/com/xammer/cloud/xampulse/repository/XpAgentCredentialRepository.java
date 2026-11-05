package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpAgentCredential;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpAgentCredentialRepository extends JpaRepository<XpAgentCredential, Long> {
    List<XpAgentCredential> findByAgentId(Long agentId);
    Optional<XpAgentCredential> findByRefreshHash(String refreshHash);
}
