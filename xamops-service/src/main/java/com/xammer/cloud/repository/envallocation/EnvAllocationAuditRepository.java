package com.xammer.cloud.repository.envallocation;

import com.xammer.cloud.domain.envallocation.EnvAllocationAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EnvAllocationAuditRepository extends JpaRepository<EnvAllocationAudit, Long> {

    List<EnvAllocationAudit> findTop200ByClientIdOrderByCreatedAtDesc(Long clientId);
}
