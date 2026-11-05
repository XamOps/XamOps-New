package com.xammer.cloud.service.automation.proposal;

import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.config.K8sReadinessKey;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ManualCapabilityDto;
import com.xammer.cloud.repository.AutomationProposalRepository;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * The per-cluster {@code manual} object of {@code /api/xamops/k8s/capabilities}:
 * {@code {mode, effective, pending, available, reason, workloadProposals}}. The frontend treats a
 * missing object as "not available", so any failure to build this falls back to that.
 *
 * <p>{@code available} means Manual can be turned on right now: the {@code manual-approval} readiness
 * key is ready, the cluster has an automation agent key, and a leader automation heartbeat reported
 * {@code manual-v1} recently. {@code reason} names the first thing missing.
 */
@Component
public class ManualCapabilityService {

    private final K8sAutomationReadinessProperties readiness;
    private final AutomationProposalRepository proposals;
    private final ProposalService proposalService;
    private final WorkloadAutoscalingProperties workloadProperties;
    private final Clock clock;

    public ManualCapabilityService(K8sAutomationReadinessProperties readiness,
                                   AutomationProposalRepository proposals,
                                   ProposalService proposalService,
                                   WorkloadAutoscalingProperties workloadProperties) {
        this(readiness, proposals, proposalService, workloadProperties, Clock.systemUTC());
    }

    ManualCapabilityService(K8sAutomationReadinessProperties readiness,
                            AutomationProposalRepository proposals,
                            ProposalService proposalService,
                            WorkloadAutoscalingProperties workloadProperties,
                            Clock clock) {
        this.readiness = readiness;
        this.proposals = proposals;
        this.proposalService = proposalService;
        this.workloadProperties = workloadProperties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ManualCapabilityDto forCluster(KubernetesClusterConfig config) {
        Instant now = clock.instant();
        String reason = null;
        if (!readiness.isReady(K8sReadinessKey.MANUAL_APPROVAL)) {
            reason = "CONTROLLER_NOT_READY";
        } else if (config.getAutomationAgentApiKey() == null || config.getAutomationAgentApiKey().isEmpty()) {
            reason = "AGENT_KEY_LEGACY";
        } else if (!proposalService.agentSupportsManual(config, now)) {
            reason = "AGENT_TOO_OLD";
        }
        int pending = (int) proposals.countByClusterConfigIdAndStatus(config.getId(), "PENDING");
        return new ManualCapabilityDto(config.getAutomationApprovalMode(), proposalService.effective(config, now),
                pending, reason == null, reason, workloadProperties.isAutoApplyPermitted());
    }
}
