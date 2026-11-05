package com.xammer.cloud.service.envallocation;

import com.xammer.cloud.domain.envallocation.EnvAllocationAudit;
import com.xammer.cloud.repository.envallocation.EnvAllocationAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The trail an auditor reads: every change to environments and rules, every publish, run, lock
 * and export.
 *
 * <p>Written in the caller's transaction, so a change and its audit entry commit or roll back
 * together. A change that could land without its entry would make the trail incomplete in exactly
 * the case it exists for.
 */
@Service
public class EnvAllocationAuditService {

    private static final Logger logger = LoggerFactory.getLogger(EnvAllocationAuditService.class);

    private final EnvAllocationAuditRepository repository;

    public EnvAllocationAuditService(EnvAllocationAuditRepository repository) {
        this.repository = repository;
    }

    public void record(Long clientId, String actor, String action, String entityType,
                       String entityRef, String detail) {
        EnvAllocationAudit entry = new EnvAllocationAudit();
        entry.setClientId(clientId);
        entry.setActor(actor);
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityRef(entityRef);
        entry.setDetail(detail);
        entry.setCreatedAt(LocalDateTime.now());
        repository.save(entry);
        logger.info("[EnvAllocation] client={} actor={} {} {} {}", clientId, actor, action,
                entityType, entityRef);
    }

    public List<EnvAllocationAudit> recent(Long clientId) {
        return repository.findTop200ByClientIdOrderByCreatedAtDesc(clientId);
    }
}
