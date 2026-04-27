package com.xammer.cloud.repository;

import com.xammer.cloud.domain.IdentityAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IdentityAuditLogRepository extends JpaRepository<IdentityAuditLog, Long> {

    Page<IdentityAuditLog> findByClientIdOrderByTimestampDesc(Long clientId, Pageable pageable);
}
