package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpAccountConfigRepository extends JpaRepository<XpAccountConfig, Long> {
    Optional<XpAccountConfig> findByCloudAccountId(Long cloudAccountId);
    List<XpAccountConfig> findByCloudAccountIdIn(Collection<Long> cloudAccountIds);
}
