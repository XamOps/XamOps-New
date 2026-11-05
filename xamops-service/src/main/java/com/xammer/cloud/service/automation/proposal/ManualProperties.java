package com.xammer.cloud.service.automation.proposal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Manual-mode timings and limits (design 3.2 TTLs, 3.5 liveness), bound from
 * {@code xamops.automation.manual.*}. Every default is the design's; nothing is hard-coded elsewhere.
 */
@Component
@ConfigurationProperties(prefix = "xamops.automation.manual")
public class ManualProperties {

    /** PENDING lifetime in minutes, by kind of proposal. */
    public static class PendingTtlMinutes {
        private int node = 120;
        private int workload = 360;
        public int getNode() { return node; }
        public void setNode(int node) { this.node = node; }
        public int getWorkload() { return workload; }
        public void setWorkload(int workload) { this.workload = workload; }
    }

    /** How long a source may stop re-sending a PENDING/REFUSED row before it is swept STALE. */
    public static class LastSeenGraceMinutes {
        private int agent = 10;
        private int backend = 45;
        public int getAgent() { return agent; }
        public void setAgent(int agent) { this.agent = agent; }
        public int getBackend() { return backend; }
        public void setBackend(int backend) { this.backend = backend; }
    }

    private PendingTtlMinutes pendingTtlMinutes = new PendingTtlMinutes();
    private LastSeenGraceMinutes lastSeenGraceMinutes = new LastSeenGraceMinutes();
    private int approvedClaimWindowMinutes = 15;
    private int dispatchLeaseMinutes = 30;
    private int dispatchLeaseMarginMinutes = 10;
    private int agentAliveMinutes = 10;
    private int agentFeaturesMaxAgeHours = 2;
    private int reproposeCooldownMinutes = 30;
    private int maxPendingPerCluster = 50;
    private int historyRetentionDays = 90;
    private int effectiveWindowSeconds = 120;
    private int workloadProposeIntervalMinutes = 15;
    private int defaultDrainTimeoutSeconds = 1200;

    public PendingTtlMinutes getPendingTtlMinutes() { return pendingTtlMinutes; }
    public void setPendingTtlMinutes(PendingTtlMinutes v) { this.pendingTtlMinutes = v; }
    public LastSeenGraceMinutes getLastSeenGraceMinutes() { return lastSeenGraceMinutes; }
    public void setLastSeenGraceMinutes(LastSeenGraceMinutes v) { this.lastSeenGraceMinutes = v; }
    public int getApprovedClaimWindowMinutes() { return approvedClaimWindowMinutes; }
    public void setApprovedClaimWindowMinutes(int v) { this.approvedClaimWindowMinutes = v; }
    public int getDispatchLeaseMinutes() { return dispatchLeaseMinutes; }
    public void setDispatchLeaseMinutes(int v) { this.dispatchLeaseMinutes = v; }
    public int getDispatchLeaseMarginMinutes() { return dispatchLeaseMarginMinutes; }
    public void setDispatchLeaseMarginMinutes(int v) { this.dispatchLeaseMarginMinutes = v; }
    public int getAgentAliveMinutes() { return agentAliveMinutes; }
    public void setAgentAliveMinutes(int v) { this.agentAliveMinutes = v; }
    public int getAgentFeaturesMaxAgeHours() { return agentFeaturesMaxAgeHours; }
    public void setAgentFeaturesMaxAgeHours(int v) { this.agentFeaturesMaxAgeHours = v; }
    public int getReproposeCooldownMinutes() { return reproposeCooldownMinutes; }
    public void setReproposeCooldownMinutes(int v) { this.reproposeCooldownMinutes = v; }
    public int getMaxPendingPerCluster() { return maxPendingPerCluster; }
    public void setMaxPendingPerCluster(int v) { this.maxPendingPerCluster = v; }
    public int getHistoryRetentionDays() { return historyRetentionDays; }
    public void setHistoryRetentionDays(int v) { this.historyRetentionDays = v; }
    public int getEffectiveWindowSeconds() { return effectiveWindowSeconds; }
    public void setEffectiveWindowSeconds(int v) { this.effectiveWindowSeconds = v; }
    public int getWorkloadProposeIntervalMinutes() { return workloadProposeIntervalMinutes; }
    public void setWorkloadProposeIntervalMinutes(int v) { this.workloadProposeIntervalMinutes = v; }
    public int getDefaultDrainTimeoutSeconds() { return defaultDrainTimeoutSeconds; }
    public void setDefaultDrainTimeoutSeconds(int v) { this.defaultDrainTimeoutSeconds = v; }

    /**
     * Effective dispatch lease for a cluster: {@code max(dispatch-lease-minutes,
     * ceil(drainTimeoutSeconds / 60) + dispatch-lease-margin-minutes)}, with the agent-reported drain
     * timeout (default 1200 s when absent), so a long {@code DRAIN_TIMEOUT_SECONDS} can never let a
     * second node action start while the first still runs.
     */
    public long effectiveLeaseMinutes(Integer agentDrainTimeoutSeconds) {
        int drain = agentDrainTimeoutSeconds == null ? defaultDrainTimeoutSeconds : agentDrainTimeoutSeconds;
        long fromDrain = (long) Math.ceil(drain / 60.0) + dispatchLeaseMarginMinutes;
        return Math.max(dispatchLeaseMinutes, fromDrain);
    }
}
