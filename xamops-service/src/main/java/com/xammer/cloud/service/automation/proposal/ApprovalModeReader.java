package com.xammer.cloud.service.automation.proposal;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.ApprovalModeProjectionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fail-closed read of a cluster's stored approval mode (design 3.3 layer 3).
 *
 * <p>Each call runs in its own {@code REQUIRES_NEW} read transaction and queries the column
 * directly, so it always sees the latest committed value regardless of the caller's transaction,
 * isolation level or already-loaded (possibly stale) entity. Both overloads carry their own
 * annotation and query the repository themselves: a same-class call would bypass the Spring proxy and
 * silently join the caller's transaction. Neither reads {@code config.getAutomationApprovalMode()}.
 *
 * <p>Anything other than exactly {@code "AUTONOMOUS"} (a missing row included) is not autonomous.
 */
@Component
public class ApprovalModeReader {

    public static final String AUTONOMOUS = "AUTONOMOUS";
    public static final String MANUAL = "MANUAL";

    private final ApprovalModeProjectionRepository repository;

    public ApprovalModeReader(ApprovalModeProjectionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isAutonomous(Long clusterConfigId) {
        if (clusterConfigId == null) {
            return false;
        }
        return AUTONOMOUS.equals(repository.approvalMode(clusterConfigId).orElse(null));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isAutonomous(KubernetesClusterConfig config) {
        if (config == null || config.getId() == null) {
            return false;
        }
        return AUTONOMOUS.equals(repository.approvalMode(config.getId()).orElse(null));
    }

    /** The stored mode, or null when the row is missing. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public String storedMode(Long clusterConfigId) {
        return clusterConfigId == null ? null : repository.approvalMode(clusterConfigId).orElse(null);
    }
}
