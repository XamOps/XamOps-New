package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudsitterAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CloudsitterAuditLogRepository extends JpaRepository<CloudsitterAuditLog, Long> {

    Page<CloudsitterAuditLog> findByAccountIdOrderByTimestampDesc(String accountId, Pageable pageable);

    List<CloudsitterAuditLog> findTop50ByInstanceIdOrderByTimestampDesc(String instanceId);
}
