package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpAuditRepository extends JpaRepository<XpAudit, Long> {
    Page<XpAudit> findByCloudAccountIdOrderByTsDesc(Long cloudAccountId, Pageable pageable);
}
